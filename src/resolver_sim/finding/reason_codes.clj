(ns resolver-sim.finding.reason-codes
  "Stable machine-readable finding reason identity.

  The framework emits reason identifiers from many subsystems: admission
  boundaries, package completion gates, review rounds, custody verifiers.
  Historically these were ad-hoc keywords with no contract governing their
  meaning, classification, or whether consumers may depend on them.

  This namespace is the registry that fixes that. A finding reason is:

    {:reason/code       :evidence/required-artifact-unreadable
     :reason/schema     :finding-reason.v1
     :reason/class      :unavailable
     :reason/subsystem  :evidence-package
     :reason/stability  :stable}

  The critical distinctions:

    code      machine-stable semantic identity; consumers may branch on it.
    class     broad interpretation, one of:
                :rejection      the supplied candidate failed policy
                :unavailable    a prerequisite artifact/input was absent
                :invalid-input  present but malformed / failed validation
                :internal       framework defect visible at a boundary
    detail    NONCANONICAL diagnostic prose (never parsed by consumers)
    caused-by structured nesting of underlying findings

  BOUNDARY RULE — only protocol-visible reasons are registered here. Internal
  JVM/database/programming errors remain exceptions; they enter this registry
  only when they cross an admission- or protocol-visible surface. This keeps
  the registry a contract, not an exception catalogue.

  STABILITY RULE — :stable codes may not change meaning once released.
  New codes start as :provisional until a release ratifies them. Renaming or
  re-classing a :stable code is a breaking protocol change.

  Constructors validate against the registry so an unregistered code cannot
  silently cross a boundary."
  (:require [clojure.string :as str]))

;; ── Schema ────────────────────────────────────────────────────────────────────

(def ^:const schema-version :finding-reason.v1)

