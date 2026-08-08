(ns resolver-sim.sim.waterfall-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.sim.waterfall :as waterfall]
            [resolver-sim.stochastic.rng :as rng]))

(defn- process-events
  [{:keys [juniors seniors]} events]
  (reduce (fn [state event]
            (let [{:keys [resolvers seniors event-result]}
                  (waterfall/process-slash-event event
                                                 (:resolvers state)
                                                 (:seniors state))]
              {:resolvers resolvers
               :seniors seniors
               :events (conj (:events state) event-result)}))
          {:resolvers juniors :seniors seniors :events []}
          events))

(deftest test-calculate-slash-amount-cap
  (testing "50% per-slash cap on bond"
    (is (= 2.5 (waterfall/calculate-slash-amount 500 50)))
    (is (= 250.0 (waterfall/calculate-slash-amount 500 5000)))))

(deftest test-apply-junior-slash-cap
  (testing "junior slash enforces 50% of remaining bond per event"
    (let [resolver {:bond-remaining 500.0 :current-epoch 0}
          {:keys [actually-slashed shortage]} (waterfall/apply-junior-slash resolver 300)]
      (is (= 250.0 actually-slashed))
      (is (= 50.0 shortage)))))

(deftest test-cumulative-slashes-deplete-junior-then-senior
  (testing "repeated slashes on one junior cascade to senior coverage"
    (let [params {:n-seniors 1 :n-juniors-per-senior 1
                  :senior-bond-amount 10000 :junior-bond-amount 500
                  :utilization-factor 0.5}
          pool (waterfall/initialize-waterfall-pool params)
          events (vec (repeat 5 {:resolver-id "j0_0"
                                 :senior-id "s0"
                                 :slash-amount 200
                                 :reason :fraud
                                 :epoch 0}))
          {:keys [resolvers seniors events]} (process-events pool events)
          junior-remaining (get-in resolvers ["j0_0" :bond-remaining])
          junior-paid-total (reduce + (map :junior-paid events))
          senior-paid-total (reduce + (map :senior-paid events))]
      (is (< junior-remaining 500.0))
      (is (pos? senior-paid-total))
      (is (= (- 500.0 junior-remaining) junior-paid-total))
      (is (= (+ junior-paid-total senior-paid-total (reduce + (map :unmet-obligation events)))
             (* 5 200.0))))))

(deftest test-senior-delegation-fallback
  (testing "process-slash-event uses :senior-delegation when :senior-id omitted"
    (let [params {:n-seniors 1 :n-juniors-per-senior 1
                  :senior-bond-amount 10000 :junior-bond-amount 500
                  :utilization-factor 0.5}
          pool (waterfall/initialize-waterfall-pool params)
          event {:resolver-id "j0_0"
                 :slash-amount 600
                 :reason :fraud
                 :epoch 0}
          {:keys [resolvers seniors event-result]}
          (waterfall/process-slash-event event (:juniors pool) (:seniors pool))]
      (is (= "s0" (:senior-id event-result)))
      (is (= 250.0 (:junior-paid event-result)))
      (is (= 350.0 (:senior-paid event-result)))
      (is (= 250.0 (get-in resolvers ["j0_0" :bond-remaining])))
      (is (= 350.0 (get-in seniors ["s0" :coverage-used]))))))

(deftest test-aggregate-metrics-use-cumulative-events
  (testing "adequacy reflects senior exhaustion and unmet obligations"
    (let [params {:n-seniors 1 :n-juniors-per-senior 1
                  :senior-bond-amount 1000 :junior-bond-amount 100
                  :utilization-factor 0.5}
          pool (waterfall/initialize-waterfall-pool params)
          events (vec (repeat 10 {:resolver-id "j0_0"
                                  :senior-id "s0"
                                  :slash-amount 200
                                  :reason :fraud
                                  :epoch 0}))
          {:keys [resolvers seniors events]} (process-events pool events)
          metrics (waterfall/aggregate-waterfall-metrics resolvers seniors events)]
      (is (pos? (:total-unmet-obligation metrics)))
      (is (< (:coverage-adequacy-score metrics) 80.0)))))

;; ─────────────────────────────────────────────────────────────────────────────
;; P0: resolver-not-found / topology validation — fail closed
;; ─────────────────────────────────────────────────────────────────────────────

