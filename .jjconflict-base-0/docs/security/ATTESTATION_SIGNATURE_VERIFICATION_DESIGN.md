# Attestation Signature Verification Design

## Status

**Design proposal.** This document defines the contract required before changing
attestation-bundle signature checks from presence-only to cryptographic
verification.

## Goal

A bundle whose verification profile requires signatures is valid only when every
included attestation has a valid signature over the canonical attestation
payload, its signing key is trusted for the named attestor, and the bundled
attestor-registry snapshot is independently trusted by the verifier.

Bundle and registry hashes establish integrity only. They do **not** establish
trust: an attacker can create a self-consistent registry containing an attacker
key, sign a forged attestation, and recompute every bundle hash.

## Non-goals

- Replacing the existing evidence-chain signature format.
- Trusting public-key file paths embedded in an attestation.
- Supporting arbitrary signature algorithms in v1.
- Retroactively treating legacy unsigned bundles as cryptographically verified.

## Existing building blocks

| Existing component | Reused role |
|---|---|
| `resolver-sim.evidence.attestation/signing-payload` | Exact canonical map that is signed and verified. |
| `resolver-sim.hash.canonical` | Canonical encoding / domain separation. |
| `resolver-sim.benchmark.signing/verify-signature` | Existing Ed25519 verification primitive; adapt it to verify bytes from a registry key instead of a mutable path. |
| `resolver-sim.definitions.passive-registries/attestor-registry` | Attestor identity, verification policy, active key, and key history. |
| `attestation-bundle` root hash | Binds a registry snapshot to the bundle after its hash is independently checked. |

## Signature envelope v2

New attestations that claim cryptographic verification use this exact shape:

```clojure
{:attestation/signature
 {:signature/version "attestation-signature.v1"
  :algorithm :ed25519
  :key-id "ci-validation-2026-01"
  :signature-encoding :hex
  :signature-bytes "<128 lowercase hex characters>"
  :payload-hash "sha256:<64 lowercase hex characters>"}}
```

The legacy `:public-key-id` field may be accepted as an input alias during
migration, but persisted v2 signatures use `:key-id` only.

## Canonical payload and signed bytes

1. Build the payload with:

   ```clojure
   (attestation/signing-payload attestation)
   ```

2. Canonically encode that map using the `:attestation-record` hash intent.
3. Compute the domain-separated payload hash.
4. Sign the UTF-8 bytes of the resulting payload hash string.

Verification recomputes the payload and payload hash from the parsed
attestation; it does **not** trust `:payload-hash` from the signature envelope.
The optional stored payload hash is a diagnostic assertion and must match the
recomputed value when present.

The signature and self identifiers remain excluded from the payload. Therefore
a signature binds exactly the content already used by attestation identity and
cannot create a circular dependency. `:payload-hash` is required. Verification
recomputes it, requires exact equality, then verifies UTF-8 bytes of exactly
`sha256:` followed by 64 lowercase hexadecimal characters—not the supplied
value.

## Trusted key resolution

### Registry entry contract

A public-key attestor names one active key and stores all key material in one
non-duplicated collection:

```clojure
{:id :ci-validation
 :status :active
 :verification {:type :public-key
                :algorithm :ed25519
                :active-key-id "ci-validation-2026-01"}
 :keys [{:key-id "ci-validation-2025-01"
         :algorithm :ed25519
         :public-key-encoding :hex
         :public-key "<64 lowercase hex characters>"
         :status :retired
         :valid-from "2025-01-01T00:00:00Z"
         :valid-until "2026-01-01T00:00:00Z"}
        {:key-id "ci-validation-2026-01"
         :algorithm :ed25519
         :public-key-encoding :hex
         :public-key "<64 lowercase hex characters>"
         :status :active
         :valid-from "2026-01-01T00:00:00Z"}
        {:key-id "compromised-key"
         :algorithm :ed25519
         :public-key-encoding :hex
         :public-key "..."
         :status :revoked
         :revoked-at "2026-04-12T10:30:00Z"
         :revocation-reason :suspected-compromise}]}
```

