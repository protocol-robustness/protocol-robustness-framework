# Release Attestation Runner Guide

This guide describes how build, verification, and signing runners participate in
the PRF release-attestation process. It is intentionally conservative:
**building an artifact, verifying an artifact, and authorizing publication are
separate roles.**

## Roles and authority

| Runner | Has source checkout | Has private release key | May authorize a release |
|---|---:|---:|---:|
| Build runner | Yes | No | No |
| Verification runner | No source required | No | No |
| Signing runner | Bundle + approved metadata | Yes, locally | Yes, only through an active policy |
| Release publisher | Published artifacts | No required | No; publishes already-authorized artifacts |

No runner may use `add-held`, `sub-held`, or other Sew custody operations as
part of release attestation. Build evidence is not protocol-state authority.

## Lifecycle model

The checked-in policy at:

```text
resources/prf/release/trust-policy.edn
```

is a structurally valid **template**, not production authorization. Its status
is deliberately:

```clojure
:policy/status :unconfigured
```

Policy lifecycle semantics:

| Status | Meaning |
|---|---|
| `:unconfigured` | Valid template/configuration shape. Never authorizes a release. |
| `:active` | May authorize a new release when signatures, eligible keys, distribution rule, and threshold all verify. |
| `:retired` | May remain useful for historical evidence review. Never authorizes a new release. |

A cryptographically valid signature is not, by itself, release authorization.
New-release authorization requires an active policy.

## Prerequisites

All runners need:

- a supported JDK and Clojure CLI;
- Babashka (`bb`) for wrapper tasks;
- `bash`, `sha256sum`, and `java`;
- a checkout or release directory appropriate to their role.

Build runners additionally need dependency resolution available before the
build begins. Signing runners must have access only to their approved private
key material, not a general CI secret store.

## 1. Build runner setup

Use a clean checkout without researcher-local source paths or release keys.

```sh
# Confirm the command registry and wrapper parity first.
clojure -M:cli/sew commands validate

# Build both distributions, run the packaged-JAR acceptance gate once,
# and emit unsigned integrity bundles plus hashed smoke transcripts.
bb build:attest
```

Expected outputs:

```text
target/prf.jar
target/prf-runner-sew-0.1.0-uber.jar
target/default-build-attestation-prf.edn
target/default-build-attestation-prf.edn.smoke.log
target/default-build-attestation-sew.edn
target/default-build-attestation-sew.edn.smoke.log
```

`bb build:attest` is intentionally not a release authorization step. It does
not require a private key and cannot make a bundle publishable.

### Build runner handoff

Transfer the JAR, its attestation bundle, and the adjacent smoke log together.
Do not alter filenames after bundle creation: the attestation records the JAR
and smoke-log path/hash.

## 2. Verification runner setup

A verification runner may operate from a minimal release directory. It does
not need a source checkout when the released verifier/JAR distribution and
policy are available; repository checkout examples below are supplied for
current operational convenience.

Place one distribution's material in a single directory, for example:

```text
release/prf/
  prf.jar
  default-build-attestation-prf.edn
  default-build-attestation-prf.edn.smoke.log
  trust-policy.edn
```

Verify a signed bundle using an explicit policy:

```sh
bb build:attest:verify \
  release/prf/default-build-attestation-prf.edn \
  release/prf \
  prf \
  release/prf/trust-policy.edn
```

The command fails unless all of these are true:

- the bundle root, definition, JAR bytes, and smoke transcript match;
- the release payload binds that exact build-bundle root and artifact;
- the policy is structurally valid and `:active`;
- signatures are cryptographically valid;
- signer keys are trusted and eligible;
- the distribution-specific distinct-signer threshold is met.

### Verifying the shipped template

The repository template is expected to **fail authorization**. This is correct:

```sh
bb build:attest:verify \
  target/default-build-attestation-prf.edn \
  target \
  prf \
  resources/prf/release/trust-policy.edn
```

Do not treat this failure as a build defect. It proves the `:unconfigured`
template cannot accidentally authorize a release.

