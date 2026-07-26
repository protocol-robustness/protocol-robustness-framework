(ns resolver-sim.protocols.sew.trace-metadata
  "Typed metadata for simulation traces.

   Defines the canonical type vocabulary for the Protocol Robustness Framework.
   Every actor, adversary, transition, effect, invariant, scenario, outcome,
   and resolution has a stable keyword type drawn from one of the sets below.

   Design principles:
   1. Keep enums stable — treat them as API.
   2. Prefer composition over explosion — 10 types + traits beats 50 types.
   3. Align with protocol semantics — every type maps to something in the
      contracts, the simulation, and the trace.
   4. Make types queryable — 'show all liveness failures with bribery.'

   Structure:
   - Section A: vocabulary sets (stable keyword enums)
   - Section B: classifier functions (derive type from data)
   - Section C: resolution semantics (full resolution map from world state)"
  (:require [resolver-sim.protocols.sew.types :as t]
            [clojure.string :as str]))

;; ===========================================================================
;; A. Vocabulary Sets
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; A1. Actor taxonomy
;; RESERVED — no production callers. Intended for per-agent trace annotation
;; (e.g. adding :actor/type and :actor/role to each trace-entry agent field).
;; Wire-up point: enrich the :agent map in process-step's trace entry build.
;; ---------------------------------------------------------------------------

(def actor-types
  "Structural roles an agent can occupy in the protocol.
   Type is structural (what the agent IS), role is behavioural (how it acts)."
  #{:sender        ; escrow depositor
    :recipient     ; escrow beneficiary
    :resolver      ; dispute arbitrator
    :appealer      ; party challenging a resolver decision
    :challenger    ; party escalating a pending settlement
    :governance    ; protocol governance / admin
    :oracle        ; external truth source (Kleros, etc.)
    :keeper        ; automated bot executing time-locked actions
    :observer})    ; passive participant, no direct state effects

(def actor-roles
  "Behavioural roles — how an actor participates, independent of its type.
   A :resolver can be :honest or :malicious; same structural type, different role."
  #{:honest         ; follows the protocol as designed
    :rational       ; deviates if profitable, cooperative otherwise
    :malicious      ; actively adversarial, willing to take losses to harm others
    :lazy           ; under-participates (does not act on deadlines)
    :coordinated    ; acts in concert with other actors
    :sybil})        ; operates multiple identities to amplify attack surface

;; ---------------------------------------------------------------------------
;; A2. Adversary taxonomy
;; RESERVED — no production callers. Intended for adversary scenario tagging
;; (classify-adversary below infers type/traits from scenario-id or explicit
;; annotation). Wire-up point: add :adversary/type + :adversary/traits to
;; replay results for adversarial scenarios via classify-adversary.
;; ---------------------------------------------------------------------------

(def adversary-types
  "Strategy classes for adversarial actors.
   Each class has a distinct objective and attack surface."
  #{:profit-maximizer    ; exploits protocol mechanics for direct monetary gain
    :forking-strategist  ; manufactures dispute forks to exhaust or confuse resolvers
    :griefer             ; maximises harm to counterparties without profit motive
    :liveness-attacker   ; prevents protocol progress (deadlocks, delays, censorship)
    :colluder            ; coordinates across multiple identities or resolver rings
    :briber              ; offers side-payments to influence resolver decisions
    :censor              ; blocks or front-runs specific transactions
    :delay-attacker      ; exploits timeouts and deadline arithmetic
    :information-attacker}) ; withholds or fabricates evidence to skew decisions

(def adversary-traits
  "Composable modifier traits that qualify an adversary strategy.
   Multiple traits can apply simultaneously."
  #{:multi-step          ; attack requires ≥ 2 coordinated protocol steps
    :cross-epoch         ; spans multiple protocol epochs or governance cycles
    :capital-efficient   ; achieves objective with minimal capital at risk
    :high-capital        ; requires large stake or bond to execute
    :stealthy            ; avoids detection by staying within protocol limits
    :adaptive            ; responds to protocol state changes mid-attack
    :reactive            ; triggered by counterparty actions (not pre-planned)
    :coordinated})       ; requires cooperation between ≥ 2 distinct actors

;; ---------------------------------------------------------------------------
;; A3. Transition taxonomy
;; ---------------------------------------------------------------------------

