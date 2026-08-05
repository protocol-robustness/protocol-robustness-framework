# ADR-0006: with-bounty — Framework-Contract Decisions

Status: Proposed
Date: 2026-08-04
Scope: framework-contract decisions for the generic `with-bounty` composition
(ADR-0005 Phase 6), preceding implementation.

Supersedes / relates to: ADR-0005 (substrate), `docs/proposals/WITH_BOUNTY.md`
(design note).

## Context

ADR-0005 Phase 6 promises a generic `with-bounty` composition as the first
post-review reference composition. The design note (`docs/proposals/WITH_BOUNTY.md`)
is deliberately scoped so that four separate objectives — proving the composition
model, implementing a bounty composition, extending Sew's custody and obligation
lifecycle, and demonstrating an extension ecosystem with independent assurance —
are not implemented as one pre-review obligation.

This ADR locks only the decisions that affect the framework contract. They are
more valuable before review than implementation volume. Decisions are recorded
with status, rationale, and consequences so the review can challenge the
contract rather than the volume.

## Decisions

### D1. Effect-contract versioning: add `:prf.effect/obligation-create.v2`

Status: **Decided (pre-review)**

Do **not** expand `:prf.effect/obligation-create.v1` in place.

Changing the semantic and schema commitment of a versioned effect in place
weakens the framework's most important convention: a versioned contract has
stable meaning. Pinned schema roots and tests are already dependencies, even
without public production artifacts.

Rationale:

- v1 remains the vocabulary of historical slash-distribution evidence.
- v2 carries the fuller obligation shape (token, owner, funding, subject,
  provenance) required by `with-bounty`.
- Schema roots are content-addressed (`effects/effect-schema-roots`); v1 and v2
  therefore have distinct, stable roots.

Consequences:

- `src/resolver_sim/economics/effects.clj` gains `obligation-create-v2-schema`
  in `effect-schema-maps`; `effect-schema-roots` grows a new entry. No existing
  root changes.
- A migration/normalisation function converts compatible v1 effects into the v2
  internal representation. `validate-effect` rejects a v2-shaped payload claimed
  as v1 (and vice versa).
- Structural tests pin both roots.

### D2. The base operation is a committed result, not slash-distribution ownership

Status: **Decided (pre-review)**

`with-bounty` consumes a committed base result:

```clojure
{:base {:plan/ref ...
        :result/schema ...
        :result/root ...}}
```

The generic evaluator never invokes Sew or slash distribution directly. The
slash-distribution reference fixture supplies a base *for the fixture only*.

Rationale:

- A later use over settlement, review completion, challenge resolution, or
  another economic action must not require revising a supposedly generic v1
  contract.
- Composition-over-committed-result preserves the base as authoritative and
  keeps the bounty from rewriting it.

Consequences:

- `with-bounty` evaluation takes `:base/result-root` as an input; base execution
  is the caller's responsibility (or the fixture's).
- `:basis {:source :base/result :field ...}` resolves against the committed
  base result projection.

### D3. Composition-plan identity: a dedicated plan composing over a base plan

Status: **Decided (pre-review)**

Introduce `:with-bounty-application-plan-v1`, which composes **over** a base
application-plan root rather than expanding `slash-distribution-application-plan.v2`:

```text
with-bounty plan
├── base application-plan root
├── base result root
├── bounty effect root
├── combined effect-set root
└── composition preconditions
```

Rationale:

- `slash-distribution-application-plan.v2` retains its committed meaning; two v1
  implementations must not disagree about whether extension effects are part of
  its preimage.
- The with-bounty plan is a composition artifact, not a slash-distribution
  artifact. Its identity commits the base-plan root, base-result root, bounty
  effect root, combined effect-set root, and creation preconditions.

Consequences:

- New domain tag `:with-bounty-application-plan-v1` in
  `src/resolver_sim/hash/canonical.clj`.
- The plan validates before mutation: capability resolution from the frozen
  snapshot, effect schema validation, adapter support, funding availability,
  creation preconditions, deterministic obligation-id recompute.

### D4. Transaction and failure semantics

Status: **Decided (pre-review)**

Fail-before-mutation is the default for every condition in the design note §11.
The policy declares one of:

- `:bounty/failure-mode :base-independent` — base remains valid, bounty step
  fails or is skipped without bounty mutation;
- `:bounty/failure-mode :transaction-fatal` — any bounty failure aborts the whole
  operation.

An ineligible bounty is **recorded as `:skipped`**, never omitted. Unsupported
effects, malformed eligibility results, insufficient funding, and failed
preconditions fail before protocol mutation.

Rationale: matches ADR-0005 §8 step-local failure semantics and the
fail-before-mutation admissibility model (ADR-0005 §9).

### D5. Boundary: generic obligations vs Sew custody and lifecycle

Status: **Decided (pre-review)**

The composition owns **creation-time** rules:

- deterministic obligation identity;
- whether an obligation effect should be created;
- amount and declared funding source;
- creation preconditions (funding availability, adapter support);
- no duplicate creation for the same composition operation.

