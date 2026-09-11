# ADR-0007: Three-Member Consensus System-Wide

Status: Proposed
Date: 2026-08-05
Scope: a single canonical three-member decision standard for genuine
contested-decision boundaries, the reconciliation of the two force-authorisation
worlds, and the separation of the generic quorum-cell primitive from the
canonical profile.

Supersedes / relates to: ADR-0006 (composition precedent),
`docs/proposals/THREE_MEMBER_CONSENSUS_THROUGHOUT.md` (design note).

## Context

The benchmark review pipeline is the only place PRF engineers contested-outcome
confidence to a shared standard: a frozen three-member cell, whole-outcome
2-of-3 consensus, and policy-linked Ed25519 force-authorisation. Every other
operational domain decides contested outcomes through single actors, procedural
rules, or divergent threshold mechanisms with no common assurance claim. Two
force-authorisation worlds with overlapping terminology but incompatible shapes
leave the authority model unresolved.

This ADR locks only the decisions that establish the standard. Decisions are
recorded with status, rationale, and consequences so review can challenge the
meaning of the standard rather than its volume. Implementation-stage detail is
deferred to the design note.

## Decisions

### D1. The canonical three-member decision standard

Status: **Decided (pre-review)**

The canonical three-member decision standard has **exactly three distinct,
frozen members** and requires **two valid concurring positions over the same
whole outcome** (2-of-3).

Whole-outcome agreement means the two positions reference the same canonical
outcome (the outcome-hash model used by `three_member_certificate.clj`), never
a synthetic field-level majority.

Rationale:

- Fixing the count and the threshold is what makes "three-member consensus" a
  meaningful assurance claim rather than a loose family of quorum
  configurations.
- The count and threshold already match the benchmark review model
  (`review_round.clj`, `force_authorisation_policy.clj`).

Consequences:

- A domain may impose a stronger condition (for example 3-of-3 unanimity or an
  independent veto) only as an additional named policy over the canonical
  baseline.
- A 1-of-3 decision is never conforming.
- A generic m-of-n quorum is a separate mechanism and does not satisfy the
  canonical claim.

| Profile | Members | Threshold | Conforming |
|---|---|---|---|
| Canonical baseline | exactly 3 | 2-of-3 | Yes |
| Optional stronger | exactly 3 | 3-of-3 | Yes, as a named policy |
| 1-of-3 | exactly 3 | 1-of-3 | Never |
| Generic m-of-n | n (any) | m (any) | No — separate primitive |

### D2. One canonical force-authorisation model

Status: **Decided (pre-review)**

PRF has one canonical force-authorisation decision model. Existing
force-authorisation representations become versioned legacy projections or
adapters over that model rather than independent authority systems.

Rationale:

- The benchmark model (`researcher_force_authorisation.clj` +
  `force_authorisation_policy.clj`) and the legacy
  `evidence/force_authorisation.clj` +
  `assurance/force_authorisation.clj` world commit different semantics,
  lifecycle states, scope definitions, and consumption evidence. They cannot
  be merged by routing alone.

Consequences:

- Distinguish canonical model (semantics every new decision must satisfy),
  canonical implementation (reusable implementation of that model), and legacy
  representation (older artifact translated or verified but not normative).
- No new decisions are emitted using the legacy authority model.
- Legacy artifacts remain independently verifiable; removal occurs only after
  all historical artifacts remain so.

### D3. Mandatory at contested boundaries only

Status: **Decided (pre-review)**

The standard is mandatory at canonical contested-decision boundaries, not
across all computation, experimentation, or operational execution.

Rationale:

- "Preferred" is too weak for a system-wide assurance standard.
- A global invariant over every simulation, test configuration, or
  experimental panel is unnecessary and harmful.

Consequences:

- A decision that is a canonical contested decision, produces a final or
  authorising PRF decision certificate, or claims compliance with the
  standard must conform (exactly three, 2-of-3).
- Non-canonical experiments may use different panel sizes but must not emit a
  canonical certificate or claim compliance with the standard.
