# Sew standalone boundary audit

Status: preparation; local source responsibilities have been separated, but no
source has been moved out of PRF. The supported composed profiles preserve the
existing Sew behavior; the new core-only candidate intentionally omits optional
pro-rata slash evidence.

## Summary

The current `sew-core` profile is not a standalone protocol package boundary.
At the current workspace state, `bb profile:describe sew-core` resolves 353
Clojure source files. The profile descriptor selects `runner`, `sew-core`, and
the optional Sew pro-rata slash-evidence adapter.
The initial audit found four undeclared component crossings. The component
registry now separates Sew slash economics, slash evidence, pro-rata evaluation,
portable allocation, verification, and Sew settlement. The slash-evidence
adapter is separately selectable; the supported `sew-core` and candidate
`sew-protocol` compositions include it to preserve the current evidence
contract. These profile reports have zero undeclared component crossings, but
their source closures remain large. The `sew-protocol` candidate excludes
runner assembly and resolves to 237 source files with zero
component crossings. That candidate closure is still too broad to count as a
standalone package.

The `sew-protocol-core` candidate omits the optional slash-evidence adapter
and its runtime bootstrap, and resolves to 232 source files. Evidence-producing
slash execution fails before side effects if the adapter is unavailable. The
four-file difference confirms a selectable boundary while showing that most of
the closure is shared Sew and allocation-economics code.

A `sew-kernel` candidate isolates the escrow world representation and guarded
state-transition API in a 30-file source closure. It is a narrow protocol
kernel, not a complete Sew implementation; settlement, yield, evidence, and
runtime composition remain outside this profile.

Yield is now modeled as `:prf/yield-runtime-v1`, a reusable component required
by Sew core. Its `yield-runtime` candidate profile resolves 69 source files,
owns all 28 yield namespaces in that closure, reports zero component crossings,
and passes the isolated profile load check. The Sew closure remains unchanged
at 232 files because Sew still composes the yield runtime. This is an explicit
ownership boundary, not a claim that yield has been published as an independent
package.

This audit describes current PRF ownership and extraction blockers. It does not
propose changes to protocol semantics or qualify an independently packaged Sew
implementation.

## Current composition

`profiles/sew-core.edn` selects `:prf/runner-v1`, `:prf/sew-core-v1`, and the
Sew pro-rata slash-evidence adapter. The runner component owns scenario, server,
replay and CLI entrypoints. Sew core requires the framework, escrow kernel,
yield runtime, and slash economics components; it does not require the evidence adapter. The
separately selectable evidence adapter installs the builder and artifact writer
through a core-owned hook contract, and depends on core, slash economics, and
pro-rata evaluation. The Sew pro-rata settlement adapter is separate and
selected by the `pro-rata` profile.

The resolved 353-file closure includes these largest groups:

| Source group | Files | What it indicates |
| --- | ---: | --- |
| `protocols_src/resolver_sim/protocols` | 77 | Sew implementation and shared adjudication adapters |
| `src/resolver_sim/yield` | 28 | Yield runtime and accounting integration |
| `protocols_src/resolver_sim/research` | 25 | Sew-specific research analyses pulled into the closure |
| `src/resolver_sim/sim` | 22 | Simulation support |
| `src/resolver_sim/scenario` | 20 | Scenario evaluation and reporting |
| `src/resolver_sim/io` | 15 | Runner and I/O integration |
| `src/resolver_sim/benchmark` | 14 | Benchmark/research coordination integration |

These are dependency-closure counts, not a proposed package manifest.

The `sew-protocol` candidate omits the runner component and has a 237-file
closure. Its largest groups remain Sew protocol sources (57), yield (28),
scenario (14), benchmark (14), and contract-model replay (12). This is the
better starting view for further separation, but the remaining groups show
that the Sew entry namespace still pulls research, yield, scenario, benchmark,
and shared framework services into its runtime closure.

The `sew-kernel` candidate has a 30-file closure and zero component crossings.
Its public entrypoint is `resolver-sim.protocols.sew.kernel`; it exposes
constructors for an initial escrow world, the transition graph, and guarded
escrow transitions. The component declares its framework dependency and kernel
contract test. The profile is intentionally narrower than `sew-protocol` and
does not claim the full protocol's settlement or evidence behavior.

The `sew-protocol-core` candidate selects Sew core and slash economics without
the optional pro-rata slash-evidence adapter. `resolution` calls a core-owned
evidence hook; the adapter installs the existing builder and artifact writer
when selected. The full `sew-core` and `sew-protocol` profiles include the
adapter and extension bootstrap. The protocol registry loads that bootstrap on
`get-protocol "sew-v1"`. `bb profile:diff sew-protocol-core sew-protocol`
reports the adapter and five added source files.

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
longer a direct dependency of the Sew core component. Full Sew compositions
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

1. Keep the current `sew-core` profile documented as the composed runtime; do
   not label its current closure standalone.
2. Preserve the split between Sew core, slash economics, and the optional
   slash-evidence adapter; inventory direct imports from Sew
   namespaces, classifying each as protocol core, framework API, or optional
   integration.
3. Reduce the `sew-protocol` candidate's 237-file closure by removing unselected
   research, benchmark, scenario, yield, DB, and replay integrations from the
   protocol entry namespace.
4. Only after the constrained profile is small and tests/material are
   self-contained, prepare a separate repository package with its own
   dependency manifest, source provenance, compatibility vectors, and
   independently reproducible acceptance commands.

## Reproduction

From the PRF repository root:

```sh
bb profile:describe sew-core
```

The command reports the resolved source closure, component groups, and current
boundary crossings. This audit is based on the local workspace state and should
be refreshed after any dependency or profile changes.
