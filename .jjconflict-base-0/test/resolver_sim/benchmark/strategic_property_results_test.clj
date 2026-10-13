(ns resolver-sim.benchmark.strategic-property-results-test
  "Adapter tests for routing strategic-property verdicts into the structured
   :property-violated result vocabulary and the strategic gate."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.strategic-property-results :as spr]
            [resolver-sim.validation.gate :as gate]))

(def clean-artifact
  {:summary {:states-examined 100}
   :properties
   [{:property :strategy/split-invariance
     :status :verified :verdict :verified
     :state-count 100 :violation-count 0}]})

(def violated-artifact
  {:summary {:states-examined 100}
   :properties
   [{:property :allocation/exact-merge-invariance
     :status :violated :verdict :violated
     :state-count 100 :violation-count 3
     :counterexample {:claims [1 1 1] :liquidity 1}
     :sample-counterexamples [{:claims [0 1] :error 1}]}]})

(def declared-property-artifact
  {:summary {:states-examined 100}
   :properties
   [{:property :strategy/split-invariance
     :status :verified :verdict :verified
     :state-count 100 :violation-count 0
     :property-role :declared-property
     :diagnostic-transform {:id :split
                            :role :diagnostic-transform
                            :semantic-purpose :bounded-counterexample-search}}]})

(def diagnostic-observation-artifact
  {:summary {:states-examined 100}
   :properties
   [{:property :allocation/exact-merge-invariance
     :status :violated :verdict :violated
     :state-count 100 :violation-count 1
     :property-role :diagnostic-observation
     :diagnostic-transform {:id :merge
                            :role :diagnostic-transform
                            :semantic-purpose :bounded-counterexample-search}
     :counterexample {:claims [1 1 1] :liquidity 1}}]})

(def inconclusive-artifact
  {:summary {:states-examined 100}
   :properties
   [{:property :strategy/split-invariance
     :status :pending :verdict nil
     :state-count 100 :violation-count 0
     :property-role :declared-property}]})

(deftest verified-property-maps-to-pass-result
  (let [results (spr/strategic-properties->results clean-artifact)
        r (first results)]
    (is (= :strategy/split-invariance (:property r)))
    (is (= :pass (:status r)))
    (is (nil? (:reason r)))
    (is (= :validation.class/deviation-resistance (:validation-class r)))
    (is (= 100 (get-in r [:observed :state-count])))))

