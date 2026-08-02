(ns resolver-sim.yield.queue-policy-test
  "Declared queue-ordering contract for shared-liquidity pro-rata settlement:
   queue domain (module/token/pool), queue key (original priority + immutable
   secondary position id), deterministic ordering, and cross-pool
   incomparability."
  (:require [clojure.test :refer :all]
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

(defn- deposit-owners [world owners amount]
  (reduce (fn [w owner]
            (ll/deposit w test-mod {:owner/id owner :amount amount :token "USDC"}))
          world
          owners))

(defn- withdraw-shared [world owners held]
  (-> world
      (assoc-in [:total-held :USDC] held)
      (ll/withdraw-shared test-mod {:owner-ids owners
                                    :token "USDC"
                                    :allocation-mode :pro-rata})))

(defn- latest-witness [world]
  (->> (:yield/partial-fill-decisions world)
       vals
       last
       :allocation/priority-witness))

;; ── Declared witness ──────────────────────────────────────────────────────

(deftest priority-witness-declares-queue-domain-and-key
  (let [w (-> (deposit-owners base-world ["alice"] 100)
              (withdraw-shared ["alice"] 30))
        witness (latest-witness w)
        row (first witness)]
    (is (= {:module-id :test-mod
            :token :USDC
            :liquidity-pool :shared-liquidity-pool}
           (:queue/domain row)))
    (is (contains? (:queue/key row) :original-priority))
    (is (contains? (:queue/key row) :secondary-position-id))
    (is (= 0 (:original-priority (:queue/key row))))))

;; ── Deterministic ordering ────────────────────────────────────────────────

(deftest same-priority-shuffled-callers-deterministic
  (testing "equal primary priorities order by immutable secondary position id"
    (let [world0 (deposit-owners base-world ["bravo" "alpha"] 100)
          w1 (-> (assoc-in world0 [:yield/positions "alpha" :original-priority] 0)
                 (withdraw-shared ["bravo" "alpha"] 30))
          w2 (-> (assoc-in world0 [:yield/positions "alpha" :original-priority] 0)
                 (withdraw-shared ["alpha" "bravo"] 30))
          k1 (mapv #(-> % :queue/key :secondary-position-id) (latest-witness w1))
          k2 (mapv #(-> % :queue/key :secondary-position-id) (latest-witness w2))]
      (is (= k1 k2) "reversed caller order yields the same canonical ordering")
      (is (= (set ["alpha" "bravo"]) (set (mapv :key (latest-witness w1))))))))

(deftest queue-key-comparator-matches-witness-order
  (let [world0 (deposit-owners base-world ["bravo" "alpha"] 100)
        w (-> (assoc-in world0 [:yield/positions "alpha" :original-priority] 0)
              (withdraw-shared ["bravo" "alpha"] 30))
        entries (latest-witness w)
        sorted (sort ll/compare-queue-entries entries)]
    (is (= (mapv :key entries) (mapv :key sorted))
        "witness order agrees with the declared queue-key comparator")))

;; ── Lineage stability ─────────────────────────────────────────────────────

(deftest same-lineage-after-repeated-deferral-keeps-secondary-position-id
  (let [w1 (-> (deposit-owners base-world ["alice"] 100)
               (withdraw-shared ["alice"] 30))
        k1 (-> (latest-witness w1) first :queue/key :secondary-position-id)
        w2 (withdraw-shared w1 ["alice"] 30)
        k2 (-> (latest-witness w2) first :queue/key :secondary-position-id)]
    (is (= k1 k2)
        "repeated deferral of the same lineage keeps the immutable position id")
    (is (= (:deferred/lineage-root
            (get-in w2 [:yield/positions "alice" :deferred-position]))
           k2)
        "secondary-position-id equals the deferred lineage root")))

;; ── Replay determinism ────────────────────────────────────────────────────

(deftest independent-replay-yields-identical-ordering
  (let [run (fn []
              (-> (deposit-owners base-world ["older" "newer"] 100)
                  (withdraw-shared ["older" "newer"] 30)
                  (withdraw-shared ["newer" "older"] 30)
                  (latest-witness)
                  (->> (mapv (fn [row]
                               [(:key row)
                                (:original-priority (:queue/key row))
                                (:secondary-position-id (:queue/key row))])))))
        a (run)
        b (run)]
    (is (= a b) "two independent replays produce the identical canonical order")))

;; ── Cross-pool incomparability ────────────────────────────────────────────

(deftest cross-pool-entries-are-incomparable
  (let [usdc (ll/queue-domain :test-mod "USDC")
        eth  (ll/queue-domain :test-mod "ETH")
        a {:queue/domain usdc
           :queue/key {:original-priority 0 :secondary-position-id "pos-a"}}
        b {:queue/domain eth
           :queue/key {:original-priority 0 :secondary-position-id "pos-b"}}]
    (is (false? (ll/same-queue-domain? a b)))
    (is (= :incomparable (ll/compare-queue-entries a b))
        "cross-pool entries are not globally ordered")))

(deftest same-pool-entries-are-ordered
  (let [dom (ll/queue-domain :test-mod "USDC")
        a {:queue/domain dom :queue/key {:original-priority 0 :secondary-position-id "aaa"}}
        b {:queue/domain dom :queue/key {:original-priority 0 :secondary-position-id "bbb"}}]
    (is (true? (ll/same-queue-domain? a b)))
    (is (neg? (ll/compare-queue-entries a b)))))
