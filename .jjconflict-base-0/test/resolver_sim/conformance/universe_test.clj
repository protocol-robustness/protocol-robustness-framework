(ns resolver-sim.conformance.universe-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.conformance.coverage :as coverage]))

(deftest universe-split-partition
  (let [ex (coverage/exclusion {:subject/id "sew-005"
                                :reason "escalation requires DecentralizedResolutionModule"
                                :class :unsupported-capability
                                :profile-root "sha256:p"
                                :evidence-root "sha256:e"})
        split (coverage/universe-split
               ["sew-001" "sew-002" "sew-005"]
               ["sew-001" "sew-002"]
               [ex])]
    (is (:partition-ok? split))
    (is (string? (:universe/root split)))
    (is (string? (:included-subject-set/root split)))
    (is (string? (:exclusion-set/root split)))
    (is (= ["sew-001" "sew-002"] (:included split)))
    (is (= ["sew-005"] (:excluded split)))))

(deftest universe-split-overlap-rejected
  (let [ex (coverage/exclusion {:subject/id "sew-001" :reason "x" :class :not-selected})
        split (coverage/universe-split ["sew-001" "sew-002"] ["sew-001" "sew-002"] [ex])]
    (is (not (:partition-ok? split)))))

(deftest universe-split-incomplete-rejected
  (let [split (coverage/universe-split ["sew-001" "sew-002" "sew-003"] ["sew-001"] [])]
    (is (not (:partition-ok? split)))))

(deftest structured-exclusion-validation
  (let [v (coverage/validate-exclusions
           [(coverage/exclusion {:subject/id "a" :reason "r" :class :not-selected})
            {:subject/id "b" :exclusion/class :not-selected} ; missing reason
            {:subject/id "c" :exclusion/reason "r" :exclusion/class :bogus-class} ; unknown class
            {:exclusion/reason "r" :exclusion/class :not-selected}])] ; missing subject
    (is (= 3 (count v)))
    (is (some #(= :violation/missing-exclusion-reason (:violation/id %)) v))
    (is (some #(= :violation/unknown-exclusion-class (:violation/id %)) v))
    (is (some #(= :violation/missing-exclusion-subject (:violation/id %)) v)))
  (testing "valid exclusions pass"
    (is (empty? (coverage/validate-exclusions
                 [(coverage/exclusion {:subject/id "a" :reason "r" :class :superseded})])))))
