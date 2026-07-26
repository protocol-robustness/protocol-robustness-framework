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

(def resolution-quality-values
  "How correct or reliable the resolution outcome was."
  #{:correct
    :incorrect
    :contested
    :unverified
    :low-confidence
    :high-confidence})

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
