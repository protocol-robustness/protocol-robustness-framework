(ns resolver-sim.protocols.sew.trace-metadata
  "SEW protocol trace metadata — classifiers and mappings.

   Generic vocabulary (actor types, transition categories, resolution values,
   etc.) lives in resolver-sim.trace-metadata.  This namespace contains:

   - Compatibility aliases for vocabulary sets that were previously here
   - SEW-specific action and invariant mappings
   - Classifier functions that map SEW world state into PRF vocabulary"
  (:require [resolver-sim.trace-metadata :as core]
            [resolver-sim.protocols.sew.types :as t]
            [clojure.string :as str]))

;; ===========================================================================
;; A. Vocabulary — compatibility aliases (delegate to core)
;; ===========================================================================
;;
;; These were historically defined directly in this namespace.  They are now
;; maintained in resolver-sim.trace-metadata and re-exported here for
;; backward compatibility.  New consumers should require the core namespace
;; directly.

(def actor-types                     core/actor-types)
(def actor-roles                     core/actor-roles)
(def adversary-types                 core/adversary-types)
(def adversary-traits                core/adversary-traits)
(def transition-types                core/transition-types)
(def effect-types                    core/effect-types)
(def scenario-types                  core/scenario-types)
(def outcome-types                   core/outcome-types)
(def resolution-quality-values       core/resolution-quality-values)
(def resolution-finality-values      core/resolution-finality-values)
(def resolution-timing-values        core/resolution-timing-values)
(def resolution-participation-values core/resolution-participation-values)
(def resolution-escalation-values    core/resolution-escalation-values)
(def resolution-economic-values      core/resolution-economic-values)
(def resolution-failure-values       core/resolution-failure-values)
(def resolution-integrity-values     core/resolution-integrity-values)
(def invariant-category-types        core/invariant-category-types)

;; ---------------------------------------------------------------------------
;; A3. SEW-specific strategic actions
;; ---------------------------------------------------------------------------

(def strategic-actions
  "Actions that represent strategic decision nodes in replay traces.
   Used by subgame counterfactual analysis and projection replay."
  #{"create-escrow" "raise-dispute" "escalate-dispute" "execute-resolution"
    "sender-cancel" "recipient-cancel"})

;; ---------------------------------------------------------------------------
;; A5. SEW invariant-ID-to-category mapping
;; ---------------------------------------------------------------------------

