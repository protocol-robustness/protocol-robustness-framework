# Replay-Sensitive Actions: Per-Action Dedupe Semantics

Reference for the actions subject to replay-boundary deduplication when
`event-id` is present.

Current count: **19 entries** — 17 canonical actions + 3 backward-compatibility aliases.

## Dedupe op-key shape

```
[:sew :replay-dedupe <action> <agent> <workflow-id> <slash-id> <hop-scope> <event-id>]
```

Fixed-length **8-element vector**.  Key schema: `:sew/replay-dedupe-v1`.

Built by `sew/dedupe-op-key` in `protocols_src/resolver_sim/protocols/sew.clj`
(see the `dedupe-op-key` function).

### Key semantics

| Position | Field | Source | Mandatory | May be nil |
|----------|-------|--------|-----------|------------|
| 0 | `:sew` | literal | yes | no |
| 1 | `:replay-dedupe` | literal | yes | no |
| 2 | `action` | `compat/canonical-action(event)` — kebab-case string | yes | no |
| 3 | `agent` | `(:agent event)` — string from event envelope | yes | no |
| 4 | `workflow-id` | `event-workflow-id(event)` — `:workflow-id` param, fallback to legacy `:id` | yes | no |
| 5 | `slash-id` | `event-slash-id(event)` — `:slash-id` param, fallback `:workflow-id` | yes | no |
| 6 | `hop-scope` | `hop-id` param **or** current dispute level | yes | yes (non-hop actions) |
| 7 | `event-id` | `compat/event-id(event)` — normalized to string | yes | no |

**Key determinism guarantee:** every field is derived solely from the event
and stable world state (dispute level for implicit hop-scope lookup).  The key
does NOT depend on mutable incidental state such as timestamps, iteration
order, or derived non-authoritative values.

### Fields

| Component | Source | Notes |
|-----------|--------|-------|
| `action` | `compat/canonical-action(event)` | Normalised kebab-case string |
| `agent` | `(:agent event)` | Direct from event envelope |
| `workflow-id` | `event-workflow-id(event)` | `:workflow-id` param, fallback `:id` |
| `slash-id` | `event-slash-id(event)` | `:slash-id` param, fallback `:workflow-id`. World is NOT consulted — slash identity is an event-level property for dedupe determinism across retries. |
| `hop-scope` | `hop-id` param or dispute level | escalate/challenge only; `nil` for others |
| `event-id` | `compat/event-id(event)` | Must be present for dedupe to activate. Keywords normalised to strings. |

### Normalization rules

- **Action names:** converted from `snake_case` to `kebab-case` by `compat/canonical-action`.
- **Identifiers (event-id, hop-id):** `compat/normalize-id` converts keywords to strings;
  strings pass through unchanged.  Integers and nil pass through.
- **workflow-id / slash-id:** passed through as-is (strings, numbers, or keywords
  from the event params).  `event-workflow-id` and `event-slash-id` apply a
  `:workflow-id` / `:id` fallback chain.

## Per-action key reference

### Actions with `hop-scope ≠ nil`

| Action | `agent` | `workflow-id` | `slash-id` | `hop-scope` | `event-id` |
|--------|---------|---------------|------------|-------------|------------|
| `escalate-dispute` | caller | `:workflow-id` | = wf-id | explicit `hop-id` OR current dispute level | `:event-id` |
| `challenge-resolution` | caller | `:workflow-id` | = wf-id | explicit `hop-id` OR current dispute level | `:event-id` |

**Why hop-scope matters:** The same `event-id` could span multiple escalation
levels. Without hop-scope, a duplicate `challenge-resolution` at L1 would be
treated as a duplicate of the L0 challenge if both share the same `event-id`.

**Common mistake:** Omitting `hop-id` when the dispute level changes between
replay passes (e.g., after a reorg). The resolver looks up the current dispute
level from the world, which may differ from the level at which the action was
originally dispatched.

### Actions with `slash-id ≠ workflow-id`

| Action | `agent` | `workflow-id` | `slash-id` | `hop-scope` | `event-id` |
|--------|---------|---------------|------------|-------------|------------|
| `propose-fraud-slash` | caller | `:workflow-id` | `:slash-id` → `:workflow-id` | `nil` | `:event-id` |
| `resolve-appeal` | caller | `:workflow-id` | `:slash-id` → `:workflow-id` | `nil` | `:event-id` |
| `execute-fraud-slash` | caller | `:workflow-id` | `:slash-id` → `:workflow-id` | `nil` | `:event-id` |
| `force-reversal-slash` | caller | `:workflow-id` | `:slash-id` → `:workflow-id` | `nil` | `:event-id` |

**Why slash-id ≠ workflow-id matters:** A single workflow can accumulate
multiple independent slash operations. Each slash has a distinct `:slash-id`.
Using `:workflow-id` as the slash-id would conflate all slashes into a single
dedupe scope, allowing a duplicate of one slash to block a different slash on
the same workflow.

**Correct:** `{:slash-id "0-reversal-0" :event-id "evt-slash-level0"}`
**Wrong:** `{:workflow-id 0 :event-id "evt-slash-level0"}` — conflates all
slash operations on workflow 0.

