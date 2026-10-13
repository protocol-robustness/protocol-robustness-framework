(ns resolver-sim.util.evidence.schema
  "Evidence payload key schema registry.")

(def known-evidence-payload-keys
  "Registry of documented evidence payload keys.
   These describe the data inside evidence artifacts (before/after/inputs),
   not the provenance context.
   Researchers should look for these keys inside :evidence/before,
   :evidence/after, and :evidence/inputs maps."
  {:yield/target-type
   {:domain :yield
    :description "Type of yield target, e.g. :resolver."}

   :yield/resolver-addr
   {:domain :yield
    :description "Resolver address for yield accrual."}

   :accrual/accrual-mode
   {:domain :accrual
    :description "Mode of accrual operation."}

   :accrual/module-id
   {:domain :accrual
    :description "Yield module identifier."}

   :accrual/token
   {:domain :accrual
    :description "Token being accrued."}

   :accrual/position-id
   {:domain :accrual
    :description "Position identifier for accrual."}

   :accrual/yield-delta
   {:domain :accrual
    :description "Final yield delta from accrual."}

   :accrual/deferred-delta
   {:domain :accrual
    :description "Deferred yield delta."}

   :accrual/previous-index
   {:domain :accrual
    :description "Previous yield index (JSON-safe)."}

   :accrual/final-index
   {:domain :accrual
    :description "Final yield index (JSON-safe)."}

   :accrual/short-circuits
   {:domain :accrual
    :description "Short-circuit flags from accrual decision."}

   :accrual/now
   {:domain :accrual
    :description "Block timestamp of accrual."}

   :accrual/before
   {:domain :accrual
    :description "Pre-accrual world state snapshot (select keys)."}

   :accrual/after
   {:domain :accrual
    :description "Post-accrual world state snapshot (select keys)."}

   :accrual/decision
   {:domain :accrual
    :description "Full accrual decision map (excluding :world)."}

   :settlement/requested
   {:domain :settlement
    :description "Total requested fill amount."}

   :settlement/filled
   {:domain :settlement
    :description "Total filled amount."}

   :settlement/deferred
   {:domain :settlement
    :description "Total deferred amount."}

   :settlement/haircut
   {:domain :settlement
    :description "Haircut applied during settlement."}

   :settlement/shortage
   {:domain :settlement
    :description "Liquidity shortage amount."}

   :settlement/module-id
   {:domain :settlement
    :description "Module handling the settlement."}

   :settlement/token
   {:domain :settlement
    :description "Token being settled."}

   :settlement/position-id
   {:domain :settlement
    :description "Position identifier for settlement."}

   :settlement/before
   {:domain :settlement
    :description "Pre-settlement world state snapshot (select keys)."}

   :settlement/after
   {:domain :settlement
    :description "Post-settlement world state snapshot (select keys)."}

   :settlement/decision
   {:domain :settlement
    :description "Full settlement decision map."}

   :proposal/resolver
   {:domain :proposal
    :description "Resolver address for a slash proposal."}

   :proposal/amount
   {:domain :proposal
    :description "Proposed slash amount."}

   :proposal/status
   {:domain :proposal
    :description "Proposal status at capture time."}

   :proposal/deadline
   {:domain :proposal
    :description "Proposal appeal deadline."}

   :proposal/workflow-id
   {:domain :proposal
    :description "Workflow the proposal applies to."}

   ;; Escrow evidence
   :escrow/before
   {:domain :escrow
    :description "Pre-creation world state snapshot (select keys)."}

   :escrow/after
   {:domain :escrow
    :description "Post-creation world state snapshot (select keys)."}

   :escrow/workflow-id
   {:domain :escrow
    :description "Allocated workflow-id for the new escrow."}

   :escrow/token
   {:domain :escrow
    :description "Token address used in the escrow."}

   :escrow/amount
   {:domain :escrow
    :description "Gross escrow amount before fee deduction."}

   :escrow/fee
   {:domain :escrow
    :description "Escrow fee deducted from amount."}

   :escrow/amount-after-fee
   {:domain :escrow
    :description "Net escrow amount after fee deduction."}

   :escrow/resolver
   {:domain :escrow
    :description "Assigned dispute resolver address."}

   :escrow/auto-release
   {:domain :escrow
    :description "Auto-release deadline (0 = none)."}

   :escrow/auto-cancel
   {:domain :escrow
    :description "Auto-cancel deadline (0 = none)."}

   :escrow/yield-module
   {:domain :escrow
    :description "Yield generation module if configured."}

   :escrow/yield-deposit-applied?
   {:domain :escrow
    :description "Whether the yield deposit was actually executed."}

   :escrow/settings
   {:domain :escrow
    :description "Normalized EscrowSettings snapshot (artifact-safe fields)."}

   ;; Escrow finalize evidence (escrow terminal transitions)
   :finalize/before
   {:domain :finalize
    :description "Pre-finalization world state snapshot (select keys)."}

   :finalize/after
   {:domain :finalize
    :description "Post-finalization world state snapshot (select keys)."}

   :finalize/workflow-id
   {:domain :finalize
    :description "Workflow-id of the finalized escrow."}

   :finalize/direction
   {:domain :finalize
    :description "Terminal direction: :released or :refunded."}

   :finalize/recipient
   {:domain :finalize
    :description "Address receiving the settled funds."}

   :finalize/settled-amount
   {:domain :finalize
    :description "Amount actually settled (may differ from principal under shortfall)."}

   :finalize/sub-held-amount
   {:domain :finalize
    :description "Amount deducted from :total-held."}

   :finalize/write-down
   {:domain :finalize
    :description "Portion of the owed principal written down out of held (e.g. a
                  negative-yield / mark-to-market principal write-down) rather than
                  settled to claimable. Zero for normal and liquidity-shortfall
                  settlements (deferred/haircut are tracked separately). Reconciliation:
                  owed = claimable + write-down + deferred + haircut."}

   :finalize/partial-yield?
   {:domain :finalize
    :description "Whether a partial-yield shortfall occurred."}

   :finalize/shortfall?
   {:domain :finalize
    :description "Whether any liquidity shortfall occurred."}

   :finalize/resolver
   {:domain :finalize
    :description "Dispute resolver at time of finalization."}

   ;; Stake evidence
   :stake/before
   {:domain :stake
    :description "Resolver stake before the operation."}

   :stake/after
   {:domain :stake
    :description "Resolver stake after the operation."}

   :stake/resolver
   {:domain :stake
    :description "Resolver address."}

   :stake/amount
   {:domain :stake
    :description "Amount deposited or withdrawn."}

   :stake/yield-profile-id
   {:domain :stake
    :description "Yield profile assigned on registration."}

   ;; Slash appeal evidence
   :appeal/before
   {:domain :appeal
    :description "Pre-appeal state (slash status, amount)."}

   :appeal/after
   {:domain :appeal
    :description "Post-appeal state (status, bond-held)."}

   :appeal/slash-id
   {:domain :appeal
    :description "Slash identifier (workflow-id or level-scoped string)."}

   :appeal/workflow-id
   {:domain :appeal
    :description "Workflow the appeal applies to."}

   :appeal/resolver
   {:domain :appeal
    :description "Resolver who appealed."}

   :appeal/bond-amount
   {:domain :appeal
    :description "Appeal bond posted."}

   :appeal/bond-token
   {:domain :appeal
    :description "Token used for appeal bond."}

   ;; Appeal resolution evidence
   :appeal-resolution/before
   {:domain :appeal-resolution
    :description "Pre-resolution state (slash status, bond-held)."}

   :appeal-resolution/after
   {:domain :appeal-resolution
    :description "Post-resolution state (slash status, bond-held)."}

   :appeal-resolution/slash-id
   {:domain :appeal-resolution
    :description "Slash identifier."}

   :appeal-resolution/workflow-id
   {:domain :appeal-resolution
    :description "Workflow the resolution applies to."}

   :appeal-resolution/resolver
   {:domain :appeal-resolution
    :description "Resolver who originally appealed."}

   :appeal-resolution/outcome
   {:domain :appeal-resolution
    :description "Resolution outcome: :reversed or :rejected."}

   :appeal-resolution/bond-forfeited?
   {:domain :appeal-resolution
    :description "Whether the appeal bond was forfeited."}

   :appeal-resolution/bond-amount
   {:domain :appeal-resolution
    :description "Amount of bond held."}

   :appeal-resolution/bond-token
   {:domain :appeal-resolution
    :description "Token of the appeal bond."}

   ;; Dispute escalation evidence
   :escalation/before
   {:domain :escalation
    :description "Pre-escalation state (dispute-level, resolver)."}

   :escalation/after
   {:domain :escalation
    :description "Post-escalation state (dispute-level, resolver)."}

   :escalation/workflow-id
   {:domain :escalation
    :description "Workflow being escalated."}

   :escalation/caller
   {:domain :escalation
    :description "Address that triggered the escalation."}

   :escalation/from-level
   {:domain :escalation
    :description "Dispute level before escalation."}

   :escalation/to-level
   {:domain :escalation
    :description "Dispute level after escalation."}

   :escalation/new-resolver
   {:domain :escalation
    :description "Resolver assigned at the new level."}

   :escalation/bond-amount
   {:domain :escalation
    :description "Appeal bond posted for escalation."}

   :escalation/escalation-count
   {:domain :escalation
    :description "Escalation count for this address (Layer B)."}

   ;; Challenge resolution evidence
   :challenge/before
   {:domain :challenge
    :description "Pre-challenge state (dispute-level, resolver)."}

   :challenge/after
   {:domain :challenge
    :description "Post-challenge state (dispute-level, resolver)."}

   :challenge/workflow-id
   {:domain :challenge
    :description "Workflow being challenged."}

   :challenge/caller
   {:domain :challenge
    :description "Address that issued the challenge."}

   :challenge/from-level
   {:domain :challenge
    :description "Dispute level before challenge."}

   :challenge/to-level
   {:domain :challenge
    :description "Dispute level after challenge."}

   :challenge/new-resolver
   {:domain :challenge
    :description "Resolver assigned at the new level."}

   :challenge/bond-amount
   {:domain :challenge
    :description "Challenge bond posted."}

   :challenge/escalation-count
   {:domain :challenge
    :description "Escalation count for this address (Layer B)."}

   ;; Bond evidence
   :bond/before
   {:domain :bond
    :description "Pre-operation bond state (balance, status)."}

   :bond/after
   {:domain :bond
    :description "Post-operation bond state (balance, status)."}

   :bond/workflow-id
   {:domain :bond
    :description "Workflow the bond applies to."}

   :bond/appellant
   {:domain :bond
    :description "Address that posted the bond."}

   :bond/amount
   {:domain :bond
    :description "Gross bond amount."}

   :bond/fee
   {:domain :bond
    :description "Protocol fee deducted from the bond."}

   :bond/net
   {:domain :bond
    :description "Net bond amount after fee deduction."}

   :bond/token
   {:domain :bond
    :description "Token used for the bond."}

   ;; Dispute evidence
   :dispute/before
   {:domain :dispute
    :description "Pre-dispute state (escrow state, resolver)."}

   :dispute/after
   {:domain :dispute
    :description "Post-dispute state (escrow state, resolver capacity)."}

   :dispute/workflow-id
   {:domain :dispute
    :description "Workflow being disputed."}

   :dispute/caller
   {:domain :dispute
    :description "Address that raised the dispute."}

   :dispute/resolver
   {:domain :dispute
    :description "Assigned dispute resolver."}

   :dispute/level
   {:domain :dispute
    :description "Current dispute level."}

   ;; Unavailability / circuit breaker evidence
   :unavailability/before
   {:domain :unavailability
    :description "Pre-change state (unavailable count, circuit breaker)."}

   :unavailability/after
   {:domain :unavailability
    :description "Post-change state (unavailable count, circuit breaker)."}

   :unavailability/resolver
   {:domain :unavailability
    :description "Resolver address whose unavailability changed."}

   :unavailability/unavailable?
   {:domain :unavailability
    :description "Whether the resolver was marked unavailable."}

   :unavailability/circuit-breaker-triggered?
   {:domain :unavailability
    :description "Whether the circuit breaker was activated by this change."}

   :unavailability/circuit-breaker-cleared?
   {:domain :unavailability
    :description "Whether the circuit breaker was deactivated by this change."}

   :unavailability/pct-bps
   {:domain :unavailability
    :description "Percentage of unavailable resolvers (basis points)."}

   :unavailability/threshold-bps
   {:domain :unavailability
    :description "Circuit breaker activation threshold (basis points)."}

   ;; Freeze evidence
   :freeze/before
   {:domain :freeze
    :description "Pre-freeze state (frozen-until time, typically 0)."}

   :freeze/after
   {:domain :freeze
    :description "Post-freeze state (frozen-until set to expiry block time)."}

   :freeze/resolver
   {:domain :freeze
    :description "Resolver being frozen."}

   :freeze/duration
   {:domain :freeze
    :description "Freeze duration in seconds."}

   :freeze/trigger
   {:domain :freeze
    :description "Action that triggered the freeze (e.g. :execute-fraud-slash)."}

   ;; Unfreeze evidence
   :unfreeze/before
   {:domain :unfreeze
    :description "Pre-unfreeze state (frozen-until time)."}

   :unfreeze/after
   {:domain :unfreeze
    :description "Post-unfreeze state (frozen-until reset to 0)."}

   :unfreeze/resolver
   {:domain :unfreeze
    :description "Resolver being unfrozen."}

   ;; World anchoring (full-state content hashes)
   :world/before-full-hash
   {:domain :world
    :description "SHA-256 content hash of the full pre-state world — links targeted evidence to the generic trace."}

   :world/after-full-hash
   {:domain :world
    :description "SHA-256 content hash of the full post-state world."}

   ;; Evidence chain fields
   :evidence/chain-seq
   {:domain :evidence
    :description "Sequential position in the targeted evidence chain for this run."}

   :evidence/chain-prev-hash
   {:domain :evidence
    :description "Evidence-hash of the previous artifact in the run-scoped chain."}

   :evidence/chain-self-hash
   {:domain :evidence
    :description "Self-referencing evidence-hash (excludes chain fields from the hash input)."}})
