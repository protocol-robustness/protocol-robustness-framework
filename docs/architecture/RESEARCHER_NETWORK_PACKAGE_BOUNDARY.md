# Research and researcher-network package boundary

This is an in-tree boundary, not a released package or an assertion of
independent reproducibility. The network now consumes the small
`resolver-sim.research.api` and `resolver-sim.incentives.api` surfaces. These
namespaces are direct aliases to existing authoritative builders/validators;
they contain no copied rules, schemas, or hashing implementations.

## Ownership and dependency classification

The checked source closure is recorded in
[`packages/module-boundaries.edn`](../../packages/module-boundaries.edn) and
validated against source `ns` declarations by
`repository-boundary-dependency-test`. Its 38 namespaces classify as:

| Class | Namespaces | Ownership / meaning |
|---|---|---|
| Public PRF protocol/semantic contract | `prf.canonical.codec`, `prf.canonical.reference`; `resolver-sim.benchmark.incentive-deviation-domain`, `incentive-model`, `outcome-manifest`, `research-analysis`, `research-assignment`, `research-claim-acceptance`, `research-claim-lifecycle`, `research-command`, `research-conclusion`, `research-theorem-outcome`; `research.api`; `incentives.api`; `researcher-reward` | Versioned artifacts and authoritative operations. Reward contracts are owned by incentives, not research acceptance. |
| Private research implementation | `research-analysis-closure`, `research-assignment-target-binding` | Supporting algorithms/adapters, not independent public authority. |
| Canonical shared foundation | `benchmark.integrity`, `benchmark.signing`; `evidence.chain`, `evidence.config`, `evidence.forensic-graph`, `evidence.node`, `evidence.reachability`, `evidence.timestamping`; `forensic.source-hash`; `hash.algorithm`, `hash.canonical`, `hash.framing-view`, `hash.reference`, `hash.round-trip`, `hash.sequence` | Shared identity, evidence, integrity and signature primitives. Canonical implementation ownership remains with C2. |
| Configuration/defaults | `config.defaults` | Runtime-selected framing admission limits; not an artifact root or V1 semantic constant. |
| Execution/infrastructure/operational dependency | `config.hardening`, `config.paths`, `io.edn`, `logging`, `vcs` | Used by evidence/operational support, not by claim semantics. |
| Test-only utility | none in the production namespace closure | Test helpers are not part of the runtime package closure. |

The namespace graph and the package graph are acyclic. Research analysis and
claim lifecycle do not depend on network or incentives. Incentives consumes
research verification/claim contracts; researcher-network consumes research
and incentives. Accounting reductions remains downstream and is not in this
network closure. No reverse dependency or cycle was found.

The network formerly imported implementation namespaces directly for
verification, claim derivation, acceptance, lifecycle and rewards. Those
imports now cross the two façade APIs. Other direct requirements—signing,
canonical hashing/reference handling, and forensic evidence composition—are
shared foundation APIs and are intentionally not copied into research.

The research-contract and incentive package candidates now each have an
explicit dependency manifest under `packages/prf-research-contracts/` and
`packages/prf-incentives/`. Each records its public namespace and callable
surface, existing schema versions, direct and computed transitive source
closure, ownership, package-level dependencies, compatibility rules, and
unresolved immutable pins. Their facades remain delegations to existing
authoritative implementations.

Both manifests name the shared canonical API required by these consumers:
V1-compatible `domain-hash`, SHA-256 reference construction/validation,
committable-content projection, and sequence hashing. In particular, the
legacy keyword-domain mapping must remain byte-exact; C1 has not changed
canonical implementation code. C2's canonical candidate is now merged into
this checkout and passes its V1 compatibility suite. Consumer manifests record
the qualified source revision and release archive digest; runtime resolution
remains an in-tree path because no external immutable coordinate is published.

## Supported API surface

`resolver-sim.research.api` exposes output verification and validation, claim
derivation/validation, acceptance policy/build/validation, lifecycle
transition/reconstruction/validation, incentive-model validation, and a
temporary root-reference compatibility alias. `resolver-sim.incentives.api`
exposes reward policy, eligibility, proposal, obligation and lineage
verification operations. Each exported var is tested to be `identical?` to
the authoritative implementation var, preventing duplicate builders.

The public artifact contracts remain the existing V1 schemas and roots. The
facades change neither domain separators nor V1 defaults.

## Canonical API coordination with C2

