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

## Pre-C2 specification review (gaps closed)

This section folds in the pre-C2 review commitments. Items are numbered R1–R12
for traceability; acceptance properties and stage changes follow.

### R1. Verification basis is a first-class committed artifact

Before two verifier outputs can legitimately disagree, they must be proven to
have evaluated the exact same thing. Every verifier attestation therefore
carries `with-bounty-verification-basis.v1`, a versioned, content-addressed
artifact committing:

- subject / package / artifact roots being verified;
- verification contract and version;
- entrypoint and invocation parameters;
- dependency / lockfile root;
- runtime / environment root;
- benchmark or vector-set root;
- resource-limit profile;
- expected public-result schema;
- classification policy / compatibility profile.

Implemented as `resolver-sim.economics.with-bounty.verification-basis`.
Two attestations with different basis roots are classified `:basis-mismatch`
and excluded from status derivation — apparent disagreement caused by different
inputs, versions, environments, policies, or limits is never a dispute.

### R2. Disagreement taxonomy (complete)

| Situation | Classification |
|---|---|
| Verifier cannot execute: unsupported runtime/version | `:incompatible` |
| Timeout, resource exhaustion, missing dependency | `:inconclusive` |
| Verifier used a different basis or subject | `:basis-mismatch` / invalid attestation |
| Implementation disagrees with pinned replay vectors | `:verifier-nonconforming` |
| Same implementation/identity emits conflicting results | `:equivocation` |
| Conforming verifiers disagree; output nondeterministic | `:replay-nondeterministic` |
| Two conforming implementations conflict over an identical frozen basis | `:disputed` |
| Attestation signature/schema/provenance invalid | excluded from the derivation set |

Critical rule: `:disputed` arises **only** from conflicting, valid, conforming
attestations over an **identical verification basis**. A failed replay by the
secondary implementation is normally evidence against that implementation
(`:verifier-nonconforming`), not evidence that the subject is disputed.

### R3. Verifier-set authority and independence

Status derivation needs explicit authority rules:

- accepted verifier capability/version ranges;
- verifier identity and signing authority;
- implementation root and build root;
- **implementation-lineage / independence class** — mandatory field; without it
  ten signatures over one binary could masquerade as ten-verifier consensus;
- key rotation and revocation;
- duplicate-attestation handling (canonical dedup);
- multiple identities running the same implementation count once;
- equivocation treatment;
- minimum cardinality required to derive each status;
- canonical ordering and deduplication of attestations.

The in-repo verifier is described as a **secondary / diverse implementation**,
never independent: it may share specifications, dependencies, canonical
encoding, fixtures, or developer assumptions with the primary implementation.

### R4. `:disputed` operational and lifecycle semantics

`:disputed` consumers must know what it does. Specified consequences:

- blocks bounty payment and obligation release;
- blocks certification / package admission;
- prevents a result from becoming final;
- requires manual adjudication;
- permits additional verifier attestations;
- is superseded only by a later status-derivation artifact;
- remains permanently visible after resolution;
- does not alter already-executed effects.

Model: attestations are immutable; a deterministic status-derivation artifact
commits the attestation set; a later derivation may supersede it with a larger
or corrected set; the original disputed state remains auditable; resolution
requires an explicit rule or adjudication artifact — never mutation of the
earlier status. Technical-correctness disputes are **fail-closed**: a conforming
minority verifier may have found a real ambiguity, so a 2-of-3 majority does
not outvote a dispute.

### R5. Canonical public-result contract

Verifier comparison requires an exact public result, not a coarse boolean:

- frozen public-result schema (included and excluded fields);
- canonical projection; diagnostics, stack traces, timestamps, paths, and
  implementation-specific metadata excluded;
- finding identifiers and ordering;
- error and rejection taxonomy (stable machine-readable classifications);
- equality is **canonical-byte equality** over the projection;
- missing and extra public fields are failures.

Implemented as `resolver-sim.economics.with-bounty.public-result`.

### R6. Source-to-executable correspondence (C3)

C3's package must bind the executable back to source and build instructions:

- source-tree root;
- build-recipe / toolchain root;
- executable root;
- source-to-executable build receipt;
- build environment root;
- reproducibility classification: `reproducible | attested | opaque`;
- publisher identity/signature;
- package identifier/version and immutable package root.

A sealed executable without this link is reproducible as an input, not
auditable as a product of the reviewed source.

### R7. Hermetic environment contract (C3)

Replay must constrain hidden inputs beyond dependency resolution: network
access, environment variables, filesystem state and working directory, locale
and timezone, system clock, randomness, thread/concurrency assumptions, native
libraries, OS/architecture where material, classpath ordering, resource
enumeration order, JVM/Clojure runtime details, dynamic namespace loading and
code evaluation. The executable manifest declares required capabilities; replay
fails closed when undeclared ambient reads are observed.

### R8. Namespace ownership admission and runtime enforcement (C3)

