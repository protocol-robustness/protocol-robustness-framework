# PRF Generic Adjudication Layering

## Existing

- `prf/adjudication-basis.v1`
- `prf/adjudicator-identity.v1`
- `prf/adjudication-decision.v1`
- Generic eligibility, selection, and authority-transition artifacts
- `prf/dispute-ruling.v1` and authentication verification in the current SEW
  integration
- `prf/dispute-ruling-finality.v1` for the supported SEW final-round path

## Design Frozen

The intended semantic layers are:

```text
case -> instance -> decision/ruling -> outcome -> finality
     -> adjudication closure -> effect instruction -> realization
     -> realization closure
```

Definitions:

- **Decision/ruling:** authenticated adjudicator statement for an exact case
  and instance.
- **Outcome:** typed, versioned semantic result derived from one decision under
  a protocol-owned outcome definition.
- **Finality:** reason the decision/outcome can no longer change under the
  applicable authority and appeal rules.
- **Adjudication closure:** proof that exactly one authoritative final outcome
  has been accepted as closed.
- **Effect instruction:** domain-specific transition authorized by the final
  outcome.
- **Realization:** observed execution of that instruction.
- **Realization closure:** terminal proof of required effect completion.

Settlement/effect and realization must remain distinct from adjudication
closure. A local custody system remains authoritative for custody execution;
an adjudication result alone is not a transfer or remote settlement authority.

## Generic Matching Design Boundary Frozen

The generic matching decomposition is frozen. Matching, selection, and
authority are distinct stages:

```text
MATCH EVALUATION
Does candidate C satisfy requirement R?

COMPLETE MATCH SET
Which members of universe U satisfy R?

SELECTION
Which matched candidate is chosen?

AUTHORITY TRANSITION
Why may the chosen candidate now act?
```

Any future generic match contract, evaluation, and match-set artifacts must
preserve this decomposition. Matching establishes suitability only; it must not
embed selection policy or grant authority.

Generic matching implementation is deferred. When a concrete consumer exists,
its contract must bind rooted requirement and candidate definitions, with typed
canonical projections rather than Clojure paths or hidden taxonomy, currency,
or protocol semantics. A complete match set must bind its candidate-universe
snapshot and prove complete re-evaluation of that universe.

SEW does not yet have category/subtype/amount/capability resolver routing. Its
current frozen resolver ladder is a level-to-resolver authority commitment, and
some modes use an authoritative module callback. Neither can be reclassified as
generic matching without changing its semantics. Current ladder/callback routing
is preserved and SEW migration is none. Future resolver capability matching is
the potential first real use case; it would need rooted routing-requirement,
resolver-capability, and operational-state projections, a frozen
candidate-universe snapshot, and a protocol-owned derivation fence.

## Candidate Future Artifacts

`prf/adjudication-outcome.v1` may eventually bind one authenticated decision,
its subject/case/instance/basis, a rooted protocol-owned outcome definition,
and a canonical payload root. PRF must not encode SEW values such as release,
refund, or split.

`prf/adjudication-closure.v1` may eventually bind one final case, instance,
decision, outcome, finality basis, and authority lineage. It must not include
settlement or realization merely for symmetry.

## Not Yet Justified

- Generic relational matching implementation (design boundary frozen; deferred)
- Generic adjudication closure implementation
- Generic effect instruction
- Generic realization closure
- Cross-domain messaging, bridges, remote execution, or custody trust models

These remain deferred because the repository has one concrete SEW consumer,
unsettled supersession/refusal/failover semantics, and no stable cross-protocol
outcome-definition registry. Implementing them now would duplicate SEW
commitments or prematurely define semantics that are not yet shared.
