# Successor Rules

Successor semantics are protocol policy, not a universal PRF default.

## Framework substrate

PRF supplies reusable mechanisms for predecessor binding, canonical transition
identity, lineage evidence, expected-state checks, fencing, and atomic
single-writer publication.

## Adopter-defined rules

Each protocol must specify:

- whether the state graph is linear, branching, or mergeable;
- what makes a candidate a valid successor;
- whether semantic rejection consumes sequence space or becomes the rejected head;
- correction, amendment, replacement, and compensation behavior;
- duplicate, stale-parent, fork, and merge handling;
- terminal-state and retry rules; and
- the atomic cutpoint at which a successor becomes authoritative.

The resubmission subsystem's one-successor-per-parent linear chain is a concrete
example, not the framework-wide default. See `docs/security/RESUBMISSION.md`.

Every successor model should include positive, stale-parent, duplicate, forged,
and concurrent-admission tests.

## Integration checklist

Record these decisions in the protocol's specification or admission policy:

| Question | Required protocol decision |
| --- | --- |
| Topology | Linear, branching, mergeable, or another explicitly defined graph |
| Parent selection | Exact predecessor identity and current-head requirements |
| Eligibility | State, authority, evidence, timing, and policy predicates for a successor |
| Rejection | Whether a rejected candidate consumes ordering, becomes a head, or is discarded |
| Correction | Whether corrections amend, replace, compensate for, or follow rejected candidates |
| Concurrency | Reservation/fencing method and behavior for conflicting candidates |
| Terminality | Which states prohibit successors and how authorized exceptions work |
| Persistence | Atomic store operation that makes the successor authoritative |

## Minimum adversarial coverage

Test at least one accepted successor and rejection for each of:

- stale, missing, or forged predecessor;
- duplicate candidate or duplicate successor slot;
- sequence gap, regression, and cycle where the topology has sequences;
- concurrent candidates for the same predecessor;
- terminal-state mutation; and
- a rejection that verifies authoritative state did not change.
