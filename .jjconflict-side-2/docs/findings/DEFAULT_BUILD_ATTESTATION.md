# Default Build Attestation

## Status

Implemented as an unsigned, integrity-verifiable build-evidence contract.

`bb build:attest` builds both supported distributions, runs the packaged-JAR
smoke gate once, and writes:

- `target/default-build-attestation-prf.edn`
- `target/default-build-attestation-sew.edn`

The public command registry declares this as the external wrapper command:

```text
build attest
```

It is intentionally `:command/jar-availability :external`: a JAR cannot attest
its own build. The wrapper is present in `bb.edn` and checked by registry parity.

## Evidence model

Each `default-build-attestation-bundle.v1` contains:

1. a `default-build.v1` definition for exactly one supported variant;
2. SHA-256 references for concrete build inputs (`deps.edn`, `bb.edn`, build
   script, command registry, portability smoke script, and the Sew corpus
   declaration where applicable);
3. the declared build command and expected artifact/entrypoint;
4. the produced JAR byte hash;
5. the packaged-JAR smoke result, requiring native-command resolution; and
6. a content-addressed bundle root.

Verification is fail-closed for a missing or failing smoke result, altered JAR
bytes, altered declared inputs, invalid definition binding, or altered bundle
content.

## Assurance boundary

`:verified? true` means **integrity-verified build evidence**. It does not mean
that a trusted release authority signed or approved the distribution. The bundle
contains optional builder identity only; release authorization requires a
separate signature/trust-policy layer.

The build-attestation surface has no authority over Sew protocol state. In
particular, it cannot invoke or stand in for `add-held`, `sub-held`, or any
custody-ledger mutation.

## Release authorization

`resolver-sim.run.release-attestation` now provides Ed25519 release-payload
signing, trusted-key verification, and per-distribution distinct-key threshold
evaluation. It recomputes the signed payload hash before signature verification,
so changing the declared JAR or build-bundle reference invalidates approval.

A trust policy is structurally valid in lifecycle states `:unconfigured`,
`:active`, or `:retired`. Only `:active` may authorize a **new** release.
`:unconfigured` and `:retired` remain valid policy artifacts but always yield
`:release-policy-not-active` at the authorization boundary. Low-level signature
verification remains distinct and may be used for historical evidence review.

This cryptographic seam is intentionally not invoked by `bb build:attest` yet:
that task produces unsigned integrity evidence. A release workflow must supply
an explicit trusted-key policy and signatures over a payload that references the
emitted build bundle; it must not treat builder identity as authorization.

An operator can attach a release signature locally (not in CI):

```sh
bb build:attest:sign <bundle.edn> <prf|sew> <key-id> <private-key> <release-metadata.edn>
```

Signed bundles are verified through:

```sh
bb build:attest:verify <bundle.edn> <artifact-root> <prf|sew> <trust-policy.edn>
```

The command fails unless both integrity verification and the policy's release
signature threshold succeed.

## Remaining work

- Add an operator-facing signing workflow that writes release payload/signature
  objects to the published bundle without exposing private keys to CI.
- Retain the smoke stdout/stderr as a separately content-addressed object,
  rather than only its output hash.
- Exercise `bb build:attest` in a release/CI environment and publish the
  resulting attestation bundles with distribution artifacts.
