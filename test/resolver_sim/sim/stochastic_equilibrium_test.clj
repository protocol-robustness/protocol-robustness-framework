(ns resolver-sim.sim.stochastic-equilibrium-test
  "Tests for stochastic-equilibrium claim evaluators:
   - evaluate-participation-stable (per-strategy decomposition)
   - evaluate-mech-budget-balance  (flow-conservation reconciliation)"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.sim.stochastic-equilibrium :as sut]))

;; ───────────────────────────────────────────────────────────────────────────
;; evaluate-participation-stable
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-participation-stable-malice-attrition-passes
  (testing "High malicious attrition with strong honest retention passes"
    (let [result {:initial-resolver-count 130
                  :initial-composition {:honest-count 50 :lazy-count 50
                                        :malicious-count 25 :collusive-count 5
                                        :malice-count 30 :total-count 130
                                        :honest-share 0.385 :malice-share 0.231}
                  :aggregated-stats {:total-resolver-exits 30
                                     :final-resolver-count 100
                                     :honest-exit-count 0
                                     :lazy-exit-count 0
                                     :malicious-exit-count 25
                                     :collusive-exit-count 5
                                     :honest-cumulative-profit 0.0
                                     :malice-cumulative-profit 0.0
                                     :honest-avg-win-rate 0.0
                                     :malice-avg-win-rate 0.0}}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :participation-stable (:claim-id %)) %) (:claim-results report))]
      (is (= :pass (:status claim))
          "0 honest/lazy exits out of 100 → productive rate 0% < 20%")
      (is (some? (:productive-exit-rate (:evidence claim)))
          "evidence includes :productive-exit-rate")
      (is (= 0.0 (get-in claim [:evidence :productive-exit-rate]))
          "productive-exit-rate is 0.0")
      (is (= 30 (:total-exits (:evidence claim)))
          "evidence shows 30 total exits, all malicious/collusive")
      (is (= 25 (get-in claim [:evidence :malicious-exits]))
          "25 malicious exits reported"))))

(deftest test-participation-stable-honest-attrition-fails
  (testing "High honest attrition fails even when overall exits are below 40%"
    (let [result {:initial-resolver-count 130
                  :initial-composition {:honest-count 50 :lazy-count 50
                                        :malicious-count 25 :collusive-count 5
                                        :malice-count 30 :total-count 130
                                        :honest-share 0.385 :malice-share 0.231}
                  :aggregated-stats {:total-resolver-exits 25
                                     :final-resolver-count 105
                                     :honest-exit-count 25
                                     :lazy-exit-count 0
                                     :malicious-exit-count 0
                                     :collusive-exit-count 0
                                     :honest-cumulative-profit 0.0
                                     :malice-cumulative-profit 0.0
                                     :honest-avg-win-rate 0.0
                                     :malice-avg-win-rate 0.0}}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :participation-stable (:claim-id %)) %) (:claim-results report))]
      (is (= :fail (:status claim))
          "25/100 productive exits = 25% ≥ 20%, aggregate=19.2% < 40% → still fail")
      (is (= (double 0.25) (get-in claim [:evidence :productive-exit-rate]))
          "productive-exit-rate is 0.25")
      (is (< (Math/abs (- (double (/ 25 130)) (get-in claim [:evidence :aggregate-exit-rate]))) 1e-9)
          "aggregate-exit-rate is ~0.192"))))

