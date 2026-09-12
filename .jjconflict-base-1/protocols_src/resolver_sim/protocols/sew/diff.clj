(ns resolver-sim.protocols.sew.diff
  "Canonical world-state hashing and structural diff.

   Used for differential testing: compare Clojure model state step-by-step
   against EVM execution on Anvil.

   Core workflow:
     1. After each replay step, call (world-hash world') to get a SHA-256 digest.
     2. Extract the equivalent minimal state from Anvil via RPC (escrow states,
        dispute levels, balances) and convert to the same canonical structure.
     3. Compare hashes; on mismatch use (diff-worlds sim-world evm-world) to
        locate the first point of divergence.

   Hashing uses resolver-sim.hash.canonical with :world-structure intent.
   Structural diff uses clojure.data/diff on sorted-map representations."
  (:require [clojure.data :as data]
            [resolver-sim.hash.canonical :as hc]))

;; ---------------------------------------------------------------------------
;; Canonical form
;; ---------------------------------------------------------------------------

(defn- ->sorted-deep
  "Recursively convert all maps to sorted-map.
   Vectors and lists are walked element-by-element; all other values pass through."
  [x]
  (cond
    (map? x)        (into (sorted-map) (map (fn [[k v]] [k (->sorted-deep v)]) x))
    (sequential? x) (mapv ->sorted-deep x)
    :else           x))

(defn canonical-world
  "Return a canonically-ordered copy of world (all maps → sorted-map).
   Used by diff-worlds for structural comparison with clojure.data/diff."
  [world]
  (->sorted-deep world))

;; ---------------------------------------------------------------------------
;; Structural diff
;; ---------------------------------------------------------------------------

(defn diff-worlds
  "Deep structural diff between two world states.

   Returns nil when worlds are logically identical.
   Otherwise returns:
     {:only-in-a  — keys/values present in world-a but absent or different in world-b
      :only-in-b  — keys/values present in world-b but absent or different in world-a
      :hash-a     — SHA-256 of world-a
      :hash-b     — SHA-256 of world-b}

   Typical usage: diff the Clojure model world against an EVM-reconstructed world
   to find the first divergent field after a state mismatch is detected."
  [world-a world-b]
  (let [[only-a only-b _same] (data/diff (canonical-world world-a)
                                         (canonical-world world-b))]
    (when (or only-a only-b)
      {:only-in-a only-a
       :only-in-b only-b
       :hash-a    (hc/hash-with-intent {:hash/intent :world-structure} world-a)
       :hash-b    (hc/hash-with-intent {:hash/intent :world-structure} world-b)})))

;; ---------------------------------------------------------------------------
;; EVM state adapter helpers
;; ---------------------------------------------------------------------------

(defn evm-world-skeleton
  "Return the keys that an EVM state adapter must populate to produce a world
   map comparable by (diff-worlds).

   The Anvil adapter (to be built) must read these fields from contract storage:
     :escrow-transfers     — {wf-id {:escrow-state :amount-after-fee :token ...}}
     :total-held           — {token-addr nat-int}  from EscrowVault.totalHeldPerToken
     :total-fees           — {token-addr nat-int}  from EscrowVault.totalFeesPerToken
     :pending-settlements  — {wf-id {:exists :is-release :appeal-deadline}}
     :dispute-levels       — {wf-id nat-int}        from DR module dm.currentRound
     :block-time           — nat-int               from block.timestamp
     :resolver-stakes      — {resolver-addr nat-int}  from ResolverStakingModuleV1
     :resolver-frozen-until — {resolver-addr nat-int}  from ResolverSlashingModuleV1.frozenUntil
     :dispute-timestamps   — {wf-id nat-int}        from escrow struct dispute timestamp

   Procedure for cross-domain comparison:
     1. Populate all fields above on the EVM side.
     2. Use (select-keys world diff/comparable-keys) on BOTH simulation and
        EVM worlds to restrict comparison to only the fields above.
     3. Call (diff-worlds sim-projection evm-projection) to compare.
        The only-in-a / only-in-b keys reveal the first divergence.

   Fields that exist only in the sim world and have NO direct EVM equivalent:
     :escrow-settings, :module-snapshots, :claimable, :pending-fraud-slashes,
     :previous-decisions, :yield/positions

   :pending-fraud-slashes has a canonical projection via slash-registry->canonical
   (types.clj:369).  See the evm-slash-registry design below for the exact
   contract queries and format expected from the EVM adapter.

   See docs/differential-testing.md (to be created) for the full mapping."
  []
  {:escrow-transfers     {}
   :total-held           {}
   :total-fees           {}
   :pending-settlements  {}
   :dispute-levels       {}
   :block-time           0
   :resolver-stakes      {}
   :resolver-frozen-until {}
   :dispute-timestamps   {}})

(defn comparable-keys
  "The world-state keys that have a direct EVM equivalent and should be used
   when comparing model state against Anvil state.

   Use (select-keys world (comparable-keys)) on BOTH sides before hashing to
   avoid false positives from fields that don't exist on-chain.

   NOTE: Some on-chain state has no direct simulation equivalent in a single
   key — e.g. :pending-fraud-slashes (slash lifecycle) and :previous-decisions
   (resolution history) are spread across multiple contracts.  They are omitted
   from comparable-keys for now.  Add them as the EVM adapter matures."
  []
  #{:escrow-transfers :total-held :total-fees :pending-settlements
    :dispute-levels :block-time :resolver-stakes :resolver-frozen-until
    :dispute-timestamps})

