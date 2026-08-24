# Prospective Party-Cancellation Flow — Contract Draft v2 (DESIGN ONLY)

Status: design draft — reviewed; approve-as-design-draft recorded after the
dependency-DAG, stale-permit, cross-surface atomicity, wall-clock, and
scope-validation clarifications.
Implementation: none
Normative roots: none

Blocked by:

1. irreversible-state reconciliation with simulation-side regimes (GAP-F
   blocker 5);
2. implementation of the shared atomic transition seam (§4 invariant).

The GAP-F choice stands decided: additive prospective path; retrospective
artifacts untouched.

## 0. Reuse decisions per primitive

| Primitive | Decision | Notes |
| --- | --- | --- |
| Party-command structure & identity | ADAPT | Intent generalizes the envelope under NEW domain tag `SEW_PARTY_CANCELLATION_INTENT_V1`. |
| signed-external-decision | REUSE verbatim | `sign-envelope`/`verify-envelope`, trust-policy keys/roles/statuses. |
| `cfa/cancellation-conflict-key` | REUSE verbatim | `{target/id lifecycle/profile-id lifecycle/profile-version}` — serialized on by every stage. |
| Cancellation window & lifecycle-head | REUSE + REGISTER PROFILE | Fail-closed classification; SEW escrow profile pending blocker 1. |
| Authority-policy & key identities | REUSE | Rooted `sew-party-cancellation-policy.v1`; ed25519 roles. |
| Refund / terminal-effect builders | REUSE kinds, DEFER kernel | Single kernel call site stays M6. |
| Receipt primitives | ADAPT pattern later | `applied-effect-receipt.v1` recompute-valid pattern; CC3 idempotency model. |
| Authorization slot on applied statement | DO NOT CHANGE TYPE IN V1 | Slot is ADDRESSED BY AN ANTECEDENT ROOT and its resolved artifact carries the retro-attesting command — exact projection pinned in §3. |

## 1. Artifact A — `party-cancellation-intent`

Signed envelope, NEW domain tag `SEW_PARTY_CANCELLATION_INTENT_V1`; closed
top-level envelope; unknown keys rejected; tagged bindings, never nil.

### Signer consent scope

The signature commits EXACTLY: state-before snapshot root; lifecycle head +
profile/version; related-claims scope IF bound (tagged); claimant option IF
bound (tagged); `:protocol/id`; intended effect class (recomputed
deterministically at issuance from bound roots — never invented). Validity is
lifecycle-sequence bounds only; no wall-clock field is signed or consulted
(§6). Nothing issued later may widen this scope.

### Binding representation and POLICY VALIDATION

Optional scopes use tagged closed values:

```clojure
{:binding/status :not-applicable}
{:binding/status :bound :binding/root "sha256:…"}
```

Field-absent and nil are both refused. **`:not-applicable` is not a bypass**:
permit issuance MUST verify against the rooted policy for THIS operation and
escrow state that each optional scope may lawfully be unbound. Policy declares
required/optional scopes per operation kind; a `:not-applicable` on a
policy-required scope fails issuance with `:permit/scope-not-permitted`.
Syntactic explicitness never substitutes for scope authority.

## 2. Artifact B — `prospective-permit`

Fields: `:permit/schema` · `:intent/root` · verified `:principal` ·
`:window/state :open` + profile id/version ·
`:fence {:snapshot-root :lifecycle-head-root :expected-version}` ·
`:conflict/key` · `:effects/intent-kind` · `:policy/root` ·
`:issuer {:role :key-id}` · `:issuer-config/root` · `:lifecycle-bounds` ·
`:permit/root`. Domain tag at implementation:
`SEW_PARTY_CANCELLATION_PERMIT_V1`.

## 3. Artifact dependency DAG (blocking correction #1)

### 3.1 The existing anti-cycle projection, exactly

The apparent cycle `statement root → command root → statement root` does not
exist because the authorization slot is **addressed by an antecedent root**
and the retro-attestation lives in the ARTIFACT FOUND AT THAT ADDRESS:

```
plan(snapshot, policy, principal)
  → preconditions/root  P                       (antecedent)
statement S commits :authorization/root = P     (ordinary_admission_test.clj:27)
R = H(S)                                        ; final statement root
command C = H({schema, :cancel, principal, R})  (party_command.clj:11-13;
                                                 ordinary_admission_test.clj:31-40)
resolver maps address P → artifact {:artifact/root P :party-command C}
                                                (ordinary_admission_test.clj:43)
admission resolves :authorization roots, pulls :party-command from the
artifact at that address, verify-command(C) asserts C.subject-root == R
                                                (admission.clj:108-115)
```

Dependency order: `P ← plan`; `R ← {…, P}`; `C ← R`. Acyclic. The command is
a RETROSPECTIVE attestation of the finished statement; it is never an input
to computing that statement's root.

### 3.2 Full DAG table

