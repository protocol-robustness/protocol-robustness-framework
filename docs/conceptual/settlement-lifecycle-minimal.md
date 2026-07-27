# Settlement & Dispute Lifecycle

A protocol-agnostic framework for escrow-based settlement with tiered dispute
resolution. This document describes the lifecycle from the perspective of a
dispute resolver — covering state machines, escalation, evidence, deadlines,
bonds, and safety properties.

---

## 1. System Architecture

Two layers interact:

| Layer | Role |
|-------|------|
| **PRF** (Protocol Robustness Framework) | Simulation engine, invariant checker, evidence capture, replay |
| **Sew** | Concrete escrow + dispute resolution protocol that PRF models and tests |

The PRF replays scenario events against Sew's state machine, checking
invariants and capturing evidence at every step.

---

## 2. Escrow State Machine

Six states form the core lifecycle:

```
                  ┌──────────────────────────────┐
                  │                              │
                  ▼                              │
    none ──► pending ──► disputed ──► released   │
                    │        │          │         │
                    │        │          ├── refunded
                    │        │          │         │
                    │        │          └── resolved
                    │        │                    │
                    │        └──► released        │
                    │             refunded        │
                    │             resolved        │
                    │                             │
                    └──► released                 │
                         refunded                 │
                         resolved ────────────────┘
```

| State | Meaning | Terminal? |
|-------|---------|-----------|
| `none` | Pre-creation | No |
| `pending` | Escrow created, funds held | No |
| `disputed` | Dispute raised | No |
| `released` | Funds released to recipient | Yes |
| `refunded` | Funds returned to sender | Yes |
| `resolved` | Mutual split settlement | Yes |

---

## 3. Dispute Lifecycle Phases

### Phase 0 — Escrow Creation
- Sender creates escrow with token amount
- Funds debited from sender to `total-held`
- Escrow settings frozen (resolver, deadlines, yield module)
- Dispute resolver assigned

### Phase 1 — Dispute
- Sender or recipient raises a dispute on a `pending` escrow
- Dispute timestamp recorded, dispute level set to 0
- Escrow enters `disputed` state
- Both participants may submit evidence
- Resolver capacity consumed (max-concurrent-disputes enforced)

### Phase 2 — Resolution
- Resolver submits outcome (release or refund) via `execute-resolution`
- Two paths:

**Immediate** (appeal-window = 0 or final round):
Executes immediately → escrow transitions directly to `released` or `refunded`.

**Deferred** (appeal-window > 0, not final round):
A `PendingSettlement` is created:
```
{:exists           true
 :is-release       <bool>
 :appeal-deadline  <now + window-duration>
 :resolution-hash  <bytes32>}
```
Escrow remains `disputed` while the appeal window is open.

### Phase 3 — Appeal Window (Debate Period)
While `block-time < appeal-deadline`:

| Action | Who | Effect |
|--------|-----|--------|
| `escalate-dispute` | Sender or recipient | Archives pending settlement, increments dispute level, rotates resolver to next level |
| `challenge-resolution` | Any address | Same as escalation, but posts a challenge bond (1.1x scaling per escalation count) |
| `submit-evidence` | Participants | Evidence accepted while window is open |
| `execute-pending-settlement` | Anyone | **Rejected** — window not yet expired |

### Phase 4 — Settlement Execution
When `block-time >= appeal-deadline`:

`execute-pending-settlement` transitions escrow to `released` (if `is-release`) or `refunded` (if not). Accounting: yield accrued and withdrawn, principal released, claimable entries recorded.

### Phase 5 — Terminal (Post-Settlement)
No further actions allowed. States `released`, `refunded`, and `resolved` are absorbing.

---

## 4. Resolver & Escalation Model

### 4.1 Resolver Assignment

Each escrow has a resolution module that maps dispute levels to resolver
addresses. The default model uses three levels:

| Level | Resolver | Appeal Window |
|-------|----------|---------------|
| L0 | Primary resolver | Configurable (e.g., 7 days) |
| L1 | Senior resolver | Configurable |
| L2 | **Kleros** (final round) | 0 — decisions are immediate |