- Requirement by context:

| Context | Requirement |
|---|---|
| Canonical contested decision | Mandatory exactly 3, 2-of-3 |
| Historical artifact verification | Legacy rules permitted |
| Experimental / comparative simulation | Alternative panel sizes permitted |
| Deterministic / probabilistic computation | Standard does not apply |
| Operational execution after certification | May remain single-actor |

### D4. Parametric primitive, canonical profile pinned

Status: **Decided (pre-review)**

The reusable quorum mechanism may be count-parameterised, but the canonical
three-member profile pins member count to exactly three and threshold to two.

Rationale:

- Generalisation of hardcoded membership is a useful capability, but it
  belongs at the primitive layer, not in the externally visible standard.
- "Minimum three" would couple different assurance structures: a 3-of-5
  decision is not the same assurance structure as a 2-of-3 decision, even
  where both achieve a majority.

Consequences:

- The generic quorum-cell primitive supports n members, an m threshold, frozen
  membership, identity uniqueness, role bindings, position validation, and
  recomputable certificates.
- The canonical profile is a named configuration of that primitive (n=3, m=2,
  whole-outcome agreement, canonical role and independence requirements).
- Parameterisation cannot weaken the canonical profile or relabel a
  non-conforming configuration as conforming.

### D5. Schema versioning rule

Status: **Decided (pre-review)**

Internal generalisation does not itself require a schema version. Any change
to committed decision semantics, membership claims, policy binding, role
binding, or certificate interpretation requires a new certificate version.

Rationale:

- A versioned contract has stable meaning (ADR-0006 D1). Generalisation that
  preserves committed semantics must not force a migration.
- A bump is warranted only when the projected, hashed meaning changes.

Consequences:

- Preserve existing three-member research certificates whose semantics already
  match the standard (v1/v2).
- Introduce a new canonical policy / profile artifact as needed.
- Bump the certificate to v3 only when it commits that profile or materially
  stronger role and independence claims, per the versioning criteria in the
  design note (section 7).

### D6. Domain profiles supply roles and eligibility

Status: **Decided (pre-review)**

The standard defines membership, whole-outcome agreement, identity, role
binding, and independence requirements. Concrete role names and eligibility
rules are supplied by versioned domain profiles.

Rationale:

- resolver / reproducer / adversarial-reviewer fit research and benchmark
  review, but not naturally governance, settlement, community moderation,
  attestation, or jury decisions.

Consequences:

- A conforming decision satisfies the standard's structural requirements
  regardless of role vocabulary.
- Each domain supplies a versioned profile, e.g. research review (steward,
  independent reproducer, adversarial reviewer), settlement (primary resolver,
  independent verifier, challenger), governance (technical reviewer,
  stakeholder reviewer, adversarial reviewer), attestation (attestor A, B, C
  with eligibility classes), jury (juror 0, 1, 2).
- Profiles change instantiation, not the standard.

### D7. Consensus authorises; execution may be single-actor

Status: **Decided (pre-review)**

Consensus governs contested decision formation and authorisation. Initiation,
evidence submission, escalation routing, and deterministic execution do not
require multi-party approval unless they themselves make a contested
consequential determination. A single actor or automated executor may submit
and execute a valid certificate-bound decision.

Rationale:

- Requiring three members to call or co-execute every transition conflates
  decision formation, authorisation, transaction submission, deterministic
  execution, and post-execution evidence.

Consequences:

- A three-member cell produces an authorising certificate; a single submitter
  or automated executor may then run the command, with the execution receipt
  bound to the certificate.
- Example: `execute-resolution` remains a single submitted operation but
  rejects execution without a valid three-member resolution certificate.
- This flow is defined in the design note (section 5).

**Enforcement rule:** A command must not be classified as a canonical
contested-decision boundary merely because it initiates, routes, or executes a
process. Classification depends on whether the command selects among
materially incompatible outcomes using discretionary or evaluative judgement.

**Asymmetric pattern (escalation / liveness-sensitive):**

