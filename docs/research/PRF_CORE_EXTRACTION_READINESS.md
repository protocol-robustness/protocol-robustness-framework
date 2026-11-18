# PRF core extraction readiness

Status: preparation only; no independent `prf-core` repository or release exists.

## Recommendation

Keep core extraction sliced by consumer: the general extension API serves
`force-authorisation` and `held-custody`; a separate portable pro-rata API
serves the Sew economics adapter. Do not move all of `src/` as one package.
The current combined source candidate demonstrates a qualified source closure,
not that the whole framework has one coherent package boundary.

The current 54-namespace source closure contains
`resolver-sim.extensions.api.v1`,
`resolver-sim.extensions.api.pro-rata.v1`, and their implementation namespaces
listed in
[`packages/prf-extension-api-v1/DEPENDENCIES.edn`](../../packages/prf-extension-api-v1/DEPENDENCIES.edn).
Its external dependencies include Clojure 1.12.0, `prf/canonical` at
`5e2b520feca812b049ba397b7b3681963c66a48c`, and
`prf/pro-rata-allocation` at `cf38de1ea4ef03654ee3de8bead56c32ef078b10`, as
well as registry/evidence runtime libraries recorded in the manifest. The
clean-room test stages this closure outside the repository classpath and passes
the isolated API and extension suites (107 tests, 603 assertions).

## Closure ownership review

For the first slice, ownership is intentionally narrow:

| Classification | Namespaces | Contract |
|---|---:|---|
| Public package surface | `resolver-sim.extensions.api.v1`; `resolver-sim.extensions.api.pro-rata.v1` | Versioned contracts for force authorization/held custody and portable pro-rata respectively. |
| Private implementation closure | Other entries in the 54-namespace source closure | Needed to implement the facades and run consumer tests; not a promise that these namespaces are individually public APIs. |
| External PRF runtime dependencies | `prf/canonical` and `prf/pro-rata-allocation` at recorded Git SHAs | Resolved as pinned dependencies, not copied into the candidate source bundle. |
| JVM runtime libraries | Data JSON, Buddy Core, and SLF4J at manifest-pinned versions | Needed by the core evidence and registry implementation closure. |
| Test-only dependency | `org.clojure/test.check` 1.1.1 | Used by source qualification; not a runtime dependency of the facades. |

A namespace scan including `src/`, `test/`, `protocols_src/`, extension
trees, and package roots found substantial direct consumer fan-out outside
this 54-namespace slice. For example, `resolver-sim.hash.canonical` has 574
outside namespace declarations and `resolver-sim.hash.reference` 349;
`genesis` has 32, extension registry 24, configuration head and extension
resolution 19 each, and assurance force-authorisation 18. The earlier narrower
scan undercounted these because it omitted protocol and package roots. These
counts include tests, measure direct requires rather than runtime calls, and
are a point-in-time inventory. They show that physically moving this closure
out of the monorepo without a migration strategy would strand many existing
consumers or risk duplicate namespace resolution. The first artifact can be
qualified for isolated extension consumers, but repository-wide migration is
a separate workstream.

Reproduce the inventory from the repository root with
`clojure -M:check scripts/audit_prf_core_candidate.clj`. The command checks the
computed first-slice closure against its dependency manifest and prints the
outside-consumer counts. It scans source, test, protocol, extension, and package
roots; duplicate namespace declarations across test roots are combined by
namespace.

The remaining source-closure namespaces should therefore be treated as private package
implementation for the initial release even though some have other in-repo
consumers. The cleanest eventual cutover is a single ownership transfer: move
the reviewed namespace files into the core release, make the monorepo depend on
that exact immutable release, and remove the old source copies in the same
change. This avoids maintaining duplicate implementations. If repository
ownership or release timing prevents that atomic cutover, first split the
candidate into an API package whose private dependency is independently
versioned; do not keep two active copies of the same namespace on one
classpath. Before source files move, capture the reverse-consumer inventory as
a checked migration fixture and update every consumer classpath and build.

## What this slice does and does not establish

- It establishes a reproducible source snapshot for these two extension
  consumers under the recorded toolchain and dependency pins.
- It does not create or identify a published Maven artifact, JAR, Git release,
  or separately governed repository.
- It does not qualify every public PRF API or every namespace under `src/`.
- It does not provide process isolation. Clojure extensions executing in the
  same JVM retain the process privileges described in ADR-0005.
- The source closure includes implementation namespaces from assurance,
  accounting, economics, evidence, configuration, hashing, and transaction
  handling. Their inclusion is a dependency fact, not a claim that they all
  belong in a permanent `prf-core` ownership boundary.

## Extraction gates

1. **Approve ownership and compatibility.** Name the maintainers, repository
   owner, license, API compatibility policy, supported Clojure/JVM versions,
   and deprecation policy. Keep both facade namespaces versioned and explicit;
   publish the pro-rata facade only with its complete reviewed source closure.
2. **Review the closure.** For every namespace in the dependency manifest,
   record whether it moves into the first release, remains a private
   implementation dependency, or blocks extraction. Review resource and
   configuration inputs as well as `ns` dependencies.
3. **Create a standalone build from the reviewed closure.** The build must
   work from a clean checkout with no repository-root paths, protocol source,
   local `:local/root`, or developer configuration. Produce a reproducible
   source archive and JAR with source, artifact, resolved-dependency, and
   runtime identities recorded separately.
4. **Dogfood the release.** Change both extension packages to consume that
   exact immutable release coordinate. Run their unit, boundary, clean-install,
   and compatibility checks in a fresh environment.
5. **Release only after independent verification.** Verify the published
   artifact and its dependency graph from a consumer environment. Then update
   the package catalog and replace the current `:local/root` declarations.
6. **Expand by consumer.** Extract another module only when an independent
   consumer requires it and its owner, API, dependency direction, and
   qualification can be stated without relying on monorepo layout.

## Current blockers and owners to supply

- A legitimate owner must approve the new repository and release identity.
- A core maintainer must approve the source ownership map and API compatibility
  policy.
- The package repositories queried during this work did not contain an
  `org.protocol-robustness/prf-core` artifact. A version or coordinate must
  come from the approved release process; it must not be inferred from the
  local package name.
- The current clean-room qualification is source based. Re-run it against the
  exact independently built and published artifact before describing the
  packages as independently released.

## Decision point

The team can proceed with repository preparation for this first slice once an
owner approves the repository and license. Until then, keep the source
candidate and its honest `:unpublished` status in this repository. Do not
extract the whole framework or switch consumers to a guessed artifact
coordinate.
