# PRF researcher ratification contract v1

Status: **Proposed; policy approval not recorded**  
Schema: `prf/research-ratification.v1`  
Related decision: [ADR-0007](../architecture/ADR-0007-three-member-consensus-system-wide.md)

## Purpose

This contract defines what it means for researchers to agree about one
immutable research claim or resolution basis. It does not grant governance,
Sew ruling, settlement, or payment authority. A domain adapter supplies the
target and the applicable policy; the generic verifier checks their exact
bindings and reconstructs the result.

## Immutable target

Every contribution and ratification binds the same target tuple:

```clojure
{:target/domain keyword
 :target/subject-root "sha256:..."
 :target/root "sha256:..."
 :target/evidence-root "sha256:..."
 :target/round keyword-or-root
 :target/policy-root "sha256:..."
 :target/schema string
 :target/predecessor-root nil-or-sha256}
```

For a Sew resolution basis, the adapter additionally binds the workflow,
subject, authoritative adjudication round, frozen snapshot, question and
evidence roots, and snapshot predecessor. A successor basis has a new target
root and names its predecessor. Existing approvals remain attached only to
their original target.

## Contributions and result

Positions, signed decisions, policy ratification, and operational authority
are separate artifacts. A contribution records its principal, signing-key
identity, exact target root, outcome, content root, signature reference, and
optional dissent/challenge/reproduction references. The signed
`researcher-decision.v2` outcome root commits to the exact target, principal,
contribution kind, and evidence root, so challenge evidence cannot be swapped
after signing. Contribution outcomes are
`approve`, `dissent`, `challenge`, and `reproduction`. Challenges are admitted
as evidence of an objection; admission does not establish scientific
correctness.

Policy evaluation yields exactly one of `ratified`, `ratified-with-dissent`,
`contested-unresolved`, or `rejected`. Policy specifies eligible principals,
principal distinctness, threshold, allowed dissent, equivocation handling,
material blocking challenges, and revision/new-round rules. No default
threshold is approved by this document. A signature collection alone is not a
ratification, and a ratification alone is not authority to execute an
operation.

## Verification and trust

Verification recomputes target and contribution hashes, verifies signatures
against explicit key/principal bindings, evaluates the named policy, and
derives the result from retained inputs. Legacy `researcher-decision.v1`
artifacts may be verified for history, but cannot create new authority-bearing
approvals. A verifier reports separately the artifacts it verified and the
external trust roots it relied on. Policy approval and key/principal
eligibility require an explicitly identified authority source.

## Rejection rules

Reject a contribution or ratification when any target field, policy, round,
subject, or evidence root differs; when a contribution is stale or
superseded; when a principal is ineligible or duplicated; when signatures or
canonical hashes fail; when equivocation or a material challenge violates
policy; or when dissent/challenge history is omitted. Never infer approval from
absence of dissent or from a stored `ratified` flag.

## Reuse boundary

| Existing mechanism | Reuse | Does not supply |
|---|---|---|
| `researcher-position.v1` | Research assessment content | Signed exact-basis approval |
| `researcher-decision.v2` | Signed outcome-bound decision primitive | Generic target tuple or ratification policy |
| Three-member certificates | Benchmark replication/consensus projection | Domain-neutral researcher ratification |
| Research claim acceptance/lifecycle | Authority-issued claim acceptance and append-only status | Researcher consensus |
| Sew resolution-basis reconstruction | Workflow/snapshot/round integrity | Research approval or Sew authority |
| Replay frames | Accepted transition lineage | Live-chain provenance or signatures |

## Conformance plan

The verifier fixture suite must accept one correctly bound result and reject
foreign subject, claim, evidence, round, policy, or predecessor; duplicate
principals with distinct keys; ineligible signers; invalid signatures; v1
approval used for new authority; equivocation; missing dissent/challenge; and
stale successor reuse. The schema remains proposed until an authorized policy
owner records explicit approval and a trust-root reference.
