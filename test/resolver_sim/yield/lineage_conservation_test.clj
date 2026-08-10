(ns resolver-sim.yield.lineage-conservation-test
  "Tests for the cross-round lineage conservation invariant.

   Verifies that a shared-withdrawal lineage that survives into later liquidity
   rounds conserves its original requested entitlement: Σ realized-fill across
   rounds + terminal outstanding = original requested, with round-chain
   continuity, no cumulative overfill, and position/decision reconciliation.

   Round-1 (4 owners × 100, available 140 = 0.35 × 400):
     alice 30 (cap 30), bob 37, carol 37, dan 36; deferred 70/63/63/64.
   Round-2 (available 260, re-admitted deferred 70/63/63/64 with caps 30/40/50):
     alice 30, bob 40, carol 50, dan 64 (satisfied); deferred 40/23/13/0.
   Cumulative: 60/77/87/100, all conserving the original 100 request."
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.invariants :as inv]
            [resolver-sim.yield.modules.liquid-lending :as ll]))

(def test-mod
  (ll/make-liquid-lending-module :test-mod))

(def base-world
  {:yield/indices {:test-mod {"USDC" 1.0}}
   :yield/rates   {:test-mod {"USDC" 0.05}}
   :yield/risk    {:test-mod {"USDC" {:liquidity-mode :available
                                      :loss-mode :none}}}
   :yield/held-balances {"USDC" 1000000}
   :yield/module-status {:test-mod :active}
   :block-time 1000
   :run/id "test-run"
   :execution/id "test-execution"
   :params {:scenario-id "test-scenario"}})

(defn- deposit-owners
  [world owners amount]
  (reduce (fn [w owner]
            (ll/deposit w test-mod {:owner/id owner :amount amount :token "USDC"}))
          world
          owners))

(defn- with-shortfall-ratio
  [world ratio]
  (assoc-in world [:yield/risk :test-mod :USDC :shortfall :available-ratio] ratio))

(defn- withdraw-shared
  [world owners caps]
  (ll/withdraw-shared world test-mod
                      {:owner-ids owners
                       :token "USDC"
                       :allocation-mode :pro-rata
                       :effective-caps caps}))

(defn- round-one-world
  []
  (-> (deposit-owners base-world ["alice" "bob" "carol" "dan"] 100)
      (assoc-in [:total-held :USDC] 400)
      (with-shortfall-ratio 0.35)
      (withdraw-shared ["alice" "bob" "carol" "dan"]
                       {"alice" 30 "bob" 40 "carol" 50})))

(defn- round-two-world
  []
  (-> (round-one-world)
      (with-shortfall-ratio 1.0)
      (withdraw-shared ["alice" "bob" "carol" "dan"]
                       {"alice" 30 "bob" 40 "carol" 50})))

(defn- update-dan-round-two-filled
  "Inflate dan's round-2 fill in the committed decision (owed 64) by delta."
  [w delta]
  (update w :yield/partial-fill-decisions
          (fn [decisions]
            (update-vals decisions
                         (fn [d]
                           (let [dan-pred? (fn [row] (and (= "dan" (:key row))
                                                          (= 64 (long (:owed row 0)))))]
                             (if (some dan-pred? (get-in d [:evidence :allocation-rows]))
                               (update-in d [:evidence :allocation-rows]
                                          (fn [rows]
                                            (mapv (fn [row]
                                                    (if (dan-pred? row)
                                                      (update row :filled + delta)
                                                      row))
                                                  rows)))
                               d)))))))

(defn- alice-round-two-owed
  "Change alice's round-2 requested (owed 70) so it no longer matches the
   round-1 deferred residual."
  [w new-owed]
  (update w :yield/partial-fill-decisions
          (fn [decisions]
            (update-vals decisions
                         (fn [d]
                           (let [alice-pred? (fn [row] (and (= "alice" (:key row))
                                                            (= 70 (long (:owed row 0)))))]
                             (if (some alice-pred? (get-in d [:evidence :allocation-rows]))
                               (update-in d [:evidence :allocation-rows]
                                          (fn [rows]
                                            (mapv (fn [row]
                                                    (if (alice-pred? row)
                                                      (assoc row :owed new-owed)
                                                      row))
                                                  rows)))
                               d)))))))

(defn- duplicate-alice-round-two-row
  "Replay alice's round-2 descendant in a second allocation row of the same decision."
  [w]
  (update w :yield/partial-fill-decisions
          (fn [decisions]
            (update-vals decisions
                         (fn [d]
                           (let [alice-pred? (fn [row] (and (= "alice" (:key row))
                                                            (= 70 (long (:owed row 0)))))]
                             (if-let [row (first (filter alice-pred?
                                                         (get-in d [:evidence :allocation-rows])))]
                               (update-in d [:evidence :allocation-rows] (fnil conj []) row)
                               d)))))))

