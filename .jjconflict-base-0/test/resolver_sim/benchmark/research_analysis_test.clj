(ns resolver-sim.benchmark.research-analysis-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.incentive-deviation-domain :as domain]
            [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.benchmark.research-analysis-closure :as closure]
            [resolver-sim.benchmark.research-assignment :as assignment]
            [resolver-sim.benchmark.research-command :as command]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(defn- root [label]
  (hash-ref/sha256-ref (hc/domain-hash :evidence-record {:label label})))

(defn- fixture []
  (let [subject (root :subject)
        incentive-model (model/build-model
                         {:incentive-model/id :model/sew-incentives
                          :incentive-model/subject-root subject
                          :incentive-model/participant-roles [:actor/challenger]
                          :incentive-model/payoff-interpretation :net-payoff
                          :incentive-model/rewards {:challenge-bounty :bounded}
                          :incentive-model/penalties {}
                          :incentive-model/costs {}
                          :incentive-model/evaluator-semantics-root (root :evaluator)
                          :incentive-model/policy-roots [(root :policy)]})
        deviation-domain (domain/build-domain
                          {:deviation-domain/id :domain/observed-challenge
                           :deviation-domain/subject-root subject
                           :deviation-domain/incentive-model-root (:incentive-model/root incentive-model)
                           :deviation-domain/baseline-strategy :strategy/honest
                           :deviation-domain/participants [:actor/challenger]
                           :deviation-domain/deviations [:strategy/frivolous-challenge]
                           :deviation-domain/coalition-scope :none
                           :deviation-domain/constraints {:trace-count 1}
                           :deviation-domain/evaluation-method :observed-single-trace})
        research-command (command/build-command
                          {:schema-version command/schema-version-v2
                           :command/id :command/research-ic
                           :command/type :benchmark-evaluation
                           :command/argv ["prf" "benchmark" "run"]
                           :command/includes [{:kind :research-scope/analysis :ref :research-analysis/incentive-compatibility}]
                           :command/environment-root (root :environment)
                           :command/runner-root (root :runner)
                           :command/input-root subject
                           :command/output-root (root :output)})
        research-assignment (assignment/build-assignment
                             {:research-assignment/id :assignment/research-ic
                              :research-assignment/environment-hash (root :environment)
                              :research-assignment/policy-hash (root :assignment-policy)
                              :research-assignment/review-round-hash (root :round)
                              :research-assignment/request-root (root :request)
                              :research-assignment/target {:target/kind :governance-mandated
                                                           :target/public-force-authorisation-scope-hash (root :scope)
                                                           :target/workflow-id 0
                                                           :target/reason :research}
                              :research-assignment/command-root (:command/hash research-command)
                              :research-assignment/plan-root (root :plan)})
        outcome-manifest (outcome/build-manifest
                          {:benchmark/content-root subject
                           :benchmark/model-root (:incentive-model/root incentive-model)
                           :execution/status :completed
                           :execution/command-root (:command/hash research-command)
                           :outcomes/operational-root (root :operational)
                           :outcomes/incentive-compatibility-root (root :ic)})]
    {:research-command research-command
     :incentive-model incentive-model
     :deviation-domain deviation-domain
     :research-assignment research-assignment
     :outcome-manifest outcome-manifest}))

(defn- output [context classification]
  (let [descriptor (analysis/build-result-descriptor
                    {:measure/id :metric/net-payoff-delta
                     :measure/value-domain :integer
                     :measure/unit :unit/abstract
                     :measure/direction :lower-is-better
                     :measure/comparison-semantics :exact-integer})
        closure-report (closure/verify-closure context)]
    (analysis/build-output
     (assoc context
            :analysis-output/closure-root (:research-analysis/root closure-report)
            :analysis-output/result-descriptor descriptor
            :analysis-engine/id :engine/observed-trace
            :analysis-engine/version "1"
            :analysis-output/proposition-id :claim/incentive-compatibility
            :analysis-output/metric-id :metric/net-payoff-delta
            :analysis-output/result {:result/classification classification
                                     :result/value -1
                                     :result/case-root (root :case)
                                     :result/witness-root (when (= :counterexample-found classification)
                                                            (root :witness))
                                     :schema-version analysis/result-schema-version}
            :analysis-output/evidence-root (root classification)))))

(deftest generic-verification-preserves-theorem-boundary
  (let [context (fixture)
        analysis-output (output context :supported)
        verification (analysis/verify-output (assoc context :analysis-output analysis-output))
        claim (analysis/derive-claim analysis-output verification (:incentive-model context))]
    (is (:valid? (analysis/validate-output analysis-output)))
    (is (analysis/verification-valid? verification))
    (is (= :not-established (:research-analysis-verification/claim-status verification)))
    (is (= :not-established (:research-claim/status claim)))
    (is (= (:incentive-model/subject-root (:incentive-model context))
           (:research-claim/subject-root claim)))
    (is (= (:incentive-model/root (:incentive-model context))
           (:research-claim/model-root claim)))
    (is (not= (:research-claim/subject-root claim) (:research-claim/model-root claim)))
    (is (:valid? (analysis/validate-claim claim)))))

