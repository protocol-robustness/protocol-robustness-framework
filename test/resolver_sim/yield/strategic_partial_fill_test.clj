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

(deftest split-invariance-preserves-total-allocation
  (testing "splitting a claim into equal parts preserves total allocation"
    (let [violations (strategic/check-split-invariance
                      [2 2] 2 {:mode :pro-rata :rounding-policy :largest-remainder})]
      (is (empty? violations)))))

(deftest permutation-invariance-is-order-independent
  (testing "reordering claims does not change total allocations"
    (let [violations (strategic/check-permutation-invariance
                      [3 1] 4 {:mode :pro-rata :rounding-policy :largest-remainder})]
      (is (empty? violations)))))

(deftest sybil-invariance-prevents-total-allocation-gain
  (testing "splitting a claim into sybil identities does not increase total allocation"
    (let [violations (strategic/check-sybil-invariance
                      [4] 4 {:mode :pro-rata :rounding-policy :largest-remainder})]
      (is (empty? violations)))))

(deftest request-monotonicity-preserves-allocations
  (testing "inflating a claim does not decrease its allocation or increase others"
    (let [violations (strategic/check-request-monotonicity
                      [3 1] 4 {:mode :pro-rata :rounding-policy :largest-remainder})]
      (is (empty? violations)))))

(deftest validate-strategic-properties-returns-complete-artifact
  (testing "validation artifact includes expected top-level keys"
    (let [artifact (strategic/validate-strategic-properties
                    :deviations [:split :merge :permute :sybil :inflate]
                    :policies [{:mode :pro-rata :rounding-policy :largest-remainder}]
                    :max-states 10)
          summary (:summary artifact)]
      (is (= :strategic-closed-form-validation (:artifact/kind artifact)))
      (is (= :yield/partial-fill (:mechanism artifact)))
      (is (map? (:validation-scope artifact)))
      (is (vector? (:properties artifact)))
      (is (map? summary))
      (is (number? (:total-checks summary)))
      (is (number? (:verified summary)))
      (is (number? (:violated summary))))))

(deftest empty-claims-vector-produces-zero-allocations
  (testing "zero claims yields empty allocations with no violations"
    (let [report (strategic/allocation-report [] 100 {:rounding-policy :largest-remainder})]
      (is (empty? (:allocations report)))
      (is (zero? (:distributed report))))))