| Artifact | Constructed when | Roots it commits | Root that signs/verifies it |
| --- | --- | --- | --- |
| Party intent | Before permit | snapshot (state-before), lifecycle head+profile-version, scoped binding roots, action, protocol id, effect class | Party signature (ed25519, intent domain) |
| Permit | Before transition | intent root, fence {snapshot, head, expected-version}, conflict key, policy root, issuer config root, lifecycle bounds | Permit issuer (`:cancellation-permit-issuer`) |
| Transition record | At commit | permit root, state-before/effects/state-after roots, fence actually used | Transition authority via the §4 CAS seam (fence ownership) |
| Applied statement V2 | After commit | transition record root, command slot (antecedent-addressed, §3.1), prospective-permit root | Retrospective verification (`admission/admit` future branch) + V2 party command |
| Party command | Exact existing position: after the final statement root exists | Exact existing subject root: the complete applied-statement root (`operation/root`) | Party signature over `{schema, action, principal, statement-root}` (existing domain) |
| Receipt | After commit | transition/statement root (+ terminal outcome) | Receipt issuer (pattern: `applied-effect-receipt.v1`) |

### 3.3 Proofs required by review

* **No content-addressing cycle:** statement roots depend only on antecedents
  (preconditions/policy/snapshot/permit — all computed earlier); commands and
  receipts depend on finished roots. No artifact's root is an input to its own
  preimage.
* **No premature `:applied`:** `:execution :status :applied` exists only on
  statements constructed AFTER the fenced transition commits (V1 rule,
  `operation.clj:30`, unchanged).
* **No retrospective-only dependency:** the prospective transition depends on
  permit + fence + transition authority ONLY. The V2 party command and receipt
  are post-commit attestations; absence of the V2 command cannot retroactively
  invalidate an already-committed transition UNLESS a future protocol rule
  explicitly makes command possession part of commit validity — that would be
  a deliberate, versioned decision, not a default.
* **V1 meanings unchanged:** V1 statements keep command-in-artifact-at-
  antecedent-address semantics; `statement_boundary`'s envelope is untouched.

## 4. Reservation machine and cross-surface atomicity (corrections #2, #3)

Single serialization seam owns each conflict key; BOTH surfaces route every
mutation through it. **Invariant: no Surface A or Surface B state mutation may
commit unless that mutation owns the current conflict-key fence issued by the
seam.** Sharing the key value alone guards nothing; a post-transition
registration of results is insufficient — reservation, lifecycle head/version,
transition publication, and terminal outcome must bind atomically (one
transaction, one CAS state, or a store-issued fence mandatory at commit).

Reservation transitions (atomic over `(conflict-key → slot)`):

```text
empty                          → ISSUE live permit
live, fence current            → same event+same intent  → EXACT REPLAY (return P)
                                 same event+different intent → :permit/event-intent-mismatch
                                 different event            → :permit/conflict-key-live
live, FENCE MISMATCH (stale)   → atomically mark :invalidated/stale
                                 respond :permit/invalidated-stale to the attempt
                                 exact replay of the stale permit returns the SAME
                                   invalidated verdict (stable, auditable)
                                 new intents may reserve only AFTER current window
                                   eligibility is rechecked (classify-lifecycle-window
                                   :open at the CURRENT head)
terminal                       → idempotent acknowledgement only
```

Staleness therefore cannot permanently lock a conflict key: invalidation is
part of the same atomic step that detects it, and re-reservation requires a
fresh signature plus a live-window recheck. Cross-reference: Surface A
certified decisions participate through the same seam so precedence and
fencing have one authority.

## 5. Issuer role and configuration

Unchanged from prior revision: party signer role `:protocol-party-cancellation`;
issuer role `:cancellation-permit-issuer`; rooted
`party-cancellation-permit-config.v1` rides on every permit; trust-policy
statuses re-checked at use time.

## 6. Authoritative time — wall-clock explicitly non-authoritative

Because the lifecycle sequence is the only time source:

* Wall-clock timestamps are DIAGNOSTIC METADATA ONLY.
* They are EXCLUDED from canonical permit identity (outside every rooted
  projection).
* Changing, adding, or omitting them cannot alter issuance validity, transition
  validity, or replay outcomes.
* ALL validity decisions derive from the rooted lifecycle head, window profile
  version, and fence.
* If any wall-clock field were ever acceptance-relevant, it would have to
  become authoritative and committed (inside the signed projection). An
  unsigned-but-acceptance-relevant field is forbidden — that is precisely the
  downgrade shape this contract exists to prevent.

## 7. Resubmission genesis V2 — confirmation (no dependency introduced)

Cancellation binds protocol identity directly (`:protocol/id`) and escrow
identity via target/workflow ids; configuration and policy identity flow
through the rooted policy/window artifacts above. No resubmission-specific
`:chain/id` is introduced: that identity is derived from family-id +
configuration inside the resubmission family (`resubmission/genesis.clj:189`)
and cancellation does not operate on that family. Genesis-V2 stays unmerged
here and unneeded here.

## 8. Non-goals

No namespaces, domain tags, schemas, or mutations; V1 artifacts byte-stable;
CC3 retains cancel-and-terminate exclusively.

## 9. Remaining open items

1. Irreversible-state list reconciliation (blocker 1).
2. Store substrate implementing the §4 seam for escrows.
3. Expected-version source at intent-signing time.