;; ───────────────────────────────────────────────────────────────────────────
;; evaluate-mech-budget-balance
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-budget-balance-flow-conservation-passes
  (testing "Positive resolver profit funded by explicit fees passes budget balance"
    (let [;; Flow identity: fees = resolver_net + bond_loss - fraud_upside
          ;; resolver_net = 80000 + (-10000) = 70000
          ;; 100000 - 70000 - 30000 + 0 = 0 → pass
          result {:initial-resolver-count 10
                  :initial-composition {:honest-count 5 :malice-count 5 :total-count 10
                                        :honest-share 0.5 :malice-share 0.5}
                  :epoch-results [{:dominance-ratio 1.0 :honest-mean-profit 10 :malice-mean-profit 1}]
                  :aggregated-stats {:honest-cumulative-profit 80000.0
                                     :malice-cumulative-profit -10000.0
                                     :total-resolver-exits 0
                                     :honest-final-count 5
                                     :malice-final-count 5
                                     :honest-avg-win-rate 0.7
                                     :malice-avg-win-rate 0.5
                                     :flow-total-fees-collected 100000.0
                                     :flow-total-bond-loss 30000.0
                                     :flow-total-fraud-upside 0.0}}
          report (sut/evaluate-stochastic-equilibrium result)
          mech (get-in report [:mechanism-proxy-results :budget-balance])
          residual (get-in mech [:evidence :residual])]
      (is (= :pass (:status mech))
          "flow conserved: 100000 - 70000 - 30000 + 0 = 0")
      (is (<= (Math/abs (double residual)) 1.0)
          (format "residual = %.0f (should be ~0)" residual)))))

(deftest test-budget-balance-flow-conservation-fails
  (testing "An unaccounted value creation fails budget balance"
    (let [;; Inconsistent data: fees alone suggest resolver_net should be 70000
          ;; but actual resolver_net is only 50000 (60000 + -10000)
          ;; residual = 100000 - 50000 - 30000 + 0 = 20000 ≫ 1
          result {:initial-resolver-count 10
                  :initial-composition {:honest-count 5 :malice-count 5 :total-count 10
                                        :honest-share 0.5 :malice-share 0.5}
                  :epoch-results [{:dominance-ratio 1.0 :honest-mean-profit 10 :malice-mean-profit 1}]
                  :aggregated-stats {:honest-cumulative-profit 60000.0
                                     :malice-cumulative-profit -10000.0
                                     :total-resolver-exits 0
                                     :honest-final-count 5
                                     :malice-final-count 5
                                     :honest-avg-win-rate 0.7
                                     :malice-avg-win-rate 0.5
                                     :flow-total-fees-collected 100000.0
                                     :flow-total-bond-loss 30000.0
                                     :flow-total-fraud-upside 0.0}}
          report (sut/evaluate-stochastic-equilibrium result)
          mech (get-in report [:mechanism-proxy-results :budget-balance])
          residual (get-in mech [:evidence :residual])]
      (is (= :fail (:status mech))
          "unaccounted: 100000 - 50000 - 30000 + 0 = 20000 > 1 wei → fail")
      (is (not (<= (Math/abs (double residual)) 1.0))
          (format "residual = %.0f should be ≫ 0" residual)))))

(deftest test-budget-balance-reports-all-components
  (testing "Every reported balance component is included in the reconciliation"
    (let [;; Consistent data: resolver_net = 96000 + (-40750) = 55250
          ;; 100000 - 55250 - 45000 + 250 = 0 → pass
          result {:initial-resolver-count 10
                  :initial-composition {:honest-count 5 :malice-count 5 :total-count 10
                                        :honest-share 0.5 :malice-share 0.5}
                  :epoch-results [{:dominance-ratio 1.0 :honest-mean-profit 10 :malice-mean-profit 1}]
                  :aggregated-stats {:honest-cumulative-profit 96000.0
                                     :malice-cumulative-profit -40750.0
                                     :total-resolver-exits 0
                                     :honest-final-count 5
                                     :malice-final-count 5
                                     :honest-avg-win-rate 0.7
                                     :malice-avg-win-rate 0.5
                                     :flow-total-fees-collected 100000.0
                                     :flow-total-bond-loss 45000.0
                                     :flow-total-fraud-upside 250.0}}
          report (sut/evaluate-stochastic-equilibrium result)
          mech (get-in report [:mechanism-proxy-results :budget-balance])
          ev (:evidence mech)]
      (is (= :pass (:status mech))
          "the independently balanced fixture must pass")
      (is (zero? (:residual ev))
          "the independently balanced fixture has no residual")
      (is (contains? ev :total-fees-collected) "evidence includes :total-fees-collected")
      (is (contains? ev :resolver-profit-net-sum) "evidence includes :resolver-profit-net-sum")
      (is (contains? ev :total-bond-loss) "evidence includes :total-bond-loss")
      (is (contains? ev :total-fraud-upside) "evidence includes :total-fraud-upside")
      (is (contains? ev :residual) "evidence includes :residual")
      (is (contains? ev :honest-cumulative-profit) "surplus diagnostic :honest-cumulative-profit present")
      (is (contains? ev :malice-cumulative-profit) "surplus diagnostic :malice-cumulative-profit present")
      (is (contains? ev :profit-ratio) "surplus diagnostic :profit-ratio present")
      (let [fees (:total-fees-collected ev)
            rnet (:resolver-profit-net-sum ev)
            bond (:total-bond-loss ev)
            fraud (:total-fraud-upside ev)
            residual (:residual ev)
            expected-residual (+ (- fees rnet bond) fraud)]
        (is (= (double expected-residual) (double residual))
            (format "residual = %.0f matches expected = %.0f" residual expected-residual))))))

