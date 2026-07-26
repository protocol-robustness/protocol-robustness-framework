# sew-custody-exposure.v1

**Evidence profile for custody state observable when a pending settlement reaches
its settlement deadline and is executed or finalized.**

## Ownership

| Layer | Responsibility |
|---|---|
| PRF core | Ledger replay (`replay-held-adjustment-state`), summaries (`final-held-summary`), artifact construction (`rebuild-held-custody-artifacts`), closed-form checks (`held-custody-closed-form-checks`), force-authorisation validation (`verify-authorisation-usable`), evidence envelope validation (`valid-envelope?`) |
| Sew | Pending-settlement lifecycle, appeal-deadline semantics, terminal world state, protocol-specific trace projection (`trace-end-projection`) |
| `prf-ef-review-packet.v1` | Packages the resulting evidence for inspection; does not redefine custody semantics |

## Evidence contract

The following values are resolved from existing benchmark or scenario outputs.
No new artifact fields are introduced.

| Field | Source | Exists? |
|---|---|---|
| Workflow identifier | event `:params :workflow-id` on `execute_pending_settlement` | Yes |
| Token | adjustment `:token` or world `:total-held` keys | Yes |
| Settlement deadline | world `:pending-settlements {wf-id :appeal-deadline}` | Yes |
| Execution event | trace entry for `execute_pending_settlement` | Yes |
| Ordered held adjustments | `:held-adjustments` from world state, sorted by `:held-adjustment/id` | Yes |
| Replayed ledger state | `replay-held-adjustment-state(adjustments)` | Yes |
| Final held summary | `final-held-summary(adjustments, index, total-held)` | Yes |
| Terminal Sew projection | `trace-end-projection(world)` → custody-relevant portion of `:money-movement-summary` | Yes |
| Force-authorisation reference | world `:force-authorisations` and `:force-authorisations/consumed` (when applicable) | Yes |
| Closed-form check results | `held-custody-closed-form-checks(artifacts)` | Yes |
| Source artifact hashes | `:artifact/hash` from each `:held-artifacts` entry | Yes |

## Reviewable claims

### 1. Replay determinism

Replaying the committed held adjustments produces the reported terminal held-ledger
state.

```
replay(trace/adjustments) = trace/world :total-held
replay(trace/adjustments) = trace/world :held-ledger/index
```

**Verification:** Compare `replay-held-adjustment-state(adjustments)` against the
terminal world's `:total-held` and `:held-ledger/index`.

### 2. Projection reconciliation

The replayed totals and positions reconcile with the custody-relevant portion of
the committed terminal Sew state projection.

**Verification:** Compare `final-held-summary(adjustments, index, total-held)` against
`trace-end-projection(world)` `:money-movement-summary` to confirm token totals match.

### 3. Adjustment conservation

For each relevant token, account, position, owner, and workflow dimension, the final
totals equal the initial totals plus the complete ordered adjustment sequence.

**Verification:** The `:local-delta` and `:sequence-replay` checks from
`held-custody-closed-form-checks` verify conservation across every individual
adjustment and the full sequence.

### 4. Deadline execution traceability

The custody-changing settlement action is linked to the pending settlement and its
applicable deadline evidence.

**Verification:** The trace entry for `execute_pending_settlement` references the
workflow ID that the pending settlement record identifies. The pending settlement's
`:appeal-deadline` precedes the event timestamp. No contemporaneous `escalate_dispute`
or `challenge_resolution` exists at that timestamp for the same workflow.

### 5. Authorisation binding

Where force-authorisation is required, the consumed authorisation scope binds the
executed custody movement and precedes its execution.

**Verification:** `verify-authorisation-usable` validates that the authorisation
record exists, is active, in-window, and has a scope-hash matching the custody
adjustment's scope. The `valid-envelope?` check confirms the evidence ordering
grant → execution → custody movement. The consumption registry entry links the
auth ID to the held adjustment ID.

### 6. Post-finality immutability

No later transition mutates the custody state after the settlement event.

**Verification:** Check that all subsequent trace entries for the same workflow
produce no new held adjustments with that workflow ID. This is a trace property
that presupposes no re-execution of `execute_pending_settlement` or other custody-
changing action on the same workflow. If the protocol permits post-finality state
change via governance override, this claim is limited to the standard settlement
path and must be documented as such.

## Concrete example

The `force-authorisation-custody-v1` benchmark instantiates this profile via its
`DR-FA-001-force-authorisation-basic` scenario:

| Event | Time | Action |
|---|---|---|
| seq 0 | 1000 | `create_escrow` |
| seq 1 | 1060 | `raise_dispute` |
| seq 2 | 1100 | `grant_force_authorization` (duration 3600) |
| seq 3 | 1120 | `execute_force_authorized_action` — creates pending settlement, deadline = 1120 + 120 = 1240 |
| seq 4 | 1300 | `execute_pending_settlement` — deadline passed, custody finalized |

At seq 4, the settlement deadline (1240) has passed and `execute_pending_settlement`
records the terminal held adjustment. The resulting world state contains the
full adjustment ledger, artifacts, and materialized views that instantiate claims
1–6 above.

## Review gaps

- The `force-authorisation-custody-v1` benchmark exercises settlement-deadline
  finalization via `execute_force_authorized_action` + `execute_pending_settlement`.
  This benchmark instantiates the **authorisation-bound custody-finalisation**
  portion of the profile; it is not evidence of the ordinary, non-force-authorised
  settlement-deadline path. A benchmark that tests settlement-deadline finalization
  without force-authorisation (a keeper executing a normal resolver's pending
  settlement) is not included in this packet. The custody mechanics are identical;
  the difference is only in the authorisation path (normal resolution vs.
  force-authorised). Reviewers should treat force-authorised and normal settlement
  custody as equivalent for claims 1–4 and 6, and apply claim 5 only when
  force-authorisation is exercised.
