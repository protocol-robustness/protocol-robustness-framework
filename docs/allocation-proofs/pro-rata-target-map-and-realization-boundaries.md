# Pro-rata target-map and proposed-realization boundaries

This additive milestone preserves `pro-rata-effect-compilation.v1` and
`protocol-effect-realization.v1` roots unchanged.

## New contracts

- `allocation-quantity-target-map.v1` binds allocation subjects and mapping
  roles to canonical quantity roots. The initial profile is one-to-one by
  `[subject, role]` and quantity root.
- `canonical-quantity-native-location-map.v1` separately binds canonical
  quantities to exact native map leaf paths. Native storage locations are not
  part of the protocol-neutral target map.
- `allocation-quantity-target-map-validation.v1` binds target map, realized
  allocation, scope, adapter descriptor, mapping profile, native-before root,
  and native-location map.
- `pro-rata-effect-compilation.v2` explicitly requires allocation policy,
  target-map, and mapping-profile roots. Allocation policy is mandatory rather
  than implicitly inferred from the allocation result.
- `core-authorized-proposed-realization.v1` derives its exact native write set
  from validated locations and normalized effects, then verifies a proposed
  native-after model changes only those exact leaves.

## Extension context

`adapter-execution-context.v1` explicitly commits either `:core` or
`:extension` source. Extension mode requires resolution and capability roots (`:extension/capability-root`)
and fails closed when absent. This slice validates their closed structural
shape only; it does **not** establish that an extension was activated or
approved by chain configuration. A later authorization wrapper must bind that
claim to the existing extension-resolution and configuration machinery.

## Non-claims

Native-after is a proposed/modelled reconstruction. These contracts perform no
persistence, write-back, read-back, transaction, or historical-execution
attestation. `protocol-effect-realization.v1` remains unchanged and does not
by itself prove this newer core-authorized construction path.