(def strategic-actions
  "Actions that represent strategic decision nodes in replay traces.
   Used by subgame counterfactual analysis and projection replay."
  #{"create-escrow" "raise-dispute" "escalate-dispute" "execute-resolution"
    "sender-cancel" "recipient-cancel"})

(def transition-types
  "Semantic categories for protocol state transitions."
  #{:creation        ; new object instantiation (escrow, bond, stake)
    :state-change    ; escrow or dispute state mutation
    :economic        ; fund movement (release, refund, slash, fee)
    :resolution      ; dispute resolution proposal or execution
    :escalation      ; dispute level increase (challenge, escalate)
    :timeout         ; time-triggered automated action
    :governance      ; admin / governance action
    :oracle          ; external oracle input
    :maintenance})   ; keeper or batch maintenance (automate-timed-actions)

;; ---------------------------------------------------------------------------
;; A4. Effect taxonomy
;; ---------------------------------------------------------------------------

(def effect-types
  "Economic effect classifications — what changes in the protocol's accounting."
  #{:lock-funds       ; add to total-held
    :release-funds    ; move from total-held → total-released
    :refund           ; move from total-held → total-refunded
    :collect-fee      ; move from total-held → total-fees
    :slash            ; remove from resolver stake
    :distribute-slash ; move slashed stake to insurance / protocol / burn
    :restore-stake    ; return stake after successful appeal
    :burn             ; permanently remove from circulation
    :mint             ; create new tokens
    :transfer         ; move between parties without protocol accounting change
    :no-effect})      ; action succeeds but no accounting change (e.g. revert)

;; ---------------------------------------------------------------------------
;; A5. Invariant taxonomy
;; RESERVED — no production callers. Intended for test coverage analysis:
;; group invariant failures by category (e.g. how many :accounting vs
;; :safety invariants failed in a sweep). Wire-up point: enrich
;; :invariant-results in accum-metrics output or the fixture runner report.
;; ---------------------------------------------------------------------------

(def invariant-categories
  "Mapping of invariant keyword → semantic category.
   Categories: :accounting :state-machine :economic :liveness :safety :governance

   Must cover every ID in `protocols.sew.invariants/canonical-ids`."
  {:solvency                          :accounting
   :fees-non-negative                 :accounting
   :held-non-negative                 :accounting
   :conservation-of-funds             :accounting
   :finalization-accounting-correct   :accounting
   :token-tax-reconciliation          :accounting
   :settlement-principal-boundary     :accounting
   :settlement-yield-boundary         :accounting
   :liability-slash-boundary          :accounting
   :bond-boundary                     :accounting
   :fee-boundary                      :accounting
   :shortfall-fidelity                :accounting
   :migration-parity                  :accounting
   :claimable-classification          :accounting
   :single-resolution-payout-consistent :accounting
   :held-delta-accounted              :accounting
    :withdrawn-monotonic               :accounting
    :released-monotonic                :accounting
    :fee-payouts-sum-equals-total-fees-withdrawn :accounting
    :fee-payouts-monotonic             :accounting
    :all-status-combinations-valid     :state-machine
   :persisted-escrow-state-valid      :state-machine
   :escrow-state-in-graph             :state-machine
   :escrow-dispute-metadata-consistent :state-machine
   :pending-settlement-consistent     :state-machine
   :dispute-timestamp-consistent      :state-machine
   :dispute-level-bounded             :state-machine
   :terminal-states-unchanged         :state-machine
   :escrow-state-transition-valid     :state-machine
   :escalation-level-monotonic        :state-machine
   :cancellation-mutex                :state-machine
   :module-snapshot-immutable         :governance
   :slash-status-consistent           :economic
   :appeal-bond-conserved             :economic
   :appeal-bond-custody-consistent    :economic
   :bond-liquidity                    :economic
   :bond-slash-bounded                :economic
   :fee-cap                           :economic
   :slash-distribution-consistent     :economic
   :resolver-bond-mix-valid           :economic
   :senior-coverage-not-exceeded      :economic
   :slash-epoch-cap-respected         :economic
   :fraud-slash-executions-accounted  :economic
   :no-auto-fraud-execute             :safety
   :resolver-not-frozen-on-assign     :safety
   :reversal-slash-disabled           :safety
   :no-withdrawal-during-dispute      :safety
   :time-lock-integrity               :safety
   :time-non-decreasing               :safety
   :time-no-action-after-finality     :safety
   :no-stale-automatable-escrows      :liveness
   :dispute-resolution-path           :liveness
   :resolver-capacity                 :liveness
   :yield-position-consistency        :accounting
   :yield-exposure                    :accounting})

