# Allocation-assurance-certificate content addressing (B4)

This document records the content-addressing and attestation design for
`allocation-assurance-certificate.v1` (B4). The certificate is no longer a
self-attesting JSON blob: it is a deterministic, content-addressed document
that can optionally carry an issuer attestation, and it has a fail-closed
verification entry point.

## Motivation

Previously `compose-certificate` returned a plain map and `issue-certificate`
printed it as JSON. The document had:

- no self-hash (nothing bound the full certificate content),
- no signature (no issuer attestation), and
- no recompute/verify entry point.

Only fragments were committed: `certificate-assertions-digest` (computed in the
kernel) and the `:proof` `:public-values-hash` / `:proof-hash` fields.

## Design

### 1. Self-hash (`:certificate/hash`)

```
certificate-hash = sha256:hex(domain-hash(
    "ALLOCATION_ASSURANCE_CERTIFICATE_V1",
    canonical-bytes(unsigned-certificate-projection)))
```

- `unsigned-certificate-projection` = the certificate minus `:certificate/hash`
  and `:certificate/signature` (self-referential exclusion, same principle as
  attempt-receipts and transaction-orderings).
- `compose-certificate` attaches `:certificate/hash` to every emitted
  certificate. Because composition is a pure function of the kernel result
  (+ optional round-state token and native evidence), a verifier recomposing
  from the same committed inputs reproduces the identical hash. This is what
  makes the certificate a *document* rather than a display artifact.
- The signature is an attestation AFTER commit and is excluded from the
  identity, so signing never changes `:certificate/hash`.

### 2. Optional attestation (`:certificate/signature`)

`sign-certificate` attaches an Ed25519 block over the exact unsigned-projection
bytes:

```
{:schema-version "prf-signed-external-decision-signature.v1"
 :key-id <issuer identity>
 :algorithm :ed25519
 :signed-hash <the certificate-hash>
 :signature-encoding :hex
 :signature-bytes <hex>}
```

- `:signed-hash` commits the certificate identity, so a verifier can confirm
  the attestation names the exact document.
- The issuer `:key-id` is carried in-band so a trust policy can resolve it to a
  public key (see `verify-certificate`).

### 3. Verification (`verify-certificate`)

Returns `{:valid? bool :signature-valid? bool|nil :issues [...] :reason kw|nil}`.

- **Integrity:** the certificate must be a map of schema
  `allocation-assurance-certificate.v1` whose `:certificate/hash` recomputes
  from its own unsigned projection.
- **Attestation:**
  - No signature → `:signature-valid? nil`; the self-hash is the document
    identity and the certificate verifies on integrity alone.
  - Signature present → must use the signed-external-decision schema,
    `:ed25519`, commit the recomputed self-hash, and (when a trust-policy is
    supplied) resolve `:key-id` to an active key of the expected role and
    verify Ed25519 over the projection bytes.
  - A present-but-invalid signature fails the WHOLE certificate — an invalid
    attestation is worse than none.

Trust policy shape (the `resolver-sim.signed-external-decision` shape):

```
{:trusted-keys [{:key/id <kw|str> :key/public <hex> :key/role kw :key/status kw}]}
```

### 4. CLI

`allocation issue-certificate` always emits `:certificate/hash`.
`--key PATH --key-id ID` additionally attaches the issuer attestation
(`--key-id` is required when `--key` is supplied).

## Failure codes

| Code | Meaning |
|------|---------|
| `:certificate/not-a-map` | not a certificate map |
| `:certificate/schema-mismatch` | wrong `:schema-version` |
| `:certificate/hash-mismatch` | `:certificate/hash` does not recompute |
| `:certificate/signature-schema-mismatch` | bad signature schema |
| `:certificate/unsupported-signature-algorithm` | not `:ed25519` |
| `:certificate/signature-hash-mismatch` | `:signed-hash` ≠ recomputed self-hash |
| `:certificate/missing-key-id` | signature lacks `:key-id` |
| `:certificate/untrusted-key` | `:key-id` not in trust policy |
| `:certificate/wrong-key-role` | key role ≠ expected role |
| `:certificate/inactive-key` | key status ≠ `:active` |
| `:certificate/invalid-signature` | Ed25519 verification failed |

## Scope and non-goals

- The certificate hash binds the certificate document; it does not replace the
  kernel's `certificate-assertions-digest` (which commits the assertion/outcome
  public values into the allocation evidence path).
- `:certificate/hash` is not added to the canonical self-hash strip set; the
  certificate uses its own domain tag and is not re-hashed through the
  intent-artifact path.
- Signing is optional: an unsigned certificate is a valid, verifiable document
  (integrity via self-hash); the signature supplies issuer attestation.