Each escalation increments the dispute level and rotates to the next
configured resolver.

### 4.2 Level 2 — Kleros Integration

When an escrow reaches Level 2 (the final escalation tier, `final-round?`):

- `execute-resolution` at Level 2 uses an appeal window of 0
- Decisions finalize immediately — no further escalation is possible
- The resolution module address for this level is the Kleros proxy contract

The system tests Kleros-level behavior across several scenarios:

| Scenario | What It Verifies |
|----------|------------------|
| `s18-dr3-kleros-l0-resolves` | Kleros L0 resolver resolves at level 0 |
| `s19-dr3-kleros-escalation-rejected-l0-resolves` | Preemptive escalation rejected; L0 resolves |
| `s20-dr3-kleros-max-escalation-guard` | Maximum escalation depth enforced |
| `s21-dr3-kleros-pending-cleared-on-escalation` | Pending settlement cleared on escalation; superseded fallback works |

### 4.3 Resolver Capacity

Resolvers have a `maxConcurrentDisputes` limit. Once at capacity, new
disputes cannot be raised against that resolver until existing ones resolve.
If a resolver is frozen (circuit breaker), new assignments are blocked.

### 4.4 Resolver Staking & Slashing

- Resolvers must stake protocol tokens to accept escrows
- `canHandleEscrow` checks that stake >= amount-at-stake-per-escrow
- On dispute timeout or misconduct, resolver stake is slashed and
  distributed to the protocol treasury
- Slashed amounts reduce the resolver's stake position, not `total-held`

---

## 5. Evidence Submission

### 5.1 Evidence Window

After a dispute is raised, participants have a configurable window
(`evidence-window-duration`) to submit evidence:

```
evidence-deadline = dispute-timestamp + evidence-window-duration
```

The deadline is enforced at two layers:
1. **PRF temporal rule**: rejects `submit_evidence` when `event-time >= deadline`
2. **Sew action handler**: rejects when `now > deadline` (strict >, second-line
   defence)

### 5.2 Evidence Capture

During replay, every critical transition captures evidence records:
- Before/after world state hashes
- Decision evidence (resolver reasoning, alternatives, outcome)
- Action evidence (action metadata, parameters)
- Invariant results (pass/fail for all registered invariants)

Evidence is deterministic, content-addressed, and immutable.

---

## 6. Keeper Actions (Automated Timed Dispatch)

The automated keeper function dispatches timed actions in priority order:

| Priority | Condition | Action | State Transition |
|----------|-----------|--------|------------------|
| 1 | `pending-settlement-executable?` | `execute-pending-settlement` | `disputed` → terminal |
| 2 | `auto-cancel-due-on-disputed?` | `auto-cancel-disputed-on-auto-time` | `disputed` → `refunded` (+ slash) |
| 3 | `dispute-timeout-exceeded?` | `auto-cancel-disputed-escrow` | `disputed` → `refunded` (+ slash resolver) |
| 4 | `auto-release-due?` | `finalize-escrow-accounting` (release) | `pending` → `released` |
| 5 | `auto-cancel-due?` | `finalize-escrow-accounting` (refund) | `pending` → `refunded` |
| 6 | (none) | `:none` | unchanged |

Priority 2 is a Sew extension — griefing protection that prevents a frivolous
dispute from blocking an auto-cancel deadline.

---

## 7. Deadline Semantics

### 7.1 Deadline Parameters (frozen per-escrow at creation)

| Parameter | Default | Meaning |
|-----------|---------|---------|
| `appeal-window-duration` | 0 | Seconds until appeal window closes after resolution |
| `challenge-window-duration` | 0 | Alternative window for third-party challenges |
| `evidence-window-duration` | 0 | Seconds after dispute for evidence submission |
| `max-dispute-duration` | 0 | Max seconds before keeper auto-cancels at dispute |
| `default-auto-release-delay` | 0 | Seconds until auto-release for non-disputed escrows |
| `default-auto-cancel-delay` | 0 | Seconds until auto-cancel for non-disputed escrows |

