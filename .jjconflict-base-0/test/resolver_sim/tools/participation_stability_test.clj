(ns resolver-sim.tools.participation-stability-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.tools.participation-stability :as ps]))

;; ───────────────────────────────────────────────────────────────────────────
;; Helper: build a minimal valid result map
;; ───────────────────────────────────────────────────────────────────────────

(defn make-result
  "Build a minimal multi-epoch result map for testing.
   Merges optional overrides so each test only sets fields it cares about."
  [& {:as overrides}]
  (merge
   {:initial-resolver-count 100
    :initial-composition {:honest-count 40 :lazy-count 30
                          :malicious-count 20 :collusive-count 10}
    :aggregated-stats {:total-resolver-exits 0
                       :final-resolver-count 100
                       :honest-exit-count 0
                       :lazy-exit-count 0
                       :malicious-exit-count 0
                       :collusive-exit-count 0
                       :honest-cumulative-profit 0.0
                       :malice-cumulative-profit 0.0
                       :honest-avg-win-rate 0.0
                       :malice-avg-win-rate 0.0}}
   overrides))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 1: malice attrition passes classified check
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-malice-attrition-passes-classified
  (testing "30% total exits caused entirely by malicious resolvers passes classified check"
    (let [result  (make-result
                   :aggregated-stats {:total-resolver-exits 30
                                      :final-resolver-count 70
                                      :honest-exit-count 0
                                      :lazy-exit-count 0
                                      :malicious-exit-count 20
                                      :collusive-exit-count 10
                                      :honest-cumulative-profit 0.0
                                      :malice-cumulative-profit 0.0
                                      :honest-avg-win-rate 0.0
                                      :malice-avg-win-rate 0.0})
          check (ps/check-participation-stability result)]
      (is (= :pass (:status check))
          "0% productive exits → pass")
      (is (= :classified (:evaluation-mode check))
          "classified check was used")
      (is (= 0.0 (get-in check [:evidence :productive-exit-rate]))
          "productive-exit-rate is 0.0")
      (is (= 30 (:total-exits (:evidence check)))
          "evidence shows 30 total exits")
      (is (= 20 (get-in check [:evidence :malicious-exits]))
          "20 malicious exits reported")
      (is (= 10 (get-in check [:evidence :collusive-exits]))
          "10 collusive exits reported"))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 2: honest exit rate fails classified check
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-honest-exit-rate-fails-classified
  (testing "More than 10% honest exits fails even when total exits are below 40%"
    (let [;; 11 honest exits out of 40 initial = 27.5% > 10% → fail
          result  (make-result
                   :aggregated-stats {:total-resolver-exits 29
                                      :final-resolver-count 71
                                      :honest-exit-count 11
                                      :lazy-exit-count 0
                                      :malicious-exit-count 15
                                      :collusive-exit-count 3
                                      :honest-cumulative-profit 0.0
                                      :malice-cumulative-profit 0.0
                                      :honest-avg-win-rate 0.0
                                      :malice-avg-win-rate 0.0})
          check (ps/check-participation-stability result)]
      (is (= :fail (:status check))
          "honest-exit-rate 27.5% > 10% → fail")
      (is (= :classified (:evaluation-mode check))
          "classified check was used")
      ;; Total exits 29/100 = 29% < 40%, but classified still fails
      (is (< (get-in check [:evidence :aggregate-exit-rate]) 0.40)
          "aggregate exit rate is below 40% but classified check dominates")
      (is (= 11 (get-in check [:evidence :honest-exits]))
          "evidence includes honest exit count"))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 3: missing classification invokes fallback
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-missing-classified-invokes-fallback
  (testing "No initial-composition data invokes the 40% aggregate fallback"
    (let [result  (make-result
                   :initial-composition nil
                   :aggregated-stats {:total-resolver-exits 25
                                      :final-resolver-count 75
                                      :honest-exit-count 0
                                      :lazy-exit-count 0
                                      :malicious-exit-count 0
                                      :collusive-exit-count 0
                                      :honest-cumulative-profit 0.0
                                      :malice-cumulative-profit 0.0
                                      :honest-avg-win-rate 0.0
                                      :malice-avg-win-rate 0.0})
          check (ps/check-participation-stability result)]
      (is (= :pass (:status check))
          "25% aggregate exit rate < 40% → pass")
      (is (= :fallback (:evaluation-mode check))
          "fallback was used because classified data unavailable")
      (is (some? (:fallback-reason (:evidence check)))
          "evidence includes reason why classified check could not run"))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 4: fallback boundary convention (strict less)
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-fallback-boundary-convention
  (testing "Exactly 40% aggregate exits fails (strict-less convention)"
    (let [result-under  (make-result
                         :initial-composition nil
                         :initial-resolver-count 1000
                         :aggregated-stats {:total-resolver-exits 399
                                            :final-resolver-count 601
                                            :honest-exit-count 0
                                            :lazy-exit-count 0
                                            :malicious-exit-count 0
                                            :collusive-exit-count 0
                                            :honest-cumulative-profit 0.0
                                            :malice-cumulative-profit 0.0
                                            :honest-avg-win-rate 0.0
                                            :malice-avg-win-rate 0.0})
          check-under (ps/check-participation-stability result-under)
          result-at  (make-result
                      :initial-composition nil
                      :initial-resolver-count 1000
                      :aggregated-stats {:total-resolver-exits 400
                                         :final-resolver-count 600
                                         :honest-exit-count 0
                                         :lazy-exit-count 0
                                         :malicious-exit-count 0
                                         :collusive-exit-count 0
                                         :honest-cumulative-profit 0.0
                                         :malice-cumulative-profit 0.0
                                         :honest-avg-win-rate 0.0
                                         :malice-avg-win-rate 0.0})
          check-at (ps/check-participation-stability result-at)]
      (is (= :pass (:status check-under))
          "< 40% passes (strict less convention)")
      (is (= :fail (:status check-at))
          "== 40% fails (strict less convention)")
      (is (= :fallback (:evaluation-mode check-under)))
      (is (= :fallback (:evaluation-mode check-at))))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 5: above 40% fallback fails
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-fallback-above-threshold-fails
  (testing "More than 40% aggregate exits fails the fallback"
    (let [result  (make-result
                   :initial-composition nil
                   :initial-resolver-count 100
                   :aggregated-stats {:total-resolver-exits 41
                                      :final-resolver-count 59
                                      :honest-exit-count 0
                                      :lazy-exit-count 0
                                      :malicious-exit-count 0
                                      :collusive-exit-count 0
                                      :honest-cumulative-profit 0.0
                                      :malice-cumulative-profit 0.0
                                      :honest-avg-win-rate 0.0
                                      :malice-avg-win-rate 0.0})
          check (ps/check-participation-stability result)]
      (is (= :fail (:status check))
          "41% > 40% → fail")
      (is (= :fallback (:evaluation-mode check))
          "fallback was used"))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 6: existing aggregate fields remain present
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-existing-aggregate-fields-present
  (testing "All existing aggregate passthrough fields remain in evidence"
    (let [result  (make-result
                   :aggregated-stats {:total-resolver-exits 25
                                      :final-resolver-count 75
                                      :honest-exit-count 5
                                      :lazy-exit-count 3
                                      :malicious-exit-count 12
                                      :collusive-exit-count 5
                                      :honest-cumulative-profit 0.0
                                      :malice-cumulative-profit 0.0
                                      :honest-avg-win-rate 0.0
                                      :malice-avg-win-rate 0.0})
          ev (:evidence (ps/check-participation-stability result))]
      (is (contains? ev :total-exits) ":total-exits present")
      (is (contains? ev :initial-count) ":initial-count present")
      (is (contains? ev :final-count) ":final-count present")
      (is (contains? ev :aggregate-exit-rate) ":aggregate-exit-rate present")
      (is (contains? ev :honest-exits) ":honest-exits present")
      (is (contains? ev :lazy-exits) ":lazy-exits present")
      (is (contains? ev :malicious-exits) ":malicious-exits present")
      (is (contains? ev :collusive-exits) ":collusive-exits present")
      (is (contains? ev :productive-exits) ":productive-exits present")
      (is (contains? ev :productive-init) ":productive-init present")
      (is (contains? ev :productive-exit-rate) ":productive-exit-rate present")
      ;; Verify values are correct
      (is (= 25 (:total-exits ev)))
      (is (= 100 (:initial-count ev)))
      (is (< (Math/abs (- 0.25 (:aggregate-exit-rate ev))) 1e-9))
      (is (= 5 (:honest-exits ev)))
      (is (= 3 (:lazy-exits ev)))
      (is (= 12 (:malicious-exits ev)))
      (is (= 5 (:collusive-exits ev)))
      (is (= 8 (:productive-exits ev)))
      (is (= 70 (:productive-init ev)))
      (is (< (Math/abs (- (/ 8.0 70) (:productive-exit-rate ev))) 1e-9)))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 7: missing aggregate fields cannot pass
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-missing-aggregate-fields-cannot-pass
  (testing "Missing initial-resolver-count returns inconclusive"
    (let [result  (make-result
                   :initial-resolver-count nil
                   :aggregated-stats {:total-resolver-exits 10
                                      :final-resolver-count 90})
          check (ps/check-participation-stability result)]
      (is (= :inconclusive (:status check))
          "cannot evaluate without initial-resolver-count")
      (is (= :invalid-evidence (:evaluation-mode check)))))
  (testing "Missing total-resolver-exits returns inconclusive"
    (let [result  (make-result
                   :aggregated-stats {:final-resolver-count 90
                                      :honest-exit-count 0
                                      :lazy-exit-count 0
                                      :malicious-exit-count 0
                                      :collusive-exit-count 0})
          check (ps/check-participation-stability result)]
      (is (= :inconclusive (:status check))
          "cannot evaluate without total-resolver-exits")
      (is (= :invalid-evidence (:evaluation-mode check)))))
  (testing "Missing final-resolver-count returns inconclusive"
    (let [result  (make-result
                   :aggregated-stats {:total-resolver-exits 10
                                      :honest-exit-count 0
                                      :lazy-exit-count 0
                                      :malicious-exit-count 0
                                      :collusive-exit-count 0})
          check (ps/check-participation-stability result)]
      (is (= :inconclusive (:status check))
          "cannot evaluate without final-resolver-count")
      (is (= :invalid-evidence (:evaluation-mode check))))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 8: non-finite counts return inconclusive
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-non-finite-counts-inconclusive
  (testing "NaN in total-resolver-exits is caught before classified check runs"
    (let [result  (make-result
                   :aggregated-stats {:total-resolver-exits ##NaN
                                      :final-resolver-count 100
                                      :honest-exit-count 0
                                      :lazy-exit-count 0
                                      :malicious-exit-count 0
                                      :collusive-exit-count 0})
          check (ps/check-participation-stability result)]
      (is (= :inconclusive (:status check))
          "NaN from zero initial count → inconclusive")
      (is (= :invalid-evidence (:evaluation-mode check)))))
  (testing "NaN in total-resolver-exits returns inconclusive"
    (let [result  (make-result
                   :aggregated-stats {:total-resolver-exits ##NaN
                                      :final-resolver-count 100
                                      :honest-exit-count 0
                                      :lazy-exit-count 0
                                      :malicious-exit-count 0
                                      :collusive-exit-count 0})
          check (ps/check-participation-stability result)]
      (is (= :inconclusive (:status check))
          "NaN total-resolver-exits → inconclusive")
      (is (= :invalid-evidence (:evaluation-mode check)))))
  (testing "Infinity in final-resolver-count returns inconclusive"
    (let [result  (make-result
                   :aggregated-stats {:total-resolver-exits 10
                                      :final-resolver-count ##Inf
                                      :honest-exit-count 0
                                      :lazy-exit-count 0
                                      :malicious-exit-count 0
                                      :collusive-exit-count 0})
          check (ps/check-participation-stability result)]
      (is (= :inconclusive (:status check))
          "Infinity final-resolver-count → inconclusive")
      (is (= :invalid-evidence (:evaluation-mode check))))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 9: negative counts return inconclusive
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-negative-counts-inconclusive
  (testing "Negative total-resolver-exits returns inconclusive"
    (let [result  (make-result
                   :aggregated-stats {:total-resolver-exits -5
                                      :final-resolver-count 105
                                      :honest-exit-count 0
                                      :lazy-exit-count 0
                                      :malicious-exit-count 0
                                      :collusive-exit-count 0
                                      :honest-cumulative-profit 0.0
                                      :malice-cumulative-profit 0.0
                                      :honest-avg-win-rate 0.0
                                      :malice-avg-win-rate 0.0})
          check (ps/check-participation-stability result)]
      (is (= :inconclusive (:status check))
          "negative exits → inconclusive")
      (is (= :invalid-evidence (:evaluation-mode check)))))
  (testing "Negative initial-resolver-count returns inconclusive"
    (let [result  (make-result
                   :initial-resolver-count -10
                   :aggregated-stats {:total-resolver-exits 0
                                      :final-resolver-count -10
                                      :honest-exit-count 0
                                      :lazy-exit-count 0
                                      :malicious-exit-count 0
                                      :collusive-exit-count 0})
          check (ps/check-participation-stability result)]
      (is (= :inconclusive (:status check))
          "negative initial count → inconclusive")
      (is (= :invalid-evidence (:evaluation-mode check)))))
  (testing "Negative per-strategy exit count returns inconclusive"
    (let [result  (make-result
                   :aggregated-stats {:total-resolver-exits 10
                                      :final-resolver-count 90
                                      :honest-exit-count -1
                                      :lazy-exit-count 0
                                      :malicious-exit-count 11
                                      :collusive-exit-count 0
                                      :honest-cumulative-profit 0.0
                                      :malice-cumulative-profit 0.0
                                      :honest-avg-win-rate 0.0
                                      :malice-avg-win-rate 0.0})
          check (ps/check-participation-stability result)]
      (is (= :inconclusive (:status check))
          "negative honest exits → inconclusive")
      (is (= :invalid-evidence (:evaluation-mode check))))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 10: per-strategy totals inconsistent with aggregate
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-inconsistent-per-strategy-totals-inconclusive
  (testing "Per-strategy exits sum (6+4+3+2=15) ≠ total (20) → inconclusive"
    (let [result  (make-result
                   :aggregated-stats {:total-resolver-exits 20
                                      :final-resolver-count 80
                                      :honest-exit-count 6
                                      :lazy-exit-count 4
                                      :malicious-exit-count 3
                                      :collusive-exit-count 2
                                      :honest-cumulative-profit 0.0
                                      :malice-cumulative-profit 0.0
                                      :honest-avg-win-rate 0.0
                                      :malice-avg-win-rate 0.0})
          check (ps/check-participation-stability result)]
      (is (= :inconclusive (:status check))
          "15 per-strategy ≠ 20 total → inconclusive")
      (is (= :invalid-evidence (:evaluation-mode check)))
      (is (= :per-strategy-exit-sum-mismatch (:inconsistency (:evidence check)))
          "evidence records the inconsistency reason"))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 11: fallback only when aggregate evidence complete
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-fallback-only-when-aggregate-complete
  (testing "Missing final-resolver-count prevents fallback → inconclusive"
    (let [result  {:initial-resolver-count 100
                   :aggregated-stats {:total-resolver-exits 30
                                      :honest-exit-count 0
                                      :lazy-exit-count 0
                                      :malicious-exit-count 0
                                      :collusive-exit-count 0}}
          check (ps/check-participation-stability result)]
      (is (= :inconclusive (:status check))
          "incomplete aggregate evidence → inconclusive, not fallback")
      (is (= :invalid-evidence (:evaluation-mode check)))))
  (testing "Missing initial-resolver-count prevents fallback → inconclusive"
    (let [result  {:aggregated-stats {:total-resolver-exits 10
                                      :final-resolver-count 90}}
          check (ps/check-participation-stability result)]
      (is (= :inconclusive (:status check))
          "missing initial-resolver-count → inconclusive")
      (is (= :invalid-evidence (:evaluation-mode check))))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 12: threshold breach is fail, not inconclusive
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-threshold-breach-is-fail-not-inconclusive
  (testing "41% aggregate exits fails fallback (not inconclusive)"
    (let [result  (make-result
                   :initial-composition nil
                   :initial-resolver-count 100
                   :aggregated-stats {:total-resolver-exits 41
                                      :final-resolver-count 59
                                      :honest-exit-count 0
                                      :lazy-exit-count 0
                                      :malicious-exit-count 0
                                      :collusive-exit-count 0
                                      :honest-cumulative-profit 0.0
                                      :malice-cumulative-profit 0.0
                                      :honest-avg-win-rate 0.0
                                      :malice-avg-win-rate 0.0})
          check (ps/check-participation-stability result)]
      (is (= :fail (:status check))
          "41% > 40% → fail, not inconclusive")
      (is (not= :inconclusive (:status check))
          "explicitly not inconclusive"))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 13: exits exceed initial → inconclusive
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-exits-exceed-initial-inconclusive
  (testing "More exits than initial resolvers → inconclusive"
    (let [result  (make-result
                   :initial-resolver-count 100
                   :aggregated-stats {:total-resolver-exits 110
                                      :final-resolver-count 0
                                      :honest-exit-count 50
                                      :lazy-exit-count 30
                                      :malicious-exit-count 20
                                      :collusive-exit-count 10
                                      :honest-cumulative-profit 0.0
                                      :malice-cumulative-profit 0.0
                                      :honest-avg-win-rate 0.0
                                      :malice-avg-win-rate 0.0})
          check (ps/check-participation-stability result)]
      (is (= :inconclusive (:status check))
          "exits exceed initial → inconclusive")
      (is (= :invalid-evidence (:evaluation-mode check)))
      (is (= :exits-exceed-initial (:inconsistency (:evidence check)))
          "evidence records the inconsistency reason"))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 14: final-count mismatch → inconclusive
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-final-count-mismatch-inconclusive
  (testing "Final count ≠ initial - exits → inconclusive"
    (let [result  (make-result
                   :initial-resolver-count 100
                   :aggregated-stats {:total-resolver-exits 20
                                      :final-resolver-count 85  ;; should be 80
                                      :honest-exit-count 5
                                      :lazy-exit-count 5
                                      :malicious-exit-count 6
                                      :collusive-exit-count 4
                                      :honest-cumulative-profit 0.0
                                      :malice-cumulative-profit 0.0
                                      :honest-avg-win-rate 0.0
                                      :malice-avg-win-rate 0.0})
          check (ps/check-participation-stability result)]
      (is (= :inconclusive (:status check))
          "final count 85 ≠ 100-20=80 → inconclusive")
      (is (= :invalid-evidence (:evaluation-mode check)))
      (is (= :final-count-mismatch (:inconsistency (:evidence check)))
          "evidence records the inconsistency reason"))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 15: per-strategy exits exceed initial → inconclusive
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-per-strategy-exits-exceed-init-inconclusive
  (testing "Honest exits exceed initial honest count → inconclusive"
    (let [result  (make-result
                   :initial-resolver-count 100
                   :initial-composition {:honest-count 40 :lazy-count 30
                                         :malicious-count 20 :collusive-count 10}
                   :aggregated-stats {:total-resolver-exits 50
                                      :final-resolver-count 50
                                      :honest-exit-count 50
                                      :lazy-exit-count 0
                                      :malicious-exit-count 0
                                      :collusive-exit-count 0
                                      :honest-cumulative-profit 0.0
                                      :malice-cumulative-profit 0.0
                                      :honest-avg-win-rate 0.0
                                      :malice-avg-win-rate 0.0})
          check (ps/check-participation-stability result)]
      (is (= :inconclusive (:status check))
          "50 honest exits > 40 initial → inconclusive")
      (is (= :invalid-evidence (:evaluation-mode check)))
      (is (= :honest-exits-exceed-init (:inconsistency (:evidence check)))
          "evidence records the specific inconsistency reason"))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 16: evaluation-result helper works
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-evaluation-result-helper
  (testing "evaluation-result fills defaults for missing fields"
    (let [r (ps/evaluation-result {})]
      (is (= :inconclusive (:status r)))
      (is (= :invalid-evidence (:evaluation-mode r)))))
  (testing "evaluation-result preserves provided fields"
    (let [r (ps/evaluation-result
             {:status :pass :evaluation-mode :classified
              :reason "all good"
              :evidence {:total-exits 5}})]
      (is (= :pass (:status r)))
      (is (= :classified (:evaluation-mode r)))
      (is (= "all good" (:reason r)))
      (is (= {:total-exits 5} (:evidence r))))))

;; ───────────────────────────────────────────────────────────────────────────
;; Test 17: complete-finite-numbers? works
;; ───────────────────────────────────────────────────────────────────────────

(deftest test-complete-finite-numbers
  (testing "All keys present and finite"
    (is (ps/complete-finite-numbers? {:a 1 :b 2.5 :c 0} [:a :b :c])))
  (testing "Missing key returns false"
    (is (not (ps/complete-finite-numbers? {:a 1} [:a :b]))))
  (testing "NaN returns false"
    (is (not (ps/complete-finite-numbers? {:a ##NaN} [:a]))))
  (testing "Infinity returns false"
    (is (not (ps/complete-finite-numbers? {:a ##Inf} [:a]))))
  (testing "nil returns false"
    (is (not (ps/complete-finite-numbers? {:a nil} [:a]))))
  (testing "Non-numeric returns false"
    (is (not (ps/complete-finite-numbers? {:a "hello"} [:a]))))
  (testing "Negative numbers are accepted (finite check only)"
    (is (ps/complete-finite-numbers? {:a -5} [:a]))))
