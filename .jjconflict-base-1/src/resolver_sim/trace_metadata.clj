(ns resolver-sim.trace-metadata
  "Protocol-independent trace vocabulary for the Protocol Robustness Framework.

   Every actor, adversary, transition, effect, scenario, outcome, and resolution
   has a stable keyword type drawn from the sets below.  These are the allowed
   descriptive values that any protocol can use when emitting typed traces.

   Design principles:
   1. Zero dependencies — this namespace contains only keyword sets.
   2. Stable enums — treat them as API; add, do not remove.
   3. Generic — no SEW action names, SEW state names, or SEW invariant IDs.
   4. Queryable — protocol integrations validate their classifiers against these
      sets to ensure cross-protocol trace compatibility.")

;; ===========================================================================
;; Actor taxonomy
;; ===========================================================================

(def actor-types
  "Structural roles an agent can occupy in the protocol.
   Type is structural (what the agent IS), role is behavioural (how it acts)."
  #{:sender
    :recipient
    :resolver
    :appealer
    :challenger
    :governance
    :oracle
    :keeper
    :observer})

(def actor-roles
  "Behavioural roles — how an actor participates, independent of its type.
   A :resolver can be :honest or :malicious; same structural type, different role."
  #{:honest
    :rational
    :malicious
    :lazy
    :coordinated
    :sybil})

;; ===========================================================================
;; Adversary taxonomy
;; ===========================================================================

(def adversary-types
  "Strategy classes for adversarial actors.
   Each class has a distinct objective and attack surface."
  #{:profit-maximizer
    :forking-strategist
    :griefer
    :liveness-attacker
    :colluder
    :briber
    :censor
    :delay-attacker
    :information-attacker})

(def adversary-traits
  "Composable modifier traits that qualify an adversary strategy.
   Multiple traits can apply simultaneously."
  #{:multi-step
    :cross-epoch
    :capital-efficient
    :high-capital
    :stealthy
    :adaptive
    :reactive
    :coordinated})

;; ===========================================================================
;; Transition taxonomy
;; ===========================================================================

(def transition-types
  "Semantic categories for protocol state transitions."
  #{:creation
    :state-change
    :economic
    :resolution
    :escalation
    :timeout
    :governance
    :oracle
    :maintenance})

;; ===========================================================================
;; Effect taxonomy
;; ===========================================================================

(def effect-types
  "Economic effect classifications — what changes in the protocol's accounting."
  #{:lock-funds
    :release-funds
    :refund
    :collect-fee
    :slash
    :distribute-slash
    :restore-stake
    :burn
    :mint
    :transfer
    :no-effect})

;; ===========================================================================
;; Invariant category taxonomy
;; ===========================================================================

(def invariant-category-types
  "Semantic categories for invariant classification.
   Protocol-specific invariant registries map their IDs into these categories."
  #{:accounting
    :state-machine
    :economic
    :liveness
    :safety
    :governance})

;; ===========================================================================
;; Scenario taxonomy
;; ===========================================================================

(def scenario-types
  "High-level scenario categories for simulation organization and filtering."
  #{:baseline
    :edge-case
    :adversarial
    :stress
    :parameter-sweep
    :multi-epoch
    :governance-change})

;; ===========================================================================
;; Outcome taxonomy
;; ===========================================================================

(def outcome-types
  "What happened at the end of a scenario or trace."
  #{:normal-completion
    :profit-extraction
    :loss
    :liveness-failure
    :invariant-failure
    :cascade-failure
    :partial-recovery
    :expected-violation})

;; ===========================================================================
;; Resolution taxonomy
;; ===========================================================================

(def resolution-outcome-values
  "Outcome assessment for resolution quality.
   Requires explicit comparison against authoritative expected truth:
     :correct    — matches authoritative expected result
     :incorrect  — conflicts with authoritative expected result
     :contested  — unresolved material disagreement, no authoritative truth
     :unverified — insufficient facts to determine correctness"
  #{:correct
    :incorrect
    :contested
    :unverified})

(def resolution-confidence-legacy-values
  "Legacy confidence-flavoured resolution descriptors.
   These are NOT correctness claims.  They describe the trace-level
   confidence in the resolution process, not whether the outcome was right.
   Retained for backward compatibility with persisted trace data.
   New code should use resolution-outcome-values for outcome assessment
   and the canonical confidence schema (resolver-sim.evidence.confidence)
   for confidence."
  #{:low-confidence
    :high-confidence})

(def resolution-quality-values
  "Combined resolution quality vocabulary — includes both outcome assessment
   and legacy confidence descriptors.
   For outcome assessment use resolution-outcome-values.
   For confidence, use the canonical confidence schema."
  (into resolution-outcome-values resolution-confidence-legacy-values))