```text
Single authorised actor pauses or escalates
    ↓
No irreversible punitive action yet
    ↓
Three-member review within a defined period
    ↓
Confirm, replace, or release
```

- Reversible protective action may be unilateral.
- Final adverse determination requires the canonical contested-decision
  process.
- Deterministic execution of that determination may be single-actor.

## Acceptance bar

The standard is established when:

- the canonical profile (exactly three, 2-of-3) is documented and enforced at
  every canonical contested-decision boundary;
- the two force-authorisation worlds are reconciled into one canonical
  policy-linked model, with legacy representations verified through adapters
  and no new legacy-model decisions;
- the generic quorum-cell primitive exists and cannot be mistaken for the
  canonical profile;
- schema versioning follows the D5 rule, preserving v1/v2 semantics until a
  genuine committed-semantics change;
- domain profiles bind roles and eligibility explicitly;
- deterministic execution remains single-actor and certificate-bound.

## Implementation status

### P1 Phase A — reconciliation layer and D5 test (landed)

- `src/resolver_sim/assurance/canonical_force_authorisation.clj` (protocol
  independent, no Sew import) supplies:
  - canonical profile constants (n=3, m=2) and `classify-profile` /
    `profile-conforming?`, pinning the canonical profile per D1/D4 and
    rejecting 1-of-3 and generic m-of-n as non-conforming;
  - `reconcile-policy`, normalising both string-keyed run-layer policy
    artifacts and keyword-keyed maps into a canonical shape classified against
    the profile;
  - `classify-representation` (canonical-research / canonical-policy /
    legacy-evidence / unknown) and `legacy-as-canonical-projection`, so legacy
    evidence reads as a non-normative projection that never reports consensus
    it does not have (D2);
  - `reconcile-force-authorisation-worlds`, the D2 one-model summary;
  - `boundary-decision?` and `decision-context` for the D3 boundary/context
    table (initiation/routing/execution are not themselves a boundary);
  - `schema-change-compatibility` / `schema-stable?`, the D5 versioning
    compatibility test (stable unless a committed-semantics bump flag is set).
- Tests: `test/resolver_sim/assurance/canonical_force_authorisation_test.clj`
  (52 assertions) and registration in
  `test/resolver_sim/assurance/namespace_load_test.clj`.

### P1 Phase B — declared-profile conformance, one-way legacy projection, and differential assessment (landed)

- **Declared-profile conformance (D1/D4).** Conformance is now a property of
  `declare-profile`, never of raw arithmetic. `three-member-standard-conforming?`
  (2-of-3 or a *declared* 3-of-3) and `canonical-profile-conforming?`
  (canonical 2-of-3 only). An unlabelled `{n 3, m 3}` never conforms; a 3-of-3
  requires an explicit named policy or profile id. `reconcile-policy` now
  declares the policy id.
- **One-way legacy projection (D2).** `legacy-as-canonical-projection` emits
  machine-visible negative markers `:representation/class :legacy-evidence`,
  `:projection/normative? false`, `:canonical-emission-eligible? false`, plus
  `:canonical/role-independent` and `:canonical/whole-outcome-consistent`
  false. `projection-normative?` and `representation-emission-eligible?`
  (`:canonical-research` only) expose the negative capability; legacy and
  `:unknown` are never emission-eligible. Unknown representations fail closed
  (non-normative, non-conforming, never emission-eligible).
- **Assessed, declared boundary classification (D3).** `decision-boundary-kinds`
  (`:contested-adjudication :initiation :routing :deterministic-execution
  :evidence-submission`); `declared-boundary-kind` fails closed to
  `:deterministic-execution`; `boundary-decision?` uses the declared kind, not
  command names.
- **Reasons-reporting D5.** `schema-change-compatibility` now returns
  `:schema-stable?`, `:required-action (preserve-version|new-version)`, and the
  full set of fired `:reasons`, so ADR/migration evidence is auditable.
