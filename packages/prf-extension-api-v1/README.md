# PRF extension API v1 package candidate

This candidate records the source boundary consumed by the
`force-authorisation` and `held-custody` extension packages. The authoritative
API facade remains at `src/resolver_sim/extensions/api/v1.clj`; this directory
does not copy or fork its implementation.

Status: in-tree source candidate. The dependency manifest pins its external
canonical-codec source and records the complete PRF source closure. The
clean-room qualification script stages that closure, excludes repository
source paths and protocol code, and runs both package test suites.

```sh
bash scripts/qualify_extension_core_api_v1.sh
```

This qualifies the current source closure only. There is no published
`org.protocol-robustness/prf-core` artifact or immutable package coordinate, so
the extension `deps.edn` files continue to use the explicit monorepo local
dependency. Do not treat this candidate as independently released until a
core artifact is published and consumers pass against that pinned artifact.
