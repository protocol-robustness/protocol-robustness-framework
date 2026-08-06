# Out-of-process artifact publisher

The artifact publisher makes the **finalization and signing** of an evidence
bundle an independent authority, mirroring the out-of-process sensitivity
sentinel (`OUT_OF_PROCESS_SENSITIVITY_SENTINEL.md`). This document describes the
publisher's assurance model, the all-or-nothing set guarantee it enforces, and
the key-isolation requirements that determine how strong the guarantee actually
is.

## Why out-of-process

If the same JVM that runs a scenario test also finalizes and signs the evidence
bundle, a defective or compromised runner can write a clean-looking but wrong
bundle and stamp a valid-looking signature on it. The in-process publisher is
not an *independent* publisher: it shares the caller's code, memory, and key
material.

The out-of-process publisher (`prf publish check`) moves the verify-and-sign
decision into a separate process the caller must consult before promoting any
staged artifact set. Even a fully compromised caller cannot fabricate a valid
publish certificate because the signing key lives only in the authority
process — and the authority re-reads and re-hashes every artifact itself.

## Components

| Component | File | Role |
| --- | --- | --- |
| Generic signed-decision primitive | `src/resolver_sim/signed_external_decision.clj` | Domain-separated envelope hashing, Ed25519 sign/verify, trust-role + key-status checks. Domain-neutral. |
| All-or-nothing set check | `src/resolver_sim/publish/manifest.clj` | sha256 per artifact, manifest commitment, fail-closed set verification. |
| Contract | `src/resolver_sim/publish/contract.clj` | wire request, publish-certificate, response shapes; manifest commitment; policy hash. |
| Authority | `src/resolver_sim/commands/publish.clj` | `prf publish check`: reads one EDN request, re-verifies the set from disk, signs the certificate. |
| Client | `src/resolver_sim/publish_client.clj` | spawns the authority via `ProcessBuilder`, verifies the returned certificate, atomically promotes the staged set. |
| Verification | `src/resolver_sim/publish/verify.clj` | `verify-publication`: independently checks a published directory's signature and manifest commitment. |

## All-or-nothing set guarantee

The authority does **not** trust the caller's hash claims. It:

1. Verifies `:request/hash` and the committed policy hash.
2. Recomputes the manifest commitment and cross-checks `:publish/declared-commit`.
3. Reads **every declared artifact** from disk, recomputes its sha256, and fails
   the whole set if any artifact is missing or modified.
4. Signs a certificate that binds the run identity, the stage root, the complete
   manifest commitment, and the required subset, under the dedicated domain tag
   `PRF_ARTIFACT_PUBLISH_DECISION_V1`.

The client then promotes the set all-or-nothing: it copies every verified file
into a fresh sibling directory (same filesystem), writes the signed certificate
as `publication.json`, and atomically renames the whole directory into place.
The target is never observed half-populated, and a rejected set never touches
the target.

Caller-supplied values are commitments to cross-check, never trusted inputs.

## Assurance levels

Process separation alone does not mean the caller cannot use the signing key.
The certificate reports an explicit `:publish/authority-assurance` value:

- `:process-isolated` — the decision runs in a separate process, but under the
  same OS principal as the caller. This gives fresh recomputation, failure
  isolation, and auditable signed certificates, **but** a caller that can read
  the private key file could still sign directly. This is the honest default.
- `:principal-isolated` — the signing key is inaccessible to the caller's OS
  principal (separate OS user with restricted key permissions, or a container /
  sandbox with a distinct identity).
- `:hardware-backed` — the key lives in an HSM or OS key store.

Do not describe a `:process-isolated` deployment as an "independent authority"
that the caller cannot influence. That claim requires `:principal-isolated` or
higher.

## Key isolation (operations)

- Use a **dedicated** publisher key pair with `:key/role :artifact-publisher`.
  Do not reuse or derive it from sentinel keys or release-attestation keys — the
  roles and compromise domains differ.
- The signer (authority) receives private-key configuration. Verifiers (the
  client and `verify-publication`) receive **only** a public trust policy.
- Keep the key path out of argv. The authority reads it from `PRF_PUBLISH_KEY`;
  the subprocess client sets it in the process environment rather than on the
  command line.
- Restrict key-file permissions so the artifact-handling process cannot read it
  (`chmod 400`, separate OS user) before claiming `:principal-isolated`.

Generate the Ed25519 keypair offline (same procedure as the sentinel) and
publish the raw 32-byte public key hex in the trust policy used by verifiers.

## Invocation

The client invokes the authority with an explicit argv vector (never a shell
string):

```
["/path/to/java" "-jar" "/path/to/prf.jar" "publish" "check"]
```

Precedence for resolving the command: injected runner (tests) → explicit config
→ `PRF_PUBLISH_JAR` (+ optional `PRF_JAVA`) → self-jar discovery only when the
classpath is exactly one unambiguous prf jar → else fail closed with
`:publish-command-unavailable`.

## Verification guarantees

