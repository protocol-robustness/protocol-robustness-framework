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

The checkout currently keeps the two source trees separate:

- `src/` contains the framework and shared implementation;
- `protocols_src/` contains the Sew protocol implementation and its protocol
  tests (`protocols_src/test`).

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

## Proposed intermediate experiment: separate workspaces and `deps.edn`

A practical next experiment is to use different workspaces, each with a
workspace-local dependency file selecting the intended classpath:

- a framework workspace whose `deps.edn` exposes `src/` and excludes
  `protocols_src/`;
- a Sew workspace whose `deps.edn` exposes the framework source plus
  `protocols_src/` (or, later, a checked-out/published framework dependency).

This gives each editor, REPL, and CI job an unambiguous classpath and makes
accidental reverse dependencies easier to detect. It is useful scaffolding for
repository extraction, but it is not sufficient as the final solution: local
path references remain implicit, versioning is still coupled, and the two
workspaces can drift unless their dependency inputs and validation commands
are pinned.

If implemented, the experiment should preserve a single source of truth for
commands and record the exact invocation for each workspace. It must not rely
on a developer's home-level `~/.clojure/deps.edn` or on an ambient classpath.

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

### P1 — next planned slice / medium urgency: workspace experiment

- Create documented framework-only and Sew-enabled workspace profiles with
  explicit `deps.edn` inputs.
- Run the same narrow tests in both profiles and compare packaging/runner
  behaviour.
- Identify the minimal public framework API that Sew actually consumes.
- Decide whether shared schemas/canonical code need a small foundational package.

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
