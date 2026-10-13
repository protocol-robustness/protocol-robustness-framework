# ATTESTATION_BUNDLE_SPEC_V1

Status: Draft V1

## 1. Purpose

An attestation bundle is a portable, self-contained package that enables offline verification of attestations.

An attestation bundle is not a storage format.

An attestation bundle is not a network protocol.

An attestation bundle is a cryptographic envelope that binds attestations, claim results, evidence nodes, and registry snapshots into a content-addressed manifest that can be verified without the originating system.

Use cases:

- Export attestations for external auditors
- Submit attestation packages to review boards
- Archive verifiable attestation snapshots
- Exchange attested claims between systems without live registry access

------

## 2. Design Principles

### 2.1 Self-Contained

A bundle MUST include everything needed for verification except the cryptographic public keys (which are resolved by key-id at verification time).

### 2.2 Content-Addressed

Every object in the bundle is identified by its content hash. The manifest root hash commits to all included objects. Tampering with any object changes the root hash.

### 2.3 Honest Verification

The verifier MUST NOT produce a single vague "valid" status. It MUST distinguish between fully verified, hash-linked, partially verified, invalid, and policy-blocked states. The bundle is useful even when not fully disclosed, but it MUST be honest about what was and was not verified.

### 2.4 Separation of Concerns

Attestations, claim results, evidence nodes, registries, and sensitivity decisions are separate sections in the manifest. Each section has its own verification rules.

------

## 3. Manifest Schema

### 3.1 Top-Level Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `:bundle/version` | string | yes | Schema version string, must be `"attestation-bundle.v1"` |
| `:bundle/kind` | keyword | yes | Must be `:attestation-verification-package` |
| `:bundle/entrypoints` | vector | yes | Ordered list of entrypoint maps |
| `:bundle/objects` | vector | yes | All bundle objects with hashes and availability |
| `:bundle/registries` | map | yes | Registry snapshot references |
| `:bundle/sensitivity` | map | yes | Sensitivity sentinel decision |
| `:bundle/verification-profile` | map | yes | Feature flags for this bundle |
| `:bundle/root-hash` | string | yes | Canonical hash of all other fields |

### 3.2 Entrypoint Entry

```clojure
{:attestation/hash "sha256-hex..."
 :attestation/path "attestations/<hash>.edn"}
```

Each entrypoint references one attestation by hash and declares its file path
within the bundle directory. The `:attestation/hash` value is the bare
64-character lowercase hex SHA-256 of the attestation record. When this
attestation is referenced from an evidence node, the typed reference format
`attestation:sha256:<hash>` is used instead (see
`ATTESTATION_RESOLVER_SPEC_V1`).

### 3.3 Object Entry

```clojure
{:object/kind       :attestation-record | :claim-result | :evidence-node
 :object/hash       "sha256-hex..."
 :object/path       "attestations/<hash>.edn" | nil
 :object/availability :included | :hash-only}
```

`:object/availability` — when `:hash-only`, the object's content is not included in the bundle. The verifier marks this as partial verification.

### 3.4 Registry Snapshots

```clojure
{:attestors
 {:registry/hash "sha256-hex..."
  :registry/path "registries/attestor-registry.edn"}

 :claim-definitions
 {:registry/hash "sha256-hex..."
  :registry/path "registries/claim-definition-registry.edn"}

 :hash-intents
 {:registry/hash "sha256-hex..."
  :registry/path "registries/hash-intent-registry.edn"}}
```

Each registry is hashed using the `:registry` intent after converting non-canonical types (sets to sorted vectors, functions to strings, vars to strings).

### 3.5 Sensitivity Sentinel

```clojure
{:sentinel/decision  :allowed | :blocked
 :sentinel/report-hash "sha256-hex..."
 :sentinel/path      "reports/sensitivity-sentinel-report.edn"}
```

The sentinel decision gates bundle export. Default is `:blocked` (safety-first). When `:blocked`, the verifier rejects the bundle before running any other checks.

### 3.6 Verification Profile

```clojure
{:integrity?                 true    ;; verify content hashes
 :signature?                 true    ;; verify cryptographic signatures
 :registry-backed?           true    ;; registry snapshots included
 :subject-content-included?  false   ;; claim results are present in bundle
 :quorum?                    false}  ;; not used in V1
```

### 3.7 Root Hash Computation

The `:bundle/root-hash` is computed as:

```
SHA-256("EVIDENCE_RECORD_V1" || canonical-bytes(manifest-minus-root-hash))
```

Where `manifest-minus-root-hash` is the manifest map with `:bundle/root-hash` removed (via `dissoc`). Self-referential fields, generation timestamps, and local file metadata are excluded by this `dissoc`.

------

## 4. Bundle Directory Layout

```
bundle-root/
  manifest.edn                         # bundle manifest (EDN)
  attestations/
    <bare-sha256-hex>.edn              # attestation records (bare hash filename)
  claims/
    <claim-result-hash>.edn            # claim result maps
  evidence-nodes/
    <node-hash>.edn                    # evidence node maps
  registries/
    attestor-registry.edn              # attestor registry snapshot
    claim-definition-registry.edn      # claim definition registry snapshot
    hash-intent-registry.edn           # hash intent registry snapshot
  reports/
    sensitivity-sentinel-report.edn    # sensitivity sentinel report
```

**Note on attestation file naming:** Attestation files use the bare SHA-256
hash as their filename (`abcdef.edn`), not the typed reference format
(`attestation:sha256:abcdef`). The typed reference is used when referencing
attestations from evidence nodes. The resolver handles both formats:
it parses the typed reference, extracts the bare hash, and looks up the
file by that hash.

