(ns resolver-sim.assurance.held-admission-test
  "Tests for the core-owned held-mutation admission boundary.

   Separates semantic operation classification (core-owned, works WITHOUT the
   optional held-custody override extension) from exceptional override
   resolution (delegated to the selected physical package via the core
   capability registry). Ordinary ingress must keep working with no extension;
   only :held-custody/force-auth-mutation requires the exceptional capability
   and fails closed when the package is absent."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.held-admission :as admission]))

(deftest semantic-operation-classification-is-core-owned
  (testing "ordinary ingress operations are classified without any extension"
    (doseq [op [:sew/escrow-principal-deposited :sew/appeal-bond-posted
                :sew/resolver-yield-accrued :sew/deferred-yield-reserved
                :sew/yield-accrued :sew/bounty-custody-reserve]]
      (is (= :ordinary (admission/semantic-operation-class op)))))
  (testing "only the force-auth mutation requires the exceptional capability"
    (is (= :force-authorisation-override
           (admission/semantic-operation-class :held-custody/force-auth-mutation))))
  (testing "unknown operations fail closed to :never-overrideable"
    (is (= :never-overrideable
           (admission/semantic-operation-class :some/unclassified)))))

(deftest ordinary-ingress-proceeds-without-the-extension
  (testing "every ordinary ingress proceeds with no force permit required"
    (doseq [op [:sew/escrow-principal-deposited :sew/appeal-bond-posted
                :sew/resolver-yield-accrued :sew/deferred-yield-reserved
                :sew/yield-accrued :sew/bounty-custody-reserve]]
      (let [d (admission/admit-held-mutation {:operation-id op})]
        (is (= :proceed-ordinary (:admission d)) (str op))
        (is (= :ordinary (:semantic-operation-class d)))))))

(deftest unknown-operation-never-proceeds
  (testing "an unclassified operation rejects regardless of any permit"
    (let [d (admission/admit-held-mutation {:operation-id :some/unclassified
                                            :permits [{:authorization/id "p"}]})]
      (is (= :reject (:admission d)))
      (is (= :never-overrideable (:classification d))))))

(deftest force-auth-mutation-fails-closed-when-extension-absent
  (testing "with no physical held-custody override package registered, the
            exceptional override fails closed before mutation"
    (let [d (admission/admit-held-mutation
             {:operation-id :held-custody/force-auth-mutation
              :scope {:token :USDC :amount 100} :permits []})]
      (is (= :reject (:admission d)))
      (is (= :forbidden (:classification d)))
      (is (some #{:resolution-invalid}
                (:blocking-reasons d))))
    (testing "a permit cannot satisfy the override when the package is absent"
      (let [d (admission/admit-held-mutation
               {:operation-id :held-custody/force-auth-mutation
                :scope {:token :USDC :amount 100}
                :permits [{:authorization/id "any-permit"}]})]
        (is (= :reject (:admission d)))
        (is (some #{:resolution-invalid}
                  (:blocking-reasons d)))))))