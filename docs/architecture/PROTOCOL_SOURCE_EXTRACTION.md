# Protocol source extraction and repository boundary

## Decision summary

Keep the current source-path workaround as a deliberate short-term boundary,
but do not treat it as the target packaging model. The target is to decompose
the framework and protocol implementations into separately versioned
repositories, with an explicit dependency from the protocol repository to the
framework (and no reverse dependency).

The repository split is architectural work, not an urgent prerequisite for
ordinary framework development. The classpath boundary is already explicit in
the current build; the immediate goal is to preserve and verify it so that new
code does not accidentally deepen the coupling.

## Current workspace workaround

The checkout keeps framework and shared implementation in `src/`. Sew model
code lives in `packages/sew-protocol-model`; runner, research, analysis, yield,
slash-economics, and slash-evidence integrations have separate source roots
under `extensions/`. The `sew-protocol` profile resolves 59 model-owned source
files from the package. Force Authorization and legacy runner/support code
remain in `protocols_src/`. The model package manifest does not include
`protocols_src`; the Sew development workspace adds protocol test paths and
selects the model and extension packages explicitly.

The base `deps.edn` classpath includes `src` but not `protocols_src`. The
`:with-sew` alias opts into the protocol tree:

```bash
clojure -M:with-sew ...
```

The test alias also selects the protocol paths, so the normal protocol test
commands run with Sew present. Framework-only commands should not select
`:with-sew`; this is the useful check that framework code does not silently
require Sew.

This is a source-path boundary, not a true dependency boundary. Both trees
still live in one repository, share one `deps.edn`, one dependency graph, and
one release/change history. A namespace can therefore cross the boundary by
accident, and the build does not yet model Sew as an independently consumable
artifact.

## Intermediate experiment: separate workspaces and `deps.edn`

`workspaces/framework/deps.edn` and `workspaces/sew/deps.edn` select two
workspace-local classpaths. The Sew workspace uses the model package through a
local-root development overlay and adds extension source trees plus remaining
`protocols_src` tests. Use the `:dev/local-economics` alias to override the
unpublished economics revision with its in-checkout package while developing.
The model package manifest pins PRF core at immutable Git
revision `b94b091cfbaf46ec34ae2ade4920f9a3751e3097` and the economics extension
at candidate commit `c6d21f852f8fbeff3d29b52c1139ad6f88ed332a`, using its Git
subdirectory manifest. That economics commit is not yet present on a shared
remote, so clean-clone dependency resolution remains unqualified. Run commands
from the matching directory
with `-Srepro` so home-level Clojure configuration cannot add paths or deps:

```bash
(cd workspaces/framework && clojure -Srepro -Spath)
(cd workspaces/sew && clojure -Srepro -M:dev/local-economics -e "(require 'resolver-sim.protocols.sew.kernel)")
(cd workspaces/framework && clojure -Srepro -M -e "(require 'resolver-sim.pro-rata.allocation)")
```

The workspace overlay remains development scaffolding. The core dependency is
independently resolvable but refers to an earlier PRF tree and does not include
the current package-boundary changes. The economics dependency now names an
immutable candidate revision, but clean resolution requires that revision to
be published and verified from a fresh checkout. Remaining
Force Authorization sources and tests also still live under `protocols_src` and
need coordinated ownership before relocation.

The initial namespace probe showed that `resolver-sim.core` is an application
runner, not a framework API: it loads Sew research and simulation phases. The
runner component now declares its Sew and slash-evidence dependencies, owns
the fixture and reference-validation entrypoints, and includes the protocol
fixtures they need. The generic fixture and reference-validation namespaces
now use registered scenario catalogs and protocol capabilities rather than
loading Sew implementation namespaces directly. The Sew cancellation
projection lives in the model package, and the resolver-ring model lives in
`extensions/sew-research-models`; the generic batch runner invokes ring
simulation through a registered runner adapter. Sew dispute-context case
bundles and runner commands, including protocol finality and invariant
execution, live in `extensions/sew-runner`. The portable framework profile
remains the framework-only load boundary.
The Sew appeal-bond transition receipt now lives in a Sew replay adapter,
registered through the protocol capability registry. Appeal-bond projection,
finalization metrics, and receipt validation now live in that adapter too;
shared replay requests it only for results containing Sew escrow or bond state.

