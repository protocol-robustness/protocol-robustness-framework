# Sew standalone boundary audit

Status: preparation; local source responsibilities have been separated, but no
source has been moved out of PRF. The supported composed profiles preserve the
existing Sew behavior; the model-core candidate intentionally omits optional
pro-rata slash evidence.

## Summary

The current `sew-protocol-model` profile is not a standalone protocol package boundary.
At the current workspace state, `bb profile:describe sew-protocol-model` resolves 373
Clojure source files. The profile descriptor selects `runner`, `sew-protocol-model`,
the Sew force-authorisation and analysis integrations, and the optional pro-rata
slash-evidence adapter.
The initial audit found four undeclared component crossings. The component
registry now separates Sew slash economics, slash evidence, pro-rata evaluation,
portable allocation, verification, and Sew settlement. The slash-evidence
adapter is separately selectable; the supported `sew-protocol-model` and candidate
`sew-protocol` compositions include it to preserve the current evidence
contract. These profile reports have zero undeclared component crossings, but
their source closures remain large. The `sew-protocol` candidate excludes
runner assembly and resolves to 199 source files with zero
component crossings. That candidate closure is still too broad to count as a
standalone package.

The `sew-protocol-model-core` candidate omits optional slash-evidence,
force-authorisation, and analysis adapters and resolves to 175 source files.
It retains the Sew yield capability adapter because yield behavior is required
by the current protocol model component.
Evidence-producing slash execution and consensus researcher-authorisation actions
fail visibly if their adapters are unavailable; analysis methods return empty or
unsupported results. The 24-file difference from `sew-protocol` shows the
selectable integration boundary.

A `sew-kernel` candidate isolates the escrow world representation and guarded
state-transition API in a 30-file source closure. It is a narrow protocol
kernel, not a complete Sew implementation; settlement, yield, evidence, and
runtime composition remain outside this profile.

Yield is now modeled as `:prf/yield-runtime-v1`, a reusable component required
by Sew protocol model. Its `yield-runtime` candidate profile resolves 69 source files,
owns all 28 yield namespaces in that closure, reports zero component crossings,
and passes the isolated profile load check. The model-core closure is 175 files;
it still composes the yield runtime. This is an explicit
ownership boundary, not a claim that yield has been published as an independent
package.

This audit describes current PRF ownership and extraction blockers. It does not
propose changes to protocol semantics or qualify an independently packaged Sew
implementation.

## Current composition

`profiles/sew-protocol-model.edn` selects `:prf/runner-v1`, `:prf/sew-protocol-model-v1`,
`:prf/sew-force-authorisation-v1`, `:prf/sew-analysis-v1`, and the Sew pro-rata
slash-evidence adapter. The runner component owns scenario, server,
replay and CLI entrypoints. Sew protocol model requires the framework, escrow kernel,
yield runtime, and slash economics components; it does not require the evidence adapter. The
separately selectable evidence adapter installs the builder and artifact writer
through a core-owned hook contract, and depends on core, slash economics, and
pro-rata evaluation. The Sew pro-rata settlement adapter is separate and
selected by the `pro-rata` profile.

The resolved 373-file closure includes these largest groups:

| Source group | Files | What it indicates |
| --- | ---: | --- |
| `protocols_src/resolver_sim/protocols` | 85 | Sew implementation and shared adjudication adapters |
| `yield_runtime_src/resolver_sim/yield` | 28 | Yield runtime and accounting integration |
| `protocols_src/resolver_sim/research` | 25 | Sew-specific research analyses pulled into the closure |
| `src/resolver_sim/sim` | 24 | Simulation support |
| `src/resolver_sim/scenario` | 20 | Scenario evaluation and reporting |
| `src/resolver_sim/io` | 16 | Runner and I/O integration |
| `src/resolver_sim/benchmark` | 14 | Benchmark/research coordination integration |

These are dependency-closure counts, not a proposed package manifest.

The `sew-protocol` candidate omits runner and analysis components and has a
199-file closure. Its largest groups are Sew protocol sources (53), yield (28),
benchmark (14), scenario (6), and contract-model replay (2). This is the
better starting view for further separation, but the remaining groups show
that the Sew entry namespace still pulls yield, benchmark,
and shared framework services into its runtime closure.

The `sew-kernel` candidate has a 30-file closure and zero component crossings.
Its public entrypoint is `resolver-sim.protocols.sew.kernel`; it exposes
constructors for an initial escrow world, the transition graph, and guarded
escrow transitions. The component declares its framework dependency and kernel
contract test. The profile is intentionally narrower than `sew-protocol` and
does not claim the full protocol's settlement or evidence behavior.

The `sew-protocol-model-core` candidate selects Sew protocol model and slash economics without
the optional analysis, pro-rata slash-evidence, or researcher force-authorisation adapters. `resolution` calls a core-owned
evidence hook; the adapter installs the existing builder and artifact writer
when selected. The full `sew-protocol-model` profile includes all optional adapters;
the `sew-protocol` candidate includes force-authorisation and slash-evidence but
omits analysis. The registry loads each selected adapter when its capability is
requested. `bb profile:diff sew-protocol-model-core sew-protocol` reports the
selected adapters and 24 added source files.

