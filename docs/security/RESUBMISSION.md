# Researcher resubmission contract (design, revision 3)

Status: **core Clojure implementation begun; full integration deferred.** The
contract below is authoritative. The pure core is implemented and tested under
`src/resolver_sim/resubmission/` with golden canonical projections locked in
`test/resolver_sim/resubmission/resubmission_test.clj`:

| Step (§22) | Status |
| --- | --- |
| 1. `submission-attempt-receipt.v1` (projection, root statuses, submitter identity, validator authority, immutable dispositions) | done — `receipt.clj`, `disposition.clj` |
| 2. package commitment cutpoints (circularity removed) | done — `basis.clj` |
| 3. missing/invalid parent result → `:submission-repair` | done — `derive_kind.clj` |
| 4. chain-admission cutpoint + `:admitted`/`:not-admitted` | done — `chain.clj` (atom/CAS) |
| 5. `resubmission-link.artifact.v1` | done — `link.clj` (verify-artifact-compatible) |
| 6. acceptance-report binding | partial — `verify.clj` composes the report; receipt `:attempt-receipt/evaluation` block defined |
| 7. CAS admission after golden vectors | golden vectors done; production transactional store deferred |
| 8. transaction-ordering layer | done — `transaction/ordering.clj`, `transaction/protocol.clj`, `transition.clj`, `store.clj`; `chain.clj` is a facade; trace-equivalence tests |
| 9. attempt-receipt issuance | done — `resubmission/issuance.clj` + out-of-process signer authority `prf resubmission issue` (`commands/resubmission_issue.clj`); independent transition re-derivation; attestation-after-commit |

Remaining before enabling in the acceptance pipeline: historical policy/key
stores, the Python/JVM equivalence for the new projections, a durable
transactional backend behind `resolver-sim.transaction.protocol/transact!`, a
broader trace corpus, and wiring the conditional resubmission-link artifact
into `validate_artifact_registry.py`.

## Receipt issuance

The validator owns the mutable chain store; for each issuance it presents
`state-before + command + ordering + candidate-receipt` to the out-of-process
signer authority `prf resubmission issue` (key isolated via `PRF_VALIDATOR_KEY`,
never in argv). The authority:

1. verifies `:request/hash`;
2. re-runs the pure transition over the presented pre-state — it must commit;
3. verifies ordering integrity and that the action is
   `:prf.resubmission/admit-child`;
4. compares the derived `state-after-root` to the ordering's committed root;
5. verifies the candidate receipt shape and its chain binding (admission
   `:admitted`, ordering hash, sequence, parent);
6. signs via attestation-after-commit (`receipt/sign-receipt` on the unsigned
   projection — identity unchanged).

Because the receipt id hashes the UNSIGNED projection, it is deterministic
across validator keys and is locked as a golden vector. Full receipt persistence
alongside the state, `:committed-receipt-pending` recovery, and moving the store
into the authority remain deferred until a durable backend exists.

## Transaction-ordering layer

Three distinct notions of order (never conflated):

- **resubmission sequence** — position in the research attempt chain
  (`:resubmission/sequence`);
- **disposition order** — order of immutable status events affecting a receipt;
- **transaction order** — the linearization order of competing state
  transitions against the mutable store (`:transaction/commit-index`).

The pure semantic transition (`resolver-sim.resubmission.transition/apply-action`)
owns action dispatch, the PINNED rejection precedence, deduplication, idempotent
replay, head/sequence/disposition validation, cycle prevention, state-root and
effects-root derivation, and emitted effects. It never mutates anything.

`resolver-sim.transaction.protocol/transact!` is the ONLY mutation surface and
the atomic execution boundary. The in-memory
`resolver-sim.resubmission.store/ResubmissionChainStore` implements it: load
snapshot, invoke the pure transition, CAS the version, attach
`transaction-ordering` evidence, commit, retry on contention. The store owns no
domain rules; a RocksDB/SQL/DynamoDB backend can implement the same protocol
without becoming a second semantic implementation.

Namespaced action vocabulary (canonical, independent of the Clojure source
namespace): `:prf.resubmission/admit-child`, `:prf.resubmission/apply-disposition`.
Outcomes such as `:idempotent-replay` / `:parent-not-current-head` are
transaction RESULTS, never actions.

