# PRF first-party extensions repository plan

Status: proposed architecture with an incubating source extraction in the
separate repository `/home/user/Code/prf-extensions`. Its remote is configured
and the initial source candidate is on `main`. The remote `main` is
`ccd59536b072d076054fbc33db6ed63ce157d505`; the local ownership-cleanup branch
is at `0ba7bc77c92e386f7394569d173af6f70ebf6f9b` and is not pushed. No package
release or independent qualification is claimed.

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
    sew-protocol-model/   # after its public PRF dependencies are fetchable
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

The Sew behavioral model belongs in `packages/sew-protocol-model/`, separate
from PRF composition adapters. The source candidate remains in the PRF
checkout until its runtime dependencies are independently resolvable: the
current core pin predates the pro-rata API facade, and the Sew economics
revision is not published. The model and a Sew Core profile adapter are
separate packages with separate contracts; do not move either by copying
private PRF source into `prf-extensions`.

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

1. **Public API package:** `packages/prf-extension-api-v1` exposes the original
   API and portable pro-rata API. Its source closure has 54 namespaces, and its
   isolated qualification passed 107 tests / 603 assertions at
   `sha256:a38d2a31f24f905b1717b9dffaffff136866b850a38e459f51f5aaee622f5d48`.
   This is an in-tree source candidate, not a published core artifact. The
   public PRF `main` revision `85983ba2766908d8c68792b090fcfafb378ea468`
   predates the portable pro-rata API; the API-bearing candidate is not yet
   fetchable by independent consumers.
2. **Concept loader:** concept-bundle packaging and loading are now integrated
   in `agent-c-staging` at `1ef277bbb60e4660431c1753c142a221a3831220`. The
   loader tests and 50-concept validator were reported passing. Independent
   review, qualification against the merged candidate, and public integration
   remain outstanding before CI or consumers can rely on that revision.
3. **CI qualification:** extension package tests, consumers, composition, and
   import-boundary checks passed in GitHub Actions. Manifest validation passed
   locally but its Actions job repeatedly failed during Clojure setup. The
   concept validation job awaits the reviewed loader integration.
4. **Ownership and qualification:** repository remote is established; named
   release owners/authority, independent source-closure review and artifact
   release qualification remain outstanding.
5. **Sew model:** the model source candidate lives at
   `packages/sew-protocol-model/` in PRF. Its runtime manifest pins core
   `b94b091cfbaf46ec34ae2ade4920f9a3751e3097` and Sew economics
   `c6d21f852f8fbeff3d29b52c1139ad6f88ed332a`. The core pin lacks the new API,
   and the economics commit is not independently fetchable. Its test alias
   uses local PRF source, so clean-consumer qualification is pending. The
   package runner passed 733 tests / 2,456 assertions on the merged candidate
   with local overrides; this is not clean-consumer qualification. The package
   README records the local test command and these limits. Keep the model in
   PRF until both dependencies can be fetched without local source overrides.
   See `docs/architecture/SEW_STANDALONE_BOUNDARY_AUDIT.md` for the remaining
   source ownership and profile closure.
6. **PRF migration:** do not remove the in-core concept catalogue or switch PRF
   consumers until pinned artifacts resolve and historical roots remain
   inspectable.

### Next sequence

1. Independently review the concept loader now integrated in `agent-c-staging`,
   rerun its validation on the merged tree, and enable extension CI only against
   that reviewed immutable revision.
2. Independently review the API source package and its full dependency closure.
   Integrate and publish or pin an API-bearing PRF revision before updating
   extension consumers.
3. Make the Sew economics revision fetchable, then run the Sew model package in
   a clean consumer without local source overrides. Keep the model in PRF until
   that qualification passes.
4. Move the qualified model into `prf-extensions/packages/sew-protocol-model/`
   with its incubating extension manifest, package and consumer runners,
   validation registration, and reproducible instructions.
5. Reconcile the local `prf-sew-core-model` candidate with the shared-repository
   architecture only after the model contract is public; keep the Sew Core
   profile adapter separate from model ownership.
6. Record named package/repository owners and release authority. Publish
   package/bundle artifacts individually after their applicable qualification
   gates, preserving historical catalogue roots during PRF consumer migration.

The local `prf-extensions` checkout contains force-authorisation,
held-custody, and repairability-report packages; it has no Sew model package.
Its current local branch is `agent-c-2-ownership-cleanup` at
`0ba7bc77c92e386f7394569d173af6f70ebf6f9b`, based on remote `main` at
`ccd59536b072d076054fbc33db6ed63ce157d505`. A separate local
`prf-sew-core-model` candidate exists at
`d02ad01d06b096e46c542698f043dc0505e70365`; its tests passed against core
`b94b091cfbaf46ec34ae2ade4920f9a3751e3097`, but its dependency still exposes
PRF implementation namespaces and `protocols_src`, and it has no remote.
Treat it as a source-layout candidate, not an independently qualified package.

The PRF API and concept-loader changes remain source candidates pending their
applicable independent review and qualification. Source publication is not a
package release, admission, or deployment evidence.
