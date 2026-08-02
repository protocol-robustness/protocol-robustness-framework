(ns resolver-sim.yield.lineage-conservation-test
  "Lineage-wide amount conservation verification for deferred yield positions.
   Covers repeated partial fill, claim-deferred, full fill, reversal, permanent
   write-down, tampered archived amounts, and reclaim mismatches."
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.conservation :as cons]
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

(defn- deposit-one
  [amount]
  (ll/deposit base-world test-mod {:owner/id "alice" :amount amount :token "USDC"}))

(defn- withdraw-shared-one
  [world held]
  (-> world
      (assoc-in [:total-held :USDC] held)
      (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                    :token "USDC"
                                    :allocation-mode :pro-rata})))

(defn- position-of [world]
  (get-in world [:yield/positions "alice"]))

(defn- only-archived
  "Return the single archived record of a position's deferred history."
  [position]
  (first (vals (:deferred-position-history position {}))))

;; ── Scenarios ─────────────────────────────────────────────────────────────

(deftest two-partial-fill-rounds-then-full-recovery
  (testing "original 100 = fulfilled 60 + reclaimed 40 after two rounds + claim"
    (let [w (-> (deposit-one 100)
                (withdraw-shared-one 30)
                (withdraw-shared-one 30)
                (ll/claim-deferred test-mod {:owner/id "alice"}))
          result (cons/verify-lineage-conservation (position-of w))]
      (is (= :evaluated-pass (:classification result)))
      (is (= 100 (:original-amount result)))
      (is (= 60 (:fulfilled result)))
      (is (= 0 (:active-deferred result)))
      (is (= 40 (:reversed result)))
      (is (= 0 (:written-down result)))
      (is (= 100 (:reconstructed-total result)))
      (is (empty? (:violations result))))))

(deftest partial-fill-then-claim-deferred
  (testing "original 100 = fulfilled 30 + reclaimed 70"
    (let [w (-> (deposit-one 100)
                (withdraw-shared-one 30)
                (ll/claim-deferred test-mod {:owner/id "alice"}))
          result (cons/verify-lineage-conservation (position-of w))]
      (is (= :evaluated-pass (:classification result)))
      (is (= 100 (:original-amount result)))
      (is (= 30 (:fulfilled result)))
      (is (= 0 (:active-deferred result)))
      (is (= 70 (:reversed result)))
      (is (= 100 (:reconstructed-total result))))))

(deftest full-fill-no-deferred-successor
  (testing "a lineage fully satisfied in a later round reconciles"
    (let [w (-> (deposit-one 100)
                (withdraw-shared-one 30)
                (withdraw-shared-one 100))
          result (cons/verify-lineage-conservation (position-of w))]
      (is (= :evaluated-pass (:classification result)))
      (is (= 100 (:fulfilled result)))
      (is (= 0 (:active-deferred result)))
      (is (= 100 (:reconstructed-total result))))))

(deftest full-fill-with-no-lineage-is-not-evaluated
  (testing "a plain full fill with no deferred lineage has nothing to verify"
    (let [w (-> (deposit-one 100) (withdraw-shared-one 100))
          result (cons/verify-lineage-conservation (position-of w))]
      (is (= :not-evaluated (:classification result)))
      (is (nil? (:original-amount result))))))

(deftest reversal-accounting
  (testing "a terminal reversal returns deferred principal to the owner without fulfillment"
    (let [w1 (-> (deposit-one 100) (withdraw-shared-one 30))
          active (get-in w1 [:yield/positions "alice" :deferred-position])
          ;; Represent a terminal reversal: the deferred 70 is returned to the
          ;; owner's reclaim ledger as a reversed amount rather than being
          ;; fulfilled or carried forward.
          w-reversed (-> w1
                         (update-in [:yield/positions "alice"] dissoc :deferred-position)
                         (assoc-in [:yield/positions "alice" :deferred-position-history
                                    (:position/id active)]
                                   (assoc active
                                          :position/status :closed
                                          :position/current-amount 0
                                          :position/closed-from-amount 70
                                          :position/closed-by :reversed
                                          :position/reversed-amount 70
                                          :position/pre-closure-snapshot active)))
          result (cons/verify-lineage-conservation (position-of w-reversed))]
      (is (= :evaluated-pass (:classification result)))
      (is (= 30 (:fulfilled result)))
      (is (= 70 (:reversed result)))
      (is (= 100 (:reconstructed-total result))))))

