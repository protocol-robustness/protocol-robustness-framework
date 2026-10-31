# Sew standalone boundary audit

Status: preparation; local source responsibilities have been separated, but no
source has been moved out of PRF and no protocol semantics have changed.

## Summary

The current `sew-core` profile is not a standalone protocol package boundary.
At the current workspace state, `bb profile:describe sew-core` resolves 350
Clojure source files. The profile descriptor selects `runner` and `sew-core`.
The initial audit found four undeclared component crossings. The component
registry now separates the Sew slash-evidence adapter, pro-rata evaluation,
portable allocator, verifier, and Sew settlement adapter. The current `sew-core`
and `pro-rata` profile reports have zero undeclared component crossings, though
their transitive source closures remain large. A new `sew-protocol` candidate
profile excludes runner assembly and resolves to 234 source files with zero
component crossings. That candidate closure is still too broad to count as a
standalone package.

This audit describes current PRF ownership and extraction blockers. It does not
propose changes to protocol semantics or qualify an independently packaged Sew
implementation.

## Current composition

`profiles/sew-core.edn` selects `:prf/runner-v1` and `:prf/sew-core-v1`.
The runner component owns scenario, server, replay and CLI entrypoints. The Sew
component declares only `resolver-sim.protocols.sew` as its entry namespace and
requires the framework plus the explicit Sew pro-rata slashing adapter. That
adapter depends on pro-rata evaluation, which in turn depends on the portable
allocator and exact verifier. The Sew pro-rata settlement adapter is separate
and selected by the `pro-rata` profile.

The resolved 350-file closure includes these largest groups:

| Source group | Files | What it indicates |
| --- | ---: | --- |
| `protocols_src/resolver_sim/protocols` | 74 | Sew implementation and shared adjudication adapters |
| `src/resolver_sim/yield` | 28 | Yield runtime and accounting integration |
| `protocols_src/resolver_sim/research` | 25 | Sew-specific research analyses pulled into the closure |
| `src/resolver_sim/sim` | 22 | Simulation support |
| `src/resolver_sim/scenario` | 20 | Scenario evaluation and reporting |
| `src/resolver_sim/io` | 15 | Runner and I/O integration |
| `src/resolver_sim/benchmark` | 14 | Benchmark/research coordination integration |

These are dependency-closure counts, not a proposed package manifest.

The `sew-protocol` candidate omits the runner component and has a 234-file
closure. Its largest groups remain Sew protocol sources (56), yield (28),
scenario (14), benchmark (14), and contract-model replay (12). This is the
better starting view for further separation, but the remaining groups show
that the Sew entry namespace still pulls research, yield, scenario, benchmark,
and shared framework services into its runtime closure.

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

### Pro-rata evaluation inside Sew slash evidence

`protocols_src/resolver_sim/protocols/sew/pro_rata/evidence.clj` imports
`resolver-sim.pro-rata.evaluation`. That namespace deliberately composes the
pro-rata allocator, redistribution engine, and independent exact verifier to
produce canonical result artifacts. This is an application integration, not a
generic protocol primitive. Decide whether Sew core should depend on a narrow
allocation/evidence contract or whether slash-allocation support belongs in a
separate Sew integration component.

The slash-allocation and slash-evidence code now has explicit
`resolver-sim.protocols.sew.pro-rata.*` namespaces. The general Sew economics
namespace no longer imports PRF's pro-rata evaluator directly. The profile-view
dependency scanner was also extended to include literal lazy namespace
references; this exposed 41 additional source files compared with the earlier
require-only audit. The old
`sew.economics` and `sew.evidence.slashing` entrypoints remain compatibility
facades; Sew resolution calls the named pro-rata evidence adapter. This makes
ownership visible in source, and the declared component graph now has no
crossings for the audited profiles. The adapter remains on the `sew-core`
runtime path, so the source closure is not yet smaller or independently
packageable.

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
directories. The slash-evidence adapter now declares its tests and conformance
vector, but the core Sew component still lacks a curated self-contained test
closure. A package candidate needs an explicit dependency manifest and complete
test/fixture closure.

## Recommended preparation sequence

1. Keep the current `sew-core` profile documented as the composed runtime; do
   not label its current closure standalone.
2. Preserve the new component split and inventory direct imports from Sew
   namespaces, classifying each as protocol core, framework API, or optional
   integration.
3. Determine whether the pro-rata slash adapter can be selected independently
   without changing Sew's current evidence/result contract.
4. Reduce the candidate profile's 234-file closure by removing unselected
   research, benchmark, scenario, yield, DB, and replay integrations from the
   protocol entry namespace.
5. Only after the constrained profile is small and tests/material are
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
