# THREE-MEMBER CONSENSUS THROUGHOUT: A Canonical Decision Standard

Status: **Proposed design note** (not an implementation commitment)
Date: 2026-08-05
Review status: Pre-review revision incorporating the D1–D7 decision set, domain
matrix refinement (initiation/routing vs contested determination), and the
asymmetric emergency pattern.
Decisions are locked in `docs/architecture/ADR-0007-three-member-consensus-system-wide.md`.

Scope: A single canonical three-member decision standard applied to every
genuine contested-decision boundary in PRF. Reconciles the two
force-authorisation worlds, separates the generic quorum-cell primitive from the
canonical profile, and restricts the standard to contested decision formation
and authorisation — not deterministic computation, experimentation, or
single-actor execution.

## 1. Purpose

PRF uses "consensus" to mean **contested-outcome confidence** — decision
quality, not blockchain validator consensus (`data/concepts/README.md`). Today
that confidence is engineered consistently in exactly one place: the benchmark
review pipeline. Everything else decides contested outcomes through a
patchwork of single actors, procedural rules, and threshold mechanisms with no
shared assurance claim.

This proposal defines one canonical **three-member decision standard** and
proposes applying it at every canonical contested-decision boundary in the
system. It does **not** propose requiring three people to approve every call,
transaction, or state transition.

### Resolved position

PRF will adopt a canonical three-member decision standard for genuine
contested-decision boundaries. A conforming decision uses exactly three
distinct, frozen members and requires two valid positions agreeing over the
same whole outcome. The underlying quorum mechanism may support other
configurations, but those configurations do not satisfy the canonical
three-member assurance claim. Domain profiles bind appropriate roles and
eligibility requirements. Consensus authorises decisions; deterministic
execution may subsequently be performed by a single actor or automated
mechanism. Existing force-authorisation systems will be reconciled into one
canonical policy-linked model, with legacy representations retained only
through versioned verification and migration adapters.

## 2. Problem

Three problems justify the proposal.

1. **The canonical pattern exists in one domain only.** The benchmark review
   pipeline already implements a frozen three-member cell
   (`benchmark/review_round.clj`), whole-outcome 2-of-3 consensus
   (`benchmark/review/three_member_certificate.clj`), and policy-linked
   Ed25519 force-authorisation (`run/force_authorisation_policy.clj`,
   `benchmark/researcher_force_authorisation.clj`). Every other operational
   domain uses its own, weaker mechanism: single resolvers, single governance
   actors, k-of-n attestation quorums with different defaults, local
   repeated-execution thresholds, and a legacy second force-authorisation world
   with incompatible semantics.
2. **Two force-authorisation worlds exist.** The benchmark
   `researcher_force_authorisation.clj` (three-member, policy/instance split,
   Ed25519) and the older protocol-independent
   `evidence/force_authorisation.clj` + `assurance/force_authorisation.clj`
   (scope-hash, single-use lifecycle) use overlapping terminology with
   incompatible shapes. This is an unresolved authority-model fork.
3. **"Three-member consensus" is a meaningful claim, not a knob.** If every
   domain can freely reconfigure membership and threshold, the phrase degrades
   into a loose family of quorum configurations and loses its assurance value.

## 3. The canonical standard

### 3.1 Structural requirements

A decision conforms to the canonical three-member standard when it has:

- exactly **three** declared member slots;
- **three distinct** member identities;
- a **frozen membership** set (committed before positions are formed);
- **explicit role bindings** for every slot;
- domain-defined **eligibility requirements**;
- explicit **independence or conflict declarations**;
- **two valid positions agreeing over the same whole outcome**; and
- **no reconstruction of consensus** from votes over incompatible projections.

Whole-outcome agreement means the two concurring positions reference the same
canonical outcome (the outcome-hash model already used by
`three_member_certificate.clj`), never a synthetic field-level majority.

### 3.2 Quorum semantics

The canonical profile is:

| Profile | Members | Threshold | Conforming |
|---|---|---|---|
| Canonical baseline | exactly 3 | 2-of-3 | Yes |
| Optional stronger profile | exactly 3 | 3-of-3 | Yes, as a named policy |
| 1-of-3 | exactly 3 | 1-of-3 | **Never** |
| Generic m-of-n | n (any) | m (any) | No — separate primitive |

A domain may impose a stronger condition (unanimity, an independent veto) but
only as an **additional named policy** layered over the canonical baseline. A
1-of-3 decision is never a three-member consensus, regardless of context.

### 3.3 Generic primitive vs canonical profile

These belong at different layers.

```text
Generic quorum-cell primitive
  - n members
  - threshold m
  - frozen membership
  - identity uniqueness
  - role bindings
  - position validation
  - certificate recomputation

Canonical three-member profile
  - n = 3
  - m = 2
  - exactly three distinct identities
  - whole-outcome agreement
  - canonical role and independence requirements
```

