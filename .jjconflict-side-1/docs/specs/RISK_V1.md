# RISK_V1 — Generic Risk Kernel, Phase 1

Status: Implemented (phase 1 + hardening pass 1.1 + authoritative adoption +
final authority/currentness bridge).

Milestone claim: **Risk V1 provides governed, state-bound exposure limits for
protected protocol operation.** A V4 authoritative configuration commits an
exact `risk-limit-policy.v1`; for protected pro-rata admission the protocol
derives loss-bearing exposure from the exact proposed operation, evaluates
conservative peak/global/domain/member exposure against that policy, and
commits only while the governing configuration authority remains unchanged.
This covers global TVL/exposure ceilings, technical-component ceilings,
participant-concentration ceilings, conservative transient-risk ceilings,
governed staged up-risking (via configuration lineage, no risk-budget schedule),
and upgrade-specific risk budgets — without "financial VaR".

Reference implementation:
- `src/resolver_sim/risk/projection.clj` — `risk-projection.v1`
- `src/resolver_sim/risk/limit_policy.clj` — `risk-limit-policy.v1` (authoritative)
- `src/resolver_sim/risk/limit_evaluation.clj` — `risk-limit-evaluation.v1` (derived evidence)
- `src/resolver_sim/risk/pro_rata_producer.clj` — first producer (general pro-rata)
- `src/resolver_sim/risk/authority.clj` — authoritative consumption bridge
- `src/resolver_sim/risk/admission.clj` — final authority/currentness admission store
- `test/resolver_sim/risk/risk_kernel_test.clj`, `test/resolver_sim/risk/authority_test.clj`, `test/resolver_sim/risk/admission_test.clj`

The older SPEDS scenario-observation artifact that previously used the name
`risk-projection.v1` is now `risk-observation.v1`
(`src/resolver_sim/notebook_support/speds/risk.clj`,
`docs/specs/RISK_OBSERVATION_SPEC_V1.md`). It is an observational/historical
artifact, distinct from the generic loss-bearing exposure kernel below.

## What V1 proves

For an exact source state and valuation/unit basis, `risk-projection.v1`
derives provenance-bound loss-bearing exposure rows and evaluates those rows
against an exact `risk-limit-policy.v1` via `risk-limit-evaluation.v1`:

- **Exact per-subject exposure phases.** Each row carries exact integer
  `:exposure/current` (as of the exact source state), `:exposure/after`
  (candidate final state), and `:exposure/peak` (the exact maximum that
  subject reaches at any point of the operation path; `peak >= max(current,
  after)`).
- **Conservative aggregate peak.** The projection summary's
  `:exposure/conservative-peak` is the **sum of per-row individual peaks**, an
  upper bound on the exact simultaneous aggregate exposure at any path point:

      actual-path-peak <= sum-of-individual-peaks = conservative-peak

  Exact path-level aggregate peak is **not** implemented in V1. V1 never
  labels its aggregate peak as exact.
- **Exact-state binding.** A projection with `:time-basis {:basis
  :state-derived}` is exact-state-bound: its `:risk-projection/source-root` IS
  the exact state its exposure derives from. Live admission evidence (future
  authoritative gate) must satisfy:

      candidate-state-root == risk-projection/source-root

  enforced by `risk-limit-evaluation/evaluate-bound`. `:as-of` projections
  remain available for observation/history/research but are never live
  admission evidence.
- **Provenance.** Every row is bound to a subject root; aggregate risk
  decomposes to individual exposure subjects.
- **Canonical, deterministic identity.** Roots are domain-separated SHA-256
  commitments over closed semantic bodies, always derived (never input), and
  re-verifiable by recomputation. Identical inputs (same source state + same
  ordered effects + same attribution) produce identical roots; changing
  current/after/peak, domain attribution, subject root, unit root, or source
  root changes the recomputed identity.
- **Membership-only domain attribution.** Attribution entries
  `{:risk/domain s :risk/member s?}` carry no amounts; a row's exposure is
  attributed in full to each listed domain. Canonical form: an explicit
  `:risk/member` equal to the row's `:exposure/subject-root` is normalized to
  the omitted form (semantically identical), identical entries within a row
  collapse, and entries sort deterministically.

## What V1 does not prove

V1 does not yet provide:

