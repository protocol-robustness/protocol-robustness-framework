(ns resolver-sim.claims.engine
  "Claim evaluation and validation over persisted execution evidence nodes.

   The engine is intentionally narrow:
   - claims are evaluated from evidence nodes selected by :evidence-references
   - claim semantics come from claim definitions in the passive registry (or an injected registry)
   - dependency edges come from claim-definition :depends-on

   This keeps claim computation reproducible from the same evidence-node set and
   claim-definition registry state."
  (:require [resolver-sim.definitions.passive-registries :as registries]))

(defn claim-definitions
  ([] (claim-definitions registries/claim-definition-registry))
  ([registry]
   (:claim-definitions registry)))

(defn claim-definition-map
  ([] (claim-definition-map registries/claim-definition-registry))
  ([registry]
   (into {} (map (juxt :id identity) (claim-definitions registry)))))

(defn claim-definition
  ([claim-id]
   (claim-definition claim-id registries/claim-definition-registry))
  ([claim-id registry]
   (get (claim-definition-map registry) claim-id)))

(defn evidence-node-map
  [evidence-nodes]
  (into {} (map (juxt :node-hash identity) evidence-nodes)))

(defn evaluate-pass-status-claim
  "Reference evaluator for claims that hold only when all referenced evidence
   nodes completed with :pass and all dependency claims also hold."
  [{:keys [evidence-nodes dependency-results]}]
  (let [statuses (mapv #(get-in % [:result :status]) evidence-nodes)
        missing-statuses (vec (keep-indexed (fn [idx status]
                                              (when-not (keyword? status)
                                                {:index idx :status status}))
                                            statuses))
        dependency-failures (->> dependency-results
                                 (keep (fn [[claim-id result]]
                                         (when-not (:holds? result)
                                           {:claim-id claim-id
                                            :status (:status result)})))
                                 vec)
        holds? (and (empty? missing-statuses)
                    (empty? dependency-failures)
                    (every? #{:pass} statuses))]
    {:holds? holds?
     :observed-statuses statuses
     :dependency-failures dependency-failures
     :missing-statuses missing-statuses}))

(defn evaluate-presence-claim
  "Reference evaluator for claims that hold when at least one evidence node is
   present and all dependencies hold."
  [{:keys [evidence-nodes dependency-results]}]
  (let [dependency-failures (->> dependency-results
                                 (keep (fn [[claim-id result]]
                                         (when-not (:holds? result)
                                           {:claim-id claim-id
                                            :status (:status result)})))
                                 vec)
        holds? (and (seq evidence-nodes)
                    (empty? dependency-failures))]
    {:holds? (boolean holds?)
     :evidence-count (count evidence-nodes)
     :dependency-failures dependency-failures}))

(defn- dep-ids
  "Extract claim IDs from :depends-on, handling both legacy keyword vectors
   and enriched map vectors ({:claim-id <kw> :concept-hash <hex> ...})."
  [deps]
  (when deps
    (if (and (sequential? deps) (every? map? deps))
      (mapv :claim-id deps)
      (vec deps))))

(defn- cycle-path
  [graph node]
  (letfn [(visit [n stack seen]
            (cond
              (some #{n} stack)
              (conj (vec (drop-while #(not= n %) stack)) n)

              (seen n)
              nil

              :else
              (some #(visit % (conj stack n) (conj seen n)) (get graph n []))))]
    (visit node [] #{})))

(defn- normalize-request
  [{:keys [claim-id evidence-references] :as request}]
  (assoc request
         :claim-id claim-id
         :evidence-references (vec (or evidence-references []))))

(defn- resolve-evaluator
  [claim-definition evaluator-resolver]
  (or (when evaluator-resolver
        (evaluator-resolver claim-definition))
      (when-let [entry (get-in claim-definition [:evaluation :entry])]
        (when (symbol? entry)
          (requiring-resolve entry)))))

(defn validate-claim-results
  "Validate evaluated claim results against the claim-definition registry and
   evidence-node set.

   Checks:
   - claim-definition exists
   - recorded :claim-definition-hash matches the registered definition hash
   - all :evidence-references exist
   - claims are not orphaned (no missing/empty evidence refs)
   - claim dependencies are acyclic
   - dependency references point to present claim results"
  ([claim-results evidence-nodes]
   (validate-claim-results claim-results evidence-nodes {}))
  ([claim-results evidence-nodes {:keys [registry]
                                  :or {registry registries/claim-definition-registry}}]
   (let [definition-map (claim-definition-map registry)
         available-evidence (set (keys (evidence-node-map evidence-nodes)))
         result-map (into {} (map (juxt :claim-id identity) claim-results))
         graph (into {}
                     (map (fn [{:keys [claim-id depends-on]}]
                            [claim-id (vec (or depends-on []))])
                          claim-results))
         errors (vec
                 (concat
                  (mapcat (fn [{:keys [claim-id claim-definition-hash evidence-references depends-on]}]
                            (let [definition (get definition-map claim-id)
                                  expected-hash (:canonical-hash definition)
                                  evidence-references (vec (or evidence-references []))
                                  missing-evidence (vec (remove available-evidence evidence-references))
                                  empty-evidence? (empty? evidence-references)
                                  dependency-source (dep-ids (or depends-on (:depends-on definition)))
                                  missing-dependencies (vec (remove #(contains? result-map %) dependency-source))]
                              (cond-> []
                                (nil? definition)
                                (conj {:error :claim/unknown-definition
                                       :claim-id claim-id})

                                (and definition (not= claim-definition-hash expected-hash))
                                (conj {:error :claim/definition-hash-mismatch
                                       :claim-id claim-id
                                       :recorded claim-definition-hash
                                       :expected expected-hash})

                                empty-evidence?
                                (conj {:error :claim/orphan
                                       :claim-id claim-id
                                       :reason :empty-evidence-references})

                                (seq missing-evidence)
                                (conj {:error :claim/missing-evidence
                                       :claim-id claim-id
                                       :missing-evidence missing-evidence})

                                (seq missing-dependencies)
                                (conj {:error :claim/missing-dependencies
                                       :claim-id claim-id
                                       :missing-dependencies missing-dependencies}))))
                          claim-results)
                  (keep (fn [claim-id]
                          (when-let [cycle (cycle-path graph claim-id)]
                            {:error :claim/dependency-cycle
                             :cycle cycle}))
                        (keys graph))))]
     {:valid? (empty? errors)
      :errors errors
      :checks {:claim-count (count claim-results)
               :evidence-node-count (count available-evidence)
               :cycles-checked (count graph)}})))

(defn evaluate-claims
  "Evaluate claim requests from evidence nodes and the claim-definition registry.

   Request shape:
   {:claim-id kw
    :evidence-references [<node-hash> ...]}

   Returns:
   {:claim-results [...]
    :validation {...}}

   Evaluation dispatch order is deterministic: dependencies are evaluated before
   dependents, and request order is preserved for top-level claims."
  ([requests evidence-nodes]
   (evaluate-claims requests evidence-nodes {}))
  ([requests evidence-nodes {:keys [registry evaluator-resolver]
                             :or {registry registries/claim-definition-registry}}]
   (let [requests (mapv normalize-request requests)
         request-map (into {} (map (juxt :claim-id identity) requests))
         definition-map (claim-definition-map registry)
         nodes-by-hash (evidence-node-map evidence-nodes)]
     (letfn [(evaluate* [claim-id seen cache]
               (if-let [cached (get cache claim-id)]
                 [cached cache]
                 (let [request (or (get request-map claim-id)
                                   {:claim-id claim-id :evidence-references []})
                       definition (or (get definition-map claim-id)
                                      (throw (ex-info "Unknown claim definition"
                                                      {:claim-id claim-id})))
                       deps (dep-ids (:depends-on definition))]
                   (when (some #{claim-id} seen)
                     (throw (ex-info "Circular claim dependency during evaluation"
                                     {:claim-id claim-id
                                      :cycle (conj (vec seen) claim-id)})))
                   (let [[dependency-results cache]
                         (reduce (fn [[results cache] dep-id]
                                   (let [[result cache] (evaluate* dep-id (conj seen claim-id) cache)]
                                     [(assoc results dep-id result) cache]))
                                 [{} cache]
                                 deps)
                         evaluator (resolve-evaluator definition evaluator-resolver)]
                     (when-not evaluator
                       (throw (ex-info "No evaluator available for claim"
                                       {:claim-id claim-id
                                        :evaluation (:evaluation definition)})))
                     (let [selected-hashes (vec (:evidence-references request))
                           selected-nodes (mapv nodes-by-hash selected-hashes)
                           raw-result (evaluator {:claim-definition definition
                                                  :evidence-nodes selected-nodes
                                                  :evidence-node-map nodes-by-hash
                                                  :evidence-references selected-hashes
                                                  :dependency-results dependency-results})
                           holds? (boolean (:holds? raw-result))
                           status (or (:status raw-result)
                                      (if holds? :pass :fail))
                           result (assoc (dissoc raw-result :holds? :status)
                                         :claim-id claim-id
                                         :claim-definition-hash (:canonical-hash definition)
                                         :evidence-references selected-hashes
                                         :depends-on deps
                                         :holds? holds?
                                         :status status)]
                       [result (assoc cache claim-id result)])))))
             (evaluate-all* [claim-ids]
               (reduce (fn [[order cache] claim-id]
                         (let [[result cache] (evaluate* claim-id [] cache)
                               order (if (some #{(:claim-id result)} order)
                                       order
                                       (conj order (:claim-id result)))
                               dependency-order (reduce (fn [acc dep-id]
                                                          (if (some #{dep-id} acc)
                                                            acc
                                                            (conj acc dep-id)))
                                                        order
                                                        (:depends-on result))]
                           [dependency-order cache]))
                       [[] {}]
                       claim-ids))]
       (let [claim-ids (mapv :claim-id requests)
             [claim-order cache] (evaluate-all* claim-ids)
             claim-results (mapv cache claim-order)
             validation (validate-claim-results claim-results evidence-nodes
                                                {:registry registry})]
         {:claim-results claim-results
          :validation validation})))))
