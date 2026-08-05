# WITH-BOUNTY: Generic Bounty Composition for Framework Extensions

Status: **Proposed design note** (not an implementation commitment)
Date: 2026-08-04
Review status: Pre-review revision incorporating boundary and sequencing corrections.
Decisions are locked in `docs/architecture/ADR-0006-with-bounty-composition.md`.

Scope: Generic economics composition, typed effects, protocol adapters, evidence,
verification, and the Sew reference mapping — as the first post-review reference
composition of ADR-0005 Phase 6.

## 1. Purpose

`with-bounty` is a reusable composition construct for attaching a conditional
bounty to an otherwise valid framework operation. It is not a new protocol
action, a direct custody mutation, or a Sew-specific calculation.

The intended shape:

```text
base operation
    ↓
with-bounty policy
    ↓
eligibility evaluation
    ↓
bounty amount calculation
    ↓
normalised bounty effect (obligation-create.v2)
    ↓
adapter support validation
    ↓
composition application plan
    ↓
protocol transition (Sew canonical paths only)
    ↓
evidence and verification
```

## 2. This revision separates four objectives

A single implementation cannot carry all four of these at once without
expanding the pre-review surface:

1. **Proving ADR-0005's composition model.**
2. **Implementing a bounty composition.**
3. **Extending Sew's custody and obligation lifecycle.**
4. **Demonstrating an extension ecosystem with independent assurance.**

| Objective | Tranche | Evidence |
|---|---|---|
| Composition model | Before review (optional, compile/hash-only) | Pure compile/evaluate fixture, no mutation |
| Bounty composition | Immediately after review (Stage B) | Vertical slice with structural verification |
| Sew custody + obligation lifecycle | After review (Stage B), then split | Creation rules in the slice; lifecycle later |
| Ecosystem + independent assurance | Later roadmap (Stages C–D) | Only after package boundaries are stable |

## 3. Component-to-primitive mapping

Every proposed component reuses an existing primitive; nothing in this note
introduces a second representation of an existing concept.

| Proposed component | Existing primitive |
|---|---|
| Extension manifest, sealed classification | `src/resolver_sim/extensions/manifest.clj` (ADR-0005) |
| Capability registry, collision, freeze | `src/resolver_sim/extensions/registry.clj` |
| Frozen transitive resolution, resolution root | `src/resolver_sim/extensions/resolution.clj` |
| Lockfile | `src/resolver_sim/extensions/lockfile.clj` |
| Invocation + envelope | `src/resolver_sim/extensions/execution.clj`, `envelope.clj` |
| Base composition pipeline | `src/resolver_sim/composition/{contract,combination,compiler,plan,execution}.clj` |
| Eligibility / amount schemas | `src/resolver_sim/economics/schemas.clj` (`core-schemas`) |
| Effect schema + validation + adapter support | `src/resolver_sim/economics/effects.clj` |
| Parameter attribution | `src/resolver_sim/util/attribution.clj` (reused, not redefined) |
| Base application plan (v1/v2 precedent) | `src/resolver_sim/economics/slash_distribution_application_plan.clj` |
| Bounty payable + backing lifecycle | `src/resolver_sim/economics/bounty_payable.clj`, `bounty_payable_backing.clj` |
| Sew application of plans | `protocols_src/resolver_sim/protocols/sew/apply_slash_distribution.clj` |
| Held-custody canonical path | `resolver-sim.accounting.held-adjustment`, `sew/accounting.clj` (`add-held`) |
| Domain hashing | `src/resolver_sim/hash/canonical.clj` |

## 4. Core semantic model

A `with-bounty` policy wraps a **committed base result** and adds one bounty step.
The base remains authoritative; the bounty is supplementary and cannot rewrite it.

A first-version policy shape:

```clojure
{:composition/type :economics/with-bounty
 :composition/version 1

 :base
 {:plan/ref :plan/root
  :result/schema :prf/base-result.v1
  :result/root "sha256:..."}

 :bounty
 {:bounty/id :review-completion
  :eligibility
  {:capability/ref
   {:capability/kind :economics/eligibility
    :capability/id :organisation/review-bounty-eligible
    :capability/version 1}}
  :amount
  {:capability/ref
   {:capability/kind :economics/award-amount
    :capability/id :organisation/review-bounty-amount
    :capability/version 1}
   :basis {:source :base/result :field :resolved-amount}}
  :funding
  {:source :declared-reserve
   :parameter/address [:bounties :review-reserve]}
  :recipient
  {:source :event/actor}
  :effect-contract :prf.effect/obligation-create.v2
  :on-ineligible :skip
  :on-calculation-failure :abort-bounty
  :on-unsupported-effect :abort-before-mutation}}
```

### 4.1 The base is generic

The evaluator consumes a committed base result (`:base/result-root`), never a
Sew or slash-distribution invocation. The slash-distribution reference fixture
supplies a base *for the fixture only*; a later use over settlement, review
completion, or another economic action does not revise the generic v1 contract.

