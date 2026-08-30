# Curiosity V1 — Non-Authoritative Observation Contract

This document is **normative** for the Curiosity V1 surface implemented in
`resolver-sim.benchmark.curiosity` and for every authoritative API that
consumes or could be expected to consume a Curiosity V1 result.

## Core contract (normative)

> A Curiosity V1 resolution is a **non-authoritative, point-in-time observation**.
> It may inspect authenticated authoritative state or validated evidence, but the
> result is not itself authorization evidence, an authority fence, a capability,
> or permission to perform an authoritative transition.

All Curiosity V1 behavior is governed by the invariants below. A violation of
any invariant is a defect, regardless of whether a test catches it.

## Invariants

### C1 — Non-authority

Every result, successful or unsuccessful, carries `:curiosity/authority-granted? false`.
There is no status, value, or combination of fields that flips this to true.

### C2 — No mutation

Curiosity resolution MUST use read-only resolution paths. A resolution never
publishes state, issues a handle, fence, receipt, or capability, and never
consumes authorization material. Resolution is observation, not transition.

### C3 — Authenticated currentness

A claim about authoritative *current* state MUST derive from authenticated
authority-store state and MUST NOT be caller asserted. Callers cannot supply
the "current" configuration root, envelope root, head root, or any other
currentness fact; the fact is read from the authenticated store.

### C4 — Closed dispatch

Only registered Curiosity V1 IDs (`curiosity-ids`) may be resolved. Callers
cannot supply executable resolver behavior. Unknown IDs return a structured
fail-closed result; they are never dispatched to caller-supplied code.

### C5 — Closed interpretation

Statuses and values have defined per-curiosity meanings. Unknown IDs or
unrecognized status interpretation fails closed. No consumer may read a
Curiosity V1 status/value as a generic truth token.

### C6 — Point observation

A result describes the state observed **during that resolution**. It does not
guarantee that the observation remains current afterward. A historically valid
observation is not a currently valid fact; it is a valid record of what was
observed at that point.

### C7 — Evidence-before-observation

Where an operational observation depends on supplied evidence, the evidence
MUST be validated **before** the protected operational fact is inspected. An
absent or tampered evidence input is never read into an operational value.

### C8 — No authority substitution

No authoritative API may treat a Curiosity V1 result as a substitute for its
native authorization, fence, capability, or evidence input. A result that says
`:curiosity/status :resolved` / `:curiosity/value true` is still
`:curiosity/authority-granted? false` and cannot occupy the slot expected for:

- an authority fence,
- authorization evidence,
- a governed authority context, or
- a native authorization artifact.

C8 gives the **authority-laundering** test a named invariant: a Curiosity V1
result placed in any of those slots MUST be rejected by shape or by the
consuming API's own fail-closed gate.

## Ownership

- `resolver-sim.benchmark.curiosity` owns curiosity semantics: curiosity IDs,
  closed dispatch, result/status semantics, and safety classification.
- A use-case definition owns its required curiosities
  (`:concept/required-curiosities` in a committed external use-case registry).
- The in-namespace `bootstrap-use-case-required-curiosities` map is
  **transitional compatibility data** for use cases that do not yet have a
  committed declaration. It is not the authoritative owner of use-case
  requirements and is deleted when a migration-equivalence test proves each
  entry is redundant with the committed definition.

## Required-curiosity resolution (dual declaration)

`resolve-use-case-required-curiosities` reconciles the bootstrap declaration
with the committed/external declaration for one use case. Both inputs must
already be validated sets of keyword curiosity IDs, so disagreement is semantic
rather than representational.

| bootstrap | external | resolved |
|---|---|---|
| absent | absent | `#{}` |
| present | absent | bootstrap |
| absent | present | external |
| present | present, equal | external |
| present | present, unequal | **fail closed** |

On disagreement the resolution throws a machine-stable, structured error:

```
{:error/code :curiosity-requirements/disagreement
 :use-case/id <use-case-id>
 :bootstrap/required-curiosities <bootstrap set>
 :declared/required-curiosities <external set>}
```

There is **no union, no precedence on disagreement, and no silent fallback**.

### Migration criterion

A bootstrap entry may be deleted only when:

1. a committed definition declares `:concept/required-curiosities` for the same
   use case, and
2. the migration-equivalence test demonstrates that removing the bootstrap
   entry yields identical resolved requirements.

The third migration test assertion — *bootstrap entry removed + external
unchanged → same resolved requirements* — is what proves bootstrap removal is
semantics-preserving and makes the deletion criterion objective.

## Out of scope for V1

The following are deliberately NOT part of Curiosity V1:

- new curiosity IDs;
- a generic `:curiosity/basis` abstraction;
- semantic-definition roots;
- governance integration;
- resolver extensibility or caller-supplied resolvers;
- any form of authority grant, fence, or capability issuance.

An authenticated *operational basis* relationship (e.g. binding an operational
observation to an authenticated basis root before inspecting the protected
fact) is a separate, later slice and must not be approximated by relocating
caller assertion into Curiosity.

## How to check

```
bb test:framework          # broad framework gate (indirect)
clojure -M:test -e "(require 'resolver-sim.benchmark.curiosity-test) (clojure.test/run-tests 'resolver-sim.benchmark.curiosity-test)"
bb validate
```

## Related

- `src/resolver_sim/benchmark/curiosity.clj` — the V1 implementation.
- `src/resolver_sim/use_cases/registry.clj` — the committed/external use-case
  registry that owns `:concept/required-curiosities`.
- `src/resolver_sim/benchmark/governed_authority_state.clj` — the authenticated
  authority-store surface that C3/C6 derive from.
- `docs/benchmarks/EVIDENCE_INTEGRITY_CONTRACT.md` — the companion integrity
  boundary for evidence-based observations.