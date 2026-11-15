# Sew standalone boundary audit

Status: preparation; Sew model and adapter sources are separated into in-tree
packages and extensions, but no source has been moved out of PRF. The yield-free
model-core candidate omits the Sew yield adapter and shared yield runtime.

## Summary

The current `sew-protocol-model` profile is not a standalone package boundary.
`bb profile:check sew-protocol-model` resolves 420 Clojure source files; it
selects the runner, model, yield integration, force-authorisation, analysis,
and slash-evidence components. The runner-free `sew-protocol` candidate selects
the model, yield integration, force-authorisation, and slash-evidence components.
It resolves 202 files, passes boundary/load checks, and reports zero component
crossings. The candidate remains too broad to count as a standalone package.

The `sew-protocol-model-core` candidate resolves 144 files and excludes the
yield integration and shared yield runtime, as well as the analysis,
force-authorisation, and slash-evidence adapters. With no yield state, its model
invariants retain empty-state results. Configured yield profiles or existing
yield state fail visibly when the integration is missing. Full protocol profiles
select the yield integration to preserve yield behavior. The 58-file difference
between the runner-free profiles demonstrates selectable adapter boundaries,
not independent package readiness.

A `sew-kernel` candidate isolates the escrow world representation and guarded
state-transition API in a 30-file source closure. It is a narrow protocol
kernel, not a complete Sew implementation; settlement, yield, evidence, and
runtime composition remain outside this profile.

Shared yield operations remain in `:prf/yield-runtime-v1`; Sew settlement policy,
Sew yield invariants, and the capability bootstrap are owned by
`:prf/sew-yield-integration-v1`. The adapter depends on the model and runtime;
the model no longer depends on the yield runtime. The extension has an in-tree
manifest with a local model dependency, so it is not independently resolvable.

This audit describes current PRF ownership and extraction blockers. It does not
propose changes to protocol semantics or qualify an independently packaged Sew
implementation.

## Current composition

`profiles/sew-protocol-model.edn` selects the runner, model, yield integration,
force-authorisation, analysis, and slash-evidence components. The model's direct
component requirements are framework, escrow kernel, and Sew pro-rata economics.
The yield integration is separately selectable and depends on the model and
shared yield runtime. Slash evidence remains a separate adapter that installs
the evidence builder through the core-owned hook contract.

The runner-free `sew-protocol` closure includes 59 model files, 28 shared yield
runtime files, 14 benchmark files, 3 Sew yield adapter files, 4 slash economics
and evidence adapter files, 2 pro-rata contract-source files, 2 Force
Authorization files, and 103 shared PRF source files. It contains no scenario
or replay source files. These counts describe the current transitive closure,
not a standalone package manifest. The shared framework API, benchmark
dependencies, and exact-verifier dependency still need further review.

The `sew-protocol-model-core` candidate excludes all shared yield runtime files
and all Sew yield adapter files. Its 144-file closure is a useful smaller model
boundary, but still includes shared framework code and the in-tree economics
adapter dependency.

The `sew-kernel` candidate has a 30-file closure and zero component crossings.
Its public entrypoint is `resolver-sim.protocols.sew.kernel`; it exposes
constructors for an initial escrow world, the transition graph, and guarded
escrow transitions. The component declares its framework dependency and kernel
contract test. The profile is intentionally narrower than `sew-protocol` and
does not claim the full protocol's settlement or evidence behavior.

The current `sew-protocol` profile resolves 59 model-owned files under
`packages/sew-protocol-model/src`. Sew analysis, slash economics, slash
evidence, and yield integration have separate extension source roots. The
model package manifest does not include `protocols_src`; the composed profile
currently resolves two Force Authorization production files from that tree.
Related sources and tests remain there for the other agent's extraction. The
103 `src/` files in the closure are shared PRF dependencies, not Sew-owned
implementation files.