;; ───────────────────────────────────────────────────────────────────────────
;; evaluate-mech-budget-balance — ratcheting
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-budget-balance-inconclusive-on-missing-flow-data-in-shared-world
  (testing "Shared-world mode with missing flow tracking data returns inconclusive with surplus diagnostics"
    (let [result {:initial-resolver-count 10
                  :initial-composition {:honest-count 5 :malice-count 5 :total-count 10
                                        :honest-share 0.5 :malice-share 0.5}
                  :epoch-results [{:batch-mode :shared-world :dominance-ratio 1.0
                                   :honest-mean-profit 10 :malice-mean-profit 1}]
                  :aggregated-stats {:honest-cumulative-profit 100.0
                                     :malice-cumulative-profit -50.0
                                     :total-resolver-exits 0
                                     :honest-final-count 5
                                     :malice-final-count 5
                                     :honest-avg-win-rate 0.7
                                     :malice-avg-win-rate 0.5}}
          report (sut/evaluate-stochastic-equilibrium result)
          mech (get-in report [:mechanism-proxy-results :budget-balance])]
      (is (= :inconclusive (:status mech))
          "shared-world with missing flow keys → inconclusive (not fail)")
      (is (re-find #"incomplete" (:reason mech ""))
          "reason mentions incomplete reconciliation inputs")
      (is (contains? (:evidence mech) :honest-cumulative-profit)
          "surplus diagnostic :honest-cumulative-profit is present")
      (is (contains? (:evidence mech) :malice-cumulative-profit)
          "surplus diagnostic :malice-cumulative-profit is present")
      (is (contains? (:evidence mech) :profit-ratio)
          "surplus diagnostic :profit-ratio is present"))))

;; ───────────────────────────────────────────────────────────────────────────
;; Inconclusive incapacity tests
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-participation-stable-inconclusive-missing-init-count
  (testing "Missing initial-resolver-count yields inconclusive"
    (let [result {:aggregated-stats {:total-resolver-exits 5}}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :participation-stable (:claim-id %)) %) (:claim-results report))]
      (is (= :inconclusive (:status claim))
          "no :initial-resolver-count → inconclusive")
      (is (re-find #"initial-resolver-count" (or (:detail claim) (:reason claim) ""))
          "detail mentions missing :initial-resolver-count"))))