A value of 0 disables the mechanism.

### 7.2 Boundary Policy

| Action | Boundary | Allowed When | Error on Violation |
|--------|----------|-------------|-------------------|
| `submit_evidence` | Before `deadline` | `time < deadline` | `evidence-deadline-exceeded` |
| `execute-pending-settlement` | At-or-after `deadline` | `time >= deadline` | `appeal-window-not-expired` |
| `escalate-dispute` | Before `deadline` | `time < deadline` | `appeal-window-expired` |
| `challenge-resolution` | Before `deadline` | `time < deadline` | `appeal-window-expired` |

**Asymmetry (by design):** At `time == deadline`, settlement succeeds and
escalation/challenge fails. The resolver's decision executes as soon as the
window closes, and the opposing party gets the full window up to exactly the
deadline.

### 7.3 Superseded Pending Fallback

When a pending settlement is archived on escalation, it is preserved as a
superseded pending entry. If escalation at the next level produces no
replacement resolution, the superseded pending can be executed as a fallback
(if `block-time >= its appeal-deadline` and the dispute level matches).

---

## 8. Pending Settlement Sub-states

Within the `disputed` state, the pending-settlement mechanic adds fine-grained
temporal states:

```
disputed
  ├── No pending settlement (awaiting first resolution)
  │
  ├── Active pending settlement (appeal window open)
  │     ├── block-time < deadline: can escalate/challenge; cannot execute
  │     └── block-time >= deadline: can execute; cannot escalate/challenge
  │
  ├── Superseded pending (archived on escalation or challenge)
  │     └── Eligible for execution if:
  │           - no active pending exists
  │           - block-time >= superseded appeal-deadline
  │           - dispute level matches
  │
  └── Multiple superseded pendings (capped at 5 per workflow)
```

---

## 9. Bonds & Slashing

### 9.1 Challenge Bond

Third-party challenge of a resolution requires a bond:
- Base bond calculated from escrow amount and module snapshot parameters
- Bond scales 1.1x per previous escalation by the same address
- After 10 escalations: 200% of base; after 100: 1100% of base
- Economic disincentive against repeated attacks

### 9.2 Resolver Bond

At escrow creation, resolver bond basis points (`resolver-bond-bps`) may be
configured. If set, the resolver must have sufficient stake to cover the
escrow amount.

### 9.3 Reversal Slash

A mechanism to penalize resolvers whose decisions are overturned on appeal.
When an escalation reverses a prior decision:
- The original resolver's bond may be slashed
- Configurable probability and basis points per module snapshot
- Slashed funds distributed to the protocol treasury

### 9.4 Fraud Slash Timelock

Fraud slash proposals have an independent appeal window from the escrow's
settlement window:

```
slash-appeal-deadline = proposal-time + appeal-window-duration
```

Execution is allowed strictly after the deadline (`>`, not `>=`), giving the
slashed resolver the full window to appeal.

---

## 10. Key Invariants

| Invariant | Description |
|-----------|-------------|
| `pending-settlement-consistent` | Pending exists only when escrow is `disputed` |
| `no-double-settlement` | Each workflow finalizes at most once |
| `terminal-states-unchanged` | Terminal states never transition out |
| `conservation-of-funds` | Funds in = funds out + funds held |
| `solvency` | `total-held[t]` = sum of live escrow amounts |
| `escalation-clears-pending` | Escalation always archives the pending settlement |
| `evidence-deadline-enforced` | No evidence after deadline |
| `finality-blocked-during-appeal` | No settlement before window close |
| `time-lock-integrity` | No double-escalation in same block |

---

## 11. Force-Authorisation (Governance Override)

Force-authorisation is a governance-authorized override that bypasses the
normal resolver path. It grants a scoped, single-use, expiring authorization
to settle a dispute when the resolver mechanism is unavailable (frozen
resolver, circuit breaker active, overcapacity, or governance correction).

