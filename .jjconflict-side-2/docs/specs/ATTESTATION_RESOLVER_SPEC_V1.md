# ATTESTATION_RESOLVER_SPEC_V1

Status: Draft V1

## 1. Purpose

Define the typed attestation reference format and resolution protocol for
verifying attestation references within evidence nodes, DAG links, and
attestation bundles.

The attestation resolver converts a typed reference string into a verified
attestation record by parsing the reference, looking up the artifact in
the attestation registry, and verifying structural integrity.

## 2. Design Principles

### 2.1 Typed References, Not Bare IDs

All attestation references within evidence nodes use the format
`attestation:sha256:<64-hex>` instead of bare content hashes. This
disambiguates attestation references from other content-addressed artifacts
(evidence nodes, claim results) at the string level.

### 2.2 Structural Integrity Before Cryptographic Verification

Resolution verifies hash consistency and schema conformance before
signature verification. A structurally valid attestation with an invalid
signature still resolves successfully (with `:signature-valid? false`)
rather than failing resolution entirely. This preserves auditability:
the artifact exists and is what it claims to be, even if its cryptographic
proof is stale or missing.

### 2.3 Registry-Bound, Not Self-Contained

Resolution depends on the in-process attestation registry. An attestation
must have been previously registered via `register-attestation!` to be
resolvable. For offline or cross-system verification, use attestation
bundles (`ATTESTATION_BUNDLE_SPEC_V1`).

## 3. Typed Reference Format

### 3.1 Syntax

```
attestation:sha256:<64-lowercase-hex-characters>
```

### 3.2 Examples

| Reference | Validity |
|---|---|
| `attestation:sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789` | Valid |
| `attestation:sha256:aaaa` | Invalid — hash must be exactly 64 hex chars |
| `sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789` | Invalid — missing `attestation:` prefix |
| `abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789` | Invalid — bare hash, no typed prefix |
| `attestation:sha512:abcdef...` | Invalid — only `sha256` supported |

### 3.3 Supported Algorithms

| Algorithm | Status |
|---|---|
| `:sha256` | Supported |

Additional algorithms may be added to `supported-algorithms` as needed.

### 3.4 Parsed Representation

```
{:type :attestation
 :algorithm :sha256
 :hash "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"}
```

## 4. Resolution Flow

### 4.1 Steps

```
Reference string
       │
       ▼
  Parse reference ───→  :unparseable-reference
       │
       ▼
  Algorithm check ───→  :unsupported-algorithm
       │
       ▼
  Registry lookup ───→  :missing
       │
       ▼
  Hash verification ──→ :hash-mismatch
       │
       ▼
  Type/schema check ──→ :invalid-type
       │
       ▼
  [Signature check] ──→ included in :checks (does not affect :valid?)
       │
       ▼
  {:valid? true
   :attestation <record>
   :checks {:hash-ok? true
            :type-ok? true
            :signature-valid? <bool>
            :algorithm :sha256
            :hash <64-hex>}}
```

### 4.2 Resolution Failure Modes

| Error | Condition |
|---|---|
| `:unparseable-reference` | String does not match `attestation:sha256:<64-hex>` pattern |
| `:unsupported-algorithm` | Algorithm is not in `supported-algorithms` |
| `:missing` | Hash not found in attestation registry |
| `:hash-mismatch` | Resolved artifact's recorded hash does not match the reference hash |
| `:invalid-type` | Resolved artifact lacks attestation schema fields |

## 5. API

All functions are in namespace `resolver-sim.evidence.attestation-resolver`.

### 5.1 `parse-reference`

Parse a typed attestation reference string into its components.

```clojure
(parse-reference ref-str)
```

| Argument | Type | Description |
|---|---|---|
| `ref-str` | `string` or `nil` | The typed reference to parse |

Returns the parsed map or `nil` if the string is not a valid typed reference.
Non-string inputs and bare attestation IDs (without the `attestation:sha256:`
prefix) return `nil`.

