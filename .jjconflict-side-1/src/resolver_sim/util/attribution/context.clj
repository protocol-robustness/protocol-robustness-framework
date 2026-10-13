(ns resolver-sim.util.attribution.context
  "Runtime attribution context and state envelope helpers.")

(def ^:dynamic *attribution* {})

(defrecord AttributedState [state attribution])

(defn wrap-state
  "Wraps raw state and attribution into an internal envelope."
  [state attribution]
  (->AttributedState state attribution))

(defn unwrap-state
  "Unwraps raw state from the internal envelope."
  [attributed-state]
  (if (instance? AttributedState attributed-state)
    (:state attributed-state)
    attributed-state))

(defn get-attribution
  "Retrieves attribution from an AttributedState or attribution map or dynamic context.
   If explicit-attr is provided, it takes precedence."
  ([attributed-state-or-map]
   (get-attribution attributed-state-or-map nil))
  ([attributed-state-or-map explicit-attr]
   (cond
     (some? explicit-attr) explicit-attr
     (instance? AttributedState attributed-state-or-map) (:attribution attributed-state-or-map)
     (map? attributed-state-or-map) attributed-state-or-map
     :else *attribution*)))

(defn current-attribution [] *attribution*)

(defmacro with-attribution
  "Execute body with merged attribution context."
  [attr & body]
  `(let [a# ~attr
         warn# (requiring-resolve (quote resolver-sim.util.attribution.validation/warn-invalid-attribution!))
         sanitize# (requiring-resolve (quote resolver-sim.util.attribution.validation/sanitize-attribution))]
     (warn# a#)
     (binding [*attribution* (merge *attribution* (sanitize# a#))]
       ~@body)))

(defmacro with-attribution-strict
  "Like with-attribution, but throws on invalid attribution entries.
   Intended for test and CI use."
  [attr & body]
  `(let [a# ~attr
         assert-valid# (requiring-resolve (quote resolver-sim.util.attribution.validation/assert-valid-attribution!))]
     (assert-valid# a#)
     (binding [*attribution* (merge *attribution* a#)]
       ~@body)))
