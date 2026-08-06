# THREE-MEMBER RESEARCHER APPLICATION: Corrected Authority Model

Status: **Design note + implementation record** (records the review correction
of the "how the three-member researcher applies" outline and the closed
canonical-layer gaps: outcome commitment, equivocation/report, cancellation
gates, deterministic evidence, replay wording)
Date: 2026-08-06
Applies to: `docs/proposals/THREE_MEMBER_CONSENSUS_THROUGHOUT.md` and
`docs/architecture/ADR-0007-three-member-consensus-system-wide.md` (D1–D7).
Scope: how the canonical three-member standard applies to the researcher
force-authorisation evidence chain and to cancellation decisions, after review.

This note corrects and supersedes the earlier four-part outline
(compromised-member guarantees, whole-outcome commitment, decision-hash-valid
honesty, out-of-process vs independent replay) plus the cancellation-predicate
treatment. Every correction below was verified against the current
implementation.

## 0. Corrected core invariant

> The three-member model prevents unilateral researcher authority. A canonical
> decision requires a policy-conforming concurrence — normally two of three
> distinct constituted seats — whose valid signed positions bind the same
> decision scope and complete outcome. The remaining position, whether
> dissenting, qualifying, absent, invalid, or equivocating, remains explicit.
> Hashes and signatures protect artifact integrity and attribution; independent
> replay recomputes the classification; and a separate atomic transition
> boundary determines whether the authorised decision changes state. A single
> compromised seat cannot act alone or manufacture a second vote, although it
> may still participate in a valid majority with another member or impair
> liveness.

## 1. Compromised-member guarantees

Correct model: the reference point for "three-member researcher applies" is a
single seat inside the 2-of-3 cell (`canonical-member-count` = 3,
`canonical-threshold` = 2, `canonical_force_authorisation.clj:32-39`), not a
two-out-of-two role pairing. The earlier "2/2 roles" phrasing was wrong; the
cell has three distinct roles
(`#{:model-steward :independent-reproducer :adversarial-reviewer}`, `review_round.clj:34`).

A single compromised seat **cannot**:
- act unilaterally (never sufficient at threshold 2);
- impersonate another seat (identity distinctness is enforced; signatures are
  key-bound);
- replay an old position as current (preimage binds request-root and
  review-round hash, `researcher_force_authorisation.clj:99-105`);
- mix outcome roots into one concurrence (requires same whole outcome).

A single compromised seat **can**:
- form a valid 2-of-3 majority together with one honest member;
- block a 3-of-3 (stronger named policy) outcome;
- steer which of two candidate outcomes is the first to reach 2-of-3;
- cause liveness failure (refuse to participate);
- sign equivocating positions under one identity unless equivocation is
  machine-visible (see §6).

This asymmetry is the honest statement of a 2-of-3 model: it prevents
unilateral authority, it does not guarantee an honest outcome when two seats
collude, and it can always degrade liveness.

## 2. Whole-outcome commitment

### 2.1 Commitment trace

`decision-preimage` (`researcher_force_authorisation.clj`) binds
`:researcher/id :authorisation/id :authorisation/request-root
:review-round/hash :decision :dissent/reason`. It does **not** bind
`:outcome/root` or `:panel/root`.

`:authorisation/request-root` is an **opaque caller-supplied string** in the
canonical layer: tests supply `"sha256:mock-request"`-style literals and the
builder performs no reference validation. There is no authorisation-request
artifact builder or verifier in this codebase, so nothing recomputes or
resolves the root. The researcher assignment commits the root and matches it
back to an authorisation by string equality only
(`research_assignment.clj:34-35,89-93`).

`:review-round/hash` commits content-root, members (researcher/id + role),
membership-frozen-at, policy-root, and purpose
(`review_round.clj:345-361`). It does **not** commit the force-authorisation
target (`:review-round/force-target`, approval-set, or branch-descriptor are
creation/finalisation requirements only, excluded from the identity hash).

Field-by-field commitment table (a transitive commitment counts only when the
referenced artifact is content-addressed, has a recomputing verifier, commits
the complete required projection, is required+resolved by the signed-decision
verifier, and cannot be substituted without verification failure):

