# Concept Risk Taxonomy

Cross-cutting risk categories across concepts.

## Risk: Verification Failure

The evidence trail cannot be independently verified, undermining
forensic confidence in any protocol outcome.

| Variant | Affected Concepts | Protocol Surface |
|---------|------------------|-----------------|
| Evidence chain broken | verifiable-assurance, all | Chain linking, bundle integrity |
| Hash mismatch | verifiable-assurance, all | Canonical serialization, hashing determinism |
| Non-deterministic replay | verifiable-assurance, all | RNG seeding, replay-mode |
| Attestation missing | verifiable-assurance, all | Signing, timestamping |
| Invariant violation suppressed | verifiable-assurance, all | Evidence collection, result aggregation |
| Bundle manifest incomplete | verifiable-assurance, all | Manifest generation, packaging |

## Risk: Stakeholder Unfairness

A stakeholder receives an outcome that contradicts the intended
use-case semantics, even if protocol rules were followed.

| Variant | Affected Concepts | Protocol Surface |
|---------|------------------|-----------------|
| Resolver bias toward one party | ecommerce, deposits | Resolver selection, governance rotation |
| Griefing via meritless disputes | ecommerce, accounts | Dispute bonding, timeout durations |
| Premature timeout claim | deposits | Timeout evaluation, appeal windows |
| Frozen account without cause | accounts | Governance intervention scope |

## Risk: Fund Unavailability

Funds cannot be accessed by any entitled party within expected time.

| Variant | Affected Concepts | Protocol Surface |
|---------|------------------|-----------------|
| Stuck after unresolved dispute | ecommerce, deposits, accounts | Dispute resolution liveness, timeout settlement |
| Indefinite hold | accounts | Hold timeout, release mechanics |
| Condition unverifiable | deposits | Oracle dependency, evidence admissibility |

## Risk: Value Extraction

Protocol value is drained through strategic behaviour.

| Variant | Affected Concepts | Protocol Surface |
|---------|------------------|-----------------|
| Collusive buyer-merchant fraud | ecommerce | Bond adequacy, slashing coverage |
| False attestation | deposits | Oracle bonding, attestation verification |
| Over-draw beyond balance | accounts | Balance check correctness, hold accounting |

## Risk: Governance Failure

Protocol governance acts outside its intended scope or fails to act
when needed.

| Variant | Affected Concepts | Protocol Surface |
|---------|------------------|-----------------|
| Selective enforcement | all | Governance authority limits, transparency |
| Collateral policy change | all | Upgradeability, timelocks, community veto |
| Failure to rotate compromised resolver | ecommerce, deposits | Resolver rotation mechanism, governance liveness |