Pinned rejection precedence (observable and pinned in tests/traces): idempotent
replay → idempotency-content mismatch → duplicate content → transplant → parent
eligibility → current-head → successor existence → sequence → cycle → commit
contention.

Generic ordering evidence (`resolver-sim.transaction.ordering`):

```
ordering-hash = "sha256:" + domain-hash(
    "prf.transaction-ordering.v1",
    canonical-bytes-v2(unsigned-ordering-projection))
```

The unsigned projection excludes only the self ordering hash; it commits
action, scope, conflict-key, commit-index, previous-transaction-hash,
state-before/state-after roots, effects-root, and expected/observed. No hash
cycle: state-after-root excludes `:transaction/last-hash` (the ordering hash),
and the ordering never commits the attempt receipt artifact. For an admitted
child the transaction persists, atomically, the successor/head/version/
idempotency/content/receipt writes plus the ordering record — all or none.

## 1. Authority model: the submission-attempt receipt

Every submission attempt (accepted, rejected, or failed) produces a signed,
content-addressed receipt issued by the acceptance validator. A resubmission
links to this receipt, not to a self-declared run and results artifact. This is
the root of authority, because the previous submission may have failed from an
invalid publisher signature, malformed commitment, missing results artifact,
duplicated/untrusted run id, or a registry that never reached accepted status.

```clojure
{:attempt-receipt/schema "submission-attempt-receipt.v1"
 :attempt-receipt/id "sha256:<hex>"                ; derived; see §7
 :attempt-receipt/submitted-bundle-root "sha256:<hex>" ; FINAL assembled bundle

 :attempt-receipt/roots                          ; status-bearing root set (§2)
 {:research-subject {:root/schema "research-subject-root.v1"
                     :status :verified | :invalid | :unavailable
                     :hash "sha256:<hex>"}
  :execution-context {:root/schema "execution-context-root.v1"
                      :status :verified | :invalid | :unavailable
                      :hash "sha256:<hex>"}
  :results {:root/schema "results-root.v1"
            :status :verified | :invalid | :missing
            :hash "sha256:<hex>"}
  :submission-basis {:root/schema "submission-basis-root.v1"
                     :status :verified | :invalid
                     :hash "sha256:<hex>"}}

 :attempt-receipt/results
 {:status :valid | :invalid | :missing
  :submitted-hash "sha256:<hex>"                 ; optional
  :verified-hash "sha256:<hex>"}                 ; only when :valid

 :attempt-receipt/submitter                       ; identity provenance (§8)
 {:status :verified | :authenticated-session | :claimed | :missing
  :researcher-id "..."
  :identity-source :publisher-signature | :submission-auth | :authenticated-session
  :policy-hash "sha256:<hex>"
  :key-id "..."}

 :attempt-receipt/outcome :accepted | :rejected | :system-failure | :indeterminate
 :attempt-receipt/finality :provisional | :final
 :attempt-receipt/resubmission-eligibility :eligible | :ineligible | :retry-same-attempt
 :attempt-receipt/lifecycle-status :active | :withdrawn | :revoked | :superseded

 :attempt-receipt/chain                           ; admission cutpoint (§9)
 {:admission-status :admitted | :not-admitted
  :family-id "sha256:<hex>"
  :sequence 2
  :parent-receipt-hash "sha256:<hex>"}

 :attempt-receipt/evaluation                      ; binds the acceptance report (§11)
 {:acceptance-report-hash "sha256:<hex>"
  :validator-version "..."
  :policy-hash "sha256:<hex>"
  :evaluated-bundle-root "sha256:<hex>"
  :evaluated-at "ISO-8601-UTC"}

 :attempt-receipt/findings [{:finding/id "sha256:<hex>" ...}]   ; §12

 :attempt-receipt/validator                        ; full authority (§10)
 {:id "..."
  :version "..."
  :policy/id "..." :policy/version "..." :policy/hash "sha256:<hex>"
  :authorisation/id "..."
  :key/id "..."
  :signature/algorithm :ed25519
  :signature "<hex>"}

 :attempt-receipt/observed-at "ISO-8601-UTC"}
```