### Actions with flat key (`slash-id = workflow-id`)

| Action | `agent` | `workflow-id` | `slash-id` | `hop-scope` | `event-id` |
|--------|---------|---------------|------------|-------------|------------|
| `execute-resolution` | caller | `:workflow-id` | = wf-id | `nil` | `:event-id` |
| `execute-pending-settlement` | caller | `:workflow-id` | = wf-id | `nil` | `:event-id` |
| `rotate-dispute-resolver` | caller | `:workflow-id` | = wf-id | `nil` | `:event-id` |

**Common mistake:** Assuming these follow the same pattern as slash actions.
`slash-id` always equals `workflow-id` for these three, so identical op-keys
differentiate solely by `event-id` (and `agent`, `action`). If two
`execute-pending-settlement` calls target different escrows but share an
`event-id`, the second will incorrectly dedupe.

**Correct:** Always use a unique `event-id` per action occurrence.

### Governance actions with replay deduplication

| Action | Notes |
|--------|-------|
| `declare-fraud-incident` | Governance-only |
| `propose-fraud-slash` | Governance-only |
| `propose-fraud-group-slash` | Governance-only |
| `appeal-fraud-group-slash` | Governance-only |
| `resolve-fraud-group-appeal` | Governance-only |
| `resolve-appeal` | Governance-only |
| `execute-fraud-slash` | Non-governance (resolved actor) |
| `execute-fraud-group-slash` | Non-governance (resolved actor) |
| `force-reversal-slash` | Governance-only; has business-logic guard as defence-in-depth |
| `grant-force-authorisation` | Governance-only |
| `revoke-force-authorisation` | Governance-only |
| `execute-force-authorised-action` | Governance-only |
| `rotate-dispute-resolver` | Governance-only |

### Backward-compatibility aliases

These aliases normalise to the same canonical action name and produce identical
dedupe keys:

| Alias | Canonical |
|-------|-----------|
| `grant-force-authorization` | `grant-force-authorisation` |
| `revoke-force-authorization` | `revoke-force-authorisation` |
| `execute-force-authorized-action` | `execute-force-authorised-action` |

### Actions NOT in the set (deliberate omissions)

The following authoritative state transitions are excluded because they have
business-logic idempotence guarantees that are sufficient, and the cost of
replay-dedupe scaffolding (event-id provisioning in all callers) outweighs the
benefit:

- `appeal-slash` — governed by pending-fraud-slashes state guard
- `unfreeze-resolver` — governed by resolver-frozen-until guard
- `declare-fraud-incident` — has idempotent insert semantics
- `set-paused` — governed by state guard
- Other governance-only actions without replay-sensitive state transitions

This list is not exhaustive; additions should be evaluated case by case.

## Dedupe activation conditions

Dedupe is active only when ALL of the following hold:

1. The action is in `replay-sensitive-actions` (see `sew/replay-sensitive-actions` def)
2. The event has a non-nil `:event-id` in params (normalized to string)
3. The `:require-event-id?` flag is NOT set to `true` (if it is, missing
   `event-id` causes rejection, not pass-through)

## Interaction with business-logic idempotence

These are independent layers:

| Layer | Mechanism | Duplicate outcome | Active when |
|-------|-----------|-------------------|-------------|
| **Replay-boundary** | `apply-once` wrapping `apply-action` | No-op (`:no-op-duplicate`) | `event-id` present |
| **Business-logic** | State guards in lifecycle/resolution | Reject (error keyword) | Always |

Business-logic idempotence is action-specific:
- `rotate-dispute-resolver`: same-target rotation returns `:idempotent? true`
- `execute-pending-settlement`: terminal state guard → `:transfer-not-in-dispute`
- `execute-fraud-slash`: status guard → `:already-executed`
- `force-reversal-slash`: `:slash-by-context` guard → world returned unchanged

## Effect on action state

When replay dedupe fires (`:no-op-duplicate`), the action is **skipped
entirely** — `apply-fn` never runs.  This means:

- **State mutations do not occur** — no pending settlement created, no
  stake slashed, no resolver rotation recorded.
- **Audit trail entries are not appended** — the action leaves no trace
  beyond the `{:extra {:idempotency :no-op-duplicate}}` marker.
- **Invariant checks still run** — the step still appears in the trace
  with `:result :ok`, and invariants pass (world unchanged).

## `apply-once` retry-after-failure contract

The `apply-once` helper (see `resolver-sim.contract-model.idempotency/apply-once`)
records the operation key ONLY when the operation succeeds (`:ok` truthy).

**Diagnostic outcomes:**

| Outcome | `:extra` marker | Key recorded |
|---------|-----------------|-------------|
| First successful application | `{:idempotency :applied-once}` | Yes |
| Duplicate of successful application | `{:idempotency :no-op-duplicate ...}` | Already recorded |
| First attempt failed | `{:idempotency :attempted-failed :retryable? true}` | **No** — retry allowed |
| Retry succeeds | `{:idempotency :applied-once}` | Yes |

This means idempotency is defined as **"at most one successful application"**,
not "at most one attempted application."  A failed operation with the same
`event-id` may be retried.  After a successful retry, further duplicates are
rejected.
