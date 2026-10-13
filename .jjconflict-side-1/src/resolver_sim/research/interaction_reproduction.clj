(ns resolver-sim.research.interaction-reproduction
  "Narrow structured-result adapter for the existing exact interaction engine.
   The qualification computation and its validators remain unchanged."
  (:require [resolver-sim.evidence.forensic-graph :as forensic]
            [resolver-sim.benchmark.research-definition :as research]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.research.analysis-compiler :as compiler]
            [resolver-sim.research.coordinate-interaction :as engine]
            [resolver-sim.research.interaction-contrast :as contrast]
            [resolver-sim.research.dependency-assurance :as dependency]
            [resolver-sim.research.execution-manifest :as manifest]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.parallel-executor :as executor]
            [resolver-sim.research.result-store :as store]
            [resolver-sim.research.scheduler-harness :as harness]
            [resolver-sim.research.reproduction-fixture :as reproduction]))

(def kind :research/integer-difference-of-differences-v1)
(def dependency-closed-kind :research/dependency-closed-interaction-v1)
(def dependency-closed-implementation-version "1.0.0")

(def ^:private dependency-closed-source-files
  ["src/resolver_sim/benchmark/research_definition.clj"
   "src/resolver_sim/research/analysis_compiler.clj"
   "src/resolver_sim/research/coordinate_interaction.clj"
   "src/resolver_sim/research/dependency_assurance.clj"
   "src/resolver_sim/research/execution_cell.clj"
   "src/resolver_sim/research/execution_cell_runner.clj"
   "src/resolver_sim/research/execution_manifest.clj"
   "src/resolver_sim/research/interaction_contrast.clj"
   "src/resolver_sim/research/interaction_reproduction.clj"
   "src/resolver_sim/research/parallel_executor.clj"
   "src/resolver_sim/research/result_store.clj"
   "src/resolver_sim/research/scheduler_harness.clj"])

(defn- root [domain value]
  (ref/sha256-ref (hc/domain-hash domain value)))

(defn- implementation-source-roots []
  (into (sorted-map)
        (map (fn [path] [path (ref/sha256-ref-file path)])
             dependency-closed-source-files)))

(defn- dependency-closed-artifact-root []
  (root :research-dependency-closed-interaction-implementation-v1
        (implementation-source-roots)))

(defn dependency-closed-source
  "Small real research-definition fixture whose four case cells are freshly
   evaluated by the existing scheduler/cell engine before interaction."
  []
  {:artifact/schema research/source-schema
   :research/id :reproduction/dependency-closed-interaction
   :research/title "Dependency-closed interaction reproduction fixture"
   :research/question "Are scheduler makespan observations recomputed before their exact interaction?"
   :research/vary {:resource/cpu-scale [1 2]
                   :resource/gpu-scale [1 2]}
   :research/measures
   [{:measure/id :scheduler/makespan-ms
     :measure/kind research/requirement-margin-kind
     :measure/domain {:kind :integer :unit :unit/ms}
     :measure/observation {:observation/id :scheduler/makespan-ms}
     :measure/requirement {:value 0 :unit :unit/ms}
     :measure/satisfying-direction :at-least}]})

(declare dependency-closed-input-valid?)

(defn build-dependency-closed-input
  "Build input for a fresh upstream research-cell execution followed by the
   existing interaction computation. No expected/original result is accepted."
  ([] (build-dependency-closed-input
       {:research/source (dependency-closed-source)
        :interaction/x-axis-id :resource/cpu-scale
        :interaction/y-axis-id :resource/gpu-scale
        :interaction/measure-id :scheduler/makespan-ms
        :execution/policy :static
        :execution/repeat {:execution/repeat-id 0
                           :execution/fixture-id :fixture/dependency-closed-interaction}
        :workload/model :scheduler/default-irregular-dag-v1}))
  ([arguments]
   (when-not (and (= #{:research/source :interaction/x-axis-id
                       :interaction/y-axis-id :interaction/measure-id
                       :execution/policy :execution/repeat :workload/model}
                     (set (keys arguments)))
                  (= :scheduler/default-irregular-dag-v1 (:workload/model arguments))
                  (= :static (:execution/policy arguments))
                  (qualified-keyword? (:interaction/x-axis-id arguments))
                  (qualified-keyword? (:interaction/y-axis-id arguments))
                  (qualified-keyword? (:interaction/measure-id arguments))
                  (= #{:execution/repeat-id :execution/fixture-id}
                     (set (keys (:execution/repeat arguments))))
                  (integer? (get-in arguments [:execution/repeat :execution/repeat-id]))
                  (qualified-keyword? (get-in arguments [:execution/repeat
                                                         :execution/fixture-id])))
     (throw (ex-info "Invalid dependency-closed reproduction inputs" {})))
   (let [body {:artifact/schema reproduction/input-schema
               :computation/kind dependency-closed-kind
               :computation/input arguments}
         input (assoc body :reproduction-input/root (reproduction/input-root body))]
     (when-not (dependency-closed-input-valid? input)
       (throw (ex-info "Invalid dependency-closed reproduction input" {})))
     input)))

