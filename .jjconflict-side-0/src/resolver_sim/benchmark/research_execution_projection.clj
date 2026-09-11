(ns resolver-sim.benchmark.research-execution-projection
  "Semantic → execution projection boundary between a frozen research
  definition and the existing benchmark runner.

  Three identities, not two:

    D   frozen research definition   (research-definition.v1)
    C   research case axis           (research-axis.v1, kind :case)
    E   runner execution case set    (generated-case-set.v1)

  C and E are DISTINCT scoped key spaces and answer different questions:

    C : \"what research condition does research-case/key 7 mean?\"
    E : \"what execution does runner case/key / ordinal 7 refer to?\"

  Numeric equality never establishes scoped identity. This is the hop-scope
  lesson applied to research: a research-case key is meaningful only inside its
  case-axis root C, and a runner case key only inside its execution case-set
  root E.

  This namespace introduces the explicit projection P binding D, C, E and the
  research-case → execution mapping:

    {:artifact/schema               \"research-execution-projection.v1\"
     :research-definition/root      D
     :research-case-axis/root       C
     :execution-case-set/root       E
     :case-bindings                 [{:research-case/key N :execution/id sha256} ...]
     :projection/root               P}

  P is the thing that prevents a transplant attack: two research definitions
  D1/C1 and D2/C2 that happen to drive the same execution case set E still get
  P1 != P2, because P commits D and C alongside the shared E.

  PILOT CARDINALITY: build-projection requires exactly one execution per frozen
  research case (and vice versa). It fails closed when repetition, sampling, or
  expansion produces any other cardinality. Extending to research-case → N
  executions is a deliberate later step behind a real stochastic use case.

  Plan entries derived from a research case carry their explicit research
  coordinate (D, C, research-case/key, P) following the existing
  semantic-composition-root precedent, so no consumer ever infers
  \"execution ordinal N == research case N\". Because the runner assembles
  results via (merge plan-entry ...), these fields propagate into execution
  results unchanged."
  (:require [resolver-sim.benchmark.case-set :as case-set]
            [resolver-sim.benchmark.research-definition :as research]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const projection-schema "research-execution-projection.v1")

(defn- sha256-root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))

(defn research-case-axis-root
  "The rooted research case axis C of a frozen definition."
  [frozen]
  (get-in frozen [:derived :case-axis/root]))

(defn research-case-keys
  "The ordered research case integer keys of a frozen definition's case axis C.
   These are authoritative (drawn from the axis), not inferred from position."
  [frozen]
  (mapv :axis/key (get-in frozen [:derived :case-axis :axis/members])))

(defn execution-case-set
  "Build the runner execution case set E from an ordered execution plan."
  [plan]
  (case-set/build-case-set plan))

(defn execution-case-set-root
  "The rooted runner execution case set E for an execution plan."
  [plan]
  (case-set/compute-case-set-root (execution-case-set plan)))

(defn projection-root
  "Root of a projection body (commits D, C, E and the case bindings)."
  [projection]
  (sha256-root :research-execution-projection (dissoc projection :projection/root)))

(defn build-projection
  "Build the semantic → execution projection P for a frozen definition `frozen`
   over an execution `plan`.

   PILOT: requires 1:1 cardinality (one execution per research case). Fails
   closed on any other cardinality. C and E remain distinct scoped key spaces;
   the mapping is explicit and rooted."
  [frozen plan]
  (let [research-cases (get-in frozen [:research-definition :research/cases])
        research-case-keys (research-case-keys frozen)
        definition-root (:research-definition/root frozen)
        case-axis-root (research-case-axis-root frozen)
        ordered (vec (sort-by :execution/ordinal plan))
        e-root (execution-case-set-root ordered)]
    (when-not (= (count research-cases) (count ordered))
      (throw (ex-info "Research case set and execution set cardinality mismatch (pilot requires 1:1)"
                      {:research-case-count (count research-cases)
                       :execution-count (count ordered)
                       :research-case-axis/root case-axis-root
                       :execution-case-set/root e-root})))
    (let [bindings (mapv (fn [research-case-key execution]
                           {:research-case/key research-case-key
                            :execution/id (:execution/id execution)})
                         research-case-keys ordered)
          body {:artifact/schema projection-schema
                :research-definition/root definition-root
                :research-case-axis/root case-axis-root
                :execution-case-set/root e-root
                :case-bindings bindings}]
      (assoc body :projection/root (projection-root body)))))

