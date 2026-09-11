(ns resolver-sim.assurance.admission-fixed-point-test
  "Fixed-point verification tests for custody admission decisions.

  Tests that:
  1. Valid artifacts pass both canonical fixed-point and verification fixed-point
  2. Tampered artifacts still pass canonical serialization (bytes stable)
     but verification shows different results (hash-integrity failure)
  3. Decision roots differ between tampered and committed artifacts
  4. Blocking reasons and check IDs are canonically ordered"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.custody :as custody]
            [resolver-sim.assurance.admission-fixed-point :as afp]))

(defn- sample-evidence-input
  "Single adjustment evidence input for testing."
  []
  [{:held-adjustment/id "held-adjustment-1"
    :held/direction :in :token :USDC :amount 1000
    :held/before 0 :held/after 1000
    :held/account :escrow-principal :owner/address "0xalice"
    :held/workflow-id 1 :held/reason :escrow-principal-deposited
    :held/position-id [:held/position :USDC :escrow-principal 1]}])

(defn- two-adjustment-evidence
  "Two adjustment evidence input for testing multi-artifact scenarios."
  []
  [{:held-adjustment/id "held-adjustment-1"
    :held/direction :in :token :USDC :amount 1000
    :held/before 0 :held/after 1000
    :held/account :escrow-principal :owner/address "0xalice"
    :held/workflow-id 1 :held/reason :escrow-principal-deposited
    :held/position-id [:held/position :USDC :escrow-principal 1]}
   {:held-adjustment/id "held-adjustment-2"
    :held/direction :out :token :USDC :amount 400
    :held/before 1000 :held/after 600
    :held/account :escrow-principal :owner/address "0xalice"
    :held/workflow-id 1 :held/reason :escrow-settlement-released
    :held/position-id [:held/position :USDC :escrow-principal 1]}])

(defn- build-chained-artifacts
  "Build artifacts with proper :held/previous-artifact-hash chain links.
   This is needed for multi-artifact verification to pass the
   :held-custody/predecessor-continuity check."
  [evidence-input]
  (reduce (fn [acc adjustment]
            (let [prev-hash (when (seq acc)
                              (:artifact/hash (last acc)))
                  art (custody/build-held-custody-artifact
                       (assoc adjustment :held/previous-artifact-hash prev-hash))]
              (conj acc art)))
          []
          (sort-by custody/held-adjustment-order evidence-input)))

(deftest canonical-artifact-passes-fixed-point
  (testing "a valid custody artifact passes both canonical and verification fixed-point"
    (let [evidence (sample-evidence-input)
          artifacts (vec (vals (custody/rebuild-held-custody-artifacts evidence)))
          result (afp/admission-fixed-point artifacts evidence)]
      (is (true? (:canonical-fixed-point? result)))
      (is (true? (:verification-fixed-point? result)))
      (is (true? (:decision-root-consistent? result)))
      (is (true? (:holds? result)))
      (is (true? (get-in result [:original :admitted?])))
      (is (empty? (get-in result [:original :blocking-reasons])))
      (is (empty? (get-in result [:original :failed-check-ids]))))))

(deftest tampered-artifact-still-canonical-but-rejected
  (testing "a tampered artifact survives canonical round-trip but fails verification"
    (let [evidence (sample-evidence-input)
          artifacts (vec (vals (custody/rebuild-held-custody-artifacts evidence)))
          tampered (mapv #(assoc % :amount 1100 :held/after 1100) artifacts)
          result (afp/admission-fixed-point tampered evidence)]
      (is (true? (:canonical-fixed-point? result))
          "canonical round-trip should be stable for tampered artifacts")
      (is (true? (:verification-fixed-point? result))
          "verification of tampered artifacts should be deterministic and consistent")
      (is (false? (get-in result [:original :admitted?]))
          "tampered artifact must be rejected")
      (is (contains? (set (get-in result [:original :blocking-reasons]))
                     :held-custody/hash-integrity))
      (is (contains? (set (get-in result [:original :failed-check-ids]))
                     :held-custody/hash-integrity)))))

(deftest decision-roots-differ-for-tampered-artifact
  (testing "tampering changes the decision-root"
    (let [evidence (sample-evidence-input)
          artifacts (vec (vals (custody/rebuild-held-custody-artifacts evidence)))
          tampered (mapv #(assoc % :amount 1100 :held/after 1100) artifacts)
          canonical-result (afp/admission-fixed-point artifacts evidence)
          tampered-result (afp/admission-fixed-point tampered evidence)]
      (is (not= (get-in canonical-result [:original :decision-root])
                (get-in tampered-result [:original :decision-root]))))))

(deftest multi-adjustment-artifact-passes-fixed-point
  (testing "multi-adjustment artifacts pass fixed-point checks"
    (let [evidence (two-adjustment-evidence)
          artifacts (vec (build-chained-artifacts evidence))
          result (afp/admission-fixed-point artifacts evidence)]
      (is (true? (:canonical-fixed-point? result)))
      (is (true? (:verification-fixed-point? result)))
      (is (true? (:holds? result)))
      (is (true? (get-in result [:original :admitted?]))))))

(deftest tampered-multi-adjustment-detected
  (testing "tampering one of multiple adjustments is detected"
    (let [evidence (two-adjustment-evidence)
          artifacts (vec (build-chained-artifacts evidence))
          tampered (update artifacts 0 #(assoc % :amount 1100 :held/after 1100))
          result (afp/admission-fixed-point tampered evidence)]
      (is (true? (:canonical-fixed-point? result)))
      (is (false? (get-in result [:original :admitted?])))
      (is (contains? (set (get-in result [:original :blocking-reasons]))
                     :held-custody/hash-integrity)))))

(deftest blocking-reasons-are-canonically-ordered
  (testing "blocking-reasons and failed-check-ids are sorted vectors"
    (let [evidence (sample-evidence-input)
          artifacts (vec (vals (custody/rebuild-held-custody-artifacts evidence)))
          tampered (mapv #(assoc % :amount 1100 :held/after 1100) artifacts)
          original (afp/verify-and-project tampered evidence)]
      (is (vector? (:blocking-reasons original)))
      (is (vector? (:failed-check-ids original)))
      (is (= (:blocking-reasons original)
             (vec (sort-by str (:blocking-reasons original))))
          "blocking-reasons must be sorted by str")
      (is (= (:failed-check-ids original)
             (vec (sort-by str (:failed-check-ids original))))
          "failed-check-ids must be sorted by str"))))

(deftest decision-stability-report-includes-fixed-point
  (testing "decision-stability-report includes full fixed-point results"
    (let [evidence (sample-evidence-input)
          artifacts (vec (vals (custody/rebuild-held-custody-artifacts evidence)))
          report (afp/decision-stability-report artifacts evidence)]
      (is (contains? report :fixed-point))
      (is (contains? report :decision-root))
      (is (contains? report :admitted?))
      (is (contains? report :blocking-reasons))
      (is (contains? report :failed-check-ids))
      (is (contains? report :subject-root))
      (is (contains? report :evidence-root))
      (is (true? (get-in report [:fixed-point :holds?]))
          "canonical artifact's fixed-point should hold"))))