(defn projection
  "Project world to only the fields that can be compared against EVM state."
  [world]
  (select-keys world (comparable-keys)))

;; ---------------------------------------------------------------------------
;; EVM slash registry adapter design
;;
;; The slash registry (:pending-fraud-slashes) has no single Solidity mapping
;; equivalent — slash state is distributed across ResolverSlashingModuleV1
;; (fraud proposals, appeals, executions), the BaseEscrow/DR module (reversal
;; slashes created during resolution), and pending/reversal slash lifecycle.
;;
;; The EVM adapter must reconstruct the canonical vector by aggregating all
;; three sources.  The expected output matches slash-registry->canonical:
;;
;;   [{:slash/id                       nat-int     — slash identity
;;     :slash/workflow-id              nat-int     — workflow this slash belongs to
;;     :slash/kind                     keyword     — :reversal :fraud :force-reversal
;;     :slash/level                    nat-int     — dispute level (0-2)
;;     :resolver                       string      — slashed resolver address
;;     :amount                         nat-int     — slash amount in :token units
;;     :token                          string      — token address
;;     :status                         keyword     — :pending :executed :appealed
;;                                                   :reversed :reversed-with-credit
;;                                                   :expired-cleaned-up
;;     :reason                         keyword     — :reversal :fraud
;;     :proposed-at                    nat-int     — timestamp
;;     :appeal-deadline                nat-int     — deadline for resolver appeal
;;     :appeal-bond-held               nat-int     — bond posted for appeal
;;     ;; Optional fields present when applicable:
;;     :basis-amount                   nat-int     — stake at time of slash
;;     :basis-kind                     keyword     — :stake
;;     :slash-bps                      nat-int     — basis points of slash
;;     :reversal-detection-probability double      — probability of reversal detection
;;     :proposal-evidence-hash         string      — evidence hash (sim-only, omit in EVM)}
;;
;; Source mapping — ResolverSlashingModuleV1 (fraud slashes):
;;   - slash ID:    proposal counter (first arg to slashForFraud)
;;   - workflow:    escrow/workflow id (second arg)
;;   - resolver:    target resolver address
;;   - amount:      slash amount proposed
;;   - status:      :pending initially, → :appealed if appealed, → :executed after timelock
;;   - proposed-at: block.timestamp of proposal
;;   - deadline:    proposed-at + appeal-window-duration
;;   - appeal-bond: 0 if not appealed, bond amount if appealed
;;
;; Source mapping — BaseEscrow/DR module (reversal slashes):
;;   - slash ID:    deterministic from (workflow-id, :reversal, level) via slash-context-key
;;   - workflow:    escrow id
;;   - resolver:    resolver whose decision was reversed
;;   - amount:      reversal-slash-bps × resolver-stake
;;   - status:      :executed (Track 1, immediate) or :pending (Track 2, new evidence)
;;   - proposed-at: block.timestamp of the reversing resolution
;;   - deadline:    proposed-at + appeal-window (Track 2 only)
;;
;; The adapter queries three contract views:
;;   1. ResolverSlashingModuleV1.getFraudSlash(id)      -> fraud slash entry
;;   2. ResolverSlashingModuleV1.getFraudSlashCount()    -> iterate all fraud slashes
;;   3. BaseEscrow.getReversalSlash(workflowId, level)   -> reversal slash entry
;;
;; If these views don't exist, the adapter must derive them from:
;;   - SlashProposed / SlashExecuted / SlashAppealed events
;;   - The DR module's previous-decisions mapping (reversal status per level)
;; ---------------------------------------------------------------------------

(defn projection-hash
  "Hash of the EVM-comparable projection of world with :evm-projection intent.
   Uses :evm-projection domain tag for cross-domain isolation.
   Only includes fields that the EVM adapter can populate (see comparable-keys).
   Does NOT include simulation-only invariants (e.g. accounting-consistent?)
   because no on-chain adapter can reproduce them."
  [world]
  (let [proj (projection world)]
    (hc/hash-with-intent {:hash/intent :evm-projection} proj)))