The `sew-protocol-model-core` candidate selects Sew protocol model and slash economics without
the optional analysis, pro-rata slash-evidence, or researcher force-authorisation adapters. `resolution` calls a core-owned
evidence hook; the adapter installs the existing builder and artifact writer
when selected. The full `sew-protocol-model` profile includes all optional adapters;
the `sew-protocol` candidate includes force-authorisation and slash-evidence but
omits analysis. The registry loads each selected adapter when its capability is
requested. `bb profile:diff sew-protocol-model-core sew-protocol` reports the
selected adapters and 58 added source files.

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
| `resolver-sim.yield.*` and Sew yield adapters | Yield actions, expectations, snapshots, evidence, market risk, settlement, and Sew exposure checks | Sew policy, exposure invariants, and bootstrap now belong to `:prf/sew-yield-integration-v1`. Model queries use fail-closed capabilities; the model-core profile supports empty yield state and rejects configured or populated yield state without the extension. |
| Sew force-authorisation adapter; imports `resolver-sim.benchmark.*` and governed authority | Consensus authorization checks, reservation records, and composition admission | Researcher validation and reservation now live in an optional Sew force-authorisation component. Full profiles select it to preserve behavior; model-core omits it. |
| `resolver-sim.db.*` | Batch summaries and replay temporal recording | Both now live behind runner-owned capabilities; neither is imported by the protocol root. Keep persistence and reporting on the runner side. |
| `resolver-sim.contract-model.replay` and idempotency | Protocol replay entrypoint and event deduplication | Replay orchestration now lives in runner-owned `sew.runner-replay`; the Sew namespace retains a compatibility wrapper. Keep event idempotency as a narrow framework service if protocol transitions still require it. |
| Sew analysis adapter; imports `resolver-sim.scenario.*` | Trace projections, Sew mechanism/equilibrium validators, advisory and accounting reports | Now optional behind `:prf/sew-analysis-v1`. Full model profiles select it; the runner-free protocol candidate omits it and returns empty or unsupported analysis results. |
| Sew yield integration | Yield initialization, operation dispatch, risk and expectation calls, settlement allocation, and Sew-specific exposure checks | These capabilities are owned by the separate Sew yield integration component. The model no longer has direct yield-runtime imports; full protocol profiles select the integration explicitly. |
| Framework hashing, time, evidence capture, attribution, held accounting | Canonical identity, temporal rules, audit records, and shared ledger operations | Keep as explicit PRF framework dependencies; define a curated public framework API before repository split. |

Replay orchestration, batch summarization, researcher validation, and analysis
now live behind optional runner or protocol capabilities. The runner-free
candidate resolves to 202 files; the model-core candidate resolves to 144.
Scenario and replay source files are absent from the runner-free closure. The
yield adapter and runtime remain optional at the component boundary. Further
repository preparation should define the public PRF APIs used by the 103 shared
sources and decide whether exact pro-rata verification belongs in a separate
published dependency.

### Yield integration gate

The `yield-runtime` component owns the generic yield engine. Sew settlement
policy, Sew exposure checks, and the Sew capability bootstrap now live in
`extensions/sew-yield-integration`; snapshot, claimable-outcome, and invariant
queries cross the boundary through capabilities. The model-core profile has no
yield runtime source in its closure. Full Sew profiles explicitly select the
integration. Its manifest still depends on the in-tree Sew model, so it is not
independently resolvable. The separate `sew-core-extension` profile remains an
adapter over the full model and currently resolves 210 files; it is not a
standalone or Solidity-equivalent package.

### Pro-rata evaluation inside the Sew slash integration

`extensions/sew-pro-rata-slashing/src/resolver_sim/protocols/sew/pro_rata/evidence.clj` imports
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
longer a direct dependency of the Sew protocol model component. Full Sew
compositions select it alongside core. The exact verifier remains in the
adapter-free model closure because slash economics uses the evaluation and
verifier chain. The shared framework and yield runtime are the larger remaining
package boundary.

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
directories; the model package now has a focused package-only contract suite, while
the broader Sew suite still spans those roots. The slash-evidence adapter declares its tests. The slash-economics
adapter has a pinned source dependency, and its allocation conformance-vector
generator now lives in the same extension source package. The model package
manifest no longer adds `protocols_src` to its classpath. It pins the sibling
economics extension to an immutable candidate revision, but that revision is
not yet available on a shared remote; clean-clone dependency resolution remains
unqualified. A package candidate needs independently resolvable dependencies
and a complete test/fixture closure.

## Recommended preparation sequence

1. Keep the current `sew-protocol-model` profile documented as the composed runtime; do
   not label its current closure standalone.
2. Preserve the split between Sew protocol model, slash economics, and the optional
   slash-evidence adapter. The direct-import inventory above classifies the root
   namespace's dependencies. Replay, batch summarization, researcher
   force-authorisation, and analysis are optional components; Sew yield
   operations are behind model-owned capabilities over the required runtime.
3. Continue reducing the `sew-protocol` candidate's 202-file closure by
   defining the PRF framework API it needs and reviewing the 14 benchmark and
   exact-verifier dependencies. Keep the yield integration selectable and
   preserve its explicit fail-closed contract. Leave Force Authorization with
   its current owner.
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