(def invariant-categories
  "Mapping of SEW invariant keyword → PRF invariant category.
   Every value must be a member of core/invariant-category-types."
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

;; Assert that every mapped category is a valid PRF category
(let [bad (remove (set core/invariant-category-types) (vals invariant-categories))]
  (when (seq bad)
    (throw (ex-info (str "invariant-categories contains unrecognized categories: " bad)
                    {:bad-categories bad}))))

;; ===========================================================================
;; B. Classifier Functions
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; B1. Actor classifiers
;; ---------------------------------------------------------------------------

(defn classify-actor-type
  "Infer the structural :actor/type keyword from an agent map."
  [agent-map]
  (let [role (or (:role agent-map) (:type agent-map) "observer")]
    (case role
      "resolver"   :resolver
      "governance" :governance
      "keeper"     :keeper
      "oracle"     :oracle
      "challenger" :challenger
      :observer)))

(defn classify-actor-role
  "Derive the behavioural :actor/role keyword from an agent map."
  [agent-map]
  (case (or (:strategy agent-map) (:behavior agent-map)
            (:role agent-map) (:type agent-map) "honest")
    "honest"      :honest
    "rational"    :rational
    "malicious"   :malicious
    "lazy"        :lazy
    "coordinated" :coordinated
    "sybil"       :sybil
    :honest))

;; ---------------------------------------------------------------------------
;; B2. Adversary classifier
;; ---------------------------------------------------------------------------

(defn- sid-contains-segment? [sid segment]
  (boolean (re-find (re-pattern (str "(?<=-|^)" segment "(?=-|$)")) sid)))

(defn classify-adversary
  "Return an adversary classification map from a scenario map."
  [scenario]
  (let [explicit-type   (:adversary/type scenario)
        explicit-traits (or (:adversary/traits scenario) #{})
        sid             (or (:scenario-id scenario) "")]
    (if explicit-type
      {:adversary/type   explicit-type
       :adversary/traits explicit-traits}
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
  "Data-driven mapping from SEW action name to PRF :transition/type keyword."
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
   "appeal-slash"                :transition/governance
   "resolve-appeal"              :transition/governance
   "execute-fraud-slash"         :transition/economic
   "distribute-slash"            :transition/economic
   "release"                     :transition/economic
   "sender-cancel"               :transition/state-change
   "recipient-cancel"            :transition/state-change
   "automate-timed-actions"      :transition/maintenance
   "auto-cancel-disputed"        :transition/timeout})

(defn transition-type
  "Map a SEW action string to its :transition/type keyword.
   Accepts kebab-case (create-escrow) and snake_case (create_escrow).
   Returns :transition/unknown for unrecognized actions."
  [action]
  (get transition-type-map action
       (get transition-type-map (str/replace action "_" "-")
            :transition/unknown)))

;; ---------------------------------------------------------------------------
;; B4. Scenario classifier
;; ---------------------------------------------------------------------------

(defn classify-scenario
  "Derive the :scenario/type keyword for a SEW scenario map."
  [scenario]
  (or (:scenario/type scenario)
      (let [sid (or (:scenario-id scenario) "")]
        (cond
          (.contains sid "profit-maximizer")    :adversarial
          (.contains sid "forking-strategist")  :adversarial
          (.contains sid "ring-attack")         :adversarial
          (.contains sid "depletion-cascade")   :stress
          (.contains sid "dr3-bond")            :stress
          (.contains sid "dr3-senior")          :stress
          (.contains sid "dr3-freeze")          :stress
          (.contains sid "dr3-reversal")        :stress
          (.contains sid "edge-case")           :edge-case
          (.contains sid "rejected")            :edge-case
          (.contains sid "unauthorized")        :edge-case
          (.contains sid "blocked")             :edge-case
          :else                                 :baseline))))

;; ---------------------------------------------------------------------------
;; B6. Outcome classifier
;; ---------------------------------------------------------------------------

(defn classify-outcome
  "Derive the :outcome/type keyword from a SEW replay result map."
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
;; C1. Legacy helpers
;; ---------------------------------------------------------------------------

(def resolution-path-map
  {"execute-resolution"          :resolution/standard
   "execute-pending-settlement"  :resolution/delayed
   "auto-cancel-disputed"        :resolution/timeout})

(defn resolution-path
  "Map an action name to its resolution path type."
  [action]
  (get resolution-path-map action
       (get resolution-path-map (str/replace action "_" "-")
            :resolution/none)))

(defn resolution-outcome
  "Legacy — returns a :resolution/* keyword for a workflow in the given world."
  [world workflow-id]
  (let [state (t/escrow-state world workflow-id)]
    (case state
      :released :resolution/release
      :refunded :resolution/refund
      :resolved :resolution/settled
      :resolution/pending)))

;; ---------------------------------------------------------------------------
;; C2. CDRS v0.1 canonical buckets (legacy)
;; ---------------------------------------------------------------------------

(defn- clean-id [id]
  (if (string? id)
    (try (Integer/parseInt (str/replace id #"^wf" ""))
         (catch Exception _ id))
    id))

(defn state-bucket
  "Map a workflow's world state to a CDRS v0.1 bucket string."
  [world workflow-id]
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
;; ---------------------------------------------------------------------------

(defn classify-resolution
  "Return a fully-typed resolution map for a workflow in the given world.
   All :resolution/* values are members of the core resolution-*-values sets."
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
;; ---------------------------------------------------------------------------

(defn classify-issue
  "Classify a replay result into an :issue/* keyword."
  [result]
  (let [metrics        (:metrics result {})
        liveness-fail? (pos? (get-in result [:score-components :liveness-failure] 0))
        invariant-fail? (pos? (:invariant-violations metrics 0))]
    (cond
      invariant-fail? :issue/invariant-violation
      liveness-fail?  :issue/liveness-failure
      :else           :issue/none)))
