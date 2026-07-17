# Schema Catalog

This directory contains versioned JSON Schemas for interchange artifacts. Schemas are descriptive contracts: the authoritative producer and validation path depend on the artifact family. Do not change an existing version in place; introduce a new versioned schema when a compatibility-breaking change is required.

## Catalog

| Schema | Artifact | Primary producer / consumer | Notes |
|---|---|---|---|
| `scenario-v1.json` | Deterministic adversarial scenario | Scenario generation and replay tooling | Requires `schema_version: "1.0"`; describes the legacy JSON scenario interchange shape. |
| `test-run-v1.json` | Test-run manifest | Test/evidence artifact pipeline | Requires `schema_version: "test-run.v1"`. |
| `test-artifacts-v1.json` | Artifact registry [DEPRECATED] | Test/evidence artifact pipeline | Legacy v1. Retained for historical validation only. |
| `test-artifacts-v1.1.json` | Artifact registry [DEPRECATED] | Test/evidence artifact pipeline | Legacy v1.1. Retained for historical validation only. |
| `test-artifacts-v1.2.json` | Artifact registry (current) | Test/evidence artifact pipeline | Current enforced contract. Schema_validator requires this version. |
| `claim.v1.json` | Claim record | Claims and evidence tooling | Pair with claim definition and evidence specifications in `docs/specs/`. |
| `claimable-classification-v1.json` | Claimability classification | Classification reporting | Earlier version of the classification record. |
| `claimable-classification-v2.json` | Claimability classification | Classification reporting | Current revision where selected by the producing tool. |
| `attestation.v1.json` | Attestation record | Attestation/evidence tooling | See `docs/specs/ATTESTATION_SPEC_V1.md`. |
| `evidence-envelope.v1.json` | Evidence transport envelope | Evidence import/export tooling | Envelope around evidence payloads. |
| `event-evidence.v1.json` | Event-level evidence | Evidence pipeline | See the evidence specifications under `docs/specs/evidence/`. |
| `trace-end-projection-v1.json` | Terminal trace projection | Replay and equivalence tooling | Used for terminal-state comparison and reporting. |
| `benchmark-conclusion.v1.json` | Benchmark conclusion projection | Benchmark pipeline | Written before inventory/registry finalization; declares outcome, counts, scope. |
| `benchmark-assurance.v1.json` | Benchmark assurance/input-set commitment | Benchmark pipeline | Aggregates required artifact presence, conservation binding, and input-set root. |
| `benchmark-finalization.v1.json` | Benchmark cryptographic finalization | Benchmark pipeline | Commits to finalized hashes; excluded from its own registry. |
| `benchmark-completion.v1.json` | Benchmark terminal lifecycle state | Benchmark pipeline | Written last; confirms finalization, registry, and validation commitments. |

## Validation and compatibility

- Run repository-level artifact and evidence checks with `bb test:evidence`, `bb test`, or the registered `evidence validate` / `evidence verify-chain` CLI commands as appropriate.
- Schema validation is only one layer of correctness. Canonical hashing, semantic intent, evidence linkage, and scenario replay have additional contracts documented under `docs/specs/`.
- Generated artifacts normally live under `results/` and should not be edited manually.
- The schema filename and the `schema_version` field are both part of the compatibility contract. Consumers must select the schema matching the produced version.

## Related documentation

- `docs/specs/ATTESTATION_SPEC_V1.md`
- `docs/specs/CLAIMS_SPEC_V1.md`
- `docs/specs/BUNDLE_VERIFICATION_SPEC.md`
- `docs/evidence/README.md`
- `docs/reference/scenario-run-report.md`