(deftest process-slash-event-unknown-resolver-throws
  (testing "an unknown resolver fails closed (never records a phantom slash)"
    (let [pool (waterfall/initialize-waterfall-pool {:n-seniors 1 :n-juniors-per-senior 1})
          event {:resolver-id "ghost" :senior-id "s0" :slash-amount 50 :reason :fraud :epoch 0}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (waterfall/process-slash-event event (:juniors pool) (:seniors pool)))))))

(deftest validate-pool-params-mismatched-juniors-per-senior-throws
  (testing "pool/params juniors-per-senior mismatch is caught before processing"
    ;; pool derived topology: 2 seniors, 3 juniors each (6 total) → 3 per senior.
    ;; params claim 4 per senior → mismatch.
    (let [pool (waterfall/initialize-waterfall-pool {:n-seniors 2 :n-juniors-per-senior 3})
          params {:n-seniors 2 :n-juniors-per-senior 4
                  :escrow-distribution {:type :lognormal :mean 1000 :std 300}
                  :strategy-mix {:honest 1.0}}]
      (is (thrown? clojure.lang.ExceptionInfo (waterfall/validate-pool-params pool params)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (waterfall/probabilistic-process-slash-pool (rng/make-rng 1) pool params 5))))))

(deftest validate-pool-params-mismatched-n-seniors-throws
  (testing "pool/params senior-count mismatch is caught before processing"
    (let [pool (waterfall/initialize-waterfall-pool {:n-seniors 2 :n-juniors-per-senior 3})
          params {:n-seniors 3 :n-juniors-per-senior 3
                  :escrow-distribution {:type :lognormal :mean 1000 :std 300}
                  :strategy-mix {:honest 1.0}}]
      (is (thrown? clojure.lang.ExceptionInfo (waterfall/validate-pool-params pool params)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (waterfall/probabilistic-process-slash-pool (rng/make-rng 1) pool params 5))))))

(deftest validate-pool-params-mismatched-n-juniors-uneven-throws
  (testing "a pool whose juniors are not evenly divided across seniors is rejected"
    ;; 2 seniors but only 5 juniors → uneven mapping, cannot assign a senior to
    ;; every junior.
    (let [full (waterfall/initialize-waterfall-pool {:n-seniors 2 :n-juniors-per-senior 3})
          pool {:seniors (:seniors full)
                :juniors (into {} (take 5 (:juniors full)))}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (waterfall/validate-pool-params pool {:n-seniors 2 :n-juniors-per-senior 3}))))))

(deftest validate-pool-params-consistent-ok
  (testing "a pool/params pair that matches is accepted"
    (let [pool (waterfall/initialize-waterfall-pool {:n-seniors 2 :n-juniors-per-senior 3})
          params {:n-seniors 2 :n-juniors-per-senior 3}]
      (is (= pool (waterfall/validate-pool-params pool params))))))

(deftest probabilistic-no-slash-without-state-transition
  (testing "every recorded slash event corresponds to an actual pool mutation"
    (let [rng-inst (rng/make-rng 7)
          params {:n-seniors 1 :n-juniors-per-senior 2
                  :senior-bond-amount 5000 :junior-bond-amount 500
                  :utilization-factor 0.5
                  :escrow-distribution {:type :lognormal :mean 2000 :std 300}
                  :strategy-mix {:malicious 1.0}
                  :resolver-fee-bps 150 :appeal-bond-bps 50
                  :slash-multiplier 2.5
                  :appeal-probability-if-correct 0.3 :appeal-probability-if-wrong 0.7
                  :slashing-detection-probability 0.90
                  :reversal-detection-probability 0.0
                  :timeout-slash-bps 25 :fraud-slash-bps 50}
          pool (waterfall/initialize-waterfall-pool params)
          result (waterfall/probabilistic-process-slash-pool rng-inst pool params 60)
          slashed (filter :slashed? (:events result))
          ;; A slash only counts toward totals if the pool actually paid it.
          reconciled (reduce (fn [[j s] ev]
                               [(+ j (:junior-paid ev)) (+ s (:senior-paid ev))])
                             [0.0 0.0] slashed)]
      (is (= (:total-slashed-by-junior (:metrics result)) (first reconciled)))
      (is (= (:total-slashed-by-senior (:metrics result)) (second reconciled)))
      (is (seq slashed) "the scenario must produce some slashes to be meaningful"))))