(deftest lineage-conservation-holds-on-two-round-world
  (let [w (round-two-world)]
    (is (:holds? (inv/check-withdrawal-lineage-conservation w))
        "honest two-round world conserves every lineage")
    (is (= 60 (get-in w [:yield/positions "alice" :cumulative-fulfilled])))
    (is (= 77 (get-in w [:yield/positions "bob" :cumulative-fulfilled])))
    (is (= 87 (get-in w [:yield/positions "carol" :cumulative-fulfilled])))
    (is (= 100 (get-in w [:yield/positions "dan" :cumulative-fulfilled])))
    (is (= 40 (get-in w [:yield/positions "alice" :deferred-position :position/current-amount])))
    (is (= 23 (get-in w [:yield/positions "bob" :deferred-position :position/current-amount])))
    (is (= 13 (get-in w [:yield/positions "carol" :deferred-position :position/current-amount])))
    (is (= :withdrawn (get-in w [:yield/positions "dan" :status]))
        "dan is fully satisfied in round 2")))

(deftest lineage-conservation-holds-on-single-round
  (let [w (round-one-world)]
    (is (:holds? (inv/check-withdrawal-lineage-conservation w))
        "a single liquidity-constrained round still conserves the lineage")))

(deftest lineage-conservation-vacuous-without-shared-decisions
  (is (:holds? (inv/check-withdrawal-lineage-conservation
                (deposit-owners base-world ["alice" "bob"] 100)))
      "worlds with no shared withdrawal hold vacuously"))

(defn- violation-kind
  [result kind]
  (some #(= kind (:kind %)) (:violations result)))

(deftest lineage-conservation-detects-overfill
  (let [w (update-dan-round-two-filled (round-two-world) 1)
        result (inv/check-withdrawal-lineage-conservation w)]
    (is (not (:holds? result)))
    (is (violation-kind result :resolver-sim.yield.invariants/lineage-overfill)
        "dan's cumulative fill exceeds his original 100 request")))

(deftest lineage-conservation-detects-chain-mismatch
  (let [w (alice-round-two-owed (round-two-world) 60)
        result (inv/check-withdrawal-lineage-conservation w)]
    (is (not (:holds? result)))
    (is (violation-kind result :resolver-sim.yield.invariants/round-request-chain-mismatch)
        "round-2 request no longer equals round-1 deferred residual")))

(deftest lineage-conservation-detects-replayed-descendant
  (let [w (duplicate-alice-round-two-row (round-two-world))
        result (inv/check-withdrawal-lineage-conservation w)]
    (is (not (:holds? result)))
    (is (violation-kind result :resolver-sim.yield.invariants/round-request-chain-mismatch)
        "a descendant replayed in two rows is an ambiguous chain")))

(deftest lineage-conservation-detects-terminal-mismatch
  (let [w (assoc-in (round-two-world)
                    [:yield/positions "alice" :deferred-position :position/current-amount]
                    50)
        result (inv/check-withdrawal-lineage-conservation w)]
    (is (not (:holds? result)))
    (is (violation-kind result :resolver-sim.yield.invariants/lineage-conservation-failed)
        "60 filled + 50 outstanding != 100 original request")))

(deftest lineage-conservation-detects-position-cumulative-mismatch
  (let [w (assoc-in (round-two-world)
                    [:yield/positions "bob" :cumulative-fulfilled]
                    78)
        result (inv/check-withdrawal-lineage-conservation w)]
    (is (not (:holds? result)))
    (is (violation-kind result :resolver-sim.yield.invariants/position-cumulative-mismatch)
        "position cumulative does not reconcile to the decision-derived cumulative")))

(deftest round-two-owner-order-is-deterministic
  (testing "shuffled round-2 owner order yields identical decision artifacts and lineage results"
    (let [w1 (round-one-world)
          caps {"alice" 30 "bob" 40 "carol" 50}
          w-a (-> w1 (with-shortfall-ratio 1.0) (withdraw-shared ["alice" "bob" "carol" "dan"] caps))
          w-b (-> w1 (with-shortfall-ratio 1.0) (withdraw-shared ["dan" "carol" "bob" "alice"] caps))
          hashes-a (sort (mapv :decision/hash (vals (:yield/partial-fill-decisions w-a))))
          hashes-b (sort (mapv :decision/hash (vals (:yield/partial-fill-decisions w-b))))]
      (is (= hashes-a hashes-b)
          "decision hashes are order-independent")
      (is (= (get-in w-a [:yield/positions "alice" :cumulative-fulfilled])
             (get-in w-b [:yield/positions "alice" :cumulative-fulfilled])))
      (is (= (get-in w-a [:yield/positions "dan" :status])
             (get-in w-b [:yield/positions "dan" :status])))
      (is (:holds? (inv/check-withdrawal-lineage-conservation w-a)))
      (is (:holds? (inv/check-withdrawal-lineage-conservation w-b))))))
