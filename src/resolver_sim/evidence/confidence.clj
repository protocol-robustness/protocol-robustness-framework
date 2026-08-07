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
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as canon]
            [resolver-sim.hash.reference :as href]))

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
;; Composition
;;
;; Preserves the full component confidence sequence and derives an aggregate
;; through an explicit, versioned composition policy.  This separates two
;; questions that a single enum conflates:
;;   - how strong is the evidence (level);
;;   - over what domain does that strength apply (scope).
;;
;; The raw sequence is treated as evidence input, never as the final
;; classification.  Supporting components remain committed but do not lower
;; the aggregate unless the policy explicitly says they do.
;; ===========================================================================

(def roles
  "Allowed component roles within a composition.
   Only :required components normally constrain an :all-required aggregate."
  #{:required :supporting})

(def composition-policies
  "Versioned confidence composition policies.

   Each maps a logical relation between components to how they combine.
   Policy ids are protocol contracts: changing the ranking or the aggregation
   rule for an id requires a NEW id, never an in-place semantic change."
  {:prf.confidence/all-required-v1
   {:relation        :all-required
    :level-rule      :minimum            ; weakest necessary dependency
    :scope-rule      :intersection       ; claim valid only where ALL apply
    :status-rule     :most-provisional
    :uses-supporting? false}

   :prf.confidence/any-sufficient-v1
   {:relation        :any-sufficient     ; A or B independently establishes
    :level-rule      :maximum
    :scope-rule      :union              ; valid wherever ANY applies
    :status-rule     :least-provisional
    :uses-supporting? false}

   :prf.confidence/independent-corroboration-v1
   {:relation        :independent-corroboration
    :level-rule      :maximum            ; corroboration raises or preserves
    :scope-rule      :intersection
    :status-rule     :least-provisional
    :uses-supporting? false
    :min-required    2}                  ; independence requires >1 source

   :prf.confidence/conditional-v1
   {:relation        :conditional        ; A assumes B; B gates the result
    :level-rule      :minimum
    :scope-rule      :intersection
    :status-rule     :most-provisional
    :uses-supporting? false}

   :prf.confidence/informational-only-v1
   {:relation        :informational-only ; no strength claim asserted
    :level-rule      :none
    :scope-rule      :intersection
    :status-rule     :most-provisional
    :uses-supporting? true}})

(defn- scope-rank
  "Nested scope ordering: :unbounded ⊃ :bounded ⊃ :trace-bounded.
   Higher rank = more restricted (smaller domain)."
  [s]
  (case s
    :unbounded      0
    :bounded        1
    :trace-bounded  2
    -1))

(defn scope-intersection
  "Intersection of two scopes (most-restricted scope that is a subset of both).
   Scopes are nested, so the intersection is the more-specific scope.
   Returns nil when either scope is outside the canonical vocabulary."
  [a b]
  (let [ra (scope-rank a)
        rb (scope-rank b)]
    (when (and (not (neg? ra)) (not (neg? rb)))
      (if (>= ra rb) a b))))

(defn scope-union
  "Union of two scopes (least-specific scope that contains both).
   Returns nil when either scope is outside the canonical vocabulary."
  [a b]
  (let [ra (scope-rank a)
        rb (scope-rank b)]
    (when (and (not (neg? ra)) (not (neg? rb)))
      (if (<= ra rb) a b))))

(defn validate-component
  "Validate a single bound confidence component.
   Returns nil if valid, or a sequence of error messages.

   Components must be bound to a subject-hash so confidence values cannot be
   reordered or reassigned independently of the claims they qualify.  A
   :required component must carry a level; :supporting components may be
   informational (nil level) and never lower an aggregate."
  [c]
  (let [subject (:subject-hash c)
        role    (:role c)
        level   (:level c)
        scope   (:scope c)
        status  (:status c :final)
        errors  (cond-> []
                  (not (and (string? subject) (pos? (count subject))))
                  (conj "Component must carry a :subject-hash string binding to the claim/artifact it qualifies")

                  (not (contains? roles role))
                  (conj (str "Invalid role: " (pr-str role)
                             ". Must be one of: " (pr-str roles)))

                  (and (= :required role) (nil? level))
                  (conj "A :required component must carry a :level")

                  (and level (not (confidence-level? level)))
                  (conj (str "Invalid confidence level: " (pr-str level)
                             ". Must be one of: " (pr-str levels)))

                  (not (confidence-scope? scope))
                  (conj (str "Invalid confidence scope: " (pr-str scope)
                             ". Must be one of: " (pr-str scopes)))

                  (not (confidence-status? status))
                  (conj (str "Invalid confidence status: " (pr-str status)
                             ". Must be one of: " (pr-str statuses))))]
    (seq errors)))

(defn validate-composition
  "Validate a component vector and policy id.
   Returns nil if valid, or a sequence of error messages."
  [components policy-id]
  (cond-> []
    (not (vector? components))
    (conj "Components must be a vector of bound confidence records")

    (not (contains? composition-policies policy-id))
    (conj (str "Unknown composition policy: " (pr-str policy-id)))

    (and (vector? components) (some validate-component components))
    (into (vec (mapcat (fn [c] (or (validate-component c) [])) components)))))

(defn- required-component-levels
  "Levels of the components that actually constrain the aggregate under a policy."
  [policy components]
  (let [eligible (if (:uses-supporting? policy) components
                     (filter #(= :required (:role %)) components))]
    (keep :level eligible)))

(defn- aggregate-level
  "Apply a policy's :level-rule to a set of levels."
  [rule levels]
  (case rule
    :minimum  (when (seq levels) (apply min-key level-rank levels))
    :maximum  (when (seq levels) (apply max-key level-rank levels))
    :none     nil
    nil))

(defn- aggregate-scope
  "Apply a policy's :scope-rule to a set of scopes."
  [rule scopes]
  (when (seq scopes)
    (case rule
      :intersection (reduce scope-intersection scopes)
      :union        (reduce scope-union scopes)
      nil)))

(defn- aggregate-status
  "Apply a policy's :status-rule to a set of statuses."
  [rule statuses]
  (case rule
    :most-provisional  (if (some #{:provisional} statuses) :provisional :final)
    :least-provisional (if (some #{:final} statuses) :final :provisional)
    :none              nil
    nil))

(defn compose-confidence
  "Compose component confidences into an aggregate under a versioned policy.

   Arguments:
     components — vector of bound records:
                  {:subject-hash <str> :role :required|:supporting
                   :level <kw> :scope <kw> [:status <kw>]}
     policy-id  — a key of composition-policies (default :all-required-v1)

   Returns a composition profile that PRESERVES the full component sequence
   alongside the derived aggregate:

     {:confidence/composition-policy <id>
      :confidence/relation           <relation>
      :confidence/components         <vector, unchanged>
      :confidence/aggregate          {:level <kw|nil> :scope <kw> :status <kw>}
      :confidence/reasons            [...]}

   Fails closed: unknown levels/scopes/roles/statuses, strings where keywords
   are required, missing subject-hash bindings, and unknown policy ids are
   rejected, never silently defaulted.  An empty required set yields
   :not-evaluated (never a vacuous :high)."
  ([components]
   (compose-confidence components :prf.confidence/all-required-v1))
  ([components policy-id]
   (when-let [errs (seq (validate-composition components policy-id))]
     (throw (ex-info "Invalid confidence composition"
                     {:errors errs :components components :policy-id policy-id})))
   (let [policy     (get composition-policies policy-id)
         eligible   (if (:uses-supporting? policy)
                      components
                      (filter #(= :required (:role %)) components))
         min-required (:min-required policy)
         levels     (required-component-levels policy components)
         scopes     (keep :scope eligible)
         statuses   (keep :status eligible)]
     (when (and min-required (< (count eligible) min-required))
       (throw (ex-info "Confidence composition requires more independent sources"
                       {:policy-id policy-id :relation (:relation policy)
                        :min-required min-required :actual (count eligible)})))
     (if (empty? eligible)
       {:confidence/composition-policy policy-id
        :confidence/relation          (:relation policy)
        :confidence/components         components
        :confidence/aggregate          {:level :not-evaluated
                                        :scope :unbounded
                                        :status :provisional}
        :confidence/reasons            [:empty-composition]}
       {:confidence/composition-policy policy-id
        :confidence/relation          (:relation policy)
        :confidence/components         components
        :confidence/aggregate          {:level (aggregate-level (:level-rule policy) levels)
                                        :scope (aggregate-scope (:scope-rule policy) scopes)
                                        :status (aggregate-status (:status-rule policy) statuses)}
        :confidence/reasons            [:required (count (filter #(= :required (:role %)) components))
                                        :supporting (count (filter #(= :supporting (:role %)) components))]}))))

(defn verify-composition
  "Verify a composition profile is internally consistent: the stated aggregate
   must be recomputable from the committed components and policy id.

   Returns true if recomputation matches the stored aggregate, false otherwise.
   A producer may not state an aggregate without it being derivable from the
   committed inputs."
  [profile]
  (let [recomputed (compose-confidence (:confidence/components profile)
                                       (:confidence/composition-policy profile))]
    (= (:confidence/aggregate recomputed)
       (:confidence/aggregate profile))))

(defn canonical-components
  "Canonical component ordering for deterministic hashing.

   Default (set semantics): sorted by the stable :subject-hash binding, so
   permuting a logically-unordered component set does not change the committed
   bytes.  If order is semantically meaningful, callers should commit an
   explicit :order and sort by it instead."
  ([components]
   (vec (sort-by (juxt :subject-hash :role) components)))
  ([components key-fn]
   (vec (sort-by key-fn components))))

(def confidence-commitment-domain-tag
  "Domain-separation tag for confidence composition commitments.
   Prevents cross-domain hash collisions between a confidence commitment and
   any other evidence record hashed under the Canonical Hash Spec V1."
  "CONFIDENCE_COMPOSITION_V1")

(defn concatenate-bound
  "Concatenate the canonical bytes of hash-bound components and
   commit them as a single domain-separated canonical sha256 reference.

   The full bound sequence is preserved in the commitment — the sequence is
   the evidence input, not a collapsed aggregate.  Components are bound by
   :subject-hash so confidence cannot be reordered or reassigned independent
   of the claims it qualifies.

   ordering:
     :by-subject (default) — set semantics; canonical-components sorts by
                              :subject-hash, so permutation does not change
                              the commitment.
     :as-given             — preserves caller order (sequence semantics);
                              use only when position is meaningful.

   Returns a canonical \"sha256:<hex>\" reference."
  ([components]
   (concatenate-bound components :by-subject))
  ([components ordering]
   (let [components (if (= ordering :as-given)
                      (vec components)
                      (canonical-components components))]
     (href/sha256-ref
      (canon/domain-hash confidence-commitment-domain-tag components)))))

;; ===========================================================================
;; Utility
;; ===========================================================================

(defn confidence-score
  "Return the numeric rank for a confidence record or level.
   Returns nil when no level is present."
  [confidence]
  (let [lvl (if (map? confidence) (:level confidence) confidence)]
    (get level-rank lvl)))
