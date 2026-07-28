(ns resolver-sim.evidence.confidence
  "Canonical confidence vocabulary, normalization, derivation, and validation.

   Confidence is three-dimensional:
     - level  (:high | :medium | :low) — strength of evidence
     - status (:final | :provisional)   — whether the conclusion is settled
     - scope  (:unbounded | :bounded | :trace-bounded) — where the conclusion is valid

   Legacy scalar representations (keywords, strings, hyphenated keywords) are
   normalized to this structured form at the boundary.

   Derivation is a pure function of policy + signals, returning a structured
   record with reasons for auditability."
  (:require [clojure.set :as set]))

;; ===========================================================================
;; Canonical vocabulary
;; ===========================================================================

(def levels
  "Allowed confidence levels: strength of support for a result or claim.
   Distinct from both scope (where it applies) and status (whether final)."
  #{:high :medium :low})

(def statuses
  "Allowed confidence statuses: whether the conclusion is settled or provisional."
  #{:final :provisional})

(def scopes
  "Allowed confidence scopes: the boundary within which the conclusion is valid."
  #{:unbounded :bounded :trace-bounded})

(def level-rank
  "Ordinal ranking for ordering and aggregation.
   Higher number = stronger confidence."
  {:high 3 :medium 2 :low 1})

;; ===========================================================================
;; Predicates
;; ===========================================================================

(defn confidence-level?
  [x]
  (contains? levels x))

(defn confidence-status?
  [x]
  (contains? statuses x))

(defn confidence-scope?
  [x]
  (contains? scopes x))

;; ===========================================================================
;; Normalization
;; ===========================================================================

(def ^:private legacy-level-map
  "Maps legacy scalar representations to canonical structured confidence records.
   Each entry handles one legacy form — keyword, string, or hyphenated keyword."
  {:high          {:level :high   :status :final      :scope :unbounded}
   :medium        {:level :medium :status :final      :scope :unbounded}
   :low           {:level :low    :status :final      :scope :unbounded}
   :high-confidence {:level :high   :status :final      :scope :unbounded}
   :low-confidence  {:level :low    :status :final      :scope :unbounded}
   :bounded       {:level nil     :status :final      :scope :bounded}
   :trace-bounded {:level nil     :status :final      :scope :trace-bounded}
   :provisional   {:level nil     :status :provisional :scope :unbounded}})

(def ^:private legacy-string-map
  {"high"          {:level :high   :status :final      :scope :unbounded}
   "medium"        {:level :medium :status :final      :scope :unbounded}
   "low"           {:level :low    :status :final      :scope :unbounded}
   "provisional"   {:level nil     :status :provisional :scope :unbounded}})

(defn normalize-confidence
  "Normalize any legacy confidence representation to the canonical structured form.

   Accepts:
     - Keyword: :high, :medium, :low, :bounded, :trace-bounded, :high-confidence,
       :low-confidence, :provisional
     - String: \"high\", \"medium\", \"low\", \"provisional\"
     - Already-structured map: {:level ... :status ... :scope ...}
       (validated for canonical vocab compliance)

   Returns a map with :level, :status, :scope."
  [x]
  (cond
    (map? x)
    (let [level  (:level x)
          status (:status x :final)
          scope  (:scope x :unbounded)]
      (merge {:status status :scope scope}
             (when level {:level level})))

    (keyword? x) (get legacy-level-map x {:level nil :status :final :scope :unbounded})

    (string? x)  (get legacy-string-map x {:level nil :status :final :scope :unbounded})

    :else {:level nil :status :final :scope :unbounded}))

;; ===========================================================================
;; Validation
;; ===========================================================================

(defn validate-confidence
  "Validate a structured confidence record against canonical vocabulary.
   Returns nil if valid, or a sequence of error messages."
  [confidence]
  (let [level  (:level confidence)
        status (:status confidence :final)
        scope  (:scope confidence :unbounded)
        errors (cond-> []
                 (and level (not (confidence-level? level)))
                 (conj (str "Invalid confidence level: " (pr-str level)
                            ". Must be one of: " (pr-str levels)))
                 (not (confidence-status? status))
                 (conj (str "Invalid confidence status: " (pr-str status)
                            ". Must be one of: " (pr-str statuses)))
                 (not (confidence-scope? scope))
                 (conj (str "Invalid confidence scope: " (pr-str scope)
                            ". Must be one of: " (pr-str scopes))))]
    (seq errors)))

;; ===========================================================================
;; Defaults
;; ===========================================================================

(def ^:private code-defaults
  "Code-level default confidence records per context.
   Used when no config file provides overrides."
  {:scenario {:level :high   :status :final      :scope :unbounded}
   :economic {:level :medium :status :provisional :scope :bounded}
   :forensic {:level :low    :status :provisional :scope :bounded}
   :finding  {:level :medium :status :final      :scope :unbounded}
   :claim    {:level :low    :status :final      :scope :unbounded}})

(defn default-confidence
  "Return the default confidence record for a given context key.

   If a policy map with a :defaults key is provided, its per-context values
   take precedence over code-level defaults.  Otherwise uses code defaults.

   Context keys: :scenario, :economic, :forensic, :finding, :claim"
  ([context]
   (get code-defaults context {:level nil :status :final :scope :unbounded}))
  ([context policy]
   (let [cfg-defaults (get policy :defaults {})
         configured   (get cfg-defaults context)]
     (if configured
       (normalize-confidence configured)
       (default-confidence context)))))

;; ===========================================================================
;; Derivation from evidence signals
;; ===========================================================================

(def ^:private code-derivation-policy
  "Code-level derivation policy matching current hardcoded behaviour.
   Used as fallback when no config policy is supplied."
  {:policy-id    :confidence/evidence-v1
   :policy-version "1.0"
   :derivation
   {:high  {:minimum-replay-match-percentage 100.0
            :required-trace-digest-status :computed
            :golden-report-required? true}
    :medium {:minimum-replay-match-percentage 95.0
             :required-trace-digest-status :computed
             :golden-report-required? false}
    :low   {:minimum-replay-match-percentage 0.0
            :required-trace-digest-status nil
            :golden-report-required? false}}})

(defn derive-confidence
  "Derive a confidence record from evidence signals and a derivation policy.

   Signals map:
     :replay-match-percentage  — float (0.0-100.0) or nil if unavailable
     :trace-digest-status      — keyword like :computed, or nil
     :golden-report-present?   — boolean

   Policy map:
     :policy-id        — keyword identifying the policy
     :policy-version   — string version
     :derivation       — map of level -> threshold map
       :high   — thresholds for high confidence
       :medium — thresholds for medium confidence

   Returns a structured confidence map including :policy-id and :reasons."
  [policy signals]
  (let [effective-policy (merge code-derivation-policy policy)
        derivation (:derivation effective-policy)
        replay-pct (:replay-match-percentage signals)
        trace-st   (:trace-digest-status signals)
        golden?    (:golden-report-present? signals)

        check-thresholds
        (fn check-thresholds [level-key]
          (when-let [thresh (get derivation level-key)]
            (and (let [min-pct (:minimum-replay-match-percentage thresh)]
                   (or (nil? min-pct)
                       (and (number? replay-pct) (>= replay-pct min-pct))))
                 (let [req-trace (:required-trace-digest-status thresh)]
                   (or (nil? req-trace)
                       (= req-trace trace-st)))
                 (let [req-golden (:golden-report-required? thresh)]
                   (or (not req-golden) golden?)))))

        [level reasons]
        (cond
          (check-thresholds :high)
          [:high
           (cond-> [:replay-complete :trace-digest-computed]
             golden? (conj :golden-report-present))]

          (check-thresholds :medium)
          [:medium [:replay-adequate :trace-digest-computed]]

          :else
          [:low
           (cond-> []
             (and (number? replay-pct) (pos? replay-pct))
             (conj :replay-partial)
             (nil? replay-pct)
             (conj :replay-unavailable))])

        status (if (and (= :high level) golden?)
                 :final
                 :provisional)]
    {:level level
     :status status
     :scope :unbounded
     :policy-id (:policy-id effective-policy)
     :reasons reasons}))

;; ===========================================================================
;; Aggregation
;; ===========================================================================

(defn aggregate-confidence
  "Aggregate a collection of confidence records using the configured method.

   Default method is :minimum (weakest-link): the aggregate is the lowest
   level, most restricted scope, and least final status in the collection.

   When a single confidence record is provided, returns it unchanged."
  ([records]
   (aggregate-confidence records {}))
  ([records _opts]
   (if (empty? records)
     {:level nil :status :final :scope :unbounded}
     (let [records (mapv normalize-confidence records)
           by-rank (fn [lvl] (get level-rank lvl 0))
           min-level (reduce (fn [a r]
                               (let [l (:level r)]
                                 (if (nil? l) a
                                     (if (nil? a) l
                                         (if (< (by-rank l) (by-rank a)) l a)))))
                             nil records)
           min-status (if (some #(= :provisional (:status %)) records)
                        :provisional
                        :final)
           min-scope (reduce (fn [a r]
                               (let [s (:scope r)]
                                 (if (= :trace-bounded s) :trace-bounded
                                     (if (= :bounded s)
                                       (if (= a :trace-bounded) :trace-bounded :bounded)
                                       a))))
                             :unbounded records)]
       {:level min-level
        :status min-status
        :scope min-scope
        :policy-id :confidence/aggregate-minimum}))))

;; ===========================================================================
;; Utility
;; ===========================================================================

(defn confidence-score
  "Return the numeric rank for a confidence record or level.
   Returns nil when no level is present."
  [confidence]
  (let [lvl (if (map? confidence) (:level confidence) confidence)]
    (get level-rank lvl)))