(defn valid-resolution-quality-for-schema?
  "Schema-aware resolution quality validation.
   For schema-version \"three-member-research-certificate.v1\" and later,
   only resolution-outcome-values are valid for newly produced certificates.
   Legacy quality values (:low-confidence, :high-confidence) remain valid
   for verification of older certificates.
   Returns true when the quality value is acceptable for the given schema."
  [schema-version quality]
  (if (= schema-version "three-member-research-certificate.v1")
    (contains? resolution-outcome-values quality)
    (contains? resolution-quality-values quality)))

;; ── Resolution-quality classifier ─────────────────────────────────────────

(defn classify-resolution-quality
  "Classify resolution quality from explicit facts.

   Input map may contain:
     :authoritative-expected-outcome — the known correct outcome keyword, or nil
     :actual-outcome                 — the observed outcome keyword
     :has-unresolved-dissent?        — whether material disagreement persists
     :verification-facts-complete?   — whether evidence is sufficient for verification

   Precedence:
     1. When authoritative truth exists and matches actual → :correct
     2. When authoritative truth exists and conflicts     → :incorrect
     3. When unresolved material disagreement exists       → :contested
     4. When verification facts are incomplete             → :unverified
     5. Default                                            → :unverified

   Returns the quality keyword.

   Throws on contradictory input:
     - Both matching and conflicting with authoritative truth
     - :correct claimed without authoritative comparison"
  [{:keys [authoritative-expected-outcome actual-outcome
           has-unresolved-dissent? verification-facts-complete?]
    :as facts}]
  (let [authoritative? (some? authoritative-expected-outcome)
        matches? (and authoritative? (= actual-outcome authoritative-expected-outcome))
        conflicts? (and authoritative? actual-outcome
                        (not= actual-outcome authoritative-expected-outcome))]
    (when (and matches? conflicts?)
      (throw (ex-info "Contradictory classifier input: both matching and conflicting with expected truth"
                      facts)))
    (cond
      (and authoritative? (nil? actual-outcome))
      (throw (ex-info "Cannot classify: authoritative truth present but no actual outcome"
                      facts))

      (and authoritative? matches?)
      :correct

      (and authoritative? conflicts?)
      :incorrect

      has-unresolved-dissent?
      :contested

      (not verification-facts-complete?)
      :unverified

      :else :unverified)))

;; ── Resolution-quality-to-confidence mapping (compatibility) ──────────────

(def ^:private resolution-quality->confidence-map
  "Maps resolution-outcome keywords to canonical structured confidence records.
   Legacy confidence-flavoured keywords (:low-confidence, :high-confidence)
   are not outcome assessments and are NOT included in this map.
   Use the canonical confidence schema directly for confidence values."
  {:correct   {:level :high   :status :final      :scope :unbounded}
   :incorrect {:level :low    :status :final      :scope :unbounded}
   :contested {:level nil     :status :provisional :scope :unbounded}
   :unverified {:level nil     :status :provisional :scope :bounded}})

(defn resolution-quality->confidence
  "Map a resolution-outcome keyword to a canonical structured confidence record.
   This is a lossy mapping — it infers confidence from outcome assessment.

   :correct   → {:level :high   :status :final      :scope :unbounded}
   :incorrect → {:level :low    :status :final      :scope :unbounded}
   :contested → {:level nil     :status :provisional :scope :unbounded}
   :unverified→ {:level nil     :status :provisional :scope :bounded}

   This function is provided for backward compatibility.
   New code should use the canonical confidence schema directly.

   NOTE: :high-confidence and :low-confidence are NOT passed through this
   function — they are confidence descriptors, not outcome assessments.
   Returns nil for those values."
  [quality]
  (get resolution-quality->confidence-map quality nil))

(def resolution-finality-values
  "The finality state of the resolution."
  #{:final
    :appealable
    :under-appeal
    :reopened
    :stalled})

(def resolution-timing-values
  "When and how the resolution was triggered."
  #{:instant
    :within-deadline
    :delayed
    :timeout-triggered
    :deadline-breached})

(def resolution-participation-values
  "How many eligible parties participated in the resolution."
  #{:full-participation
    :partial-participation
    :no-participation
    :asymmetric-participation})

(def resolution-escalation-values
  "How many escalation rounds occurred."
  #{:none
    :single-step
    :multi-step
    :max-escalation
    :recursive})

(def resolution-economic-values
  "Economic character of the resolution outcome."
  #{:profitable
    :loss-making
    :break-even
    :capital-locked
    :capital-efficient
    :over-slashed
    :under-slashed})

(def resolution-failure-values
  "Class of resolution failure, if any."
  #{:none
    :liveness-failure
    :deadlock
    :infinite-appeal-loop
    :inconsistent-state
    :partial-execution
    :economic-exploit})

(def resolution-integrity-values
  "Accounting integrity of the resolution."
  #{:fully-reconciled
    :accounting-mismatch
    :missing-effects
    :double-counted
    :leakage})
