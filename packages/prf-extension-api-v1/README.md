# PRF extension API v1 package candidate

This candidate records the source boundary consumed by the
`force-authorisation`, `held-custody`, and Sew pro-rata economics packages. It
contains three versioned facades: the general extension API at
`src/resolver_sim/extensions/api/v1.clj`, the portable pro-rata API at
`src/resolver_sim/extensions/api/pro_rata/v1.clj`, and the narrow protocol
capability registry API at `src/resolver_sim/extensions/api/protocol/v1.clj`.
Neither implementation is copied or forked into this directory.

Status: in-tree source candidate. The dependency manifest pins the external
canonical-codec and pro-rata allocation sources, records the complete PRF
source closure, and includes the data JSON, SLF4J, and Buddy dependencies
needed by registry and evidence startup. The clean-room qualification script
stages that closure, excludes unrelated repository source paths and protocol
code, and runs the isolated consumer contract plus the force-authorisation and
held-custody package suites. Sew economics has a separate in-tree test alias
because its pinned public PRF revision predates the pro-rata API.

```sh
bash scripts/qualify_extension_core_api_v1.sh
```

The Sew Core profile adapter consumes only the narrow protocol capability API
for registration and lookup; it no longer loads the aggregate extension API
and its unrelated authorization and evidence dependencies for that purpose.

This qualifies the current source closure only. There is no published
`org.protocol-robustness/prf-core` artifact or immutable package coordinate.
The source review passed, but the GitHub publisher rejected the write required
to create a fetchable public revision because MCP approval is unavailable; no
public revision was created.
The force-authorisation and held-custody manifests use a local-root PRF
dependency; Sew economics pins public PRF revision
`b94b091cfbaf46ec34ae2ade4920f9a3751e3097`, which does not contain the new
facade, and adds the in-tree source only in its test alias. The candidate is
not independently consumable until an API-bearing core revision is made
fetchable and consumers pass against that pinned revision.
