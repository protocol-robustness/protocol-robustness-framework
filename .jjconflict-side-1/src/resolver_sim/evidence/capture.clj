(ns resolver-sim.evidence.capture
  "Pure evidence-builder primitives for structured field capture.
   
   Design:
     - cap-field / cap-fields are pure builders: (cap-field evidence k v) => updated-evidence
     - Fields must be qualified keywords; nil values are silently omitted
     - Values are canonicalized for deterministic hashing
     - require-fields validates that CORE / DIAGNOSTIC evidence has required keys
     - finalize-evidence computes composite evidence-hash and optionally chains with prev-hash
   
   Flow:
     transition code
       -> cap-field / cap-fields
       -> require-fields
       -> finalize-evidence
       -> capture-event-evidence!
   
   See also resolver-sim.io.event-evidence for durable persistence."
  (:require [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.util.attribution :as attr]))

;; ── Required Fields ──────────────────────────────────────────────────────────

(def core-evidence-required-fields
  "Fields that must be present on every CORE evidence record."
  #{:scenario/id :run/id :event/seq :transition/id
    :world/before-hash :world/after-hash
    :replay/seed :oracle/cursor
    :evidence/type :evidence/importance})

(def diagnostic-evidence-required-fields
  "Fields that must be present on DIAGNOSTIC evidence."
  #{:scenario/id :run/id :event/seq
    :evidence/type :evidence/importance})

(defn default-metadata
  "Generate standardized, schema-compliant metadata for evidence export.
   Merges dynamic context with current attribution state."
  [scenario-id run-id & {:keys [event-seq] :or {event-seq 0}}]
  (let [attribution (attr/current-attribution)]
    (merge
     {:evidence/schema-version (evcfg/schema :event-evidence)
      :scenario/id             scenario-id
      :run/id                  run-id
      :event/seq               event-seq
      :evidence/timestamp      (str (java.time.Instant/now))
      :attribution/subject     (:subject/id attribution)
      :attribution/action      (:action/type attribution)
      :evidence/reason         (:evidence/reason attribution)}
     (:data attribution))))

;; ── Evidence Base ────────────────────────────────────────────────────────────

(defn evidence-base
  "Create a minimal evidence record with schema version and type.
   
   Required opts:
     :type       — evidence type keyword (e.g. :fraud-slash)
     :importance — :core | :diagnostic | :trace (default :core)
   
   Optional:
     :ctx        — attribution context map (merged into :attribution)"
  [{:keys [type importance ctx]
    :or   {importance :core}}]
  {:evidence/schema-version (evcfg/schema :event-evidence)
   :evidence/type           (when type (name type))
   :evidence/importance     (name (or importance :diagnostic))
   :evidence/context        (when ctx (attr/sanitize-attribution ctx))})

;; ── Field Capture ────────────────────────────────────────────────────────────

(defn- canonicalize-evidence-value
  "Normalize a value for deterministic serialization.
   Keywords are converted to strings; sorted-maps used for stable hashing."
  [v]
  (cond
    (instance? clojure.lang.Keyword v) (name v)
    (map? v) (into (sorted-map) (map (fn [[k v]] [(name k) v]) v))
    (sequential? v) (mapv canonicalize-evidence-value v)
    :else v))

(defn cap-field
  "Add one structured field to an evidence map.
   
   Rules:
     - k must be a qualified keyword (:ns/name)
     - nil values are silently omitted (returns evidence unchanged)
     - Non-collection values are canonicalized for deterministic hashing
   
   Returns updated evidence map."
  [evidence k v]
  (cond
    (nil? v)
    evidence

    (not (qualified-keyword? k))
    (throw (ex-info "Evidence field must be a qualified keyword"
                    {:field k :value v :evidence evidence}))

    :else
    (assoc evidence k (canonicalize-evidence-value v))))

(defn cap-fields
  "Add multiple fields to an evidence map.
   
   (cap-fields evidence {:scenario/id sid :run/id rid ...})"
  [evidence fields]
  (reduce-kv (fn [acc k v] (cap-field acc k v)) evidence fields))

;; ── Validation ───────────────────────────────────────────────────────────────

(defn require-fields
  "Validate that evidence contains all required keys for its importance level.
   
   Throws ex-info with :missing keys on failure.
   Returns evidence unchanged on success."
  [evidence]
  (let [importance (keyword (:evidence/importance evidence))
        required (case importance
                   :core core-evidence-required-fields
                   :diagnostic diagnostic-evidence-required-fields
                   :trace #{}
                   #{})
        present (set (keys evidence))
        missing (seq (remove #(contains? present %) required))]
    (when missing
      (throw (ex-info (str "Missing required evidence fields for " importance " evidence")
                      {:importance importance
                       :missing (vec missing)
                       :present (vec present)
                       :evidence-type (:evidence/type evidence)})))
    evidence))

;; ── Finalization ─────────────────────────────────────────────────────────────

(defn finalize-evidence
  "Finalize an evidence record before persistence.
   
   1. Canonicalizes all values for deterministic output
   2. Computes composite :evidence/hash over all other fields except
      :evidence/timestamp (metadata, not content)
   3. Optionally chains with :evidence/prev-hash
   
   Returns the finalized evidence map."
  [evidence]
  (let [hash-input (dissoc evidence :evidence/hash :evidence/timestamp)
        evidence-hash (hc/hash-with-intent {:hash/intent :evidence-content} hash-input)]
    (assoc evidence :evidence/hash evidence-hash)))

;; ── Dynamic dispatch for I/O capture ──────────────────────────────────────

(def ^:dynamic *capture-event-evidence!*
  "Dynamic var for I/O-backed evidence capture.
   Bound by resolver-sim.io.event-evidence at load time to the actual
   persistence implementation. Protocol-layer code should call
   capture-event-evidence! which delegates through this var.

   Default implementation returns nil (no-op) until bound.
   Callers can rebind to `noop-capture` to suppress evidence globally."
  (fn
    ([reason pre post inputs] nil)
    ([reason pre post inputs calc] nil)
    ([reason pre post inputs calc ctx-or-opts] nil)
    ([evidence] nil)))

(def noop-capture
  "The default no-op evidence capture implementation.
   Bind *capture-event-evidence!* to this to suppress all evidence
   capture, including protocol-level capture-event-evidence! calls.
   Defined as a literal fn so it is never affected by alter-var-root
   from resolver-sim.io.event-evidence."
  (fn
    ([reason pre post inputs] nil)
    ([reason pre post inputs calc] nil)
    ([reason pre post inputs calc ctx-or-opts] nil)
    ([evidence] nil)))

(defn capture-event-evidence!
  "Delegate to the bound I/O implementation of event evidence capture.
   See resolver-sim.io.event-evidence/capture-event-evidence! for the
   actual implementation that persists evidence artifacts to disk.
   
   Supports the same calling conventions as the I/O implementation:
   positional (legacy) or pre-built evidence map."
  [& args]
  (apply *capture-event-evidence!* args))