- probabilistic VaR;
- failure probabilities;
- exact temporal/path aggregate peak (only the conservative sum of individual
  peaks);
- an authoritative risk taxonomy (the domain vocabulary is open and
  non-authoritative);
- reserve coverage;
- insurance sufficiency;
- alerts;
- historical rolling risk (risk-observation.v1 covers scenario observation,
  not the generic kernel);
- governance authorization by itself.

## Semantics that must not be misread

- **Risk domains may overlap.** A single subject may be attributed to several
  domains (technical, human/principal, infrastructure, …).
- **Domain totals are counterfactual views and are not generally additive.**
  Amounts are never summed across *different* domain identities. Only exposure
  attributed to the *same exact* domain identity is aggregateable — and only
  because the authoritative policy explicitly commits to that identity.
- **Projections and evaluations do not grant authority.** `risk-projection.v1`
  and `risk-limit-evaluation.v1` are derived evidence. Only
  `risk-limit-policy.v1` is authoritative/governance-controlled, and only a
  future authorized consumer of `risk-limit-policy.v1` may make its
  satisfaction a protocol admission requirement.
- **`:conservative-peak` is an upper bound, not an exact peak.** A policy
  basis of `:conservative-peak` is safe for admission (harder to pass than
  exact path peak); `:after` is the exact final-state aggregate.
- **Evaluation is fail-closed.** `evaluate`/`evaluate-bound` refuse on invalid
  projections or policies, unit mismatch, and root verification failure, and
  commit both the policy root and the projection root so a consumer can
  independently re-verify what evidence was checked against which authorized
  policy.

## Authoritative adoption (`chain-configuration.v4`)

`risk-limit-policy.v1` becomes authoritative via the configuration lineage:

- **`chain-configuration.v4`** adds the mandatory `:risk-limit-policy/root`
  (closed shape, canonical root, `sha256:` reference). V1/V2/V3 configurations
  remain supported without a risk root; V4 without one is rejected — there is
  no optional risk-controls-disabled mode.
- **The risk root participates in existing change identity.** Changing only
  `:risk-limit-policy/root` changes the configuration root, the transition
  root, and the generic `chain-configuration-change-identity` hash.
- **`resolver-sim.risk.authority/admit`** is the fail-closed gate. It resolves
  the policy body ONLY from the authoritative configuration's committed root
  via a content-addressed store (recomputed root == configured root;
  absent/malformed/wrong-rooted bodies are rejections), then requires an
  exact-state-bound projection whose `:risk-projection/source-root` equals the
  exact source state, an evaluation whose policy-root equals the authoritative
  root, a valid/recomputable evaluation, and result `:pass`.
- **`admit-pro-rata-operation`** derives the projection internally from the
  exact `state-before` + stages being admitted, so a projection cannot be
  transplanted from another candidate.
- The evaluation does **not** grant authority; it is a required condition
  consumed by the existing authority mechanism. No caller-controlled lookup
  can select the policy used for admission.

## Final authority/currentness bridge (`risk.admission`)

The currently applicable authoritative configuration is resolved from the
existing configuration-head machinery (never caller-asserted) and consumed
through the existing two-phase fence/CAS pattern:

- **`resolve-current`** walks head → committed `:configuration/head-root` →
  retained configuration body → committed `:risk-limit-policy/root` → retained
  policy body, recomputing/verifying every root. No caller can supply a
  configuration body, configuration root, or risk-policy root.
- **`issue-risk-fence!`** (phase 1) resolves current H→C→P, derives the exact
  candidate projection internally, evaluates it, and only on PASS and a
  still-current head records a fence in one atomic CAS.
- **`finalise-risk-fence!`** (phase 2) commits the candidate's business-state
  transition only if the head is unchanged (`:state-not-at-required-head`
  otherwise), closing the stale-head/TOCTOU window; a retry re-resolves the
  newly applicable policy. `admit-and-commit-pro-rata!` is the single call.
- No risk-specific head/epoch/fence store is introduced; this is the final
  authority/currentness bridge over the existing authority model.

Binding semantics (audited): `:risk-projection/source-root` is bound by the
producer to `canonical-effects/state-root` of `state-before` (the predecessor
state); `evaluate-bound`'s `exact-source-root` must equal it. The candidate
(post-transition) state is bound structurally because the projection is derived
from the exact transition being admitted.