(deftest claim-root-binds-both-subject-and-model-roots
  (let [context (fixture)
        analysis-output (output context :supported)
        verification (analysis/verify-output (assoc context :analysis-output analysis-output))
        claim (analysis/derive-claim analysis-output verification (:incentive-model context))
        changed-subject (assoc claim :research-claim/subject-root (root :other-subject))
        changed-model (assoc claim :research-claim/model-root (root :other-model))]
    (is (not= (:research-claim/root claim) (analysis/claim-root changed-subject)))
    (is (not= (:research-claim/root claim) (analysis/claim-root changed-model)))
    (is (false? (:valid? (analysis/validate-claim changed-subject))))
    (is (false? (:valid? (analysis/validate-claim changed-model))))))

(deftest counterexamples-are-comparable-without-being-upgraded-to-a-proof
  (let [context (fixture)
        analysis-output (output context :counterexample-found)
        verification (analysis/verify-output (assoc context :analysis-output analysis-output))
        claim (analysis/derive-claim analysis-output verification (:incentive-model context))]
    (is (= :counterexample-found (:research-analysis-verification/claim-status verification)))
    (is (= :counterexample-found (:research-claim/status claim)))
    (is (not (:valid? (analysis/validate-claim
                       (assoc claim :research-claim/status :verified-general)))))))

(deftest legacy-claim-schema-is-rejected
  (let [context (fixture)
        analysis-output (output context :supported)
        verification (analysis/verify-output (assoc context :analysis-output analysis-output))
        claim (analysis/derive-claim analysis-output verification (:incentive-model context))]
    (is (false? (:valid? (analysis/validate-claim
                          (assoc claim :schema-version "research-analytical-claim.v1")))))))

(deftest verifier-rejects-substituted-analysis-output
  (let [context (fixture)
        analysis-output (output context :supported)
        verification (analysis/verify-output
                      (assoc context :analysis-output
                             (assoc analysis-output :analysis-output/domain-root (root :other-domain))))]
    (is (= :invalid (:research-analysis-verification/status verification)))
    (is (some #{:output-root-mismatch :output-domain-mismatch}
              (:errors verification)))))

(deftest claim-rejects-verification-for-another-output
  (let [context (fixture)
        output-a (output context :supported)
        output-b (output context :counterexample-found)
        verification-b (analysis/verify-output (assoc context :analysis-output output-b))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (analysis/derive-claim output-a verification-b (:incentive-model context))))))

(deftest verifier-rejects-relationship-valid-but-invalid-outcome-manifest
  (let [context (fixture)
        analysis-output (output context :supported)
        invalid-context (assoc context
                               :outcome-manifest
                               (assoc (:outcome-manifest context)
                                      :benchmark-outcome/hash (root :forged-outcome)))
        verification (analysis/verify-output
                      (assoc invalid-context :analysis-output analysis-output))]
    (is (= :invalid (:research-analysis-verification/status verification)))
    (is (some #{:invalid-outcome-manifest}
              (:errors verification)))))

(deftest derive-claim-rejects-verification-for-another-output
  (let [context (fixture)
        output-a (output context :supported)
        output-b (assoc (output context :supported)
                        :analysis-output/evidence-root (root :other-evidence))
        output-b (assoc output-b
                        :research-analysis-output/root (analysis/output-root output-b))
        verification (analysis/verify-output (assoc context :analysis-output output-a))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (analysis/derive-claim output-b verification (:incentive-model context))))))

(deftest verifier-rejects-fabricated-outcome-manifest
  (let [context (fixture)
        analysis-output (output context :supported)
        fabricated (assoc (:outcome-manifest context) :benchmark-outcome/hash (root :fabricated))
        verification (analysis/verify-output (assoc context
                                                    :outcome-manifest fabricated
                                                    :analysis-output analysis-output))]
    (is (= :invalid (:research-analysis-verification/status verification)))
    (is (some #{:invalid-outcome-manifest} (:errors verification)))))

(deftest verifier-rejects-substituted-closure
  (let [context (fixture)
        analysis-output (output context :supported)
        verification (analysis/verify-output
                      (assoc context
                             :analysis-output
                             (assoc analysis-output
                                    :analysis-output/closure-root (root :other-closure))))]
    (is (= :invalid (:research-analysis-verification/status verification)))
    (is (some #{:output-root-mismatch :output-closure-mismatch}
              (:errors verification)))))