Ownership checks cover: duplicate namespace declarations, core namespace
shadowing, preloaded namespace capture, transitive dependencies defining
reserved namespaces, resource-path collisions, entrypoints resolving to a
dependency rather than the sealed package, dynamic `require`/`load-string`/
reflection/generated class names where relevant, and parent-first vs
child-first classloader behaviour. The entrypoint-origin check binds the
resolved var/class to the package root, not merely its namespace name.

### R9. Compatibility attestation scope (C3)

A Sew adapter compatibility attestation binds: adapter package root and version,
reference package root, PRF version/runtime, lockfile and environment roots,
compatibility profile, test/benchmark corpus root, complete result root, and
verifier identity/implementation root. It states what it does not prove:
passing the corpus establishes compatibility with that profile and corpus only —
not general correctness, security, or correctness for every Sew configuration.
The same attestation schema is used internally now so Stage D does not migrate
the trust model.

### R10. Benchmark pack moves to C3

`with-bounty-v1.edn` ships in C3 (external execution stays in Stage D). The pack
pins a corpus with: passing examples; every stable rejection/classification;
tampered package and lockfile cases; runtime incompatibility; namespace
collisions; dependency substitution; basis mismatch; timeout/resource-limit
cases; deliberate primary/secondary verifier disagreement; golden public-result
bytes. Without it, the stable replay contract has no released executable
expression.

### R11. Application-plan projection is frozen

Because verifier attestations and package roots will bind roots derived from
`with-bounty-application-plan.v1`, its projection is frozen to the B3 field set
(see `plan-hash-projection-fields`). Changing it requires a v2 domain/version.
A golden-preimage test pins the exact projection table so a change fails the
test and forces a conscious decision.

### R12. Declarative funding v1 semantics

Declarative funding defines enough semantics to prevent false claims:

- asset and denomination identity; amount and scale;
- funding source/root;
- whether funds are declared, reserved, escrowed, or proven available;
- freshness / cut-point of availability evidence;
- relationship between funding and obligation creation;
- underfunding and overfunding behaviour; refund/release path; expiry;
- idempotency and duplicate-application handling; atomicity expectations;
- what the verifier may legitimately claim about funding.

The safe v1 claim: *the plan commits a declared funding source and required
amount; it does not prove custody, availability, reservation, or successful
transfer unless separate evidence is supplied.* That boundary is explicit
before an external consumer interprets "funded" more strongly.

## C2/C3 acceptance properties

### C2 — status derivation

- status derivation is deterministic and permutation-invariant;
- duplicate attestations do not alter status;
- an unrelated-basis attestation cannot create a dispute;
- an invalid or nonconforming verifier cannot create a dispute;
- two identities using the same implementation do not establish diversity;
- equivocation is detected;
- adding a compatible agreeing verifier cannot turn `:verified` into
  `:disputed`;
- adding a valid conflicting verifier must not be silently ignored;
- `:inconclusive` never falls through to a successful status;
- every derived status carries the complete attestation-set root and
  verification-basis root.

### C3 — release gates

- lockfile tampering rejected;
- executable substitution rejected;
- source/build/executable root mismatch rejected;
- namespace shadowing through a transitive dependency rejected;
- entrypoint origin spoofing rejected;
- undeclared network/filesystem/environment access rejected;
- runtime outside the declared range rejected;
- repository/dependency substitution with same coordinates but different bytes
  rejected;
- archive path traversal, duplicate entries, symlink escape rejected (archives);
- non-reproducible builds must not be represented as reproducible.

## Recommended stage changes

- **Into C2:** `verification-basis.v1` (R1); verifier-attestation schema and
  authority model (R3); complete mismatch/dispute taxonomy (R2); status
  derivation rules (R4); dispute consequences and supersession lifecycle (R4);
  canonical public-result projection (R5); experimental benchmark vectors
  exercising every classification.
- **Into C3:** canonical `with-bounty-v1` benchmark pack (R10);
  source/build/executable correspondence (R6); hermetic environment contract
  (R7); package signing, publisher identity, revocation/deprecation policy (R6);
  full transitive namespace and resource ownership checks (R8);
  compatibility-attestation schema, even if only internally issued (R9).
- **Stays in Stage D:** a genuinely clean-room verifier in another runtime;
  external Sew execution/attestation; generalised compositions; extension-team
  economic distribution; third-party certification ecosystem. Any public C3
  claim must state that verification diversity is still in-repo and not
  independently implemented.

## Documentation gate (per stage)

C2/C3 completion requires, in addition to code: ADR-0006 status updated from
Proposed (to Accepted/Experimental as appropriate); exact artifact and
projection tables; threat-model and trust-boundary section; verification-status
taxonomy; package consumer/operator guide; compatibility matrix; versioning,
migration, revocation, and deprecation policy; explicit guarantees and
non-guarantees; and an explicit distinction between structural verification,
replay verification, compatibility attestation, and independent assurance.
Documentation is part of each stage's completion gate, not trailing cleanup.

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