- **Differential assessment.** `assess-representation` reports
  `:representation/class`, `:canonical-model-compatible?`,
  `:canonical-emission-eligible?`, `:representation-normative?`,
  `:missing-claims`, `:schema-change`, and `:migration-action` for research
  instances, policy artifacts, legacy evidence, and unknown artifacts.
- Tests extended to 7 tests / 68 assertions; clean `clj-kondo` (0/0).

### P1 Phase C — cancellation-decision.v1 (landed)

- **A cancellation is itself a canonical contested decision.** It must conform
  to the same three-member profile (D1), including the D4 declaration
  requirement: conformance needs an explicit `:profile-id` or
  `:named-policy?`, never bare arithmetic. The 2-of-3 conformance lives in
  the decision profile — never in the generic window primitive.

### P1 Phase C.1 — generic cancellation-window.v1 primitive (landed)

- **Primitive extraction.** A domain-neutral `cancellation-window.v1` primitive
  is separated from the force-authorisation-specific model. It owns only:
  valid target states, irreversible states, open/closed/invalid classification,
  fail-closed handling, and blocking reasons — no 2-of-3 semantics.
  `classify-lifecycle-window` classifies any state against a lifecycle profile.
- **`cancellation-decision.v1` composes authority over the primitive.** The
  canonical three-member conformance (D1/D4) stays in the decision profile; the
  target window is read through a supplied lifecycle profile, defaulting to
  `force-authorisation-window`. A force-authorisation state is never treated as
  a domain state directly — the domain supplies a projection.
- **Force-authorisation lifecycle profile** (`force-authorisation-window`):
  pre-consumption states, **including `:reservation-issued`, are open**;
  terminal consumption/effect states (`:consumed`, `:outcome-released`,
  `:rolled-back-after-consumption`, `:consumption-receipt-terminal`) are
  irreversible (contract 2, Option A).
- **Probabilistic-allocation lifecycle profile**
  (`probabilistic-allocation-window`) projection:

  | Allocation state | window state | Class |
  |---|---|---|
  | AllocationCommitted | `:allocation-committed` | pre-cutpoint |
  | RandomnessRequested | `:randomness-requested` | **authoritative randomness-request cutpoint** |
  | RandomnessFulfilled | `:randomness-fulfilled` | irreversible |
  | ResultProposed | `:result-proposed` | irreversible |
  | ResultAccepted | `:result-accepted` | irreversible |
  | ClaimConsumptionStarted | `:claim-consumption-started` | irreversible |

  The probabilistic-allocation cutpoint is the authoritative randomness request,
  earlier than `:consumed`. After randomness is requested the round may fail,
  time out, reuse the same seed, or enter a predeclared fallback, but it must
  **not** be cancelled and rerolled.
- **Certificate-ready assertion.** `cancellation-window-assertion` emits a
  certificate input `{:assertion/id :cancellation/window-respected
  :cancellation/window :closed :cancellation/possible? false
  :blocking-reasons [:authoritative-randomness-requested]}`; the
   `:assurance` label is `:independent-replay` only when recomputed from
   the observed round-state input token (see contracts, below).
- Tests extended to 11 tests / 242 assertions; clean `clj-kondo` (0/0).

### P1 Phase C.2 — lifecycle-window contracts 1–8 (landed)

- **Contract 1 — cancellation vs deterministic lapse.** Only an explicit
  cancellation that revokes an otherwise valid authorisation
  (`:decide-cancel-valid-authorisation`) is a canonical decision. Expiry at a
  deadline, deterministic invalidation, post-cutpoint rejection, certified
  execution, and precommitted fallback/abandonment are deterministic and do NOT
  require the canonical panel. `cancellation-decision-required?` encodes the
  taxonomy; preserves D3/D7.
- **Contract 2 — `:reservation-issued` pinned (Option A).** It remains
  cancellable; docs no longer list it incorrectly. A cancellation in this window
  must atomically invalidate, release the reservation, prevent consumption,
  emit a terminal receipt, and preserve evidence (`cancellation-effects`).
- **Contract 3 — cutpoint inclusion explicit.** The first irreversible state is
  :closed, never the last-open: `:randomness-requested` is closed for
  allocation; `:consumed` for force-authorisation. Asserted directly.
