(ns resolver-sim.protocols.sew.apply-slash-distribution-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.economics.slash-distribution-application-plan :as plan]
            [resolver-sim.protocols.sew.apply-slash-distribution :as adapter]))

(def application-plan
  {:plan/idempotency-key [:slash-distribution-applied 7 "0xChallenger"]
   :plan/distribution-root "sha256:distribution"
   :plan/hash "sha256:plan"
   :plan/gross-amount 100
   :plan/allocation-credits {:sew.allocation/insurance 10
                             :sew.allocation/protocol 20
                             :sew.allocation/retained 30}
   :plan/payables [{:payable/hash "sha256:payable"
                    :payable/beneficiary "0xChallenger"
                    :payable/amount 40}]
   :plan/backing-records [{:backing/hash "sha256:backing"}]
   :plan/preconditions {:final-conservation true}})

(defn- with-valid-plan [f]
  (with-redefs [plan/verify-application-plan (constantly {:valid? true})]
    (f)))

(deftest application-validates-before-mutating-and-applies-all-sew-effects
  (with-valid-plan
    (fn []
      (let [result (adapter/apply-plan-to-world application-plan {} {})]
        (is (false? (:idempotent? result)))
        (is (= 10 (get-in result [:world :bond-distribution :insurance])))
        (is (= 20 (get-in result [:world :bond-distribution :protocol])))
        (is (= 30 (get-in result [:world :retained-slash-reserves])))
        (is (= 40 (get-in result [:world :claimable-v2 7
                                 :liability/challenge-bounty "0xChallenger"])))
        (is (= "sha256:distribution"
               (get-in result [:world :slash-distribution-applied 7 "0xChallenger"])))
        (is (= (:plan/payables application-plan) (:payables result)))
        (is (= (:plan/backing-records application-plan) (:backings result)))))))

(deftest reapplying-the-same-plan-is-idempotent
  (with-valid-plan
    (fn []
      (let [world (assoc-in {} (:plan/idempotency-key application-plan)
                            (:plan/distribution-root application-plan))
            result (adapter/apply-plan-to-world application-plan world {})]
        (is (:idempotent? result))
        (is (= world (:world result)))))))

(deftest application-rejects-invalid-plans-before-mutation
  (with-redefs [plan/verify-application-plan (constantly {:valid? false
                                                           :errors [:invalid-plan]})]
    (is (= {:valid? false :errors [:invalid-plan]}
           (adapter/validate-plan-for-application application-plan {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pre-mutation validation failed"
                          (adapter/apply-plan-to-world application-plan {} {})))))

(deftest application-receipt-binds-state-and-artifact-references
  (with-valid-plan
    (fn []
      (let [result (adapter/apply-with-receipt application-plan {} {:context {:run/id "run-1"}})
            receipt (:receipt result)]
        (is (= "sha256:plan" (:receipt/plan-root receipt)))
        (is (= "sha256:distribution" (:receipt/distribution-root receipt)))
        (is (= ["sha256:payable"] (:receipt/payable-roots receipt)))
        (is (= ["sha256:backing"] (:receipt/backing-roots receipt)))
        (is (= {:run/id "run-1"} (:receipt/context receipt)))
        (is (string? (:receipt/hash receipt)))))))
