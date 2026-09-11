# S-DR-073: Resolver Capacity Liveness Gap

## Classification

- **Status:** `:protocol/known-gap`
- **Solidity:** `:solidity/current-behaviour`
- **Kind:** `:finding-reproduction`

## Description

When a resolver reaches its `maxConcurrentDisputes` capacity, `raise_dispute`
fails with `:resolver-capacity-exceeded`. The escrow remains in `:pending`
state.

## What still works

Because the escrow is still `:pending` (not `:disputed`):

- `sender_cancel` may still be available (if the protocol allows sender
  cancellation of pending escrows).
- `recipient_cancel` may still be available.
- `release` may still be available (if the protocol allows release of pending
  escrows and the resolver is willing).

Funds are **not necessarily permanently locked**. The liveness gap is specific
to the **dispute path only**.

## What is blocked

- There is no overflow queue to queue disputes for when a slot frees up.
- There is no resolver reassignment mechanism to route the dispute to another
  resolver.
- There is no governance override to force-accept a dispute even when at
  capacity.

The dispute path is dead until a slot frees naturally (via resolution, timeout,
or cancellation of another dispute on the same resolver), or until governance
takes action outside the current protocol scope.

## Why this is a design gap, not a bug

The capacity limit itself is intentional — it prevents resolver overload. The
gap is that **there is no escalation path for users when all resolvers are
full**. In a system where dispute resolution is the only way to recover funds
from a non-cooperative counterparty, resolver capacity exhaustion becomes a
denial-of-service surface.

## Clojure mitigation policy

Any Clojure code that models a mitigation for this gap (e.g. a governance
overflow dispute action) MUST use:

```clojure
{:protocol/status :protocol/proposed
 :solidity/status :solidity/not-implemented
 :finding/id      "S-DR-073"
 :proposal/id     "PRF-PROP-001"
 :scenario/kind   :mitigation-validation}
```

This classification ensures that researchers, auditors, and implementers can
clearly distinguish between current Solidity behaviour and proposed
enhancements.

## References

- `src/resolver_sim/protocols/sew/lifecycle.clj` — `raise-dispute` capacity guard
- `src/resolver_sim/protocols/sew/types.clj` — `resolver-at-capacity?`
- `src/resolver_sim/sim/capacity_exhaustion.clj` — existing capacity exhaustion simulations
