(ns resolver-sim.definitions.solidity-shadow-registry
  "Machine-readable registry tracking which simulation code shadows which
   Solidity contract, with documented known differences.

   Each entry maps a simulation module/function to its Solidity counterpart
   and records intentional differences.  The registry is informational —
   it does not block startup.

   Query API:
   - lookup-by-simulation  — find entries by simulation namespace
   - lookup-by-solidity    — find entries by Solidity contract file
   - all-differences       — return all recorded differences
   - check-shadow-coverage — detect simulation namespaces without entries"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ──────────────────────────────────────────────────────────────────────────
;; Schema
;; ──────────────────────────────────────────────────────────────────────────

(def schema-version
  "Current schema version for solidity-shadow entries."
  "1.0")

(def shadow-entry-keys
  "Required keys for every shadow entry."
  #{:shadow/id :simulation/ns :simulation/role
    :solidity/contract :solidity/function
    :solidity/status :protocol/status
    :description})

(def semantic-coverage-statuses
  "Closed vocabulary for semantic coverage rows."
  #{:semantic/solidity-coverage-absent
    :semantic/proof-verified-full-coverage})

(def semantic-coverage-reasons
  "Closed vocabulary explaining the semantic coverage status."
  #{:reason/solidity-coverage-absent
    :reason/proof-verified-full-coverage})

(def semantic-coverage-table
  "Machine-readable semantic coverage claims for registry domains.

   A row is deliberately explicit: absent Solidity coverage is not a failed
   proof, and proof-verified full coverage is not inferred from an entry map."
  [{:semantic/id :solidity-shadow/semantic-coverage
    :semantic/status :semantic/solidity-coverage-absent
    :semantic/reason :reason/solidity-coverage-absent
    :semantic/solidity-covered? false
    :semantic/proof-verified? false}
   {:semantic/id :pro-rata/allocation
    :semantic/status :semantic/proof-verified-full-coverage
    :semantic/reason :reason/proof-verified-full-coverage
    :semantic/solidity-covered? true
    :semantic/proof-verified? true}])

(def semantic-shadow-rows
  "Semantic boundaries which are explicitly represented in the shadow registry."
  [{:semantic/id :pro-rata/protected-lineage
    :semantic/status :semantic/solidity-coverage-absent
    :semantic/reason :reason/solidity-coverage-absent
    :semantic/solidity-covered? false
    :semantic/proof-verified? false}])

(defn valid-semantic-coverage-row?
  "Return true when a semantic coverage row uses the closed vocabulary and
   its flags agree with its status."
  [row]
  (let [id (:semantic/id row)
        status (:semantic/status row)
        reason (:semantic/reason row)
        solidity-covered? (:semantic/solidity-covered? row)
        proof-verified? (:semantic/proof-verified? row)]
    (and (keyword? id)
         (contains? semantic-coverage-statuses status)
         (contains? semantic-coverage-reasons reason)
         (boolean? solidity-covered?)
         (boolean? proof-verified?)
         (case status
           :semantic/solidity-coverage-absent
           (and (= reason :reason/solidity-coverage-absent)
                (not solidity-covered?)
                (not proof-verified?))
           :semantic/proof-verified-full-coverage
           (and (= reason :reason/proof-verified-full-coverage)
                solidity-covered?
                proof-verified?)))))

(defn semantic-coverage
  "Return the semantic coverage row identified by id, or nil."
  [id]
  (some #(when (= id (:semantic/id %)) %)
        (concat semantic-coverage-table semantic-shadow-rows)))

(defn valid-semantic-shadow-row?
  "Return true when a semantic shadow row conforms to the coverage vocabulary."
  [row]
  (valid-semantic-coverage-row? row))

(defn validate-semantic-shadow-rows
  "Return invalid semantic shadow rows, preserving their declaration order."
  ([] (validate-semantic-shadow-rows semantic-shadow-rows))
  ([rows] (vec (remove valid-semantic-shadow-row? rows))))

;; ──────────────────────────────────────────────────────────────────────────
;; Registry entries
;; ──────────────────────────────────────────────────────────────────────────