| Required decision meaning  | Directly | Transitively | Not committed | Evidence |
| -------------------------- | -------: | -----------: | ------------: | -------- |
| authorisation identity     | yes      | —            | —             | `:authorisation/id` in v1/v2 preimage |
| review round               | yes      | —            | —             | `:review-round/hash` in v1/v2 preimage |
| complete proposed outcome  | **v2 only** | —        | **v1**        | v2 binds `:outcome/root`; v1 binds none |
| effects / state transition | —        | —            | both v1 & v2  | no effects/transition commitment |
| relevant parameters        | —        | —            | both v1 & v2  | not bound by the signed decision |
| panel or constituted seats | —        | yes (via round) | —         | `:review-round/hash` commits members |
| decision scope             | yes      | —            | —             | `:authorisation/id` + round + request-root |
| request artifact           | yes (ref only) | no verifier | content  | opaque `:authorisation/request-root` |

### 2.2 Finding

`request-root` does **not** commit the complete outcome in a machine-verifiable
way. It is an unverifiable reference; even the review-round it points at does
not commit the force-authorisation target. A v1 `:approve` therefore binds only
"this researcher approves something identified by this opaque root", and two
materially different outcomes can share the same signed artifacts. `:panel/root`
is not needed: `:review-round/hash` already commits the exact constituted seats.

### 2.3 Implemented resolution: researcher-decision.v2

Introduced `researcher-decision.v2` (`build-signed-decision-v2`,
`verify-signed-decision-v2`) which binds `:outcome/root` in the signed preimage
under a **new domain separator** `RESEARCHER_DECISION_V2`
(`src/resolver_sim/hash/canonical.clj`), per the ADR-0007 D5 schema-version
rule:

```clojure
{:researcher/id ...
 :authorisation/id ...
 :authorisation/request-root ...
 :review-round/hash ...
 :outcome/root ...            ; new: complete proposed outcome root
 :decision :approve|:dissent
 :dissent/reason ...}
```

Compatibility behaviour:

- `build-signed-decision` / `verify-signed-decision` remain as the explicitly
  versioned **legacy v1 path**; existing v1 artifacts continue to verify.
- New production positions must use v2; a v2 position cannot be built without a
  valid `:outcome/root`.
- `classify-decision-version` → `:v2-complete-outcome | :v1-legacy | :unknown`.
- `decision-outcome-binding` classifies evidence honestly:
  `:outcome-committed | :outcome-unavailable | :invalid`. A v1 legacy position
  is always `:outcome-unavailable`; `complete-outcome-verified?` is true only
  for v2.
- `authorisation-outcome-consistency` proves outcome concurrence from committed
  roots: all v2 refs must embed the containing `:authorisation/id` (rejects
  substitution), share one `:outcome/root`, and match the target's
  `:target/proposed-content-root`.
- The v2 verifier fails closed: wrong schema, missing/invalid outcome root, hash
  mismatch (tampered without rehash), and signature failure (modified+rehashed
  without re-signing) each fail at the correct stage. A modified+rehashed+
  re-signed artifact is a **new position** subject to the full checks.

Rule: whole-outcome agreement (ADR-0007 D1;
`THREE_MEMBER_CONSENSUS_THROUGHOUT.md` §3.1) is proven from the committed
`:outcome/root`, never from matching `:approve` values or field summaries.

## 3. Authority, not "no artifact is authoritative"

The earlier phrase "no artifact, hash, or process is authoritative" is
overbroad. Correct model:

- no **single researcher position** is sufficient authority;
- terminal artifacts — a verified canonical certificate — **are** authoritative
  once verified;
- a coordinator/state machine is authoritative for the **transition race**
  (which authorised decision wins a conflicting transition).

The code already models the last two points: `cancellation-window-assertion`
distinguishes `:assurance :independent-replay` (recomputed from committed
evidence) from `:assurance :structural-check`
(`canonical_force_authorisation.clj:790+`).

## 4. decision-hash-valid is necessary, not sufficient

`verify-decision-hash` is an **integrity gate**, not the **authority gate**. A
valid hash proves the preimage is what was signed over; it does not alone make
a decision authoritative. Complete chain, in order:

