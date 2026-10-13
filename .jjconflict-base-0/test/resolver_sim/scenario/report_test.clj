(ns resolver-sim.scenario.report-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.scenario.golden :as golden]
            [resolver-sim.scenario.report :as report]))

(deftest status-uses-pass-not-outcome
  (testing "FAIL when pass? false even if outcome is pass"
    (is (str/includes?
         (with-out-str
           (report/print-report
            {:passed 0 :total 1 :elapsed-ms 0 :ok? false
             :results [{:name "tricky"
                        :pass? false
                        :expected-fail? false
                        :outcome :pass
                        :steps 1
                        :reverts 0
                        :checks {:expectations {:ok? false
                                                :violations [{:type :metric-violation
                                                              :name :yield/x
                                                              :op :>
                                                              :expected 1
                                                              :actual 0}]}}}]}
            {:show-checks? true}))
         "✗ FAIL"))))

(deftest status-label-uses-pass-only
  (is (= "✓ PASS" (#'report/status-label {:pass? true :expected-fail? false :outcome :fail})))
  (is (= "✗ FAIL" (#'report/status-label {:pass? false :outcome :pass}))))

(deftest failure-detail-includes-id-halt-and-expectation
  (let [out (with-out-str
              (report/print-report
               {:passed 0 :total 1 :elapsed-ms 0 :ok? false
                :results [{:name "s99-demo"
                           :scenario-id "s99-demo"
                           :pass? false
                           :expected-fail? false
                           :outcome :fail
                           :halt-reason :expectation-mismatch
                           :steps 3
                           :reverts 0
                           :checks {:expectations {:ok? false
                                                   :violations [{:type :metric-violation
                                                                 :name :yield/escrow-principal
                                                                 :op :=
                                                                 :expected 100
                                                                 :actual 99}]}
                                    :thresholds {:ok? false
                                                 :violations [{:type :solvency-violation
                                                               :detail "Strict solvency check failed"}]}
                                    :golden {:ok? false
                                             :summary "replay snapshot mismatch"
                                             :mismatches [{:path [:metrics :yield/escrow-principal]
                                                           :expected 100
                                                           :actual 99}]}}}]}
               {:show-checks? true}))]
    (is (str/includes? out "id: s99-demo"))
    (is (str/includes? out "halt: :expectation-mismatch"))
    (is (str/includes? out "yield/escrow-principal"))
    (is (str/includes? out "threshold: Strict solvency"))
    (is (str/includes? out "golden: replay snapshot mismatch"))
    (is (str/includes? out "path: [:metrics :yield/escrow-principal]"))
    (is (str/includes? out "expected: 100"))
    (is (str/includes? out "actual:   99"))))

(deftest golden-single-mismatch-report-shape
  (let [lines (report/format-check-failures
               {:pass? false
                :checks {:golden {:ok? false
                                  :summary "replay snapshot mismatch"
                                  :mismatches [{:path [:metrics :yield/escrow-principal]
                                                :expected 10000
                                                :actual 9950}]}}})]
    (is (some #(str/includes? % "golden: replay snapshot mismatch") lines))
    (is (some #(str/includes? % "path: [:metrics :yield/escrow-principal]") lines))
    (is (some #(str/includes? % "expected: 10000") lines))
    (is (some #(str/includes? % "actual:   9950") lines))))

(deftest golden-multiple-mismatch-report-compact
  (let [lines (report/format-check-failures
               {:pass? false
                :checks {:golden {:ok? false
                                  :summary "3 mismatches"
                                  :mismatches [{:path [:outcome] :expected :pass :actual :fail}
                                               {:path [:metrics :yield/deferred-claims]
                                                :expected 0 :actual 25}
                                               {:path [:metrics :yield/x] :expected 1 :actual 2}]}}})]
    (is (some #(str/includes? % "golden: 3 mismatches") lines))
    (is (some #(str/includes? % "[:outcome] expected :pass, actual :fail") lines))
    (is (some #(str/includes? % "[:metrics :yield/deferred-claims]") lines))
    (is (not (some #(str/includes? % "[:metrics :yield/x]") lines))
        "compact mode shows at most two inline examples")))

(deftest golden-multiple-mismatch-report-verbose
  (let [lines (report/format-check-failures
               {:pass? false
                :checks {:golden {:ok? false
                                  :summary "3 mismatches"
                                  :mismatches [{:path [:outcome] :expected :pass :actual :fail}
                                               {:path [:metrics :yield/deferred-claims]
                                                :expected 0 :actual 25}
                                               {:path [:metrics :yield/x] :expected 1 :actual 2}]}}}
               {:report-detail :verbose})]
    (is (some #(str/includes? % "[:metrics :yield/x]") lines))))

(deftest golden-mismatch-outcome-pass-still-renders-fail
  (let [out (with-out-str
              (report/print-report
               {:passed 0 :total 1 :elapsed-ms 0 :ok? false
                :results [{:name "s42-yield-shortfall"
                           :scenario-id "s42-yield-shortfall"
                           :pass? false
                           :expected-fail? false
                           :outcome :pass
                           :steps 4
                           :reverts 0
                           :checks {:golden {:ok? false
                                             :summary "replay snapshot mismatch"
                                             :mismatches [{:path [:metrics :yield/escrow-principal]
                                                           :expected 10000
                                                           :actual 9950}]}}}]}
               {:show-checks? true}))]
    (is (str/includes? out "✗ FAIL"))
    (is (str/includes? out "golden: replay snapshot mismatch"))
    (is (not (str/includes? out "✓ PASS")))))

(deftest golden-match-omitted-in-compact-failure-formatter
  (is (not (some #(str/includes? % "golden:")
                 (report/format-check-failures
                  {:pass? true
                   :checks {:golden {:ok? true :summary "match" :mismatches []}}})))))

(deftest golden-match-shown-in-verbose-on-passing-row
  (let [out (with-out-str
              (report/print-report
               {:passed 1 :total 1 :elapsed-ms 0 :ok? true
                :results [{:name "ok-row"
                           :pass? true
                           :steps 1
                           :reverts 0
                           :checks {:golden {:ok? true :summary "match" :mismatches []}}}]}
               {:show-checks? true :report-detail :verbose}))]
    (is (str/includes? out "golden: match"))))

(deftest golden-missing-distinct-from-mismatch-in-report
  (let [lines (report/format-check-failures
               {:pass? false
                :checks {:golden (golden/missing-golden-check
                                  :traces/missing
                                  "data/fixtures/golden/missing.report.edn"
                                  :replay-and-theory)}})]
    (is (some #(str/includes? % "golden: golden snapshot missing") lines))
    (is (some #(str/includes? % "file:") lines))
    (is (not (some #(str/includes? % "expected:") lines)))))