`verify-publication` requires, for a published directory, that `publication.json`
exists, that the certificate signature verifies under a trusted
`:artifact-publisher` key, and that the certificate's manifest commitment binds
the exact bytes currently in the directory (every file re-hashed,
`publication.json` excluded). Any in-process or unsigned bundle — or any bundle
modified after promotion — fails verification. Any failure is fail-closed.

## Deployment status

The core publisher slice — authority, client, atomic promotion, and independent
verification — is implemented and tested (`test/resolver_sim/publish/`,
`test/resolver_sim/commands/publish_test.clj`).

The publisher signature is now **wired into the Python evidence acceptance bar**
as stage 3 (`scripts/validate/validate_artifact_registry.py` with
`--publisher-manifest` / `--publisher-policy`, or standalone
`scripts/validate/verify_publisher_commitment.py`). The signer is
`scripts/evidence/sign_publisher_commitment.py`, the policy schema is
`config/publisher/publisher-policy.schema.json`, and the gate is tested in
`scripts/evidence/test_publisher_commitment.py`.

## Acceptance order

A test-artifact bundle is **accepted** only by passing, in order:

1. **Schema validation** — exact versions and closed shapes; required fields and
   basic encodings. Aborts before any filesystem or cryptographic work.
2. **Content integrity and cross-file binding** — the run manifest and every
   artifact are re-hashed from disk; `run_id`, paths, required chain IDs
   (`test-run`, `test-summary`, `claimable-classification`), and compatibility
   fields are validated, including `claimable-classification.v2` integrity.
3. **Publisher commitment and signature** — the signed commitment is
   reconstructed from the already-validated data, the digest is recomputed, the
   signature is verified, and the signing key is checked for authorisation under
   the declared publisher policy.
4. **Claim/capability admission** — Clojure determines which claims may actually
   be emitted; publisher validity is a prerequisite, never evidence that a
   particular claim class is correct.

This preserves the boundary:

```
schema-valid → content-integrity-valid → publisher-authentic
             → capability-exercised → claim-permitted
```

Acceptance therefore proves that **the artifact files present on disk match the
accepted registry, the registry is bound to the declared run manifest, and the
resulting publisher commitment was signed by a key authorised under the
declared publisher policy.**

It does **not** prove, and must not be described as proving, that:

- the publisher generated the artifacts;
- the publisher independently reproduced the run;
- model assumptions are correct;
- economic conclusions are sound;
- every artifact supports every claim;
- signature validity upgrades procedural evidence into model correctness.

Those stronger meanings would be represented as separate publisher roles or
attestations, not overloaded into the base signature.

## Scope decision

The publisher signature is part of **artifact provenance and release
authenticity**, not part of model verification. That is why it lives in the
Python acceptance bar while the Clojure claim/capability boundary stays intact:
a publisher-authentic bundle is a *prerequisite* for claims, but the claim layer
independently decides which claims are permitted and exercised.

## What the commitment commits to

The verifier never signs a loosely defined file or trusts a digest copied from
the signature envelope. It reconstructs the preimage from validated data and
re-hashes the run manifest and every artifact from disk:

- domain separator `prf.test-artifacts.publisher-manifest.v1`;
- `schema_version`, `contract_version`, `run_id`;
- run-manifest path and **recomputed** SHA-256;
- a deterministic, ordered projection of every artifact (id, path, **recomputed**
  SHA-256, importance);
- the required-chain identifiers (`test-run`, `test-summary`,
  `claimable-classification`);
- `publisher-manifest` version;
- publisher-policy identifier and recomputed policy hash.

`digest = sha256(canonical_json(preimage))` with sorted keys and compact
separators. A golden commitment-preimage fixture
(`scripts/evidence/fixtures/publisher-commitment-golden-v1.json`) locks the
serialization so Python, Clojure, and any future verifier cannot silently adopt
different ordering or serialization rules. Binding `run_id` and using domain
separation prevents a valid signature from being transplanted between runs or
reused for another artifact type.

## Cryptographic validity vs. publisher authority

These are kept separate so failures are diagnosable without weakening the
fail-closed result. The verifier distinguishes at least:

| Reason | Meaning |
| --- | --- |
| `signature-invalid` | bad hex, wrong length, or Ed25519 verify failed |
| `unknown-signature-algorithm` | algorithm is not `ed25519` |
| `unknown-publisher-key` | `key_id` not present in the applied policy |
| `publisher-not-authorised` | key revoked, or `publisher_id` mismatch |
| `publisher-policy-mismatch` | envelope policy id/hash ≠ applied policy |
| `publisher-commitment-mismatch` | claimed digest ≠ reconstructed digest |
| `publisher-signature-valid` | accepted |

Additional diagnosable rejections: `envelope-malformed`,
`unsupported-publisher-manifest-version`, `domain-mismatch`,
`unknown-envelope-field`, `artifact-unreadable`.

A signature can be mathematically valid yet made by an unknown, revoked, or
unauthorised key — that is a different, later reason than
`signature-invalid`, and the gate fails closed on all of them.