A generic obligation / bounty-payable **lifecycle** owns:

- paid / cancelled / outstanding reconciliation;
- partial payment;
- release of backing;
- terminal settlement conservation.

The existing `bounty_payable` and `bounty_payable_backing` artifacts already
provide this lifecycle (`:pending-backing :backed :settled :cancelled` /
`:committed :consumed :released`). Sew enforces lifecycle through that
machinery, and any reserve custody reservation uses the canonical held-adjustment
path (`add-held` via `resolver-sim.accounting.held-adjustment`). Extensions and
the generic compositor must never invoke a custody mutation directly.

Rationale: one composition cannot own both creation and full lifecycle without
duplicating existing payable/backing semantics and expanding the audit surface.

Consequences:

- `with-bounty` owns creation invariants only.
- Broader lifecycle work is a separate roadmap item, undertaken only if
  `bounty_payable`/`bounty_payable_backing` do not already provide it.

### D6. What "independent verification" means

Status: **Decided (pre-review)**

A separate extension package in this repository that invokes shared framework
code is a **separate verifier package** / **secondary verifier capability** /
**externally distributable verifier capability** — **not** "independent".

Reserve **independent verifier** / **independently verified** for a verifier
with meaningful implementation independence: a clean-room implementation,
another language/runtime, or at minimum no reuse of producer evaluation code.

Rationale: this follows the distinction already established by the repository's
conformance work (producer replay vs independent verification, ADR-0005 §11).
Evidence must not label implementation replay as independent verification.

Consequences:

- In-repo verifier packages ship under the secondary-verifier terminology.
- An "independently verified" classification (and the `:extension/status`
  dimension, ADR-0005 §12) is earned only by external/clean-room verification,
  deferred to Stage D.

### D7. Fixture package earlier, released package later

Status: **Decided (pre-review)**

- **Stage B:** the executable fixture extension (eligibility + amount
  capabilities) lives under the test/fixtures boundary (test classpath), not
  under `src`. It is a fixture package, not a shipped `:example-org/...` built-in.
- **Stage C/D:** the sealed, distributable reference package and lockfile land in
  an `examples`/`reference-packages` root (with a `deps.edn` path) or as a
  distribution artifact, never authored under `src` as a built-in.

Rationale: keeping `:example-org/...` production manifests out of the main `src`
tree preserves the core-versus-example distribution boundary.

### D8. Verification is delivered per phase

Status: **Decided (pre-review)**

Structural verification accompanies each artifact as it lands:

- Phase 1 — policy-root verifier;
- Phase 2 — invocation and evaluation-envelope verifier;
- Phase 3 — effect and plan verifier;
- Phase 4 — transition-evidence verifier.

Implementation replay and verifier composition (disagreement → `:disputed`)
follow in Stage C. Structural verification is never deferred to a single late
phase.

## Artifact register

Domain tags added to `src/resolver_sim/hash/canonical.clj`:

- `:with-bounty-policy-v1`
- `:with-bounty-invocation-v1`
- `:with-bounty-obligation-v1`
- `:with-bounty-composition-v1`
- `:with-bounty-application-plan-v1`
- `:with-bounty-transition-evidence-v1`

Effect contract: `:prf.effect/obligation-create.v2` (via existing
`PRF_EFFECT_CONTRACT_V1` tag; root committed in `effects/effect-schema-roots`).

Parameter attribution: reused from `resolver-sim.util.attribution`; `with-bounty`
declares allowed parameter addresses and commits a projection of the attribution
context root — no second attribution primitive.

## Tranche disposition

| Current phase | Timing | Disposition |
|---|---|---|
| Phase 1 — policy and identity | After review; decisions before | Good scope after D1 and D2 |
| Phase 2 — evaluation | After review | Core of the Stage B vertical slice; reuse attribution |
| Phase 3 — plan and conservation | Split | Creation/preflight after review (D5); lifecycle conservation later |
| Phase 4 — Sew mapping | After review | High-value once generic plan semantics are stable |
| Phase 5 — verification | Split across phases | Structural per phase (D8); replay in Stage C |
| Phase 6 — ecosystem artifact | Later roadmap | Premature before stable implementation and external consumption |

## Pre-Stage-C boundary review (review commitments)

Five boundaries were reviewed before Stage C; the review conclusions are
committed here.

### B1. Obligation identity vs duplicate identity vs plan root

These are deliberately separate identifiers with distinct equivalence relations:

| Identifier | Intended equivalence relation |
|---|---|
| `bounty-obligation-id` | Identity of the economic obligation. Two obligations are the same iff the versioned projection `[:bounty-payable operation-root bounty-id recipient token amount policy-root]` is equal. |
| `no-duplicate-creation key` | Exclusivity scope `[operation-root bounty-id recipient]` (design note §13): at most one live obligation per scope. Coarser than the obligation id — a different token or policy yields a different obligation id but the same scope, so duplicate creation is rejected. |
| `application-plan-root` | Identity of one validated application attempt: the content-addressed plan committing policy root, base roots, resolution root, adapter, effects, and preconditions. Two materially different plans never share a root. |

