# FORENSIC_ATTESTATIONS_SPEC_V1

Status: Draft V1

## 1. Purpose

Define the schema, population rules, and verification requirements for the
`attestations/` directory within a forensic run bundle. The `attestations/`
directory holds attestation records — signed statements by an identified
attestor vouching for a specific claim result or evidence node.

This spec covers what an attestation record is, how it is produced, how it
is stored, and how it is verified.

## 2. Design Principles

### 2.1 Attestations Are Audit Records

An attestation records that a specific identity evaluated a specific piece
of evidence or claim result at a specific time and reached a specific
conclusion. Attestations are the **who attested to what, when, and with
what result**.

### 2.2 Content-Addressed and Optional Signing

Each attestation record is content-addressed (named by its SHA-256 hash)
so that identical attestations produce identical filenames. Attestations
MAY optionally carry an Ed25519 cryptographic signature over a canonical
projection of the record.

### 2.3 Attestations Reference Either Claims or Evidence Nodes

An attestation MAY reference either:

1. A **claim result** (a file in `claims/`) — the attestor vouches for the
   claim evaluation outcome.
2. An **evidence node** (a file in `evidence-dag/`) — the attestor vouches
   for the integrity or interpretation of the evidence.

### 2.4 Self-Attestation vs External Attestation

The forensic pipeline supports two attestation modes:

- **Self-attestation**: The run itself attests to its own claim results
  (attestor-id is the run's bundle hash). This is automatic.
- **External attestation**: A separate attester (CI system, researcher,
  auditor) produces attestations referencing the run's evidence. This is
  imported after the run.

## 3. Schema: Attestation Record

### 3.1 Attestation File

Each file in `attestations/` is a JSON document with the following schema.

**Filename:** `attestation-<hash-prefix>.json` where `<hash-prefix>` is the
first 16 hex characters of the file's SHA-256 (self-referential, computed
after omitting the `attestation/hash` and `attestation/signature` fields).

**Required fields:**

| Field | Type | Description |
|---|---|---|
| `attestation/schema-version` | string | `"forensic-attestation.v1"` |
| `attestation/id` | string | SHA-256 content hash (same as `attestation/hash`) |
| `attestation/hash` | string | SHA-256 of canonical projection (excluding `attestation/hash` and `attestation/signature`) |
| `attestation/subject-kind` | string | One of `:claim-result`, `:evidence-node` |
| `attestation/subject-hash` | string | SHA-256 of the claim result or evidence node being attested |
| `attestation/claim-result` | string | One of `:verified`, `:reproduced`, `:certified`, `:approved`, `:rejected` |
| `attestation/attestor-id` | string | Identity of the attester (keyword or bundle hash) |
| `attestation/signed-at` | string | ISO-8601 UTC timestamp |

**Optional fields:**

| Field | Type | Description |
|---|---|---|
| `attestation/claim-id` | string | Claim definition id (present when `subject-kind` is `:claim-result`) |
| `attestation/signing-key-id` | string | Key identifier (present when cryptographically signed) |
| `attestation/signature` | object | Ed25519 signature (see below) |
| `attestation/provenance` | object | Context provenance map (see below) |
| `attestation/metadata` | object | Any additional metadata (excluded from hash computation) |

**Signature object** (`attestation/signature`):

| Field | Type | Description |
|---|---|---|
| `sig/algorithm` | string | `:ed25519` |
| `sig/public-key-id` | string | Identifier for the signing public key |
| `sig/value` | string | Hex-encoded Ed25519 signature |

**Provenance map** (`attestation/provenance`):

| Field | Required | Description |
|---|---|---|
| `prov/schema-version` | YES | `"forensic-provenance.v1"` |
| `prov/trigger` | YES | One of `:claim-evaluation`, `:run-complete`, `:startup-validation`, `:manual`, `:scenario-result`, `:pipeline-step` |
| `prov/generated-at` | YES | ISO-8601 UTC timestamp |
| `prov/run-id` | No | Run identifier |
| `prov/scenario-id` | No | Scenario identifier |
| `prov/vcs-sha` | No | Git commit SHA |
| `prov/producer` | No | Producer identification |

### 3.2 Signing Payload (Canonical Projection)

When cryptographically signed, the bytes fed to Ed25519 are the canonical
JSON of this projection:

```json
{
  "intent": "forensic-attestation",
  "artifact": {
    "attestation/schema-version": "forensic-attestation.v1",
    "attestation/subject-hash": "...",
    "attestation/subject-kind": ":claim-result",
    "attestation/claim-id": ":registry-hash-verifies",
    "attestation/claim-result": ":verified",
    "attestation/attestor-id": "self:run-bundle-hash",
    "attestation/signed-at": "2026-06-27T23:48:22Z",
    "attestation/provenance": {...}
  }
}
```

### 3.3 Example

