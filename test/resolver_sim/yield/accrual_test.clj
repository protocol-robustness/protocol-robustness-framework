(ns resolver-sim.yield.accrual-test
  "Tests for the yield accrual decision layer and its 7 short circuits."
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.accrual :as accrual]
            [resolver-sim.yield.exact-math :as m]
            [resolver-sim.yield.position :as pos]
            [resolver-sim.yield.risk-monitor :as risk]
            [resolver-sim.time.context :as time-ctx]))

(def base-world
  {:yield/indices {:test-mod {"USDC" 1}}
   :yield/rates {:test-mod {"USDC" 0.05}}
   :yield/held-balances {"USDC" 1000000}
   :yield/risk {:test-mod {"USDC" {:liquidity-mode :available
                                   :loss-mode :none}}}
   :yield/module-status {:test-mod :active}
   :block-time 1000})

(def base-position
  {:owner/id "user1"
   :module/id :test-mod
   :token "USDC"
   :principal 10000
   :shares (m/ratio 10000)
   :entry-index (m/ratio 1)
   :status :active
   :realized-yield 0
   :unrealized-yield 0
   :deferred-yield 0
   :haircut-yield 0
   :principal-impairment 0
   :accrual-dust-remainder 0
   :shortfall-affected? false
   :oracle-stale-affected? false
   :partial-fill-affected? false
   :capital-event-affected? false
   :last-accrual-time nil
   :last-accrual-index nil})

(defn- world-with-position
  ([] (world-with-position base-world base-position))
  ([world pos]
   (assoc-in world [:yield/positions "user1"] pos)))

(defn- decision-for
  ([world pos dt]
   (accrual/accrual-decision world {:module-id :test-mod
                                    :token "USDC"
                                    :position-id "user1"
                                    :now 1000
                                    :dt dt}))
  ([dt]
   (accrual/accrual-decision (world-with-position) {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt dt})))

(deftest test-normal-positive-accrual
  (testing "Normal positive accrual with 5% APY over one year"
    (let [dt 31536000
          decision (decision-for dt)]
      (is (= :normal (:accrual-mode decision))
          "Accrual mode should be :normal")
      (is (empty? (:short-circuits decision))
          "No short circuits should trigger")
      (is (> (:final-accrual-delta decision) 0)
          "Accrual delta should be positive")
      (is (= 500 (:base-apy-bps decision))
          "5% APY = 500 bps"))))

