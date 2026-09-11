# SEW Aggregate Held-Credit Semantic Contract V1

This is the portable semantic contract for `:sew/aggregate-held-credit.v1`.
It contains no chain, contract, nonce, ABI, or FRAME concepts.

## Authoritative Inputs

- Exact realized allocation, including `:allocation/hash` and canonical rows.
- Aggregate quantity identity (`canonical-quantity-identity.v1`).
- Aggregate target map (`allocation-quantity-target-map.v2`).
- Native location map (`canonical-quantity-native-location-map.v1`).
- SEW adapter descriptor and its descriptor root.
- Allocation scope, aggregate custody scope, and mapping profile.
- Native pre-state root.
- Allocation policy root and compiler semantics root.

The target map is authoritative application input. It is not derived from the
allocation. It must cover exactly the realized allocation row IDs and map them
to the one aggregate held-credit quantity.

## Derived Artifacts

1. Recompute and validate target-map roots and location-map roots.
2. Build `allocation-quantity-target-map-validation.v2`, committing the exact
   realized allocation root and all mapping/state dependencies.
3. Compile `sew/aggregate-held-credit.v1`: positive, uncapped, all-active rows
   produce one normalized aggregate delta.
4. Normalize effects under `canonical-effect-set.v1`.
5. Apply effects to the canonical pre-state to derive the canonical post-state.
6. Build `canonical-effect-transition.v1` from exact semantics, pre-state,
   effects, and post-state.
7. Build `effect-compilation-binding.v1` or `.v2` joining compilation identity
   to the exact transition identity.

## Canonicality

- Allocation rows preserve the claimant-context order established by the
  allocation artifact.
- Target-map targets are canonically sorted by allocation subject ID.
- Effect normalization composes deltas by quantity root, removes net-zero
  deltas, and orders by quantity root.
- State quantities are represented as a sorted map in the state root.
- Effect emission order is therefore irrelevant after normalization.
- Target-map subject coverage and target cardinality remain exact; duplicate
  subjects are rejected.

## Root Projections

- Target-map root: schema, allocation-subjects root, allocation scope root,
  aggregate custody scope root, mapping profile root, and sorted targets.
- Validation root: schema, target-map root, realized-allocation root, mapping
  profile, both scopes, adapter descriptor root, native pre-state root, native
  location-map root, and aggregate quantity root.
- Effect-compilation root: schema, realized-allocation root, allocation-policy
  root, target-map root, mapping profile, compiler semantics root, and effects
  root.
- Effects root: effect schema, effect-semantics root, and normalized effects.
- State roots: state schema and sorted quantity map.
- Transition root: transition schema, effect-semantics root, state-before root,
  effects root, and state-after root.
- Binding root: binding schema, effect-compilation root, and canonical
  transition root.

## Fail-Closed Invariants

- Claimant IDs, requested IDs, and filled IDs must be identical sets.
- Deferred and haircut values are forbidden by this all-active profile.
- Target subjects must equal allocation row IDs exactly.
- The target map must contain one aggregate-held-credit target quantity.
- Aggregate quantity identity, scopes, adapter descriptor, and native locations
  must agree with the validation inputs.
- Effects must be reconstructed from allocation, target map, and compiler
  semantics; a locally self-consistent effects root is insufficient.
- State-after must equal applying exact normalized effects to exact state-before.
- Compilation and transition must be bound by exact root equality.
- The realized-allocation root is retained throughout validation and compilation;
  equivalent-looking rows with another identity are not interchangeable.