The previous results hash may be carried as a convenience field when one
validly existed, but it is never the root of authority and never required for
the missing/malformed cases.

## 2. The four (typed, status-bearing) parent roots

The receipt MUST carry the parent's four roots with status, because the earlier
submission may not have provided enough valid content to derive every root:

| Root | Schema | Status set | Commits |
| --- | --- | --- | --- |
| `research-subject` | `research-subject-root.v1` | `:verified | :invalid | :unavailable` | question, scenario, parameters, policy, methodology |
| `execution-context` | `execution-context-root.v1` | `:verified | :invalid | :unavailable` | implementation, seed, environment, tool versions |
| `results` | `results-root.v1` | `:verified | :invalid | :missing` | canonical semantic result |
| `submission-basis` | `submission-basis-root.v1` | `:verified | :invalid` | pre-link submission material under the cutpoint (§3) |

Status is essential: a parent rejected before results were produced has
`results :missing`, so equality-based derivation cannot assume a root exists.

**Relationship among acceptance-question-root, research-subject-root,
family-id (explicit):**
- `acceptance-question-root` == `research-subject-root` of the same attempt
  (canonical equivalence; the receipt carries it once, as `research-subject`);
- `family-id = domain-hash("prf.resubmission-family.v1", research-subject-root)`;
- the link's `family-id` must equal the receipt-derived family-id of the parent
  chain head.

## 3. Package commitment cutpoints (circularity removed)

The revision-2 `submission-package-root` created a cycle:

```
submission-package-root → registry / publisher envelope → resubmission-link
artifact hash → resubmission-link body → submission-package-root
```

The fix is two different package commitments:

```clojure
:resubmission/current
{:submission-basis-root "sha256:<hex>"   ; IN the link
 :final-bundle-root nil}                 ; NEVER placed in the link
```

- **`submission-basis-root`** commits the pre-link submission material under a
  precisely defined cutpoint; it is placed in the link.
- **`final-bundle-root`** is never placed in the link. It is committed by the
  validator-issued attempt receipt as `:attempt-receipt/submitted-bundle-root`.

Dependency chain (acyclic):

```
submission basis
      ↓
resubmission link      (commits basis-root)
      ↓
publisher envelope     (commits resubmission artifact hash)
      ↓
complete submitted bundle   (basis + link + envelope + signatures)
      ↓
validator-issued attempt receipt   (commits submitted-bundle-root)
```

**Basis projection (explicit contents).** The `submission-basis-root` commits,
under the cutpoint:

- the results artifact (pre-link, pre-final-signature);
- the current allocation certificate;
- execution evidence;
- registry entries other than the resubmission link;
- the UNSIGNED publisher-envelope fields (excluding the final signature);
- publisher policy and key identity.

**The basis projection excludes (at least):**

- the resubmission link;
- the final publisher signature;
- the final submitted-bundle root;
- the attempt receipt.

The basis projection rules reuse `canonical-bytes-v2` (see §6): keyword/string
keys, sorted map keys, non-negative integers, NFC-normalized UTF-8, ordered
vectors. A golden fixture locks the cutpoint.

## 4. Derived resubmission kind (three direct relationships)

Kind is derived from root comparisons against the parent receipt, never trusted
from a field. Revision 3 introduces a third direct relationship,
`:submission-repair`, to cover the missing/invalid-previous-result case that
`:exact-retry` cannot satisfy (no authoritative previous results root exists to
compare).

| Kind | Parent result | Current result | Other requirements |
| --- | --- | --- | --- |
| `:exact-retry` | `:verified` | same verified root | subject unchanged; current independently passes ≥1 blocking gate the parent failed (§13) |
| `:corrected-result` | `:verified` and rejected semantically | different root | subject unchanged |
| `:submission-repair` | `:missing` / `:invalid` | new authoritative result | subject unchanged; completes/repairs the missing acceptance material; makes NO claim the semantic result is identical |

- a run rejected for `:result-award-mismatch` may NOT declare `:exact-retry`
  while preserving the same semantic result (`:result-change-required`);
- a changed subject root is NEVER a direct resubmission: it is lineage (§5);
- a `:submission-repair` with a `:verified` parent results root is invalid
  (`:submission-repair-not-permitted`);