(deftest test-budget-balance-inconclusive-missing-flow-data
  (testing "Missing flow tracking keys with non-shared-world yields inconclusive"
    (let [result {:initial-resolver-count 10
                  :initial-composition {:honest-count 5 :malice-count 5 :total-count 10
                                        :honest-share 0.5 :malice-share 0.5}
                  :aggregated-stats {:honest-cumulative-profit 100.0
                                     :malice-cumulative-profit -50.0
                                     :total-resolver-exits 0
                                     :honest-final-count 5
                                     :malice-final-count 5
                                     :honest-avg-win-rate 0.7
                                     :malice-avg-win-rate 0.5}}
          report (sut/evaluate-stochastic-equilibrium result)
          mech (get-in report [:mechanism-proxy-results :budget-balance])]
      (is (= :inconclusive (:status mech))
          "no flow keys and no shared-world → inconclusive")
      (is (re-find #"reconciliation inputs incomplete" (or (:reason mech) ""))
          "reason mentions incomplete reconciliation inputs"))))

(deftest test-malice-net-profit-negative-inconclusive
  (testing "Missing malice-cumulative-profit yields inconclusive"
    (let [result {:aggregated-stats {}}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :malice-net-profit-negative (:claim-id %)) %) (:claim-results report))]
      (is (= :inconclusive (:status claim))
          "no :malice-cumulative-profit → inconclusive"))))

(deftest test-honest-dominates-inconclusive-no-epochs
  (testing "Missing epoch-results yields inconclusive"
    (let [result {}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :honest-dominates (:claim-id %)) %) (:claim-results report))]
      (is (= :inconclusive (:status claim))
          "no :epoch-results → inconclusive"))))

(deftest test-honest-dominates-inconclusive-no-profit-data
  (testing "Final epoch with no dominance-ratio or profit data yields inconclusive"
    (let [result {:epoch-results [{}]}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :honest-dominates (:claim-id %)) %) (:claim-results report))]
      (is (= :inconclusive (:status claim))
          "final epoch missing all profit data → inconclusive"))))

(deftest test-slashing-deters-inconclusive
  (testing "Missing malice-avg-win-rate yields inconclusive"
    (let [result {:aggregated-stats {:honest-avg-win-rate 0.7}}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :slashing-deters (:claim-id %)) %) (:claim-results report))]
      (is (= :inconclusive (:status claim))
          "no :malice-avg-win-rate → inconclusive"))))

(deftest test-honest-survival-rate-inconclusive-missing-final-counts
  (testing "Missing honest-final-count yields inconclusive"
    (let [result {:aggregated-stats {}}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :honest-survival-rate (:claim-id %)) %) (:claim-results report))]
      (is (= :inconclusive (:status claim))
          "no :honest-final-count → inconclusive"))))

(deftest test-honest-survival-rate-inconclusive-missing-initial-composition
  (testing "Missing initial-composition yields inconclusive"
    (let [result {:aggregated-stats {:honest-final-count 5 :malice-final-count 5}}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :honest-survival-rate (:claim-id %)) %) (:claim-results report))]
      (is (= :inconclusive (:status claim))
          "no :initial-composition → inconclusive"))))

(deftest test-honest-survival-rate-inconclusive-zero-cohort
  (testing "Zero initial honest cohort yields inconclusive"
    (let [result {:aggregated-stats {:honest-final-count 5 :malice-final-count 5}
                  :initial-composition {:honest-count 0 :malice-count 5}}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :honest-survival-rate (:claim-id %)) %) (:claim-results report))]
      (is (= :inconclusive (:status claim))
          "zero :honest-count → inconclusive"))))

(deftest test-incentive-compatibility-inconclusive
  (testing "Missing profit data yields inconclusive for incentive-compatibility"
    (let [result {:aggregated-stats {}}
          report (sut/evaluate-stochastic-equilibrium result)
          mech (get-in report [:mechanism-proxy-results :incentive-compatibility])]
      (is (= :inconclusive (:status mech))
          "no profit/win-rate data → inconclusive"))))

(deftest test-individual-rationality-inconclusive
  (testing "Missing honest-cumulative-profit yields inconclusive for individual-rationality"
    (let [result {:aggregated-stats {}}
          report (sut/evaluate-stochastic-equilibrium result)
          mech (get-in report [:mechanism-proxy-results :individual-rationality])]
      (is (= :inconclusive (:status mech))
          "no :honest-cumulative-profit → inconclusive"))))