(defn execution->research-case
  "Resolve the research case key for an execution id under a projection.
   Returns nil when the execution is not part of the projection."
  [projection execution-id]
  (some (fn [binding]
          (when (= execution-id (:execution/id binding))
            (:research-case/key binding)))
        (:case-bindings projection)))

(defn research-case->executions
  "Resolve the execution ids that realize a research case key under a
   projection. PILOT is 1:1, so this returns a single-element vector."
  [projection research-case-key]
  (->> (:case-bindings projection)
       (filter #(= research-case-key (:research-case/key %)))
       (mapv :execution/id)))

(defn verify-projection
  "Fail-closed verification that `projection` is exactly the projection of
   `frozen` over `plan`. Recomputes C, E, P and the case bindings from first
   principles and throws on any disagreement. Returns a summary map when valid.

   This is the ownership-transfer check: a disagreement means the runner's
   manifest no longer corresponds to the research definition, and must be
   treated as 'projection invalid' rather than trusted."
  [frozen plan projection]
  (let [recomputed (build-projection frozen plan)
        problems (cond-> []
                   (not= (:research-definition/root frozen)
                         (:research-definition/root projection))
                   (conj {:reason :definition-root-mismatch})

                   (not= (:research-definition/root frozen)
                         (:research-definition/root recomputed))
                   (conj {:reason :definition-root-recompute-mismatch})

                   (not= (research-case-axis-root frozen)
                         (:research-case-axis/root projection))
                   (conj {:reason :case-axis-root-mismatch})

                   (not= (:execution-case-set/root projection)
                         (:execution-case-set/root recomputed))
                   (conj {:reason :execution-case-set-root-mismatch})

                   (not= (:case-bindings projection)
                         (:case-bindings recomputed))
                   (conj {:reason :case-binding-mismatch})

                   (not= (:projection/root projection)
                         (:projection/root recomputed))
                   (conj {:reason :projection-root-recompute-mismatch}))]
    (when (seq problems)
      (throw (ex-info "Research execution projection is invalid"
                      {:problems problems
                       :expected/definition-root (:research-definition/root frozen)
                       :expected/case-axis-root (research-case-axis-root frozen)
                       :expected/execution-case-set-root (:execution-case-set/root recomputed)
                       :expected/projection-root (:projection/root recomputed)})))
    {:projection/valid? true
     :projection/root (:projection/root projection)
     :research-definition/root (:research-definition/root projection)
     :research-case-axis/root (:research-case-axis/root projection)
     :execution-case-set/root (:execution-case-set/root projection)}))

(defn project-plan-entries
  "Annotate an execution plan so every execution carries its explicit research
   coordinate: D, C, research-case/key, and P. Fails closed if any execution is
   not bound by `projection` to a valid research case key.

   The runner assembles results via (merge plan-entry ...), so these fields
   propagate into execution results unchanged (semantic-composition-root
   precedent)."
  [frozen projection plan]
  (let [ordered (vec (sort-by :execution/ordinal plan))
        execution->key (into {} (map (fn [binding]
                                       [(:execution/id binding)
                                        (:research-case/key binding)])
                                     (:case-bindings projection)))
        valid-keys (set (research-case-keys frozen))]
    (mapv (fn [entry]
            (let [research-case-key (execution->key (:execution/id entry))]
              (when-not (and (some? research-case-key)
                             (contains? valid-keys research-case-key))
                (throw (ex-info "Plan execution lacks a valid research-case coordinate"
                                {:execution/id (:execution/id entry)
                                 :research-case/key research-case-key})))
              (assoc entry
                     :research-definition/root (:research-definition/root frozen)
                     :research-case-axis/root (research-case-axis-root frozen)
                     :research-case/key research-case-key
                     :research-execution-projection/root (:projection/root projection))))
          ordered)))

(defn plan-entry-lineage
  "The committed lineage projection of a research-annotated plan entry: execution
   identity plus the research coordinate. Excludes the heavy :execution/descriptor."
  [entry]
  (select-keys entry [:execution/ordinal :execution/id
                      :research-definition/root :research-case-axis/root
                      :research-case/key :research-execution-projection/root]))

(defn plan-root
  "Committed root over an (optionally research-annotated) execution plan, keyed on
   execution identity plus research lineage. Because it hashes the lineage fields,
   changing D/C/P changes the plan root — the lineage is authoritative, not
   decorative."
  [plan]
  (sha256-root :research-execution-plan (mapv plan-entry-lineage plan)))

(defn prepare-research-plan
  "Narrow orchestration preparation step: from a frozen research definition and an
   ordinary execution plan, build and verify P, annotate plan entries with their
   research coordinates, and commit a lineage-sensitive plan root.

   Returns {:plan annotated :projection P :plan-root R}.

   This is the single preparation step a runner would invoke before common
   execution; `run-benchmark` consumes the resulting annotated plan and never
   interprets D itself."
  [frozen plan]
  (let [ordered (vec (sort-by :execution/ordinal plan))
        projection (build-projection frozen ordered)
        _ (verify-projection frozen ordered projection)
        annotated (project-plan-entries frozen projection ordered)]
    {:plan annotated
     :projection projection
     :plan-root (plan-root annotated)}))

(defn verify-research-run-projection
  "End-to-end fail-closed verification that a prepared research run
   `{:plan annotated :projection P :plan-root R}` is the operational realization
   of `frozen`. Checks D, C, E, P, every execution's research coordinate, and that
   the committed plan root agrees with recomputation. Returns a summary when valid."
  [frozen {:keys [plan projection] :as prepared}]
  (let [ordered (vec (sort-by :execution/ordinal plan))
        committed-plan-root (:plan-root prepared)
        _ (verify-projection frozen ordered projection)
        recomputed-annotated (project-plan-entries frozen projection ordered)]
    (when-not (= recomputed-annotated ordered)
      (throw (ex-info "Plan entries disagree with the projection's research coordinates"
                      {:reason :plan-coordinate-mismatch})))
    (when-not (= committed-plan-root (plan-root ordered))
      (throw (ex-info "Committed research plan root does not match recomputation"
                      {:reason :plan-root-recompute-mismatch
                       :committed committed-plan-root
                       :recomputed (plan-root ordered)})))
    {:research-lineage/valid? true
     :research-definition/root (:research-definition/root frozen)
     :research-case-axis/root (research-case-axis-root frozen)
     :research-execution-projection/root (:projection/root projection)
     :execution-case-set/root (:execution-case-set/root projection)
     :plan-root committed-plan-root}))

(defn results->execution-plan
  "Reconstruct the ordinary execution plan (ordinal + id) from genuine execution
   results. Each result carries :execution/ordinal and :execution/id."
  [results]
  (mapv (fn [result]
          {:execution/ordinal (:execution/ordinal result)
           :execution/id (:execution/id result)})
        results))

(defn compile-research-matrix-from-results
  "Derive the C × M research result matrix from genuine execution results.

   `extract-observation` returns either the legacy integer used for every
   measure or a map keyed by declared measure ID. The latter lets applications
   select distinct observations without changing framework measure semantics."
  [frozen results extract-observation implementation-root]
  (let [plan (results->execution-plan results)
        ordered (vec (sort-by :execution/ordinal plan))
        projection (build-projection frozen ordered)
        _ (verify-projection frozen ordered projection)
        cases (get-in frozen [:research-definition :research/cases])
        measures (get-in frozen [:research-definition :research/measures])
        measure-ids (set (map :measure/id measures))
        observations (into {}
                           (map (fn [binding]
                                  (let [case-id (:case/id (nth cases (:research-case/key binding)))
                                        result (nth ordered (:research-case/key binding))
                                        extracted (extract-observation result)
                                        observed (if (integer? extracted)
                                                   (zipmap measure-ids (repeat extracted))
                                                   extracted)]
                                    (when-not (and (map? observed)
                                                   (= measure-ids (set (keys observed)))
                                                   (every? integer? (vals observed)))
                                      (throw (ex-info "Research observation extraction must yield exact integer measure coverage"
                                                      {:case/id case-id :observed extracted
                                                       :measure-ids measure-ids})))
                                    [case-id observed]))
                                (:case-bindings projection)))]
    {:matrix (research/compile-result-matrix frozen observations implementation-root)
     :projection projection
     :execution-case-set/root (:execution-case-set/root projection)}))