- identical content with no authoritative change = duplicate or reevaluation.

Derivation reasons: `:declared-kind-mismatch`, `:result-change-required`,
`:result-change-not-permitted`, `:subject-root-mismatch`,
`:rejection-kind-inconsistent`, `:submission-repair-not-permitted`.

A policy or validator implementation change causing identical bytes to be
re-evaluated is **reevaluation**, not researcher resubmission.

## 5. Research lineage (illustrative only)

Revised studies (material subject/input/parameter/methodology change) use a
separate lineage artifact. **In revision 3 this schema is ILLUSTRATIVE ONLY** —
it is not a completed companion contract and does not yet carry content
addressing, canonical projection, researcher signature, publisher commitment,
subject-root comparison, authority policy, or transplant protection. It is
intentionally sequenced after the resubmission contract:

```clojure
{:lineage/schema "research-lineage.v1"   ; ILLUSTRATIVE — not a finished contract
 :lineage/relation :revised-from
 :lineage/previous-run "..."
 :lineage/current-run "..."
 :lineage/change-summary {...}}
```

An unrelated new run carries neither a resubmission block nor a lineage block.

## 6. Canonicalization and hash projection (link)

```
resubmission-hash =
  domain-hash("prf.researcher-resubmission.v1",
              canonical-bytes-v2(resubmission-projection))
```

`canonical-bytes-v2` = `resolver-sim.hash.canonical/canonical-bytes`
(CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI); `domain-hash` accepts the string
tag directly. Cross-language equivalence is locked by a golden canonical-bytes
fixture.

Projection rules (explicit):
- the projection is ALL authoritative fields, excluding ONLY the self fields
  `:resubmission/hash` and `:researcher/signature`;
- map keys: keywords (portable `ns/name` / `name`) or strings; maps sorted by
  canonical key bytes;
- numbers: non-negative integers (bigint) only;
- strings: UTF-8, NFC-normalized;
- arrays: ordered vectors, sorted by the relevant derived id
  (`remediation` sorted by `finding-id`);
- the signature covers EXACTLY the same unsigned projection bytes as the hash.

## 7. Receipt hashing and signature projection

The attempt receipt — the root of authority — has an equivalent explicit
contract:

```
attempt-receipt-hash =
  domain-hash("prf.submission-attempt-receipt.v1",
              canonical-bytes-v2(unsigned-receipt-projection))
```

The unsigned receipt projection EXCLUDES only:
- `:attempt-receipt/id`;
- the validator signature bytes.

It INCLUDES: validator key and policy identifiers, findings, root statuses,
outcome/finality/eligibility/lifecycle, observed bundle root, chain-admission
data, submitter identity, evaluation binding, and `observed-at`/`evaluated-at`
(these timestamps are authoritative). The validator signature covers exactly the
same unsigned projection.

## 8. Researcher continuity from receipt identity

The resubmission verifier requires the current researcher to match the rejected
attempt's researcher. The receipt records identity provenance, because a
submission rejected for an invalid publisher signature cannot have its
researcher identity trusted from inside that envelope:

```clojure
:attempt-receipt/submitter
{:status :verified | :authenticated-session | :claimed | :missing
 :researcher-id "..."
 :identity-source :publisher-signature | :submission-auth | :authenticated-session
 :policy-hash "sha256:<hex>"
 :key-id "..."}
```

For submissions rejected on invalid publisher signatures, continuity requires
one of: a separate valid submission-authentication signature,
an authenticated researcher account/session, an independently issued submission
authorization, or explicit delegation from the authenticated prior submitter.
Without such evidence, continuity cannot be established
(`:researcher-continuity-mismatch`, `:delegation-required`).

## 9. Chain admission cutpoint

A linear chain allows only one successor per parent, so the contract must say
when a child consumes the parent's successor slot. A malformed or forged link
must NOT advance the chain; a locally valid child later rejected on semantic
gates SHOULD become the new rejected head.

State machine:

```
attempt observed
    ↓
bundle-local validation
    ↓
resubmission-link local validation
    ↓
atomic chain admission / successor reservation
    ↓
current semantic and certificate gates
    ↓
attempt receipt issued
```