(defn- dependency-closed-input-valid? [input]
  (try
    (let [{:keys [research/source interaction/x-axis-id interaction/y-axis-id
                  interaction/measure-id execution/policy execution/repeat] :as args}
          (:computation/input input)
          frozen (research/freeze-research source)
          vary (get-in source [:research/vary])
          measures (get-in frozen [:research-definition :research/measures])]
      (and (= reproduction/input-schema (:artifact/schema input))
           (= dependency-closed-kind (:computation/kind input))
           (= (:reproduction-input/root input)
              (reproduction/input-root (dissoc input :reproduction-input/root)))
           (= #{:research/source :interaction/x-axis-id :interaction/y-axis-id
                :interaction/measure-id :execution/policy :execution/repeat :workload/model}
              (set (keys args)))
           (= :scheduler/default-irregular-dag-v1 (:workload/model args))
           (= :static policy)
           (not= x-axis-id y-axis-id)
           (= [1 2] (get vary x-axis-id))
           (= [1 2] (get vary y-axis-id))
           (= 2 (count vary))
           (= 4 (count (get-in frozen [:research-definition :research/cases])))
           (= 1 (count (filter #(= measure-id (:measure/id %)) measures)))
           (integer? (:execution/repeat-id repeat))
           (qualified-keyword? (:execution/fixture-id repeat))))
    (catch Exception _ false)))

(defn- dependency-closed-dependency-context [input]
  (let [frozen (research/freeze-research
                (get-in input [:computation/input :research/source]))
        code-root (dependency-closed-artifact-root)
        source-root (root :research-dependency-closed-source-v1
                          (get-in input [:computation/input :research/source]))
        entries [{:dependency/id :dependency/research-definition
                  :dependency/class :scenario-corpus
                  :dependency/visibility :public
                  :dependency/acquisition-mode :public-by-root
                  :dependency/artifact-root (:research-definition/root frozen)
                  :dependency/provenance-roots [source-root]
                  :dependency/metadata {:fixture/id :fixture/dependency-closed-interaction}}
                 {:dependency/id :dependency/reproduction-engine
                  :dependency/class :clojure-model
                  :dependency/visibility :public
                  :dependency/acquisition-mode :public-by-root
                  :dependency/artifact-root code-root
                  :dependency/provenance-roots [code-root]
                  :dependency/metadata {:semantic-version dependency-closed-implementation-version}}
                 {:dependency/id :dependency/dependency-manifest
                  :dependency/class :runtime-toolchain
                  :dependency/visibility :public
                  :dependency/acquisition-mode :public-by-root
                  :dependency/artifact-root (ref/sha256-ref-file "deps.edn")
                  :dependency/provenance-roots [code-root]
                  :dependency/metadata {:manifest/name "deps.edn"}}]
        dep-manifest (dependency/build-manifest entries)
        dep-policy (dependency/build-policy
                    {:required-classes [:clojure-model :runtime-toolchain :scenario-corpus]
                     :allowed-visibility [:public]
                     :allowed-acquisition-modes [:public-by-root]
                     :sealed-environment-root nil})
        resolutions (mapv (fn [entry]
                            {:dependency/id (:dependency/id entry)
                             :dependency/artifact-root (:dependency/artifact-root entry)
                             :resolution/acquisition-mode :public-by-root
                             :resolution/status :resolved
                             :resolution/evidence-root
                             (root :research-dependency-closed-resolution-v1
                                   {:dependency/id (:dependency/id entry)
                                    :dependency/artifact-root (:dependency/artifact-root entry)
                                    :source/root source-root})})
                          entries)
        resolved (dependency/resolve-dependencies dep-manifest dep-policy resolutions)]
    {:frozen frozen :manifest dep-manifest :policy dep-policy
     :resolutions resolutions :resolved resolved :code-root code-root}))

(defn dependency-closed-implementation [input]
  (when-not (dependency-closed-input-valid? input)
    (throw (ex-info "Invalid dependency-closed input for implementation identity" {})))
  (let [{:keys [resolved code-root]} (dependency-closed-dependency-context input)]
    (assurance/build-implementation
     {:algorithm-id dependency-closed-kind
      :artifact-root code-root
      :language-runtime-family :clojure/jvm
      :resolved-dependency-set-root
      (:resolved-research-dependency-set/root resolved)
      :semantic-version dependency-closed-implementation-version})))

(defn- dependency-closed-axis [frozen axis-id]
  (let [axis-root (root :research-dependency-closed-axis-v1
                        {:research-definition/root
                         (get-in frozen [:research-definition :research-definition/root])
                         :axis/id axis-id :axis/levels [1 2]})]
    (engine/build-axis-semantics
     {:axis-root axis-root :axis-kind :parameter :data-kind :integer
      :ordered? true :integer? true :metric? false :additive? false
      :interaction-eligible? true})))

(def ^:private interaction-observation-keys
  [:x1-y1 :x1-y2 :x2-y1 :x2-y2])

(defn- dependency-closed-computation [input]
  (let [{:keys [frozen manifest resolved]}
        (dependency-closed-dependency-context input)
        args (:computation/input input)
        cases (get-in frozen [:research-definition :research/cases])
        policy-root (harness/policy-subject-root :static false)
        workloads (mapv (fn [research-case]
                          (let [parameters (:case/parameters research-case)]
                            (harness/workload
                             {:id (:case/id research-case)
                              :cpu-scale (get parameters (:interaction/x-axis-id args))
                              :gpu-scale (get parameters (:interaction/y-axis-id args))
                              :false-predictions #{}})))
                        cases)
        execution-manifest
        (manifest/build-manifest
         frozen {:policy-subject-roots [policy-root]
                 :case-workloads
                 (into {}
                       (map (fn [research-case workload]
                              [(:case/id research-case)
                               {:workload/root (:workload/root workload)
                                :expected/root (harness/canonical-root workload)}])
                            cases workloads))
                 :repeats [(:execution/repeat args)]})
        scope {:policy-by-subject-root {policy-root :static}
               :workload-by-root (into {} (map (juxt :workload/root identity)
                                               workloads))}
        report (executor/execute-manifest scope execution-manifest)
        result-store (store/new-store)]
    (when-not (every? #(= :complete (:execution/status %)) (:execution/entries report))
      (throw (ex-info "Upstream research cell execution was incomplete"
                      {:execution/statuses (mapv :execution/status
                                                 (:execution/entries report))})))
    (doseq [entry (:execution/entries report)]
      (store/put-result! result-store (:result entry)))
    (let [analysis (compiler/compile-policy-analysis
                    frozen execution-manifest result-store policy-root
                    (:execution/repeat args) report)
          ;; Read the authoritative result store through its public API.
          case-results
          (into {}
                (for [research-case cases
                      :let [cell (some #(when (= (:case/id research-case)
                                                 (:case/id %)) %)
                                       (:dispatch/cells execution-manifest))]]
                  [(:case/id research-case)
                   (store/result-by-cell result-store
                                         (:research-execution-cell/root cell))]))
          x-id (:interaction/x-axis-id args)
          y-id (:interaction/y-axis-id args)
          observations-by-coordinate
          (into {}
                (for [research-case cases
                      :let [parameters (:case/parameters research-case)
                            x (get parameters x-id)
                            y (get parameters y-id)
                            x-label (if (= x 1) :x1 :x2)
                            y-label (if (= y 1) :y1 :y2)
                            coordinate-key (keyword (str (name x-label) "-" (name y-label)))
                            cell (some #(when (= (:case/id research-case) (:case/id %)) %)
                                       (:dispatch/cells execution-manifest))
                            cell-result (case-results (:case/id research-case))
                            value (get-in cell-result [:metrics
                                                       (keyword (name (:interaction/measure-id args)))])
                            observation-body
                            {:research-analysis/root (:research-analysis/root analysis)
                             :accounting-policy/root policy-root
                             :experiment/domain :experiment-domain/scheduler-cell
                             :measure/id (:interaction/measure-id args)
                             :observation/status :different
                             :observation/value value
                             :observation/reason nil}
                            observation (assoc observation-body :observation/root
                                               (root :research-dependency-closed-observation-v1
                                                     {:research-definition/root
                                                      (:research-definition/root frozen)
                                                      :research-analysis/root
                                                      (:research-analysis/root analysis)
                                                      :research-execution-cell/root
                                                      (:research-execution-cell/root cell)
                                                      :research-execution-cell-result/root
                                                      (:research-execution-cell-result/root cell-result)
                                                      :resolved-research-dependency-set/root
                                                      (:resolved-research-dependency-set/root resolved)
                                                      :coordinate [x y]
                                                      :observation observation-body}))]]
                  [coordinate-key observation]))
          observations (into {} (map (fn [k] [k (observations-by-coordinate k)])
                                     interaction-observation-keys))
          interaction (engine/difference-of-differences
                       (dependency-closed-axis frozen x-id)
                       (dependency-closed-axis frozen y-id)
                       {:x1 1 :x2 2 :y1 1 :y2 2}
                       observations)
          ordered-cells (sort-by :research-execution-cell/root
                                 (:dispatch/cells execution-manifest))]
      (when-not (and (= :complete (:analysis/status analysis))
                     (= #{:x1-y1 :x1-y2 :x2-y1 :x2-y2}
                        (set (keys observations)))
                     (every? integer? (map :observation/value (vals observations))))
        (throw (ex-info "Upstream analysis did not yield four comparable observations" {})))
      {:result interaction
       :dependency-set-root (:resolved-research-dependency-set/root resolved)
       :dependency-manifest-root (:research-dependency-manifest/root manifest)
       :execution-manifest-root (:research-execution-manifest/root execution-manifest)
       :analysis-root (:research-analysis/root analysis)
       :cell-result-roots (mapv (fn [cell]
                                  (:research-execution-cell-result/root
                                   (case-results (:case/id cell))))
                                ordered-cells)
       :observation-roots (mapv #(get-in observations [% :observation/root])
                                interaction-observation-keys)})))

(defn- execute-dependency-closed [input impl]
  (when-not (and (dependency-closed-input-valid? input)
                 (assurance/valid-implementation? impl)
                 (= dependency-closed-kind (:implementation/algorithm-id impl))
                 (= :clojure/jvm (:implementation/language-runtime-family impl))
                 (= impl (dependency-closed-implementation input)))
    (throw (ex-info "Invalid dependency-closed reproduction implementation" {})))
  (let [base {:artifact/schema reproduction/execution-schema
              :reproduction-input/root (:reproduction-input/root input)
              :research-implementation-identity/root
              (:research-implementation-identity/root impl)}
        body (try
               (let [{:keys [result dependency-set-root dependency-manifest-root
                             execution-manifest-root analysis-root cell-result-roots
                             observation-roots]}
                     (dependency-closed-computation input)]
                 (assoc base :execution/outcome :completed
                        :execution/fresh-result result
                        :execution/resolved-dependency-set-root dependency-set-root
                        :execution/dependency-manifest-root dependency-manifest-root
                        :execution/upstream-manifest-root execution-manifest-root
                        :execution/upstream-analysis-root analysis-root
                        :execution/upstream-cell-result-roots cell-result-roots
                        :execution/upstream-observation-roots observation-roots))
               (catch Exception e
                 (assoc base :execution/outcome :failed
                        :execution/failure-kind :computation-failed
                        :execution/failure-class (.getName (class e)))))]
    (assoc body :reproduction-execution/root (reproduction/execution-root body))))

(defn build-input [arguments]
  (when-not (and (= #{:x-axis :y-axis :levels :observations} (set (keys arguments)))
                 (= #{:x1 :x2 :y1 :y2} (set (keys (:levels arguments))))
                 (= #{:x1-y1 :x1-y2 :x2-y1 :x2-y2}
                    (set (keys (:observations arguments))))
                 (every? #(= #{:observation/root :research-analysis/root
                               :accounting-policy/root :experiment/domain :measure/id
                               :observation/status :observation/value :observation/reason}
                             (set (keys %))) (vals (:observations arguments)))
                 (every? #(= #{:artifact/schema :axis/root :axis/kind :axis/data-kind
                               :axis/ordered? :axis/integer? :axis/metric? :axis/additive?
                               :axis/interaction-eligible? :axis/semantic-warning
                               :research-axis-semantics/root} (set (keys %)))
                         [(:x-axis arguments) (:y-axis arguments)]))
    (throw (ex-info "Only engine inputs are permitted" {})))
  (let [body {:artifact/schema reproduction/input-schema
              :computation/kind kind :computation/input arguments}]
    (assoc body :reproduction-input/root (reproduction/input-root body))))

(defn valid-input? [input]
  (try
    (case (:computation/kind input)
      :research/integer-difference-of-differences-v1
      (= input (build-input (:computation/input input)))
      :research/dependency-closed-interaction-v1
      (dependency-closed-input-valid? input)
      false)
    (catch Exception _ false)))

(defn implementation
  "Bind the actual engine source bytes and an existing resolved closure root.
   Version changes still mean same-implementation reproduction, not independence."
  [dependency-root version]
  (assurance/build-implementation
   {:algorithm-id kind
    :artifact-root (ref/sha256-ref-file "src/resolver_sim/research/coordinate_interaction.clj")
    :language-runtime-family :clojure/jvm
    :resolved-dependency-set-root dependency-root :semantic-version version}))

(defn contrast-implementation
  "Identify the separately written row-contrast implementation of the same
   interaction contract. Distinctness is at the source-artifact level."
  [dependency-root version]
  (assurance/build-implementation
   {:algorithm-id kind
    :artifact-root (ref/sha256-ref-file "src/resolver_sim/research/interaction_contrast.clj")
    :language-runtime-family :clojure/jvm
    :resolved-dependency-set-root dependency-root :semantic-version version}))

(defn valid-implementation-for-input?
  "Check supported implementation bytes and their existing canonical identity.
   A version/closure change alone does not establish implementation independence."
  [input impl]
  (try
    (and (valid-input? input) (assurance/valid-implementation? impl)
         (if (= dependency-closed-kind (:computation/kind input))
           (= impl (dependency-closed-implementation input))
           (some #(= impl (% (:resolved-research-dependency-set/root impl)
                             (:implementation/semantic-version impl)))
                 [implementation contrast-implementation])))
    (catch Exception _ false)))

(defn execute [input impl]
  (if (= dependency-closed-kind (:computation/kind input))
    (execute-dependency-closed input impl)
    (do
      (when-not (valid-implementation-for-input? input impl)
        (throw (ex-info "Invalid engine reproduction inputs/implementation" {})))
      (let [base {:artifact/schema reproduction/execution-schema
                  :reproduction-input/root (:reproduction-input/root input)
                  :research-implementation-identity/root
                  (:research-implementation-identity/root impl)}
            body (try
                   (let [{:keys [x-axis y-axis levels observations]} (:computation/input input)]
                     (assoc base :execution/outcome :completed
                            :execution/fresh-result
                            ((if (= (:implementation/artifact-root impl)
                                    (:implementation/artifact-root
                                     (contrast-implementation
                                      (:resolved-research-dependency-set/root impl)
                                      (:implementation/semantic-version impl))))
                               contrast/compute engine/difference-of-differences)
                             x-axis y-axis levels observations)))
                   (catch Exception e
                     (assoc base :execution/outcome :failed
                            :execution/failure-kind :computation-failed
                            :execution/failure-class (.getName (class e)))))]
        (assoc body :reproduction-execution/root (reproduction/execution-root body))))))

(defn- valid-result? [r]
  (and (= engine/interaction-schema (:artifact/schema r))
       (contains? #{:equal :different :missing :not-comparable} (:interaction/status r))
       (= (:research-coordinate-interaction/root r) (engine/interaction-root r))))

(defn valid-execution? [e]
  (try
    (and (= reproduction/execution-schema (:artifact/schema e))
         (ref/valid-sha256-ref? (:reproduction-input/root e))
         (ref/valid-sha256-ref? (:research-implementation-identity/root e))
         (= (:reproduction-execution/root e) (reproduction/execution-root e))
         (if (= :completed (:execution/outcome e))
           (if (contains? e :execution/upstream-analysis-root)
             (and (= #{:artifact/schema :reproduction-input/root
                       :research-implementation-identity/root :execution/outcome
                       :execution/fresh-result
                       :execution/resolved-dependency-set-root
                       :execution/dependency-manifest-root
                       :execution/upstream-manifest-root
                       :execution/upstream-analysis-root
                       :execution/upstream-cell-result-roots
                       :execution/upstream-observation-roots
                       :reproduction-execution/root} (set (keys e)))
                  (every? ref/valid-sha256-ref?
                          [(:execution/resolved-dependency-set-root e)
                           (:execution/dependency-manifest-root e)
                           (:execution/upstream-manifest-root e)
                           (:execution/upstream-analysis-root e)])
                  (= 4 (count (:execution/upstream-cell-result-roots e)))
                  (= 4 (count (:execution/upstream-observation-roots e)))
                  ;; Cell roots retain the execution-manifest's canonical cell
                  ;; order. They are not lexically sorted: sorting would erase
                  ;; that source-to-result correspondence.
                  (= 4 (count (set (:execution/upstream-cell-result-roots e))))
                  (= 4 (count (set (:execution/upstream-observation-roots e))))
                  (every? ref/valid-sha256-ref?
                          (concat (:execution/upstream-cell-result-roots e)
                                  (:execution/upstream-observation-roots e)))
                  (valid-result? (:execution/fresh-result e)))
             (and (= #{:artifact/schema :reproduction-input/root
                       :research-implementation-identity/root :execution/outcome
                       :execution/fresh-result :reproduction-execution/root} (set (keys e)))
                  (valid-result? (:execution/fresh-result e))))
           (reproduction/valid-execution? e)))
    (catch Exception _ false)))