## 3. Signing runner setup

The signing runner is a controlled operator environment, separate from normal
CI. It receives a reviewed integrity bundle and release metadata, then uses a
locally held Ed25519 private key.

### Policy enrollment requirements

Before signing a production release, an operator-owned policy must be created
from the template with all of the following supplied through the organization’s
key-governance process:

```clojure
{:schema-version "prf-release-trust-policy.v1"
 :policy-id :release/<approved-policy-id>
 :policy/status :active
 :policy-version <positive-integer>
 :trusted-keys
 [{:key-id "<stable-operator-key-id>"
   :status :active
   ;; Exactly 64 lowercase hexadecimal characters: raw Ed25519 public key.
   :public-key "<operator-enrolled-key-material>"}]
 :requirements
 {:distribution
  {:prf {:minimum-valid-signatures <approved-prf-threshold>}
   :sew {:minimum-valid-signatures <approved-sew-threshold>}}}
 :canonicalization
 {:payload-profile "prf-release-attestation-payload.v1"}}
```

Do not place private keys in this file. Do not use the checked-in
`:release/unconfigured` policy for signing.

### Release metadata

Prepare reviewed EDN release metadata. The exact keys are release-process
specific, but it should identify the release without redefining bundle/JAR
identity, which the signing payload derives from the bundle:

```clojure
{:release/id "2026.08.11"
 :release/channel :stable
 :release/notes-ref "sha256:<approved-notes-content-hash>"}
```

### Signing command

```sh
bb build:attest:sign \
  target/default-build-attestation-prf.edn \
  prf \
  <operator-key-id> \
  <local-private-key-path> \
  '<release-metadata-edn>'
```

The command writes the release payload and signature into the bundle. It does
not print or persist the private key. Immediately verify with the approved
active policy:

```sh
bb build:attest:verify \
  target/default-build-attestation-prf.edn \
  target \
  prf \
  /secure/operator-policy/trust-policy.edn
```

For multi-signature policy, each approved signer must attach their signature
through the controlled signing procedure. The current signing wrapper replaces
the bundle authorization set, so multi-signer coordination must preserve and
append prior signatures through a reviewed operator workflow before publication.
Do not assume repeated invocation automatically accumulates signatures.

## 4. Publication checklist

Publish together:

- the JAR;
- the matching default-build attestation bundle;
- matching smoke transcript;
- active trust policy or immutable policy reference;
- release metadata and signatures embedded in the bundle;
- verifier instructions and expected distribution name.

Before publication, an independent verification runner must obtain:

```clojure
{:classification :release-authorized-build
 :verified? true}
```

Do not publish a bundle classified only as:

```clojure
:integrity-verified-build
```

when the channel requires release authorization.

## Key lifecycle and incident response

Operators own key lifecycle. The framework enforces statuses but does not pick
keys or thresholds.

- Add a new key as `:active` before rotation when policy threshold rules require
  overlap.
- Mark a superseded key `:retired` when it should no longer authorize a new
  release.
- Mark a compromised key `:revoked`; it is ineligible for new authorization.
- Retire the entire policy when it must not approve future releases.
- Preserve the prior policy and signed artifacts for historical verification.

## Runner troubleshooting

### The template cannot authorize

Expected. `:policy/status :unconfigured` is deliberately non-authorizing even
if someone adds syntactically valid keys to it.

### Signature verifies but release verification fails

Check, in order:

1. policy status is `:active`;
2. signer key is listed, `:active`, and correctly encoded;
3. policy has a requirement for the requested distribution;
4. unique eligible signatures satisfy that distribution’s threshold;
5. JAR and smoke-log files have not moved or changed;
6. payload references the same immutable bundle root.

### Non-interactive shell/PTY warning

Some interactive sandbox/PTY environments have emitted:

```text
/bin/sh: 1: Cannot set tty process group (No such process)
```

after Clojure reports passing tests. Treat Clojure test summaries and outer
process exit status separately. Release CI should use a normal non-interactive
shell and must require a clean zero exit code from the actual build/sign/verify
tasks.