C1 did not move or edit canonical implementation code. Research-side callers
still directly use the legacy `resolver-sim.hash.canonical` and
`resolver-sim.hash.reference` compatibility namespaces. The exact canonical
operations needed by this closure are recorded under `:canonical-api-requests`
in `packages/module-boundaries.edn`: canonical bytes and hex, domain hashing,
canonical projection, canonical value validation, intent hashing/validation,
and SHA-256 reference creation/parsing/validation.

The merged canonical source candidate exports the codec, domain hashing,
canonical projections, intent hashing/equality, sequence commitments and
SHA-256 reference operations. C1 ran the canonical package suite (8 tests,
54 assertions) and the legacy/research compatibility suite (6 tests,
45 assertions). The isolated research and incentives closures now include the
canonical `hash`, `sequence` and `decode` namespaces required by legacy
compatibility facades. The function set and V1 compatibility constraints
remain machine-recorded in `packages/module-boundaries.edn`.

## `config.defaults` coupling

No research artifact builder reads `config.defaults` directly. It enters the
transitive closure through:

```text
research-command
  → hash.sequence
  → hash.round-trip
  → hash.framing-view
  → config.defaults
```

The five values are defensive input-admission limits (stream bytes, component
count, payload bytes, collection depth and member count). They do not enter
canonical encoding or artifact identity. However, `PRF_DEFAULTS_CONFIG_PATH`
can override them, so they can change whether an externally supplied sequence
commitment is admitted. They are therefore operational configuration with a
potential verification-policy effect, not protocol constants. The current V1
fallbacks/defaults must be preserved.

Before an independently released canonical package, C2 should either pin the
same packaged default resource and document the override as verifier-local
admission policy, or expose an explicit versioned admission profile to
validation callers. Research consumers must not move these values into
`prf-canonical` themselves. CLI exit codes and benchmark hardening defaults
are separate application configuration and are not part of the research
artifact closure reported here.

## Independent package status

In addition to in-tree conformance, an isolated temporary project was built
from a copied snapshot of the 38 transitive source namespaces plus the
researcher-network entrypoint. It used only an explicit source classpath, the
existing `config/defaults.edn` resource, and explicitly declared Maven
dependencies; the repository source tree was not on its classpath. The
research and incentives API alias suite passed there (2 tests, 23 assertions).
This check is reproducible with
[`scripts/qualify_research_api_closure.sh`](../../scripts/qualify_research_api_closure.sh).
This qualifies the current source closure for isolated loading, not a
reproducible package release: the temporary snapshot is not a package artifact
or immutable published coordinate. The staged `src/`, `resources/` and `test/`
content-set hash (sorted relative paths plus per-file SHA-256 digests, then
SHA-256 of that manifest stream) was
`sha256:83473263e2ecd25b8d5b7cd4a0203859c69ab4862c6f2ac1d3925eefbf27802a`.

No immutable published coordinate currently exists for `prf-canonical`,
`prf-research-contracts`, or `prf-incentives`. A clean external consumer
therefore cannot resolve the required authoritative dependencies without an
unpublished/local source path. No implementation was copied into the
repository and no package is represented as independently released.

The research API closure qualification passed from an isolated snapshot
(`bash scripts/qualify_research_api_closure.sh`; 2 tests, 23 assertions;
content set `sha256:83473263e2ecd25b8d5b7cd4a0203859c69ab4862c6f2ac1d3925eefbf27802a`).
The narrower research-contract manifest closure also passed its API,
acceptance and lifecycle suite (`bash scripts/qualify_research_contracts_closure.sh`;
24-namespace closure, 30 tests, 74 assertions; content set
`sha256:bca2a460db195bafa56948927a8765cbf04a78ac5accbf88a18bd1b04aa16a91`).
The incentives API/reward closure also passed independently staged
(`bash scripts/qualify_incentives_api_closure.sh`; 8 tests, 64 assertions;
27-namespace closure; content set
`sha256:9d9406e5ab25b8a0e23d92a6015178058430595bfaa5b58adb3de1ae9a55b6f1`).
Both snapshots exclude the monorepo source classpath and use declared Maven
dependencies, but they are qualification fixtures rather than released
package artifacts or immutable dependency coordinates.

The canonical source is now pinned and verified within the shared JJ checkout,
but remains unpublished and has no external dependency coordinate. Research
contracts and incentives also remain in-tree candidates; their authoritative
implementation closures are not independently released. Clean external
resolution is still blocked on publication of immutable canonical,
research-contract and incentives coordinates with their declared dependencies.
Researcher-network remains **in-tree only**, with explicit API and dependency
seams prepared for later packaging.
