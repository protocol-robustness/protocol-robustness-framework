# Private Claim-Relation Seam

## Scope

This note prepares a future privacy-preserving proof that two hidden research
claims satisfy a directed relation. It does not add a claim-relation artifact,
private claims, commitments, a claim graph, a proof system, a proof registry,
or a verification subsystem.

The future capability is conceptually:

```text
private witness
  valid source claim B
  valid target claim A
  canonical PRF roots for A and B
  required corpus/context membership evidence
  relation and policy/method evidence

public statement
  source B :supersedes target A
  approved context and policy bindings
  verifier/proof identity and privacy-safe public commitments, if needed
```

The proof establishes only its declared statement. It does not make either
claim public, mutate either claim, accept the relation, or alter lifecycle or
reward state.

## Current Boundaries

### Identity

`research-analytical-claim.v2` is a self-addressed, domain-separated SHA-256
artifact. `research-analysis/claim-root` hashes the complete canonical claim
body, excluding only `:research-claim/root`. The body commits to its analysis
output, subject, model, proposition, domain, metric, result, evidence,
verification root, and status.

`research-analysis-verification.v1` is separately self-addressed. It commits
to the exact output and its command, model, domain, assignment, and outcome
roots. A claim carries that verification root. The generic verifier validates
the output/context bindings and does not establish a general theorem.

Canonical PRF SHA-256 identities remain authoritative. A future privacy
adapter may commit to a canonical claim root, but must not replace it, change
its domain tag, or add a ZK-specific hash to PRF core.

### Resolution and validation

There is no global research-claim registry, collection, or generic
`claim-exists?` predicate. A claim root is an identifier, not evidence that an
artifact is retrievable, valid, admitted, accepted, or current.

Current validators use supplied artifacts. The stronger pattern already exists
in research analysis and reward lineage:

```text
supplied claim artifact
  -> validate schema and referenced dependencies
  -> recompute canonical claim root
  -> compare it with the claimed or enclosing reference
```

For example, acceptance validates a supplied claim and verification, then
checks that the acceptance binds their exact roots. The adversarial reward
lineage verifier reconstructs the expected verification and claim from its
resolved context and compares roots. These are resolution-time checks; none
turn a bare root into proof of existence.

### Existing public disclosure assumptions

The current analytical-claim, acceptance, lifecycle, and reward-lineage paths
take complete claim artifacts as inputs. Consequently, those paths are public
artifact validation paths today. Acceptance and lifecycle records also publish
the raw claim root they govern.

This is not a global requirement that every future consumer disclose a claim
body or raw claim root. No research pack or benchmark manifest found in the
current architecture commits a globally public collection of analytical-claim
bodies. Research packs commit executable benchmark members; benchmark manifests
select frozen research-definition measures. Neither is a claim corpus.

The existing three-member research certificate has its own certificate
supersession vocabulary and must not be treated as an analytical-claim relation
mechanism.

### Distinct meanings

Future work must preserve these separate questions:

| Question | Meaning | Current owner |
| --- | --- | --- |
| Claim reference | An identifier names a claimed artifact or commitment. | Enclosing artifact/proof statement |
| Claim existence/resolution | A resolver supplied the artifact and its canonical root and dependencies validate. | Consumer-specific validation |
| Corpus/context membership | A valid claim is admitted to a particular bounded corpus or context. | No current research-claim corpus contract |
| Claim acceptance | A designated authority made an `:accepted` or `:rejected` assertion over exact roots. | `research-claim-acceptance.v1` |
| Claim lifecycle state | Ordered accepted/rejected/superseded state reconstructed from governed acceptance transitions. | `research-claim-lifecycle.v1` |

In particular, acceptance is not epistemic establishment, and a lifecycle
supersession transition is not evidence of a semantic relation between two
claims.

## Future Relation Contract

Only `:supersedes` has a concrete present use: a later claim replaces an
earlier claim for a declared policy/context. Do not reserve `:supports`,
`:contradicts`, or `:reproduces` until each has a precise predicate and policy
use case.

A future relation assertion or private proof statement needs, at minimum:

```text
source-claim       B, the later or replacing claim
target-claim       A, the claim B relates to
relation           :supersedes
context/corpus     exact admitted comparison scope, if membership matters
policy/method      roots that define permitted relation semantics and evidence
```

The direction is normative:

```text
source claim B :supersedes target claim A
```

No ambiguous `claim-to` field should be introduced.

A relation assertion alone has no lifecycle effect. Any later workflow must
make the consequence explicit and separately authorized:

```text
relation proof or assertion
  -> optional acceptance under a relation/lifecycle policy
  -> optional lifecycle transition for the target claim
  -> optional reward or other economic consequence
```

In particular, a proof of `B :supersedes A` does not mean `A` is now in
`:superseded` lifecycle state unless the relevant acceptance/lifecycle contract
authorizes and records that transition.

## Privacy-Compatible Proof Admission

A future proof adapter should expose four technology-neutral layers:

```text
public relation statement
proof artifact or receipt
authorized verifier identity
verification result
```

The public statement may disclose relation type, corpus/context root,
subject/domain/policy roots, verifier identity, and privacy-safe claim
commitments. It must not require public claim bodies or raw canonical claim
roots. Whether a public commitment reveals, derives from, or is unlinkable to
a raw root is an adapter-specific cryptographic decision and is intentionally
unspecified here.

The private witness may contain both claim bodies, canonical PRF claim roots,
membership paths or admission evidence, relation evidence, and required
verification or acceptance artifacts. The adapter must establish the declared
predicate over that witness without requiring PRF core to interpret the proof
technology.

`verification-basis.v1`, `verification-result.v1`, and the extension
verifier-registry selection model already provide the appropriate authority
pattern: a public basis binds a subject and verifier-registry/resolution roots;
an authorized registry selects the verifier; a result binds that exact basis
and selected verifier. A future claim-relation adapter should reuse this
selection and authority model where applicable, while defining its own
versioned public-statement and receipt shapes. It must not overload the current
closed `verification-basis.v1` or `verification-result.v1` schemas.

No proof technology, commitment construction, verifier package, registry
entry, circuit hash, or public-input encoding is selected by this note.

## Researcher Identity Is Independent

Claim privacy and researcher anonymity are separate optional capabilities:

```text
Proof A
  Hidden source and target claims satisfy the declared relation statement.

Proof B
  A researcher is eligible under a role, membership, or authorization policy.
```

Neither proof implies the other. A later policy may require both, but any
binding between them must be explicit, minimal, and policy-owned. The relation
proof must not silently reveal a researcher, and an anonymous-role proof must
not silently reveal claim bodies or establish a relation.

## Deferred Work

The following are deliberately out of scope:

- `research-claim-relation.v1` or any relation storage/graph;
- claim corpus admission, membership trees, and private corpus commitments;
- private claim schemas or changes to public analytical claims;
- ZK circuits, proof generation, verification, receipt formats, or verifier
  packages;
- commitment construction or hash changes, including Poseidon;
- lifecycle, acceptance, reward, or economic consequences of a relation;
- anonymous researcher credentials or identity proofs.

Implement one of these only when a concrete consumer supplies its relation
predicate, corpus/context semantics, public disclosure policy, verifier
authority, and lifecycle consequence.