Key properties:
- **Scoped**: binds to a specific workflow, token, amount, and recipient
- **Single-use**: consumed atomically with the custody movement
- **Time-bounded**: has start and optional expiry timestamps
- **Auditable**: full provenance chain from grant through execution to
  consumption, captured as evidence
- **Two consumption models**: single-claim (one resolution) or related-claims
  (batch, per-member tracking)

The force-authorisation path diverges from the standard path only at the
resolution step, then rejoins the same settlement and finalization flow.

---

## 12. Evidence & Audit Trail

Every step of the lifecycle produces deterministic, verifiable evidence:

| Evidence Type | Captured At | Contents |
|--------------|-------------|----------|
| `escrow-created` | Phase 0 | Token, amount, fee, resolver, deadlines, yield module |
| `dispute-raised` | Phase 1 | Workflow, caller, resolver, dispute level |
| `resolution-submitted` | Phase 2 | Decision (release/refund), reasoning, alternatives |
| `escalation` | Phase 3 | Prior pending archived, new level, new resolver |
| `settlement-executed` | Phase 4 | Final direction, amounts, yields, claimable entries |
| `force-authorisation-*` | Governance | Grant, execute, consumption provenance |

All evidence is:
- **Deterministic**: same inputs → same evidence
- **Content-addressed**: identified by canonical SHA-256 hash
- **Immutable**: never modified after creation
- **Self-hashed**: linked into an evidence chain with cursor integrity

---

## 13. Scenario Coverage

The lifecycle is tested across automated scenarios:

| Category | What It Tests |
|----------|---------------|
| Baseline | Honest resolution, pending settlement execute |
| Deadline edge cases | Settlement/escalation race at window boundary |
| Kleros integration | Escalation chain, pending fallback, final-round behavior |
| Premature rejection | Settlement during open window correctly rejected |
| Fraud races | Window raced by fraudulent resolver |
| Force-authorisation | Grant-execute-consume lifecycle, expiry enforcement |
| Superseded pending | Fallback execution after escalation, repeated keepers |
| Evidence | Evidence rejected after settlement attempt |

---

## 14. Data Structures

### PendingSettlement
```
{:exists           boolean    ; true when a deferred decision exists
 :is-release       boolean    ; true → release, false → refund
 :appeal-deadline  integer    ; block timestamp for execution eligibility
 :resolution-hash  string     ; bytes32 hex}
```

### EscrowTransfer (key fields)
```
{:token             keyword   ; token identifier
 :from              string    ; sender
 :to                string    ; recipient
 :amount-after-fee  integer   ; net held amount
 :dispute-resolver  string    ; current resolver (or nil)
 :escrow-state      keyword   ; one of six states
 :auto-release-time integer   ; 0 = disabled
 :auto-cancel-time  integer   ; 0 = disabled
 :resolution        map       ; decision metadata}
```

### World State (key fields)
```
{:escrow-transfers               {workflow-id → EscrowTransfer}
 :pending-settlements            {workflow-id → PendingSettlement}
 :superseded-pending-settlements {workflow-id → [SupersededEntry]}
 :total-held                     {token → integer}
 :claimable-v2                   {workflow-id → ...}
 :dispute-timestamps             {workflow-id → integer}
 :dispute-levels                 {workflow-id → integer}
 :module-snapshots               {workflow-id → ModuleSnapshot}}
```

---

## 15. Summary

The protocol provides a complete escrow and dispute resolution lifecycle:

1. **Escrows** hold funds in a `pending` state until release, cancel, or dispute
2. **Disputes** transition to a `disputed` state with evidence windows and
   resolver assignment
3. **Resolutions** create pending settlements with configurable appeal windows
4. **Appeals** escalate to higher-tier resolvers (up to Kleros as final round)
5. **Settlement** executes when the appeal window expires, transitioning to
   terminal state
6. **Automated keepers** enforce deadlines when participants do not act
7. **Force-authorisation** provides governance override for exceptional cases

The entire lifecycle is simulation-tested, invariant-checked, and
evidence-captured for independent verification.
