(ns resolver-sim.conformance.outcome-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.conformance.outcome :as outcome]))

(deftest non-success-outcomes-are-not-claimable
  (doseq [cls [:not-executable :not-evaluated :incomplete-evidence
               :incompatible-profile :execution-failed :comparison-diverged
               :invariant-failed :reproduction-mismatch :claim-not-permitted]]
    (testing (str cls)
      (is (not (:outcome/claimable? (outcome/outcome {:class cls :reason :x}))))
      (is (outcome/known-outcome-class? cls)))))

(deftest success-outcomes-are-claimable
  (doseq [cls [:equivalent :reproduced :candidate-compatible :accepted-divergence]]
    (is (:outcome/claimable? (outcome/outcome {:class cls :reason :ok})))))

(deftest outcome-shape
  (let [o (outcome/outcome {:class :not-executable
                            :reason :missing-capability
                            :details [{:capability :x}]})]
    (is (= :not-executable (:outcome/class o)))
    (is (= :missing-capability (:outcome/reason o)))
    (is (= [{:capability :x}] (:outcome/details o)))
    (is (false? (:outcome/claimable? o)))))