```json
{
  "attestation/schema-version": "forensic-attestation.v1",
  "attestation/id": "a1b2c3d4e5f6...",
  "attestation/hash": "a1b2c3d4e5f6...",
  "attestation/subject-kind": ":claim-result",
  "attestation/subject-hash": "d4e37ab33c6ea91c...",
  "attestation/claim-id": ":registry-hash-verifies",
  "attestation/claim-result": ":verified",
  "attestation/attestor-id": "self:forensic-pipeline",
  "attestation/signed-at": "2026-06-27T23:48:22Z",
  "attestation/signing-key-id": "run-bundle-001",
  "attestation/signature": {
    "sig/algorithm": ":ed25519",
    "sig/public-key-id": "run-bundle-001",
    "sig/value": "abcdef0123456789..."
  },
  "attestation/provenance": {
    "prov/schema-version": "forensic-provenance.v1",
    "prov/trigger": ":run-complete",
    "prov/generated-at": "2026-06-27T23:48:22Z",
    "prov/run-id": "2026-06-27T23-47-21Z-workspace-isolation-test",
    "prov/vcs-sha": "abc123def456",
    "prov/producer": "resolver-sim.evidence.chain/finalize-and-attest!"
  }
}
```

## 4. Population Rules

### 4.1 Producer

The `attestations/` directory is populated by the Clojure attestation
pipeline during evidence chain finalization. Specifically:

1. After claim evaluation results are written to `claims/`, the Clojure
   pipeline calls `chain/finalize-and-attest!`.
2. For each claim result, a self-attestation is created by
   `attestation/build-attestation` with `attestor-id` set to
   `"self:<bundle-hash>"`.
3. Attestations are optionally signed if a signing key is configured.
4. Attestation records are serialized to JSON and written to `attestations/`
   using the self-referential sealing pattern (hash → write → verify).

### 4.2 When Attestations Are Created

| Phase | What Is Attested | Attestor |
|---|---|---|
| Evidence chain finalization | Each forensic-grade claim result | `"self:<bundle-hash>"` |
| Run completion | Overall `:forensic-grade` composite claim | `"self:<bundle-hash>"` |
| External import | Any bundle evidence | External key |

### 4.3 What Goes in `attestations/` vs `evidence-dag/`

Evidence nodes in `evidence-dag/` are lightweight references to attestations
(storing only `attestor-id`, `subject-hash`, `claim-result`, `signed-at`).
Full attestation records with provenance and optional signatures go in
`attestations/`.

| Concept | Location | Description |
|---|---|---|
| Full attestation record | `attestations/` | Complete signed statement with provenance and optional signature |
| Attestation evidence node | `evidence-dag/` | Lightweight reference pointing to attestation via `attestation-id` |

## 5. Verification Rules

The `verify.py` script MUST enforce:

| Check | Rule | Severity |
|---|---|---|
| Directory exists | `attestations/` must exist | required |
| At least one record | `attestations/` must contain at least one `*.json` file | required |
| Hash integrity | Each file's SHA-256 must match its claimed `attestation/hash` (self-referential, excluding `attestation/hash` and `attestation/signature` fields) | required |
| Schema version | Each file's `attestation/schema-version` must be `"forensic-attestation.v1"` | required |
| Valid claim-result | Each file's `attestation/claim-result` must be one of the allowed values | required |
| Subject resolves | If `subject-kind` is `:claim-result`, `subject-hash` must match a file in `claims/`; if `:evidence-node`, must match a file in `evidence-dag/` | warning |
| Optional signature | If `attestation/signature` is present, the signature must verify against the canonical projection using the claimed public key | required |

## 6. Transition from Stubs

As of 2026-06-27, the `attestations/` directory is created empty by
`run.py`. The planned implementation phases are:

| Phase | What Changes | Status |
|---|---|---|
| Phase A | Spec written, directory created | ✅ Done |
| Phase B | Clojure pipeline writes self-attestations for forensic-grade claims | ✅ Done |
| Phase C | Verify enforces content checks | ✅ Done |
| Phase D | External attestation import support | 📅 Future |

## 7. References

- `src/resolver_sim/evidence/attestation.clj` — Attestation builder,
  signing payload, verify-attestation
- `src/resolver_sim/evidence/attestation_registry.clj` — In-memory
  attestation registry with query functions
- `src/resolver_sim/evidence/attestation_node.clj` — Evidence node builder
  for attestations
- `src/resolver_sim/evidence/attestation_provenance.clj` — Provenance
  map schema and builder
- `src/resolver_sim/evidence/attestation_emitter.clj` — Orchestrated
  emit pipeline
- `src/resolver_sim/evidence/chain.clj` — `finalize-and-attest!`,
  `register-additional-artifact!`
- `schemas/attestation.v1.json` — Attestation JSON Schema
- `docs/forensic/FORENSIC_CLAIMS_SPEC_V1.md` — Claim evaluation result spec
- `docs/forensic/CAPABILITIES.md` — Forensic bundle workflow layout
- `scripts/forensic/run.py` — Forensic run orchestrator
- `scripts/forensic/verify.py` — Bundle verification