The count parameterisation is an implementation and research capability. It
must not weaken the externally meaningful canonical profile. "Minimum three" is
rejected: a five-member 3-of-5 decision is not the same assurance structure as
a three-member 2-of-3 decision, even where both achieve a majority.

## 4. Domain matrix

The standard applies to **canonical contested decisions only**. It is assigned
to the specific adjudicative boundary inside a domain, never to the whole
domain.

| Domain | Today | Under the standard |
|---|---|---|
| Benchmark review | Canonical 3-member, 2-of-3 | Reference model — unchanged |
| Evidence force-authorisation | Legacy shape, scope-hash | Legacy adapter/projection over the canonical model; no new emissions |
| Attestation quorum | k-of-n (default k=2) | Canonical profile 2-of-3 where a canonical contested determination; generic primitive retains m-of-n |
| Forensic runner consensus | Local repeated-execution threshold `max(2, N-1)` | Adopt canonical 2-of-3 certificate at run boundaries |
| Community mailbox | Single signer | 2-of-3 |
| Escalation initiation and routing | Often single caller or procedural rule | Remains unilateral or deterministic where appropriate; no canonical certificate required merely to access review |
| Escalated contested determination | Often resolved by a single authority | Canonical three-member authorising certificate |
| Slash allegation and evidence submission | Single caller | Remains open to eligible reporters; admission checks deterministic |
| Contested slash adjudication or appeal | Single authority or incomplete path | Canonical three-member certificate where facts, applicability, or remedy are disputed |
| Slash calculation and execution | Caller-driven or deterministic | Deterministic execution bound to the adjudication certificate or mechanically verified violation |
| Governance rule change | Single governance actor | Canonical three-member profile at contested boundaries |
| Settlement / `execute-resolution` | Single resolver | Canonical three-member authorising certificate; single-actor execution |
| Jury / Kleros stub | Declared panel-size 3, not implemented | Implement as a three-member profile |

**Standard does not apply** (deterministic or probabilistic computation, no
contested decision): `claims/engine.clj`, `economics/slash_distribution.clj`,
`oracle/detection.clj`, `stochastic/dispute.clj`.

**Requirement by context:**

| Context | Requirement |
|---|---|
| Canonical contested decision | Mandatory exactly 3, 2-of-3 |
| Historical artefact verification | Legacy rules permitted |
| Experimental or comparative simulation | Alternative panel sizes permitted (must not emit a canonical certificate) |
| Deterministic/probabilistic computation | Standard does not apply |
| Operational execution after certification | May remain single-actor |

### 4.1 Domain profiles

The standard defines structural requirements; it does not hardcode one
domain's role vocabulary. `resolver` / `reproducer` / `adversarial-reviewer`
fit research review but not governance, settlement, or jury decisions. Each
domain binds roles and eligibility in a versioned profile.

| Domain | Example bindings |
|---|---|
| Research review | model steward, independent reproducer, adversarial reviewer |
| Settlement | primary resolver, independent verifier, challenger |
| Governance | technical reviewer, stakeholder reviewer, adversarial reviewer |
| Attestation | attestor A, attestor B, attestor C, with eligibility classes |
| Jury | juror 0, juror 1, juror 2 |

The exact domain roles need not be semantically different, but their bindings
and eligibility must be explicit.

## 5. Where consensus ends and execution begins

A three-member cell produces an **authorising certificate**. A single actor or
deterministic executor may then submit the certificate and cause the state
transition. Requiring three members to call or co-execute every operation
would conflate distinct concerns: decision formation, authorisation,
transaction submission, deterministic execution, and post-execution evidence.

```text
Proposal
    ↓
Three frozen member positions
    ↓
2-of-3 whole-outcome certificate
    ↓
Authorised command
    ↓
Single submitter or automated executor
    ↓
Execution receipt bound to certificate
```

For example, `execute-resolution` may remain a single submitted operation, but
it rejects execution without a valid three-member resolution certificate. This
preserves operational simplicity while moving authority out of the single
caller.

### 5.1 Initiation, routing, and evidence are not the boundary

Initiation, evidence submission, escalation routing, and deterministic
execution do not require multi-party approval unless they themselves make a
contested consequential determination. Classification depends on whether the
command **selects among materially incompatible outcomes using discretionary or
evaluative judgement** — not on whether it starts, routes, or runs a process.

### 5.2 Emergency and liveness-sensitive escalation

For urgent cases an asymmetric pattern preserves liveness without granting a
single actor final adverse power:

```text
Single authorised actor pauses or escalates
    ↓
No irreversible punitive action yet
    ↓
Three-member review within a defined period
    ↓
Confirm, replace, or release
```

- **Reversible protective action:** may be unilateral.
- **Final adverse determination:** requires the canonical contested-decision
  process.