(deftest violated-property-maps-to-property-violated-fail
  (let [results (spr/strategic-properties->results violated-artifact)
        r (first results)]
    (is (= :allocation/exact-merge-invariance (:property r)))
    (is (= :fail (:status r)))
    (is (= :property-violated (:reason r)))
    (is (= :validation.class/deviation-resistance (:validation-class r)))
    (is (some #(= {:claims [1 1 1] :liquidity 1} %) (:offending r)))
    (is (some #(= {:claims [0 1] :error 1} %) (:offending r)))
    (is (= 3 (get-in r [:observed :violation-count])))))

(deftest empty-artifact-produces-empty-results
  (is (= [] (spr/strategic-properties->results nil)))
  (is (= [] (spr/strategic-properties->deviation-results nil))))

(deftest deviation-results-match-gate-input-contract
  (let [dev (spr/strategic-properties->deviation-results violated-artifact)]
    (is (= [{:property :allocation/exact-merge-invariance :verdict :violated}]
           dev))
    (let [g (gate/evaluate-strategic-gate
             {:gate :economic-model :verdict :pass}
             dev
             [])]
      (is (= :violated (:verdict g)))
      (is (some #(= :property-violated (:reason %))
                (:properties (gate/evaluate-strategic-gate
                              {:gate :economic-model :verdict :pass}
                              (spr/strategic-properties->results violated-artifact)
                              [])))))))

(deftest verified-deviation-results-pass-strategic-gate
  (let [dev (spr/strategic-properties->deviation-results clean-artifact)
        g (gate/evaluate-strategic-gate {:gate :economic-model :verdict :pass} dev [])]
    (is (= :verified (:verdict g)))))

;; ---------------------------------------------------------------------------
;; Gate 2 evidence-contract tests
;; ---------------------------------------------------------------------------

(deftest evidence-metadata-for-declared-verified-property
  (testing "declared + verified produces bounded-empirical-evidence claim status"
    (let [results (spr/strategic-properties->results declared-property-artifact)
          r (first results)]
      (is (= :bounded-deviation-search (:evidence/kind r)))
      (is (= :no-counterexample-found (:evaluation/status r)))
      (is (= :bounded-empirical-evidence (:claim/status r)))
      (is (= :declared-property (:property-role r)))
      (is (= :pass (:status r))))))

(deftest evidence-metadata-for-declared-violated-property
  (testing "declared + violated produces counterexample-found claim status"
    (let [artifact (assoc-in declared-property-artifact
                             [:properties 0 :status] :violated)
          results (spr/strategic-properties->results artifact)
          r (first results)]
      (is (= :bounded-deviation-search (:evidence/kind r)))
      (is (= :counterexample-found (:evaluation/status r)))
      (is (= :counterexample-found (:claim/status r)))
      (is (= :declared-property (:property-role r)))
      (is (= :fail (:status r))))))

(deftest evidence-metadata-for-diagnostic-observation
  (testing "diagnostic observation always produces :unestablished claim status"
    (let [results (spr/strategic-properties->results diagnostic-observation-artifact)
          r (first results)]
      (is (= :bounded-deviation-search (:evidence/kind r)))
      (is (= :counterexample-found (:evaluation/status r)))
      (is (= :unestablished (:claim/status r)))
      (is (= :diagnostic-observation (:property-role r)))
      (is (= :fail (:status r))))))

(deftest evidence-metadata-for-diagnostic-verified
  (testing "diagnostic + verified still produces :unestablished claim status"
    (let [artifact {:summary {:states-examined 100}
                    :properties
                    [{:property :allocation/exact-merge-invariance
                      :status :verified :verdict :verified
                      :state-count 100 :violation-count 0
                      :property-role :diagnostic-observation}]}
          results (spr/strategic-properties->results artifact)
          r (first results)]
      (is (= :bounded-deviation-search (:evidence/kind r)))
      (is (= :no-counterexample-found (:evaluation/status r)))
      (is (= :unestablished (:claim/status r)))
      (is (= :diagnostic-observation (:property-role r))))))

(deftest evidence-metadata-for-inconclusive-pending-result
  (testing "pending/missing result produces inconclusive evaluation and claim status"
    (let [results (spr/strategic-properties->results inconclusive-artifact)
          r (first results)]
      (is (= :bounded-deviation-search (:evidence/kind r)))
      (is (= :inconclusive (:evaluation/status r)))
      (is (= :inconclusive (:claim/status r)))
      (is (= :declared-property (:property-role r))))))

(deftest evaluation-status-no-counterexample-found-differs-from-claim-status
  (testing "evaluation/status and claim/status are independent fields"
    (let [declared-verified (first (spr/strategic-properties->results declared-property-artifact))
          declared-violated (first (spr/strategic-properties->results
                                    (assoc-in declared-property-artifact
                                              [:properties 0 :status] :violated)))
          diagnostic-verified (first (spr/strategic-properties->results
                                      {:summary {:states-examined 100}
                                       :properties
                                       [{:property :strategy/split-invariance
                                         :status :verified :verdict :verified
                                         :state-count 100 :violation-count 0
                                         :property-role :diagnostic-observation}]}))]
      ;; declared + verified: evaluation=no-counterexample-found, claim=bounded-empirical-evidence
      (is (= :no-counterexample-found (:evaluation/status declared-verified)))
      (is (= :bounded-empirical-evidence (:claim/status declared-verified)))
      ;; declared + violated: both are counterexample-found
      (is (= :counterexample-found (:evaluation/status declared-violated)))
      (is (= :counterexample-found (:claim/status declared-violated)))
      ;; diagnostic + verified: evaluation=no-counterexample-found, but claim=unestablished
      (is (= :no-counterexample-found (:evaluation/status diagnostic-verified)))
      (is (= :unestablished (:claim/status diagnostic-verified))))))

(deftest deviation-results-for-declared-property-enter-gate
  (testing "declared property deviation results enter the strategic gate"
    (let [dev (spr/strategic-properties->deviation-results declared-property-artifact)]
      (is (= 1 (count dev)))
      (is (= :declared-property (:property-role (first dev))))
      (is (= :verified (:verdict (first dev))))
      (let [g (gate/evaluate-strategic-gate
               {:gate :economic-model :verdict :pass}
               dev [])]
        (is (= :verified (:verdict g)))))))

(deftest deviation-results-for-diagnostic-observation-enter-gate-as-property
  (testing "diagnostic observation deviation results also enter the gate (non-gating only at artifact level)"
    (let [dev (spr/strategic-properties->deviation-results diagnostic-observation-artifact)]
      (is (= 1 (count dev)))
      (is (= :diagnostic-observation (:property-role (first dev))))
      (is (= :violated (:verdict (first dev))))
      (let [g (gate/evaluate-strategic-gate
               {:gate :economic-model :verdict :pass}
               dev [])]
        (is (= :violated (:verdict g))
            "gate itself sees the violation regardless of role")))))

(deftest result-counts-agree-between-artifact-and-adapter
  (testing "adapter produces one result per property entry"
    (doseq [artifact [clean-artifact violated-artifact
                      declared-property-artifact diagnostic-observation-artifact
                      inconclusive-artifact]]
      (let [raw-count (count (:properties artifact))
            results (spr/strategic-properties->results artifact)
            dev-results (spr/strategic-properties->deviation-results artifact)]
        (is (= raw-count (count results))
            "results count matches property count")
        (is (= raw-count (count dev-results))
            "deviation-results count matches property count")))))

;; ---------------------------------------------------------------------------
;; Gate 2: :not-applicable vs :unestablished distinction
;; ---------------------------------------------------------------------------

(deftest not-applicable-and-unestablished-are-distinct-concepts
  (testing ":not-applicable is a check-level status; :unestablished is a claim-level status"
    (let [;; declared + verified: claim/status = :bounded-empirical-evidence
          declared-verified (first (spr/strategic-properties->results declared-property-artifact))
          ;; diagnostic + verified: claim/status = :unestablished
          diagnostic-verified (first (spr/strategic-properties->results
                                      {:summary {:states-examined 100}
                                       :properties
                                       [{:property :strategy/split-invariance
                                         :status :verified :verdict :verified
                                         :state-count 100 :violation-count 0
                                         :property-role :diagnostic-observation}]}))]
      ;; :unestablished only appears in claim/status for diagnostic observations
      (is (= :unestablished (:claim/status diagnostic-verified))
          "diagnostic observation claim status is :unestablished")
      (is (not= :unestablished (:claim/status declared-verified))
          "declared property claim status is NOT :unestablished")
      ;; :not-applicable does NOT appear in property result maps
      ;; (it's a closed-form check status, not a property-level status)
      (is (not= :not-applicable (:claim/status declared-verified))
          ":not-applicable is not a claim/status value")
      (is (not= :not-applicable (:claim/status diagnostic-verified))
          ":not-applicable is not a claim/status value")
      (is (not= :not-applicable (:evaluation/status declared-verified))
          ":not-applicable is not an evaluation/status value")
      (is (not= :not-applicable (:evaluation/status diagnostic-verified))
          ":not-applicable is not an evaluation/status value"))))

(deftest not-applicable-is-pass-status-in-gate
  (testing ":not-applicable counts as a pass in gate evaluation"
    (is (contains? gate/pass-statuses :not-applicable)
        ":not-applicable is in gate/pass-statuses")
    (is (not (contains? gate/block-statuses :not-applicable))
        ":not-applicable is NOT in gate/block-statuses")))

(deftest unestablished-is-not-a-gate-status
  (testing ":unestablished is not used as a gate check status"
    (is (not (contains? gate/pass-statuses :unestablished))
        ":unestablished is NOT in gate/pass-statuses")
    (is (not (contains? gate/block-statuses :unestablished))
        ":unestablished is NOT in gate/block-statuses")))

;; ---------------------------------------------------------------------------
;; Gate 2: Diagnostic-non-gating at artifact level
;; ---------------------------------------------------------------------------

(deftest diagnostic-violation-does-not-prevent-artifact-admission
  (testing "diagnostic observation violation doesn't prevent artifact-level admission"
    (let [;; Mix of declared (verified) and diagnostic (violated)
          mixed-artifact {:summary {:states-examined 100}
                          :properties
                          [{:property :strategy/split-invariance
                            :status :verified :verdict :verified
                            :state-count 100 :violation-count 0
                            :property-role :declared-property}
                           {:property :allocation/exact-merge-invariance
                            :status :violated :verdict :violated
                            :state-count 100 :violation-count 1
                            :property-role :diagnostic-observation
                            :counterexample {:claims [1] :liquidity 1}}]}
          results (spr/strategic-properties->results mixed-artifact)
          declared-results (filterv #(= :declared-property (:property-role %)) results)
          diagnostic-results (filterv #(= :diagnostic-observation (:property-role %)) results)]
      ;; declared properties are verified
      (is (every? #(= :pass (:status %)) declared-results)
          "declared properties pass")
      ;; diagnostic observations can fail without preventing admission
      (is (some #(= :fail (:status %)) diagnostic-results)
          "diagnostic observation fails")
      ;; claim/status for diagnostic is always :unestablished regardless of outcome
      (is (every? #(= :unestablished (:claim/status %)) diagnostic-results)
          "diagnostic claim status is always :unestablished"))))

(deftest diagnostic-pass-does-not-establish-claim
  (testing "diagnostic observation pass does NOT establish a claim"
    (let [results (spr/strategic-properties->results
                   {:summary {:states-examined 100}
                    :properties
                    [{:property :strategy/split-invariance
                      :status :verified :verdict :verified
                      :state-count 100 :violation-count 0
                      :property-role :diagnostic-observation}]})
          r (first results)]
      ;; Even though the property passed, claim/status is still :unestablished
      (is (= :pass (:status r)) "property passes")
      (is (= :no-counterexample-found (:evaluation/status r))
          "evaluation says no counterexample found")
      (is (= :unestablished (:claim/status r))
          "but claim is NOT established — it's :unestablished"))))

;; ---------------------------------------------------------------------------
;; Gate 2: Declared-only gate subjects
;; ---------------------------------------------------------------------------

(deftest only-declared-properties-establish-claims
  (testing "only declared properties produce bounded-empirical-evidence claim status"
    (let [declared (first (spr/strategic-properties->results declared-property-artifact))
          diagnostic (first (spr/strategic-properties->results diagnostic-observation-artifact))]
      (is (= :bounded-empirical-evidence (:claim/status declared))
          "declared property establishes claim")
      (is (= :unestablished (:claim/status diagnostic))
          "diagnostic observation does NOT establish claim"))))

(deftest deviation-results-enter-gate-regardless-of-role
  (testing "both declared and diagnostic deviation results enter the gate"
    (let [mixed-dev (spr/strategic-properties->results
                     {:summary {:states-examined 100}
                      :properties
                      [{:property :strategy/split-invariance
                        :status :verified :verdict :verified
                        :state-count 100 :violation-count 0
                        :property-role :declared-property}
                       {:property :allocation/exact-merge-invariance
                        :status :verified :verdict :verified
                        :state-count 100 :violation-count 0
                        :property-role :diagnostic-observation}]})
          g (gate/evaluate-strategic-gate
             {:gate :economic-model :verdict :pass}
             mixed-dev [])]
      ;; Both properties appear in gate properties
      (is (= 2 (count (:properties g)))
          "both declared and diagnostic enter gate")
      (is (some #(= :declared-property (:property-role %))
                (:properties g))
          "declared property is in gate properties")
      (is (some #(= :diagnostic-observation (:property-role %))
                (:properties g))
          "diagnostic observation is in gate properties"))))

(deftest diagnostic-violation-blocks-gate-but-not-claim
  (testing "diagnostic violation blocks the strategic gate but doesn't establish a claim"
    (let [dev-results (spr/strategic-properties->deviation-results diagnostic-observation-artifact)
          g (gate/evaluate-strategic-gate
             {:gate :economic-model :verdict :pass}
             dev-results [])]
      ;; Gate is violated because diagnostic observation violated
      (is (= :violated (:verdict g))
          "gate is violated")
      ;; But the claim status is still :unestablished
      (let [results (spr/strategic-properties->results diagnostic-observation-artifact)
            diag-result (first (filter #(= :diagnostic-observation (:property-role %)) results))]
        (is (= :unestablished (:claim/status diag-result))
            "diagnostic claim is :unestablished even though gate is violated")))))