(defn comparison-assessment
  "Derived comparability view; no claim authority. Reasons are not an assurance ladder."
  [execution original]
  (when-not (and (valid-execution? execution) (map? original))
    (throw (ex-info "Missing or invalid fresh execution" {})))
  (let [fresh (:execution/fresh-result execution)
        valid? (try (valid-result? original) (catch Exception _ false))
        scope-keys [:interaction/method :interaction/x-axis-semantics-root
                    :interaction/y-axis-semantics-root :interaction/levels
                    :interaction/constituent-observation-roots
                    :interaction/research-analysis-roots :interaction/arithmetic]
        overflow? (or (= "java.lang.ArithmeticException" (:execution/failure-class execution))
                      (when-let [v (:interaction/formula-values fresh)]
                        (try
                          ;; Both implementations must be defined, without promoting
                          ;; checked longs or changing either operation's arithmetic.
                          (not= (+ (v :x2-y2) (- (v :x2-y1)) (- (v :x1-y2)) (v :x1-y1))
                                (- (- (v :x2-y2) (v :x2-y1))
                                   (- (v :x1-y2) (v :x1-y1))))
                          (catch ArithmeticException _ true))))
        reason (cond overflow? :outside-common-arithmetic-domain
                     (= :failed (:execution/outcome execution)) :execution-failed
                     (not valid?) :incompatible-result-schema-or-identity
                     (not= (select-keys fresh scope-keys) (select-keys original scope-keys))
                     :different-semantic-scope
                     (not (every? #(contains? #{:equal :different} (:interaction/status %))
                                  [fresh original])) :unavailable-or-not-comparable-observation)]
    {:comparison/comparable? (nil? reason) :comparison/reason reason}))