(def solidity-shadow-entries
  "SOLIDITY_SHADOW_REGISTRY_SPEC_V1 entries."
  [;; ══════════════════════════════════════════════════════════════════
         ;; Escrow lifecycle — BaseEscrow.sol
         ;; ══════════════════════════════════════════════════════════════════
   {:shadow/id           :escrow-create
    :simulation/ns       "resolver-sim.sim.adversarial.reorg-check"
    :simulation/role     :action
    :solidity/contract   "BaseEscrow.sol"
    :solidity/function   "createEscrow"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Escrow creation with deposit and participant assignment"
    :differences         ["Simulation combines deposit + escrow creation in a single step"
                          "Simulation does not enforce Nonce-based ID derivation (keccak256)"]
    :test/link           "test/foundry/core/BaseEscrowComprehensive.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :escrow-sender-cancel
    :simulation/ns       "resolver-sim.contract-model.replay.execution"
    :simulation/role     :action
    :solidity/contract   "BaseEscrow.sol"
    :solidity/function   "senderCancel"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Sender-initiated escrow cancellation"
    :differences         ["Simulation assumes mutual consent — no unilateral sender cancel pathway"]
    :test/link           "test/foundry/core/BaseEscrowComprehensive.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :escrow-recipient-cancel
    :simulation/ns       "resolver-sim.contract-model.replay.execution"
    :simulation/role     :action
    :solidity/contract   "BaseEscrow.sol"
    :solidity/function   "recipientCancel"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Recipient-initiated escrow cancellation"
    :differences         ["Simulation cancels are symmetric — Solidity distinguishes sender vs recipient pathways"]
    :test/link           "test/foundry/core/BaseEscrowComprehensive.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :escrow-auto-cancel
    :simulation/ns       "resolver-sim.contract-model.replay.execution"
    :simulation/role     :action
    :solidity/contract   "BaseEscrow.sol"
    :solidity/function   "autoCancelDisputedEscrow"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Automatic cancellation of disputed escrows by timeout"
    :differences         []
    :test/link           "test/foundry/core/BaseEscrowComprehensive.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

         ;; ══════════════════════════════════════════════════════════════════
         ;; Dispute lifecycle — EscrowStateMachine, resolver contracts
         ;; ══════════════════════════════════════════════════════════════════
   {:shadow/id           :dispute-raise
    :simulation/ns       "resolver-sim.contract-model.replay.execution"
    :simulation/role     :action
    :solidity/contract   "EscrowStateMachine.sol"
    :solidity/function   "raiseDispute"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Raising a dispute on an escrow, transitioning to DISPUTED state"
    :differences         []
    :test/link           "test/foundry/core/EscrowStateMachine.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :dispute-resolve
    :simulation/ns       "resolver-sim.contract-model.replay.execution"
    :simulation/role     :action
    :solidity/contract   "ResolverSlashingModuleV1.sol"
    :solidity/function   "resolveDispute"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Resolution of a dispute by a resolver, with optional slashing"
    :differences         ["Simulation models dispute resolution at higher granularity — does not replicate Solidity call sequencing"]
    :test/link           "test/foundry/invariants/ResolverInvariants.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :settlement-execute
    :simulation/ns       "resolver-sim.contract-model.replay.execution"
    :simulation/role     :action
    :solidity/contract   "SettlementOps.sol"
    :solidity/function   "executePendingSettlement"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Execution of a pending settlement after the appeal window closes"
    :differences         []
    :test/link           "test/foundry/core/AppealWindowEnforcement.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :appeal-raise
    :simulation/ns       "resolver-sim.contract-model.replay.execution"
    :simulation/role     :action
    :solidity/contract   "ResolverSlashingModuleV1.sol"
    :solidity/function   "appeal"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Appeal of a dispute resolution to the next escalation level"
    :differences         []
    :test/link           "test/foundry/core/AppealWindowEnforcement.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

         ;; ══════════════════════════════════════════════════════════════════
         ;; Cancellation strategies — ICancellationStrategy implementations
         ;; ══════════════════════════════════════════════════════════════════
   {:shadow/id           :cancellation-strategy-default
    :simulation/ns       "resolver-sim.contract-model.replay.execution"
    :simulation/role     :action
    :solidity/contract   "DefaultCancellationStrategy.sol"
    :solidity/function   "canCancel, onCancelAttempt"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Mutual-consent cancellation strategy"
    :differences         ["Simulation does not model separate strategy contracts — cancellation is a single action"]
    :test/link           "test/foundry/modules/DefaultCancellationStrategy.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

         ;; ══════════════════════════════════════════════════════════════════
         ;; Invariants — Foundry forge invariants
         ;; ══════════════════════════════════════════════════════════════════
   {:shadow/id           :invariant-solvency
    :simulation/ns       "resolver-sim.protocols.sew.invariants"
    :simulation/role     :invariant
    :solidity/contract   "StateInvariants.t.sol"
    :solidity/function   "invariant_solvency"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Vault balance must cover principal + fees"
    :differences         []
    :test/link           "test/foundry/invariants/StateInvariants.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :invariant-terminal-states
    :simulation/ns       "resolver-sim.protocols.sew.invariants"
    :simulation/role     :invariant
    :solidity/contract   "StateInvariants.t.sol"
    :solidity/function   "invariant_terminal_states_absorbing"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Released/refunded/resolved escrows are absorbing"
    :differences         []
    :test/link           "test/foundry/invariants/StateInvariants.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :invariant-fees-monotone
    :simulation/ns       "resolver-sim.protocols.sew.invariants"
    :simulation/role     :invariant
    :solidity/contract   "StateInvariants.t.sol"
    :solidity/function   "invariant_fees_monotone"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Fees do not decrease between non-withdraw actions"
    :differences         []
    :test/link           "test/foundry/invariants/StateInvariants.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :invariant-appeal-window
    :simulation/ns       "resolver-sim.protocols.sew.invariants"
    :simulation/role     :invariant
    :solidity/contract   "ResolverInvariants.t.sol"
    :solidity/function   "invariant_appeal_window_enforced"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "executePendingSettlement must fail pre-deadline"
    :differences         []
    :test/link           "test/foundry/invariants/ResolverInvariants.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

         ;; ══════════════════════════════════════════════════════════════════
         ;; Proposed features — not yet in Solidity
         ;; ══════════════════════════════════════════════════════════════════
   {:shadow/id           :identity-guard
    :simulation/ns       "resolver-sim.protocols.sew.lifecycle"
    :simulation/role     :action
    :solidity/contract   "IdentityGuard.sol"
    :solidity/function   "N/A — proposed"
    :solidity/status     :solidity/not-implemented
    :protocol/status     :protocol/proposed
    :description         "Identity confusion fix (S55): require(escrows[transferId].state == None)"
    :differences         ["Entirely simulation-only — Solidity contract does not exist yet"
                          "Keccak256 ID derivation modeled in Clojure via hash/canonical"]
    :test/link           "N/A"
    :trace-equivalence   "N/A"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :storage-migration-v2
    :simulation/ns       "resolver-sim.contract-model.idempotency"
    :simulation/role     :projection
    :solidity/contract   "StorageMigration.sol"
    :solidity/function   "N/A — proposed"
    :solidity/status     :solidity/not-implemented
    :protocol/status     :protocol/proposed
    :description         "V1→V2 storage layout migration from uint256 monotonic to bytes32 semantic IDs"
    :differences         ["Entirely simulation-only — Solidity contract does not exist yet"
                          "Simulation models the migration algebra without actual storage layout"]
    :test/link           "N/A"
    :trace-equivalence   "N/A"
    :last-reviewed       "2026-06-27"}

          ;; ══════════════════════════════════════════════════════════════════
          ;; Bug fixes applied in sessions 10–14
          ;; ══════════════════════════════════════════════════════════════════

   {:shadow/id           :auto-cancel-disputed-griefing
    :simulation/ns       "resolver-sim.protocols.sew.state-machine"
    :simulation/role     :predicate
    :solidity/contract   "SettlementOps.sol"
    :solidity/function   "computeTimedActions"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "auto-cancel-time fires on DISPUTED escrows (griefing protection)"
    :differences         ["Simulation was added first (auto-cancel-due-on-disputed?), then ported to Solidity as ACTION_AUTO_CANCEL_DISPUTED (Session 10)"]
    :test/link           "test/foundry/core/EscrowStateMachine.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :state-management-terminal-guards
    :simulation/ns       "resolver-sim.protocols.sew.state-machine"
    :simulation/role     :guard
    :solidity/contract   "StateManagementLibrary.sol"
    :solidity/function   "transitionToReleased, transitionToRefunded, transitionToResolved"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Terminal state transition guards prevent silent state corruption"
    :differences         ["Simulation uses ex-info (programming error) — Solidity uses revert AlreadyTerminal"]
    :test/link           "test/foundry/core/EscrowStateMachine.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :dispute-timestamp-cleanup
    :simulation/ns       "resolver-sim.protocols.sew.lifecycle"
    :simulation/role     :cleanup
    :solidity/contract   "BaseEscrow.sol"
    :solidity/function   "_cancelAndRefund, _releaseEscrowTransfer"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "disputeRaisedTimestamp cleaned up on ALL terminal paths"
    :differences         ["Simulation cleans up in finalize (lifecycle.clj) — Solidity cleans up in _cancelAndRefund and _releaseEscrowTransfer"]
    :test/link           "test/foundry/core/EscrowStateMachine.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :yield-unwind-state-cleanup
    :simulation/ns       "N/A — Solidity only"
    :simulation/role     :cleanup
    :solidity/contract   "BaseEscrow.sol"
    :solidity/function   "_handleYieldModuleUnwind"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Yield module state deleted after successful unwind; returns amount not yieldPrincipal on double-failure"
    :differences         ["Simulation yield model uses yield-policy (different architecture) — no direct equivalent"]
    :test/link           "test/foundry/core/BaseEscrowComprehensive.t.sol"
    :trace-equivalence   "N/A"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :finalize-dispute-in-module-coverage
    :simulation/ns       "resolver-sim.protocols.sew.resolution"
    :simulation/role     :cleanup
    :solidity/contract   "BaseEscrow.sol"
    :solidity/function   "_executeResolution, resolveDisputeByTimeout, automateTimedActions, _closeDisputeByMutualAgreement"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "_finalizeDisputeInModule called from all terminal dispute paths"
    :differences         ["Simulation uses t/decrement-resolver-capacity directly in finalize wrapper"]
    :test/link           "test/foundry/core/SimulationHardening.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :governance-sandwich-mitigation
    :simulation/ns       "resolver-sim.protocols.sew.state-machine"
    :simulation/role     :guard
    :solidity/contract   "BaseEscrow.sol"
    :solidity/function   "_isAuthorizedDisputeResolver"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Resolver captured at raiseDispute is sole authority — prevents governance sandwich (F3)"
    :differences         []
    :test/link           "test/foundry/core/SimulationHardening.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :auto-time-mutual-exclusion
    :simulation/ns       "N/A — Solidity only"
    :simulation/role     :validation
    :solidity/contract   "BaseEscrow.sol"
    :solidity/function   "_applyEscrowSettings"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Independent mutual-exclusion check for autoReleaseTime and autoCancelTime"
    :differences         ["Simulation delegates mutual-exclusion to shared protocol-params; Solidity now has BothAutoTimesSet guard"]
    :test/link           "test/foundry/core/PerEscrowSettings.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :sel-finalize-dispute-selector
    :simulation/ns       "N/A — Solidity only"
    :simulation/role     :fix
    :solidity/contract   "BaseEscrow.sol, EscrowManagementLibrary.sol"
    :solidity/function   "SEL_FINALIZE_DISPUTE, EscrowManagementLibrary.finalizeDisputeInModule"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "Fixed incorrect selector (\"finalizeDispute(uint256)\" → \"finalizeDispute(uint256,address)\")"
    :differences         ["Simulation does not use selectors — uses Clojure symbols directly"]
    :test/link           "test/foundry/core/EscrowStateMachine.t.sol"
    :trace-equivalence   "N/A"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :accept-split-dispute-cleanup
    :simulation/ns       "N/A — Solidity only"
    :simulation/role     :cleanup
    :solidity/contract   "BaseEscrow.sol"
    :solidity/function   "acceptSplit"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "acceptSplit now cleans up disputeRaisedTimestamp (missing before)"
    :differences         ["Simulation does not model acceptSplit/proposeSplit — Solidity-only feature"]
    :test/link           "test/foundry/core/BaseEscrowComprehensive.t.sol"
    :trace-equivalence   "N/A"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :transition-to-disputed-guard
    :simulation/ns       "resolver-sim.protocols.sew.state-machine"
    :simulation/role     :guard
    :solidity/contract   "StateManagementLibrary.sol"
    :solidity/function   "transitionToDisputed"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "transitionToDisputed now reverts AlreadyTerminal for non-PENDING states"
    :differences         ["Simulation guards via :from #{:pending} in allowed-transitions graph"]
    :test/link           "test/foundry/core/EscrowStateMachine.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

          ;; ══════════════════════════════════════════════════════════════════
          ;; Cross-module tests — DRv3CrossModuleInvariants (Priority 2)
          ;; ══════════════════════════════════════════════════════════════════

   {:shadow/id           :cross-module-terminal-paths
    :simulation/ns       "resolver-sim.protocols.sew.resolution"
    :simulation/role     :integration
    :solidity/contract   "BaseEscrow.sol, DecentralizedResolutionModule.sol"
    :solidity/function   "_finalizeDisputeInModule, automateTimedActions, resolveDisputeByTimeout, executePendingSettlement"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "All terminal dispute paths converge to terminal state (REFUNDED/RELEASED)"
    :differences         []
    :test/link           "test/foundry/decentralized-resolution-module/DRv3CrossModuleInvariants.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}

          ;; ══════════════════════════════════════════════════════════════════
          ;; Priority 3 fixes — CEI, encoding, events
          ;; ══════════════════════════════════════════════════════════════════

   {:shadow/id           :escrowable-erc20-cei
    :simulation/ns       "N/A — Solidity only"
    :simulation/role     :fix
    :solidity/contract   "EscrowableERC20.sol"
    :solidity/function   "withdrawFees"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "totalFees zeroed before _transfer (CEI pattern fix)"
    :differences         []
    :test/link           "test/foundry/core/EscrowStateMachine.t.sol"
    :trace-equivalence   "N/A"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :escrow-data-encoding-alignment
    :simulation/ns       "N/A — Solidity only"
    :simulation/role     :fix
    :solidity/contract   "DisputeOps.sol"
    :solidity/function   "computeDisputeOpening, computeEscalation"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "DisputeOps now uses 5-element EscrowEncodingLibrary encoding (matching CreateOps)"
    :differences         []
    :test/link           "test/foundry/core/EscrowStateMachine.t.sol"
    :trace-equivalence   "N/A"
    :last-reviewed       "2026-06-27"}

   {:shadow/id           :finalize-dispute-event-emission
    :simulation/ns       "resolver-sim.protocols.sew.resolution"
    :simulation/role     :fix
    :solidity/contract   "BaseEscrow.sol"
    :solidity/function   "_finalizeDisputeInModule"
    :solidity/status     :solidity/implemented
    :protocol/status     :protocol/current
    :description         "OperationFailure event emitted when finalizeDispute or decrementResolverActiveDisputes calls fail"
    :differences         ["Simulation does not model low-level call failures — all failures are structured returns"]
    :test/link           "test/foundry/core/EscrowStateMachine.t.sol"
    :trace-equivalence   "cdrs-v0.2"
    :last-reviewed       "2026-06-27"}])

;; ──────────────────────────────────────────────────────────────────────────
;; Index
;; ──────────────────────────────────────────────────────────────────────────

(def ^:private entries-by-simulation-ns
  "Index: simulation namespace → list of entries."
  (delay
    (group-by :simulation/ns solidity-shadow-entries)))

(def ^:private entries-by-solidity-contract
  "Index: solidity contract name → list of entries."
  (delay
    (group-by (comp str/trim :solidity/contract) solidity-shadow-entries)))

;; ──────────────────────────────────────────────────────────────────────────
;; Query API
;; ──────────────────────────────────────────────────────────────────────────

(defn lookup-by-simulation
  "Return all shadow entries whose :simulation/ns matches ns-str.
   ns-str may be a fully qualified namespace string.
   Matches by prefix if ns-str ends with '*'."
  [ns-str]
  (let [entries @entries-by-simulation-ns]
    (if (str/ends-with? ns-str "*")
      (let [prefix (str/replace ns-str #"\*$" "")]
        (filter #(str/starts-with? (:simulation/ns %) prefix)
                (mapcat val entries)))
      (get entries ns-str []))))

(defn lookup-by-solidity
  "Return all shadow entries that shadow contract-name."
  [contract-name]
  (get @entries-by-solidity-contract (str/trim contract-name) []))

(defn all-differences
  "Return a seq of {:shadow/id :differences [...]} for entries with non-empty differences."
  []
  (keep (fn [e]
          (let [diffs (:differences e)]
            (when (seq diffs)
              {:shadow/id (:shadow/id e)
               :simulation/ns (:simulation/ns e)
               :solidity/contract (:solidity/contract e)
               :differences diffs})))
        solidity-shadow-entries))

(defn all-entries
  "Return all registry entries."
  []
  solidity-shadow-entries)

;; ──────────────────────────────────────────────────────────────────────────
;; Coverage check
;; ──────────────────────────────────────────────────────────────────────────

(def ^:private known-simulation-paths
  "Known simulation namespaces that have shadow entries.
   Used by check-shadow-coverage to detect unregistered code."
  (delay
    (set (map :simulation/ns solidity-shadow-entries))))

(defn- collect-simulation-namespaces
  "Scan src/ for .clj files and extract their namespace declarations."
  []
  (let [files (file-seq (io/file "src"))]
    (into #{}
          (comp
           (filter #(and (.isFile %)
                         (.endsWith (.getName %) ".clj")))
           (map (fn [f]
                  (with-open [r (clojure.java.io/reader f)]
                    (some (fn [line]
                            (when-let [m (re-find #"^\(ns\s+([^\s\)]+)" line)]
                              (second m)))
                          (line-seq r))))))
          files)))

(defn check-shadow-coverage
  "Identify simulation namespaces in src/ that lack a shadow entry.
   Returns a seq of {:namespace <name> :path <file-path>}.
   Ignores test and protocols_src namespaces by default."
  ([]
   (check-shadow-coverage {:include-protocols? false
                           :ignore-prefixes #{"resolver-sim.definitions"
                                              "resolver-sim.hash"
                                              "resolver-sim.logging"
                                              "resolver-sim.server"
                                              "resolver-sim.db"
                                              "resolver-sim.io"
                                              "resolver-sim.notebook-support"
                                              "resolver-sim.benchmark"
                                              "resolver-sim.scenario"
                                              "resolver-sim.generators"
                                              "resolver-sim.validation"
                                              "resolver-sim.cartesi"
                                              "resolver-sim.stochastic"
                                              "resolver-sim.economics"
                                              "resolver-sim.time"
                                              "resolver-sim.fixtures"}}))
  ([{:keys [include-protocols? ignore-prefixes]}]
   (let [registered @known-simulation-paths
         all-nses (collect-simulation-namespaces)
         ignored? (fn [ns]
                    (some #(str/starts-with? % (str/replace % #"\*" ""))
                          (keep (fn [p] (when (str/starts-with? ns p) p)) ignore-prefixes)))
         unregistered (remove (fn [ns]
                                (or (contains? registered ns)
                                    (ignored? ns)))
                              all-nses)]
     (mapv (fn [ns]
             (let [path (str/replace ns "." "/")
                   f (io/file "src" (str path ".clj"))]
               {:namespace ns
                :path (str "src/" path ".clj")
                :exists? (.exists f)}))
           (sort unregistered)))))

;; ──────────────────────────────────────────────────────────────────────────
;; Report
;; ──────────────────────────────────────────────────────────────────────────

(defn format-shadow-report
  "Return a human-readable string summarizing the shadow registry."
  []
  (let [entries solidity-shadow-entries
        total (count entries)
        implemented (count (filter #(= :solidity/implemented (:solidity/status %)) entries))
        not-implemented (count (filter #(= :solidity/not-implemented (:solidity/status %)) entries))
        with-diffs (count (all-differences))
        contracts (distinct (map :solidity/contract entries))
        contract-block (fn [c]
                         (let [ce (sort-by :shadow/id (filter #(= c (:solidity/contract %)) entries))]
                           (str "  " c "\n"
                                (str/join "\n" (for [e ce]
                                                 (let [diffs (:differences e)]
                                                   (str "    " (name (:shadow/id e))
                                                        (when (seq diffs) (str " (" (count diffs) " diff(s))"))
                                                        (when (= :solidity/not-implemented (:solidity/status e)) " [PROPOSED]"))))))))
        ns-list (str/join "\n" (map (fn [ns] (str "  " ns)) (sort (distinct (map :simulation/ns entries)))))]
    (str (format "Solidity Shadow Registry — %d entries, %d contracts" total (count contracts))
         "\n"
         (format "  Implemented: %d" implemented)
         "\n"
         (format "  Not impl.:   %d" not-implemented)
         "\n"
         (format "  Differences: %d" with-diffs)
         "\n"
         "\n"
         (str/join "\n" (map contract-block (sort contracts)))
         "\n"
         "\n"
         "Registered namespaces:"
         "\n"
         ns-list)))