(deftest test-apply-normal-accrual
  (testing "apply-accrual-decision updates world correctly for normal accrual"
    (let [dt 31536000
          world (world-with-position)
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt dt})
          world' (accrual/apply-accrual-decision world decision)
          pos' (get-in world' [:yield/positions "user1"])]
      (is (> (:unrealized-yield pos' 0) 0))
      (is (> (:current-index pos' 0) 1))
      (is (= 1000 (:last-accrual-time pos'))))))

(deftest test-dust-threshold-accrual
  (testing "Dust-threshold: very small dt produces zero final delta"
    (let [dt 1
          decision (decision-for dt)]
      (is (= :dust-threshold (:accrual-mode decision)))
      (is (contains? (set (:short-circuits decision)) :dust-threshold))
      (is (zero? (:final-accrual-delta decision))))))

(deftest test-dust-threshold-preserves-exact-remainder
  (testing "Dust accumulator carries sub-unit accrual forward"
    (let [world (world-with-position)
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 1})
          world' (accrual/apply-accrual-decision world decision)
          dust-after (get-in world' [:yield/positions "user1" :accrual-dust-remainder])]
      (is (ratio? dust-after))
      (is (> (double dust-after) 0) "Dust should be carried forward"))))

(deftest test-frozen-module-zero-accrual
  (testing "Frozen module produces zero accrual"
    (let [world (-> (world-with-position)
                    (assoc-in [:yield/module-status :test-mod] :frozen))
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})]
      (is (contains? (set (:short-circuits decision)) :module-frozen-zero-accrual))
      (is (= 0 (:effective-apy-bps decision)))
      (is (= (:previous-index decision) (:final-index decision)))
      (is (zero? (:final-accrual-delta decision))))))

(deftest test-unwinding-position-no-accrual
  (testing "Unwinding position suspends accrual"
    (let [pos (assoc base-position :status :unwinding)
          world (world-with-position base-world pos)
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})]
      (is (contains? (set (:short-circuits decision)) :position-unwinding-accrual-suspended))
      (is (zero? (:final-accrual-delta decision))))))

(deftest test-stale-oracle-degradation
  (testing "Stale oracle degrades APY"
    (let [world (-> (world-with-position)
                    (assoc-in [:yield/risk :test-mod "USDC" :failure-modes] #{:oracle-stale})
                    (assoc-in [:yield/risk :test-mod "USDC" :oracle-stale-seconds] 43200))
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})]
      (is (contains? (set (:short-circuits decision)) :stale-oracle-degraded-apy))
      (is (< (:effective-apy-bps decision) (:base-apy-bps decision))
          (str "Effective APY " (:effective-apy-bps decision)
               " should be less than base APY " (:base-apy-bps decision))))))

(deftest test-stale-oracle-negative-apy-unchanged
  (testing "Stale oracle does not make negative APY less conservative"
    (let [world (-> (world-with-position)
                    (assoc-in [:yield/rates :test-mod "USDC"] -0.05)
                    (assoc-in [:yield/risk :test-mod "USDC" :failure-modes] #{:oracle-stale})
                    (assoc-in [:yield/risk :test-mod "USDC" :oracle-stale-seconds] 43200))
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})]
      (is (contains? (set (:short-circuits decision)) :stale-oracle-degraded-apy))
      (is (= (:effective-apy-bps decision) (:base-apy-bps decision))
          "Negative APY should remain unchanged under stale oracle"))))

(deftest test-max-index-delta-cap
  (testing "Max index delta caps large APY changes"
    (let [world (world-with-position)
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt (* 10 31536000)})]
      (is (contains? (set (:short-circuits decision)) :max-index-delta-capped)
          (str "Short circuits: " (:short-circuits decision)))
      (is (not= (:attempted-index decision) (:final-index decision))
          "Final index should differ from attempted index"))))

(deftest test-max-index-delta-zero-policy
  (testing "Max index delta zero policy zeros accrual"
    (let [world (-> (world-with-position)
                    (assoc-in [:yield/accrual-config :test-mod :max-index-delta-policy] :zero))
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt (* 10 31536000)})]
      (is (contains? (set (:short-circuits decision)) :max-index-delta-zeroed))
      (is (= (:previous-index decision) (:final-index decision))))))

(deftest test-negative-yield-floor-capital-event
  (testing "Negative yield floor breach classifies as capital event"
    (let [world (-> (world-with-position (assoc-in base-world [:yield/rates :test-mod "USDC"] -1.0)
                                         base-position)
                    (assoc-in [:yield/risk :test-mod "USDC" :negative-yield-floor] 0)
                    (assoc-in [:yield/accrual-config :test-mod :max-index-delta-ratio] 2))
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})
          world' (accrual/apply-accrual-decision world decision)
          pos' (get-in world' [:yield/positions "user1"])]
      (is (= :capital-event (:accrual-mode decision))
          (str "Accrual mode: " (:accrual-mode decision)
               " short-circuits: " (:short-circuits decision)))
      (is (true? (:capital-event-affected? pos'))
          "capital-event-affected? must be set when the negative-yield-floor short circuit fires"))))

(deftest test-recoverable-liquidity-cap
  (testing "Recoverable liquidity cap creates unrealized excess"
    (let [pos-with-yield (assoc base-position :unrealized-yield 500)
          ;; held-balances must exceed module liabilities (principal=10000) to have
          ;; net-solvent space, but not enough to cover the full 10000 new accrual.
          ;; net-solvent = 15000 - 10000 = 5000 < projected-total = 500 + 10000 = 10500
          world (-> (world-with-position base-world pos-with-yield)
                    (assoc-in [:yield/held-balances "USDC"] 15000)
                    (assoc-in [:yield/accrual-config :test-mod :max-index-delta-ratio] 2)
                    (assoc :yield/rates {:test-mod {"USDC" 1.0}}))
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})]
      (is (contains? (set (:short-circuits decision)) :recoverable-liquidity-cap)
          (str "Short circuits: " (:short-circuits decision)))
      (is (> (:deferred-yield-delta decision) 0)
          "Excess yield should be classified as deferred"))))

