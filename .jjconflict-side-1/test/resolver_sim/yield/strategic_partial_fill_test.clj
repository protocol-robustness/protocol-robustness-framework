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

(deftest artifact-epistemic-scope-is-bounded-exhaustive
  (testing "epistemic-scope declares bounded exhaustive search"
    (let [artifact (strategic/validate-strategic-properties
                    :deviations [:split]
                    :max-states 5)
          scope (:epistemic-scope artifact)]
      (is (map? scope))
      (is (= :bounded-exhaustive (:scope/kind scope)))
      (is (false? (:scope/universal-claim? scope)))
      (is (true? (:scope/falsification? scope)))
      (is (= :validation-scope (:scope/coverage scope)))
      (is (contains? (set (:scope/limitations scope)) :bounded-domain))
      (is (contains? (set (:scope/limitations scope)) :no-equilibrium-proof))
      (is (contains? (set (:scope/limitations scope)) :no-concurrency-assurance)))))

(deftest artifact-strategic-model-describes-the-evaluation
  (testing "strategic-model captures mechanism, payoff, scope-kind, and actions"
    (let [artifact (strategic/validate-strategic-properties
                    :deviations [:split :merge :permute :sybil :inflate]
                    :policies [{:mode :pro-rata :rounding-policy :largest-remainder}]
                    :max-states 5)
          model (:strategic-model artifact)]
      (is (map? model))
      (is (= :yield/partial-fill (:mechanism model)))
      (is (= :pro-rata (:allocation-mode model)))
      (is (= :allocated-amount-only (:payoff-model model)))
      (is (= :bounded-enumeration (:scope-kind model)))
      (is (vector? (:rounding-policies model)))
      (is (vector? (:actions model)))
      (is (vector? (:claims-unmodeled model)))
      (is (set (set (:actions model)))
          "actions is a vector of keywords"))))

(deftest diagnostic-transform-is-attached-to-each-property
  (testing "every property carries a diagnostic-transform with role and semantic-purpose"
    (let [artifact (strategic/validate-strategic-properties
                    :deviations [:split :merge :permute :sybil :inflate]
                    :max-states 10)
          by-prop (into {} (map (juxt :property identity)) (:properties artifact))]
      (doseq [prop (:properties artifact)]
        (is (map? (:diagnostic-transform prop))
            (str (:property prop) " must have diagnostic-transform"))
        (is (= :diagnostic-transform
               (get-in prop [:diagnostic-transform :role]))
            (str (:property prop) " role must be :diagnostic-transform"))
        (is (= :bounded-counterexample-search
               (get-in prop [:diagnostic-transform :semantic-purpose]))
            (str (:property prop) " semantic-purpose must be :bounded-counterexample-search")))
      (is (= :split (get-in by-prop [:strategy/split-invariance :diagnostic-transform :id])))
      (is (= :merge (get-in by-prop [:allocation/exact-merge-invariance :diagnostic-transform :id])))
      (is (= :permute (get-in by-prop [:strategy/permutation-invariance :diagnostic-transform :id])))
      (is (= :sybil (get-in by-prop [:strategy/sybil-invariance :diagnostic-transform :id])))
      (is (= :inflate (get-in by-prop [:strategy/request-monotonicity :diagnostic-transform :id]))))))

(deftest diagnostic-transformations-listed-in-validation-scope
  (testing ":diagnostic-transformations in validation-scope is sorted vector of deviation keywords"
    (let [artifact (strategic/validate-strategic-properties
                    :deviations [:split :merge :permute :sybil :inflate]
                    :max-states 5)
          dt (get-in artifact [:validation-scope :diagnostic-transformations])]
      (is (vector? dt))
      (is (= (vec (sort dt)) dt))
      (is (= [:inflate :merge :permute :split :sybil] dt)))))

(deftest empty-claims-vector-produces-zero-allocations
  (testing "zero claims yields empty allocations with no violations"
    (let [report (strategic/allocation-report [] 100 {:rounding-policy :largest-remainder})]
      (is (empty? (:allocations report)))
      (is (zero? (:distributed report))))))

(deftest protocol-supplied-generator-runs-through-standard-harness
  (testing "a protocol can register a new deviation family via :extra-generators
            without editing the dispatcher"
    (let [artifact (strategic/validate-strategic-properties
                    :deviations [:split :delay-request]
                    :extra-generators {:delay-request
                                       {:id :delay-request
                                        :property :strategy/delay-invariance
                                        :evaluate (fn [claims liquidity policy]
                                                    {:property :strategy/delay-invariance
                                                     :verdict :verified
                                                     :state {:claims claims :liquidity liquidity
                                                             :policy (select-keys policy [:mode :rounding-policy])}})}}
                    :max-states 3)]
      (is (some #(= :strategy/delay-invariance (:property %))
                (:properties artifact)))
      (is (some #(= :strategy/split-invariance (:property %))
                (:properties artifact))))))

(deftest protocol-supplied-generator-attaches-diagnostic-transform
  (testing "generators supplied via :extra-generators carry a diagnostic-transform"
    (let [artifact (strategic/validate-strategic-properties
                    :deviations [:delay-request]
                    :extra-generators {:delay-request
                                       {:id :delay-request
                                        :property :strategy/delay-invariance
                                        :evaluate (fn [_ _ _]
                                                    {:property :strategy/delay-invariance
                                                     :verdict :verified
                                                     :state {}})}}
                    :max-states 1)
          prop (first (:properties artifact))]
      (is (= :delay-request (get-in prop [:diagnostic-transform :id])))
      (is (= :diagnostic-transform (get-in prop [:diagnostic-transform :role]))))))

(deftest unresolved-deviation-generator-fails-closed
  (testing "an unknown deviation keyword throws rather than silently dropping scope"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unresolved deviation generator"
         (strategic/validate-strategic-properties
          :deviations [:not-a-real-deviation]
          :max-states 1)))))

(deftest contract-id-path-resolves-via-default-registry
  (testing "contract-id resolves deviations from the builtin deviation-contract registry"
    (let [artifact (strategic/validate-strategic-properties
                    :contract-id :partial-fill/claimant-monotonicity
                    :max-states 3)]
      (is (some #(= :strategy/request-monotonicity (:property %))
                (:properties artifact))))))

(deftest registered-generators-cover-all-builtin-deviation-families
  (testing "every builtin deviation keyword resolves to a registered generator"
    (doseq [dev [:split :merge :permute :sybil :inflate]]
      (is (contains? strategic/registered-deviation-generators dev)
          (str dev " must be registered"))
      (is (keyword? (get-in strategic/registered-deviation-generators [dev :property]))
          (str dev " generator must declare a property")))))
