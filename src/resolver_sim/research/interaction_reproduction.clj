(ns resolver-sim.research.interaction-reproduction
  "Narrow structured-result adapter for the existing exact interaction engine.
   The qualification computation and its validators remain unchanged."
  (:require [resolver-sim.evidence.forensic-graph :as forensic]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.research.coordinate-interaction :as engine]
            [resolver-sim.research.execution-assurance :as assurance]
            [resolver-sim.research.reproduction-fixture :as reproduction]))

(def kind :research/integer-difference-of-differences-v1)

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
  (try (= input (build-input (:computation/input input)))
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

(defn execute [input impl]
  (when-not (and (valid-input? input) (assurance/valid-implementation? impl)
                 (= kind (:implementation/algorithm-id impl))
                 (= :clojure/jvm (:implementation/language-runtime-family impl))
                 (= (:implementation/artifact-root impl)
                    (:implementation/artifact-root
                     (implementation (:resolved-research-dependency-set/root impl)
                                     (:implementation/semantic-version impl)))))
    (throw (ex-info "Invalid engine reproduction inputs/implementation" {})))
  (let [base {:artifact/schema reproduction/execution-schema
              :reproduction-input/root (:reproduction-input/root input)
              :research-implementation-identity/root
              (:research-implementation-identity/root impl)}
        body (try
               (let [{:keys [x-axis y-axis levels observations]} (:computation/input input)]
                 (assoc base :execution/outcome :completed
                        :execution/fresh-result
                        (engine/difference-of-differences x-axis y-axis levels observations)))
               (catch Exception e
                 (assoc base :execution/outcome :failed
                        :execution/failure-kind :computation-failed
                        :execution/failure-class (.getName (class e)))))]
    (assoc body :reproduction-execution/root (reproduction/execution-root body))))

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
           (and (= #{:artifact/schema :reproduction-input/root
                     :research-implementation-identity/root :execution/outcome
                     :execution/fresh-result :reproduction-execution/root} (set (keys e)))
                (valid-result? (:execution/fresh-result e)))
           (reproduction/valid-execution? e)))
    (catch Exception _ false)))

(defn compare-execution [execution original]
  (when-not (and (valid-execution? execution) (map? original))
    (throw (ex-info "Missing or invalid fresh execution" {})))
  (let [fresh (:execution/fresh-result execution)
        original-valid? (try (valid-result? original) (catch Exception _ false))
        ;; Result identity includes all observation, axis, level and scope bindings.
        scope-keys [:interaction/method :interaction/x-axis-semantics-root
                    :interaction/y-axis-semantics-root :interaction/levels
                    :interaction/constituent-observation-roots
                    :interaction/research-analysis-roots :interaction/arithmetic]
        comparable? (and original-valid?
                         (= (select-keys fresh scope-keys) (select-keys original scope-keys))
                         (every? #(contains? #{:equal :different} (:interaction/status %))
                                 [fresh original]))
        fresh-root (:research-coordinate-interaction/root fresh)
        original-root (when original-valid? (:research-coordinate-interaction/root original))
        status (cond (= :failed (:execution/outcome execution)) :failed
                     (not comparable?) :not-comparable
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
