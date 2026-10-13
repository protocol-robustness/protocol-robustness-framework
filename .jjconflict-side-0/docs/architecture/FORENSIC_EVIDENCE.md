# Forensic-Grade Evidence Chain

## Acceptance Criteria

A run is forensic-grade only if all five criteria below are met
(`chain.clj`):

| # | Criterion | Verification | Status |
|---|-----------|-------------|--------|
| 1 | Registry hash verifies against all artifact entries | `verify-registry-hash` checks that hash recomputed from content matches recorded hash | Implemented |
| 2 | Registry hash is signed by a known signer | `verify-registry-signature` checks Ed25519 signature over the registry hash | Implemented |
| 3 | Final chain cursor verifies against the evidence chain | `verify-cursor-signature` checks cursor content hash against signed hash | Implemented |
| 4 | TSA token verifies against the registry hash or signed attestation | `write-tsa-timestamp!` submits registry hash to RFC 3161 TSA | Implemented |
| 5 | Strict validation passes | Checked externally via `build-evidence-registry!` with `:strict true` | External |

The `chain/forensic-status` function (`chain.clj`) checks criteria 1-4 and
returns a summary:

```clojure
{:all-pass? true/false
 :c1 {:criterion :registry-hash-verifies :pass true  :detail {:valid true}}
 :c2 {:criterion :registry-hash-signed   :pass true  :detail {:valid true}}
 :c3 {:criterion :cursor-verifies        :pass false :detail {:error "..."}}
 :c4 {:criterion :tsa-token-verified     :pass true  :detail {:valid true :tsa-url "..."}}}
```

## Evidence Chain Integrity

`chain/evidence-chain-integrity` validates the registry atom's contents:

```
{:registry-hash-valid true/false        — recomputed hash matches recorded
 :artifact-count N
 :all-hashes-non-nil true/false         — every artifact has an evidence hash
 :all-hashes-well-formed true/false     — every hash is a 64-char hex string
 :all-hashes-registered true/false      — all hashes non-nil and well-formed
 :all-with-component-hashes true/false  — every artifact has context/before/after/action/result hashes
 :chain-intact true/false               — registry-hash AND all hashes registered
}
```

## Signature Chain

### Registry Signature

The `write-registry-signature!` function (`chain.clj`):

1. Computes `registry-hash` (canonical hash over all evidence artifacts)
2. Signs the hash with Ed25519 private key (path from `:private-key-path` or
   dynamic var `*signing-key*`)
3. Writes `signature.json` containing the signature, hash, signer, timestamp
4. Writes `envelope.json` wrapping the signature with schema version and
   chain-final flag

```json
// signature.json
{
  "hash": "abc123...",
  "signature": "base64url-encoded-ed25519-sig",
  "signer": "path-to-public-key",
  "signed-at": "2026-06-26T..."
}

// envelope.json
{
  "registry_sha256": "abc123...",
  "schema_version": "evidence-envelope.v1",
  "signed-at": "...",
  "signer": "...",
  "signature": "...",
  "chain-final": true
}
```

### Cursor Signature

The `write-chain-cursor-final!` function optionally signs the cursor data:

```clojure
{:cursor/scope :targeted-evidence
 :cursor/final-seq 42
 :cursor/final-self-hash "deadbeef..."
 :cursor/total-captured 42
 :cursor/hash "hash-over-cursor-data"
 :cursor/signature "ed25519-sig"
 :cursor/signer "path-to-key"
 :cursor/signed-at "..."
 :cursor/forensic {:signature "..." :signer "..."}}
```

The `cursor/hash` is computed with `:evidence-chain` intent over the cursor
data fields. The signature is verified by `verify-cursor-signature`.

## Timestamping (TSA)

When a TSA URL is configured (via `:tsa-url` arg or `ts/*tsa-url*` dynamic
var), `write-tsa-timestamp!` submits the registry hash to an RFC 3161 Time
Stamp Authority:

