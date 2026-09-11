# Attestation Signature Verification Implementation Plan

## Objective

Implement cryptographic attestation verification so a bundle is eligible for
`:fully-verified` only when its attestor-registry snapshot is externally trusted
and every required attestation signature verifies against a trusted,
time-policy-valid Ed25519 key.

This plan implements `ATTESTATION_SIGNATURE_VERIFICATION_DESIGN.md`. It does
not alter evidence-chain signing or trust key paths embedded in attestations.

## Compatibility decisions

| Area | Decision |
|---|---|
| Existing `:public-key-id` | Read-only compatibility alias during migration; new emitters write `:key-id`. |
| Existing unsigned bundles | Readable and may remain `:hash-linked`; never `:fully-verified`. |
| `verify-attestation-bundle` | Add a two-argument arity for verifier-side trust policy. Preserve one-argument structural verification as legacy/unsigned mode. |
| Existing attestor registry | Keep current shape readable, but do not permit placeholder keys for crypto verification. Migrate production-capable entries to `:keys`. |
| Bundle policy | Cannot select trusted registry hashes or relax historical/revocation policy. |

## Work packages

### WP1 — Signature envelope and canonical payload helpers

**Files**

- Add `src/resolver_sim/evidence/attestation_signature.clj`
- Update `src/resolver_sim/evidence/attestation.clj`
- Add `test/resolver_sim/evidence/attestation_signature_test.clj`

**Implementation**

1. Move/reuse canonical payload construction through the existing public:

   ```clojure
   (attestation/signing-payload attestation)
   ```

2. Add `signing-payload-hash`, returning exactly:

   ```text
   sha256:<64 lowercase hexadecimal characters>
   ```

3. Add strict `attestation-signature.v1` envelope validation:

   ```clojure
   {:signature/version "attestation-signature.v1"
    :algorithm :ed25519
    :key-id <non-empty-string>
    :signature-encoding :hex
    :signature-bytes <128-lowercase-hex>
    :payload-hash <sha256-prefixed-64-lowercase-hex>}
   ```

4. Reject unsupported/unknown envelope fields for v1. Do not silently accept
   unrecognized extension keys.

5. Implement byte-level validation before crypto:
   - key hex: exactly 64 lowercase hex characters / 32 decoded bytes;
   - signature hex: exactly 128 lowercase hex characters / 64 decoded bytes;
   - reject zero/placeholder public key material;
   - accept only `:ed25519` and `:hex`.

**Exit criteria**

- No crypto primitive is called for malformed envelope/key material.
- Changing a signed payload field changes recomputed payload hash.
- Changing `:payload-hash` causes envelope validation failure.

---

### WP2 — Attestor key registry schema and validation

**Files**

- Update `src/resolver_sim/definitions/passive_registries.clj`
- Add/extend `test/resolver_sim/definitions/passive_registries_test.clj`
- Update registry documentation/spec references if present

**Implementation**

1. Introduce the canonical public-key attestor structure:

   ```clojure
   {:id :ci-validation
    :status :active
    :verification {:type :public-key
                   :algorithm :ed25519
                   :active-key-id "ci-validation-2026-01"}
    :keys [{:key-id ... :status :active|:retired|:revoked ...}]}
   ```

2. Extend `validate-attestor-registry-entries` to require for public-key
   attestors:
   - unique `:key-id` values;
   - one and only one `:active-key-id` match;
   - valid key material and exact algorithm/encoding;
   - RFC 3339 UTC `:valid-from`, optional `:valid-until`;
   - `valid-from < valid-until` when both exist;
   - revoked key requires `:revoked-at` and `:revocation-reason`;
   - no placeholder material.

3. Do not allow both duplicated active key material in `:verification` and
   `:keys` for new entries. Legacy fields may be read only by a migration
   adapter, with conflict treated as invalid.

4. Do not provision a real production key in source code. Introduce a test
   fixture registry with deterministic test-only Ed25519 keys.

**Exit criteria**

- Conflicting duplicate key IDs fail registry validation.
- A placeholder CI key cannot be used for cryptographic verification.
- Registry validation catches invalid key lengths before verifier execution.

---

### WP3 — Trusted registry snapshot validation

**Files**

- Update `src/resolver_sim/evidence/attestation_bundle.clj`
- Add `src/resolver_sim/evidence/attestation_registry_trust.clj` if keeping
  bundle I/O concerns separate
- Extend `test/resolver_sim/evidence/attestation_bundle_test.clj`

**Implementation**

1. Add verifier-side options to the bundle API:

   ```clojure
   (verify-attestation-bundle bundle
     {:trusted-attestor-registry-hashes #{"sha256:..."}
      ;; or
      :trusted-attestor-registry trusted-registry
      :allow-retired-attestors? false
      :allow-retired-keys? true
      :revoked-key-policy :reject-all})
   ```

2. Add bundle check `:attestor-registry-trusted` before signature checks:
   - containment-resolve the bundled attestor registry path;
   - parse and canonicalize it;
   - recompute hash using the `:registry` intent;
   - compare to `[:bundle/registries :attestors :registry/hash]`;
   - compare to externally supplied trusted hash set or directly supplied
     trusted registry;
   - validate the registry schema from WP2.

3. Keep failure ownership at the bundle layer:

   ```clojure
   {:check/id :attestor-registry-trusted
    :check/status :fail
    :reason :registry-hash-mismatch | :untrusted-registry |
            :malformed-registry | :registry-path-invalid}
   ```

