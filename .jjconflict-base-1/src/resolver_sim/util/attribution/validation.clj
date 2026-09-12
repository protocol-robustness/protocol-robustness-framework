(ns resolver-sim.util.attribution.validation
  "Attribution validation, sanitization, and quality helpers."
  (:require [resolver-sim.logging :as log]
            [resolver-sim.util.attribution.context :as context]
            [resolver-sim.util.attribution.schema :as schema]))

(defn artifact-safe-value?
  "Predicate for values safe to include in persisted JSON/EDN artifacts."
  [v]
  (or (nil? v)
      (string? v)
      (keyword? v)
      (number? v)
      (boolean? v)
      (and (map? v) (every? (fn [[k v]] (and (keyword? k) (artifact-safe-value? v))) v))
      (and (sequential? v) (every? artifact-safe-value? v))))

;; ── Validation ───────────────────────────────────────────────────────────────

(defn invalid-attribution-entries
  "Returns entries from attr that will be dropped by sanitize-attribution.
   Each entry passes through three checks:
     - key must be a keyword
     - key must have a namespace (e.g. :ctx/run-id)
     - value must be artifact-safe (serializable to JSON/EDN)"
  [attr]
  (remove (fn [[k v]]
            (and (keyword? k)
                 (namespace k)
                 (artifact-safe-value? v)))
          attr))

(defn warn-invalid-attribution!
  "Log a warning for each invalid attribution entry.
   Safe to call on nil or non-map — silently skips."
  [attr]
  (when (map? attr)
    (doseq [[k v] (invalid-attribution-entries attr)]
      (log/log! :warn
                "Dropping invalid attribution entry"
                {:attribution/key k
                 :attribution/value-type (some-> v type str)
                 :attribution/reason
                 (cond
                   (not (keyword? k)) :key-not-keyword
                   (not (namespace k)) :key-not-namespaced
                   (not (artifact-safe-value? v)) :value-not-artifact-safe
                   :else :unknown)}))))

(defn assert-valid-attribution!
  "Throw if attr contains entries that will be dropped by sanitize-attribution.
   Intended for test/CI use via with-attribution-strict."
  [attr]
  (let [invalid (seq (invalid-attribution-entries attr))]
    (when invalid
      (throw (ex-info "Invalid attribution entries — will be dropped by sanitize-attribution"
                      {:invalid invalid})))))

(defn sanitize-attribution
  "Drop non-namespaced or non-serializable values from attribution map.
   Pure: does not log. Use sanitize-attribution-with-warnings! when callers
   need operator-visible warnings."
  [attr]
  (into {}
        (filter (fn [[k v]]
                  (and (keyword? k)
                       (namespace k)
                       (artifact-safe-value? v))))
        attr))

(defn sanitize-attribution-with-warnings!
  "Drop invalid attribution entries and log one warning per dropped entry."
  [attr]
  (when (map? attr)
    (doseq [[k v] (invalid-attribution-entries attr)]
      (log/log! :warn "sanitize-attribution dropping invalid entry"
                {:key k
                 :value-type (some-> v type str)
                 :reason (cond
                           (not (keyword? k)) :key-not-keyword
                           (not (namespace k)) :key-not-namespaced
                           (not (artifact-safe-value? v)) :value-not-artifact-safe
                           :else :unknown)})))
  (sanitize-attribution attr))

(defn attribution-quality
  "Analyze the provided attribution context against required keys.
   Returns {:quality :complete|:partial|:missing, :missing [...], :attribution {...}}
   Invalid entries (non-namespaced keys, non-serializable values) are dropped
   by sanitize-attribution and a warning is logged for each."
  [attr requirements]
  (let [invalid (seq (invalid-attribution-entries attr))]
    (when invalid
      (doseq [[k v] invalid]
        (log/log! :warn "attribution-quality: invalid entry dropped before quality check"
                  {:key k :value-type (some-> v type str)})))
    (let [safe-attr (sanitize-attribution attr)
          missing   (seq (remove #(contains? safe-attr %) requirements))]
      {:quality (cond
                  (nil? missing) :complete
                  (seq safe-attr) :partial
                  :else :missing)
       :missing missing
       :attribution safe-attr
       :invalid-entries (count invalid)})))

(defn current-evidence-attribution
  "Helper for evidence capture modules to get a standardized attribution block.
   Accepts an optional explicit attribution map; falls back to context/*attribution*."
  ([] (current-evidence-attribution schema/required-evidence-keys nil))
  ([requirements] (current-evidence-attribution requirements nil))
  ([requirements explicit-attr]
   (attribution-quality (or explicit-attr context/*attribution*) requirements)))