------

## 5. Verification Pipeline

The verifier MUST run these checks in order and return structured results:

### 5.1 Check Definitions

| # | Check ID | Status Values | Description |
|---|---|---|---|
| 1 | `:bundle-version-valid` | `:pass` / `:fail` | Bundle version matches `"attestation-bundle.v1"` |
| 2 | `:bundle-root-hash-valid` | `:pass` / `:fail` | Recomputed root hash matches recorded hash |
| 3 | `:object-integrity-valid` | `:pass` / `:warning` / `:fail` | Each included object's hash matches its content |
| 4 | `:attestation-integrity-valid` | `:pass` / `:warning` / `:fail` | Attestations pass Phase 9 integrity verification |
| 5 | `:attestation-signature-valid` | `:pass` / `:warning` | Attestation signatures are present (unsigned = warning) |
| 6 | `:registry-references-valid` | `:pass` | Registry snapshots are listed |
| 7 | `:claim-definition-references-valid` | `:pass` / `:warning` | Claim definition references are present |
| 8 | `:subject-content-available` | `:pass` / `:warning` | Hash-only subjects are explicitly marked |
| 9 | `:sensitivity-sentinel-approved` | `:pass` / `:blocked` | Sentinel decision is `:allowed` |

### 5.2 Status Computation

The overall bundle status is determined from individual check results:

| Status | Condition |
|---|---|
| `:blocked-by-sensitivity-policy` | Any check has status `:blocked` |
| `:invalid` | Any check has status `:fail` |
| `:fully-verified` | All checks `:pass`, no `:warning`, no `:fail`, no `:blocked` |
| `:partially-verified` | The subject-content-available check has `:warning` |
| `:hash-linked` | Warnings present (not subject-related), no failures |

### 5.3 Check Detail: Object Integrity

For each object in `:bundle/objects`:

- If `:object/availability` is `:hash-only` or the file does not exist → `:warning`
- If the file exists and its recomputed hash matches the recorded hash → `:pass`
- If hashes do not match → `:fail`

Object hashes are computed using `:evidence-record` hash intent.

### 5.4 Check Detail: Attestation Integrity

For each attestation object:

- If the attestation file is missing → `:warning`
- If the file exists and passes `verify-attestation-integrity` (Phase 9) → `:pass`
- If integrity verification fails → `:fail`

### 5.5 Check Detail: Sensitivity Sentinel

The sentinel check runs first in the status computation ordering:

- `:blocked-by-sensitivity-policy` is returned immediately if the sentinel decision is not `:allowed`
- Other checks still run and their results are reported, but the bundle status is `:blocked-by-sensitivity-policy`

------

## 6. Verification Response Shape

```clojure
{:valid?        true | false
 :bundle/status :fully-verified
                | :hash-linked
                | :partially-verified
                | :invalid
                | :blocked-by-sensitivity-policy
 :checks        [{:check/id    <keyword>
                  :check/status <:pass | :warning | :fail | :blocked>
                  :reason       <string>    ;; present on non-pass
                  :detail       <map>       ;; optional details
                  ...}]
 :summary       {:total-checks <int>
                 :pass         <int>
                 :warning      <int>
                 :fail         <int>
                 :blocked      <int>}}
```

------

## 7. Public API

### 7.1 Build

```clojure
(build-attestation-bundle
  {:attestations     [attestation-1 attestation-2 ...]
   :claim-results    [claim-result-1 ...]          ;; optional
   :evidence-nodes   [evidence-node-1 ...]          ;; optional
   :registries       {:attestors        <attestor-registry-map>
                      :claim-definitions <claim-definition-registry-map>
                      :hash-intents     <hash-intents-map>}
   :sensitivity-report {:sentinel/decision  :allowed | :blocked
                        :sentinel/report-hash "sha256:..."
                        :sentinel/path      "..."}           ;; optional
   :options          {:bundle-dir "path/to/output"}})
```

Returns the bundle manifest map.

### 7.2 Verify

```clojure
(verify-attestation-bundle bundle-manifest)
```

Returns the verification response map.

### 7.3 Persist

```clojure
(write-attestation-bundle! bundle-manifest
  {:attestations   [attestation-1 ...]
   :claim-results  [claim-result-1 ...]
   :evidence-nodes [evidence-node-1 ...]}
  trusted-bundle-root)
```

Writes all objects to their declared paths and writes `manifest.edn`.

### 7.4 Load

```clojure
(read-attestation-bundle "path/to/bundle")
```

Returns the bundle manifest map read from `manifest.edn`.

------

## 8. Non-Goals (V1)

The following are explicitly out of scope for V1:

- Encryption format
- Decentralized storage publishing (IPFS, Arweave, etc.)
- Nostr relay publishing
- On-chain anchoring (Ethereum, Bitcoin, etc.)
- Dynamic key discovery
- Automatic redaction of sensitive fields
- Multi-party workflow state
- Quorum verification
- Bundle diffing or merging

------

## 9. Acceptance Criteria

A V1 bundle is acceptable when:

1. The manifest is canonical and hash-addressed
2. Included objects match their declared hashes
3. Attestations pass Phase 9 integrity verification
4. Signatures verify against included registry snapshots (external verification)
5. Absent subjects are explicitly marked as hash-only
6. Sensitivity sentinel approval is present before export
7. The external verifier emits a deterministic report