The Sew workspace loads the model through the local economics override.
`bb profile:check framework` and
`bb profile:check sew-kernel` pass. The full `sew-protocol-model-core` profile check
previously failed during view generation because the exact-verifier component
declared an absent `coprocessor/core` Rust crate. That stale material entry has
been removed; profile generation now succeeds. `runner` and supported
`sew-protocol-model` profile boundary/load checks pass. The runner profile declares its
protocol fixture data and test material. Profile link generation verifies
that destinations remain inside `.profile-view` and cannot overwrite source
paths. The runner-free `sew-protocol-model-core` profile now passes its
boundary/load check without runner, yield integration, or pro-rata economics
adapter sources. This check does not qualify all model-only runtime behavior;
no-yield fallback and fail-closed optional capability behavior have focused
contract coverage.

The runner-free `sew-protocol` candidate resolves to 203 files with no
component crossings. The `sew-protocol-model-core` profile resolves to 129 files
and excludes the Sew yield integration, shared yield runtime, and optional
pro-rata economics adapter. Full Sew profiles select these integrations
explicitly. Replay and batch reporting are runner capabilities;
researcher authorization and scenario analysis are separately selectable Sew
integrations. Scenario and replay sources are absent from the runner-free
candidate, while benchmark and shared framework dependencies remain. Keep
research models and runner assembly out of the initial `prf-sew` runtime
package. The exact pro-rata verifier remains a candidate for a separate
optional assurance package later, once its dependency contract is independently
qualified.

## Target approach: multiple repositories

Decompose into at least:

1. **Framework repository** — canonical framework, shared contracts, runners,
   evidence/canonical infrastructure, and framework tests. It must build and
   test with no Sew source available.
2. **Sew protocol repository** — Sew state machine, accounting/resolution
   implementation, protocol tests, and protocol-specific conformance suites.
   It consumes a released framework API/artifact, or an explicit local checkout
   during development.
3. **Optional later repositories** — only for independently owned and released
   protocol/extension packages when their API and release cadence justify the
   operational cost. Do not split every directory pre-emptively.

The dependency direction is one-way: framework <- Sew. Shared schemas,
canonical formats, and cross-language interfaces must be extracted or owned by
the lowest-level stable package before the split. CI should test the framework
alone, then test Sew against a pinned framework version, and finally run an
explicit compatibility/conformance job.

### Candidate move to `prf-extensions`

The initial destination for PRF's Sew behavioral model is
`packages/sew-protocol-model/` in `~/Code/prf-extensions`. This repository
currently has no Sew model package. Keep `adapters/sew/` for PRF-specific
adapters; the behavioral model is a package-level implementation, not an
adapter. Move the model only after its current package qualification and
dependency blockers are resolved.

The destination repository's package contract requires more than `deps.edn`
and source files. The moved package must add an incubating `extension.edn`
whose capabilities and runtime dependencies match its runtime manifest, a
package test runner, and an independent `consumer-test` runner. Register the
manifest and runtime implementation in `tooling/validate_packages.clj`, add
the package to the repository's validation and CI classpaths, and include a
README with reproducible `-Srepro` commands. Keep package tests and consumer
tests separate: the former exercise implementation behavior, while the latter
prove use through the declared API.

Before moving the model source:

1. Replace its economics dependency on unpublished PRF candidate revision
   `c6d21f852f8fbeff3d29b52c1139ad6f88ed332a` with an independently resolvable
   economics package revision. Do not leave a local workspace override in the
   runtime dependency graph. The PRF core pin
   `b94b091cfbaf46ec34ae2ade4920f9a3751e3097` is publicly reachable; the
   economics pin is not. Also establish the dependency as a supported boundary:
   the adapter now calls the in-tree
   `resolver-sim.extensions.api.pro-rata.v1` facade for allocation, evidence,
   and projection evaluation. The facade has isolated consumer tests, but the
   economics manifest still pins a public PRF revision that predates this API;
   its test alias uses the local PRF source tree. The API package also closes
   over core registry, evidence, and execution code, so retain its explicit
   source qualification and dependency list. Publish or otherwise make the
   API-bearing PRF revision independently resolvable before moving economics.
   `prf-extensions/adapters/sew/README.md` continues to defer Sew adapters
   until their PRF dependencies are public contracts.