The Sew adapter enforces no-duplicate-creation at application time via a
live-obligation index keyed on the scope, in addition to the per-obligation
idempotency key. Retry idempotency (same plan root) and duplicate rejection
(different obligation, same scope) are therefore both enforced.

### B2. Atomicity between payable, backing, claimable, custody

Application is a pure reduction over an immutable world: no partial state is
ever observable if any effect fails (test `mid-application-failure-is-atomic`).
Additional guarantees:

- claimability derives from the created, backed payable (`record-claimable-v2`
  is called with the payable's beneficiary and amount), never written
  independently;
- transition evidence binds the exact held-adjustment artifact
  (`{:held-adjustment/id ... :artifact/hash ...}`), not merely its amount or
  account;
- idempotent replay verifies the existing payable/backing/claimable/custody
  state before returning success; drifted state fails
  (`with-bounty-state-drift`).

### B3. Application-plan completeness

The plan commits every field whose change could alter protocol mutation:
policy root, base-operation root, base-result root, base-plan root,
`extensions-resolution-root`, adapter-support declaration, the ordered effect
set and its roots, combined effect-set root, effect-schema roots, obligation
id, no-duplicate-creation key, `declared-maximum`, `funding-available`,
preconditions, and idempotency key. Two materially different Sew transitions
cannot validate against the same plan root: the adapter-support declaration and
resolution root are part of the committed preimage, and preflight rejects a
plan whose committed adapter differs from the applying adapter.

### B4. Structural verifier independence from constructors

Structural verifiers recompute from artifact bodies rather than trusting
constructor caches:

- `verify-with-bounty-plan` recomputes effect roots, combined effect-set root,
  obligation id, no-duplicate key, and all four preconditions from the
  committed effects and committed inputs, and enforces an exact committed shape
  (unknown top-level keys rejected);
- reconciliation verifiers (`verify-receipt-with-plan`,
  `verify-transition-with-plan`, `verify-application-world`,
  `verify-transition-binds-world`) cross-check artifacts against referenced
  artifacts and fail closed on missing referenced evidence;
- canonical hashing and schema predicates are the only primitives reused;
  no constructor-assembled projection is trusted.

### B5. Frozen-resolution commitment

Exactly one run-level `:extensions/resolution-root` is computed by the
evaluator and committed identically into the receipt, the application plan,
and (via the plan root) the transition evidence. No artifact re-resolves
capabilities from mutable registry state: evaluation resolves against the
supplied frozen extension-map, and verification reconciles committed roots only.
`verify-receipt-with-plan` rejects a receipt whose resolution root differs from
the plan's.

## C1 — implementation replay

Implemented. `resolver-sim.economics.with-bounty.replay/replay-with-bounty`
re-runs the exact sealed eligibility and amount implementations against the
committed inputs (`:replay/inputs` captured by the evaluator) and reconciles
the receipt, plan, and effect by full value comparison. Results are classified
explicitly `:implementation-replay` — never independent verification.
Replay stops at the capability boundary: protocol transition reproduction is
not claimed (the Sew runtime and state fixture are not sealed). Covered by
`resolver-sim.economics.with-bounty.replay-test`.

C2 (`:disputed`, verifier composition) and C3 (released manifest and lockfile)
are intentionally not part of this change.

## Acceptance bar (Stage B vertical slice)

`with-bounty` reaches its first production-quality composition only when:

- eligibility and amount capabilities resolve through a frozen transitive
  extension snapshot (fixture package);
- the exact package, schema, dependency, effect-contract, and runtime roots are
  committed;
- ineligible bounty steps are recorded as `:skipped`, not omitted;
- the base result is consumed as a committed result, not owned by the evaluator;
- the composition emits only validated typed effects under
  `:prf.effect/obligation-create.v2`;
- adapter support is checked before mutation;
- Sew mapping uses canonical payable/backing and held-custody paths;
- retry cannot create a duplicate economic obligation;
- every artifact has its structural verifier;
- the vertical slice is idempotent and fail-before-mutation is tested end-to-end.

**Stage B status: implemented.** The vertical slice is landed and exercised by
`resolver-sim.economics.with-bounty.{stage-a,application-plan,verification}-test`
and `resolver-sim.protocols.sew.with-bounty-test` (registered in the canonical
`./scripts/test.sh unit` target). It uses the fixture package, committed base
result, v2 obligation effect, dedicated application plan, Sew canonical
`add-held`/payable/backing paths, idempotent application, and per-artifact
structural verification.

The ecosystem acceptance items (independent verifier, compatibility attestation,
benchmark pack, second adapter/use case) are explicitly **not** part of Stage B.

## Open questions

- Exact `with-bounty-application-plan.v1` projection fields.
- Whether a generic `:economics/funding` capability is needed before the first
  external consumer, or declarative funding references suffice (v1 defaults to
  declarative).
- Classloader/namespace-ownership checks for sealed fixture packages (ADR-0005 §5)
  in a test-classpath context.