(deftest test-no-recoverable-cap-when-adequate-liquidity
  (testing "No recoverable cap applied when liquidity is adequate"
    (let [world (-> (world-with-position)
                    (assoc-in [:yield/held-balances "USDC"] 1000000))
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})]
      (is (= :normal (:accrual-mode decision))
          (str "Short circuits: " (:short-circuits decision))))))

(deftest test-multi-short-circuit-interaction
  (testing "Multiple short circuits can trigger together"
    (let [pos-with-yield (assoc base-position :unrealized-yield 500)
          ;; held=11000 > principal=10000 gives net-solvent=1000 < projected=5500
          world (-> (world-with-position base-world pos-with-yield)
                    (assoc-in [:yield/risk :test-mod "USDC" :failure-modes] #{:oracle-stale})
                    (assoc-in [:yield/risk :test-mod "USDC" :oracle-stale-seconds] 43200)
                    (assoc-in [:yield/accrual-config :test-mod :max-index-delta-ratio] 2)
                    (assoc-in [:yield/held-balances "USDC"] 11000)
                    (assoc :yield/rates {:test-mod {"USDC" 1.0}}))
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})]
      (is (contains? (set (:short-circuits decision)) :stale-oracle-degraded-apy))
      (is (contains? (set (:short-circuits decision)) :recoverable-liquidity-cap))
      (is (< (:effective-apy-bps decision) 10000)
          "Effective APY should be degraded by stale oracle"))))

(deftest test-evidence-map-present
  (testing "Every decision includes a complete evidence map"
    (let [decision (decision-for 31536000)]
      (is (map? (:evidence decision)))
      (is (contains? (:evidence decision) :schema-version))
      (is (contains? (:evidence decision) :base-apy-bps))
      (is (contains? (:evidence decision) :effective-apy-bps))
      (is (contains? (:evidence decision) :previous-index))
      (is (contains? (:evidence decision) :final-index))
      (is (contains? (:evidence decision) :short-circuits))
      (is (contains? (:evidence decision) :accrual-mode)))))

