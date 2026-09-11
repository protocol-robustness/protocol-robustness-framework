(ns resolver-sim.util.attribution
  "Compatibility facade for attribution utilities.

   New code may require the focused namespaces directly:
   - resolver-sim.util.attribution.context
   - resolver-sim.util.attribution.schema
   - resolver-sim.util.attribution.validation
   - resolver-sim.util.attribution.logging
   - resolver-sim.util.evidence.schema"
  (:require [resolver-sim.logging :as log]
            [resolver-sim.util.attribution.context :as context]
            [resolver-sim.util.attribution.schema :as schema]
            [resolver-sim.util.attribution.validation :as validation]
            [resolver-sim.util.evidence.schema :as evidence-schema]))

(def ^:dynamic *attribution* {})

(def ^:private attribution-version 1)

(defrecord AttributedState [state attribution])

(def required-evidence-keys schema/required-evidence-keys)
(def scenario-evidence-keys schema/scenario-evidence-keys)
(def known-attribution-keys schema/known-attribution-keys)
(def known-attribution-namespaces schema/known-attribution-namespaces)
(def known-evidence-payload-keys evidence-schema/known-evidence-payload-keys)

(def attribution-marker-keys
  "Keys that identify a map as attribution rather than arbitrary domain data."
  (into (set (keys known-attribution-keys))
        [:ctx/step
         :ctx/event-id
         :ctx/trial-id
         :ctx/replay-seed
         :ctx/oracle-cursor
         :ctx/oracle-mode
         :ctx/oracle-fixture-id
         :ctx/evidence-group-id]))

(defn attribution-map?
  "True when m looks like an attribution map, not an arbitrary world/config/result map."
  [m]
  (and (map? m)
       (boolean (some attribution-marker-keys (keys m)))))

(defn wrap-state
  "Wraps raw state and attribution into an internal envelope."
  [state attribution]
  (->AttributedState state attribution))

(defn unwrap-state
  "Unwraps raw state from the internal envelope."
  [attributed-state]
  (cond
    (instance? AttributedState attributed-state) (:state attributed-state)
    (instance? resolver_sim.util.attribution.context.AttributedState attributed-state) (context/unwrap-state attributed-state)
    :else attributed-state))

(defn explicit-attribution
  "Return attribution only when x explicitly carries attribution. Arbitrary maps
   without attribution marker keys are ignored."
  [x]
  (cond
    (instance? AttributedState x) (:attribution x)
    (instance? resolver_sim.util.attribution.context.AttributedState x) (context/get-attribution x)
    (attribution-map? x) x
    :else nil))

(defn resolve-attribution
  "Resolve attribution safely, preferring explicit override, then explicit
   attribution carried by x, then the current dynamic context."
  ([x]
   (resolve-attribution x nil))
  ([x explicit-attr]
   (or (explicit-attribution explicit-attr)
       (explicit-attribution x)
       *attribution*)))

(defn nested-attribution
  "Find the first explicit attribution map nested inside x.

   Searches AttributedState values, attribution maps, common attribution carrier
   keys (`:attribution`, `:attribution-context`), attribution envelopes with
   `:attribution/context`, and nested map/sequential values. Arbitrary maps are
   not treated as attribution unless they contain attribution marker keys."
  [x]
  (letfn [(clean [attr]
            (when attr
              (validation/sanitize-attribution attr)))
          (from-envelope [m]
            (when (map? m)
              (or (clean (explicit-attribution (:attribution/context m)))
                  (clean (explicit-attribution (:attribution m)))
                  (clean (explicit-attribution (:attribution-context m))))))
          (search [v]
            (or (clean (explicit-attribution v))
                (from-envelope v)
                (cond
                  (map? v) (some search (vals v))
                  (sequential? v) (some search v)
                  :else nil)))]
    (search x)))