- **Deterministic execution of that determination:** may be single-actor.

The justification is not that "escalation" or "slash appeal" inherently needs
three people. It is that some processes contain a consequential contested
adjudication currently controlled by one actor. The proposal assigns the
three-member cell to that adjudicative boundary.

## 6. Force-authorisation reconciliation

One canonical model, not parallel operation and not superficial routing.

- **Canonical model** — the semantics every new force-authorisation must
  satisfy (frozen membership, policy-linkage, whole-outcome agreement,
  lifecycle, consumption).
- **Canonical implementation** — the reusable implementation of that model.
- **Legacy representation** — an older artefact that can be translated or
  verified but is not the normative source.

Target end-state:

- one canonical policy and membership model;
- one policy-linked decision and certificate chain;
- legacy readers and adapters for existing evidence artefacts;
- no new decisions emitted using the legacy authority model;
- eventual removal only after all historical artefacts remain independently
  verifiable.

Routing the `evidence/force_authorisation.clj` implementation through the
researcher implementation may be part of migration, but routing alone is not
enough: the two systems commit different semantics, lifecycle states, scope
definitions, and consumption evidence. Those must be reconciled, not papered
over.

## 7. Schema versioning rule

Internal generalisation does not itself require a schema version. The
certificate bumps to a new version only when committed decision semantics,
membership claims, policy binding, role binding, or certificate interpretation
change.

No schema bump where:

- existing certificate fields retain identical meaning;
- the canonical projection and hash preimage remain unchanged;
- existing recomputation produces the same result;
- the profile still requires exactly three members and two concurring
  positions; and
- new information is external policy metadata or an additive non-committed
  implementation concern.

New version required where:

- member count or threshold becomes certificate-controlled;
- the certificate commits a policy/profile identifier that was not previously
  committed;
- role semantics change;
- the meaning of consensus or whole-outcome agreement changes;
- new membership or independence claims enter the hashed projection; or
- old readers could accept an artefact while interpreting it differently.

Likely outcome: preserve existing three-member research certificates where
their semantics already match the standard; introduce a new canonical
policy/profile artefact; bump the certificate only if it starts committing
that profile or materially stronger role and independence claims.

## 8. Migration and rollout

- **P0** Write this proposal + ADR-0007 (done).
- **P1** Reconcile the two force-authorisation worlds; extract the generic
  quorum-cell primitive; pin the canonical profile (n=3, m=2); generalise the
  hardcoded membership checks behind the primitive with the canonical default;
  apply the schema-versioning rule before any certificate bump.
- **P2** Adopt the canonical profile at forensic consensus, attestation quorum,
  and community mailbox boundaries.
- **P3** Settlement, escalation, and governance conversion via the
  certificate-before-execute pattern (D7).
- **P4** Implement the jury panel as a three-member profile.

## 9. Implementation-stage open questions

The meaning of the standard, its member count, its threshold, and its
applicability are **resolved** in ADR-0007. The following are explicitly
implementation-stage and intentionally left open here:

- whether the generic quorum-cell primitive is extracted before or during
  force-authorisation reconciliation;
- exact namespace and package boundaries;
- whether legacy evidence is translated eagerly or verified through adapters;
- the precise certificate version after projection-level analysis;
- domain-specific role names and eligibility conditions for later phases;
- whether particular high-risk governance actions require an additional 3-of-3
  profile;
- the sequence in which settlement, escalation, and governance are migrated.

## 10. Benefits

- **One assurance claim.** "Three-member consensus" has a precise, machine
  checkable meaning system-wide.
- **Authority moved out of single callers.** Contested determinations stop
  being one-actor decisions without forcing multi-party execution.
- **Legacy honesty.** Historical artefacts remain verifiable; the legacy
  force-authorisation world stops emitting new decisions.
- **Layering without erosion.** The generic quorum primitive exists for
  research and experiment; it cannot be mistaken for the canonical profile.
- **Operational simplicity preserved.** Initiation, routing, and deterministic
  execution stay unilateral; only the adjudicative boundary is three-member.

## 11. References

- `docs/architecture/ADR-0007-three-member-consensus-system-wide.md`
  (decisions D1–D7).
- `src/resolver_sim/benchmark/review_round.clj`,
  `src/resolver_sim/benchmark/review/three_member_certificate.clj` (reference
  model).
- `src/resolver_sim/run/force_authorisation_policy.clj`,
  `src/resolver_sim/benchmark/researcher_force_authorisation.clj` (canonical
  force-authorisation model).
- `src/resolver_sim/evidence/force_authorisation.clj`,
  `src/resolver_sim/assurance/force_authorisation.clj` (legacy world).
- `docs/remediation/CRITICAL_HIGH_GAPS_REMEDIATION_PLAN.md` (WP5
  recomputable-certificate rule).
