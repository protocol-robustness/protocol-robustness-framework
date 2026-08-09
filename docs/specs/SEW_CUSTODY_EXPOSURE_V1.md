# sew-custody-exposure.v1

**Evidence profile for custody state observable when a pending settlement reaches
its settlement deadline and is executed or finalized.**

## Ownership

| Layer | Responsibility |
|---|---|
 | PRF core | Ledger replay (`replay-held-adjustment-state`), summaries (`final-held-summary`), artifact construction (`rebuild-held-custody-artifacts`), closed-form checks (`held-custody-closed-form-checks`), force-authorisation validation (`verify-authorisation-usable`), evidence envelope validation (`valid-envelope?`) |
| Sew | Pending-settlement lifecycle, appeal-deadline semantics, terminal world state, protocol-specific trace projection (`trace-end-projection`), live custody mutation (`update-ledger-index`) |
| `prf-ef-review-packet.v1` | Packages the resulting evidence for inspection; does not redefine custody semantics |

The relationship between Sew's live custody mutation (`update-ledger-index` in `accounting.clj`)
and core's replay reconstruction (`replay-held-adjustment-state`) is documented in a comment
on the Sew function. The existing test `held-custody-closed-form-checks-pass-on-valid-artifacts`
characterises the current equivalence guarantee.

`held-custody-closed-form-checks` has two arities: the one-arity form verifies the
artifact surface alone (hash, schema, amount/artifact well-formedness, delta,
non-negative, predecessor, sequence); the two-arity form
`held-custody-closed-form-checks(adjustments, artifacts)` additionally verifies
ledger↔artifact completeness (`ledger-artifact-bijection`,
`ledger-artifact-order`), replayed reason/position policy, and attribution
shape/requirement. This packet uses the two-arity form so that custody assurance
covers ledger completeness, not only artifact-chain integrity.

## Evidence contract

The following values are resolved from existing benchmark or scenario outputs.
No new artifact fields are introduced.

| Field | Source | Resolution |
|---|---|---|
| Workflow identifier | event `:params :workflow-id` on `execute_pending_settlement` | Direct trace lookup |
| Token | union of adjustment `:token`, `:total-held`, `:held-ledger/index :by-token`, and escrow/pending-settlement records | **Required verification procedure:** compare every token represented by any custody view; absence from one representation is itself reviewable. This packet does not yet include a dedicated one-representation-only corruption test. |
| Settlement deadline | world `:pending-settlements {wf-id :appeal-deadline}` | Direct world lookup |
| Execution event | trace entry for `execute_pending_settlement` | Direct trace lookup |
| Ordered held adjustments | `:held-adjustments` sorted by `:held-adjustment/id` | Derived from world |
| Replayed ledger state | `replay-held-adjustment-state(adjustments)` | Core function call |
| Final held summary | `final-held-summary(adjustments, index, total-held)` | Core function call |
| Terminal Sew projection | `trace-end-projection(world)` → `:money-movement-summary` | Sew projection call |
| Force-authorisation reference | `:force-authorisations` / `:force-authorisations/consumed` | Direct world lookup |
| Closed-form check results | `held-custody-closed-form-checks(adjustments, artifacts)` | Core function call |
| Source artifact hashes | `:artifact/hash` from each `:held-artifacts` entry | Direct artifact lookup |

## Custody-validation classification

Custody evidence has three mutually exclusive outcomes. Reviewers and automated
assurance consumers must use `:classification` (or the
`custody-validation-pass?` predicate), not `:holds?` alone.

| Classification | Meaning | Assurance outcome |
|---|---|---|
| `:evaluated-pass` | A declared-complete, zero-origin adjustment ledger was checked and all required reconciliation/closed-form checks passed. | Positive custody assurance. |
| `:evaluated-fail` | Validation ran and at least one check failed, including a declared-complete ledger whose asserted zero-origin opening is invalid. | Negative custody assurance; inspect `:violations`. |
| `:not-evaluated-incomplete-history` | The world does not declare a complete ledger, or opening evidence is genuinely unavailable. A consistent available prefix does not establish total custody completeness. | No custody assurance. |

`held-custody-closed-form?`, `held-adjustments-reconstruct-total-held?`, and
`held-artifacts-derived-from-adjustments?` report `:holds? false` for the third
state, so generic invariant aggregation cannot classify incomplete evidence as
passing. A positive result requires:

```clojure
(custody-validation-pass? result)
;; equivalent to:
(and (= :evaluated-pass (:classification result))
     (= :evaluated (:status result))
     (true? (:holds? result)))
```

