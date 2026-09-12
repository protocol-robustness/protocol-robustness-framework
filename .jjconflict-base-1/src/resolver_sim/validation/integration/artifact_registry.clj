(ns resolver-sim.validation.integration.artifact-registry
  "Integration: artifact registry → validation-root.v1.

   Consumes a raw artifact registry map (from test-artifacts.json) and
   produces a finalized validation-root.v1 via the adapter.

   This namespace performs lightweight registry checks and feeds them into
   resolver-sim.validation.adapters.artifact-registry/registry-result->validation-root.

   It does NOT replace the existing Python-based validate_artifact_registry.py;
   it provides a validation-root.v1 result alongside it.

   ── entry point ──

     (validate-artifact-registry registry-map opts)

     registry-map must contain:
       :artifacts     — vector of artifact entries
       :run-id        — string run identifier

     opts may contain:
       :extra-checks     — additional check maps to append
       :extra-errors     — additional error maps
       :extra-warnings   — additional warning maps
       :extra-metadata   — additional metadata merged into :extra
       :run-id           — overrides the registry's :run-id

   ── checks performed ──

     - :registry/artifacts-present          — at least one artifact in the registry
     - :registry/dangling-dep               — each artifact's verifies_against entries
                                               are satisfied by another artifact's
                                               schema_version
     - :registry/ambiguous-schema-version   — warns when a verifies_against schema
                                               version is satisfied by more than one
                                               artifact (ambiguous resolution)

   ── check/id vs error-key/warning-key ──

     :check/id    — procedural identity for the check; used to locate the
                    check in the :checks vector.  Changes when the check
                    logic or structure changes.

     :error-key   — stable issue taxonomy key; used for set membership in
     :warning-key  :error-keys / :warning-keys.  These are the canonical
                    identifiers for downstream consumers (dashboards, CI,
                    alerting) and should NOT change when check internals
                    change.

     Example:
       {:check/id   :registry/dangling-dep    ;; procedural identity
        :status     :failed
        :error-key  :registry/dangling-dependency  ;; stable taxonomy key
        :message    \"Unresolved dependency: projection.v1\"}

     Downstream code should match on :error-key, never on :check/id.

   ── dependency resolution mode ──

     When artifacts use the canonical :dependencies field (v1.2+), the
     resolver checks each dependency :id against the set of artifact :id
     values.  When only :verifies_against is present (legacy), the resolver
     matches schema version strings against artifact :schema_version values.
     The active mode is recorded in the validation root's :extra key:

       :dependency-resolution :dependencies-field    (canonical, v1.2+)
       :dependency-resolution :verifies-against-field (legacy fallback)

   ── known external schema dependencies ──

     The following schema versions may appear in a registry artifact's
     verifies_against list but are NOT expected to be provided as an
     artifact entry's schema_version.  They are external/environment
     schemas, not artifact schemas, and are excluded from dangling
     dependency detection:

       evidence-contract.v1
       scenario.v1
       projection.v1

     This set is hardcoded in known-non-artifact-schemas.  If new
     external schemas are added to the evidence chain without adding
     corresponding artifact entries, this set must be updated.

   ── total-checks semantics ──

     The :metrics :checks counter counts the number of validation checks
     the integration layer emitted, NOT the number of artifacts examined.
     For a clean registry with no dangling or ambiguous dependencies:

       total-checks=1  (artifacts-present aggregate)

     Each additional warning or failure adds one check.  This is the
     \"collapsed aggregate\" approach — one check covers all artifacts.
     Downstream dashboards and CI should compare artifact-count (in
     :extra) against checks/total-checks rather than expecting one
     check per artifact.

   ── status precedence ──

     Uses resolver-sim.validation.root/status-precedence:
       :failed  — any error-key present
       :warning — any warning-key present, no errors
       :passed  — no error or warning keys"

  (:require
   [clojure.data.json :as json]
   [resolver-sim.evidence.config :as evcfg]
   [resolver-sim.validation.adapters.artifact-registry :as adapter]))

;; ── registry checks ──────────────────────────────────────────────────────────

(defn- known-non-artifact-schemas
  "Schema versions that are not provided by any artifact entry but are
   referenced as verifies_against targets.
   Read from config/evidence.json :exempt_schemas key."
  []
  (set (get (evcfg/get-config) :exempt_schemas #{})))

(defn- parse-verifies-against
  "Extract the set of schema versions an artifact depends on (legacy field)."
  [artifact]
  (set (:verifies_against artifact)))

(defn- parse-dependencies
  "Extract the set of artifact :id strings an artifact depends on (canonical v1.2 field).
   Returns an empty set when :dependencies is nil or empty."
  [artifact]
  (set (map :id (:dependencies artifact))))

(defn- provided-schemas
  "Collect the set of schema_version strings from all artifacts."
  [artifacts]
  (set (keep :schema_version artifacts)))

(defn- provided-artifact-ids
  "Collect the set of artifact :id strings from all artifacts."
  [artifacts]
  (set (keep :id artifacts)))

(defn- schema-version->artifacts
  "Build a map from schema_version to the count of artifacts providing it."
  [artifacts]
  (reduce (fn [acc a]
            (if-let [sv (:schema_version a)]
              (update acc sv (fnil inc 0))
              acc))
          {} artifacts))

(defn- dangling-dependencies
  "Return a vector of check maps for verifies_against schema dependencies
   that are not provided by any artifact in the registry.
   This is the legacy resolution mode (schema-version match)."
  [artifacts]
  (let [provided (provided-schemas artifacts)
        all-deps (mapcat parse-verifies-against artifacts)
        dangling (remove #(or (contains? provided %)
                              (contains? (known-non-artifact-schemas) %))
                         all-deps)]
    (mapv (fn [dep]
            {:check/id  :registry/dangling-dep
             :status    :failed
             :error-key :registry/dangling-dependency
             :severity  :critical
             :message   (str "Dependency " dep " is not provided by any artifact")})
          dangling)))

(defn- dangling-dependency-refs
  "Return a vector of check maps for dependency :id refs (canonical v1.2
   :dependencies field) that are not provided by any artifact in the registry."
  [artifacts]
  (let [provided (provided-artifact-ids artifacts)
        all-deps (mapcat parse-dependencies artifacts)
        dangling (remove #(contains? provided %) all-deps)]
    (mapv (fn [dep]
            {:check/id  :registry/dangling-dep-ref
             :status    :failed
             :error-key :registry/dangling-dependency-ref
             :severity  :critical
             :message   (str "Dependency ref '" dep "' (by :id) is not provided by any artifact")})
          dangling)))

(defn- ambiguous-schema-versions
  "Return a vector of warning check maps for verifies_against targets that
   are satisfied by more than one artifact (ambiguous resolution)."
  [artifacts]
  (let [sv->count (schema-version->artifacts artifacts)
        all-deps  (mapcat parse-verifies-against artifacts)
        ambiguous (set (filter #(> (get sv->count % 0) 1) all-deps))]
    (mapv (fn [dep]
            {:check/id    :registry/ambiguous-schema-version
             :status      :warning
             :warning-key :registry/ambiguous-schema-version-dependency
             :message     (str "Dependency " dep " is satisfied by "
                               (get sv->count dep) " artifacts; resolution is ambiguous")})
          ambiguous)))

;; ── public API ───────────────────────────────────────────────────────────────

(defn- dependency-resolution-mode
  "Determine the dependency resolution mode.
   Returns :dependencies-field when any artifact uses the canonical v1.2
   :dependencies field, otherwise :verifies-against-field (legacy fallback)."
  [artifacts]
  (if (some (comp seq :dependencies) artifacts)
    :dependencies-field
    :verifies-against-field))

(defn validate-artifact-registry
  "Run registry diagnostics and return a finalized validation-root.v1 map.

   registry-map:
     :artifacts     — vector of artifact entries (each with :schema_version,
                      :verifies_against, :dependencies, :id, :path, :sha256)
     :run-id        — string

   opts (optional map):
     :extra-checks     — additional check maps appended after built-in checks
     :extra-errors     — additional error maps for issues beyond individual checks
     :extra-warnings   — additional warning maps for issues beyond individual checks
     :extra-metadata   — extra metadata merged into :extra (e.g. :scenario-id)
     :run-id           — overrides the registry's :run-id"
  [registry-map & [opts]]
  (let [artifacts   (:artifacts registry-map [])
        run-id      (or (:run-id opts) (:run-id registry-map "unknown"))
        dep-mode    (dependency-resolution-mode artifacts)
        meta        {:run-id                run-id
                     :artifact-count        (count artifacts)
                     :dependency-resolution dep-mode}
        metadata    (merge meta (:extra-metadata opts))]
    (adapter/registry-result->validation-root
     {:checks   (into [{:check/id :registry/artifacts-present
                        :status   (if (seq artifacts) :passed :failed)
                        :error-key :registry/no-artifacts
                        :message  (str (count artifacts) " artifact(s) in registry")}]
                      (concat (dangling-dependencies artifacts)
                              (dangling-dependency-refs artifacts)
                              (ambiguous-schema-versions artifacts)
                              (:extra-checks opts)))
      :errors   (:extra-errors opts [])
      :warnings (:extra-warnings opts [])
      :metadata metadata})))

(defn validate-registry
  "Deprecated alias for validate-artifact-registry. Prefer the full name."
  [registry-map & [opts]]
  (apply validate-artifact-registry registry-map (when opts [opts])))

(defn validate-artifact-registry-from-file
  "Read a registry JSON file and run validate-artifact-registry on its content.
   Returns a finalized validation-root.v1 map.

   registry-path — string path to a test-artifacts.json file
   opts          — same as validate-artifact-registry opts (optional)"
  [registry-path & [opts]]
  (let [data (json/read-str (slurp registry-path) :key-fn keyword)]
    (apply validate-artifact-registry data (when opts [opts]))))

(defn -main
  "CLI entry point for bb validation:artifact-registry.

   Usage: clojure -M -m resolver-sim.validation.integration.artifact-registry <registry-path>

   Reads the registry file, validates it, writes artifact-registry-validation.json
   to the same directory, and prints a status line to stdout.
   Exits with 0 on success, 1 on operational error."
  [& args]
  (let [registry-path (or (first args)
                          (evcfg/artifact-path :artifacts/registry))
        f (java.io.File. registry-path)]
    (if (.exists f)
      (try
        (let [root    (validate-artifact-registry-from-file registry-path)
              out-path (java.io.File. (.getParentFile f) "artifact-registry-validation.json")]
          (spit out-path (json/write-str root))
          (println (str "[artifact-registry-validation] " (.getPath f) " -> " (.getPath out-path)))
          (println (str "[artifact-registry-validation] status=" (name (:status root))
                        " passed=" (get-in root [:metrics :passed])
                        " failed=" (get-in root [:metrics :failed])
                        " warnings=" (get-in root [:metrics :warnings])
                        " total-checks=" (get-in root [:metrics :checks])
                        " artifacts=" (get-in root [:extra :artifact-count])))
          (when (= :failed (:status root))
            (println "[artifact-registry-validation] Registry validation :failed (see" (.getPath out-path) "for details)"))
          (System/exit 0))
        (catch Exception e
          (println "[artifact-registry-validation] Operational error:" (.getMessage e))
          (System/exit 1)))
      (do
        (println "[artifact-registry-validation] File not found:" registry-path)
        (System/exit 1)))))

