(ns resolver-sim.conformance.plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.conformance.plan :as plan]))

(def ^:private trace-profile
  {:profile/id :sew-trace-equivalence.v1
   :profile/version 1
   :profile/verdict-policy
   {:claim-classes [:attested :reproduced :candidate-compatible
                    :accepted-divergence :not-evaluated]
    :derivation-boundaries
    [:simulation-result :export :generated-fixture :sync :solidity-fixture :replay]}})

(def ^:private subject-set
  {:subject-set/root "sha256:subjects"
   :subjects [:sew-001 :sew-002]
   :classification {:included [:sew-001 :sew-002] :excluded []}})

(deftest plan-steps-derived-from-boundaries
  (let [steps (plan/build-plan-steps trace-profile)]
    (is (some #(= :schema-validation (:step/id %)) steps))
    (is (some #(= :semantic-validation (:step/id %)) steps))
    (is (some #(= :capability-check (:step/id %)) steps))
    (is (some #(= :replay (:step/id %)) steps))
    (is (some #(= :reconciliation (:step/id %)) steps))
    (is (some #(= :attestation (:step/id %)) steps))))

(deftest plan-topologically-valid
  (let [steps (plan/build-plan-steps trace-profile)]
    (is (plan/plan-topologically-valid? steps)))
  (testing "a broken DAG (dependency on a not-yet-produced receipt) is invalid"
    (is (not (plan/plan-topologically-valid?
              [{:step/id :replay :requires [:capability-receipt] :produces [:replay-receipt]}
               {:step/id :capability-check :requires [] :produces [:capability-receipt]}])))))

(deftest plan-complete-against-profile
  (let [steps (plan/build-plan-steps trace-profile)]
    (is (plan/plan-complete? trace-profile steps))
    (testing "omitting capability-check makes the plan incomplete"
      (let [incomplete (remove #(= :capability-check (:step/id %)) steps)]
        (is (not (plan/plan-complete? trace-profile incomplete)))))))

(deftest plan-content-addressed-and-deterministic
  (let [p1 (plan/build-plan trace-profile subject-set)
        p2 (plan/build-plan trace-profile subject-set)]
    (is (string? (:plan/root p1)))
    (is (= (:plan/root p1) (:plan/root p2)))
    (is (= (:profile/root p1) (:profile/root p2)))
    (is (= "sha256:subjects" (:subject-set/root p1)))
    ;; changing the subject set changes the plan root
    (is (not= (:plan/root p1)
              (:plan/root (plan/build-plan trace-profile
                                           (assoc subject-set :subject-set/root "sha256:other")))))))
