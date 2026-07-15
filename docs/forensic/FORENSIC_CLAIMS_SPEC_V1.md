# FORENSIC_CLAIMS_SPEC_V1

Status: Draft V1

## 1. Purpose

Define the schema, population rules, and verification requirements for the
`claims/` directory within a forensic run bundle. The `claims/` directory
holds machine-readable claim evaluation results — records of which protocol
claims were evaluated during the run, with their status, evidence references,
and outcome.

This spec covers what a claim evaluation result is, how it is produced, how
it is stored, and how it is verified.

## 2. Design Principles

### 2.1 Claims Are Falsifiable Propositions

A claim is a testable statement about protocol behavior under stated
assumptions. Every claim evaluation result records whether the claim was
falsified, not falsified, or inconclusive, along with the evidence that
supports the conclusion.

### 2.2 Claim Definitions vs Claim Results

The forensic bundle distinguishes between:

- **Claim definitions** — registered, versioned descriptions of what a
  claim asserts, its assumptions, and its falsification conditions. These
  live in `data/claims/` and `protocols_src/` within the source tree.
- **Claim evaluation results** — per-run records in `claims/` that capture
  the outcome of evaluating one or more claim definitions against the run's
  evidence. These are produced by the forensic pipeline, not by source
  editing.

### 2.3 Content-Addressed Results

Each claim evaluation result file is content-addressed (named by its
SHA-256 hash) so that identical evaluation results produce identical
filenames across runs.

### 2.4 Separated by Claim Source

The `claims/` directory may contain results from both:

1. **Forensic-grade claims** (defined in
   `protocols_src/resolver_sim/evidence/forensic_claims.clj`) — automated
   audit claims about the evidence chain itself.
2. **Protocol claims** (defined in `data/claims/sew-claims.edn`) — domain
   claims about protocol behavior.

## 3. Schema: Claim Evaluation Result

### 3.1 Claim Evaluation Result File

Each file in `claims/` is a JSON document with the following schema.

**Filename:** `claim-result-<hash-prefix>.json` where `<hash-prefix>` is the
first 16 hex characters of the file's SHA-256 (self-referential, computed
after omitting the `result/hash` field).

**Required fields:**

| Field | Type | Description |
|---|---|---|
| `result/schema-version` | string | `"forensic-claim-result.v1"` |
| `result/hash` | string | SHA-256 of canonical projection (excluding `result/hash`) |
| `result/claim-id` | string | Keyword identifying the claim definition (e.g. `:registry-hash-verifies`) |
| `result/category` | string | One of `:audit`, `:invariant`, `:safety`, `:composite` |
| `result/status` | string | One of `:pass`, `:fail`, `:inconclusive`, `:not-evaluated` |
| `result/evaluated-at` | string | ISO-8601 UTC timestamp of evaluation |
| `result/evidence-refs` | array | List of evidence references supporting the result |

**Optional fields:**

| Field | Type | Description |
|---|---|---|
| `result/description` | string | Human-readable restatement of the claim |
| `result/assumptions` | array | Parameter bounds / preconditions under which the claim was evaluated |
| `result/falsified-if` | string | Exact condition that would disprove the claim |
| `result/failure-detail` | string | Human-readable explanation (present when `status` is `:fail`) |
| `result/confidence` | string | One of `:high`, `:medium`, `:bounded`, `:low` |
| `result/counterexamples` | array | Zero or more counterexample maps (present when `status` is `:fail`) |
| `result/inputs` | object | Map of input names to values used in evaluation |

**Evidence reference entry** (in `result/evidence-refs`):

| Field | Type | Description |
|---|---|---|
| `ref/kind` | string | One of `:evidence-node`, `:artifact`, `:cursor`, `:tsa-token` |
| `ref/hash` | string | SHA-256 of the referenced evidence |
| `ref/path` | string | Relative path within the bundle to the referenced evidence |

### 3.2 Example