- failure BEFORE chain admission: a receipt may be issued with
  `:chain :admission-status :not-admitted`; the attempt is not a successor and
  does NOT consume the slot;
- success at atomic admission: the child becomes sequence N+1
  (`:admission-status :admitted`);
- later semantic rejection: the child REMAINS the current rejected head;
- idempotent replay is checked BEFORE `:parent-not-current-head` (§14).

The receipt records the chain decision:

```clojure
:attempt-receipt/chain
{:admission-status :admitted | :not-admitted
 :family-id "sha256:<hex>"
 :sequence 2
 :parent-receipt-hash "sha256:<hex>"}
```

Without this distinction, one invalid request could consume the only allowed
successor.

**Linear-chain rules (v1, explicit):** initial attempt sequence 1; every child
links to the current chain head; sequence == parent + 1 exactly; a parent has
exactly one committed direct successor; uniqueness enforced atomically (CAS /
transactional append) at admission; a later correction links to the latest
rejected child, not an arbitrary ancestor. Branching (sequence as depth) is out
of scope for v1. Reasons: `:parent-not-current-head`,
`:parent-already-has-successor`, `:sequence-gap`, `:sequence-regression`,
`:cycle-detected`.

## 10. Validator authority

The receipt validator block carries the full authority contract, not just a
signature:

```clojure
:attempt-receipt/validator
{:id "..." :version "..."
 :policy/id "..." :policy/version "..." :policy/hash "sha256:<hex>"
 :authorisation/id "..."
 :key/id "..."
 :signature/algorithm :ed25519
 :signature "<hex>"}
```

The policy hash alone is insufficient unless the policy snapshot is durably
available as a content-addressed artifact. The verifier must be able to retrieve
and verify the EXACT historical policy, not merely observe its hash. Validator
key rotation and revocation follow the same cutpoint rules as researcher keys.

## 11. Acceptance-report binding (explicit)

The resubmission verifier requires independently recomputed certificate and
reconciliation outcomes. A stale or unrelated report must not be attachable, so
the receipt binds the evaluation:

```clojure
:attempt-receipt/evaluation
{:acceptance-report-hash "sha256:<hex>"
 :validator-version "..."
 :policy-hash "sha256:<hex>"
 :evaluated-bundle-root "sha256:<hex>"
 :evaluated-at "ISO-8601-UTC"}
```

The acceptance report itself must bind: current results artifact hash; current
result root; current certificate hash; current bundle root; resubmission link
artifact hash; validator policy and version. This ensures
`result-capacity-reconciles :pass` refers to the current submission, not a
stale one.

## 12. Findings: deterministic identity + remediation

Finding IDs are content-derived, not validator-assigned counters (order-,
duplication-, and language-sensitive):

```
finding-id =
  domain-hash("prf.acceptance-finding.v1",
              canonical-bytes-v2({stage, assertion-id, reason, subject, blocking?}))
```

If duplicate identical findings are possible, a deterministic occurrence /
subject path is appended. The receipt's `findings` and the link's `remediation`
are both sorted by this derived ID.

```clojure
{:finding/id "sha256:<hex>"
 :stage :allocation/reconciliation
 :assertion/id 14
 :reason :result-award-mismatch
 :subject {:claim-id "A"}
 :blocking? true}
```

Researcher remediation references findings by ID with a disposition and
evidence hash. Decision: all `blocking? true` findings MUST be accounted for
before the link is structurally valid (`:rejection-finding-unaccounted`).
Remediation never waives validation; the current run must independently pass
the relevant gate.

## 13. The no-op / exact-retry rule (works for signature corrections)

An opaque package-root inequality fails for signature-only corrections (all
unsigned package content can be identical). The exact-retry rule is therefore:

1. research subject unchanged;
2. verified semantic result unchanged;
3. the current submission independently passes at least one blocking gate the
   parent failed;
4. no current semantic result correction;
5. child content is not an idempotent replay of an already observed attempt.

Example remediation entry:

```clojure
:resubmission/remediation
[{:finding-id (finding-id-of :publisher-signature-invalid)
  :disposition :addressed
  :evidence-hash "sha256:<current-publisher-envelope-artifact>"}]
```