(defn get-attribution
  "Compatibility wrapper: retrieves attribution from an AttributedState,
   attribution map, arbitrary map, or dynamic context. If explicit-attr is
   provided, it takes precedence. Prefer resolve-attribution for new code."
  ([attributed-state-or-map]
   (get-attribution attributed-state-or-map nil))
  ([attributed-state-or-map explicit-attr]
   (cond
     (some? explicit-attr) explicit-attr
     (instance? AttributedState attributed-state-or-map) (:attribution attributed-state-or-map)
     (map? attributed-state-or-map) attributed-state-or-map
     (some? attributed-state-or-map) (context/get-attribution attributed-state-or-map)
     :else *attribution*)))

(defn artifact-safe-value?
  "Predicate for values safe to include in persisted JSON/EDN artifacts."
  [v]
  (validation/artifact-safe-value? v))

(defn invalid-attribution-entries
  "Returns entries from attr that will be dropped by sanitize-attribution."
  [attr]
  (validation/invalid-attribution-entries attr))

(defn warn-invalid-attribution!
  "Log a warning for each invalid attribution entry."
  [attr]
  (validation/warn-invalid-attribution! attr))

(defn assert-valid-attribution!
  "Throw if attr contains entries that will be dropped by sanitize-attribution."
  [attr]
  (validation/assert-valid-attribution! attr))

(defn sanitize-attribution-with-warnings!
  "Drop invalid attribution entries and log one warning per dropped entry."
  [attr]
  (validation/sanitize-attribution-with-warnings! attr))

(defmacro with-attribution
  "Execute body with merged attribution context."
  [attr & body]
  `(let [a# ~attr]
     (binding [*attribution* (merge *attribution* (sanitize-attribution-with-warnings! a#))]
       ~@body)))

(defmacro with-attribution-strict
  "Like with-attribution, but throws on invalid attribution entries."
  [attr & body]
  `(let [a# ~attr]
     (assert-valid-attribution! a#)
     (binding [*attribution* (merge *attribution* (sanitize-attribution a#))]
       ~@body)))

(defmacro with-resolved-attribution
  "Resolve attribution from an explicit carrier (e.g. AttributedState) and bind it
   for legacy dynamic consumers. The resolved context is merged into the current
   dynamic context to preserve with-attribution bridge semantics."
  [attributed & body]
  `(binding [*attribution* (merge *attribution* (resolve-attribution ~attributed))]
     ~@body))

(defn current-attribution [] *attribution*)

(defn sanitize-attribution
  "Drop non-namespaced or non-serializable values from attribution map. Pure: does not log."
  [attr]
  (validation/sanitize-attribution attr))

(defn attribution-quality
  "Analyze the provided attribution context against required keys."
  [attr requirements]
  (validation/attribution-quality attr requirements))

(defn current-evidence-attribution
  "Helper for evidence capture modules to get a standardized attribution block.
   Accepts an optional explicit attribution map; falls back to *attribution*."
  ([] (current-evidence-attribution required-evidence-keys nil))
  ([requirements] (current-evidence-attribution requirements nil))
  ([requirements explicit-attr]
   (attribution-quality (or explicit-attr *attribution*) requirements)))

(defn attribution-envelope
  "Return a versioned, sanitized envelope of the current context.
   Accepts optional explicit attribution map."
  ([] (attribution-envelope *attribution*))
  ([attr]
   {:attribution/version attribution-version
    :attribution/context (sanitize-attribution attr)}))

(defn annotate-evidence
  "Attach the current attribution envelope to an evidence record.
   Accepts optional explicit attribution map."
  ([evidence] (annotate-evidence evidence *attribution*))
  ([evidence attr]
   (assoc evidence :attribution (attribution-envelope attr))))

(defn log-with-attr [level msg & [data]]
  (log/log! level msg (merge data *attribution*)))

(defn make-context [data] data)

(defn log-annotated! [level msg ctx data]
  (log/log! level msg (merge data ctx)))
