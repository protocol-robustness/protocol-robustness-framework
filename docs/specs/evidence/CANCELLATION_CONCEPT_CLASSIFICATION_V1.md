# Cancellation concept classification

This document is the Iteration 7A gate. The names below are not yet canonical
operation values. They must not be added to `:request/action` until their
semantic level and identity owner are settled.

## Decision table

| Concept | Provisional semantic level | Canonical identity owner | Inputs | Preconditions | Authority | State transition | Effects | Terminal? | Receipt |
|---|---|---|---|---|---|---|---|---|---|
| `cancel-and` | Composition/operator pattern | Not a standalone cancellation identity; an ordered composition identity if adopted | Cancellation subject plus composed operation/command | Both component operations valid and composition ordering valid | Authority for each component, plus any composition gate | Defined by composed operations | Ordered union/derivation of component effects | Depends on composed operation | Component receipts plus composition receipt if required |
| `cancel-and-terminate` | Command-lineage composition / terminal command | Existing `command-lineage` terminator command and termination receipt | Current lineage head plus terminal state root | Head is current, non-terminal, and terminator input equals head result | Existing termination/cancellation authority as applicable | Append terminal command and establish terminal state | Terminal effects defined by the joined cancellation transition | Yes | Existing termination receipt, joined to cancellation operation only if semantically applicable |
| `cancel-during-dispute` | Lifecycle-context cancellation transition profile | `cancellation-operation.v1` evaluation/profile, not necessarily a new command kind | Cancellation operation, dispute/lifecycle state, target snapshot, authority evidence | Target is in disputed lifecycle state and policy permits this cancellation | Dispute-specific cancellation authority | Profile-specific state-after derivation | Profile-specific effects, including dispute handling | Profile-dependent | `cancellation-operation.v1` execution receipt or a derived terminal receipt |
| `cancel-disputed-escrow-now` | SEW-specific protocol operation / transition profile | SEW cancellation operation/profile and its authoritative transition definition | Escrow snapshot, dispute state, cancellation command, authority evidence | Exact SEW escrow/dispute predicates and immediate-cancellation policy pass | SEW protocol-party or governed authority, as specified by the operation | SEW escrow state transition | Release/void/invalidate effects as specified by SEW semantics | Likely yes for the escrow lifecycle; must be specified | SEW terminal cancellation receipt, bound to exact state-after and effects |

## Required decisions before implementation

For each concept, the project must explicitly answer:

1. Is it a command identity?
2. Is it a transition-definition identity?
3. Is it a composition operator?
4. Is it a protocol-specific profile?
5. Is it only an alias or convenience function?
6. Which existing artifact owns its canonical identity?
7. Which exact roots are inputs to the research subject?
8. Which authority artifact authorizes it?
9. Which state is the authoritative predecessor?
10. Which effects and state-after values are derived?
11. Is the transition terminal?
12. Which receipt is authoritative and how is it derived?

## Current conclusions

- `cancel-and` should not be added to the request-action enumeration until it
  is proven to be an intrinsic protocol operation rather than composition.
- `cancel-and-terminate` already has a command-lineage implementation, but that
  does not by itself establish a cancellation transition. It must be joined to
  `cancellation-operation.v1` only if the protocol semantics require that join.
- `cancel-during-dispute` is best treated initially as a lifecycle/profile
  distinction over the existing cancellation operation carrier.
- `cancel-disputed-escrow-now` is likely SEW-specific and should not be made a
  globally meaningful cancellation command without a concrete cross-protocol
  use case.
- No new cancellation registry or parallel state model is introduced by this
  classification.

## Next gate: Iteration 7B

Select one concrete transition, preferably `cancel-and-terminate` only if its
existing command-lineage semantics correspond to the intended cancellation
scenario. Implement the smallest join using:

```text
cancellation-operation.v1
+ existing command-lineage command/receipt
+ authoritative predecessor snapshot
+ derived effects
+ derived state-after
```

Do not implement the other three concepts until that transition closes its
substitution and lineage-conservation tests.