1. preimage integrity (declared hash equals hash over preimage);
2. hash integrity (cryptographic, domain-separated);
3. signature authenticity (Ed25519, key-bound);
4. seat eligibility (distinct, constituted, eligible, role-bound);
5. scope binding (same decision scope);
6. outcome concurrence (same whole outcome);
7. policy conformance (declared, three-member-standard-conforming,
   `canonical_force_authorisation.clj:111-119`);
8. lifecycle applicability (window/certificate still bound to the current
   snapshot).

Invald or tampered positions are **excluded from the support/vote count** but
**preserved in the verification report** as corruption/attempt evidence. They
are evidence, not nothing.

Rehash semantics (correcting the earlier "rehashed fails hash-validity" claim):
- tampered without rehash → hash mismatch;
- modified and rehashed → signature mismatch unless re-signed;
- re-signed by a valid member → potentially a **new valid position**, subject
  to scope/panel/equivocation/policy checks.

## 5. Replay vs out-of-process: five properties, not one

Conflating these five process properties was the core error in the earlier
"out-of-process vs independent replay" section. They are independent:

1. **recomputable replay** — classification recomputable from committed
   evidence (contract 8 gives this; `:assurance :independent-replay`);
2. **process separation** — replay runs in a separate process (contract 8 MAY
   support this; not guaranteed by the return value);
3. **implementation independence** — replay uses a different implementation
   (contract 8 does NOT give this);
4. **state independence** — replay does not share mutable state with the
   primary decision path (contract 8 does NOT give this);
5. **transition atomicity** — a durable atomic boundary decides which
   authorised transition actually commits (compare-and-transition, out of
   process/out of scope of the classification).

Contract 8 (`cancellation-window-assertion`) gives property 1, may give
property 2, and gives **none** of 3–5. Claiming "independent replay" without
qualifying which properties are satisfied is overclaiming.

## 6. Equivocation is a machine-visible classification, not a policy guess

Unique equivocation key:

```text
[review-round/hash, authorisation/id, decision-scope, member/id]
```

The decision scope is **derived from the complete committed projection**
(`decision-scope-projection`: domain-hash over authorisation/id, review-round
hash, request-root). The outcome is deliberately not part of the scope: two
positions over the same scope but different outcomes are exactly the
equivocation case. `:independent-replay`-compatible recomputation means the
report never takes caller-supplied grouping or scope.

**Implemented (Slice B)** in `resolver-sim.assurance.three-member-authority`:

- `detect-equivocation` — machine-visible detection, no policy. Any two
  positions with different committed hashes under one key are materially
  different (approve vs dissent, approval of different outcome roots, distinct
  dissents). Identical duplicates are compatible and never flagged.
- `classify-equivocating-seat` — policy consequence only
  (`:invalid-seat` default, `:count-as-dissent`, `:fail-certificate`). One
  member occupies at most one counted seat; equivocation never creates votes;
  all equivocating artifacts are preserved.
- `evaluate-three-member-authority` — the full recomputable authority report
  with `:valid-supporting-positions :valid-dissenting-positions
  :valid-qualifying-positions :absent-members :invalid-positions
  :equivocating-members :unknown-members :re-scoped-positions
  :duplicate-seat-positions :counted-support :required-threshold :outcome-root
  :policy-conforming? :identity-separate? :authority-status :authority/reasons`.

Position validity is `decision-hash-valid?` (recomputed, never caller-supplied)
AND an externally supplied signature check (keys are outside this layer). The
report proves common decision scope and common complete outcome from committed
roots, never from matching `:approve` values. `:identity-separate?` names seat
distinctness — never real-world independence.

## 7. Cancellation: four predicates, not one

`classify-cancellation` collapsed the gates into a single
`:cancellation/possible? (and profile-conforming? window-open?)`. **Implemented
(Slice C)**: `classify-cancellation` now exposes
`:cancellation/window-possible?` (with `:cancellation/possible?` retained only
as a deprecated derived view), and `classify-cancellation-gates` composes four
predicates owned by four layers:

- `:cancellation/window-possible?` = `(and profile-conforming? window-open?)` —
  lifecycle reconciliation, no researcher decision semantics;
- `:cancellation/authorised?` = `cancellation-authorised?` =
  `(three-member-standard-conforming? certificate)` — certificate layer, never
  reopens lifecycle state;
- `:cancellation/executable?` = `(and window-possible? authorised?
  current-snapshot-binding-valid?)` — contract 7 whole-snapshot binding;
