# Pro-rata commitment-stack composition conformance

`test/fixtures/pro-rata/commitment-stack/all-active-modeled-v1.edn` is a
nonempty, all-active **composition conformance** vector. It checks that the
existing Clojure builders compose consistently across:

```text
allocation input
→ canonical effects
→ effect compilation
→ canonical effect transition
→ transact.v1
→ trace-bounded-transition.v1
→ transition-binding.v1
→ modeled protocol-effect-realization.v1
→ protocol-transaction-realization.v1
```

It is not end-to-end SEW conformance. The native state is a deliberately small
synthetic model, so the fixture does not establish that production SEW emits
these effects, derives the same write set, preserves all production SEW state,
or publishes final evidence.

## Hashing policy

Each artifact in this fixture uses `domain-hash` over the builder's exact,
closed, versioned projection. The vector records the schema version,
projection fields, canonical-byte hex, domain-derived root, and dependency
roots for each layer.

No `hash-intent` was added merely because the artifact is security-relevant.
A registered intent is appropriate only if a root must be derived from richer
runtime values by multiple callers, or needs registry-enforced include/exclude
rules that cannot be established at this closed-artifact boundary.

## Assertions

The matching test verifies:

- deterministic canonical bytes and roots on repeated construction;
- meaningful full allocation for two claims, with no residual, rejection,
  deferral, or haircut;
- exact normalized canonical effects and exact modeled write set;
- ordered Transact operations carrying the same target/delta pairs;
- preservation of modeled fields outside the write set, including a `nil`
  optional field and nested metadata;
- transition identity agreement across binding, realization, and join;
- rejection of mismatched roots, binding-mode substitution, unexpected join
  fields, and transplanting a reordered trace beneath the original join.

`effect-exact` is target/delta exact and sequence-not-exact. A reordered,
independently built transaction can therefore be a valid binding to the same
canonical transition, but it has a distinct `transact.v1`, trace, and binding
commitment and cannot replace the original join child.

The fixture compares the legacy realized-allocation statement only at an
explicit future compatibility boundary. It does not assert equality between
legacy and commitment-stack roots.