1. Creates a TSA request (`registry.tsq`)
2. Receives a TSA response (`registry.tsr`)
3. Verifies the response locally
4. Stores sidecar artifacts alongside the registry

The TSA binds the registry hash to a wall-clock time, providing proof that
the evidence chain existed before a given date.

## Evidence Linking Model

### Temporal Chain (chain-seq)

Every evidence record produced during replay gets a sequential number via
`chain/inject-chain-fields` (`chain.clj:82`):

```clojure
{:evidence/chain-seq N
 :evidence/chain-prev-hash "hash-of-previous-evidence"
 :evidence/chain-self-hash "hash-of-this-evidence"}
```

Within a scenario, this provides total temporal ordering. Across scenarios,
each scenario has its own chain (starting at seq 1).

### Dependency Links (evidence/dependencies)

Fraud-slash evidence carries explicit dependency hashes to the evidence it
depends on:

```
proposal evidence ──→ slash/prorata-allocation evidence
                     (:evidence/dependencies)

stake evidence ────→ slash/prorata-allocation evidence
                     (:evidence/dependencies)

slash/prorata-allocation evidence ──→ allocation result artifact
                                     (:external-refs :evidence-record-hash)
```

### Aggregate Cursor

The aggregate cursor (`build-aggregate-cursor` at `chain.clj`) references all
scenario chain heads:

```json
{
  "cursor/scope": "aggregate-run",
  "cursor/scenario-count": 43,
  "cursor/scenario-heads": [
    {"scenario/seq": 3,
     "scenario/last-hash": "evidence-chain-hash",
     "scenario/total-captured": 3},
    {"scenario/seq": 5, ...},
    ...
  ],
  "cursor/total-evidence": 623,
  "cursor/registry-root-hash": "sha256-of-registry-json",
  "cursor/reconciled": true
}
```

Every scenario chain is referenced. A researcher holding the aggregate cursor
can verify that no scenario chain was tampered with (any change to a
scenario's chain head changes the aggregate cursor hash).

## Reconciliation

`reconcile-evidence!` (`chain.clj`) is a cross-check between three sources:

```
evidence files on disk (event-evidence/)
    vs
evidence-registry.json
    vs
chain-cursor-final.json
```

It detects:
- Evidence files written to disk but not registered (orphan evidence)
- Registry entries without corresponding disk files (phantom entries)
- Chain cursor seq behind the max disk seq (stale cursor)

## Key Files

| File | Path | Role |
|------|------|------|
| Registry | `evidence-registry.json` | All artifact entries with self-hash |
| Cursor | `chain-cursor-final.json` | Last scenario's chain head |
| Aggregate cursor | `aggregate-cursor.json` | All scenario chain heads |
| Signature | `signature.json` | Ed25519 signature over registry hash |
| Envelope | `envelope.json` | Wrapped signature with metadata |
| TSA request | `registry.tsq` | RFC 3161 timestamp request |
| TSA response | `registry.tsr` | RFC 3161 timestamp response |
| TSA metadata | `registry.tsa.json` | Verification details |
| Evidence files | `event-evidence/*.json` | Individual evidence records |

## Forensic Flow

```
1.  Scenario replay
    │
    ├── capture-event-evidence!      → write to disk
    ├── inject-chain-fields          → add seq/prev-hash/self-hash
    ├── register-evidence!           → add to scenario-local atom
    └── register-scenario-snapshot!  → push to run accumulator
    │
2.  Run finalization
    │
    ├── accumulate-scenario-evidence!  → merge into top-level atom
    ├── finalize-and-write!            → build registry → write
    ├── build-aggregate-cursor         → build aggregate cursor
    ├── write-registry-signature!      → sign registry hash
    ├── write-tsa-timestamp!           → timestamp registry hash
    └── reconcile-evidence!            → validate all sources match
    │
3.  Verification
    │
    ├── verify-registry-hash          → criterion 1
    ├── verify-registry-signature     → criterion 2
    ├── verify-cursor-signature       → criterion 3
    ├── verify-tsa-response           → criterion 4
    └── forensic-status                → aggregate status
```