(defn compare-execution [execution original]
  (when-not (and (valid-execution? execution) (map? original))
    (throw (ex-info "Missing or invalid fresh execution" {})))
  (let [fresh (:execution/fresh-result execution)
        original-valid? (try (valid-result? original) (catch Exception _ false))
        fresh-root (:research-coordinate-interaction/root fresh)
        original-root (when original-valid? (:research-coordinate-interaction/root original))
        assessment (comparison-assessment execution original)
        status (cond (= :outside-common-arithmetic-domain (:comparison/reason assessment))
                     :not-comparable
                     (= :failed (:execution/outcome execution)) :failed
                     (not (:comparison/comparable? assessment)) :not-comparable
                     (= fresh original) :reproduced
                     :else :counterexample-found)
        body (cond-> {:artifact/schema reproduction/evidence-schema
                      :reproduction-execution/root (:reproduction-execution/root execution)
                      :authoritative-result/root original-root :reproduction/status status}
               fresh (assoc :reproduction/fresh-result-root fresh-root)
               (= status :counterexample-found)
               (assoc :reproduction/counterexample-result fresh
                      :reproduction/counterexample-witness-root fresh-root))]
    (assoc body :reproduction-evidence/root (reproduction/evidence-root body))))

(defn evidence-node [execution original evidence]
  (when-not (= evidence (compare-execution execution original))
    (throw (ex-info "Detached reproduction evidence" {})))
  (forensic/build-forensic-evidence-node
   {:subject {:subject/kind :research/reproduction-evidence
              :subject/root (:reproduction-evidence/root evidence)}
    :role :derived :visibility :public :consequence? true
    :causal-parent-roots
    (vec (distinct (remove nil? [(:reproduction-execution/root execution)
                                 (:reproduction-input/root execution)
                                 (:research-implementation-identity/root execution)
                                 (:authoritative-result/root evidence)])))}))