(deftest test-collusion-resistance-inconclusive
  (testing "Missing malice final count or initial composition yields inconclusive"
    (let [result {}  ;; neither aggregated-stats nor initial-composition
          report (sut/evaluate-stochastic-equilibrium result)
          mech (get-in report [:mechanism-proxy-results :collusion-resistance])]
      (is (= :inconclusive (:status mech))
          "no malice-final-count or malice-count → inconclusive"))))

;; ───────────────────────────────────────────────────────────────────────────
;; evaluate-honest-survival-rate
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-honest-survival-rate-passes
  (testing "Honest survival rate > malice survival rate passes"
    (let [result {:aggregated-stats {:honest-final-count 8 :malice-final-count 2}
                  :initial-composition {:honest-count 10 :malice-count 10}}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :honest-survival-rate (:claim-id %)) %) (:claim-results report))]
      (is (= :pass (:status claim))
          "honest survival 0.8 > malice survival 0.2 → pass")
      (is (= (double 0.8) (get-in claim [:evidence :honest-survival-rate]))
          "honest-survival-rate is 0.8")
      (is (= (double 0.2) (get-in claim [:evidence :malice-survival-rate]))
          "malice-survival-rate is 0.2")
      (is (< (Math/abs (- (double 0.6) (get-in claim [:evidence :survival-margin]))) 1e-9)
          "survival-margin is ~0.6"))))

(deftest test-honest-survival-rate-fails
  (testing "Honest survival rate ≤ malice survival rate fails"
    (let [result {:aggregated-stats {:honest-final-count 2 :malice-final-count 8}
                  :initial-composition {:honest-count 10 :malice-count 10}}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :honest-survival-rate (:claim-id %)) %) (:claim-results report))]
      (is (= :fail (:status claim))
          "honest survival 0.2 ≤ malice survival 0.8 → fail")
      (is (= (double 0.2) (get-in claim [:evidence :honest-survival-rate]))
          "honest-survival-rate is 0.2")
      (is (= (double 0.8) (get-in claim [:evidence :malice-survival-rate]))
          "malice-survival-rate is 0.8")
      (is (neg? (get-in claim [:evidence :survival-margin]))
          "survival-margin is negative"))))

;; ───────────────────────────────────────────────────────────────────────────
;; evaluate-strategy-adaptation-compatibility
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-strategy-adaptation-passes-with-no-blocked-events
  (testing "No blocked adaptation targets passes"
    (let [result {}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :strategy-adaptation-compatibility (:claim-id %)) %) (:claim-results report))]
      (is (= :pass (:status claim))
          "no epoch-results → no blocked events → pass")
      (is (= 0 (get-in claim [:evidence :blocked-events]))
          "blocked-events is 0"))))

(deftest test-strategy-adaptation-fails-on-blocked-with-fail-policy
  (testing "Blocked events with :fail policy fails"
    (let [result {:epoch-results [{:defection {:adaptation/resolved-config {:blocked-target-policy :fail}
                                               :diagnostics [{:reason :target-outside-strategy-space}]}}]}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :strategy-adaptation-compatibility (:claim-id %)) %) (:claim-results report))]
      (is (= :fail (:status claim))
          "blocked event with :fail policy → fail")
      (is (= 1 (get-in claim [:evidence :blocked-events]))
          "blocked-events is 1"))))

(deftest test-strategy-adaptation-warns-on-blocked-with-warn-policy
  (testing "Blocked events with :warn policy passes (warn)"
    (let [result {:epoch-results [{:defection {:adaptation/resolved-config {:blocked-target-policy :warn}
                                               :diagnostics [{:reason :target-outside-strategy-space}]}}]}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :strategy-adaptation-compatibility (:claim-id %)) %) (:claim-results report))]
      (is (= :pass (:status claim))
          "blocked event with :warn policy → pass (warn)")
      (is (= 1 (get-in claim [:evidence :blocked-events]))
          "blocked-events is 1"))))