## 5. Capability separation

`with-bounty` uses distinct capability kinds (ADR-0005 §10, §13):

- `:economics/eligibility` — whether the bounty step may execute.
- `:economics/award-amount` — how much is owed.
- Funding — declared separately (`:declared-reserve`, `:base/gross`,
  `:external-sponsor`); v1 prefers declarative references.
- `:evidence/verifier` (Stage C+) — verification, selected independently.

The separation is:

```text
who may earn
≠ how much is earned
≠ where funding comes from
≠ what economic effect is created
≠ how a protocol applies it
≠ who verifies it
```

## 6. Effect contract: obligation-create.v2

`:prf.effect/obligation-create.v1` keeps its committed meaning for historical
slash-distribution evidence. `with-bounty` introduces
`:prf.effect/obligation-create.v2`, which carries the fuller obligation shape
(token, owner, funding, subject, provenance). A migration/normalisation function
translates compatible v1 effects into the v2 internal representation.

```clojure
{:effect/type :obligation/create
 :effect/contract :prf.effect/obligation-create.v2
 :obligation/type :bounty-payable
 :obligation/id "sha256:..."
 :obligation/amount 500
 :obligation/token :token/usdc
 :obligation/owner :researcher/alice
 :obligation/funding
 {:source :declared-reserve
  :parameter-address [:bounties :review-reserve]
  :parameter-context-root "sha256:..."}
 :obligation/subject
 {:operation-root "sha256:..." :bounty-id :review-completion}
 :effect/provenance
 {:policy-root "sha256:..."
  :eligibility-invocation-id "sha256:..."
  :amount-invocation-id "sha256:..."
  :extensions/resolution-root "sha256:..."}}
```

The obligation ID is deterministic and versioned:

```clojure
(hash
 [:bounty-payable
  operation-root
  bounty-id
  recipient
  token
  amount
  policy-root])
```

## 7. Composition application plan

A dedicated, content-addressed composition plan composes **over** a base plan —
it is not an expanded slash-distribution v2 plan:

```text
with-bounty plan
├── base application-plan root
├── base result root
├── bounty effect root
├── combined effect-set root
└── composition preconditions
```

The plan is invalid unless the eligibility and amount capabilities resolve from
the frozen snapshot, all effects validate against their versioned schemas, the
selected adapter supports the exact effect contract, funding is available, and
all creation-time preconditions pass — **before any mutation**.

## 8. Creation rules vs lifecycle rules

`with-bounty` the composition owns **creation-time** invariants only:

- deterministic obligation identity;
- whether an obligation effect should be created;
- the amount and declared funding source;
- creation preconditions (funding availability, adapter support);
- no duplicate creation for the same composition operation.

A generic obligation / bounty-payable lifecycle owns the rest:

- paid / cancelled / outstanding reconciliation;
- partial payment;
- release of backing;
- terminal settlement conservation.

The existing `bounty_payable` (`:pending-backing :backed :settled :cancelled`)
and `bounty_payable_backing` (`:committed :consumed :released`) artifacts already
provide this lifecycle; Sew enforces it through that machinery. Broader lifecycle
work is a separate roadmap item unless already fully provided by those artifacts.

## 9. Verification

- **Structural verification is delivered per phase** with the artifact it checks
  (policy root; invocation/envelope; effect/plan; transition evidence).
- **Implementation replay** re-runs the exact sealed eligibility and amount
  implementations against committed inputs (Stage C).
- **Verifier terminology.** A separate extension package in this repository is a
  *separate/secondary verifier capability* — it is **not** described as
  *independent*. "Independent verifier" is reserved for meaningful implementation
  independence (clean-room, another runtime, or no reuse of producer evaluation
  code). This follows the existing conformance work's producer-vs-verifier
  distinction.

Evidence must never label implementation replay as independent verification.

## 10. Execution evidence

Each evaluation produces a composition envelope:

```clojure
{:composition/type :economics/with-bounty
 :composition/version 1
 :composition/policy-root "sha256:..."
 :composition/base-operation-root "sha256:..."
 :composition/status :applied

 :bounty/id :review-completion
 :bounty/eligibility {:invocation/evidence-envelope ... :result/domain-evidence ...}
 :bounty/amount      {:invocation/evidence-envelope ... :result/domain-evidence ...}

 :bounty/effect-root "sha256:..."
 :bounty/application-plan-root "sha256:..."
 :bounty/transition-evidence-root "sha256:..."

 :extensions/resolution-root "sha256:..."
 :verification/profile :implementation-replay}
```

For an ineligible bounty:

```clojure
{:composition/status :skipped
 :composition/reason :bounty-ineligible
 :bounty/effect-root nil
 :bounty/application-plan-root nil}
```

Omission is not acceptable: the evidence must show the declared bounty step
existed and why it produced no effect.

## 11. Failure semantics (creation-time)