- **Contract 4 — structural profile validation.** `validate-lifecycle-profile`
  detects overlap, undeclared states, incomplete valid-state contract, missing
  profile id/version, missing blocking reasons, and per-state misclassification.
  Lifecycle profiles now carry `:profile/id`, `:profile/version`, and explicit
  `:open-states` (not "valid minus irreversible").
- **Contract 5 — monotonic window.** `validate-lifecycle-monotonicity` rejects
  any permitted transition from a closed state back to open. Failure, timeout,
  restart, recovery, fallback, and partial rollback must continue on the same
  committed basis. The `probabilistic-allocation-window` profile carries an
  explicit `:transitions` map (the forward-only state graph), so the check is
  asserted against the real profile, not vacuous.
- **Contract 6 — atomic guard.** `cancellation-conflict-key` defines the single
  key cancellation and the irreversible transition contend on; enforcement is
  `(compare-and-transition! ...)`, not classification.
- **Contract 7 — target-snapshot binding.** `cancellation-binding-fields` +
  `cancellation-binding-complete?` require the decision to bind target id/hash,
  lifecycle profile id+version, state-evidence root, action/effects, reason,
  decision/declaration, policy instance, validity window, and conflict key.
- **Contract 8 — assurance labelling.** `cancellation-window-assertion` gives
  `:independent-replay` ONLY by recomputing the classification from the
  observed round-state input token (`:target-evidence`, `:lifecycle-profile`,
  `:domain-projection`, `:decision-opts`); a supplied classification is
  labelled `:structural-check`.
- Tests extended to 12 tests / 163 assertions in the canonical namespace
  (13 / 282 with namespace-load); clean `clj-kondo` (0/0).

### P1 Phase C.3 — allocation round-state mapper consuming the vocabulary (landed)

- **Coprocessor round-state mapper.** `resolver-sim.allocation.round-state`
  now maps the coprocessor allocation-round progression
  (`:allocation-committed :randomness-requested :randomness-fulfilled
  :result-proposed :result-accepted :claim-consumption-started`) onto the
  canonical `probabilistic-allocation-window` lifecycle, then consumes
  `classify-cancellation` and `cancellation-window-assertion`. It adds no
  lifecycle semantics, no 2-of-3 authority, and no window mechanics of its own.
  `lifecycle-target-state` fails closed on unrecognised tokens.
- **Certificate inclusion.** `compose-certificate`
  (`allocation-assurance-certificate.v1`) accepts an optional round-state
  token (second arity, default nil). When supplied it emits a
  `:round-lifecycle` block carrying the round state and the
  `cancellation-window-assertion` (passing, `:independent-replay`, never
  `:zk-proof`). The one-arity form is unchanged.
- **Integration invariants pinned.** `:randomness-requested` closes the window
  and refuses cancellation even under a conforming decision profile, with
  blocking reason `[:authoritative-randomness-requested]`; a pre-request round
  (`:allocation-committed`) remains cancellable.
- Tests: `round-state-test` (6 tests / 38 assertions), full allocation suite
  and `namespace-load-test` green; clean `clj-kondo` (0/0).
- **Coordinator, Rust-kernel, and on-chain cutpoints remain out of scope**
  (the ADR records the canonical decision and audit vocabulary only). The
  Rust/Solidity coordinators and the external demonstration consume this
  vocabulary in a later slice.

## Open questions

Implementation-stage only (design note section 9). The meaning of the
standard, its member count, its threshold, and its applicability are resolved
by D1–D7:

- whether the generic quorum-cell primitive is extracted before or during
  force-authorisation reconciliation;
- exact namespace and package boundaries;
- whether legacy evidence is translated eagerly or verified through adapters;
- the precise certificate version after projection-level analysis;
- concrete domain role names and eligibility conditions for later phases;
- whether particular high-risk governance actions require an additional 3-of-3
  profile;
- the sequence in which settlement, escalation, and governance are migrated.