- `:cancellation/committable?` = `(and executable? conflict-key-transition-won?)`
  — the authoritative transition race. The canonical layer consumes a supplied,
  verified race result; it never claims durable cross-process atomicity
  (JVM-local compare-and-transition is not durable coordination).

## 8. "No panel required" never means "no verification required"

Deterministic operations still need evidence: profile, lifecycle state,
cutpoint, time/event, target binding, conflict-key result, provenance. Only
researcher discretion is not required. The three-member cell is assigned to the
adjudicative boundary, not to deterministic execution
(`THREE_MEMBER_CONSENSUS_THROUGHOUT.md` §5–5.2).

**Implemented (Slice D)**: `deterministic-operation-evidence` is a data-driven
evidence checklist per deterministic operation in `cancellation-operations`
(expiry at deadline, deterministic invalidation, post-cutpoint rejection,
certified execution, fallback, submission); `deterministic-operation-evidence-valid?`
fails closed on missing/nil/inconsistent evidence, and
`deterministic-operation-verified?` is true only when the operation carries all
required evidence. A certificate never turns a deterministic operation into a
canonical cancellation decision (`cancellation-decision-required?` remains
false for all deterministic operations).

## 9. Textual corrections to the earlier outline

| As written | Corrected |
|---|---|
| uadline | deadline |
| legacy fiscal | legacy evidence |
| separately-f raining repla remainder | separately-running replay verifier |
| transparent durable bind-out-of-process | durable atomic transition boundary |

## 10. "Three-member throughout" interpretation

"Three-member throughout" = **three-member authority semantics throughout the
evidence chain**. The panel may be constituted per review round / decision
scope (as it is today: a frozen cell per round, `review_round.clj`); what stays
continuous is the evidence/authority model, not a frozen electorate.

## 11. Authority report shape — implemented

The §6 report shape is now implemented by
`evaluate-three-member-authority` (`resolver-sim.assurance.three-member-authority`):

```text
:valid-supporting-positions   (same committed outcome, counted toward threshold)
:valid-dissenting-positions   (same scope, dissent)
:valid-qualifying-positions   (scope-concurring, not outcome-concurring, incl.
                               v1 outcome-binding-unavailable approvals)
:absent-members               (constituted seats with no valid position)
:invalid-positions            (excluded from count, PRESERVED as evidence)
:equivocating-members         (member counts once; policy decides status)
:unknown-members              (not constituted/eligible)
:re-scoped-positions          (fail at the scope stage, not the count)
:duplicate-seat-positions     (identical dupes preserved, never extra votes)
:counted-support / :required-threshold
:outcome-root / :policy-conforming? / :identity-separate?
:authority-status / :authority/reasons
```

## 12. Gaps and next actions

1. ~~**Outcome-root binding**~~ **DONE (Slice A)** — `researcher-decision.v2`
   binds `:outcome/root` under a new `RESEARCHER_DECISION_V2` domain separator;
   v1 remains an explicitly versioned legacy path. `:panel/root` is unnecessary:
   `:review-round/hash` commits the exact seats. `build-signed-decision-v2`,
   `verify-signed-decision-v2`, `classify-decision-version`,
   `decision-outcome-binding`, `complete-outcome-verified?`,
   `decision-hash-valid?`, and `authorisation-outcome-consistency` are
   implemented with corruption, cross-version, substitution, and replay tests
   (§2.3). Residual: effects / state-transition commitment and outcome-relevant
   parameters are still not bound by a signed position.
2. ~~**Equivocation classification**~~ **DONE (Slice B)** — `detect-equivocation`,
   `classify-equivocating-seat`, and `evaluate-three-member-authority` are
   implemented with the ten adversarial scenarios (§6/§11). Decision-scope is
   derived from the complete committed projection, never an under-bound key.
3. ~~**Cancellation predicate split**~~ **DONE (Slice C)** — four predicates with
   layer ownership (`classify-cancellation-gates`), `current-snapshot-binding-valid?`
   (contract 7), deprecated legacy `:cancellation/possible?`, and the full
   window/certificate/snapshot/race matrix (§7).