## Concrete component crossings

The initial profile boundary report identified four crossings:

1. Runner/server replay reaches `resolver-sim.pro-rata.allocation` through
   `contract-model.replay` → `scenario.expectations` → `yield.invariants` →
   `yield.partial-fill` → pro-rata evidence and invariants.
2. Runner/scenario execution reaches `resolver-sim.pro-rata.exact-verifier`
   through Sew resolution → Sew lifecycle/yield/accounting → the new
   `sew.pro-rata.economics` adapter → `pro-rata.evaluation`.
3. Sew resolution reaches `resolver-sim.pro-rata.allocation` through
   `yield.partial-fill` → pro-rata evidence and invariants.
4. Sew resolution reaches `resolver-sim.pro-rata.exact-verifier` through Sew
   lifecycle/yield/accounting → `sew.pro-rata.economics` →
   `pro-rata.evaluation`.

The component registry now represents these edges explicitly. The portable
pro-rata allocator no longer requires Sew; the Sew settlement adapter depends
on both. This removes the component cycle while retaining the real integration
dependency in profile composition.

The yield runtime now has explicit ownership and a candidate profile. Its
direct component requirements include framework services and portable pro-rata
semantics used by yield claims and partial-fill accounting. Profile generation
links declared local-root dependencies into the constrained view so the
candidate's isolated load check resolves the repository's accounting package.

## Source-level blockers

### Integration-heavy root namespace

`protocols_src/resolver_sim/protocols/sew.clj` directly assembles Sew state and
actions with held accounting, DB access, temporal state, replay/idempotency,
yield, benchmark/research assignment, governed authority, force-authorisation
extensions, semantic composition, evidence capture, and hashing. Requiring this
namespace loads the composed application surface, not a minimal protocol core.

Preparation should identify smaller stable entrypoints for:

- Sew state, transitions, and protocol-owned invariants.
- Optional yield integration.
- Optional pro-rata slash-allocation integration.
- Research/benchmark analyses.
- Runner, replay, DB, and server integrations.
- Force-authorisation and governed-composition integrations.

These are candidate ownership slices, not yet approved package boundaries.

#### Direct-import inventory

The root namespace is the main closure multiplier. The following classification
is based on its current `:require` list and direct call sites; it distinguishes
protocol behavior from supporting integrations and is not a package manifest.

| Import group | Current use | Boundary recommendation |
| --- | --- | --- |
| Sew types, state machine, lifecycle, resolution, accounting, authority, invariants, validator registry | State construction, transitions, settlement, and validation | Keep in the Sew protocol model; these define its behavior. |
| `resolver-sim.yield.*` and Sew yield adapters | Yield actions, expectations, snapshots, evidence, market risk, settlement, and Sew exposure checks | The Sew model component includes a yield capability bootstrap over the shared `:prf/yield-runtime-v1`. Extracting or omitting the shared yield runtime requires a separate no-yield model specification. |
| Sew force-authorisation adapter; imports `resolver-sim.benchmark.*` and governed authority | Consensus authorization checks, reservation records, and composition admission | Researcher validation and reservation now live in an optional Sew force-authorisation component. Full profiles select it to preserve behavior; model-core omits it. |
| `resolver-sim.db.*` | Batch summaries and replay temporal recording | Both now live behind runner-owned capabilities; neither is imported by the protocol root. Keep persistence and reporting on the runner side. |
| `resolver-sim.contract-model.replay` and idempotency | Protocol replay entrypoint and event deduplication | Replay orchestration now lives in runner-owned `sew.runner-replay`; the Sew namespace retains a compatibility wrapper. Keep event idempotency as a narrow framework service if protocol transitions still require it. |
| Sew analysis adapter; imports `resolver-sim.scenario.*` | Trace projections, Sew mechanism/equilibrium validators, advisory and accounting reports | Now optional behind `:prf/sew-analysis-v1`. Full model profiles select it; the runner-free protocol candidate omits it and returns empty or unsupported analysis results. |
| Sew yield integration | Yield initialization, operation dispatch, risk and expectation calls, settlement allocation, and Sew-specific exposure checks | These capabilities are owned by the Sew model component, which requires shared yield runtime. The Sew protocol root and lifecycle call through this explicit interface. Snapshot validation, claimable-outcome classification, projections, and invariant calculations still have direct runtime imports. |
| Framework hashing, time, evidence capture, attribution, held accounting | Canonical identity, temporal rules, audit records, and shared ledger operations | Keep as explicit PRF framework dependencies; define a curated public framework API before repository split. |