```json
{
  "result/schema-version": "forensic-claim-result.v1",
  "result/hash": "d4e37ab33c6ea91c...",
  "result/claim-id": ":registry-hash-verifies",
  "result/category": ":audit",
  "result/status": ":pass",
  "result/evaluated-at": "2026-06-27T23:48:22Z",
  "result/evidence-refs": [
    {"ref/kind": ":artifact", "ref/hash": "a1b2c3...", "ref/path": "../workspace/evidence-registry.json"},
    {"ref/kind": ":cursor", "ref/hash": "d4e5f6...", "ref/path": "../workspace/chain-cursor-final.json"}
  ],
  "result/description": "Evidence registry hash is consistent with its content",
  "result/inputs": {
    ":evidence-registry": "../workspace/evidence-registry.json"
  }
}
```

## 4. Population Rules

### 4.1 Producer

The `claims/` directory is populated by the Clojure forensic adapter
(`resolver-sim.evidence.forensic-adapter`) during the scenario execution
pipeline. Specifically:

1. After all scenarios complete, the Clojure pipeline evaluates each
   registered claim definition against the run's evidence.
2. Claim definitions are loaded from the passive claim-definition-registry
   (populated by `resolver-sim.evidence.forensic-claims/register-forensic-claims!`).
3. For each claim, an evaluation result map is built using the claim's
   `:evaluation` entry point.
4. Result maps are serialized to JSON and written to `claims/` using the
   self-referential sealing pattern (hash → write → verify).

### 4.2 When Claims Are Evaluated

| Phase | Claims Evaluated | Trigger |
|---|---|---|
| Evidence chain finalization | `:registry-hash-verifies`, `:registry-hash-signed`, `:cursor-verifies`, `:tsa-token-verified`, `:evidence-chain-reconciled` | Called by `chain/finalize-and-attest!` |
| Run completion | `:forensic-grade` (composite) | After all five audit claims pass |
| User-initiated | Any registered claim | Via `bb forensic:claim-evaluate` |

### 4.3 What Goes in `claims/` vs `attestations/`

| Concept | Location | Description |
|---|---|---|
| Claim evaluation result | `claims/` | Machine-readable outcome of testing a claim |
| Attestation | `attestations/` | Signed record of who vouched for a claim result or evidence node |

Claim results are produced by the evaluator; attestations are produced by
the attester (which may be the same process acting as a self-attestor).

## 5. Verification Rules

The `verify.py` script MUST enforce:

| Check | Rule | Severity |
|---|---|---|
| Directory exists | `claims/` must exist | required |
| At least one result | `claims/` must contain at least one `*.json` file | required |
| Hash integrity | Each file's SHA-256 must match its claimed `result/hash` (self-referential, excluding `result/hash` field) | required |
| Schema version | Each file's `result/schema-version` must be `"forensic-claim-result.v1"` | required |
| Valid status | Each file's `result/status` must be one of the allowed values | required |
| Evidence refs resolve | Each `ref/path` in `result/evidence-refs` must point to an existing file within the bundle | warning |

## 6. Transition from Stubs

As of 2026-06-27, the `claims/` directory is created empty by `run.py`.
The planned implementation phases are:

| Phase | What Changes | Status |
|---|---|---|
| Phase A | Spec written, directory created | ✅ Done |
| Phase B | Clojure pipeline writes forensic-grade claim results | ✅ Done |
| Phase C | Verify enforces content checks | ✅ Done |
| Phase D | Protocol claim evaluation integrated | 📅 Future |

## 7. References

- `protocols_src/resolver_sim/evidence/forensic_claims.clj` — 6 forensic
  claim definitions
- `data/claims/sew-claims.edn` — 17 protocol claim definitions
- `src/resolver_sim/evidence/forensic_adapter.clj` — ForensicEvidenceAdapter
  protocol (produces derived diagnostic artifacts)
- `src/resolver_sim/definitions/passive_registries.clj` — Claim definition
  registry (dynamic var)
- `src/resolver_sim/evidence/chain.clj` — `forensic-status`,
  `finalize-and-attest!`, `verify-registry-hash`
- `schemas/claim.v1.json` — Claim definition JSON Schema
- `docs/forensic/CAPABILITIES.md` — Forensic bundle workflow layout
- `scripts/forensic/run.py` — Forensic run orchestrator
- `scripts/forensic/verify.py` — Bundle verification
