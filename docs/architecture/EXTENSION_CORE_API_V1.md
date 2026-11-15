# PRF extension core API v1

## Purpose and status

`resolver-sim.extensions.api.v1` is the general versioned source boundary for
PRF extension packages. The portable pro-rata allocation/evidence/evaluation
contracts have a separate facade at
`resolver-sim.extensions.api.pro-rata.v1`. Package runtime source must import
PRF through the versioned facade that owns the contract it consumes rather than
core implementation namespaces. These namespaces re-export existing behavior;
they do not create new authorization, hashing, or allocation semantics.

This establishes a source compatibility contract inside the repository. PRF
core is still consumed through a local dependency in this workspace, so this
document does not claim that a separately published core artifact exists.

The in-tree source candidate is tracked in
`packages/prf-extension-api-v1/DEPENDENCIES.edn`. Its clean-room qualification
stages the 54-namespace source closure into a temporary project with the
canonical codec and pro-rata allocator pinned by Git SHA, then runs the
isolated consumer contract and the force-authorisation and held-custody package
suites without the repository root or `protocols_src` on the classpath. Sew
economics has a separate local-source test because its public PRF pin predates
the pro-rata API. This qualifies the staged source closure; it does not create
an immutable Maven coordinate or independently qualify the Sew package.

## API surface

The v1 API exports these functions and values:

```text
normalize-force-authorisation-scope
normalize-force-authorisation-record
force-authorisation-scope-hash
scope-hash-missing?                     scope-hash-mismatch?
verify-authorisation-usable             semantic-operation-class
valid-configuration-head-state?
artifact-envelope-keys                  artifact-body
finalize-artifact                       valid-artifact?
preimage-and-hash-valid?
domain-hash                             sha256-ref
canonical-sequence-bytes                sequence-hash
remote-authority-required-artifact?
```

The portable pro-rata API exports `allocate`, `mechanism-evidence-artifact`,
`evidence-reference`, `evidence-valid?`, `build-projection-artifact`, and
`calculate-from-projection` from
`resolver-sim.extensions.api.pro-rata.v1`. Its implementation dependency set is
also pinned by `resolver-sim.extensions.package-boundary-test` and the package
dependency manifest.

The implementation dependency set is pinned by
`resolver-sim.extensions.package-boundary-test`. A new implementation
dependency requires review because it changes the surface's trusted closure.
Run `bash scripts/test_extension_packages.sh` to execute the boundary test,
extension package suites, and the clean-room source-closure qualification in
`packages/prf-extension-api-v1`. The repository's `scripts/test.sh` `all` and
`ci` modes include the same check.

## Compatibility rules

- Additions that preserve existing behavior may be added to v1.
- Changing argument order, return shape, hashing semantics, validation policy,
  or failure behavior requires a new versioned API namespace.
- Extension packages must not bypass the relevant versioned API by importing
  implementation namespaces directly.
- API v1 does not promise sandboxing, process isolation, or artifact release
  reproducibility. Those require separately qualified execution and release
  machinery under ADR-0005.

`verify-authorisation-usable` is specifically the existing single-scope
lifecycle validator. It does not establish related-claim membership or Sew
protocol enforcement; those remain caller/protocol-layer responsibilities.
