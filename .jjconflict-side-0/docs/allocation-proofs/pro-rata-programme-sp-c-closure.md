# SP-C Closure — exact allocation programme slice

Implementation slice SP-C is closed for its first end-to-end programme
(allocate → validate → evidence → complete), with the exact-plan identity,
stage algebra, exact-set reconciliation, and an independently verifiable
receipt established **before** the runner was allowed to produce receipts.
The table below is the authoritative closure status.

## Closure status

Scope note: the **pro-rata-programme.v1 core currently executes one canonical
allocation per programme.** It is a single-allocation vertical slice; the
multi-allocation batch abstraction is not yet implemented (see Open).

| Item                                        | Status                                   |
| ------------------------------------------- | ---------------------------------------- |
| Canonical programme plan (`canonical-programme-plan`) | CLOSED                      |
| Programme plan identity (`programme-plan-root`) | CLOSED                               |
| `verify-programme-plan`                     | CLOSED (idempotent canonicalization)    |
| Stage vocabulary + per-stage status legality | CLOSED                                   |
| Exact-set reconciliation (planned=executed=receipt) | CLOSED (duplicate/missing/unexpected) |
| Receipt verifier before runner (`verify-programme-receipt`) | CLOSED            |
| Aggregate verdict derived, not trusted      | CLOSED (see below)                      |
| Runner producing verifier-accepted receipts | CLOSED                                   |
| Programme validation = exact-verifier result | CLOSED (SP-A + SP-B invariant)          |
| `:unsupported` propagates into programme semantics | CLOSED (see below)                |
| Execution settings cannot change programme semantics | CLOSED                       |
| Progress (SP-A) + execution budget wired    | CLOSED (operational, non-canonical)     |
| Cooperative programme cancellation          | OPEN C.4/P1                             |
| Operational execution report                | OPEN P1 (receipt is canonical-only)     |
| Hierarchical multi-allocation progress view | OPEN until batch programme              |
| Multi-allocation batch programme            | OPEN (v1 is single-allocation)          |
| Proof/statement/verification/admission adapters | OPEN SP-C.5 (deferred)              |
| Realized-statement / SP1 integration        | NOT YET CLAIMED (deferred)              |

## Receipt aggregates are DERIVED, not trusted

`verify-programme-receipt` independently derives every aggregate verdict field
from artifacts — not merely the constituent roots:

- `:request-root`, `:result-root`, `:validation-status`, `:validation-details`,
  `:evidence-root`, `:evidence-id` (constituent facts)
- `:stages` (reconstructed per-stage statuses, from artifacts)
- `:semantic/status` (`:pass` / `:fail` / `:unsupported` / `:error` /
  `:incomplete`)
- `:programme/status` (lifecycle terminal)
- `:summary` (`required-total`, `required-completed`, `failed`, `cancelled`,
  `unsupported`)
- `:exact-set-complete`

All are recomputed by `derive-programme-verdict` from the reconstructed stage
statuses plus the exact-set reconciliation — the same pure function the runner
uses — so an aggregate claim cannot diverge from its leaves. A tampered
`semantic/status`, `programme/status`, stage status, summary count, or
`exact-set-complete` is rejected even when the constituent roots are all intact
(covered by `each-derived-aggregate-verdict-field-is-the-verifier-derives`).

## `:unsupported` propagation

An exact-verifier `:unsupported` verdict flows into
`(:stage-statuses :validation)` → `:semantic/status :unsupported` and a
`summary :unsupported 1` — it is never flattened to `:failed` nor treated as
`:pass`. `derive-programme-verdict` is unit-tested on a validation `:unsupported`
leaf (`unsupported-validation-propagates-into-programme-semantics`), and the
runner preserves the vote end-to-end.

Current reachability note: on the live evaluator path
(`evaluate-pro-rata-allocation`) the admitted policy domain is a subset of the
exact-verifier's supported domain (payoffs admits tie-break `:input-order`
only; the verifier admits `:input-order` and `:canonical-id`), so a
verifier-`:unsupported` request is structurally unreachable through the existing
evaluator today. The propagation is load-bearing: the moment a policy expansion
is admitted, the aggregate semantics must (and will) report `:unsupported` and
non-`:pass` rather than silently reformulating or passing. This is exactly the
future-expansion failure mode the propagation protects.

## Single-allocation scope and the request-root-set invariant

v1 executes one canonical allocation per programme, so reconciliation checks
equality of per-stage ids — including the allocation/validation `request-root`.
For the future multi-allocation programme, the stronger invariant that MUST
hold is planned request-root**set** = executed request-root**set** =
receipt request-root**set** (the same semantic id under a different request
must not satisfy reconciliation). That is recorded as an OPEN multi-allocation
requirement, not claimed closed.

