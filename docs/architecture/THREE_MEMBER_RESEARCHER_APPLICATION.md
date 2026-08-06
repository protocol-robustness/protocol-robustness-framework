# THREE-MEMBER RESEARCHER APPLICATION: Corrected Authority Model

Status: **Design note** (records the review correction of the "how the
three-member researcher applies" outline; not a new implementation commitment)
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

Two incompatible valid signatures under one key ⇒ machine-visible equivocation.
The member counts once; policy decides the seat's status
(invalid/dissent/decision fails); proof is preserved. The forensic layer already
implements runner-id and crypto equivocation detection
(`test/forensic_python/test_phase3.py:344-395`), but the canonical Clojure
decision path does not yet classify equivocation into the verification report.

## 7. Cancellation: four predicates, not one

`classify-cancellation` currently collapses the gates into a single
`:cancellation/possible? (and profile-conforming? window-open?)`
(`canonical_force_authorisation.clj:779`), and `cancellation-possible?`
(`:703`) is window-only. Three distinct questions are conflated (profile +
window, certificate authority, transition race). Replace with:

- `:cancellation/window-possible?` = `(and profile-conforming? window-open?)` —
  lifecycle gate only;
- `:cancellation/authorised?` = `(three-member-standard-conforming? certificate)`
  — authority gate;
- `:cancellation/executable?` = `(and window-possible? authorised?)` —
  certificate still bound to the current snapshot;
- `:cancellation/committable?` = `(and executable? conflict-key-transition-won?)`
  — durable state race (`cancellation-conflict-key`, `:570`).

## 8. "No panel required" never means "no verification required"

Deterministic operations still need evidence: profile, lifecycle state,
cutpoint, time/event, target binding, conflict-key result, provenance. Only
researcher discretion is not required. The three-member cell is assigned to the
adjudicative boundary, not to deterministic execution
(`THREE_MEMBER_CONSENSUS_THROUGHOUT.md` §5–5.2).

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

## 11. Proposed verification-report shape

Suggested result shape (not yet implemented):

```text
:valid-supporting-positions   (same outcome, counted toward threshold)
:valid-dissenting-positions   (same scope, dissent)
:valid-qualifying-positions   (scope-concurring, not outcome-concurring)
:invalid-positions            (excluded from count, PRESERVED as evidence)
:equivocating-members         (member counts once; policy decides status)
:unknown-members              (not constituted/eligible)
:duplicate-seat-positions     (identity non-distinct; excluded)
:authority-status             (concurrence reached / not)
```

## 12. Gaps and next actions

1. ~~**Outcome-root binding**~~ **DONE (Slice A)** — `researcher-decision.v2`
   binds `:outcome/root` under a new `RESEARCHER_DECISION_V2` domain separator;
   v1 remains an explicitly versioned legacy path. `:panel/root` is unnecessary:
   `:review-round/hash` commits the exact seats. `build-signed-decision-v2`,
   `verify-signed-decision-v2`, `classify-decision-version`,
   `decision-outcome-binding`, `complete-outcome-verified?`, and
   `authorisation-outcome-consistency` are implemented with corruption,
   cross-version, substitution, and replay tests (§2.3). Residual: effects /
   state-transition commitment and outcome-relevant parameters are still not
   bound by a signed position.
2. **Equivocation classification** — port the forensic runner/crypto equivocation
   logic into the canonical verification report; implement the §6 key and
   member-counts-once rule.
3. **Cancellation predicate split** — refactor `classify-cancellation` from one
   combined `:cancellation/possible?` to the four-predicate model (§7); keep
   `classify-lifecycle-window` free of decision semantics.
4. **Deterministic-op evidence checklist** — encode the §8 evidence
   requirements (profile, lifecycle, cutpoint, time/event, target binding,
   conflict-key, provenance) as a checklist for non-discretionary operations.
5. **Verification report shape** — adopt the §11 result shape so invalid
   positions remain visible as evidence while being excluded from the count.
6. **Replay claims** — qualify every "independent replay" claim with which of
   the five properties (§5) are actually satisfied; contract 8 gives only
   recomputable replay today.
7. **Out of scope, documented** — implementation independence, state
   independence, and transition atomicity remain outside the current
   classification layer; the durable atomic transition boundary is a
   coordinator/out-of-process concern.