;; ───────────────────────────────────────────────────────────────────────────
;; Participation stability — partial classified data fallback
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-participation-stable-fallback-on-partial-classified-data
  (testing "Missing some per-strategy classified keys falls back to aggregate"
    (let [result {:initial-resolver-count 100
                  :aggregated-stats {:total-resolver-exits 10
                                     :final-resolver-count 90
                                     :honest-exit-count 2
                                    ;; intentionally omit :lazy-exit-count,
                                    ;; :malicious-exit-count, :collusive-exit-count
                                     }}
          report (sut/evaluate-stochastic-equilibrium result)
          claim (some #(when (= :participation-stable (:claim-id %)) %) (:claim-results report))]
      (is (= :fallback (get-in claim [:evidence :evaluation-mode]))
          "falls back to aggregate when classified data is incomplete")
      (is (some? (get-in claim [:evidence :aggregate-exit-rate]))
          "aggregate-exit-rate is present")
      (is (< (get-in claim [:evidence :aggregate-exit-rate]) 0.40)
          "10/100=10% aggregate exit rate < 40% → pass")
      (is (= :pass (:status claim))
          "fallback with low exit rate passes"))))

;; ───────────────────────────────────────────────────────────────────────────
;; Overall status propagation
;; ───────────────────────────────────────────────────────────────────────────

(deftest overall-status-inconclusive-on-any-incapacity
  (testing "Any inconclusive claim makes overall status inconclusive"
    (let [result {:initial-resolver-count 10}
          report (sut/evaluate-stochastic-equilibrium result)]
      (is (= :inconclusive (:overall-status report))
          "overall status must be inconclusive when some claims cannot evaluate")
      (is (pos? (:inconclusive-count report))
          "inconclusive count must be positive")
      (is (some? (:coverage report))
          "coverage ratio is present")
      (is (< (:coverage report) 1.0)
          "coverage < 1.0 when some claims are inconclusive"))))

;; ───────────────────────────────────────────────────────────────────────────
;; grim-trigger stability
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-grim-trigger-stable-when-discount-high
  (testing "grim-trigger passes when discount-factor >= threshold"
    (let [result {:aggregated-stats {:honest-mean-profit 100.0
                                     :malice-mean-profit 80.0}}
          report (sut/evaluate-grim-trigger-stability result :discount-factor 0.95)]
      (is (= :pass (:status report)))
      (is (true? (:stable? report))))))

(deftest test-grim-trigger-unstable-when-discount-low
  (testing "grim-trigger fails when discount-factor < threshold"
    (let [result {:aggregated-stats {:honest-mean-profit 100.0
                                     :malice-mean-profit 150.0}}
          report (sut/evaluate-grim-trigger-stability result :discount-factor 0.3)]
      (is (= :fail (:status report)))
      (is (false? (:stable? report))))))

(deftest test-grim-trigger-inconclusive-with-no-profit-data
  (testing "grim-trigger is inconclusive when profit data is absent"
    (let [result {:aggregated-stats {:honest-mean-profit 0.0
                                     :malice-mean-profit 0.0}}
          report (sut/evaluate-grim-trigger-stability result)]
      (is (= :inconclusive (:status report))))))

;; ───────────────────────────────────────────────────────────────────────────
;; Repeated-game deterrence threshold
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-repeated-game-deterrence-threshold-is-met
  (testing "the normalized-baseline threshold passes when deterrence holds"
    (let [result {:aggregated-stats {:honest-mean-profit 100.0
                                     :malice-mean-profit 80.0}}
          report (sut/evaluate-repeated-game-deterrence-threshold result)]
      (is (= :pass (:status report)))
      (is (true? (:deterrence? report))))))

(deftest test-repeated-game-deterrence-threshold-infeasible
  (testing "a threshold above one explicitly fails because no valid discount can meet it"
    (let [result {:aggregated-stats {:honest-mean-profit 10.0
                                     :malice-mean-profit 300.0}}
          report (sut/evaluate-repeated-game-deterrence-threshold result)]
      (is (= :fail (:status report)))
      (is (= :infeasible-threshold (:reason report)))
      (is (= :threshold (:binding-constraint report)))
      (is (> (:threshold report) 1.0))
      (is (false? (:deterrence? report))))))