## SP-C.1 — programme plan and identity first

`resolver-sim.pro-rata.programme` establishes identity before the runner exists.

FROZEN into `programme-plan-root` (`hc/domain-hash "PROGRAMME_PLAN_V1"` over the
canonical plan):

- `programme/id`
- `:allocation-request-root` — exact allocation request root
  (`hc/domain-hash "PROGRAMME_ALLOCATION_REQUEST_V1"`)
- `:semantic-ids` — semantic allocation set/order (participant ids in request
  order)
- `:stages` — requested stages (`:requested` / `:not-requested` per stage)
- `:validation-profile-root`, `:statement-profile-root` (when requested),
  `:proof-profile-id`, `:admission-profile-id`

EXCLUDED (operational, dropped by canonicalization, verified by test):
`parallelism`, progress callbacks, worker pools, `host`, timestamps, `paths`,
`cancel-atom`. `canonical-programme-plan` is idempotent: an already-canonical
plan revalidates against its own frozen fields, so `verify-programme-plan`
round-trips without needing the raw request.

## SP-C.2 — stage vocabulary and exact-set reconciliation

Stage vocabulary (`valid-stage-status?`): not every status is legal for every
stage.

| stage        | allowed statuses                              |
| ------------ | --------------------------------------------- |
| allocation   | completed, failed, cancelled, error           |
| validation   | passed, failed, unsupported, error            |
| evidence     | completed, failed, cancelled, error           |
| statement    | completed, not-requested, failed, cancelled, error |
| proof        | completed, not-requested, failed, cancelled, error |
| verification | passed, not-requested, failed, unsupported, error |
| admission    | completed, not-requested, failed, cancelled, error |

`reconcile-programme-stages` and `reconcile-programme-ids` reject duplicate,
missing, and unexpected entries across planned = executed = recorded. Allocation
and validation legitimately share the request-root as their stage id, so
duplicate detection is staged (duplicate execution of a stage, not shared id).

## SP-C.3 — receipt verifier before the runner

`verify-programme-receipt` derives the receipt's semantic fields from the
execution artifacts (independent reconstruction), then compares against the
recorded claim:

- `request-root` — re-derived via `allocation-request-root`
- `result-root` — re-derived as `hc/domain-hash "PRO_RATA_EVALUATION_V1"` over
  `[:result :artifact/value]`
- `validation-status` / `validation-details` — re-derived via
  `programme-validation-result` (the exact verifier)
- `evidence-root` / `evidence-id` — re-derived by rebuilding the evidence
  envelope

The runner (`run-programme`) was then written to produce receipts this verifier
accepts. This mirrors the SP-B pattern: define the independently checkable
claim first.

## SP-C.4 — runner

`run-programme` executes plan → allocate → validate → evidence → complete,
emitting SP-A typed progress events (`:on-progress`) and reducing into a
caller-owned `:progress-atom`, and optionally binding an execution budget
(`:budget-permits`, via `resolver-sim.execution.budget`). statement / proof /
verification / admission are `:not-requested` until SP-C.5. This is a complete,
valuable end-to-end closure point on its own.

## The SP-C invariant (SP-A + SP-B tie-in)

`programme-validation-result` returns the exact verifier verdict for the exact
request/result pair, and it is the single authoritative validation verdict.
The test `execution-settings-cannot-change-programme-semantics` proves that
serial/parallel (budget), a progress atom, callbacks, and worker budget do not
change request-root, result-root, validation status/details, evidence-root, or
receipt semantic fields. Progress and budget are operational only and never
leak into programme identity.

## Verification note

- `test/resolver_sim/pro_rata/programme_test.clj` — 12 tests / 94 assertions:
  plan freezing, operational exclusion, plan-root stability across settings,
  `verify-programme-plan` (incl. idempotency), stage-vector legality, exact-set
  reconciliation (missing/unexpected/duplicate/deviated-id), runner receipt
  verification, tamper rejection, the validation-equals-exact-verifier
  invariant, the execution-settings invariance, and progress events/atom.
- Full battery across the eight touched suites:
  224 tests / 830 assertions / 0 failures / 0 errors.

## Open (SP-C.5 and beyond)

- Proof/statement/verification/admission adapters: add as a separate closure
  inside or immediately after SP-C, proving serial/parallel equivalence and
  receipt reconstruction **without** proof complexity first, then wiring the
  realized-statement/SP1 adapters.
- Rust/SP1 exact-verifier conformance is NOT YET CLAIMED (see SP-B P1 items:
  extended independent adversarial corpus, and a two-root
  `fairness-spec-root`/`conformance-corpus-root` via `domain-hash`).