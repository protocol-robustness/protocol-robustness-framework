(ns resolver-sim.validation.gate-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.validation.gate :as gate]))

(deftest integrity-gate-passes-with-clean-checks
  (let [result (gate/evaluate-integrity-gate
                [{:check/id :conservation :status :pass
                  :validation-class :validation.class/algebraic-integrity}
                 {:check/id :non-negative :status :pass
                  :validation-class :validation.class/algebraic-integrity}]
                :witnesses [{:exercised-fill? true}])]
    (is (= :pass (:verdict result)))
    (is (= 2 (:checks-executed result)))
    (is (nil? (:blocked-reason result)))))

(deftest integrity-gate-blocks-on-failure
  (let [result (gate/evaluate-integrity-gate
                [{:check/id :conservation :status :fail
                  :validation-class :validation.class/algebraic-integrity
                  :details {:imbalance 20}}]
                :witnesses [{:exercised-fill? true}])]
    (is (= :blocked (:verdict result)))
    (is (some? (:blocked-reason result)))))

(deftest integrity-gate-blocks-on-missing-witnesses
  (let [result (gate/evaluate-integrity-gate
                [{:check/id :conservation :status :pass
                  :validation-class :validation.class/algebraic-integrity}]
                :witnesses []
                :required-mechanisms #{:pro-rata})]
    (is (= :blocked (:verdict result)))
    (is (some? (:blocked-reason result)))))

(deftest economic-model-gate-blocks-when-integrity-blocked
  (let [integrity {:gate :integrity :verdict :blocked :blocked-reason "test"}
        result (gate/evaluate-economic-model-gate
                integrity
                [{:check/id :budget-balance :status :pass
                  :validation-class :validation.class/payoff-property}])]
    (is (= :blocked (:verdict result)))
    (is (some? (:blocked-reason result)))))

(deftest economic-model-gate-passes-with-clean-checks
  (let [integrity {:gate :integrity :verdict :pass}
        result (gate/evaluate-economic-model-gate
                integrity
                [{:check/id :budget-balance :status :pass
                  :validation-class :validation.class/payoff-property}
                 {:check/id :pro-rata-cross-product :status :pass
                  :validation-class :validation.class/allocation-property}])]
    (is (= :pass (:verdict result)))
    (is (= 2 (:checks-executed result)))))

(deftest economic-model-gate-blocks-on-failure
  (let [integrity {:gate :integrity :verdict :pass}
        result (gate/evaluate-economic-model-gate
                integrity
                [{:check/id :budget-balance :status :fail
                  :validation-class :validation.class/payoff-property
                  :details {:imbalance 10}}])]
    (is (= :blocked (:verdict result)))))

(deftest strategic-gate-passes-with-clean-deviations
  (let [economic {:gate :economic-model :verdict :pass}
        result (gate/evaluate-strategic-gate
                economic
                [{:property :split-invariance :verdict :verified}
                 {:property :merge-invariance :verdict :verified}]
                [])]
    (is (= :verified (:verdict result)))
    (is (= 2 (count (:properties result))))))

(deftest strategic-gate-violates-on-deviation-failure
  (let [economic {:gate :economic-model :verdict :pass}
        result (gate/evaluate-strategic-gate
                economic
                [{:property :split-invariance :verdict :violated}]
                [])]
    (is (= :violated (:verdict result)))))

(deftest strategic-gate-blocks-when-economic-blocked
  (let [economic {:gate :economic-model :verdict :blocked :blocked-reason "test"}
        result (gate/evaluate-strategic-gate economic [] [])]
    (is (= :blocked (:verdict result)))))

(deftest strategic-gate-exposes-resolved-contract-ids
  (let [economic {:gate :economic-model :verdict :pass}
        multi (gate/evaluate-strategic-gate
               economic
               [{:property :split-invariance :verdict :verified}]
               []
               :contract-ids [:partial-fill/claimant-monotonicity
                              :partial-fill/claimant-split-merge-sybil])
        single (gate/evaluate-strategic-gate
                economic
                [{:property :split-invariance :verdict :verified}]
                []
                :contract-id :partial-fill/claimant-split-merge-sybil)]
    (is (= :partial-fill/claimant-monotonicity (:contract-id multi)))
    (is (= [:partial-fill/claimant-monotonicity
            :partial-fill/claimant-split-merge-sybil]
           (:contract-ids multi)))
    (is (= :partial-fill/claimant-split-merge-sybil (:contract-id single)))
    (is (= [:partial-fill/claimant-split-merge-sybil] (:contract-ids single)))))

(deftest strategic-gate-rejects-contract-provenance-disagreement
  (is (thrown? clojure.lang.ExceptionInfo
               (gate/evaluate-strategic-gate
                {:gate :economic-model :verdict :pass}
                []
                []
                :contract-id :partial-fill/claimant-split-merge-sybil
                :contract-ids [:partial-fill/claimant-monotonicity]))))

(deftest combined-gates-all-pass
  (let [result (gate/evaluate-gates
                [{:check/id :conservation :status :pass
                  :validation-class :validation.class/algebraic-integrity}]
                [{:check/id :budget-balance :status :pass
                  :validation-class :validation.class/payoff-property}]
                [{:property :split-invariance :verdict :verified}]
                []
                :witnesses [{:exercised-fill? true}])]
    (is (= :verified (:overall-verdict result)))))

(deftest combined-gates-blocked-on-integrity-failure
  (let [result (gate/evaluate-gates
                [{:check/id :conservation :status :fail
                  :validation-class :validation.class/algebraic-integrity
                  :details {:imbalance 20}}]
                [{:check/id :budget-balance :status :pass
                  :validation-class :validation.class/payoff-property}]
                [{:property :split-invariance :verdict :verified}]
                []
                :witnesses [{:exercised-fill? true}])]
    (is (= :blocked (:overall-verdict result)))
    (is (= :blocked (:verdict (first (:gates result)))))
    (is (= :blocked (:verdict (second (:gates result)))))
    (is (= :blocked (:verdict (nth (:gates result) 2))))))