The acceptance validator independently verifies that the current
publisher-signature gate passes; the researcher's claim neither waives nor
replaces that check. The basis-root comparison remains a supporting signal but
is not the decisive condition for `:exact-retry`.

The `:resubmission/change-set` keeps only root-level DERIVED booleans in v1;
artifact-level diff evidence (`changed-artifact-ids`, `change-evidence-root`) is
omitted from v1 because the parent receipt alone cannot support an artifact-level
diff unless both full bundles are durably retained. (See §16.)

## 14. Idempotency: three identities + deterministic duplicate outcomes

Three distinct identities:
- `:submission-family/id` — stable subject identity; `family-id` as in §2.
- `:resubmission/id` — derived `:resubmission/hash` (§6); UUID is display-only.
- `:submission/idempotency-key` — the submission OPERATION:

```
idempotency-key = sha256(canonical-bytes-v2({
    parent-attempt-receipt-hash,
    current-submission-basis-root,
    researcher-authorisation-id }))
```

Deterministic v1 outcomes:
- same parent + same unsigned link projection → return the existing attempt
  receipt (`:submission-already-observed`, idempotent replay);
- same parent + same current basis root but different authorization →
  `:duplicate-content-submission`, unless delegation policy permits replacement;
- same package submitted to another parent → `:idempotency-key-rebound`
  (transplant rejection).

The admission transaction checks deduplication BEFORE the parent-is-current-head
check. Reasons: `:duplicate-content-submission`, `:idempotency-key-rebound`,
`:submission-already-observed`, `:idempotency-content-mismatch`.

## 15. Validation split (five groups)

Revision-2 "local" included external authority lookups. The cleaner split:

1. **Pure artifact validation** — schema, hash, canonical bytes, signature
   mechanics (bundle-local, deterministic).
2. **Bundle binding** — current registry, results, envelope, certificates.
3. **Historical authority validation** — researcher and validator policies,
   keys, revocations, delegations (deterministic given immutable inputs, but
   requires the authority artifacts to be retrievable).
4. **Historical receipt validation** — parent receipt + dispositions.
5. **Mutable chain admission** — head check and CAS.

This makes the Python/JVM boundary explicit: groups 1–2 are bundle-local;
groups 3–4 require retrievable historical artifacts; group 5 requires a
transactional store.

## 16. Change-set recomputation boundary

`changed-artifact-ids` is NOT recomputable from a parent receipt containing only
roots, unless both full bundles are durably retained by the validator. Minimal
v1: omit `changed-artifact-ids` and `change-evidence-root`; keep only root-level
derived booleans. Detailed diff evidence (`submission-diff.artifact.v1`) is a
later addition.

## 17. Immutable lifecycle via disposition events

Content-addressed receipts cannot change state without changing identity.
Lifecycle transitions are IMMUTABLE, signed disposition events:

```clojure
{:attempt-disposition/schema "attempt-disposition.v1"
 :attempt-disposition/attempt-receipt-hash "sha256:<hex>"
 :attempt-disposition/previous-disposition-hash "sha256:<hex>"   ; optional
 :attempt-disposition/status :pending-review | :final | :withdrawn
                            | :revoked | :superseded
 :attempt-disposition/superseding-receipt-hash "sha256:<hex>"     ; when applicable
 :attempt-disposition/policy-hash "sha256:<hex>"
 :attempt-disposition/signature {:algorithm :ed25519 :signature "<hex>"}}
```

The effective state is derived from the latest valid disposition; the original
receipt remains immutable.

**Child admitted before its parent is later superseded (explicit decision):**
the child remains historically valid but is marked `:orphaned` via a signed
disposition; a later correction links to the superseding receipt.
Re-anchoring is ALWAYS performed through a signed transition disposition —
never an informal mutation. Disposition hashing/projection follows the same
`domain-hash + canonical-bytes-v2` pattern with domain tag
`prf.attempt-disposition.v1`.

## 18. v1 link schema (finalized)

