(ns resolver-sim.concepts.registry
  "Load and validate concept metadata from data/concepts/.

   Phase 1 scope:
   - Load registry index and individual concept definitions.
   - Validate concept IDs, required fields, and file references.
   - Provide lookup functions for report enrichment.

   No protocol execution changes, no scenario generation."
  (:require [clojure.set :as set]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.logging :as log]
            [resolver-sim.hash.reference :as hash-ref]))

(def standard-concept-types
  #{:use-case :decision-quality :assurance :allocation :yield :framework :security})

;; ── Registry loading ─────────────────────────────────────────────────────────

(def ^:private concept-registry-path (str hash-ref/resource-prefix hash-ref/concept-registry-path))

(def required-concept-keys
  #{:concept/id :concept/name :concept/summary
    :concept/stakeholder-question :concept/protocols
    :concept/roles :concept/entities :concept/actions
    :concept/outcomes :concept/failure-modes
    :concept/metrics :concept/assumptions
    :concept/out-of-scope})

(def mapping-statuses
  #{:native :derived :approximate :not-modelled})

(def use-case-required-keys
  #{:concept/maturity :concept/support-status :concept/known-gaps :concept/evidence})

(defn mapping-status
  "Return the declared mapping status, or derive a conservative status for
   legacy concept metadata. Empty mappings are not modelled; approximate
   mappings retain their existing confidence marker; all other direct mappings
   are native."
  [mapping]
  (or (:mapping/status mapping)
      (cond
        (empty? (:maps-to mapping)) :not-modelled
        (= :approximate (:mapping/confidence mapping)) :approximate
        :else :native)))

(defn normalize-concept
  "Add a machine-readable :mapping/status to every role, entity, action, and
   outcome mapping without changing the source concept's stakeholder wording."
  [concept]
  (reduce (fn [normalized category]
            (update normalized category
                    (fn [mappings]
                      (into {}
                            (map (fn [[id mapping]]
                                   [id (assoc mapping :mapping/status
                                              (mapping-status mapping))]))
                            mappings))))
          concept
          [:concept/roles :concept/entities :concept/actions :concept/outcomes]))

(defn capability-validation-errors
  "Validate concept mapping labels against an adapter-supplied capability set.

   Concept :protocol.* labels are stakeholder vocabulary, so this function does
   not invent a static Sew capability catalogue. Callers provide the exact set
   supported by a protocol/version/configuration and receive structured errors
   for labels that are not available in that declared capability surface."
  [concept capability-labels]
  (->> [:concept/roles :concept/entities :concept/actions :concept/outcomes]
       (mapcat (fn [category]
                 (for [[mapping-id mapping] (get concept category)
                       label (:maps-to mapping)
                       :when (not (contains? capability-labels label))]
                   {:concept/id (:concept/id concept)
                    :mapping/category category
                    :mapping/id mapping-id
                    :mapping/label label
                    :error :unsupported-capability-label})))
       vec))

(defn- load-edn
  [path]
  (rp/edn-read path))

(defn- resolve-concept-file
  "Resolve a concept file path. Tries filesystem first, then classpath.
   Returns a path spec usable by rp/edn-read."
  [concept-entry]
  (let [rel-path (:concept/file concept-entry)]
    (if (.exists (java.io.File. rel-path))
      rel-path
      (let [resource-path (str hash-ref/resource-prefix rel-path)]
        (if (rp/path-exists? resource-path)
          resource-path
          (throw (ex-info (str "Concept file not found: " rel-path)
                          {:concept-id (:concept/id concept-entry)
                           :path rel-path})))))))

(defn- missing-keys-violations
  "Schema-integrity violations for a concept definition: missing required
   members are errors (an invalid concept must not enter the usable registry),
   while mapping-reference shape/status remain warnings."
  [concept]
  (let [id (:concept/id concept)
        ctype (:concept/type concept)
        is-standard (contains? standard-concept-types ctype)]
    (cond-> []
      (and is-standard
           (seq (set/difference required-concept-keys (set (keys concept)))))
      (conj {:concept/id id
             :violation/id :violation/missing-required-concept-keys
             :missing (vec (sort (set/difference required-concept-keys (set (keys concept)))))})

      (and (= :use-case ctype)
           (seq (set/difference use-case-required-keys (set (keys concept)))))
      (conj {:concept/id id
             :violation/id :violation/missing-required-use-case-keys
             :missing (vec (sort (set/difference use-case-required-keys (set (keys concept)))))}))))

(defn- validate-concept
  "Validate a single concept definition. Returns
   {:concept <normalized-or-raw> :errors [<integrity-violations>]}
   plus :warnings for non-blocking quality checks. Required-member violations
   are errors; mapping-reference shape/status remain warnings."
  [concept]
  (let [id (:concept/id concept)
        ctype (:concept/type concept)
        is-standard (contains? standard-concept-types ctype)
        errors (missing-keys-violations concept)]
    ;; Mapping references and any explicitly declared status (warnings only).
    (when is-standard
      (doseq [category [:concept/roles :concept/entities :concept/actions :concept/outcomes]
              [mapping-key mapping] (get concept category)]
        (when-not (vector? (:maps-to mapping))
          (log/warn! "concept/invalid-maps-to"
                     {:concept-id id :category category :mapping mapping-key
                      :maps-to (:maps-to mapping)}))
        (when (and (:mapping/status mapping)
                   (not (contains? mapping-statuses (:mapping/status mapping))))
          (log/warn! "concept/invalid-mapping-status"
                     {:concept-id id :category category :mapping mapping-key
                      :mapping/status (:mapping/status mapping)}))))
    {:concept (if is-standard (normalize-concept concept) concept)
     :errors errors}))

(defn- duplicate-concept-violations
  "Duplicate :concept/id violations across the registry entries. Duplicate ids
   are schema-integrity failures: file load order must not become hidden
   semantic state. Sources are the :concept/file of the conflicting entries."
  [entries]
  (let [by-id (reduce (fn [acc {:keys [concept/id concept/file]}]
                        (if id (update acc id (fnil conj []) file) acc))
                      {}
                      entries)]
    (into []
          (keep (fn [[id sources]]
                  (when (< 1 (count sources))
                    {:concept/id id
                     :violation/id :violation/duplicate-concept-id
                     :sources (vec (sort sources))})))
          by-id)))

(defn registry-integrity-violations
  "Schema-integrity violations for a registry index and its loaded concepts:
   duplicate :concept/id across entries and missing required keys on each
   concept. Returns [<violation-maps>]. Pure and directly testable."
  [entries loaded-concepts]
  (into []
        (concat (mapcat missing-keys-violations loaded-concepts)
                (duplicate-concept-violations entries))))

;; ── Public API ───────────────────────────────────────────────────────────────

(defonce ^:private registry-cache
  (atom nil))

(defn clear-registry-cache!
  "Reset the concept registry cache. Intended for test isolation."
  []
  (reset! registry-cache nil)
  nil)

(defn load-registry
  "Load the concept registry and all referenced concept definitions.
   Fails closed on schema-integrity violations: missing required concept keys
   or duplicate concept ids produce an exception and the registry is NOT
   published. All violations are reported in one run.
   Returns {:registry <registry-map> :concepts <vec-of-concept-maps>}.
   Cached after first load — disk is read once per process."
  ([]
   (load-registry concept-registry-path))
  ([registry-path]
   (or @registry-cache
       (let [registry (load-edn registry-path)
             entries (:concepts registry)
             loaded (mapv (fn [entry]
                            (let [path (resolve-concept-file entry)
                                  concept (load-edn path)]
                              (validate-concept concept)))
                          entries)
             integrity (registry-integrity-violations entries (map :concept loaded))]
         (when (seq integrity)
           (throw (ex-info "concept registry validation failed"
                           {:error :concepts/validation-failed
                            :violations integrity})))
         (log/info! "concepts/loaded" {:count (count entries)
                                       :ids (mapv :concept/id (map :concept loaded))})
         (let [result {:registry registry
                       :concepts (mapv :concept loaded)}]
           (reset! registry-cache result)
           result)))))

(defn lookup-concept
  "Find a concept definition by qualified keyword."
  [concepts id]
  (first (filter #(= (:concept/id %) id) concepts)))

(defn concept-index
  "Build a concept-id -> concept map."
  [concepts]
  (into {} (map (fn [concept] [(:concept/id concept) concept]) concepts)))

(defn concept-ids
  "Return all registered concept IDs."
  [concepts]
  (mapv :concept/id concepts))

(defn concepts-for-protocol
  "Return concept definitions that support a given protocol."
  [concepts protocol-id]
  (filter #(contains? (:concept/protocols %) protocol-id) concepts))

(defn missing-related-concepts
  "Return unresolved :concept/related references as
   {:from <concept-id> :to <related-concept-id>} maps."
  [concepts]
  (let [known-ids (set (concept-ids concepts))]
    (mapcat (fn [concept]
              (keep (fn [related-id]
                      (when-not (contains? known-ids related-id)
                        {:from (:concept/id concept)
                         :to related-id}))
                    (:concept/related concept)))
            concepts)))