The current `ci-validation-placeholder` key is not eligible for cryptographic
verification. No production bundle may claim `:signature? true` while its
attestor registry contains a placeholder key.

### External registry trust anchor

The verifier receives trust policy independently of the bundle:

```clojure
(verify-attestation-bundle
 bundle
 {:trusted-attestor-registry-hashes #{"sha256:<approved-registry-hash>"}})
```

or a directly supplied trusted registry:

```clojure
{:trusted-attestor-registry trusted-registry}
```

Required sequence: (1) recompute and verify the bundled snapshot against its
declared bundle hash; (2) verify that hash or canonical registry equals external
verifier trust policy; (3) only then resolve attestor keys. A cryptographically
valid signature from an untrusted snapshot is never `:fully-verified`; it is
reported as `:signature-valid-untrusted-registry`.

### Resolution algorithm

For each attestation after the bundle-level registry check:

1. Resolve `:attestation/attestor-id` exactly (no keyword/string coercion).
2. Resolve signature `:key-id` from the attestor's unique `:keys` collection.
3. Require exact supported version, `:ed25519`, `:hex`, lowercase hex, a
   32-byte decoded public key, and a 64-byte decoded signature.
4. Require `:attestation/signed-at` to be a valid RFC 3339 UTC instant.
5. Require the half-open validity interval: `valid-from <= signed-at < valid-until`.
6. Apply verifier-side historical/revocation policy.
7. Verify Ed25519 signature using only the trusted registry public key.

The verifier must never read a public key from a path in the attestation,
bundle, environment, or working tree.

## Bundle registry snapshot verification

Before using the included attestor registry for trust resolution:

1. Resolve its path through the bundle containment resolver.
2. Read and canonicalize the registry snapshot.
3. Recompute the registry hash using `:registry` intent.
4. Require it to match `[:bundle/registries :attestors :registry/hash]`.
5. Require that hash or canonical snapshot to match external verifier trust policy.
6. Validate registry entry shape: unique key IDs, exactly one matching key,
   no placeholder/zero material, and no ambiguous key definitions.

Registry-hash/trust failures are bundle-layer results, e.g.
`{:check :attestor-registry :reason :registry-hash-mismatch}`, not signature
function results. A self-consistent but externally untrusted snapshot fails.

## Meaning of `:attestation/signed-at`

A signature proves only that the signer asserted this value and that the
resolved key's declared validity interval contains it. It does **not** prove
that the signature was physically produced at that time: an old or compromised
key holder can backdate a new signature. Strong historical claims require an
independent bound, such as evidence-chain ordering/timestamp, trusted bundle
generation time, transparency-log inclusion time, or externally timestamped
run metadata.

Retirement and revocation are distinct. `:retired` stops new issuance while
historical verification may be permitted by verifier policy. `:revoked` signals
withdrawn trust; whether signatures before `:revoked-at` remain acceptable is
also verifier-side policy, never bundle policy.

## Verification policy and statuses

`[:bundle/verification-profile :signature?]` becomes a policy, not a display
hint.

| Profile / attestation state | Signature check | Bundle effect |
|---|---|---|
| `:signature? true`, missing signature | `:fail` | bundle invalid |
| `:signature? true`, malformed or unknown key | `:fail` | bundle invalid |
| `:signature? true`, invalid crypto verification | `:fail` | bundle invalid |
| `:signature? true`, all signatures valid | `:pass` | eligible for `:fully-verified` |
| `:signature? false`, missing signature | `:warning` | may be `:hash-linked` / `:partially-verified`, never `:fully-verified` |
| Legacy bundle lacking the profile field | `:warning :legacy-unsigned-policy` | valid only as `:hash-linked`; never cryptographically verified |

`verify-attestation-bundle` must set `:valid? false` whenever a required
signature check fails. `:fully-verified` additionally requires no warnings,
verified object hashes, trusted registry snapshots, and approved sensitivity
status.

