(ns resolver-sim.commands.research-command-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.incentive-deviation-domain :as domain]
            [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.research-assignment :as assignment]
            [resolver-sim.benchmark.research-command :as command]
            [resolver-sim.benchmark.research-workflow :as workflow]
            [resolver-sim.commands.research :as research]
            [resolver-sim.composition.semantic :as composition]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(defn- root [label]
  (hash-ref/sha256-ref (hc/domain-hash :evidence-record {:label label})))

(defn- write-input! [value]
  (let [file (doto (java.io.File/createTempFile "research-command" ".edn")
               (.deleteOnExit))]
    (spit file (pr-str value))
    (.getPath file)))

(defn- invoke [handler input]
  (let [result (atom nil)]
    (with-out-str (reset! result (handler {:input (write-input! input)})))
    @result))

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
        semantic-composition (let [composed (:composition
                                             (composition/compose-authoritative
                                              :development [] {:schemas {} :effect-schemas {}} {}))]
                               (assoc composed
                                      :semantic-composition/version 1
                                      :semantic-composition/root (:root composed)))
        context {:research-command research-command
                 :incentive-model incentive-model
                 :deviation-domain deviation-domain
                 :research-assignment research-assignment
                 :outcome-manifest manifest
                 :semantic-composition semantic-composition}]
    (assoc context :execution (workflow/record-execution context))))

(defn- with-execution [context changes]
  (let [execution (merge (:execution context) changes)]
    (assoc context :execution (assoc execution :research-execution/root
                                     (workflow/execution-root execution)))))

(defn- persisted [context]
  (-> context
      (update :semantic-composition composition/portable-body)
      (update-in [:execution :research-execution/trace] dissoc :trace/commitment)))

(deftest out-of-band-submit-is-admitted-only-after-verification
  (let [out-of-band (with-execution (fixture) {:research-execution/origin :out-of-band})
        invalid (assoc-in out-of-band [:execution :research-execution/outcome-root] (root :substituted))]
    (is (= 0 (:exit-code (invoke research/submit (persisted out-of-band)))))
    (is (= :accepted (get-in (invoke research/submit (persisted out-of-band))
                             [:result :submission/status])))
    (is (= 1 (:exit-code (invoke research/submit (persisted invalid)))))
    (is (= :rejected (get-in (invoke research/submit (persisted invalid))
                             [:result :submission/status])))))

(deftest valid-disagreement-is-a-successful-classification
  (let [context (with-execution (fixture) {:research-execution/classification :disagreement})
        result (invoke research/submit (persisted context))]
    (is (= 0 (:exit-code result)))
    (is (= :disagreement (get-in result [:result :submission/execution-classification])))))

(deftest unsupported-and-unavailable-are-explicit-non-successes
  (doseq [classification [:unsupported :unavailable]]
    (let [context (with-execution (fixture) {:research-execution/classification classification})
          result (invoke research/submit (persisted context))]
      (is (= 1 (:exit-code result)))
      (is (= classification (get-in result [:result :submission/execution-classification])))
      (is (= :accepted (get-in result [:result :submission/status]))))))

(deftest diff-excludes-runtime-only-variation-from-canonical-differences
  (let [left (fixture)
        right (assoc-in left [:execution :runtime/worker] "worker-2")
        result (invoke research/diff {:left (persisted left)
                                      :right (persisted right)})]
    (is (= 0 (:exit-code result)))
    (is (empty? (get-in result [:result :comparison/canonical-differences])))
    (is (true? (get-in result [:result :comparison/equivalent?])))))
