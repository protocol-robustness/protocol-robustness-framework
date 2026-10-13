(ns resolver-sim.sim.fixtures-runner-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.scenario.report :as report]
            [resolver-sim.scenario.runner :as runner]
            [resolver-sim.io.fixtures :as fixtures]
            [resolver-sim.sim.result-display :as display]))

(deftest fixture-suite-unified-summary-shape
  (let [summary (fixtures/run-suite-from-key :suites/equivalence-escalation-boundaries nil nil {:silent? true})]
    (is (contains? summary :passed))
    (is (contains? summary :total))
    (is (contains? summary :elapsed-ms))
    (is (contains? summary :ok?))
    (is (= (:ok? summary) (= (:passed summary) (:total summary))))
    (testing "each row has unified pass metadata"
      (is (every? #(contains? % :pass?) (:results summary)))
      (is (every? #(contains? % :checks) (:results summary))))))

(deftest fixture-pass-aligned-with-scenario-pass
  (let [summary (fixtures/run-suite-from-key :suites/equivalence-escalation-boundaries nil nil {:silent? true})]
    (is (every? #(= (:pass? %) (runner/scenario-pass? % {})) (:results summary)))))

(deftest legacy-display-failure-lines-delegate-to-checks
  (let [entry {:pass? false
               :trace-id "s1"
               :checks {:fixture-outcome {:ok? false :expected :pass :actual :fail}
                        :expectations {:ok? false
                                       :violations [{:type :metric-violation
                                                     :name :yield/x :op := :expected 1 :actual 0}]}}}
        lines (display/suite-report-lines
               {:suite-id :test :ok? false :results [entry]}
               {:result-display-level :failures})]
    (is (some #(str/includes? % "outcome:") lines))
    (is (some #(str/includes? % "expectation:") lines))
    (is (pos? (count (report/format-check-failures entry))))))

(deftest fixture-entry-checks-include-outcome-when-declared
  (let [summary (fixtures/run-suite-from-key :suites/equivalence-escalation-boundaries nil nil {:silent? true})
        with-expected (some #(when (:expected-outcome %) %) (:results summary))]
    (when with-expected
      (is (contains? (:checks with-expected) :fixture-outcome))
      (is (boolean? (get-in with-expected [:checks :fixture-outcome :ok?]))))))