2. **Completed on 2026-11-16:** reconciled the candidate with staging through
   `jjq` item 72 (`0c12f4ee`) and advanced `agent-c-3` to that landed revision.
   The Sew package suite (733 tests / 2,456 assertions), core API qualification
   (107 / 603), dispute-review tests (8 / 32), focused Sew Core adapter tests
   (10 / 62), profile checks, `bb fmt:check`, full `bb backstop`, and the JJQ
   `bb backstop:fast` gate passed. The package still cannot move while the
   public PRF API and economics revisions remain unavailable to clean-clone
   consumers; continue preserving Force Authorization ownership during that
   dependency work.
3. Keep the existing `replay-dedupe-policy-test` failure assigned to the Force
   Authorization owner. The exact namespace fails on both this candidate and
   `main` with the same 36 failures out of 106 assertions: its Force
   Authorization actions run with an empty context and are rejected by the
   model's existing admission gate. Treat this as a verified baseline failure,
   not an extraction regression. Before moving the model, compare the broad
   suite on the reconciled candidate against that baseline and require no new
   failures; do not change Force Authorization behavior as part of the model
   move.
4. Transfer the package's source, test, resource, and provenance closure, then
   qualify both `-M:test` and `-M:consumer-test` from a clean clone of
   `prf-extensions`. Verify the resulting dependency lock/roots and rerun the
   PRF profile checks against the package consumer.

Keep the Sew Core profile adapter separate under the Sew adapter area. It is a
PRF composition adapter and should depend on the moved model package through
its declared API; it should not be folded into the model package merely to
make the repository move easier.

## Priorities and urgency

### P0 — now / high urgency: preserve the existing boundary

This is already the current repository behaviour, not new work:

- `protocols_src` is excluded from the base/project classpath.
- Sew-aware development and builds select `:with-sew` explicitly.
- The test alias intentionally includes the Sew paths because it runs the
  protocol test suite; that does not make Sew part of the framework-only base
  environment.

The remaining P0 work is to keep this contract visible and protected:

- retain framework-only CI that proves the framework loads and tests without
  Sew;
- reject new framework-to-Sew reverse dependencies in review or a lightweight
  namespace/dependency check; and
- document the exact classpath selection in developer and CI commands.

This prevents further coupling while the current layout remains in use.

### P1 — next planned slice / medium urgency: qualify the workspace boundary

- Replace the workspace local-root dependency with an immutable framework
  artifact or explicit sibling checkout.
- Identify and remove the remaining runner/research reverse dependencies from
  framework-owned namespaces.
- Reconcile stale exact-verifier component material so full Sew profiles can
  be generated and qualified.
- Identify the minimal public framework API that Sew consumes and decide
  whether shared schemas/canonical code need a small foundational package.

This is valuable engineering preparation, but should not block feature delivery
unless a new protocol or consumer requires isolation.

### P2 — planned architectural migration / medium-to-low urgency: repository split

- Publish or locally substitute the framework artifact.
- Move Sew and its tests into the protocol repository.
- Pin framework versions and add compatibility/conformance CI.
- Migrate release, provenance, and documentation workflows.
- Remove the monorepo path alias only after independent builds and parity checks
  pass.

The split becomes urgent before independent protocol release, separate access
control/ownership, or external consumers depend on the framework API. Until
then, it is a planned decoupling investment rather than a production blocker.

## Exit criteria

- Framework tests and packaging run without `protocols_src` on the base/project
  classpath.
- Sew is consumable through an explicit local or published dependency.
- The framework has no reverse dependency on Sew implementation code.
- Framework and Sew have independently reproducible dependency inputs.
- Runner, trace-equivalence, and protocol conformance behaviour remain
  identical across the migration.
- Release, provenance, and compatibility checks are deterministic and documented.