;; ---------------------------------------------------------------------------
;; A6. Scenario taxonomy
;; RESERVED — no production callers. Domain for classify-scenario (below).
;; Wire-up point: add :scenario/type to the fixture runner report or replay
;; result once classify-scenario is called in sim/fixtures.clj.
;; ---------------------------------------------------------------------------

(def scenario-types
  "High-level scenario categories for simulation organization and filtering."
  #{:baseline        ; standard happy-path and common protocol flows
    :edge-case       ; boundary conditions, permission checks, state guards
    :adversarial     ; scenarios driven by an adversarial strategy
    :stress          ; high-volume, depletion, or invariant saturation tests
    :parameter-sweep ; varying a parameter across a range
    :multi-epoch     ; scenarios spanning multiple epochs
    :governance-change}) ; protocol upgrade or governance intervention

;; ---------------------------------------------------------------------------
;; A7. Outcome taxonomy
;; RESERVED — no production callers. Domain for classify-outcome (below).
;; Wire-up point: add :outcome/type to the replay result map once
;; classify-outcome is called in replay-with-protocol or the fixture runner.
;; ---------------------------------------------------------------------------

(def outcome-types
  "What happened at the end of a scenario or trace."
  #{:normal-completion  ; protocol executed as designed
    :profit-extraction  ; adversary successfully extracted value
    :loss               ; honest party suffered unexpected loss
    :liveness-failure   ; protocol progress was blocked
    :invariant-failure  ; a safety invariant was violated
    :cascade-failure    ; multiple compounding failures
    :partial-recovery   ; failure occurred but protocol partially recovered
    :expected-violation}) ; invariant violation that is the intended test outcome

;; ---------------------------------------------------------------------------
;; A8. Resolution taxonomy
;; RESERVED — no production callers. Domain for classify-resolution (Section
;; C3 below). Wire-up point: replace resolution-semantics in trace_export.clj
;; once the Forge trace schema is updated to accept namespaced keyword values
;; instead of the legacy CDRS string maps.
;; ---------------------------------------------------------------------------

(def resolution-quality-values
  "How correct or reliable the resolution outcome was."
  #{:correct          ; matches ground truth
    :incorrect        ; diverges from ground truth (successful attack)
    :contested        ; disputed by at least one party
    :unverified       ; no oracle or appeal to confirm correctness
    :low-confidence   ; decision quality model gives < 0.5 score
    :high-confidence}) ; decision quality model gives ≥ 0.8 score

(def resolution-finality-values
  "The finality state of the resolution."
  #{:final            ; irreversible, settlement executed
    :appealable       ; within the appeal window, not yet challenged
    :under-appeal     ; active challenge in progress
    :reopened         ; previously final, re-opened by governance
    :stalled})        ; no progress possible without external intervention

(def resolution-timing-values
  "When and how the resolution was triggered."
  #{:instant             ; settled in the same block as decision
    :within-deadline     ; settled before the appeal or pending deadline
    :delayed             ; settled after expected deadline
    :timeout-triggered   ; triggered automatically by keeper after timeout
    :deadline-breached}) ; deadline passed with no keeper action

(def resolution-participation-values
  "How many eligible parties participated in the resolution."
  #{:full-participation       ; all parties responded
    :partial-participation    ; at least one party responded
    :no-participation         ; neither party engaged (timeout)
    :asymmetric-participation}) ; only one side responded

(def resolution-escalation-values
  "How many escalation rounds occurred."
  #{:none           ; resolved at level 0 (initial resolver)
    :single-step    ; one challenge / escalation
    :multi-step     ; two escalations
    :max-escalation ; reached the protocol's maximum level
    :recursive})    ; escalation chain looped or was attempted beyond max

(def resolution-economic-values
  "Economic character of the resolution outcome."
  #{:profitable         ; at least one party gained relative to no-dispute
    :loss-making        ; at least one party lost relative to escrow amount
    :break-even         ; parties recover approximately their escrow contributions
    :capital-locked     ; funds unable to be released (liveness failure)
    :capital-efficient  ; resolved with minimal bond / stake expenditure
    :over-slashed       ; resolver lost more stake than warranted
    :under-slashed})    ; resolver escaped proportionate penalty