(deftest test-repeated-game-deterrence-threshold-boundaries
  (let [result {:aggregated-stats {:honest-mean-profit 100.0
                                   :malice-mean-profit 150.0}}]
    (testing "a discount strictly above the threshold passes"
      (let [report (sut/evaluate-repeated-game-deterrence-threshold result :discount-factor 0.6)]
        (is (= :pass (:status report)))
        (is (= 0.5 (:threshold report)))
        (is (pos? (:distance-to-boundary report)))))
    (testing "equality passes"
      (let [report (sut/evaluate-repeated-game-deterrence-threshold result :discount-factor 0.5)]
        (is (= :pass (:status report)))
        (is (true? (:deterrence? report)))
        (is (zero? (:distance-to-boundary report)))))
    (testing "a discount below the threshold fails"
      (let [report (sut/evaluate-repeated-game-deterrence-threshold result :discount-factor 0.49)]
        (is (= :fail (:status report)))
        (is (neg? (:distance-to-boundary report)))))))

(deftest test-repeated-game-deterrence-validates-discount-domain
  (let [result {:aggregated-stats {:honest-mean-profit 100.0
                                   :malice-mean-profit 110.0}}]
    (doseq [discount-factor [-0.01 1.01]]
      (let [report (sut/evaluate-repeated-game-deterrence-threshold
                    result :discount-factor discount-factor)]
        (is (= :inconclusive (:status report)))
        (is (= :discount-factor-out-of-range (:reason report)))))
    (doseq [discount-factor [0.0 1.0]]
      (let [report (sut/evaluate-repeated-game-deterrence-threshold
                    result :discount-factor discount-factor)]
        (is (not= :inconclusive (:status report)))
        (is (= discount-factor (:discount-factor report)))))))

(deftest test-repeated-game-deterrence-requires-finite-utilities
  (doseq [[honest-profit malice-profit expected-reason]
          [[0.0 20.0 :non-positive-honest-utility]
           [-10.0 20.0 :non-positive-honest-utility]
           [nil 20.0 :missing-or-invalid-honest-utility]
           [Double/NEGATIVE_INFINITY 20.0 :missing-or-invalid-honest-utility]
           [100.0 nil :missing-or-invalid-malice-utility]
           [100.0 Double/POSITIVE_INFINITY :missing-or-invalid-malice-utility]]]
    (let [report (sut/evaluate-repeated-game-deterrence-threshold
                  {:aggregated-stats {:honest-mean-profit honest-profit
                                      :malice-mean-profit malice-profit}})]
      (is (= :inconclusive (:status report)))
      (is (= expected-reason (:reason report)))
      (is (false? (:deterrence? report))))))

;; ───────────────────────────────────────────────────────────────────────────
;; Grim-trigger and repeated-game deterrence in combined evaluation
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-combined-output-includes-deterrence-threshold
  (testing "evaluate-stochastic-equilibrium includes grim-trigger and repeated-game-deterrence keys"
    (let [result {:initial-resolver-count 100
                  :initial-composition {:honest-count 50 :lazy-count 25
                                        :malicious-count 20 :collusive-count 5
                                        :malice-count 25 :total-count 100
                                        :honest-share 0.5 :malice-share 0.25}
                  :aggregated-stats {:total-resolver-exits 10
                                     :honest-exit-count 2
                                     :lazy-exit-count 3
                                     :malicious-exit-count 4
                                     :collusive-exit-count 1
                                     :honest-cumulative-profit 100.0
                                     :malice-cumulative-profit -50.0
                                     :honest-avg-win-rate 0.8
                                     :malice-avg-win-rate 0.3}}
          report (sut/evaluate-stochastic-equilibrium result)]
      (is (contains? report :grim-trigger))
      (is (contains? report :repeated-game-deterrence))
      (is (contains? report :folk-theorem)
          "deprecated compatibility key remains available"))))