| Condition | Result |
|---|---|
| Bounty ineligible | Base continues; bounty recorded as `:skipped` |
| Malformed eligibility / capability failure | Policy-defined; default aborts composition |
| Amount calculation failure | No bounty effect |
| Negative / non-numeric amount | Loud validation failure |
| Zero amount | Explicit `:zero-bounty`; no obligation |
| Unsupported effect | Planning fails before mutation |
| Insufficient declared funding | No obligation creation; loud funding violation |
| Adapter mutation failure | Transition fails atomically |
| Evidence construction failure | Transition non-admissible |

The policy declares `:bounty/failure-mode :base-independent | :transaction-fatal`.

## 12. Reference fixture (Stage B scope)

The first vertical slice stays narrow:

- one base type in the reference fixture (slash distribution as base result);
- one obligation type (`:bounty-payable`);
- one protocol (Sew), through canonical `add-held` and payable/backing paths;
- no partial payments unless already supported generically;
- no compatibility attestation;
- no benchmark publication claim;
- no "independent verification" claim.

## 13. Implementation sequence

### Stage A — before review: specification gate

1. Revise this note (done).
2. Land `docs/architecture/ADR-0006-with-bounty-composition.md` with the six
   framework-contract decisions.
3. Choose `:prf.effect/obligation-create.v2`.
4. Define the generic base-plan/result contract.
5. Reuse `resolver-sim.util.attribution`; do not create a second primitive.
6. Define creation vs lifecycle ownership.
7. Map every proposed claim to: design only | structural test | implementation
   replay | external evidence.
8. Optional: one pure compile/evaluate fixture (policy normalisation + hashing,
   frozen resolution, pure evaluation → effect candidate, structural composition
   receipt; no Sew mutation, no custody reservation, no released attestation).

   Implemented as the Stage A thin proof:
   `src/resolver_sim/economics/with_bounty/{policy,identity,composition}.clj` +
   `test/resolver_sim/economics/with_bounty/{fixture,fixtures,proof,stage_a_test}.clj`.

### Stage B — immediately after review: minimum complete vertical slice

Policy, identity and domain hashes; test fixture extension for eligibility and
amount; frozen resolution and invocation; pure evaluation and normalised v2
obligation effect; dedicated composition application plan; Sew preflight
validation; Sew application through canonical payable and `add-held` paths;
bound transition evidence; structural verification for every artifact;
end-to-end idempotency and fail-before-mutation tests.

Implemented:

- `src/resolver_sim/economics/effects.clj` — `:prf.effect/obligation-create.v2`
  (ADR-0006 D1) + `normalize-v1-obligation-create` migration.
- `src/resolver_sim/economics/with_bounty/{policy,identity,composition}.clj` —
  Stage A policy/identity/receipt.
- `src/resolver_sim/economics/with_bounty/{application_plan,transition_evidence}.clj`
  — composition plan and bound transition evidence.
- `src/resolver_sim/economics/with_bounty/evaluation.clj` — pure evaluation.
- `src/resolver_sim/economics/with_bounty/verification.clj` — per-artifact
  structural verification (ADR-0006 D8).
- `protocols_src/resolver_sim/protocols/sew/with_bounty.clj` — Sew adapter:
  preflight fail-before-mutation, canonical `add-held` reservation, payable +
  backing + claimable, idempotent application.
- Tests: `test/resolver_sim/economics/with_bounty/*` and
  `protocols_src/test/resolver_sim/protocols/sew/with_bounty_test.clj`.

The slice stays narrow: one base type (slash distribution as committed base
result), one obligation type (`:bounty-payable`), one protocol (Sew), no
partial payments, no compatibility attestation, no benchmark publication claim,
no independent-verification claim.

### Stage C — after the vertical slice is stable

Implementation replay; verifier disagreement and `:disputed`; additional failure
modes; wider base-plan compatibility; released manifest and lockfile; protocol
compatibility evidence.

### Stage D — later roadmap (deferred until an external or genuinely separate use exists)

Independent-verifier claim; external Sew compatibility attestation; benchmark
pack as ecosystem evidence; generalised `with-obligation` / `with-incentive` /
governance-triggered composition; extension-team incentives and package
distribution; conformance certification for third-party packages.

A benchmark pack reports an established capability; it is not the first place
where semantics are stabilised.

## 14. Deferred / non-goals

- Universal protocol-level bounty claim action.
- Direct `adjust-held`, balance, or protocol-record mutation by extensions.
- How a protocol exposes a resulting bounty through `available-actions`.
- Guaranteeing a bounty is economically desirable.
- A bounty discovery or funding marketplace.
- Collapsing eligibility, calculation, funding, effect application, and
  verification into one capability.
- Out-of-process execution profile.

## 15. References

- `docs/architecture/ADR-0005-framework-extension-packages.md` (Phases 1–5
  substrate; Phase 6 is this note).
- `docs/architecture/ADR-0006-with-bounty-composition.md` (decisions).