(deftest permanent-write-down-accounting
  (testing "a permanent haircut is attributed to written-down, not a violation"
    (let [w1 (-> (deposit-one 100) (withdraw-shared-one 30))
          active (get-in w1 [:yield/positions "alice" :deferred-position])
          w-write (-> w1
                      (update-in [:yield/positions "alice"] dissoc :deferred-position)
                      (assoc-in [:yield/positions "alice" :deferred-position-history
                                 (:position/id active)]
                                (assoc active
                                       :position/status :closed
                                       :position/current-amount 0
                                       :position/closed-from-amount 70
                                       :position/closed-by :write-down
                                       :position/written-down-amount 70
                                       :position/pre-closure-snapshot active)))
          result (cons/verify-lineage-conservation (position-of w-write))]
      (is (= :evaluated-pass (:classification result)))
      (is (= 30 (:fulfilled result)))
      (is (= 70 (:written-down result)))
      (is (= 100 (:reconstructed-total result))))))

(deftest tampered-archived-amount-is-evaluated-fail
  (testing "an archived closed-from-amount that disagrees with its pre-closure snapshot fails"
    (let [w2 (-> (deposit-one 100) (withdraw-shared-one 30) (withdraw-shared-one 30))
          archived (only-archived (position-of w2))
          w-tampered (-> w2
                         (assoc-in [:yield/positions "alice" :deferred-position-history
                                    (:position/id archived) :position/closed-from-amount]
                                   60))
          result (cons/verify-lineage-conservation (position-of w-tampered))]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(= :archived-amount-mismatch (:code %))
                (:violations result))))))

(deftest reclaim-amount-mismatch-is-evaluated-fail
  (testing "a claim-closed record whose reclaimed amount differs from outstanding fails"
    (let [w (-> (deposit-one 100)
                (withdraw-shared-one 30)
                (ll/claim-deferred test-mod {:owner/id "alice"}))
          archived (only-archived (position-of w))
          w-tampered (assoc-in w
                               [:yield/positions "alice"
                                :deferred-position-history
                                (:position/id archived)
                                :position/closed-reclaimed-amount]
                               69)
          result (cons/verify-lineage-conservation (position-of w-tampered))]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(= :reclaim-amount-mismatch (:code %))
                (:violations result))))))

(deftest lineage-amount-imbalance-is-evaluated-fail
  (testing "a missing disposition (unaccounted residual) surfaces as an imbalance"
    (let [w2 (-> (deposit-one 100) (withdraw-shared-one 30) (withdraw-shared-one 30))
          w-lost (update-in w2 [:yield/positions "alice"] dissoc :deferred-position)
          result (cons/verify-lineage-conservation (position-of w-lost))]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(= :lineage-amount-imbalance (:code %))
                (:violations result))))))

;; ── Lineage conservation report file-artifact ────────────────────────────

(deftest conservation-report-roundtrip
  (let [w (-> (deposit-one 100) (withdraw-shared-one 30))
        r (cons/build-conservation-report (position-of w))]
    (is (= "lineage-conservation-verification.v1" (:schema-version r)))
    (is (= :lineage-conservation-verification (:artifact/kind r)))
    (is (string? (:report/hash r)))
    (is (true? (cons/valid-conservation-report? r)))))

(deftest conservation-report-tamper-classification
  (let [w (-> (deposit-one 100) (withdraw-shared-one 30))
        r (cons/build-conservation-report (position-of w))]
    (is (false? (cons/valid-conservation-report?
                 (assoc r :classification :evaluated-fail))))))

(deftest conservation-report-tamper-hash
  (let [w (-> (deposit-one 100) (withdraw-shared-one 30))
        r (cons/build-conservation-report (position-of w))]
    (is (false? (cons/valid-conservation-report?
                 (assoc r :report/hash "sha256:forged"))))))

(deftest conservation-report-wrong-schema
  (let [w (-> (deposit-one 100) (withdraw-shared-one 30))
        r (cons/build-conservation-report (position-of w))]
    (is (false? (cons/valid-conservation-report?
                 (assoc r :schema-version "wrong.v9"))))))

(deftest conservation-report-not-evaluated-still-valid
  (let [w (-> (deposit-one 100) (withdraw-shared-one 100))
        r (cons/build-conservation-report (position-of w))]
    (is (= :not-evaluated (:classification r)))
    (is (true? (cons/valid-conservation-report? r)))))
