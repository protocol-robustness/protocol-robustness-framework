# PRF first-party extensions repository plan

Status: proposed architecture with an incubating source extraction in the
separate repository `/home/user/Code/prf-extensions`. Its remote is configured
and the initial source candidate is on `main`. GitHub currently exposes only
that branch; the latest local package/test commit
`0335ee452053967612a4aef68ddb1563f18a6a8e` (tree
`7f49df6fe60699b57194bb83f986d23218dfa3f4`) is not present on the remote.
No package release or independent qualification is claimed.

## Decision

Use one `prf-extensions` repository as the first-party development and
composition workspace. Keep executable packages and declarative concept bundles
independently versioned and qualified. The repository is not one deployable
extension: consumers select and pin individual package roots and dependencies.
Create a separate repository only when a package has independent maintainers,
external adoption, substantial domain ownership, or a distinct release lifecycle.

Repository co-location does not relax package boundaries. Production source may
depend only on versioned public package APIs, never another package's private
source directory. Test and integration dependencies are opt-in and are not part
of runtime closures.

## Initial layout

```text
prf-extensions/
  packages/
    force-authorisation/
    held-custody/
    repairability-report/
  adapters/
    sew/force-authorisation/
  concepts/
    framework/
    prf-research/
    sew/
    stablecoins/
  integration-tests/
  tooling/
```

These are proposed package boundaries. Start with logical package manifests and
independent versioning; do not split each directory into a separate Git or Maven
project until API and release requirements justify it.

`force-authorisation-scope` owns protocol-neutral scope normalization,
commitments, expiry, target binding, and consumption semantics.
`force-authorisation-governance` consumes the governed-authority API to produce
permits; it does not implement or own the authority provider.
`sew-force-authorisation` owns Sew custody execution and effect evidence. It
depends on the force-authorisation permit contract and held-custody mutation
contract. Keep `governed-authority` and `held-custody` as provider packages.
Force-authorisation remains simulation-only until a separate on-chain path is
implemented and qualified.

## Package manifest requirements

Every package or concept bundle declares:

* Stable ID, semantic version, manifest version, owner, license, support status,
  and source root.
* Public API or catalogue schema version and compatibility promise.
* Runtime dependencies as package IDs plus accepted version ranges; no source
  path dependencies.
* Capability requirements, profiles, input/output contracts, and independent
  verification contract where executable.
* Qualification command, test suite, exact dependency lock, and retained
  qualification evidence.
* For release, immutable source root and artifact hash. A Git commit alone is
  source identity, not a built artifact or dependency closure.

Concept bundles use the same identity, dependency, version, root, and provenance
conventions but have no executable entrypoints. They declare concept ID
namespaces, required bundle versions, catalogue schema, and protocol mapping
ownership. Bundle merge is explicit, deterministic, and rejects duplicate IDs
unless an intentional shadow is declared and validated. No classpath discovery.

An evidence artifact that refers to a concept definition binds the exact bundle
and concept roots. Updating a general explanatory definition does not mutate the
meaning of a retained evidence record.

## Qualification gates

Each executable package must:

1. Load and run from a clean consumer project with no PRF checkout paths.
2. Import only public, versioned core/package namespaces.
3. Resolve all runtime dependencies from pinned immutable coordinates/commits.
4. Pass package-local positive and adversarial tests in isolation.
5. Pass composition tests with required packages missing, incompatible, or
   returning malformed/adversarial results.
6. Retain exact source, artifact, dependency, runtime and qualification roots.

Each concept bundle must pass closed schema validation, namespace and dependency
resolution, explicit ownership/mapping checks, collision checks, and deterministic
merge/root tests. It must not change execution or evidence roots unless a
consumer explicitly commits the bundle root into its own artifact.

## Migration status

### Done in the local extraction

- Created the separate `prf-extensions` repository and configured its `origin`
  remote. The initial source candidate is present on `main`; the newer local
  package candidate is not. This is source publication, not a package release
  or qualification. See that repository's `docs/EXTRACTION_STATUS.md`.
- Copied force-authorisation scope verification, held-custody, a dependency-free
  repairability report renderer, and three declarative concept bundles with
  mappings. The source migration is recorded in
  that repository's `docs/INITIAL_EXTRACTION_PLAN.md`.