The completeness declaration is made by Sew `init-world` only for zero-origin
worlds whose custody mutations pass through `acct/adjust-held`, which appends
an adjustment and derived artifact for each mutation. Any direct write to
custody views must clear `:held-adjustments/complete?`; otherwise evaluated
reconciliation fails rather than presenting an incomplete history as complete.

This is a construction-discipline boundary, not a proof that every unlogged
write is detectable from a terminal world alone: compensating unlogged writes
could restore terminal materialized views. The declaration is backed by the
static direct-write gate; stronger assurance requires reconciliation at
intermediate world states as well as the terminal state.

## Reviewable claims

> **Trace scope:** Claims marked trace-scoped below are verifiable from events,
> world states, and invariants within a single scenario trace. They do not assert
> properties across independent execution runs or protocol upgrades.

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

The replayed totals and positions reconcile with the terminal world state's held
ledger.

**Verification:** Compare `final-held-summary(adjustments, index, total-held)` against
the terminal world's `:total-held` and `:held-ledger/index` to confirm token totals
match by all available derivation paths. The Sew-specific trace projection
(`trace-end-projection`) is an additional rendering; the terminal world state is
the authoritative source.

### 3. Adjustment conservation

For each relevant token, account, position, owner, and workflow dimension, the final
totals equal the initial totals plus the complete ordered adjustment sequence.

**Verification:** The `:local-delta` and `:sequence-replay` checks from
`held-custody-closed-form-checks` verify conservation across every individual
adjustment and the full sequence.

### 4. Deadline execution traceability (trace-scoped)

The custody-changing settlement action is linked to the pending settlement and its
applicable deadline evidence. The temporal guard that enforces the deadline is a
PRF-core rule, not merely a Sew state-machine check.

**Required verification procedure:** Obtain the deadline from
`:pending-settlements[workflow-id]` in the world immediately preceding the
accepted `execute_pending_settlement` event. Determine that pre-state by
canonical trace order—`(event-time, seq)` or the trace's explicit order—not
timestamp alone. The event must have a successful outcome, a corresponding
custody/state transition, and a passing `:deadline-enforcement` result. At equal
timestamps, a prior challenge or escalation can change the applicable
pending-settlement state; event presence alone is not acceptance evidence. The
packet does not yet contain a dedicated same-timestamp ordering test, so this is
a required procedure rather than a separately validated packet property.

### 5. Authorisation binding

Where force-authorisation is required, the consumed authorisation scope binds the
executed custody movement and precedes its execution.

**Verification:** `verify-authorisation-usable` validates that the authorisation
record exists, is active, in-window, and has a scope-hash matching the custody
adjustment's scope. The `valid-envelope?` check confirms the evidence ordering
grant → execution → custody movement. The consumption registry entry links the
auth ID to the held adjustment ID.

### 6a. Terminal escrow-state immutability (trace-scoped)

After the settlement event, the workflow is in a terminal escrow state. The Sew
state machine rejects state transitions on a terminal-state workflow.

**Verification:** The trace entry for `execute_pending_settlement` transitions the
workflow to a terminal escrow state (`:released` or `:refunded`), and the
`terminal-states-unchanged?` cross-world invariant verifies that its escrow state
cannot later change. This is the demonstrated portion of claim 6.

### 6b. Post-finality custody immutability (trace-scoped; not yet demonstrated)

No later held adjustment may affect the finalised workflow.

**Required verification procedure:** Check the adjustment suffix after the
terminal transition for matching `:held/workflow-id`. `terminal-states-unchanged?`
does not perform this check. In the current concrete example settlement is the
final event, so the empty suffix is only a vacuous observation; a rejected
custody-affecting post-finality action and a dedicated adjustment-suffix check
are required before this subclaim is demonstrated. If the protocol permits
post-finality state change via governance override, both subclaims must be
scoped to the standard settlement path.

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
1–5 and 6a above. Claim 6b remains unevidenced by this final-event trace.

## Review gaps

- The `force-authorisation-custody-v1` benchmark exercises settlement-deadline
  finalization via `execute_force_authorized_action` + `execute_pending_settlement`.
  Claims 1–4 and 6 are path-independent in definition, but this packet demonstrates
  them only for the force-authorised scenario. Ordinary settlement requires a
  separate trace, or evidence that both paths invoke the same custody-finalisation
  primitive under equivalent inputs. Claim 5 applies only where force-authorisation
  is exercised. Claim 6b additionally requires a non-vacuous post-finality
  adjustment-suffix test.
