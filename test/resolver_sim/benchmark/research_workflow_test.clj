(ns resolver-sim.benchmark.research-workflow-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.incentive-deviation-domain :as domain]
            [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.research-assignment :as assignment]
            [resolver-sim.benchmark.research-command :as command]
            [resolver-sim.benchmark.research-workflow :as workflow]
            [resolver-sim.composition.command-lineage :as lineage]
            [resolver-sim.composition.semantic :as composition]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(defn- root [label]
  (hash-ref/sha256-ref (hc/domain-hash :evidence-record {:label label})))

(defn- fixture []
  (let [subject (root :sew-subject)
        research-command (command/build-command
                          {:schema-version command/schema-version-v2
                           :command/id :research/sew-observed-ic
                           :command/type :benchmark-evaluation
                           :command/argv ["prf" "benchmark" "run"]
                           :command/includes [{:kind :research-scope/analysis
                                               :ref :research-analysis/incentive}
                                              {:kind :research-scope/analysis
                                               :ref :research-analysis/incentive-compatibility}]
                           :command/environment-root (root :environment)
                           :command/runner-root (root :runner)
                           :command/input-root subject
                           :command/output-root (root :output)})
        incentive-model (model/build-model
                         {:incentive-model/id :model/sew
                          :incentive-model/subject-root subject
                          :incentive-model/participant-roles [:actor/challenger]
                          :incentive-model/payoff-interpretation :net-payoff
                          :incentive-model/rewards {:challenge :bounded}
                          :incentive-model/penalties {:slash :bounded}
                          :incentive-model/costs {:bond :included}
                          :incentive-model/evaluator-semantics-root (root :evaluator)
                          :incentive-model/policy-roots [(root :policy)]})
        deviation-domain (domain/build-domain
                          {:deviation-domain/id :domain/observed
                           :deviation-domain/subject-root subject
                           :deviation-domain/incentive-model-root (:incentive-model/root incentive-model)
                           :deviation-domain/baseline-strategy :strategy/honest
                           :deviation-domain/participants [:actor/challenger]
                           :deviation-domain/deviations [:strategy/challenge]
                           :deviation-domain/coalition-scope :none
                           :deviation-domain/constraints {:trace-count 1}
                           :deviation-domain/evaluation-method :observed-single-trace})
        research-assignment (assignment/build-assignment
                             {:research-assignment/id :assignment/sew-observed-ic
                              :research-assignment/environment-hash (root :environment)
                              :research-assignment/policy-hash (root :assignment-policy)
                              :research-assignment/review-round-hash (root :round)
                              :research-assignment/request-root (root :request)
                              :research-assignment/target {:target/kind :research
                                                           :target/public-force-authorisation-scope-hash (root :scope)
                                                           :target/workflow-id 0
                                                           :target/reason :research}
                              :research-assignment/command-root (:command/hash research-command)
                              :research-assignment/plan-root (root :plan)})
        manifest (outcome/build-manifest
                  {:benchmark/content-root subject
                   :benchmark/model-root (root :benchmark-model)
                   :benchmark/evaluation-policy-root (root :evaluation-policy)
                   :execution/status :completed
                   :execution/model-instance-root (root :model-instance)
                   :execution/plan-root (root :plan)
                   :execution/parameter-domain-root (root :parameters)
                   :execution/sampling-policy-root (root :sampling)
                   :execution/generated-case-set-root (root :case-set)
                   :execution/command-root (:command/hash research-command)
                   :outcomes/operational-root (root :operational)
                   :outcomes/incentive-root (root :incentive)
                   :outcomes/incentive-compatibility-root (root :ic)})
        semantic-composition (composition/build
                              {:semantic-composition/version 1
                               :semantic-composition/profile :development
                               :semantic-composition/resolution-root (root :extension-resolution)})
        context {:research-command research-command
                 :incentive-model incentive-model
                 :deviation-domain deviation-domain
                 :research-assignment research-assignment
                 :outcome-manifest manifest
                 :semantic-composition semantic-composition}
        execution (workflow/record-execution context)]
    (assoc context :execution execution)))

(deftest out-of-band-submission-is-admitted-only-after-full-verification
  (let [context (fixture)
        submitted (assoc-in context [:execution :research-execution/origin] :out-of-band)
        submitted (assoc-in submitted [:execution :research-execution/root]
                            (workflow/execution-root (:execution submitted)))
        result (workflow/submit submitted)]
    (is (= :out-of-band (:submission/origin result)))
    (is (= :accepted (:submission/status result)))
    (is (not (:submission/execution-failed? result)))))

(deftest submission-fails-closed-on-output-or-extension-substitution
  (let [context (fixture)
        wrong-output (assoc-in context [:execution :research-execution/outcome-root] (root :substituted))
        wrong-extension (assoc-in context [:execution :research-execution/composition-root] (root :substituted-provider))]
    (is (= :rejected (:submission/status (workflow/submit wrong-output))))
    (is (= :rejected (:submission/status (workflow/submit wrong-extension))))))