- Added independent consumer tests, public API boundary checks, package
  manifests, manifest validation, and a package dependency composition check.
- Added concept bundle validation against the PRF loader. The extracted
  catalogue has a new root; it is not claimed equivalent to the old catalogue
  root.
- Kept the Sew-specific adapters and governed-authority out of the initial
  extraction. The force-authorisation adapter and
  `extensions/sew-core-model` still import PRF protocol, registry, and research
  namespaces. The latter also depends on `packages/sew-protocol-model`, whose
  `deps.edn` still points back to the whole PRF checkout. These are extraction
  blockers, not standalone packages. Define a public protocol/model contract
  and remove that private source dependency before moving them.
- A separate local Sew Core adapter repository exists at
  `/home/user/Code/prf-sew-core-model`, commit
  `d02ad01d06b096e46c542698f043dc0505e70365`. Its 10 tests / 62 assertions
  pass against its declared immutable Git dependency at PRF revision
  `b94b091cfbaf46ec34ae2ade4920f9a3751e3097` when using a temporary writable
  `GITLIBS` cache. Its model dependency still resolves PRF implementation
  namespaces, and the dependency exposes `protocols_src` outside the package
  root. It has no configured remote. Treat it as an incubating source-layout
  candidate, not a qualified independent extension; prefer folding its package
  into the shared extensions repository after the model contract and
  dependency closure are made public.

### In progress or blocked

1. **Public API package:** `packages/prf-extension-api-v1` now contains a
   generated 25-namespace source closure and a `deps.edn` contract. The
   generator checks package copies against canonical PRF sources. The clean-room
   qualification passed 105 tests / 598 assertions, and both extracted package
   suites passed when resolving this narrow local package root. It is not yet
   integrated into staging, published as a Maven artifact, or pinned by
   `prf-extensions`; those consumers still use the broader PRF root.
2. **Concept loader:** focused commit
   `925108c73121d41622925ae9427b100305c18347` is isolated on the exact
   `agent-c-staging` base. Its loader tests and 50-concept validator pass, but
   independent review and staging admission remain required before CI can use
   the loader revision.
3. **CI qualification:** extension package tests, consumers, composition, and
   import-boundary checks passed in GitHub Actions. Manifest validation passed
   locally but its Actions job repeatedly failed during Clojure setup. The
   concept validation job awaits the reviewed loader integration.
4. **Ownership and qualification:** repository remote is established; named
   release owners/authority, independent source-closure review and artifact
   release qualification remain outstanding.
5. **PRF migration:** do not remove the in-core concept catalogue or switch PRF
   consumers until pinned artifacts resolve and historical roots remain
   inspectable.

### Next sequence

1. Independently review the exact loader candidate and land it through the
   authorized PRF staging workflow; then enable concept validation in extension
   CI against that immutable revision.
2. Independently review the API source package and its complete dependency
   closure; update extension dependencies to its subdirectory only after the
   API package is integrated and fetchable.
3. Record named package/repository owners and release authority.
4. Publish package/bundle artifacts individually after their applicable
   qualification gates, then migrate PRF consumers
   in a separate reviewed change while preserving historical catalogue roots.
5. Extract governed-authority or Sew adapters only after their public
   dependency contracts are defined and private imports are removed. In
   particular, make `sew-protocol-model` independently resolvable before
   extracting `extensions/sew-core-model`; then reconcile the existing local
   `prf-sew-core-model` candidate with the shared-repository architecture.

The current local `prf-extensions` branch includes three executable packages:
force-authorisation, held-custody, and repairability-report. Its latest
package/test commit is `0335ee452053967612a4aef68ddb1563f18a6a8e`, tree
`7f49df6fe60699b57194bb83f986d23218dfa3f4`; the current branch head is
`ccd59536b072d076054fbc33db6ed63ce157d505`, tree
`0aec33b394eb45a585bd851d4d6c02e741cb6c3a`, including qualification records.
The branch is 13 commits ahead of local `origin/main`. GitHub confirms the
package/test commit is absent from the remote; a push attempt failed because
no GitHub credentials are available in the shell. No remote changes were made.
Fresh-cache package checks pass, but the candidate has no remote CI run or
independent review. The API and concept-loader candidates remain separate PRF
changes pending review and staging qualification. Source publication is not
release, admission, or deployment evidence.
