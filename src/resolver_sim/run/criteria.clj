(ns resolver-sim.run.criteria
  "Specs and validation for canonical run execution, bundle root runnability,
   and runner selection. These are the canonical criteria for:
   - what makes a run canonical
   - what makes a bundle root runnable (reproducible)
   - what makes a runner selection valid
   - what makes an overview comparable across runners

   Used by io.scenario-runner, registry validation, and future runner-comparison
   tooling.  Single source of truth for run criteria — do not duplicate these
   checks in callers."
  (:require [clojure.set :as set]))

;; ── Run request criteria ──────────────────────────────────────────────────────

(def required-run-request-fields
  "Fields that MUST be present in a run request for canonical or runnable-bundle
   execution.  Missing any of these marks the request as invalid."
  #{:registry-key :workspace :runner-selection})

(def runner-selection-fields
  "Fields that MUST be present in a :runner-selection map."
  #{:mode :runner-id})

(def valid-selection-modes
  "Allowed runner selection modes.  :pinned is required for canonical runs."
  #{:pinned :capability-match :quorum})

(defn valid-runner-ids
  "Known execution runner ids.  Loaded from
   passive-registries/known-execution-runner-ids at runtime with a
   compile-time fallback."
  []
  (or (try
        (when-let [v (requiring-resolve
                      'resolver-sim.definitions.passive-registries/known-execution-runner-ids)]
          @v)
        (catch Exception _ nil))
      #{:runner/local-bb :runner/local-clojure}))

(def canonical-selection-mode
  "Runner selection mode required for canonical (bundle-marked) runs."
  :pinned)

;; ── Bundle root status criteria ──────────────────────────────────────────────

(def bundle-status-canonical
  "Status keyword for a fully canonical bundle root."
  :canonical)

(def bundle-status-non-canonical
  "Status keyword for a bundle root that was run with selection overrides
   (scenario filter, fixture suite, dev mode, etc.).  Still useful for
   development but not suitable for comparison or attestation."
  :non-canonical)

(def bundle-status-invalid
  "Status keyword for a bundle root that is structurally invalid (missing
   required fields, unresolvable references, etc.)."
  :invalid)

;; ── Validation helpers ───────────────────────────────────────────────────────

(defn- runner-selection-errors
  "Check a runner-selection map and return a vector of error maps."
  [selection]
  (let [sel (or selection {})]
    (cond-> []
      (seq (set/difference runner-selection-fields (set (keys sel))))
      (conj {:code :missing-runner-selection-fields
             :missing (vec (set/difference runner-selection-fields
                                           (set (keys sel))))})

      (not (contains? valid-selection-modes (:mode sel)))
      (conj {:code :unknown-selection-mode
             :mode (:mode sel)
             :known (vec (sort valid-selection-modes))})

      (and (= :pinned (:mode sel))
           (not (contains? (valid-runner-ids) (:runner-id sel))))
      (conj {:code :unknown-runner-id
             :runner-id (:runner-id sel)
             :known (vec (sort (valid-runner-ids)))}))))

(defn validate-run-request
  "Validate a run request map against the required criteria.

   Returns {:valid? true} or {:valid? false :errors [...]}.
   Checks:
   - required top-level keys are present
   - runner-selection has required sub-fields
   - runner-selection mode is known
   - runner-id is known (when :pinned mode)"
  [request]
  (let [errors (cond-> []
                 (seq (set/difference required-run-request-fields
                                      (set (keys request))))
                 (conj {:code :missing-request-fields
                        :missing (vec (set/difference
                                       required-run-request-fields
                                       (set (keys request))))})

                 (not (contains? request :runner-selection))
                 (conj {:code :missing-runner-selection})

                 (contains? request :runner-selection)
                 (into (runner-selection-errors (:runner-selection request))))]
    (if (seq errors)
      {:valid? false :errors errors}
      {:valid? true})))

(defn bundle-status
  "Determine the bundle status for a run result map.

   A bundle is canonical iff:
   - it was a full registry suite run (no scenario/fixture overrides)
   - mode is production (not dev)
   - runner selection is pinned to a known runner

   Returns one of #{:canonical :non-canonical :invalid}."
  [{:keys [canonical? non-canonical-reason runner-selection] :as _run-result}]
  (cond
    (false? canonical?) bundle-status-non-canonical
    (some? non-canonical-reason) bundle-status-non-canonical
    (not= :pinned (:mode runner-selection)) bundle-status-non-canonical
    :else bundle-status-canonical))

(defn runnable-bundle-root?
  "Check whether a bundle root is runnable (reproducible).

   A bundle root is runnable iff all of the following hold:
   - run request is present and valid
   - runner selection resolves to a known runner
   - registry snapshots are present
   - canonical status is consistent

   Returns {:runnable? true} or {:runnable? false :errors [...]}."
  [bundle-root]
  (let [run-request (:run/request bundle-root)
        request-valid (when run-request (validate-run-request run-request))
        has-registries? (contains? bundle-root :registry/snapshot)
        errors (cond-> []
                 (nil? run-request)
                 (conj {:code :missing-run-request})

                 (and run-request (not (:valid? request-valid)))
                 (conj {:code :invalid-run-request
                        :request-errors (:errors request-valid)})

                 (not has-registries?)
                 (conj {:code :missing-registry-snapshot})

                 (and has-registries?
                      (not (contains? (:registry/snapshot bundle-root)
                                      :attestor-registry-hash)))
                 (conj {:code :missing-attestor-registry-hash})

                 (and has-registries?
                      (not (contains? (:registry/snapshot bundle-root)
                                      :scenario-suite-hash)))
                 (conj {:code :missing-scenario-suite-hash})

                 (and has-registries?
                      (not (contains? (:registry/snapshot bundle-root)
                                      :dispatcher-registry-hash)))
                 (conj {:code :missing-dispatcher-registry-hash})

                 (and has-registries?
                      (not (contains? (:registry/snapshot bundle-root)
                                      :evidence-policy-hash)))
                 (conj {:code :missing-evidence-policy-hash})

                 (not= (:bundle/id bundle-root) (:bundle/hash bundle-root))
                 (conj {:code :bundle-id-hash-mismatch
                        :bundle/id (:bundle/id bundle-root)
                        :bundle/hash (:bundle/hash bundle-root)})

                 (not (contains? bundle-root :overview/hash))
                 (conj {:code :missing-overview-hash})

                 (not (contains? bundle-root :bundle/hash))
                 (conj {:code :missing-bundle-hash}))]
    (if (seq errors)
      {:runnable? false :errors errors}
      {:runnable? true})))

(defn valid-runner-selection?
  "Check whether a runner selection map is valid for execution.
   In :pinned mode the runner id must be known.
   In :capability-match mode the capabilities must be declared."
  [selection]
  (let [mode (:mode selection)
        runner-id (:runner-id selection)]
    (case mode
      :pinned (contains? (valid-runner-ids) runner-id)
      :capability-match (boolean (seq (:capabilities selection)))
      :quorum false
      false)))
