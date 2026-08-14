(ns resolver-sim.benchmark.research-analysis-closure-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.incentive-deviation-domain :as domain]
            [resolver-sim.benchmark.research-analysis-closure :as closure]
            [resolver-sim.benchmark.research-command :as command]
            [resolver-sim.benchmark.research-assignment :as assignment]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(defn- root [label]
  (hash-ref/sha256-ref (hc/domain-hash :evidence-record {:label label})))

(defn- fixture []
  (let [subject (root :subject)
        incentive-model (model/build-model
                         {:incentive-model/id :model/sew-incentives
                          :incentive-model/subject-root subject
                          :incentive-model/participant-roles [:actor/challenger :actor/resolver]
                          :incentive-model/payoff-interpretation :net-payoff
                          :incentive-model/rewards {:challenge-bounty :bounded}
                          :incentive-model/penalties {:resolver-slash :proportional}
                          :incentive-model/costs {:appeal-bond :included}
                          :incentive-model/evaluator-semantics-root (root :evaluator)
                          :incentive-model/policy-roots [(root :policy)]})
        deviation-domain (domain/build-domain
                          {:deviation-domain/id :domain/observed-challenge
                           :deviation-domain/subject-root subject
                           :deviation-domain/incentive-model-root (:incentive-model/root incentive-model)
                           :deviation-domain/baseline-strategy :strategy/honest
                           :deviation-domain/participants [:actor/challenger :actor/resolver]
                           :deviation-domain/deviations [:strategy/frivolous-challenge]
                           :deviation-domain/coalition-scope :none
                           :deviation-domain/constraints {:trace-count 1}
                           :deviation-domain/evaluation-method :observed-single-trace})
        research-command (command/build-command
                          {:schema-version command/schema-version-v2
                           :command/id :command/research-ic
                           :command/type :benchmark-evaluation
                           :command/argv ["prf" "benchmark" "run"]
                           :command/includes [{:kind :research-scope/analysis :ref :research-analysis/incentive}
                                              {:kind :research-scope/analysis :ref :research-analysis/incentive-compatibility}]
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
        manifest {:execution/command-root (:command/hash research-command)
                  :outcomes/operational-root (root :operational)
                  :outcomes/incentive-root (root :incentive)
                  :outcomes/incentive-compatibility-root (root :ic)
                  :benchmark-outcome/hash (root :outcome)}]
    {:research-command research-command :incentive-model incentive-model
     :deviation-domain deviation-domain :research-assignment research-assignment
     :outcome-manifest manifest}))

(deftest model-and-domain-roots-commit-semantic-content
  (let [{:keys [incentive-model deviation-domain]} (fixture)]
    (is (model/model-valid? incentive-model))
    (is (domain/domain-valid? deviation-domain))
    (is (not= (:incentive-model/root incentive-model)
              (:incentive-model/root (model/build-model
                                      (assoc (dissoc incentive-model :incentive-model/root)
                                             :incentive-model/rewards {:challenge-bounty :unbounded})))))
    (is (not= (:deviation-domain/root deviation-domain)
              (:deviation-domain/root (domain/build-domain
                                       (assoc (dissoc deviation-domain :deviation-domain/root)
                                              :deviation-domain/deviations [:strategy/collude])))))))

(deftest valid-closure-is-derived-and-never-general-ic-proof
  (let [report (closure/verify-closure (fixture))]
    (is (closure/closure-valid? report))
    (is (= :evidence/observed-single-trace (:research-analysis/evidence-class report)))
    (is (false? (:research-analysis/general-ic-proven? report)))))

(deftest closure-rejects-tampering-and-missing-requested-output
  (let [f (fixture)
        changed-model (assoc-in f [:incentive-model :incentive-model/rewards] {:challenge-bounty :unbounded})
        missing-output (assoc-in f [:outcome-manifest] (dissoc (:outcome-manifest f) :outcomes/incentive-compatibility-root))
        wrong-assignment (assoc-in f [:research-assignment :research-assignment/command-root] (root :wrong-command))]
    (is (= :invalid (:research-analysis/status (closure/verify-closure changed-model))))
    (is (= :invalid (:research-analysis/status (closure/verify-closure missing-output))))
    (is (= :invalid (:research-analysis/status (closure/verify-closure wrong-assignment))))))

(deftest unsupported-deviation-method-fails-closed
  (let [f (fixture)
        unsupported (assoc-in f [:deviation-domain :deviation-domain/evaluation-method] :counterfactual-exhaustive)
        report (closure/verify-closure unsupported)]
    (is (= :invalid (:research-analysis/status report)))
    (is (some #(re-find #"unsupported-evaluation-method" %) (:errors report)))))