## API design

New namespace:

```clojure
resolver-sim.evidence.attestation-signature
```

Public functions:

```clojure
(signing-payload-hash attestation)
(verify-attestation-signature attestation trusted-attestor-registry)
(validate-attestor-key-entry key-entry)
```

`verify-attestation-signature` takes verifier-side historical policy and returns
structured data, never a bare boolean:

```clojure
(verify-attestation-signature
 attestation
 trusted-attestor-registry
 {:allow-retired-attestors? false
  :allow-retired-keys? true
  :revoked-key-policy :reject-all})
```

The bundle cannot loosen this policy.


```clojure
{:valid? true
 :attestor-id :ci-validation
 :key-id "ci-validation-2026-01"
 :algorithm :ed25519
 :payload-hash "sha256:..."}
```

Failure returns:

```clojure
{:valid? false
 :reason :unknown-key | :key-not-valid-at-signing-time | :invalid-signature |
         :unsupported-algorithm | :malformed-signature | :revoked-key
 :attestor-id ...
 :key-id ...}
```

`attestation-bundle/check-attestation-signatures` calls this API after registry
snapshot verification.

## Migration plan

### Phase 0 — schema and test fixtures

- Add `attestation-signature.v1` envelope validation.
- Add no production key material yet.
- Preserve acceptance of old `:public-key-id` as an input-only alias.

### Phase 1 — trust registry activation

- Replace `ci-validation-placeholder` with real versioned public key material
  managed outside source history where required by operational policy.
- Add key validity windows and registry validation.
- Emit dual fields (`:key-id` and legacy alias) only if compatibility requires.

### Phase 2 — verifier in observe mode

- Verify signatures when present.
- Emit a structured warning for invalid/unknown signatures.
- Do not yet change legacy bundle validity.
- Measure existing unsigned and malformed bundle population.

### Phase 3 — enforce profile semantics

- Set `:signature? true` only for bundles emitted by sign-capable producers.
- Make missing, invalid, unknown, or untrusted signatures fatal for those
  bundles.
- Legacy bundles remain readable but receive `:hash-linked`, never
  `:fully-verified`.

### Phase 4 — remove legacy alias

- Stop emitting and then stop accepting `:public-key-id`.
- Require `:signature/version`, `:key-id`, encoding, and payload-hash.

## Required tests

1. Valid Ed25519 signature over canonical payload and externally trusted registry passes.
2. A self-consistent attacker registry with a valid attacker signature fails external registry trust.
3. Duplicate key IDs with conflicting material fail registry validation.
4. Malformed public-key/signature lengths fail before crypto verification.
5. Missing or malformed `:signed-at` fails for signature v1.
6. Boundary behavior proves `valid-until` is exclusive.
7. Retired and revoked keys follow distinct verifier policies.
8. Bundle-provided policy cannot enable retired/revoked key acceptance.
9. Valid Ed25519 signature over canonical payload passes.
2. Changing any signed attestation field fails verification.
3. Changing only signature metadata fails when payload-hash is inconsistent.
4. Invalid signature bytes fail.
5. Unknown attestor and unknown key fail.
6. Retired key outside its validity window fails.
7. Retired key within its historical validity window passes when policy allows.
8. Placeholder key cannot produce a cryptographically verified status.
9. Tampered bundle registry snapshot/hash fails before key use.
10. Embedded public-key paths are ignored/rejected.
11. Required-signature bundle with an unsigned attestation is invalid.
12. Legacy unsigned bundle remains readable but is not `:fully-verified`.

## Security properties

After enforcement, an attacker cannot obtain a passing cryptographic
attestation by:

- adding a signature-shaped map;
- swapping an embedded public key or key path;
- selecting an unregistered key ID;
- using a retired key outside its validity window;
- altering signed attestation content;
- altering the bundle's attestor registry snapshot without breaking its hash.