;; ─────────────────────────────────────────────────────────────────────────────
;; P1: single-source junior/senior caps + the two distinct cap layers
;; ─────────────────────────────────────────────────────────────────────────────

(deftest cap-junior-slash-per-slash-binds
  (testing "only the per-slash cap binds (50% of current bond < epoch remaining)"
    ;; initial bond 1000 → epoch budget 20% = 200.  current bond 300 → per-slash
    ;; max 50% = 150.  150 < 200, so the per-slash cap is the binding constraint.
    (let [resolver {:bond-remaining 300.0 :slash-history []}
          capped (waterfall/cap-junior-slash resolver 300 0 1000)]
      (is (= 150.0 capped)))))

(deftest cap-junior-slash-epoch-binds
  (testing "only the epoch cap binds (epoch remaining < per-slash max)"
    ;; initial bond 1000 → epoch budget 200.  current bond 2000 → per-slash max
    ;; 50% = 1000.  200 < 1000, so the epoch cap is the binding constraint.
    (let [resolver {:bond-remaining 2000.0 :slash-history []}
          capped (waterfall/cap-junior-slash resolver 5000 0 1000)]
      (is (= 200.0 capped)))))

(deftest cap-junior-slash-both-bind
  (testing "both caps bind and the minimum of the two is taken"
    ;; initial 1000 → epoch budget 200.  current bond 100 → per-slash max 50.
    ;; Requested 5000; both caps would reduce it; the per-slash (50) is the
    ;; tighter of the two, but both are active constraints.
    (let [resolver {:bond-remaining 100.0 :slash-history []}
          capped (waterfall/cap-junior-slash resolver 5000 0 1000)]
      (is (= 50.0 capped)))))

(deftest cap-junior-slash-epoch-remaining-shrinks
  (testing "the epoch cap accounts for slashes already taken this epoch"
    (let [resolver {:bond-remaining 900.0
                    :slash-history [{:epoch 0 :amount 150.0}]} ; 150 already taken
          capped (waterfall/cap-junior-slash resolver 300 0 1000)] ; budget 200 - 150 = 50
      (is (= 50.0 capped)))))

;; ─────────────────────────────────────────────────────────────────────────────
;; P2: slash-share vs genuine coverage exhaustion (monotonic distress)
;; ─────────────────────────────────────────────────────────────────────────────
;;
;; Three regimes, identical pool except senior coverage, processed until the
;; juniors deplete:
;;   A) juniors cover everything            → no senior use, no unmet
;;   B) seniors absorb the overflow         → senior share high, no unmet
;;   C) seniors exhaust, unmet accumulates  → exhaustion rises
;;
;; The invariant: coverage-exhaustion-pct must be monotonic non-decreasing
;; (regime C more distressed than A and B), while total-unmet is the direct
;; failure signal.

(defn- aggregate-regime [senior-bond slashes]
  (let [pool (waterfall/initialize-waterfall-pool
              {:n-seniors 1 :n-juniors-per-senior 1
               :senior-bond-amount senior-bond :junior-bond-amount 100
               :utilization-factor 1.0})
        events (vec (repeat slashes {:resolver-id "j0_0" :senior-id "s0"
                                     :slash-amount 80 :reason :fraud :epoch 0}))
        {:keys [resolvers seniors events]} (process-events pool events)]
    (waterfall/aggregate-waterfall-metrics resolvers seniors events)))

(deftest coverage-exhaustion-monotonicity
  (testing "coverage-exhaustion-pct rises as losses move past junior, through senior, into unmet"
    ;; Deterministic slash path (process-events): junior absorbs per-slash-capped
    ;; amounts off its 100 bond; the overflow goes to senior up to senior per-epoch
    ;; cap (10% of bond, util-factor 1.0 => coverage = bond).  5 x 80 = 400 pressure.
    ;;   A) senior 0     → junior ~96.9, no senior, unmet ~303
    ;;   B) senior 4000  → senior cap 400 covers the ~303 overflow: no unmet
    ;;   C) senior 200   → senior cap 20 covers 20, then unmet ~283
    (let [a (aggregate-regime 0 5)
          b (aggregate-regime 4000 5)
          c (aggregate-regime 200 5)
          exhaustion-a (:coverage-exhaustion-pct a)
          exhaustion-b (:coverage-exhaustion-pct b)
          exhaustion-c (:coverage-exhaustion-pct c)]
      ;; B: fully covered → no exhaustion.  C: some unmet → distressed.
      (is (= 0.0 exhaustion-b))
      (is (pos? exhaustion-c))
      (is (>= exhaustion-c exhaustion-b))
      (is (>= exhaustion-a exhaustion-c)
          "with no senior capacity the system is most distressed")
      (is (zero? (:total-unmet-obligation b)))
      (is (pos? (:total-unmet-obligation c)))
      (is (pos? (:total-unmet-obligation a))))))

