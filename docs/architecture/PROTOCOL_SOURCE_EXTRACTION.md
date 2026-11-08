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

The checkout keeps framework and shared implementation in `src/`, while most
optional Sew integrations and research code remain in `protocols_src/`. The
Sew escrow kernel and 48 additional core model namespaces, plus four component
contract tests, have moved into `packages/sew-protocol-model/{src,test}`.
The pro-rata slash-evidence adapter and its dedicated slashing tests now live
in `extensions/sew-pro-rata-slashing/{src,test}`. Slash economics lives
separately in `extensions/sew-pro-rata-economics`, and the analysis adapter with
its dedicated tests lives in `extensions/sew-analysis/{src,test}`.
Force-authorisation and shared adapter tests remain in `protocols_src` under
separate component owners. The package manifest still includes
`../../protocols_src`, so this is a partial relocation, not a standalone package
boundary.

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
`protocols_src` tests. The model package manifest pins PRF core at immutable Git
revision `b94b091cfbaf46ec34ae2ade4920f9a3751e3097`, but it still resolves the
unpublished Sew pro-rata economics extension through a local-root path. Run
commands from the matching directory
with `-Srepro` so home-level Clojure configuration cannot add paths or deps:

```bash
(cd workspaces/framework && clojure -Srepro -Spath)
(cd workspaces/sew && clojure -Srepro -Spath)
(cd workspaces/framework && clojure -Srepro -M -e "(require 'resolver-sim.pro-rata.allocation)")
(cd workspaces/sew && clojure -Srepro -M -e "(require 'resolver-sim.protocols.sew.kernel)")
```

The workspace overlay remains development scaffolding. The core dependency is
independently resolvable but refers to an earlier PRF tree and does not include
the current package-boundary changes. Replacing the economics local-root
requires a shared immutable revision containing its package manifest. Remaining
Force Authorization sources and tests also still live under `protocols_src` and
need coordinated ownership before relocation.

The initial namespace probe showed that `resolver-sim.core` is an application
runner, not a framework API: it loads Sew research and simulation phases. The
runner component now declares its Sew and slash-evidence dependencies, owns
the fixture and reference-validation entrypoints, and includes the protocol
fixtures they need. The generic fixture and reference-validation namespaces
now use registered scenario catalogs and protocol capabilities rather than
loading Sew implementation namespaces directly. The Sew cancellation
projection and resolver-ring batch implementation now live under
`protocols_src`; the generic batch runner invokes ring simulation through a
registered runner adapter. Sew dispute-context case-bundle and model-only
fixture utilities, plus the protocol-finality command, also moved under
`protocols_src` and are owned by the runner profile. The portable framework
profile remains the framework-only load boundary. The Sew invariant-suite
command also moved under `protocols_src` and is declared as runner-owned.
The Sew appeal-bond transition receipt now lives in a Sew replay adapter,
registered through the protocol capability registry. Appeal-bond projection,
finalization metrics, and receipt validation now live in that adapter too;
shared replay requests it only for results containing Sew escrow or bond state.

The workspace probes pass with 120 framework classpath entries and 121 Sew
entries; the only additional entries are `protocols_src` and
`protocols_src/test`. `bb profile:check framework` and
`bb profile:check sew-kernel` pass. The full `sew-protocol-model-core` profile check
previously failed during view generation because the exact-verifier component
declared an absent `coprocessor/core` Rust crate. That stale material entry has
been removed; profile generation now succeeds. `runner` and supported
`sew-protocol-model` profile boundary/load checks pass. The runner profile declares its
protocol fixture data and test material. Profile link generation verifies
that destinations remain inside `.profile-view` and cannot overwrite source
paths. The runner-free `sew-protocol-model-core` profile now passes its
boundary/load check without runner or yield integration sources. This check
does not qualify all model-only runtime behavior; no-yield fallback and
fail-closed yield capability behavior have focused contract coverage.

The runner-free `sew-protocol` candidate resolves to 201 files with no
component crossings. The `sew-protocol-model-core` profile resolves to 143 files
and excludes the Sew yield integration and shared yield runtime. Full Sew
profiles select `sew-yield-integration`, which has an in-tree manifest with a
local model dependency. Replay and batch reporting are runner capabilities;
researcher authorization and scenario analysis are separately selectable Sew
integrations. Benchmark, scenario, replay, and shared framework code remain in
the candidate closure. Keep research models and runner assembly out of the
initial `prf-sew` runtime package. The exact pro-rata verifier remains a
candidate for a separate optional assurance package later, once its dependency
contract is independently qualified.

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