```clojure
{:resubmission/schema "resubmission-link.artifact.v1"
 :artifact/kind :resubmission-link
 :artifact/verifier "resubmission-link.verifier.v1"

 :resubmission/hash "sha256:<derived>"
 :resubmission/family-id "sha256:<research-subject-root>"
 :resubmission/kind :exact-retry | :corrected-result | :submission-repair
 :resubmission/sequence 2

 :resubmission/parent
 {:attempt-receipt-hash "sha256:<hex>"
  :sequence 1}

 :resubmission/current
 {:run-id "..."
  :research-subject {:root/schema "research-subject-root.v1" :root/hash "sha256:<hex>"}
  :execution-context {:root/schema "execution-context-root.v1" :root/hash "sha256:<hex>"}
  :results {:root/schema "results-root.v1" :root/hash "sha256:<hex>"}
  :results-artifact-hash "sha256:<hex>"
  :submission-basis {:root/schema "submission-basis-root.v1" :root/hash "sha256:<hex>"}}

 :resubmission/remediation
 [{:finding-id "sha256:<hex>"
   :disposition :addressed | :disputed | :not-applicable
   :evidence-hash "sha256:<hex>"}]

 :resubmission/change-set
 {:execution-context-changed? bool
  :submission-basis-changed? bool
  :results-changed? bool}

 :resubmission/idempotency-key "sha256:<derived>"

 :resubmission/researcher
 {:researcher-id "..."
  :authorisation-id "..."
  :policy/id "..." :policy/version "..." :policy/hash "sha256:<hex>"
  :key/id "..."
  :signature/algorithm :ed25519
  :signature "<hex>"}}
```

The parent receipt supplies previous roots, findings, validator identity,
policy, status, finality, and chain data. Historical data is not duplicated in
the link.

## 19. Composition with the four existing contracts

- **verify-artifact / valid-artifact**: the link is a standalone
  `resubmission-link.artifact.v1` artifact (produced by `finalize-artifact`,
  verified by `verify-artifact`); the publisher envelope commits its artifact
  hash; it is conditionally required when the run declares a resubmission;
  Python/Clojure share golden canonical bytes. `verify-artifact` establishes
  ONLY local integrity (schema, preimage, content hash, canonical commitment) —
  never parent existence, finality, or sequence uniqueness.
- **acceptance validation**: groups 1–5 of §15.
- **valid-certificate**: always recomputed for the current result; binds current
  subject root, results artifact hash, result root, verifier/implementation
  identity, reconciliation outcome, schema + policy versions. A valid link never
  upgrades an invalid certificate; a valid certificate never implies a valid
  link.
- **result-capacity-reconciles**: for `:corrected-result`, the current result
  independently reruns the full gate. Same result root as parent → NOT a
  corrected result (`:result-change-required`). Changed root but still failing →
  valid lineage link, current run rejected. Changed root and passing →
  corrected result eligible for remaining gates. The verifier does not rerun the
  allocation; it requires the bound acceptance report (§11) to contain the
  independently recomputed result. Final report exposes:

```clojure
{:resubmission-link-valid? true
 :previous-blocking-findings ["sha256:<finding-id>" ...]
 :current-gate-results
 {:result-capacity-reconciles :pass
  :valid-certificate :pass
  :results-artifact :pass}}
```

## 20. Rejection taxonomy (consolidated, v1)

Structure/hash: `:malformed-link`, `:unsupported-link-schema`,
`:unknown-link-field`, `:hash-mismatch`, `:self-reference`.
Kind derivation: `:declared-kind-mismatch`, `:result-change-required`,
`:result-change-not-permitted`, `:subject-root-mismatch`,
`:rejection-kind-inconsistent`, `:submission-repair-not-permitted`,
`:identical-content-re-resubmission`.
Researcher: `:researcher-continuity-mismatch`, `:delegation-required`,
`:authorisation-policy-mismatch`, `:key-not-valid-at-cutpoint`, `:key-revoked`,
`:researcher-signature-invalid`.
Idempotency/sequence: `:duplicate-content-submission`,
`:idempotency-key-rebound`, `:submission-already-observed`,
`:idempotency-content-mismatch`, `:parent-not-current-head`,
`:parent-already-has-successor`, `:sequence-gap`, `:sequence-regression`,
`:cycle-detected`.
Finality/eligibility: `:parent-rejection-not-final`,
`:parent-rejection-not-resubmittable`, `:parent-rejection-superseded`,
`:parent-attempt-withdrawn`, `:previous-not-found`, `:previous-not-a-rejection`.
Findings: `:rejection-finding-unaccounted`, `:finding-unknown`.
Binding: `:parent-receipt-hash-mismatch`, `:current-results-hash-mismatch`,
`:current-run-id-mismatch`, `:parent-receipt-forged`, `:parent-policy-mismatch`,
`:duplicate-content-submission`.

