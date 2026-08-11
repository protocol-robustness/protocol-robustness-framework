# Benchmark Documentation

Human-readable specifications and design documentation for the benchmark
infrastructure. The canonical executable surface lives in `benchmarks/`.

## Specifications

- [Benchmark Pack Spec V1](BENCHMARK_PACK_SPEC_V1.md) — registry format,
  benchmark definition bundle, lifecycle, claims, runner policies, scoring,
  and evidence policies.
- [Benchmark Result Spec V1](BENCHMARK_RESULT_SPEC_V1.md) — result shape,
  status classification, claim/scenario result format, and hash computation.
- [Benchmark Report Fields](BENCHMARK_REPORT_FIELDS.md) — field-by-field
  reference for benchmark reports, scoring classification, claim maturity,
  and conclusion format.
- [Benchmark Assurance Spec V1](BENCHMARK_ASSURANCE_SPEC_V1.md) — assurance
  and finalization chain: conservation, input-set commitment, finalization,
  and verification.
- [Benchmark Conclusion Spec V1](BENCHMARK_CONCLUSION_SPEC_V1.md) — conclusion
  projection schema, classification rules, and registry contract.
- [External Benchmark Pack Spec V1](EXTERNAL_BENCHMARK_PACK_SPEC_V1.md) —
  contract for supplying external benchmark packs to the canonical runner.
- [Evidence Bundle Integrity Contract](EVIDENCE_INTEGRITY_CONTRACT.md) —
  the `:bundle-root` commitment: writer normalization, verification, the
  report fail-closed gate, and the integrity-vs-authenticity boundary.

## Design

- [Claim Verification Design](DESIGN_CLAIM_VERIFICATION.md) — claim
  verification maturity levels, evaluator interface, runner integration,
  and implementation status.

## Canonical Source

The corresponding executable surface — registries, pack definitions,
scenario data, scoring policies, and runner configuration — lives in
`benchmarks/`.