(def resolution-failure-values
  "Class of resolution failure, if any."
  #{:none                  ; resolution succeeded
    :liveness-failure      ; dispute was not resolved within deadline
    :deadlock              ; no party can act to break the stalemate
    :infinite-appeal-loop  ; appeals were cycled without convergence
    :inconsistent-state    ; world state diverged from expected
    :partial-execution     ; resolution partially applied
    :economic-exploit})    ; resolution succeeded but adversary extracted value

(def resolution-integrity-values
  "Accounting integrity of the resolution."
  #{:fully-reconciled    ; all accounting balances match expected
    :accounting-mismatch ; held/released/refunded do not sum correctly
    :missing-effects     ; some expected accounting effects did not apply
    :double-counted      ; effects were applied more than once
    :leakage})           ; value exited the protocol without accounting entry

;; ===========================================================================
;; B. Classifier Functions
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; B1. Actor classifiers
;; RESERVED — no production callers. Intended for per-agent trace annotation.
;; Wire-up point: enrich :agent map in process-step trace entry to include
;; :actor/type and :actor/role derived from these classifiers.
;; ---------------------------------------------------------------------------

(defn classify-actor-type
  "Infer the structural :actor/type keyword from an agent map.
   Agent maps have :role 'resolver' | 'governance' | 'keeper' | ...
   Structural type is derived from the declared role, defaulting to :observer.
   Falls back to :type for backward compatibility."
  [agent-map]
  (let [role (or (:role agent-map) (:type agent-map) "observer")]
    (case role
      "resolver"   :resolver
      "governance" :governance
      "keeper"     :keeper
      "oracle"     :oracle
      "challenger" :challenger
      (do
        (println "WARNING: Unrecognized actor role:" role)
        :observer))))

(defn classify-actor-role
  "Derive the behavioural :actor/role keyword from an agent map.
   Declared :strategy or :behavior field is the primary signal.
   Falls back to :role or :type for backward compatibility."
  [agent-map]
  (case (or (:strategy agent-map) (:behavior agent-map) (:role agent-map) (:type agent-map) "honest")
    "honest"      :honest
    "rational"    :rational
    "malicious"   :malicious
    "lazy"        :lazy
    "coordinated" :coordinated
    "sybil"       :sybil
    :honest))

;; ---------------------------------------------------------------------------
;; B2. Adversary classifier
;; RESERVED — no production callers. Intended for adversary scenario tagging.
;; Wire-up point: add :adversary map to replay result in replay-with-protocol,
;; or call in sim/fixtures.clj fixture runner to annotate adversarial suites.
;; ---------------------------------------------------------------------------