### 5.2 `resolve-attestation`

Resolve a typed attestation reference. Performs the full resolution pipeline:
parse, lookup, hash verify, type verify, optional signature verify.

```clojure
(resolve-attestation ref-str opts)
```

| Argument | Type | Default | Description |
|---|---|---|---|
| `ref-str` | `string` | — | Typed attestation reference |
| `:verify-fn` (in opts map) | `fn` | `nil` | Optional `(fn [attestation] bool)` for cryptographic signature verification |

Returns:

```clojure
;; Success
{:valid? true
 :attestation <attestation-record>
 :checks {:hash-ok? true
          :type-ok? true
          :signature-valid? <true/false/nil>
          :algorithm :sha256
          :hash <64-hex>}}

;; Parse failure
{:valid? false
 :error :unparseable-reference
 :detail "Expected \"attestation:sha256:<64-hex>\", got ..."}

;; Algorithm failure
{:valid? false
 :error :unsupported-algorithm
 :detail "Unsupported hash algorithm ..."}

;; Registry miss
{:valid? false
 :error :missing
 :detail "No attestation found for hash ..."}

;; Hash mismatch (tampered or corrupted artifact)
{:valid? false
 :error :hash-mismatch
 :detail {:reference <hash-from-ref>
          :resolved <hash-from-artifact>}}

;; Invalid type (artifact is not an attestation record)
{:valid? false
 :error :invalid-type
 :detail "Resolved artifact is not a valid attestation record"}
```

**Important:** `:valid?` reflects structural integrity only (hash + type).
Signature verification is reported in `:checks :signature-valid?` but does
not affect `:valid?`. This ensures that a validly-formed attestation with
an expired or missing signature is still resolvable (the caller decides
how to treat the signature status).

### 5.3 `resolve-attestation!`

Like `resolve-attestation` but throws on failure.

```clojure
(resolve-attestation! ref-str opts)
```

| Returns | Condition |
|---|---|
| Attestation record | Resolution succeeds |
| Throws `ex-info` with `:error` and `:detail` keys | Resolution fails |

## 6. Integration

### 6.1 Evidence Node Attestation References

Evidence nodes store attestation references in `:attestations` as a vector
of typed reference strings:

```clojure
{:node-hash "sha256:abc..."
 :attestations ["attestation:sha256:def..."
                "attestation:sha256:ghi..."]
 ...}
```

Node validation (`check-node-attestations-present`) enforces the typed
reference pattern. The resolver consumes these references to retrieve
the full attestation records for verification or display.

### 6.2 Attestation DAG Nodes

`build-attestation-dag-node` and `emit-attestation-dag-node!` in
`resolver-sim.evidence.attestation-dag` produce evidence nodes whose
`:attestations` entries use the typed reference format. The reference is
constructed as:

```clojure
(str "attestation:sha256:" (:attestation/id attestation))
```

### 6.3 Attestation Bundles

Attestation bundles (`docs/specs/ATTESTATION_BUNDLE_SPEC_V1`) embed
attestation records alongside their references. The resolver's registry
lookup step is replaced by bundle-local lookup when verifying bundles.

## 7. Related Specs

| Document | Location |
|---|---|
| Attestation record schema | `docs/specs/ATTESTATION_SPEC_V1.md` |
| Attestation bundle format | `docs/specs/ATTESTATION_BUNDLE_SPEC_V1.md` |
| Attestation integrity verification | `src/resolver_sim/evidence/attestation_integrity.clj` |
| Attestation DAG nodes | `src/resolver_sim/evidence/attestation_dag.clj` |
| Evidence node validation | `src/resolver_sim/evidence/node.clj` |
| Resolver source | `src/resolver_sim/evidence/attestation_resolver.clj` |
| Resolver tests | `test/resolver_sim/evidence/attestation_resolver_test.clj` |
