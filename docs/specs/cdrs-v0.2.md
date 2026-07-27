# Common Dispute Resolution Standard (CDRS) v0.2

**Status**: Experimental (Draft)  
**Supersedes**: CDRS v0.1 (`cdrs-v0.1.md`)  
**Backward compatible**: Yes — v0.1 traces are valid v0.2 traces; the new blocks
are optional and checked only when present.

## Changes from v0.1

CDRS v0.1 verifies the *structural* correctness of a trace: state buckets, the
solvency invariant, and per-step accounting fields.

CDRS v0.2 adds *semantic* correctness: resolution outcome, resolution authority,
escalation level, participant roles, and timing window booleans.  A trace that
ends in the same `state_bucket` but resolves to the wrong side, by an
unauthorised actor, or at the wrong escalation level now fails equivalence.

---

## 1. Trace-Level Fields

### `cdrs_version` (required)

MUST be `"0.2"` for v0.2 traces.

### `schema_version` (required)

Integer version of the fixture format.  MUST be `2` for v0.2 fixtures.

### `trace_kind` (required)

Array of one or more strings classifying the trace content.  Used by
TraceEquivalence.t.sol to select which semantic check sets to run.

| Value | Meaning |
|---|---|
| `"lifecycle"` | No dispute raised; basic create/release/cancel flow |
| `"dispute-resolution"` | At least one dispute raised; includes resolution path |
| `"escalation"` | At least one `escalate_dispute` action in the trace |
| `"pending-settlement"` | A pending settlement was created and/or executed |
| `"timing"` | At least one `auto_cancel_disputed` or deadline action present |
| `"authorization"` | At least one invalid-state-transition rejection present |
| `"adversarial"` | Scenario classified as adversarial or stress class |

A single trace may carry multiple values.  `"lifecycle"` and
`"dispute-resolution"` are mutually exclusive.

### `expected_semantics` (optional)

Top-level block of semantic expectations.  Absent or empty means "no semantic
checks" (equivalent to v0.1 behaviour).  Each sub-block is independently
optional; omitting a sub-block suppresses that class of check entirely.

---

## 2. `expected_semantics.resolution`

Required when `trace_kind` contains `"dispute-resolution"`.

| Field | Type | Description |
|---|---|---|
| `outcome` | string | `"release"` / `"refund"` / `"settled"` / `"unresolved"` |
| `finality` | string | `"final"` / `"appealable"` / `"stalled"` |
| `integrity` | string | `"fully-reconciled"` / `"accounting-mismatch"` / `"missing-effects"` / `"leakage"` |
| `beneficiary` | string | `"seller"` / `"buyer"` / `"settled"` — absent when unresolved |
| `resolver` | string | Agent id of the party who executed resolution — absent when unresolved |
| `authorized_resolver` | boolean | Whether `execute_resolution` succeeded (:ok) |
| `pending_settlement_created` | boolean | Whether a pending-settlement object was created |
| `settlement_executed` | boolean | Whether `execute_pending_settlement` succeeded |

### Outcome vocabulary

| Value | Maps from v0.1 | Meaning |
|---|---|---|
| `release` | `RELEASE` | Funds sent to recipient |
| `refund` | `REFUND` | Funds returned to sender |
| `settled` | `SETTLED` | Pending settlement executed |
| `unresolved` | `NO_OP` | No fund movement (dispute still open or stalled) |

### Finality vocabulary

| Value | Meaning |
|---|---|
| `final` | Irreversible; settlement executed |
| `appealable` | Within the appeal window; pending settlement exists |
| `stalled` | No progress possible without external intervention |

---

## 3. `expected_semantics.escalation`

Required when `trace_kind` contains `"escalation"`.  Absent when no
`escalate_dispute` action appears in the trace.

| Field | Type | Description |
|---|---|---|
| `level` | integer | Dispute level at trace end |
| `max_level_reached` | integer | Maximum level observed across the trace |
| `tier` | string | `"l0"` / `"l1"` / `"l2"` / `"arbitration"` |
| `attempted` | boolean | Whether escalation was attempted (`true` when block present) |
| `accepted` | boolean | Whether at least one escalation succeeded (:ok) |
| `rejected` | boolean | Whether at least one escalation was rejected |

### Tier vocabulary

Tier is derived from the final `level` value:

| Level | Tier |
|---|---|
| 0 | `l0` (initial resolver) |
| 1 | `l1` (senior resolver) |
| 2 | `l2` (second escalation) |
| ≥ 3 | `arbitration` |

---

## 4. `expected_semantics.participation`

Present for any trace with a dispute raised.

| Field | Type | Description |
|---|---|---|
| `dispute_initiator` | string | Agent id who called `raise_dispute` |
| `resolution_actor` | string | Agent id who last called `execute_resolution` successfully |
| `settlement_actor` | string | Agent id who called `execute_pending_settlement` — absent if none |
| `authorized_participant` | boolean | Whether the resolution actor's call succeeded (:ok) |

---

## 5. `expected_semantics.timing`

Present for any trace with a dispute raised.

| Field | Type | When present |
|---|---|---|
| `auto_cancel_triggered` | boolean | Always |
| `within_resolution_window` | boolean | When `max-dispute-duration > 0` and resolution timestamp exists |
| `within_settlement_window` | boolean | When `appeal-window-duration > 0` and settlement timestamp exists |
| `pending_delay_seconds` | integer | When both resolution and settlement timestamps exist |

`within_resolution_window`: true when
`(resolution_time − dispute_open_time) ≤ max-dispute-duration`.

`within_settlement_window`: true when
`(settlement_time − resolution_time) ≤ appeal-window-duration`.

`pending_delay_seconds`: `settlement_time − resolution_time` (in seconds).

---

## 6. Per-Step Semantic Fields

Every step in a v0.2 trace MUST include:

| Field | Type | Description |
|---|---|---|
| `accepted` | boolean | Whether the action was accepted (:ok result) |
| `state_changed` | boolean | Whether the world state changed (same as `accepted`) |
| `rejection_reason` | string | Error keyword when `accepted: false`; absent otherwise |

---

## 7. Backward Compatibility

| Fixture version | Behaviour |
|---|---|
| v0.1 | All existing v0.1 checks run; no semantic checks applied |
| v0.2, no `expected_semantics` | Same as v0.1 |
| v0.2, partial `expected_semantics` | Only declared sub-blocks are checked |
| v0.2, full `expected_semantics` | All declared fields checked |

**Optional-field rule**: if a field is absent in `expected_semantics`, the
check is skipped.  If a field is declared but the trace cannot provide the
observation, the check MUST fail loudly — never silently skip a declared field.

---

## 8. Solvency Standard (inherited from v0.1)

`TotalValueIn == TotalValueOut + TotalValueHeld`

Must hold at every step.  See CDRS v0.1 §3 for the full definition.

---

## 9. Canonical State Buckets (inherited from v0.1)

`IDLE` · `ACTIVE` · `CHALLENGED` · `RECONCILING` · `SETTLED`

See CDRS v0.1 §1 for definitions.

---

## 10. Deferred to v0.3

The following fields are reserved in the schema with no required semantics for
v0.2:

- `expected_semantics.accounting` — per-escrow balance deltas, fees, bonds
- Raw timestamps in `timing` — only derived booleans are required
- `resolution.resolution_hash` — optional; not verified until on-chain registry available
- `escalation.pending_cleared_on_escalation` — complex for multi-escalation traces
- Coalition / payoff matrix fields — multi-epoch only
