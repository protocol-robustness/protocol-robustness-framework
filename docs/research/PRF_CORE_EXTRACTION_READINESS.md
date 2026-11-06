# PRF core extraction readiness

Status: preparation only; no independent `prf-core` repository or release exists.

## Recommendation

Extract a narrow, source-pinned core slice first: the supported extension API
and the complete implementation closure needed by `force-authorisation` and
`held-custody`. Do not move all of `src/` as one package. The current extension
qualification demonstrates a tractable first slice, not that the whole
framework has one coherent package boundary.

The source candidate is `resolver-sim.extensions.api.v1` plus the 24
implementation namespaces listed in
[`packages/prf-extension-api-v1/DEPENDENCIES.edn`](../../packages/prf-extension-api-v1/DEPENDENCIES.edn).
Its tested external dependencies are Clojure 1.12.0, `prf/canonical` at
`5e2b520feca812b049ba397b7b3681963c66a48c`, and test.check 1.1.1 for tests.
The clean-room test stages this closure outside the repository classpath and
passes both extension suites (113 tests, 622 assertions).

## Closure ownership review

For the first slice, ownership is intentionally narrow:

| Classification | Namespaces | Contract |
|---|---:|---|
| Public package surface | `resolver-sim.extensions.api.v1` | The only namespace extension packages may import; compatibility follows API v1. |
| Private implementation closure | Other 24 `resolver-sim.*` entries in the dependency manifest | Needed to implement the facade and run the extension suites; not a promise that these namespaces are individually public APIs. |
| External runtime dependency | `prf/canonical` at the recorded Git SHA | Resolved as a pinned dependency, not copied into the candidate source bundle. |
| Test-only dependency | `org.clojure/test.check` 1.1.1 | Used by the source qualification; not a runtime dependency of the API. |

A namespace scan including `src/`, `test/`, `protocols_src/`, both extension
trees, and package roots found substantial direct consumer fan-out outside
this 25-namespace slice. For example, `resolver-sim.hash.canonical` has 574
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

The remaining 24 namespaces should therefore be treated as private package
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
   and deprecation policy. Keep `resolver-sim.extensions.api.v1` as the only
   package-facing namespace for the first release.
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