4. If a signature is mathematically valid but the bundled registry is not
   externally trusted, report an explicit non-trusted status and never elevate
   bundle status to `:fully-verified`.

**Exit criteria**

- A self-consistent attacker registry and valid attacker signature fails.
- Bundle registry hash mismatch fails before attestation key resolution.
- A bundle cannot supply or override verifier trust policy.

---

### WP4 — Cryptographic signature verifier

**Files**

- Implement `src/resolver_sim/evidence/attestation_signature.clj`
- Update `src/resolver_sim/evidence/attestation_bundle.clj`
- Reuse/adapt Ed25519 primitives from `resolver-sim.benchmark.signing`
- Extend `test/resolver_sim/evidence/attestation_signature_test.clj`

**Implementation**

1. Implement:

   ```clojure
   (verify-attestation-signature
    attestation
    trusted-attestor-registry
    verifier-policy)
   ```

2. Resolve attestor ID exactly; do not coerce keyword/string IDs.
3. Resolve only one key by envelope `:key-id`.
4. Parse `:attestation/signed-at` as RFC 3339 UTC.
5. Enforce half-open validity windows:

   ```text
   valid-from <= signed-at < valid-until
   ```

6. Apply policy:
   - default `:allow-retired-attestors? false`;
   - default `:allow-retired-keys? false` or explicitly decide this before
     coding; recommended conservative default is `false`;
   - default `:revoked-key-policy :reject-all`.

7. Recompute payload hash and require equality with envelope `:payload-hash`.
8. Verify Ed25519 over UTF-8 bytes of the recomputed hash string.
9. Return structured reasons, e.g. `:unknown-key`, `:revoked-key`,
   `:invalid-signature`, `:key-not-valid-at-signing-time`.

**Exit criteria**

- Valid test signature verifies with trusted test registry.
- Tampering with payload, attestor ID, key ID, signature bytes, or payload hash
  fails.
- The crypto implementation never consumes a path supplied by the attestation.

---

### WP5 — Bundle status and migration policy

**Files**

- Update `src/resolver_sim/evidence/attestation_bundle.clj`
- Extend `test/resolver_sim/evidence/attestation_bundle_test.clj`
- Update `docs/security/ATTESTATION_SIGNATURE_VERIFICATION_DESIGN.md` status
  from proposal to implemented contract after rollout

**Implementation**

1. Change `check-attestation-signatures` to consume the trusted registry result
   and WP4 verifier output.
2. Treat these as failures when `:signature? true`:
   - unsigned;
   - malformed envelope;
   - unknown/untrusted key;
   - invalid signature;
   - invalid registry trust.
3. One-argument `verify-attestation-bundle` remains legacy structural mode and
   cannot return `:fully-verified`.
4. Two-argument verification only returns `:fully-verified` when all required
   integrity, sensitivity, registry trust, and signature checks pass.
5. Add status `:signature-valid-untrusted-registry` as a report/check outcome;
   it is not a trusted bundle success status.

**Exit criteria**

- Required-signature bundle with any unsigned attestation is invalid.
- Trusted valid signed bundle is eligible for `:fully-verified`.
- Legacy unsigned bundle is readable but never `:fully-verified`.

## Test matrix

| Test | WP |
|---|---|
| Valid trusted Ed25519 signature | 1, 4, 5 |
| Self-consistent attacker registry rejected | 3, 5 |
| Registry hash mismatch rejected | 3 |
| Duplicate/conflicting key IDs rejected | 2 |
| Bad hex, zero key, wrong binary lengths rejected before crypto | 1, 2, 4 |
| Missing/malformed `:signed-at` rejected | 1, 4 |
| `valid-until` boundary is exclusive | 2, 4 |
| Retired versus revoked policy behavior | 2, 4 |
| Bundle policy cannot relax verifier policy | 3, 5 |
| Changed attestor ID / payload hash / signed payload fails | 1, 4 |
| Embedded key path is ignored/rejected | 4 |
| Legacy unsigned compatibility status | 5 |

## Delivery sequence

1. Land WP1 and WP2 with unit tests only; no production verifier behavior
   changes.
2. Land WP3 with untrusted-registry reporting, still observe-only for legacy
   bundles.
3. Land WP4 against deterministic test keys.
4. Land WP5 with the two-argument trusted verification API.
5. Enable `:signature? true` only after a real externally managed CI public-key
   registry hash has been distributed to verifier configuration.
6. Remove legacy `:public-key-id` acceptance in a separately announced major
   compatibility change.

## Operational prerequisites

- Obtain a real CI/public attestor public key through the organization’s key
  management process; do not commit private keys.
- Distribute approved registry hashes through verifier configuration, CI secret
  configuration, release metadata, or another independent trust channel.
- Define incident owner and policy for key revocation, including whether
  pre-revocation signatures are accepted.
- Decide retention period for retired keys and historical bundle verification.

## Completion criteria

The feature is complete when a verifier supplied only with external trust policy
can distinguish all of these cases:

1. trusted registry + valid signature → trusted verification;
2. untrusted but internally valid registry + valid signature → not trusted;
3. trusted registry + invalid/tampered signature → invalid;
4. trusted registry + missing required signature → invalid;
5. legacy unsigned bundle → readable but never `:fully-verified`.
