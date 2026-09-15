# SEW Dispute Lifecycle Contract

## Status

Phase 6 generic authority-transition integration is frozen. Ordinary escalation
continues to use the existing SEW lifecycle. The frozen resolver ladder is an
authoritative successor-selection constraint, not a completed generic PRF
authority-transition integration.

## Authority Stages

| Stage | Authoritative facts | Artifact or state | Does not establish |
| --- | --- | --- | --- |
| Mandate | Escrow snapshot and frozen resolver ladder select permitted resolver roles and successors | Per-escrow module snapshot; `sew-resolver-ladder.v1` | A ruling, outcome, or finality |
| Case | Closed round evidence admission, question, policy, and predecessor reference | `sew/adjudication-case.v1` | Current adjudicator authority by itself |
| Instance | Published case, workflow/round, temporal basis, parent lineage, and resolver principal | `sew/adjudication-instance.v1`; current-instance root | A final decision or settlement |
| Ruling | Exact case/instance/basis plus provider-authentication commitment | `prf/dispute-ruling.v1`; authentication verification | Finality or custody execution |
| Finality | Authoritative replayable final round and exact authenticated ruling lineage | `prf/dispute-ruling-finality.v1` | Adjudicator authority, outcome selection, refusal semantics, or effect realization |
| Closure | Final authenticated release/refund settlement facts and post-settlement SEW state | `prf/dispute-resolution-closure.v1`; external terminal envelope | Generic adjudication closure or external delivery proof |

## Ordinary Escalation Boundary

The frozen Phase 6A sequence is:

```text
frozen resolver ladder
-> authoritative configured successor
-> callback result equality check
-> SEW resolver/round mutation
```

The callback is orchestration only. It cannot choose an otherwise-valid
replacement resolver.

Generic `prf/adjudication-authority-transition.v1` is not consumed here. The
existing case/instance lifecycle requires a closed successor evidence admission
and a published successor case before the successor instance can be created.
Those facts do not exist before ordinary escalation activates the successor
round. Constructing generic transition evidence afterward would be explanatory
rather than authorizing, so this integration remains frozen.

## Current Non-Equivalences

- A valid instance is not a final ruling.
- A valid ruling is not a final outcome.
- Finality is not settlement authority beyond the supported terminal path.
- Settlement closure is not proof of recipient redemption or external delivery.
- Expiry may constrain an existing action; it is not successor authority.
- Refusal remains a process result and does not become an economic release or
  refund outcome.

## Next SEW Focus

Harden SEW-local supersession, refusal, and failover semantics before revisiting
generic authority-transition consumption. Do not add appeal exhaustion, new
temporal authority, or a second generic transition abstraction without a
concrete SEW lifecycle fact that can be represented pre-activation.