4. ~~**Deterministic-op evidence checklist**~~ **DONE (Slice D)** —
   `deterministic-operation-evidence` + `deterministic-operation-evidence-valid?`
   encode the §8 checklist; negative tests cover missing/nil/inconsistent
   evidence (§8).
5. ~~**Verification report shape**~~ **DONE (Slice B)** — §11 shape is machine
   enforced by `evaluate-three-member-authority`.
6. ~~**Replay claims**~~ **DONE (Slice D)** — `cancellation-window-assertion`
   now states in its contract that `:independent-replay` means recomputation
   independence and establishes exactly one of the five properties (§5); the
   ADR-0007 contract 8 vocabulary already defined it that way.
7. **Out of scope, documented** — implementation independence, state
   independence, and transition atomicity remain outside the current
   classification layer; the durable atomic transition boundary is a
   coordinator/out-of-process concern. Coordinators, Rust/on-chain atomicity,
   and independently implemented external verifiers remain out of scope.

## 13. Outcome-source hardening and the decision-subject contract

### 13.1 No plurality-derived outcome identity

The authority report NEVER derives the authoritative outcome from the submitted
positions. The outcome being decided is obtained only from the authorisation
target (`:target/proposed-content-root`). Authority requires every supporter to
have signed exactly that root:

```clojure
(and (= supporter-outcome-roots #{authoritative-target-root})
     (>= counted-distinct-supporters required-threshold))
```

- `:decision-scope/root` — derived scope (the question);
- `:position/outcome-root` — each member's signed answer (per position);
- `:authoritative-target-root` — independently obtained from the FA target.

When no authoritative target outcome exists the report classifies
`:outcome-source :target-outcome-unavailable` and authority is never reached —
plurality never manufactures a root, so two members cannot agree an outcome that
was never the target. A single supporter signing a non-target outcome fails
concurrence (`:non-target-outcome-concurrence`); non-target approvers are
`:valid-qualifying-positions`, never counted.

### 13.2 decision-subject.v1 (reusable, not researcher-decision.v3)

`resolver-sim.benchmark.decision-subject` defines the reusable, content-addressed
subject artifact (`decision-subject.v1`) committing:

- `:subject/content-root` — the subject content;
- `:subject/parameters-root` — the relevant parameters;
- `:subject/effects-root` — the effects;
- `:subject/branch-descriptor-hash` — the branch;
- `:subject/transition-root` — the intended state transition.

Domain separator `DECISION_SUBJECT_V1`; `build-decision-subject`,
`validate-decision-subject`, `verify-decision-subject-root` recompute the root
(integrity); `subject-commitment-summary` lists what is committed. This is the
STABLE subject that a future decision version binds as `:subject/root`, closing
the Slice-A residual (effects/parameters/branch/transition were not committed by
a signed position). It is deliberately decision-schema-independent.

### 13.3 Consumer-enforcement audit

`evaluate-three-member-authority` is the canonical authority gate. The builder
conveniences (`authorisation-approved?`, `authorisation-status`) compute status
from `:approve` counts and are NOT authority. Audit results:

- **`run/verdict_policy.clj`** gates supersession execution on
  `authorisation-approved?` — a bypass: a v1-only "approved" artifact, or two
  approves over different outcome roots, passes the builder status yet fails
  `evaluate-three-member-authority`. Consumers must gate on the authority
  report instead.
- **`verify-authorisation-usable`** uses `authorisation-approved?` — usability,
  not authority.
- **`force_authorised_execution_evidence`** verifies signatures + policy
  threshold but not three-member outcome concurrence or equivocation.
- Proven by `consumer-enforcement-test`: builder `:approved` ≠ authority, and
  divergent-outcome approvals are refused by the report.

### 13.4 Committed equivocation policy and report trust boundary

- The equivocation consequence is committed, not verifier-time: policy is taken
  from `:review-round/equivocation-policy`, else the supplied option, else the
  canonical `:invalid-seat` default; the applied policy is surfaced as
  `:equivocation-policy-applied`.
- Consumers must either recompute the report from committed inputs or bind it:
  `authority-report-root` produces a canonical root over the recomputed report
  (`THREE_MEMBER_AUTHORITY_REPORT_V1`), and `recompute-authority-report`
  re-evaluates from committed inputs and compares — a stored classification that
  fails to recompute is rejected. A stored classification is never trusted on
  its own.
