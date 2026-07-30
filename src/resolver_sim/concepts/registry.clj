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

(defn- validate-concept
  "Validate a single concept definition."
  [concept]
  (let [id (:concept/id concept)
        ctype (:concept/type concept)
        is-standard (contains? standard-concept-types ctype)]
    (when is-standard
      (let [missing (set/difference required-concept-keys
                                    (set (keys concept)))]
        (when (seq missing)
          (log/warn! "concept/missing-keys" {:concept-id id :missing missing}))))
    (when (= :use-case ctype)
      (let [missing-use-case (set/difference use-case-required-keys
                                             (set (keys concept)))]
        (when (seq missing-use-case)
          (log/warn! "concept/missing-use-case-contract"
                     {:concept-id id :missing missing-use-case}))))
    ;; Validate mapping references and any explicitly declared status.
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
    (if is-standard
      (normalize-concept concept)
      concept)))

;; ── Public API ───────────────────────────────────────────────────────────────

(def load-registry
  "Load the concept registry and all referenced concept definitions.
   Returns {:registry <registry-map> :concepts <vec-of-concept-maps>}.
   Cached after first load — disk is read once per process."
  (let [cache (atom nil)]
    (fn load-registry*
      ([] (load-registry* concept-registry-path))
      ([registry-path]
       (if-let [cached @cache]
         cached
         (let [result (let [registry (load-edn registry-path)
                            concepts (mapv (fn [entry]
                                             (let [path (resolve-concept-file entry)
                                                   concept (load-edn path)]
                                               (validate-concept concept)))
                                           (:concepts registry))]
                        (log/info! "concepts/loaded" {:count (count concepts)
                                                      :ids (mapv :concept/id concepts)})
                        {:registry registry
                         :concepts concepts})]
           (reset! cache result)
           result))))))

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