Replay orchestration, batch summarization, researcher validation, and analysis
now live behind optional runner or protocol capabilities. Extracting scenario
analysis reduced the runner-free candidate from 239 to 199 files and model-core
from 215 to 175. The remaining yield boundary is the shared runtime. It remains
required for core behavior until a no-yield model defines alternatives for every
lifecycle and evidence operation; changing component metadata alone would
understate protocol semantics.

### Yield integration gate

The `yield-runtime` component gives the generic yield runtime an independent
owner and source root, but Sew still requires it. The protocol root and lifecycle
now use the model-owned yield capability interface for runtime operations,
settlement policy, and exposure checks. Direct yield imports remain in
`sew.snapshot`, `sew.projection`, `sew.claimable-outcome`, and
`sew.invariants`; these support snapshot construction, analysis, classification,
and invariant calculations. Further isolation should move analysis/reporting
imports first and retain direct dependencies where they define protocol
semantics. A profile aligned with Solidity `sew-core` would be a separate,
narrower model: its spec explicitly disables yield and excludes staking and
slashing. It must be built from a reviewed operation subset with explicit
rejection behavior, not by dropping yield from the current full model. The
`extensions/sew-core-model` package now provides an initial guarded adapter,
but it still delegates to that full model and retains a 194-file source
closure. It is an in-tree candidate, not an independently extracted or
Solidity-equivalent package. Its candidate profile passes source-boundary and
entrypoint-load checks. The model profile still composes `yield-runtime`.

### Pro-rata evaluation inside the Sew slash integration

`protocols_src/resolver_sim/protocols/sew/pro_rata/evidence.clj` imports
`resolver-sim.pro-rata.evaluation`. That namespace deliberately composes the
pro-rata allocator, redistribution engine, and independent exact verifier to
produce canonical result artifacts. This is an application integration, not a
generic protocol primitive. The evidence adapter is now independently
selectable behind a Sew-owned hook, while core still depends on pro-rata slash
economics and its evaluation/verifier chain. The exact verifier therefore
remains in the adapter-free core closure; removing that dependency is a
separate follow-up.

The slash-allocation and slash-evidence code has explicit
`resolver-sim.protocols.sew.pro-rata.*` namespaces. The general Sew economics
namespace no longer imports PRF's pro-rata evaluator directly. The profile-view
dependency scanner was also extended to include literal lazy namespace
references; this exposed 41 additional source files compared with the earlier
require-only audit. The old `sew.economics` and `sew.evidence.slashing`
entrypoints remain compatibility facades; Sew resolution calls a core-owned
multimethod contract, and the adapter installs its implementation when loaded.
This makes ownership visible in source, and the declared component graph now
has no crossings for the audited profiles. The slash-evidence adapter is no
longer a direct dependency of the Sew protocol model component. Full Sew compositions
select it alongside core; the adapter-free candidate is only five source files
smaller, so the broader protocol closure remains the main extraction blocker.

### Local PRF framework APIs

The closure uses many `resolver-sim.*` namespaces, including canonical hashes,
evidence capture/chains, time context, execution context, attribution,
accounting, replay, yield, and scenario machinery. A standalone Sew repository
must consume deliberate public framework APIs for the subset it needs. Copying
these namespaces into a Sew package would create competing owners; importing
all of PRF would not create an independent package boundary.

### Packaging and qualification materials

The profile view is a source-composition view, not a package build. Its
dependency-closure scanner follows `:require` entries and literal
`requiring-resolve`, `resolve`, and `ns-resolve` references. Computed namespace
names and other reflective loading are outside this static scan and require
separate review. Its generated `deps.edn` copies the repository dependency map,
so it does not define a minimal Sew dependency set. Sew tests and test material
also live across
`test/`, `protocols_src/test/`, `protocols_src/`, and shared fixture/resource
directories. The slash-evidence adapter declares its tests, and the slash
economics component declares the allocation conformance vector. The core Sew
component still lacks a curated self-contained test
closure. A package candidate needs an explicit dependency manifest and complete
test/fixture closure.

## Recommended preparation sequence

1. Keep the current `sew-protocol-model` profile documented as the composed runtime; do
   not label its current closure standalone.
2. Preserve the split between Sew protocol model, slash economics, and the optional
   slash-evidence adapter. The direct-import inventory above classifies the root
   namespace's dependencies. Replay, batch summarization, researcher
   force-authorisation, and analysis are optional components; Sew yield
   operations are behind model-owned capabilities over the required runtime.
3. Reduce the `sew-protocol` candidate's 199-file closure by resolving its yield
   and remaining shared-framework dependencies. Keep yield required until
   no-yield behavior is specified for every lifecycle and evidence operation.
4. Only after the constrained profile is small and tests/material are
   self-contained, prepare a separate repository package with its own
   dependency manifest, source provenance, compatibility vectors, and
   independently reproducible acceptance commands.

## Reproduction

From the PRF repository root:

```sh
bb profile:describe sew-protocol-model
```

The command reports the resolved source closure, component groups, and current
boundary crossings. This audit is based on the local workspace state and should
be refreshed after any dependency or profile changes.