(deftest valid-disagreement-is-not-execution-failure
  (let [context (fixture)
        execution (assoc (:execution context) :research-execution/classification :disagreement)
        execution (assoc execution :research-execution/root (workflow/execution-root execution))
        verified (workflow/verify (assoc context :execution execution))]
    (is (:verified? verified))
    (is (= :disagreement (get-in verified [:submission :submission/execution-classification])))
    (is (false? (get-in verified [:submission :submission/execution-failed?])))))

(deftest trace-command-and-assignment-bindings-fail-closed
  (let [context (fixture)
        bad-command (assoc-in context [:execution :research-execution/command-root] (root :other-command))
        bad-assignment (assoc-in context [:execution :research-execution/assignment-root] (root :other-assignment))
        bad-trace (assoc-in context [:execution :research-execution/trace-root] (root :other-trace))]
    (is (some #{:research/execution-command-mismatch} (:errors (workflow/validate-execution bad-command))))
    (is (some #{:research/execution-assignment-mismatch} (:errors (workflow/validate-execution bad-assignment))))
    (is (some #{:research/execution-trace-mismatch} (:errors (workflow/validate-execution bad-trace))))))

(deftest exact-and-independent-reproduction-remain-distinct
  (let [context (fixture)
        exact (workflow/reproduce :exact-environment context context)
        independent-execution (assoc (:execution context) :research-execution/origin :out-of-band)
        independent-execution (assoc independent-execution :research-execution/root
                                     (workflow/execution-root independent-execution))
        independent (workflow/reproduce :independent-conforming context
                                        (assoc context :execution independent-execution))]
    (is (= :reproduced (:reproduction/status exact)))
    (is (= :comparable (:reproduction/status independent)))
    (is (false? (:reproduction/exact-execution-match? independent)))))

(deftest differential-ignores-runtime-provenance-and-reports-semantic-change
  (let [context (fixture)
        runtime-variant (assoc-in context [:execution :runtime/worker] "worker-2")
        same (workflow/differential context runtime-variant)
        changed (assoc-in context [:outcome-manifest :outcomes/incentive-root] (root :different-incentive))
        different (workflow/differential context changed)]
    (is (:comparison/equivalent? same))
    (is (empty? (:comparison/forbidden-canonical-differences same)))
    (is (not (:comparison/equivalent? different)))
    (contains? (:comparison/canonical-differences different) :output-roots)))

(defn- cc3-provenance-source []
  (let [state-a (root :cc3-state-a)
        state-b (root :cc3-state-b)
        state-c (root :cc3-state-c)
        command (fn [action input resulting]
                  (lineage/build-command
                   {:command/action action
                    :command/input-state-root input
                    :command/resulting-state-root resulting
                    :command/built-with-includes [{:kind :shared-state :ref input}]}))
        commands [(command :research/a state-a state-b)
                  (command :research/b state-b state-c)
                  (command :research/c state-c state-a)]]
    {:commands commands
     :concatenations (lineage/build-concatenation-chain commands)
     :combination (lineage/build-combination
                   (:command/built-with-includes (peek commands)))}))

(defn- v2-context []
  (let [context (dissoc (fixture) :execution)
        source (cc3-provenance-source)
        execution (workflow/record-execution-v2
                   (assoc context :executable-command-provenance-input source))]
    (assoc context
           :executable-command-provenance-input source
           :execution execution)))

(deftest cc3-provenance-is-bound-into-research-execution-v2
  (let [context (v2-context)
        execution (:execution context)
        trace (:research-execution/trace execution)
        source (:executable-command-provenance-input context)
        reordered (assoc source :concatenations (vec (reverse (:concatenations source))))
        broken-concatenation (assoc (first (:concatenations source))
                                    :concatenation/join-state
                                    (root :broken-join))
        broken-concatenation (assoc broken-concatenation
                                    :concatenation/root
                                    (lineage/concatenation-root broken-concatenation))
        broken (assoc source :concatenations
                      (assoc (:concatenations source) 0 broken-concatenation))
        substituted-combination (lineage/build-combination
                                 [{:kind :shared-state :ref (root :substituted-include)}])
        substituted (assoc source :combination substituted-combination)]
    (is (= workflow/schema-version-v2 (:schema-version execution)))
    (is (= command/command-trace-v3-schema-version (:trace/schema-version trace)))
    (is (= 4 (:trace/component-count trace)))
    (is (= :accepted (:submission/status (workflow/submit context))))
    (is (= :rejected (:submission/status
                      (workflow/submit (assoc context :executable-command-provenance-input substituted)))))
    (is (= :rejected (:submission/status
                      (workflow/submit (assoc context :executable-command-provenance-input reordered)))))
    (is (= :rejected (:submission/status
                      (workflow/submit (assoc context :executable-command-provenance-input broken)))))))
