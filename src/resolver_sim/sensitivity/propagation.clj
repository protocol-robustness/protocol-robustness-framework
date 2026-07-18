(ns resolver-sim.sensitivity.propagation
  "Sensitivity metadata propagation helpers.
   Validation, extraction, attachment, merging, and provenance for
   the scenario -> evidence -> attestation -> bundle sensitivity pipeline.

   Usage:
     (require '[resolver-sim.sensitivity.propagation :as prop])

     ;; Validate a :scenario/sensitivity declaration
     (prop/validate-sensitivity sensitivity-map)
     ;; => nil or [{:path [:level] :reason \"unknown level\"} ...]

     ;; Compute effective sensitivity for an artifact
     (prop/effective-sensitivity artifact scenario-sensitivity)
     ;; => {:level :sensitivity/private
     ;;     :risk-meta {:value-at-risk \"15,000,000\"}
     ;;     :structural-level :sensitivity/internal
     ;;     :declared-level :sensitivity/private
     ;;     :reasons [...]
     ;;     :source :declared}

     ;; Attach sensitivity to an artifact
     (prop/attach-sensitivity evidence-node effective-sens)
     ;; => artifact with sensitivity metadata attached

     ;; Merge collection
     (prop/merge-sensitivity [{:level :sensitivity/internal}
                               {:level :sensitivity/private}])
     ;; => {:level :sensitivity/private}"

  (:require [resolver-sim.sensitivity.sentinel :as sentinel]))

;; ── Constants ────────────────────────────────────────────────────────────────

(def provenance-schema-version "sensitivity-provenance.v1")

;; ── Validation ──────────────────────────────────────────────────────────────

(def ^:private valid-levels
  #{:sensitivity/public :sensitivity/internal :sensitivity/private
    :sensitivity/embargoed :sensitivity/critical-private})

(def ^:private valid-risk-severities
  #{:risk-severity/low :risk-severity/medium :risk-severity/high
    :risk-severity/critical})

(defn validate-sensitivity
  "Validate a :scenario/sensitivity declaration map.
   Returns nil if valid, or a vector of {:path <keyword-vec> :reason <str>}
   error maps for each issue found.

   Checks:
   - :level must be a valid sensitivity level keyword
   - :risk-meta, when present, must be a map
   - :risk-meta :risk-severity must be a valid severity keyword
   - :risk-meta :reason-codes, when present, must be sequential of keywords
   - Unknown keys in the top-level block are silently preserved per
     the repository's metadata-extension policy"
  [sensitivity]
  (when sensitivity
    (let [errors (atom [])
          level (:level sensitivity)
          risk-meta (:risk-meta sensitivity)
          reason-codes (get-in sensitivity [:risk-meta :reason-codes])]
      (when-not (contains? valid-levels level)
        (swap! errors conj {:path [:level]
                            :reason (str "Invalid sensitivity level: " level
                                         ". Must be one of: " valid-levels)}))
      (when (and (some? risk-meta) (not (map? risk-meta)))
        (swap! errors conj {:path [:risk-meta]
                            :reason (str "risk-meta must be a map, got: "
                                         (type risk-meta))}))
      (when (and (map? risk-meta) (contains? risk-meta :risk-severity)
                 (not (contains? valid-risk-severities (:risk-severity risk-meta))))
        (swap! errors conj {:path [:risk-meta :risk-severity]
                            :reason (str "Invalid risk severity: " (:risk-severity risk-meta)
                                         ". Must be one of: " valid-risk-severities)}))
      (when (and (some? reason-codes) (not (sequential? reason-codes)))
        (swap! errors conj {:path [:risk-meta :reason-codes]
                            :reason "reason-codes must be a sequential collection"}))
      (when (and (sequential? reason-codes)
                 (some (complement keyword?) reason-codes))
        (swap! errors conj {:path [:risk-meta :reason-codes]
                            :reason "All reason-codes must be keywords"}))
      (when (seq @errors) @errors))))

;; ── Extraction ──────────────────────────────────────────────────────────────

(defn scenario-sensitivity
  "Extract sensitivity metadata from a scenario map.
   Looks for :scenario/sensitivity or :sensitivity/level in the scenario map.
   Returns nil if no sensitivity metadata is present.

   Arguments:
     scenario — a scenario map

   Returns nil or {:level <kw> :risk-meta <map>}"
  [scenario]
  (when scenario
    (let [block (:scenario/sensitivity scenario)
          level (or (:level block)
                    (:sensitivity/level scenario))
          risk-meta (or (:risk-meta block)
                        (:sensitivity/risk-meta scenario))]
      (when level
        (cond-> {:level level}
          risk-meta (assoc :risk-meta (select-keys risk-meta
                                                    [:value-at-risk :risk-severity
                                                     :risk-vector :reason-codes])))))))

(defn effective-scenario-sensitivity
  "Compute the effective sensitivity for a scenario result, using the
   declared sensitivity when available or falling back to structural
   classification alone.

   A scenario without a declaration still has a valid structural
   classification and must not be silently dropped from aggregation.

   Arguments:
     result — a scenario result map (from enrich-summary-results)

   Returns a map {:level <kw> :risk-meta <map|nil>}
   with :structural-only true when no declaration was present."
  [result]
  (if-let [sens (get-in result [:scenario-metadata :scenario/sensitivity])]
    (scenario-sensitivity (assoc {} :scenario/sensitivity sens))
    (let [structural (sentinel/classify-structural result)]
      {:level structural :structural-only true})))

(defn artifact-sensitivity
  "Extract sensitivity metadata already attached to an artifact.
   Checks :sensitivity/level, :sensitivity/risk-meta directly, and
   also looks in :extensions for evidence nodes and :attestation/metadata
   for attestations.

   Arguments:
     artifact — an evidence node, attestation, claim result, or bundle

   Returns nil or {:level <kw> :risk-meta <map>}"
  [artifact]
  (when artifact
    (let [;; Direct keys
          direct-level (or (:sensitivity/level artifact)
                           (get-in artifact [:extensions :sensitivity/level])
                           (get-in artifact [:attestation/metadata :sensitivity/level])
                           (get-in artifact [:policy-output :sensitivity :level]))
          direct-risk (or (:sensitivity/risk-meta artifact)
                          (get-in artifact [:extensions :sensitivity/risk-meta])
                          (get-in artifact [:attestation/metadata :sensitivity/risk-meta])
                          (get-in artifact [:policy-output :sensitivity :risk-meta]))]
      (when direct-level
        (cond-> {:level direct-level}
          direct-risk (assoc :risk-meta direct-risk))))))

;; ── Effective sensitivity ───────────────────────────────────────────────────

(defn effective-sensitivity
  "Compute the effective sensitivity for an artifact given optional
   scenario sensitivity metadata.

   Returns a canonical provenance map:
     {:sentinel/structural-level <kw>
      :sentinel/declared-level <kw | nil>
      :sentinel/effective-level <kw>
      :sentinel/reasons [<kw> ...]
      :sentinel/risk-meta <map | nil>
      :sentinel/sources [<str> ...]}

   The effective level is the maximum of:
   - structural classification of the artifact
   - declared sensitivity from scenario metadata"
  [artifact scenario-sensitivity]
  (let [structural (sentinel/classify-structural artifact)
        declared-level (:level scenario-sensitivity)
        risk-meta (:risk-meta scenario-sensitivity)
        structural-level structural
        effective (if (and declared-level
                           (contains? sentinel/level-set declared-level)
                           (sentinel/level>= declared-level structural))
                    declared-level
                    structural)
        base-reasons (sentinel/default-reasons effective)
        declared-reasons (vec (get-in scenario-sensitivity [:risk-meta :reason-codes] []))
        all-reasons (vec (distinct (concat base-reasons declared-reasons)))
        sources (cond-> []
                  (some? scenario-sensitivity) (conj (str "scenario:" (:level scenario-sensitivity)))
                  (not= structural effective) (conj (str "declared-floor:" (name declared-level))))]
    {:sentinel/structural-level structural-level
     :sentinel/declared-level declared-level
     :sentinel/effective-level effective
     :sentinel/reasons all-reasons
     :sentinel/risk-meta risk-meta
     :sentinel/sources (vec sources)}))

;; ── Attachment ──────────────────────────────────────────────────────────────

(defn attach-sensitivity
  "Attach effective sensitivity metadata to an artifact.
   Uses the appropriate slot per artifact type:
   - Evidence DAG nodes: :policy-output :sensitivity
   - Attestations: :attestation/metadata
   - Everything else: direct :sensitivity keys

   Arguments:
     artifact    — artifact map
     sensitivity — provenance map from effective-sensitivity or nil

   Returns the artifact with sensitivity attached (or unchanged if
   sensitivity is nil)."
  [artifact sensitivity]
  (if sensitivity
    (let [;; Determine artifact type by shape
          is-evidence-node? (contains? artifact :policy-output)
          is-attestation? (or (:attestation/id artifact)
                              (:attestation/hash artifact))
          effective (:sentinel/effective-level sensitivity)
          risk-meta (:sentinel/risk-meta sensitivity)]
      (cond
        is-evidence-node?
        (assoc-in artifact [:policy-output :sensitivity]
                  {:level effective
                   :risk-meta risk-meta
                   :provenance sensitivity})

        is-attestation?
        (assoc-in artifact [:attestation/metadata :sensitivity] sensitivity)

        :else
        (cond-> artifact
          effective (assoc :sensitivity/level effective)
          risk-meta (assoc :sensitivity/risk-meta risk-meta)
          sensitivity (assoc :sensitivity/provenance sensitivity))))
    artifact))

;; ── Merging ─────────────────────────────────────────────────────────────────

(defn merge-sensitivity
  "Given a collection of sensitivity metadata maps (provenance maps
   with :sentinel/effective-level), return the highest (most restrictive)
   effective level with combined metadata.

   Arguments:
     sensitivities — seq of maps (provenance maps, nil, or maps with :level)

   Returns {:level <kw> :risk-meta <map>} with the highest level
   and the most severe risk metadata, or nil if empty."
  [sensitivities]
  (let [present (filter some? sensitivities)
        ;; Normalize: accept both provenance maps and simple maps
        normalized (map (fn [s]
                          (if (:sentinel/effective-level s)
                            {:level (:sentinel/effective-level s)
                             :risk-meta (:sentinel/risk-meta s)
                             :provenance s}
                            s))
                        present)]
    (when (seq normalized)
      (let [levels (keep :level normalized)
            highest-level (when (seq levels)
                            (apply max-key sentinel/level-index levels))
          ;; Pick the risk-meta with highest severity, or the first one
          risk-meta (first (sort-by (fn [rm]
                                     (get sentinel/risk-severity-order
                                          (:risk-severity rm) 0))
                                    >
                                    (keep :risk-meta normalized)))]
        (cond-> {:level highest-level}
          risk-meta (assoc :risk-meta risk-meta))))))

;; ── Derivation provenance ───────────────────────────────────────────────────

(defn build-sensitivity-derivation
  "Build a canonical sensitivity derivation record for an artifact
   from the effective sensitivity and originating source contexts.

   This is a pure derivation helper — it computes a provenance record
   from facts (effective sensitivity map) and source context (strings
   or structured maps). It does NOT bind those facts to a specific
   report identity or policy context.

   The report.clj build-canonical-report-provenance function is the
   single canonical authority for assembling and persisting the
   run-level sensitivity provenance object. Other callers must not
   persist the output of build-sensitivity-derivation as an equivalent
   authority.

   Arguments:
     effective — provenance map from effective-sensitivity
     sources   — additional source context: strings (appended to
                 :sentinel/sources display list) or structured maps
                 (added to :sentinel/structured-sources, with a
                 human-readable summary appended to :sentinel/sources)

   Returns a derivation map suitable for inclusion in bundle manifests,
   finalization records, and verification reports."
  [effective & sources]
  (let [provenance-map (select-keys effective
                                    [:sentinel/structural-level
                                     :sentinel/declared-level
                                     :sentinel/effective-level
                                     :sentinel/reasons
                                     :sentinel/risk-meta
                                     :sentinel/sources
                                     :sentinel/structured-sources])
        ;; Separate structured maps from plain strings
        [structured extra-strings]
        ((fn [xs]
           (reduce (fn [[struct strs] s]
                     (if (map? s)
                       [(conj struct s) strs]
                       [struct (conj strs (cond
                                            (string? s) s
                                            (keyword? s) (name s)
                                            :else (str s)))]))
                   [[] []]
                   xs))
         (filterv some? sources))
        existing-structured (vec (concat (:sentinel/structured-sources provenance-map [])
                                         structured))
        existing-strings (vec (concat (:sentinel/sources provenance-map [])
                                      extra-strings))]
    (cond-> provenance-map
      (seq extra-strings)
      (assoc :sentinel/sources existing-strings)
      (seq structured)
      (assoc :sentinel/structured-sources existing-structured))))

(defn- build-provenance
  "Deprecated — use build-sensitivity-derivation instead."
  [effective & sources]
  (apply build-sensitivity-derivation effective sources))

;; ── Downgrade Prevention ────────────────────────────────────────────────────

(defn assert-no-downgrade!
  "Check that attaching a new sensitivity level does not downgrade an
   existing higher level on the artifact.

   Throws ex-info with :sensitivity/downgrade-attempted when the new
   level is lower than an already-attached level.

   Arguments:
     artifact    — the target artifact (may already carry sensitivity)
     new-level   — the level being attached (keyword or nil)
     context     — string describing the operation (e.g. \"attach-sensitivity\")"
  [artifact new-level context]
  (when new-level
    (let [existing-level (or (:sensitivity/level artifact)
                             (get-in artifact [:extensions :sensitivity/level])
                             (get-in artifact [:attestation/metadata :sensitivity/level])
                             (get-in artifact [:policy-output :sensitivity :level]))]
      (when (and existing-level
                 (contains? sentinel/level-set existing-level)
                 (contains? sentinel/level-set new-level)
                 (sentinel/level> existing-level new-level))
        (throw (ex-info (str "Sensitivity downgrade prevented: "
                             existing-level " -> " new-level
                             " during " context)
                        {:sensitivity/downgrade-attempted true
                         :sensitivity/existing-level existing-level
                         :sensitivity/new-level new-level
                         :sensitivity/context context}))))))

(defn assert-merge-no-downgrade!
  "Check that merging a collection of sensitivities produces a level at
   least as high as any individual input.

   Throws if the merged result is lower than any input level.

   Arguments:
     merged — result from merge-sensitivity (or nil)
     inputs — seq of maps with :level keys"
  [merged inputs]
  (let [merged-level (:level merged)
        highest-input (when (seq inputs)
                        (apply max-key sentinel/level-index (keep :level inputs)))]
    (when (and merged-level highest-input
               (sentinel/level> highest-input merged-level))
      (throw (ex-info (str "Merge downgrade detected: highest input "
                           highest-input " -> merged " merged-level)
                      {:sensitivity/merge-downgrade true
                       :sensitivity/highest-input highest-input
                       :sensitivity/merged-level merged-level})))))

(defn assert-no-serialization-loss!
  "Check that sensitivity metadata survives a serialize/readback cycle.
   Compares the original and readback sensitivity maps for essential fields.

   Arguments:
     original — original sensitivity metadata map
     readback — sensitivity metadata map after readback
     context  — string describing the serialization context

   Throws if essential fields differ."
  [original readback context]
  (let [essential-keys [:sentinel/structural-level :sentinel/declared-level
                        :sentinel/effective-level :sentinel/reasons]
        original-essentials (select-keys original essential-keys)
        readback-essentials (select-keys readback essential-keys)]
    (when (and original readback (not= original-essentials readback-essentials))
      (throw (ex-info (str "Sensitivity metadata changed during serialization: "
                           context)
                      {:sensitivity/serialization-loss true
                       :sensitivity/context context
                       :sensitivity/original original-essentials
                       :sensitivity/readback readback-essentials})))))