(deftest senior-slash-share-is-a-mix-metric-not-saturation
  (testing "senior-slash-share-pct measures allocation mix, not exhaustion"
    ;; B: seniors absorb the whole overflow → share high, but exhaustion 0.
    (let [b (aggregate-regime 4000 5)
          c (aggregate-regime 200 5)]
      (is (> (:senior-slash-share-pct b) (:senior-slash-share-pct c))
          "as seniors exhaust (C) the covered share falls, yet C is more distressed")
      (is (= 0.0 (:coverage-exhaustion-pct b)))
      (is (pos? (:coverage-exhaustion-pct c))))))

(deftest adequacy-pct-and-exhaustion-pct-complement
  (testing ":coverage-adequacy-pct + :coverage-exhaustion-pct = 100 for positive loss pressure"
    (doseq [regime [(aggregate-regime 0 5)
                    (aggregate-regime 4000 5)
                    (aggregate-regime 200 5)
                    (aggregate-regime 4000 1)]]
      (let [pct (:coverage-adequacy-pct regime)
            exc (:coverage-exhaustion-pct regime)
            pressure (+ (:total-slashed-by-junior regime)
                        (:total-slashed-by-senior regime)
                        (:total-unmet-obligation regime))]
        (is (pos? pressure) "this regime should have positive loss pressure")
        (is (<= 0.0 pct 100.0) "bounded adequacy percentage")
        (is (== 100.0 (+ pct exc))
            (str "adequacy-pct " pct " + exhaustion-pct " exc " must sum to 100"))))))

(deftest adequacy-pct-is-bounded-where-historical-score-goes-negative
  (testing "the new bounded metric stays in [0,100] exactly where the historical
            deficit-margin score goes negative"
    ;; senior bond 200: junior ~98 + senior 120 covered, 261.6 unmet →
    ;; historical score is negative; the bounded pct must not be.
    (let [m (aggregate-regime 200 5)]
      (is (neg? (:coverage-adequacy-score m))
          "historical deficit-margin proxy goes negative past exhaustion")
      (is (<= 0.0 (:coverage-adequacy-pct m) 100.0)))))

(deftest adequacy-pct-no-events-is-fully-adequate
  (testing "no loss pressure at all ⇒ 100% adequacy, 0% exhaustion"
    (let [pool (waterfall/initialize-waterfall-pool {:n-seniors 1 :n-juniors-per-senior 1
                                                     :senior-bond-amount 1000 :junior-bond-amount 100
                                                     :utilization-factor 1.0})
          m (waterfall/aggregate-waterfall-metrics (:juniors pool) (:seniors pool) [])]
      (is (= 100.0 (:coverage-adequacy-pct m)))
      (is (= 0.0 (:coverage-exhaustion-pct m))))))

;; --- Probabilistic waterfall tests ---

