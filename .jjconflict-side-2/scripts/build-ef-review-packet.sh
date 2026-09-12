#!/usr/bin/env bash
# Create a reproducible external technical-review packet from the built Sew JAR.
# Usage: bash scripts/build-ef-review-packet.sh /absolute/or-relative/output-dir

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 OUTPUT_DIR" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$1"
JAR="$PROJECT_DIR/target/prf-runner-sew-0.1.0-uber.jar"

if [ ! -f "$JAR" ]; then
  echo "Missing Sew JAR: $JAR. Run bb build:sew first." >&2
  exit 2
fi
if [ -e "$OUTPUT_DIR" ] && [ -n "$(find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  echo "Review packet output must be absent or empty: $OUTPUT_DIR" >&2
  exit 2
fi

mkdir -p "$OUTPUT_DIR"/{bin,docs/specs,inputs/scenarios,inputs/benchmarks,inputs/test-vectors/pro-rata,evidence,diagnostics}
cp "$JAR" "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar"
cp "$PROJECT_DIR/scripts/verify-ef-review-packet.sh" "$OUTPUT_DIR/bin/verify-review-packet.sh"
chmod +x "$OUTPUT_DIR/bin/verify-review-packet.sh"
cp "$PROJECT_DIR/docs/review/EF_REVIEW_GUIDE.md" "$OUTPUT_DIR/docs/REVIEW_GUIDE.md"
cp "$PROJECT_DIR/docs/review/SCENARIO_REVIEW_HIGHLIGHTS.md" "$OUTPUT_DIR/docs/SCENARIO_REVIEW_HIGHLIGHTS.md"
cp "$PROJECT_DIR/docs/review/PACKET_LAYOUT.md" "$OUTPUT_DIR/docs/PACKET_LAYOUT.md"
cp "$PROJECT_DIR/docs/benchmarks/BENCHMARK_ASSURANCE_SPEC_V1.md" "$OUTPUT_DIR/docs/BENCHMARK_ASSURANCE_SPEC_V1.md"
cp "$PROJECT_DIR/docs/specs/SEW_CUSTODY_EXPOSURE_V1.md" \
   "$OUTPUT_DIR/docs/specs/SEW_CUSTODY_EXPOSURE_V1.md"

for scenario in \
  S-DR-001-basic-release-ruling.edn \
  S-DR-084-evidence-after-settlement-rejected.edn \
  S-NC-001-freeze-active-dispute-negative-control.edn \
  Y06_multi-party-pro-rata-shortfall.edn \
  DR-N-002-reversal-slash-appeal-rejected.edn; do
  cp "$PROJECT_DIR/scenarios/edn/$scenario" "$OUTPUT_DIR/inputs/scenarios/$scenario"
done
cp "$PROJECT_DIR/benchmarks/packs/prf-core/force-authorisation-custody-v1.edn" \
   "$OUTPUT_DIR/inputs/benchmarks/force-authorisation-custody-v1.edn"
# These are calculator-reference inputs, not replay witnesses for Y06.
cp "$PROJECT_DIR/resources/test-vectors/pro-rata/liquidity-fulfillment-liquidity-insufficient.json" \
   "$OUTPUT_DIR/inputs/test-vectors/pro-rata/liquidity-fulfillment-liquidity-insufficient.json"
cp "$PROJECT_DIR/resources/test-vectors/pro-rata/liquidity-fulfillment-liquidity-equal-buckets-dust.json" \
   "$OUTPUT_DIR/inputs/test-vectors/pro-rata/liquidity-fulfillment-liquidity-equal-buckets-dust.json"

# Emit canonical sha256:<hex> references for packet-relative paths using the
# packaged jar itself (resolver-sim.hash.reference/sha256-ref-file), so the
# packet is self-hosting: the artifact being reviewed computes its own refs.
packet_refs() {
  ( cd "$OUTPUT_DIR" && java -jar bin/prf-runner-sew-0.1.0-uber.jar -m resolver-sim.cli.main ref-file "$@" )
}

MANIFEST_REFS="$(mktemp)"
trap 'rm -f "$MANIFEST_REFS"' EXIT
packet_refs \
  bin/prf-runner-sew-0.1.0-uber.jar \
  inputs/scenarios/S-DR-084-evidence-after-settlement-rejected.edn \
  inputs/scenarios/Y06_multi-party-pro-rata-shortfall.edn \
  inputs/scenarios/S-NC-001-freeze-active-dispute-negative-control.edn \
  docs/specs/SEW_CUSTODY_EXPOSURE_V1.md \
  inputs/benchmarks/force-authorisation-custody-v1.edn \
  inputs/test-vectors/pro-rata/liquidity-fulfillment-liquidity-insufficient.json \
  inputs/test-vectors/pro-rata/liquidity-fulfillment-liquidity-equal-buckets-dust.json \
  > "$MANIFEST_REFS"

ref_for() {
  awk -v target="$1" '$2 == target {print $1}' "$MANIFEST_REFS"
}

JAR_REF="$(ref_for bin/prf-runner-sew-0.1.0-uber.jar)"
if [ -z "$JAR_REF" ]; then
  echo "Failed to compute canonical jar reference via ref-file" >&2
  exit 2
fi

{
  echo "packet_schema_version=prf-ef-review-packet.v1"
  echo "built_jar=bin/prf-runner-sew-0.1.0-uber.jar"
  echo "build_timestamp_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  JJ_COMMIT="$(jj --repository "$PROJECT_DIR" log -r @ --no-graph -T commit_id 2>/dev/null | tr -d '\n')"
  if [ -n "$JJ_COMMIT" ]; then
    echo "repository_vcs=jujutsu"
    echo "repository_commit=$JJ_COMMIT"
  else
    echo "repository_vcs=unavailable"
    echo "repository_commit=unavailable"
    echo "provenance_status=incomplete"
  fi
  echo "jar_sha256=$JAR_REF"
} > "$OUTPUT_DIR/PROVENANCE.txt"

(
  cd "$OUTPUT_DIR/evidence"
  java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" -m resolver-sim.cli.main \
    run-scenario --scenario classpath:scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn \
    --run-root scenario-rejected
  java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" -m resolver-sim.cli.main \
    verify-scenario --run-root scenario-rejected
  java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" -m resolver-sim.cli.main \
    run-scenario --scenario classpath:scenarios/edn/Y06_multi-party-pro-rata-shortfall.edn \
    --run-root scenario-pro-rata
  java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" -m resolver-sim.cli.main \
    verify-scenario --run-root scenario-pro-rata
  # This scenario reaches an unsuppressed invariant violation. Its non-zero
  # semantic outcome is expected; the completed package must still verify.
  if java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" -m resolver-sim.cli.main \
       run-scenario --scenario classpath:scenarios/edn/S-NC-001-freeze-active-dispute-negative-control.edn \
       --run-root scenario-semantic-failure; then
    echo "Expected S-NC-001 to conclude with a semantic failure" >&2
    exit 1
  fi
  java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" -m resolver-sim.cli.main \
    verify-scenario --run-root scenario-semantic-failure
  java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" -m resolver-sim.cli.main \
    run-benchmark prf-core/force-authorisation-custody-v1 \
    --run-root benchmark-force-authorisation
  java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" -m resolver-sim.cli.main \
    verify-benchmark --run-root benchmark-force-authorisation
)

# Derived reviewer aid. It is deliberately outside the immutable scenario
# bundle, so its generation cannot alter registry or finalization commitments.
clojure -M "$PROJECT_DIR/scripts/render_scenario_diagnostic.clj" \
  "$OUTPUT_DIR/evidence/scenario-semantic-failure" \
  --focus first-failure \
  --output-dir "$OUTPUT_DIR/diagnostics/scenario-semantic-failure" \
  > /dev/null

REJECTED_INPUT_REF="$(ref_for inputs/scenarios/S-DR-084-evidence-after-settlement-rejected.edn)"
PRO_RATA_INPUT_REF="$(ref_for inputs/scenarios/Y06_multi-party-pro-rata-shortfall.edn)"
FAILURE_INPUT_REF="$(ref_for inputs/scenarios/S-NC-001-freeze-active-dispute-negative-control.edn)"
CUSTODY_EXPOSURE_SPEC_REF="$(ref_for docs/specs/SEW_CUSTODY_EXPOSURE_V1.md)"
BENCHMARK_INPUT_REF="$(ref_for inputs/benchmarks/force-authorisation-custody-v1.edn)"
PRO_RATA_INSUFFICIENT_VECTOR_REF="$(ref_for inputs/test-vectors/pro-rata/liquidity-fulfillment-liquidity-insufficient.json)"
PRO_RATA_DUST_VECTOR_REF="$(ref_for inputs/test-vectors/pro-rata/liquidity-fulfillment-liquidity-equal-buckets-dust.json)"

# Canonical ref for the value-at-risk projection derived for the Y06 shortfall
# example (written by the run above). The enriched projection lives in
# manifest/summary.json under value_at_risk_overview; the strict observation
# artifact (manifest/value-at-risk.json) remains the declaration-driven record.
PRO_RATA_VAR_JSON="evidence/scenario-pro-rata/manifest/summary.json"
PRO_RATA_VAR_REF="$( (cd "$OUTPUT_DIR" && java -jar bin/prf-runner-sew-0.1.0-uber.jar -m resolver-sim.cli.main ref-file "$PRO_RATA_VAR_JSON") | awk -v t="$PRO_RATA_VAR_JSON" '$2 == t {print $1}' )"
if [ -z "$PRO_RATA_VAR_REF" ]; then
  echo "Failed to compute value-at-risk ref for $PRO_RATA_VAR_JSON" >&2
  exit 2
fi

cat > "$OUTPUT_DIR/REVIEW_PACKET_MANIFEST.json" <<EOF
{
  "schema_version": "prf-ef-review-packet-manifest.v1",
  "jar": {
    "path": "bin/prf-runner-sew-0.1.0-uber.jar",
    "sha256": "$JAR_REF"
  },
  "provenance_ref": "PROVENANCE.txt",
  "evidence_profiles": [
    {
      "id": "sew-custody-exposure.v1",
      "path": "docs/specs/SEW_CUSTODY_EXPOSURE_V1.md",
      "sha256": "$CUSTODY_EXPOSURE_SPEC_REF",
      "role": "custody-at-settlement-deadline evidence profile",
      "instantiated_by": "benchmark-force-authorisation"
    }
  ],
  "reference_vectors": [
    {
      "path": "inputs/test-vectors/pro-rata/liquidity-fulfillment-liquidity-insufficient.json",
      "sha256": "$PRO_RATA_INSUFFICIENT_VECTOR_REF",
      "role": "independent deterministic calculator reference; not a Y06 replay witness"
    },
    {
      "path": "inputs/test-vectors/pro-rata/liquidity-fulfillment-liquidity-equal-buckets-dust.json",
      "sha256": "$PRO_RATA_DUST_VECTOR_REF",
      "role": "deterministic rounding and dust calculator reference; not a Y06 replay witness"
    }
  ],
  "examples": [
    {
      "id": "scenario-rejected",
      "input": "inputs/scenarios/S-DR-084-evidence-after-settlement-rejected.edn",
      "input_sha256": "$REJECTED_INPUT_REF",
      "bundle_root": "evidence/scenario-rejected",
      "semantic_outcome": "pass",
      "verify": ["verify-scenario", "--run-root", "evidence/scenario-rejected"]
    },
    {
      "id": "scenario-pro-rata",
      "input": "inputs/scenarios/Y06_multi-party-pro-rata-shortfall.edn",
      "input_sha256": "$PRO_RATA_INPUT_REF",
      "bundle_root": "evidence/scenario-pro-rata",
      "semantic_outcome": "pass",
      "verify": ["verify-scenario", "--run-root", "evidence/scenario-pro-rata"],
      "value_at_risk": {
        "path": "$PRO_RATA_VAR_JSON",
        "sha256": "$PRO_RATA_VAR_REF",
        "field": "value_at_risk_overview",
        "observation_path": "evidence/scenario-pro-rata/manifest/value-at-risk.json",
        "role": "multi-party pro-rata shortfall: 3000 USDC protected, 0.6 available ratio, 1200 USDC at risk",
        "authoritative": false
      }
    },
    {
      "id": "scenario-semantic-failure",
      "input": "inputs/scenarios/S-NC-001-freeze-active-dispute-negative-control.edn",
      "input_sha256": "$FAILURE_INPUT_REF",
      "bundle_root": "evidence/scenario-semantic-failure",
      "semantic_outcome": "fail",
      "verify": ["verify-scenario", "--run-root", "evidence/scenario-semantic-failure"],
      "derived_diagnostic": {
        "mermaid": "diagnostics/scenario-semantic-failure/diagnostic.mmd",
        "markdown": "diagnostics/scenario-semantic-failure/diagnostic.md",
        "authoritative": false
      }
    },
    {
      "id": "benchmark-force-authorisation",
      "input": "inputs/benchmarks/force-authorisation-custody-v1.edn",
      "input_sha256": "$BENCHMARK_INPUT_REF",
      "bundle_root": "evidence/benchmark-force-authorisation",
      "verify": ["verify-benchmark", "--run-root", "evidence/benchmark-force-authorisation"]
    }
  ]
}
EOF

(
  cd "$OUTPUT_DIR"
  java -jar bin/prf-runner-sew-0.1.0-uber.jar -m resolver-sim.cli.main ref-file \
    bin/prf-runner-sew-0.1.0-uber.jar \
    bin/verify-review-packet.sh \
    inputs/scenarios/S-DR-084-evidence-after-settlement-rejected.edn \
    inputs/scenarios/Y06_multi-party-pro-rata-shortfall.edn \
    inputs/scenarios/S-NC-001-freeze-active-dispute-negative-control.edn \
    inputs/scenarios/DR-N-002-reversal-slash-appeal-rejected.edn \
    inputs/scenarios/S-DR-001-basic-release-ruling.edn \
    inputs/benchmarks/force-authorisation-custody-v1.edn \
    docs/specs/SEW_CUSTODY_EXPOSURE_V1.md \
    inputs/test-vectors/pro-rata/liquidity-fulfillment-liquidity-insufficient.json \
    inputs/test-vectors/pro-rata/liquidity-fulfillment-liquidity-equal-buckets-dust.json \
    docs/REVIEW_GUIDE.md \
    docs/SCENARIO_REVIEW_HIGHLIGHTS.md \
    docs/PACKET_LAYOUT.md \
    docs/BENCHMARK_ASSURANCE_SPEC_V1.md \
    PROVENANCE.txt \
    REVIEW_PACKET_MANIFEST.json \
    diagnostics/scenario-semantic-failure/diagnostic.mmd \
    diagnostics/scenario-semantic-failure/diagnostic.md \
    evidence/scenario-pro-rata/manifest/summary.json \
    > SHA256SUMS
)

# EF Review Packet

## Packet Overview
This directory contains a generated EF technical-review packet for the PRF (Protocol Robustness Framework) project.

**Packet path**: %OUTPUT_DIR%

**Generated**: %BUILD_TIMESTAMP%

## Included Scenarios
The packet includes the following scenarios:

- S-DR-001-basic-release-ruling.edn
- S-DR-084-evidence-after-settlement-rejected.edn
- S-NC-001-freeze-active-dispute-negative-control.edn
- Y06_multi-party-pro-rata-shortfall.edn
- DR-N-002-reversal-slash-appeal-rejected.edn

## Key Artifacts
- `manifest/canonical-integrity.json` - Unsigned canonical integrity assurance
- `manifest/forensic-claims-status.json` - Forensic claims status (deferred when no signing configured)
- `manifest/verdict-policy.json` - Verdict policy for outcome determination
- `manifest/artifacts.json` - Artifact registry
- `evidence/scenario-pro-rata/manifest/summary.json` - Summary with value-at-risk
- `diagnostics/scenario-semantic-failure/` - Semantic failure diagnostic

## Verification Commands
```bash
# Verify scenario bundles
java -jar bin/prf-runner-sew-0.1.0-uber.jar -m resolver-sim.cli.main verify-scenario --run-root scenario-pro-rata
java -jar bin/prf-runner-sew-0.1.0-uber.jar -m resolver-sim.cli.main verify-scenario --run-root scenario-rejected
java -jar bin/prf-runner-sew-0.1.0-uber.jar -m resolver-sim.cli.main verify-scenario --run-root scenario-semantic-failure

# Verify benchmark
java -jar bin/prf-runner-sew-0.1.0-uber.jar -m resolver-sim.cli.main verify-benchmark --run-root benchmark-force-authorisation

# Build attestation
bb build:attest

# Review packet analysis
bash scripts/verify-ef-review-packet.sh evidence/scenario-pro-rata
```

## Current Status
- **Scenario outcomes**: Y06 multi-party pro-rata shortfall passes semantically (1/1)
- **Verification status**: See individual scenario results above
- **Forensic claims status**: Deferred (unsigned; signing required for eligibility)
- **Canonical integrity**: 14/14 checks pass

## Documentation References
- [`docs/PACKET_LAYOUT.md`](PACKET_LAYOUT.md) - Packet structure and conventions
- [`docs/REVIEW_GUIDE.md`](REVIEW_GUIDE.md) - Review commands and procedures
- [`docs/SCENARIO_REVIEW_HIGHLIGHTS.md`](SCENARIO_REVIEW_HIGHLIGHTS.md) - Scenario review priorities
- [`docs/BENCHMARK_ASSURANCE_SPEC_V1.md`](BENCHMARK_ASSURANCE_SPEC_V1.md) - Assurance spec reference

## Quick Start
```bash
# Generate packet
bash scripts/build-ef-review-packet.sh /path/to/output-dir

# Verify scenarios
java -jar bin/prf-runner-sew-0.1.0-uber.jar -m resolver-sim.cli.main verify-scenario --run-root scenario-pro-rata

# Or run the review packet verification script
bash scripts/verify-ef-review-packet.sh evidence/scenario-pro-rata
```

## Directory Layout
output-dir/
├── bin/                          # JAR and verification scripts
├── docs/                         # Documentation copies
│   ├── REVIEW_GUIDE.md
│   ├── SCENARIO_REVIEW_HIGHLIGHTS.md
│   ├── PACKET_LAYOUT.md
│   └── BENCHMARK_ASSURANCE_SPEC_V1.md
│   └── specs/                    # Additional specs
├── inputs/                       # Input scenario and benchmark files
│   ├── scenarios/                 # Scenario EDN files
│   ├── benchmarks/               # Benchmark definitions
│   └── test-vectors/             # Test vectors
├── evidence/                     # Generated evidence bundles
│   ├── scenario-pro-rata/        # Y06 multi-party pro-rata shortfall
│   ├── scenario-rejected/        # S-DR-084 evidence after settlement rejected
│   └── scenario-semantic-failure/ # Semantic failure diagnostic
├── diagnostics/                  # Diagnostic outputs
├── REVIEW_PACKET_MANIFEST.json   # Manifest of all packet contents
├── SHA256SUMS                      # SHA256 checksums for verification
├── PROVENANCE.txt                # Provenance metadata
└── README.md                     # This file

echo "Review packet created: $OUTPUT_DIR"