(def classes
  "Closed enumeration of finding classes."
  #{:rejection :unavailable :invalid-input :internal})

(def stabilities
  "Closed enumeration of stability levels."
  #{:stable :provisional})

(defn valid-entry?
  "True when `entry` conforms to :finding-reason.v1.

  Code identity policy: codes are registered EXACTLY as they cross the
  protocol boundary today. Legacy subsystems emit simple (unqualified)
  keywords; those are registered verbatim and surfaced by
  `conformance-report` under :unqualified-codes so migration to namespaced
  codes stays visible without pretending the current surface differs.
  NEW codes must be namespaced."
  [entry]
  (and (map? entry)
       (= schema-version (:reason/schema entry))
       (keyword? (:reason/code entry))
       (contains? classes (:reason/class entry))
       (keyword? (:reason/subsystem entry))
       (contains? stabilities (:reason/stability entry))))

;; ── Registry ──────────────────────────────────────────────────────────────────
;;
;; Seeds are harvested from existing emitters. Codes appear here exactly as
;; they are emitted today; entries marked :provisional conflate multiple
;; causes today and should be split before ratification.

(def ^:private known-codes
  {;; resubmission chain admission
   :ok                              {:reason/class :rejection      ; success reason on admit results
                                     :reason/subsystem :resubmission-chain
                                     :reason/stability :stable}
   :receipt-authority-not-configured {:reason/class :rejection
                                      :reason/subsystem :resubmission-chain
                                      :reason/stability :stable}
   :parent-not-current-head         {:reason/class :rejection
                                     :reason/subsystem :resubmission-chain
                                     :reason/stability :stable}

   ;; force-authorisation scope checks
   :scope-hash-mismatch             {:reason/class :rejection
                                     :reason/subsystem :force-authorisation
                                     :reason/stability :stable}
   :scope-mismatch                  {:reason/class :rejection
                                     :reason/subsystem :force-authorisation
                                     :reason/stability :stable}
   :invalid-parameter-attribution   {:reason/class :rejection
                                     :reason/subsystem :force-authorisation
                                     :reason/stability :stable}

   ;; held-custody admission fixed point (blocking reasons)
   :held-custody/hash-integrity     {:reason/class :rejection
                                     :reason/subsystem :held-custody
                                     :reason/stability :stable}

   ;; canonical package gates
   :package/completion-gate-failed  {:reason/class :invalid-input
                                     :reason/subsystem :package
                                     :reason/stability :provisional} ; conflates completeness+integrity causes
   :package/missing-required-artifact {:reason/class :unavailable
                                       :reason/subsystem :package
                                       :reason/stability :stable}
   :package/missing-authoritative-identity {:reason/class :invalid-input
                                            :reason/subsystem :package
                                            :reason/stability :stable}
   :package/unsupported-run-type    {:reason/class :invalid-input
                                     :reason/subsystem :package
                                     :reason/stability :stable}

   ;; value-at-risk gate
   :value-at-risk/validator-failed  {:reason/class :invalid-input
                                     :reason/subsystem :package
                                     :reason/stability :provisional} ; conflates unreadable/mismatch causes
   :value-at-risk/source-not-registered {:reason/class :unavailable
                                         :reason/subsystem :package
                                         :reason/stability :stable}
   :value-at-risk/source-ref-mismatch {:reason/class :invalid-input
                                       :reason/subsystem :package
                                       :reason/stability :stable}
   :value-at-risk/summary-mismatch  {:reason/class :invalid-input
                                     :reason/subsystem :package
                                     :reason/stability :stable}})

(defn- enrich
  [[code {:keys [reason/class reason/subsystem reason/stability]}]]
  {:pre [(keyword? code)]}
  {:reason/code code
   :reason/schema schema-version
   :reason/class class
   :reason/subsystem subsystem
   :reason/stability stability})

(def registry
  "code → full :finding-reason.v1 entry. Frozen at load."
  (into {} (map (fn [[k v]] [k (enrich [k v])])) known-codes))

(defn registered?
  "True when `code` has a registry entry."
  [code]
  (contains? registry code))

(defn describe
  "Full :finding-reason.v1 entry for `code`, or nil."
  [code]
  (get registry code))

(defn all-codes
  "Sorted vector of every registered code."
  []
  (vec (sort (keys registry))))

;; ── Findings ──────────────────────────────────────────────────────────────────

(defn finding*
  "Build a finding map WITHOUT registration checking. Reserved for the
   transitional period: prefer `finding`."
  [code detail caused-by]
  {:finding/reason-code code
   :finding/detail detail
   :finding/caused-by (vec caused-by)})

(defn finding
  "Build a protocol-visible finding for a REGISTERED reason code.

    (finding :evidence/required-artifact-unreadable
             \"manifest/value-at-risk.json missing from artifacts\"
             [])

  Throws if `code` is unregistered — the correct friction: a new
  protocol-visible reason must be added to the registry first."
  ([code detail]
   (finding code detail []))
  ([code detail caused-by]
   (when-not (registered? code)
     (throw (ex-info "Unregistered finding reason code"
                     {:code ::unregistered-reason-code
                      :attempted code
                      :hint "Add the code to resolver-sim.finding.reason-codes/known-codes"})))
   (when-not (string? detail)
     (throw (ex-info "Finding detail must be a string" {:code ::invalid-detail :got (type detail)})))
   (when-not (vector? caused-by)
     (throw (ex-info "Finding caused-by must be a vector" {:code ::invalid-caused-by})))
   (finding* code detail caused-by)))

(defn provisional-finding
  "Explicit opt-in for codes not yet in the registry. Emits the finding tagged
   :finding/unregistered true so downstream can quarantine it. Use during
   migration only."
  [code detail caused-by]
  (assoc (finding* code detail caused-by) :finding/unregistered true))

(defn classify
  "The :reason/class for `code` when registered, else nil."
  [code]
  (:reason/class (describe code)))

;; ── Conformance helpers ───────────────────────────────────────────────────────

(defn invalid-entries
  "Registry self-audit: returns [] when every entry conforms to the schema.
   Intended for CI invocation via `conformance-report`."
  []
  (into [] (comp (filter #(not (valid-entry? (val %))))
                 (map key))
        registry))

(defn conformance-report
  "Summary map suitable for test assertions and CI logs."
  []
  {:schema schema-version
   :code-count (count registry)
   :classes-used (into (sorted-set) (map :reason/class) (vals registry))
   :stabilities-used (into (sorted-set) (map :reason/stability) (vals registry))
   :unqualified-codes (vec (sort (filter simple-keyword? (keys registry))))
   :invalid-codes (vec (sort (map (fn [c] [:reason/invalid-entry c]) (invalid-entries))))})