(deftest test-draw-escrow-size-positive
  (testing "draw-escrow-size returns positive amounts from lognormal"
    (let [rng-inst (rng/make-rng 42)
          dist {:type :lognormal :mean 10000 :std 3000}
          sizes (repeatedly 20 #(waterfall/draw-escrow-size rng-inst dist))]
      (is (every? pos? sizes))
      (is (some #(> % 10000) sizes) "some draws should exceed the mean"))))

(deftest test-draw-strategy-in-range
  (testing "draw-strategy picks from mix and respects weights"
    (let [rng-inst (rng/make-rng 42)
          mix {:honest 0.80 :malicious 0.10 :lazy 0.05 :collusive 0.05}
          draws (repeatedly 200 #(waterfall/draw-strategy rng-inst mix))
          {:keys [honest malicious] :as counts} (frequencies draws)]
      (is (contains? counts :honest))
      (is (contains? counts :malicious))
      (is (> honest 100) "honest should be the majority"))))

(deftest test-probabilistic-process-slash-pool-returns-metrics
  (testing "probabilistic-process-slash-pool returns expected structure"
    (let [rng-inst (rng/make-rng 42)
          params {:n-seniors 1 :n-juniors-per-senior 2
                  :senior-bond-amount 5000 :junior-bond-amount 200
                  :utilization-factor 0.5
                  :escrow-distribution {:type :lognormal :mean 1000 :std 300}
                  :strategy-mix {:honest 0.80 :malicious 0.15 :lazy 0.05}
                  :resolver-fee-bps 150 :appeal-bond-bps 50
                  :slash-multiplier 2.5
                  :appeal-probability-if-correct 0.3 :appeal-probability-if-wrong 0.7
                  :slashing-detection-probability 0.50
                  :reversal-detection-probability 0.02
                  :timeout-slash-bps 25 :fraud-slash-bps 50}
          pool (waterfall/initialize-waterfall-pool params)
          result (waterfall/probabilistic-process-slash-pool rng-inst pool params 100)]
      (is (contains? result :resolvers))
      (is (contains? result :seniors))
      (is (contains? result :events))
      (is (contains? result :metrics))
      (is (= 100 (count (:events result)))))))

(deftest test-probabilistic-waterfall-per-epoch-cap
  (testing "per-epoch cap limits total slashing in a single epoch"
    (let [rng-inst (rng/make-rng 42)
          params {:n-seniors 1 :n-juniors-per-senior 1
                  :senior-bond-amount 5000 :junior-bond-amount 1000
                  :utilization-factor 0.5
                  :escrow-distribution {:type :lognormal :mean 5000 :std 100}
                  :strategy-mix {:malicious 1.0}
                  :resolver-fee-bps 150 :appeal-bond-bps 50
                  :slash-multiplier 5.0
                  :appeal-probability-if-correct 0.3 :appeal-probability-if-wrong 0.7
                  :slashing-detection-probability 1.0
                  :reversal-detection-probability 0.0
                  :timeout-slash-bps 25 :fraud-slash-bps 5000}
          pool (waterfall/initialize-waterfall-pool params)
          result (waterfall/probabilistic-process-slash-pool rng-inst pool params 50)
          metrics (:metrics result)
          total-junior-slash (:total-slashed-by-junior metrics)
          junior-bond (:junior-bond-amount params 1000)
          max-per-epoch (* junior-bond 0.20)] ;; 20% cap
      ;; Even with 100% detection and max slash rate, per-epoch cap should limit
      (is (<= total-junior-slash (* 50 max-per-epoch)) "total slash bounded by per-epoch cap")
      (is (pos? (:total-slashes metrics)) "should have some slashes"))))

(deftest test-probabilistic-vs-deterministic-semantics
  (testing "probabilistic mode produces fewer slashes than deterministic (same params)"
    (let [rng-inst (rng/make-rng 42)
          params {:n-seniors 1 :n-juniors-per-senior 3
                  :senior-bond-amount 5000 :junior-bond-amount 200
                  :utilization-factor 0.5
                  :escrow-distribution {:type :lognormal :mean 1000 :std 300}
                  :strategy-mix {:honest 0.80 :malicious 0.15 :lazy 0.05}
                  :resolver-fee-bps 150 :appeal-bond-bps 50
                  :slash-multiplier 2.5
                  :appeal-probability-if-correct 0.3 :appeal-probability-if-wrong 0.7
                  :slashing-detection-probability 0.30
                  :reversal-detection-probability 0.02
                  :timeout-slash-bps 25 :fraud-slash-bps 50}
          pool (waterfall/initialize-waterfall-pool params)
          n-trials 100
          prob-result (waterfall/probabilistic-process-slash-pool rng-inst pool params n-trials)
          ;; Deterministic would be n-trials * fraud-rate slashes at calculate-slash-amount
          det-slash-count (int (* n-trials 0.15))] ;; 15% malicious
      (is (< (:total-slashes (:metrics prob-result)) det-slash-count)
          "probabilistic slashes fewer than deterministic worst-case"))))

;; --- Private helpers should not be called directly in tests,
;; --- but draw-escrow-size and draw-strategy are useful for downstream use.

(defn -main [& _]
  (clojure.test/run-tests 'resolver-sim.sim.waterfall-test))
