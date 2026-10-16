(ns resolver-sim.commands.validate-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.commands.validate :as sut]))

(defn- report [overrides]
  (merge {:status "passed"
          :expectedCount 2
          :discoveredCount 2
          :attempted 2
          :failed 0
          :timedOut 0
          :missing []
          :unexpected []
          :namespaces [{:status "passed"} {:status "passed"}]}
         overrides))

(deftest notebook-validation-requires-complete-success-report
  (testing "a complete report passes"
    (is (sut/notebook-report-valid? (report {}))))
  (testing "a timed-out child cannot be hidden by a zero subprocess status"
    (is (not (sut/notebook-report-valid?
              (report {:status "failed" :failed 1 :timedOut 1
                       :namespaces [{:status "passed"} {:status "timed-out"}]})))))
  (testing "incomplete inventory and missing report cannot pass"
    (is (not (sut/notebook-report-valid? (report {:discoveredCount 1 :attempted 1}))))
    (is (not (sut/notebook-report-valid? nil)))))