(defn- sid-contains-segment?
  [sid segment]
  (some #{segment} (str/split sid #"-")))

(defn classify-adversary
  "Return an adversary classification map from a scenario map.
   Looks for :adversary/type and :adversary/traits on the scenario, or
   infers from the scenario-id string as a fallback."
  [scenario]
  (let [explicit-type   (:adversary/type scenario)
        explicit-traits (or (:adversary/traits scenario) #{})
        sid             (or (:scenario-id scenario) "")]
    (if explicit-type
      {:adversary/type   explicit-type
       :adversary/traits explicit-traits}
      ;; Fallback: infer from scenario-id segments
      (cond
        (sid-contains-segment? sid "profit-maximizer")
        {:adversary/type   :profit-maximizer
         :adversary/traits #{:multi-step :capital-efficient}}
        (sid-contains-segment? sid "forking-strategist")
        {:adversary/type   :forking-strategist
         :adversary/traits #{:multi-step :adaptive}}
        (sid-contains-segment? sid "ring-attack")
        {:adversary/type   :colluder
         :adversary/traits #{:multi-step :coordinated}}
        :else nil))))

;; ---------------------------------------------------------------------------
;; B3. Transition classifier
;; ---------------------------------------------------------------------------

(def transition-type-map
  "Data-driven mapping from action name to transition type keyword.
   Keys use kebab-case (after canonical-action normalization)."
  {"create-escrow"               :transition/creation
   "register-stake"              :transition/creation
   "set-resolver-capacity"       :transition/creation
   "register-resolver-bond"      :transition/creation
   "register-senior-bond"        :transition/creation
   "delegate-to-senior"          :transition/creation
   "raise-dispute"               :transition/state-change
   "challenge-resolution"        :transition/escalation
   "submit-evidence"             :transition/escalation
   "escalate-dispute"            :transition/escalation
   "execute-resolution"          :transition/resolution
   "execute-pending-settlement"  :transition/resolution
   "propose-fraud-slash"         :transition/governance
   "appeal-slash"               :transition/governance
   "resolve-appeal"             :transition/governance
   "execute-fraud-slash"        :transition/economic
   "distribute-slash"           :transition/economic
   "release"                    :transition/economic
   "sender-cancel"              :transition/state-change
   "recipient-cancel"           :transition/state-change
   "automate-timed-actions"     :transition/maintenance
   "auto-cancel-disputed"       :transition/timeout})

(defn transition-type
  "Map a protocol action string to its :transition/type keyword.
   Accepts both kebab-case (create-escrow) and snake_case (create_escrow).
   Returns :transition/unknown for unrecognized actions."
  [action]
  (get transition-type-map action
       (get transition-type-map (str/replace action "_" "-")
            :transition/unknown)))

;; ---------------------------------------------------------------------------
;; B4. Scenario classifier
;; Active — called from io/trace_export.clj to populate fixture :metadata block.
;; ---------------------------------------------------------------------------

(defn classify-scenario
  "Derive the :scenario/type keyword for a scenario map.
   Uses explicit :scenario/type if present; otherwise infers from scenario-id
   and agent composition."
  [scenario]
  (or (:scenario/type scenario)
      (let [sid (or (:scenario-id scenario) "")]
        (cond
          (.contains sid "profit-maximizer") :adversarial
          (.contains sid "forking-strategist") :adversarial
          (.contains sid "ring-attack")        :adversarial
          (.contains sid "depletion-cascade")  :stress
          (.contains sid "dr3-bond")           :stress
          (.contains sid "dr3-senior")         :stress
          (.contains sid "dr3-freeze")         :stress
          (.contains sid "dr3-reversal")       :stress
          (.contains sid "edge-case")          :edge-case
          (.contains sid "rejected")           :edge-case
          (.contains sid "unauthorized")       :edge-case
          (.contains sid "blocked")            :edge-case
          :else                                :baseline))))

;; ---------------------------------------------------------------------------
;; B6. Outcome classifier
;; Active — called from io/trace_export.clj to populate fixture :metadata block.
;; ---------------------------------------------------------------------------

(defn classify-outcome
  "Derive the :outcome/type keyword from a replay result map.
   Cross-references outcome, halt-reason, metrics, and expected-fail?."
  [result scenario]
  (let [outcome    (:outcome result)
        halt       (:halt-reason result)
        expected   (:expected-fail? scenario false)
        violations (get-in result [:metrics :invariant-violations] 0)]
    (cond
      (and expected (= :fail outcome))   :expected-violation
      (and (= :pass outcome) (zero? violations)) :normal-completion
      (= halt :invariant-violation)      :invariant-failure
      (#{:open-entities-at-end :open-disputes-at-end} halt) :liveness-failure
      (= outcome :fail)                  :cascade-failure
      :else                              :normal-completion)))

;; ===========================================================================
;; C. Resolution Semantics
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; C1. Legacy helpers (retained for backwards compatibility)
;; ---------------------------------------------------------------------------

(def resolution-path-map
  {"execute-resolution"          :resolution/standard
   "execute-pending-settlement"  :resolution/delayed
   "auto-cancel-disputed"        :resolution/timeout})

(defn resolution-path
  "Map an action name to its resolution path type.
   Accepts both kebab-case and snake_case. Returns :resolution/none by default."
  [action]
  (get resolution-path-map action
       (get resolution-path-map (str/replace action "_" "-")
            :resolution/none)))

;; RESERVED — no production callers. Superseded by classify-resolution (C3)
;; which returns the full resolution taxonomy. Retained as vocabulary reference.
(defn resolution-outcome [world workflow-id]
  (let [state (t/escrow-state world workflow-id)]
    (case state
      :released :resolution/release
      :refunded :resolution/refund
      :resolved :resolution/settled
      :resolution/pending)))

;; ---------------------------------------------------------------------------
;; C2. CDRS v0.1 canonical buckets (legacy — retained for compatibility)
;; ---------------------------------------------------------------------------

(defn- clean-id [id]
  (if (string? id)
    (try (Integer/parseInt (clojure.string/replace id #"^wf" ""))
         (catch Exception _ id))
    id))

(defn state-bucket [world workflow-id]
  (let [id      (clean-id workflow-id)
        state   (or (get-in world [:escrow-transfers id :escrow-state])
                    (get-in world [:live-states id])
                    :none)
        pending (or (get-in world [:pending-settlements id])
                    (when (pos? (get world :pending-count 0))
                      {:exists true})
                    {:exists false})]
    (cond
      (= :none state)      "IDLE"
      (= :pending state)   "ACTIVE"
      (and (= :disputed state) (:exists pending)) "RECONCILING"
      (= :disputed state)  "CHALLENGED"
      (contains? t/terminal-states state) "SETTLED"
      :else "IDLE")))

(defn resolution-semantics
  "Legacy: returns a CDRS v0.1 string-keyed map for a workflow.
   Prefer classify-resolution for new code."
  [world workflow-id]
  (let [id      (clean-id workflow-id)
        state   (or (get-in world [:escrow-transfers id :escrow-state])
                    (get-in world [:live-states id])
                    :none)
        pending (or (get-in world [:pending-settlements id])
                    {:exists false})]
    (case state
      :released {:outcome "RELEASE" :finality "FINAL" :integrity "FULLY_RECONCILED"}
      :refunded {:outcome "REFUND"  :finality "FINAL" :integrity "FULLY_RECONCILED"}
      :resolved {:outcome "SETTLED" :finality "FINAL" :integrity "FULLY_RECONCILED"}
      :disputed (if (:exists pending)
                  {:outcome "NO_OP" :finality "APPEALABLE" :integrity "MISSING_EFFECTS"}
                  {:outcome "NO_OP" :finality "STALLED"    :integrity "ACCOUNTING_MISMATCH"})
      {:outcome "NO_OP" :finality "STALLED" :integrity "LEAKAGE"})))

;; ---------------------------------------------------------------------------
;; C3. Full resolution taxonomy classifier
;; RESERVED — no production callers. Supersedes resolution-semantics (C2,
;; called from io/trace_export.clj) but returns keyword-namespaced values
;; (e.g. :resolution/outcome :released) where resolution-semantics returns
;; CDRS string maps (e.g. {:outcome "RELEASE" ...}).
;; Wire-up point: replace resolution-semantics in trace_export.clj:180 once
;; the Forge trace schema is updated to accept namespaced keyword values.
;; ---------------------------------------------------------------------------

(defn classify-resolution
  "Return a fully-typed resolution map for a workflow in the given world.

   Derives all resolution/* taxonomy values from current world state.
   The result is a keyword-keyed map with entries from the resolution-*-values
   sets defined in Section A8.

   This supersedes resolution-semantics for new analysis code."
  [world workflow-id]
  (let [id       (clean-id workflow-id)
        state    (or (get-in world [:escrow-transfers id :escrow-state])
                     (get-in world [:live-states id])
                     :none)
        pending  (get-in world [:pending-settlements id])
        level    (get-in world [:dispute-levels id] 0)]
    {:resolution/outcome
     (case state
       :released :released
       :refunded :refunded
       :resolved :settled
       :disputed :no-op
       :no-op)

     :resolution/finality
     (cond
       (contains? t/terminal-states state) :final
       (and (= :disputed state) pending)                  :appealable
       (= :disputed state)                                :stalled
       :else                                              :stalled)

     :resolution/escalation
     (cond
       (= level 0) :none
       (= level 1) :single-step
       (= level 2) :multi-step
       :else        :max-escalation)

     :resolution/participation
     (cond
       (contains? t/terminal-states state) :full-participation
       (and (= :disputed state) pending)                  :partial-participation
       (= :disputed state)                                :no-participation
       :else                                              :no-participation)

     :resolution/timing
     (cond
       (contains? t/terminal-states state) :within-deadline
       (= :disputed state)                                :delayed
       :else                                              :deadline-breached)

     :resolution/integrity
     (cond
       (contains? t/terminal-states state) :fully-reconciled
       (and (= :disputed state) pending)                  :missing-effects
       (= :disputed state)                                :accounting-mismatch
       :else                                              :leakage)}))

;; ---------------------------------------------------------------------------
;; C4. Issue / failure classifier
;; Active — called from io/trace_score.clj:81 to set :issue/type on scored results.
;; ---------------------------------------------------------------------------

(defn classify-issue [result]
  (let [metrics        (:metrics result {})
        liveness-fail? (pos? (get-in result [:score-components :liveness-failure] 0))
        invariant-fail? (pos? (:invariant-violations metrics 0))]
    (cond
      invariant-fail? :issue/invariant-violation
      liveness-fail?  :issue/liveness-failure
      :else           :issue/none)))