(deftest test-apply-accrual-decision-position-fields
  (testing "apply-accrual-decision correctly updates all position fields"
    (let [world (world-with-position)
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})
          world' (accrual/apply-accrual-decision world decision)
          pos' (get-in world' [:yield/positions "user1"])]
      (is (contains? pos' :current-index))
      (is (contains? pos' :current-value))
      (is (contains? pos' :unrealized-yield))
      (is (contains? pos' :deferred-yield))
      (is (contains? pos' :haircut-yield))
      (is (contains? pos' :principal-impairment))
      (is (contains? pos' :accrual-dust-remainder))
      (is (contains? pos' :shortfall-affected?))
      (is (contains? pos' :oracle-stale-affected?))
      (is (contains? pos' :partial-fill-affected?))
      (is (contains? pos' :capital-event-affected?))
      (is (contains? pos' :last-accrual-time))
      (is (contains? pos' :last-accrual-index)))))

(deftest test-no-position-accrual-still-updates-index
  (testing "Module-level index update happens even without position"
    (let [world base-world
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :now 1000
                                                    :dt 31536000})
          world' (accrual/apply-accrual-decision world decision)
          new-index (get-in world' [:yield/indices :test-mod "USDC"])]
      (is (> (double new-index) 1.0)
          "Index should advance even without a position to accrue against"))))

(deftest test-projection-fields
  (testing "Projection fields are computable from a position"
    (let [world (world-with-position)
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})
          world' (accrual/apply-accrual-decision world decision)
          pos' (get-in world' [:yield/positions "user1"])
          proj (pos/projection-fields pos' :workflow-id "wf-1"
                                      :settlement-mode :none
                                      :accrual-mode (:accrual-mode decision)
                                      :short-circuits (:short-circuits decision))]
      (is (= "wf-1" (:workflow_id proj)))
      (is (= :test-mod (:module_id proj)))
      (is (= "USDC" (:token proj)))
      (is (= :normal (:accrual_mode proj)))
      (is (pos? (:yield_unrealized proj)))
      (is (zero? (:yield_deferred proj)))
      (is (zero? (:principal_haircut proj))))))

(deftest test-custom-stale-oracle-config
  (testing "Custom stale-oracle-max-seconds and stale-oracle-floor-bps are honored"
    (let [world (-> (world-with-position)
                    (assoc-in [:yield/accrual-config :test-mod :stale-oracle-max-seconds] 3600)
                    (assoc-in [:yield/accrual-config :test-mod :stale-oracle-floor-bps] 250)  ;; 2.5%
                    (assoc-in [:yield/risk :test-mod "USDC" :failure-modes] #{:oracle-stale})
                    (assoc-in [:yield/risk :test-mod "USDC" :oracle-stale-seconds] 7200))
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})
          evidence (:evidence decision)]
      (is (some #(= :stale-oracle-degraded-apy %) (:short-circuits evidence))
          "Oracle staleness beyond custom max-seconds should trigger degradation")
      (is (number? (:effective-apy-bps-after-degradation evidence))
          "Effective APY after degradation is present")
      (is (< (:effective-apy-bps-after-degradation evidence)
             (:base-apy-bps evidence 0))
          "Degraded APY is below base APY")
      (is (>= (:effective-apy-bps-after-degradation evidence) 0)
          "Floor APY should be non-negative (custom floor-bps = 250 = 2.5%)"))))

(deftest test-oracle-stale-affected-flag-set-on-application
  (testing "oracle-stale-affected? is set when the stale-oracle short circuit fires"
    (let [world (-> (world-with-position)
                    (assoc-in [:yield/risk :test-mod "USDC" :failure-modes] #{:oracle-stale})
                    (assoc-in [:yield/risk :test-mod "USDC" :oracle-stale-seconds] 43200))
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})
          world' (accrual/apply-accrual-decision world decision)
          pos' (get-in world' [:yield/positions "user1"])]
      (is (some #(= :stale-oracle-degraded-apy %) (:short-circuits decision)))
      (is (true? (:oracle-stale-affected? pos'))
          "oracle-stale-affected? must be set on the position"))))

(deftest test-frozen-module-suppresses-stale-oracle-degradation
  (testing "A frozen module does not record stale-oracle degradation or override zero APY"
    (let [world (-> (world-with-position)
                    (assoc-in [:yield/module-status :test-mod] :frozen)
                    (assoc-in [:yield/risk :test-mod "USDC" :failure-modes] #{:oracle-stale})
                    (assoc-in [:yield/risk :test-mod "USDC" :oracle-stale-seconds] 43200))
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})]
      (is (= :module-frozen (:accrual-mode decision)))
      (is (not-any? #(= :stale-oracle-degraded-apy %) (:short-circuits decision))
          "stale-oracle degradation must not be recorded alongside a freeze")
      (is (zero? (:effective-apy-bps decision))
          "effective APY stays zero under a frozen module")
      (is (zero? (:final-accrual-delta decision))))))

(deftest test-custom-freeze-on
  (testing "Custom freeze-on set only freezes accrual for listed statuses"
    (let [;; High enough APY to avoid dust-threshold short circuiting the freeze check
          world (-> (world-with-position)
                    (assoc-in [:yield/accumulated-guard :reset] true)
                    (assoc-in [:yield/rates :test-mod "USDC"] 0.10)
                    (assoc-in [:yield/accrual-config :test-mod :freeze-on] #{:paused}))
          ;; :frozen is NOT in the custom set, so accrual should proceed
          unfrozen (-> world
                       (assoc-in [:yield/module-status :test-mod] :frozen))
          ;; :frozen is NOT in the custom set, so accrual should proceed
          unfrozen (-> world
                       (assoc-in [:yield/module-status :test-mod] :frozen))
          unfrozen-decision (accrual/accrual-decision unfrozen {:module-id :test-mod
                                                                :token "USDC"
                                                                :position-id "user1"
                                                                :now 1000
                                                                :dt 31536000})
          ;; :paused IS in the custom set, so accrual should be blocked
          frozen (-> world
                     (assoc-in [:yield/module-status :test-mod] :paused))
          frozen-decision (accrual/accrual-decision frozen {:module-id :test-mod
                                                            :token "USDC"
                                                            :position-id "user1"
                                                            :now 1000
                                                            :dt 31536000})]
      (is (not= :module-frozen (:accrual-mode unfrozen-decision))
          ":frozen module should NOT freeze accrual when custom freeze-on excludes it")
      (is (= :module-frozen (:accrual-mode frozen-decision))
          ":paused module SHOULD freeze accrual when custom freeze-on includes it"))))

(deftest test-custom-min-accrual-delta
  (testing "Custom min-accrual-delta defers small accruals"
    (let [world (-> (world-with-position)
                    (assoc-in [:yield/rates :test-mod "USDC"] 0.001)  ;; very low APY
                    (assoc-in [:yield/accrual-config :test-mod :min-accrual-delta] 100))
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})
          world' (accrual/apply-accrual-decision world decision)
          pos' (get-in world' [:yield/positions "user1"])]
      (is (zero? (:unrealized-yield pos' 0))
          "Unrealized yield should be 0 when accrual delta is below min-accrual-delta")
      (is (some #(= :dust-threshold %) (:short-circuits decision))
          "Dust threshold short circuit should fire"))))

(deftest test-risk-monitor-captures-short-circuit-events
  (testing "Risk monitor captures frozen-module event"
    (risk/clear!)
    (let [world (-> (world-with-position)
                    (assoc-in [:yield/module-status :test-mod] :frozen))
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})]
      (accrual/apply-accrual-decision-with-attribution world decision)
      (let [events (risk/events)]
        (is (pos? (count events)) "Risk events should be captured")
        (is (some #(= :module-frozen-zero-accrual (first (:short-circuits %))) events)
            "Frozen module event should appear in risk monitor")))))

(deftest test-risk-monitor-summary
  (testing "Risk monitor summary aggregates correctly"
    (risk/clear!)
    (let [world (-> (world-with-position)
                    (assoc-in [:yield/module-status :test-mod] :frozen))
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})]
      (accrual/apply-accrual-decision-with-attribution world decision)
      (let [s (risk/summary)]
        (is (contains? s :module-frozen-zero-accrual))
        (is (= 1 (:count (get s :module-frozen-zero-accrual))))))))

(deftest test-risk-monitor-summary-buckets-each-short-circuit-type
  (testing "A multi-type short-circuit event contributes to every type's bucket"
    (risk/with-fresh-risk-context
      (swap! @#'risk/*risk-events* concat
             [{:short-circuits [:stale-oracle-degraded-apy :recoverable-liquidity-cap]
               :deferred-delta 100 :yield-delta 50 :module-id "m1" :ts 1}])
      (let [s (risk/summary)]
        (is (= 1 (get-in s [:stale-oracle-degraded-apy :count])))
        (is (= 1 (get-in s [:recoverable-liquidity-cap :count]))
            "the same event must also appear under its second short-circuit type")
        (is (= 100 (get-in s [:recoverable-liquidity-cap :total-deferred])))))))

(deftest test-make-decision-base-now-defaults-to-world-block-ts
  (testing "make-decision-base without :now falls back to the world's block-ts, not epoch 0"
    (let [world (-> (time-ctx/ensure-temporal-context {:block-time 5000})
                    (assoc-in [:yield/positions "user1"]
                              {:module/id :test-mod :token "USDC" :status :active :principal 1000}))
          decision (accrual/make-decision-base world {:module-id :test-mod
                                                      :token "USDC"
                                                      :position-id "user1"
                                                      :dt 100})]
      (is (= 5000 (:now decision)))
      (is (= 100 (:dt decision))))))

(deftest test-dust-carry-prior-reflects-position-remainder
  (testing "Evidence :dust-carry-prior records the position's prior dust, not the new carry"
    (let [pos-with-dust (assoc base-position
                               :principal 50 :shares (m/ratio 50)
                               :accrual-dust-remainder (m/ratio 0.2))
          world (world-with-position base-world pos-with-dust)
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})
          ev (:evidence decision)]
      (is (= "1" (get-in ev [:dust-carry-prior :num]))
          "prior carry should be the position's prior 0.2 remainder (num 1/5)")
      (is (= "5" (get-in ev [:dust-carry-prior :den])))
      (is (not= (:dust-carry-prior ev) (:dust-carry-after ev))
          "prior and after carry should differ when dust was consumed"))))

(deftest test-negative-fractional-loss-is-not-dropped
  (testing "Fractional negative losses floor instead of truncating toward zero"
    (let [world (-> (world-with-position)
                    (assoc-in [:yield/risk :test-mod "USDC" :loss-mode] :mark-to-market)
                    (assoc-in [:yield/rates :test-mod "USDC"] -0.0003))
          pos-shrunk (-> base-position
                         (assoc :principal 10 :shares (m/ratio 10))
                         (assoc-in [:accrual-dust-remainder] 0))
          world (assoc-in world [:yield/positions "user1"] pos-shrunk)
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})]
      (is (neg? (:unrealized-yield-delta decision))
          "a fractional loss must be recognized, not truncated to zero")
      (let [ev (:evidence decision)
            exact-num (BigInteger. (:num (:delta-exact ev)))
            exact-den (BigInteger. (:den (:delta-exact ev)))
            exact-ratio (/ exact-num exact-den)]
        (is (<= (bigint (:unrealized-yield-delta decision)) exact-ratio)
            "recognized loss must be at least as negative as the exact fractional loss (never dropped)")))))

(deftest test-negative-loss-not-swallowed-by-dust-threshold
  (testing "A negative accrual is never dropped by a configured dust threshold"
    (let [world (-> (world-with-position)
                    (assoc-in [:yield/risk :test-mod "USDC" :loss-mode] :mark-to-market)
                    (assoc-in [:yield/rates :test-mod "USDC"] -0.0001)
                    (assoc-in [:yield/accrual-config :test-mod :min-accrual-delta] 5))
          pos-shrunk (-> base-position
                         (assoc :principal 10 :shares (m/ratio 10))
                         (assoc-in [:accrual-dust-remainder] 0))
          world (assoc-in world [:yield/positions "user1"] pos-shrunk)
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})]
      (is (not= :dust-threshold (:accrual-mode decision))
          "dust threshold must not suppress a negative loss")
      (is (neg? (:final-accrual-delta decision))
          "the negative loss must be recognized"))))

(deftest test-negative-loss-preserves-prior-dust
  (testing "A negative (loss) accrual preserves already-accumulated dust instead of dropping it"
    (let [world (-> (world-with-position)
                    (assoc-in [:yield/risk :test-mod "USDC" :loss-mode] :mark-to-market)
                    (assoc-in [:yield/rates :test-mod "USDC"] -0.0001))
          pos-with-dust (-> base-position
                            (assoc :principal 10 :shares (m/ratio 10))
                            (assoc :accrual-dust-remainder (m/ratio 0.7)))
          world (assoc-in world [:yield/positions "user1"] pos-with-dust)
          decision (accrual/accrual-decision world {:module-id :test-mod
                                                    :token "USDC"
                                                    :position-id "user1"
                                                    :now 1000
                                                    :dt 31536000})
          world' (accrual/apply-accrual-decision world decision)
          pos' (get-in world' [:yield/positions "user1"])]
      (is (neg? (:unrealized-yield-delta decision))
          "a loss is recognized")
      (is (= "7" (get-in decision [:evidence :dust-carry-prior :num]))
          "prior dust is 0.7")
      (is (= "7" (get-in decision [:evidence :dust-carry-after :num]))
          "dust-carry-after preserves the prior dust through a loss")
      (is (= 7/10 (get-in pos' [:accrual-dust-remainder]))
          "position dust survives the loss instead of being reset to zero"))))


