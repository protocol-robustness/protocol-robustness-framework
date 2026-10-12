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

C2 should provide those operations through the shared package while preserving
legacy call compatibility and the existing V1 vectors. In particular,
`domain-hash` must preserve the current string/keyword domain mapping and
`project-canonical-safe` behavior used by researcher-challenge roots.

The current canonical source candidate exports the codec, string-tag
`hash-with-domain`, and SHA-256 reference operations. A research-side
compatibility test now checks codec bytes, representative research V1 domain
hashes, and reference formatting against those candidate APIs. It does not
switch any authoritative callers. The standalone canonical candidate's own
test runner also passes (3 tests, 25 assertions) from its package directory.
The remaining C2 coordination items are
keyword domain-tag compatibility, safe/content projections, intent-based
hash/equality used by evidence records/chains, and the canonical sequence
commitment API used by research-command. The exact function set and the
required V1 compatibility constraints are machine-recorded in
`packages/module-boundaries.edn`.

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

The smallest release blocker is availability of immutable, independently
consumable canonical, research-contract, and incentives packages (including
their declared dependency closure). The source namespace count itself is not
the blocker. Researcher-network remains **in-tree only**, with an explicit
API seam prepared for later packaging.
