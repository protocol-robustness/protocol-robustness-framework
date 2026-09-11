# V2 Semantic IDs ↔ V1 Event-ID Mapping

This document describes how V1 replay params (`event-id`, `workflow-id`,
`slash-id`, `hop-id`) map to the aspirational V2 semantic ID hierarchy defined
in `IDENTITY_PROTOCOL.md`.

## Mapping Table

| V2 Semantic ID | V1 Equivalent | Derivation in V2 | Current V1 mechanism |
|----------------|---------------|------------------|----------------------|
| `ProtectedTransferId` | `workflow-id` (integer) | Hash of canonical source | Monotonic counter (0, 1, 2, …) |
| `DisputeProcessId` | `event-id` on `raise_dispute` | `hash(TransferId, nonce)` | Optional string param on dispute events |
| `ResolverDecisionId` | `event-id` on `execute_resolution` | `hash(DisputeProcessId, resolverId, round)` | Optional string param on resolution events |
| `WatchdogChallengeId` | `event-id` + `hop-id` on `challenge-resolution` | `hash(DisputeProcessId, challengerId, nonce)` | Combined `event-id` + `hop-id` scope |
| `ClaimableWithdrawalId` | (no V1 equivalent) | `hash(TransferId, claimType, sequence)` | Not modelled — withdrawals use ledger paths |

## Current V1 Replay Params

| Param | Type | Purpose | V2 Replacements |
|-------|------|---------|-----------------|
| `event-id` | string (optional) | Logical transaction id; gates replay dedupe | `DisputeProcessId`, `ResolverDecisionId`, etc. |
| `hop-id` | string or int (optional) | Escalation hop scope for `escalate-dispute` / `challenge-resolution` | Part of `WatchdogChallengeId` |
| `workflow-id` | int (required for action targeting) | Index of the target escrow | `ProtectedTransferId` |
| `slash-id` | string or int (optional) | Scope for fraud/reversal slash operations | Part of `DisputeProcessId` + round |

## Phase 1: Current State (Simulation Only)

V1 monotonic IDs (`workflow-id` as integer) remain the primary addressing
mechanism for actions. `event-id` is an optional string that activates
replay-boundary dedupe. There is **no canonicalization layer** between V1
and V2 IDs — they coexist as parallel namespaces.

## Phase 2: Canonicalization Layer (Proposed)

A `canonicalize-id` utility would map V1 counters to deterministic V2 hashes:

```clojure
(canonicalize-id :workflow-id 0)
;; => {:v1 0 :v2 "0x8a2f3c..." :type :ProtectedTransferId}

(canonicalize-id :event-id "evt-exec-res-1" {:workflow-id 0 :round 0})
;; => {:v1 "evt-exec-res-1" :v2 "0xbc4d1e..." :type :ResolverDecisionId}
```

Mapping V1 → V2 requires:
1. A genesis-timestamp-derived seed for hash determinism
2. The V1 `event-id` string (or a synthetic one if absent) as preimage
3. The `workflow-id`, `round`, `caller` context for type-specific derivation

## Phase 3: Full V2 Migration (Hard Fork)

Solidity storage migrates from `mapping(uint256 => Escrow)` to
`mapping(bytes32 => Escrow)`. All actions use V2 semantic IDs as primary
keys. The canonicalization layer becomes the audit trail linking old V1
traces to new V2 state.

## Replay Implication

V2 `event-id` would be mandatory for all replay-sensitive actions (currently
optional). The `:require-event-id?` flag (in `external-log-replay-flags`)
would become the default replay mode. Replay-boundary dedupe switches from
`event-id`-gated to unconditional for the 8 `replay-sensitive-actions`.