## Atomicity: one domain

The risk-controlled admission store is a **single atomic state primitive**
(one `state-atom` holding the authoritative head, the business state, and the
issued fences together). Configuration activation and `finalise-risk-fence!`
`compare-and-set!` the **same** atom, so the head check and the business-state
commit are one atomic update. There is no separate `ConfigurationHeadStore` and
no copied head representation across stores, so a configuration activation can
never race *between* the head check and the business commit.

**The guarantee is structural, not conventional.** It rests on three facts that
hold by construction: (a) the head and the business state live in the same
atomic primitive; (b) both competing actions serialize through that one CAS
domain; (c) the head check and the business mutation are a single state
transition. Under real threads the two serialize: either the finalize commits
under head H1 (its admission record is bound to H1's root), or the activation
wins and the finalize re-reads the new head and rejects
(`:state-not-at-required-head`). A committed admission is never observed bound
to a later head.

Temporal meaning: currentness is evaluated **at the commit boundary**, not
continuously afterward. A commit under H1 permanently records H1/C1/P1; a later
governed activation to H2 is a distinct, correct event and does not make the
historical admission stale.

The 200-iteration concurrent activate-vs-finalize test in `admission_test.clj`
is **adversarial regression evidence only** — it exercises the CAS boundary
under real threads but is not the primary justification for atomicity. The
justification is the structural fact (one state atom, one CAS primitive, head
check + business mutation in one transition).

## Risk V1 semantic freeze

The following are frozen **contract-level** semantics. Do not modify them
opportunistically; expand through new versions/extensions instead:

- **`risk-projection.v1`** — loss-bearing exposure; `current` / `after` /
  per-row `peak`; conservative aggregate peak; open overlapping domain
  attribution; exact source-state binding; derived, non-authoritative.
- **`risk-limit-policy.v1`** — sole risk authority artifact; global / selected-
  domain / member-concentration limits; mandatory under `chain-configuration.v4`.
- **`risk-limit-evaluation.v1`** — derived evidence; exact policy + projection
  roots; fail-closed.
- **Protected pro-rata admission** — candidate projection derived internally;
  current config derived from the authoritative head; policy derived from
  configuration; issue/finalize fence; same atomicity domain.
- **Assurance claim** — Risk V1 provides governed, state-bound exposure limits
  for protected protocol operation: the exact candidate operation is evaluated
  against the exact risk-limit policy committed by the authoritative
  configuration applicable at commit, and stale authority cannot finalize.
- **Lineage invariant** — head → configuration → risk policy → exact candidate
  projection → evaluation → atomic commit contains **no caller-asserted
  authority gap**. Named invariant, not emergent behavior.

Frozen here refers to the observable contract (shapes, roots, semantics, the
V4 authority relationship, and the currentness/atomicity guarantee). Reopening
any of these requires a new version (e.g. `risk-limit-policy.v2`) or an explicit
freeze revision, never an in-place mutation of V1.

## Assurance ladder (Risk V1)

Each rung is a distinct, independently verifiable property; the milestone claim
is the conjunction.

1. **EXPOSURE** — exact loss-bearing value is derived from the exact
   state/effects (never caller-supplied projection evidence).
2. **ATTRIBUTION** — exposure is classified across risk domains and members.
3. **BOUND** — conservative exposure (global/domain/member/conservative-peak) is
   compared to exact limit amounts.
4. **AUTHORITY** — the exact limits are committed by an authoritative
   configuration (`chain-configuration.v4` → `:risk-limit-policy/root`), never
   a policy registry or caller nomination.
5. **CURRENTNESS** — admission and commit occur under the same currently
   applicable authority (single-atomicity fence; stale head rejects).
6. **LINEAGE** — the chain
   head → configuration → policy → projection → evaluation → commit
   contains **no caller-asserted authority gap** at any link. This is an
   explicit invariant of the admission store, not merely emergent from the
   implementation.

## Deferred (explicit scope control)

Scenario distributions, Monte Carlo, expected shortfall, reserve coverage,
insurance, alerts, risk-domain registry, arbitrary policy expressions, and
exact path-level aggregate peak are all future consumers of the same rooted
core artifacts — none is added in V1.