## 21. Adversarial tests (required before enabling)

- parent rejection receipt forged;
- parent rejection receipt signed under another policy;
- previous results artifact missing → valid `:submission-repair`, and a packaging
  fix when the parent results root is `:verified` remains `:exact-retry`;
- previous publisher envelope invalid (identity from `:submission-auth`);
- declared `:exact-retry` but semantic result changed;
- declared `:corrected-result` but result root unchanged;
- prior capacity mismatch followed by unchanged result;
- research subject unchanged but execution context changed;
- research subject changed through one uncommitted parameter;
- same idempotency key and same content returns the existing attempt;
- concurrent children competing for one linear-chain parent (CAS admits one);
- parent is not the current chain head;
- researcher changed without delegation;
- researcher key valid now but revoked at the required cutpoint;
- mutating any authoritative link field (policy hash, key id, parent receipt
  hash, etc.) changes `resubmission-hash` and breaks the signature;
- a `:not-admitted` attempt does NOT consume the successor slot;
- malformed or missing prior results still permits a valid packaging retry;
- valid link + current certificate failure remains rejected;
- valid link + current capacity reconciliation failure remains rejected;
- current result passes but lineage is forged → rejected;
- rejection finding omitted from remediation → structurally invalid;
- rejection report later superseded → child marked `:orphaned`, correction links
  to superseding receipt;
- identical bundle under a changed validation policy → reevaluation;
- same package submitted to another parent → `:idempotency-key-rebound`;
- signature-only correction (basis root unchanged) still permits `:exact-retry`
  when the current publisher-signature gate passes.

## 22. Implementation-ready dependency order

1. Define `submission-attempt-receipt.v1`: canonical projection (§7), root
   statuses (§2), submitter identity (§8), validator authority (§10), immutable
   dispositions (§17).
2. Define the package commitment cutpoints (§3) and remove the cycle.
3. Decide missing/invalid parent result classification → `:submission-repair`
   (§4).
4. Define the chain-admission cutpoint and `:admitted`/`:not-admitted` receipt
   states (§9).
5. Finalize `resubmission-link.artifact.v1` (§18).
6. Bind the acceptance report to the current bundle, certificate, result, and
   link (§11).
7. Implement CAS admission only after all preceding projections have golden
   vectors.

## 23. Explicit v1 decisions (summary)

- Direct relationships: `:exact-retry`, `:corrected-result`, `:submission-repair`
  only; revised studies via illustrative `research-lineage.v1`; unrelated runs
  carry neither.
- Parent authority: single `:resubmission/parent {:attempt-receipt-hash
  :sequence}`; no `multi-parent`/`:parents`.
- Package commitments: `submission-basis-root` in the link;
  `final-bundle-root`/`submitted-bundle-root` only in the receipt.
- Roots: typed `{:root/schema :root/hash}`, status-bearing in the receipt;
  `acceptance-question-root == research-subject-root`;
  `family-id = domain-hash("prf.resubmission-family.v1", research-subject-root)`.
- Sequence model: linear chain, head-enforced, CAS admission; `:admitted` /
  `:not-admitted` cutpoint.
- Lifecycle: immutable `attempt-disposition.v1` events; effective state from the
  latest valid disposition; orphaned children on supersession.
- Canonicalization: `canonical-bytes-v2` + `domain-hash` for link, receipt, and
  disposition; one unsigned projection each; only self hash + signature bytes
  excluded.
- No-op rule: exact-retry requires the current submission to independently pass
  at least one blocking gate the parent failed; no opaque package-inequality
  requirement.
- Idempotency: family-id / derived resubmission-id / operation idempotency-key,
  with deterministic duplicate outcomes checked before head-check.
- Certificate and capacity validity always independently recomputed and bound to
  the current bundle via the receipt evaluation block; lineage validity never
  implies correction success.
