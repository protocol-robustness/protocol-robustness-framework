(ns resolver-sim.yield.strategic-partial-fill-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.yield.strategic-partial-fill :as strategic]))

(deftest integer-rounding-is-not-exactly-merge-invariant
  (is (= [{:claims [1 2]
           :individual-sum 0
           :merged-allocation 1
           :error 1}]
         (strategic/check-merge-invariance
          [1 1 1] 1 {:mode :pro-rata
                     :rounding-policy :largest-remainder})))
  (is (some #(= {:claims [1 2]
                 :individual-sum 0
                 :merged-allocation 1
                 :error 1}
                %)
            (strategic/check-merge-invariance
             [1 1 1] 2 {:mode :pro-rata
                        :rounding-policy :floor}))))

(deftest rounding-policies-have-distinct-accounting-semantics
  (let [input [1 1 1]
        floor-result (strategic/allocation-report
                      input 1 {:rounding-policy :floor})
        remainder-result (strategic/allocation-report
                          input 1 {:rounding-policy :largest-remainder})]
    (testing "floor keeps its rounding residual visible"
      (is (= [0 0 0] (:allocations floor-result)))
      (is (= 0 (:distributed floor-result)))
      (is (= 1 (:undistributed floor-result))))
    (testing "largest remainder distributes the residual"
      (is (= [1 0 0] (:allocations remainder-result)))
      (is (= 1 (:distributed remainder-result)))
      (is (zero? (:undistributed remainder-result))))))

(deftest validation-artifact-reports-the-known-merge-counterexample
  (let [artifact (strategic/validate-strategic-properties
                  :deviations [:merge]
                  :policies [{:mode :pro-rata
                              :rounding-policy :largest-remainder}]
                  :max-states 1)
        property (first (:properties artifact))]
    (is (= :allocation/exact-merge-invariance (:property property)))
    (is (= :violated (:verdict property)))
    (is (= 1 (:violation-count property)))
    (is (= {:claims [1 1 1]
            :liquidity 1
            :merged-indices [1 2]
            :merged-claims [1 2]
            :individual-sum 0
            :merged-allocation 1
            :error 1}
           (:counterexample property)))))
