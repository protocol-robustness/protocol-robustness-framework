(ns resolver-sim.benchmark.research-analysis-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.incentive-deviation-domain :as domain]
            [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.research-analysis :as analysis]
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
        outcome-manifest {:execution/command-root (:command/hash research-command)
                          :outcomes/operational-root (root :operational)
                          :outcomes/incentive-compatibility-root (root :ic)
                          :benchmark-outcome/hash (root :outcome)}]
    {:research-command research-command
     :incentive-model incentive-model
     :deviation-domain deviation-domain
     :research-assignment research-assignment
     :outcome-manifest outcome-manifest}))

(defn- output [context classification]
  (analysis/build-output
   (assoc context
          :analysis-engine/id :engine/observed-trace
          :analysis-engine/version "1"
          :analysis-output/proposition-id :claim/incentive-compatibility
          :analysis-output/metric-id :metric/net-payoff-delta
          :analysis-output/result {:result/classification classification
                                   :result/value -1}
          :analysis-output/evidence-root (root classification))))

(deftest generic-verification-preserves-theorem-boundary
  (let [context (fixture)
        analysis-output (output context :supported)
        verification (analysis/verify-output (assoc context :analysis-output analysis-output))
        claim (analysis/derive-claim analysis-output verification)]
    (is (:valid? (analysis/validate-output analysis-output)))
    (is (analysis/verification-valid? verification))
    (is (= :not-established (:research-analysis-verification/claim-status verification)))
    (is (= :not-established (:research-claim/status claim)))
    (is (:valid? (analysis/validate-claim claim)))))

(deftest counterexamples-are-comparable-without-being-upgraded-to-a-proof
  (let [context (fixture)
        analysis-output (output context :counterexample-found)
        verification (analysis/verify-output (assoc context :analysis-output analysis-output))
        claim (analysis/derive-claim analysis-output verification)]
    (is (= :counterexample-found (:research-analysis-verification/claim-status verification)))
    (is (= :counterexample-found (:research-claim/status claim)))
    (is (not (:valid? (analysis/validate-claim
                       (assoc claim :research-claim/status :verified-general)))))))

(deftest verifier-rejects-substituted-analysis-output
  (let [context (fixture)
        analysis-output (output context :supported)
        verification (analysis/verify-output
                      (assoc context :analysis-output
                             (assoc analysis-output :analysis-output/domain-root (root :other-domain))))]
    (is (= :invalid (:research-analysis-verification/status verification)))
    (is (some #{:output-root-mismatch :output-domain-mismatch}
              (:errors verification)))))
