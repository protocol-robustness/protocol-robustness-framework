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
  echo "jar_sha256=$(sha256sum "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" | awk '{print $1}')"
} > "$OUTPUT_DIR/PROVENANCE.txt"

(
  cd "$OUTPUT_DIR/evidence"
  java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" \
    run-scenario classpath:scenarios/edn/S-DR-084-evidence-after-settlement-rejected.edn \
    --run-root scenario-rejected
  java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" \
    verify-scenario --run-root scenario-rejected
  java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" \
    run-scenario classpath:scenarios/edn/Y06_multi-party-pro-rata-shortfall.edn \
    --run-root scenario-pro-rata
  java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" \
    verify-scenario --run-root scenario-pro-rata
  # This scenario reaches an unsuppressed invariant violation. Its non-zero
  # semantic outcome is expected; the completed package must still verify.
  if java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" \
       run-scenario classpath:scenarios/edn/DR-N-002-reversal-slash-appeal-rejected.edn \
       --run-root scenario-semantic-failure; then
    echo "Expected DR-N-002 to conclude with a semantic failure" >&2
    exit 1
  fi
  java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" \
    verify-scenario --run-root scenario-semantic-failure
  java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" \
    run-benchmark force-authorisation-custody-v1 \
    --run-root benchmark-force-authorisation
  java -jar "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" \
    verify-benchmark --run-root benchmark-force-authorisation
)

# Derived reviewer aid. It is deliberately outside the immutable scenario
# bundle, so its generation cannot alter registry or finalization commitments.
clojure -M "$PROJECT_DIR/scripts/render_scenario_diagnostic.clj" \
  "$OUTPUT_DIR/evidence/scenario-semantic-failure" \
  --focus first-failure \
  --output-dir "$OUTPUT_DIR/diagnostics/scenario-semantic-failure" \
  > /dev/null

JAR_SHA256="$(sha256sum "$OUTPUT_DIR/bin/prf-runner-sew-0.1.0-uber.jar" | awk '{print $1}')"
REJECTED_INPUT_SHA256="$(sha256sum "$OUTPUT_DIR/inputs/scenarios/S-DR-084-evidence-after-settlement-rejected.edn" | awk '{print $1}')"
PRO_RATA_INPUT_SHA256="$(sha256sum "$OUTPUT_DIR/inputs/scenarios/Y06_multi-party-pro-rata-shortfall.edn" | awk '{print $1}')"
FAILURE_INPUT_SHA256="$(sha256sum "$OUTPUT_DIR/inputs/scenarios/DR-N-002-reversal-slash-appeal-rejected.edn" | awk '{print $1}')"
CUSTODY_EXPOSURE_SPEC_SHA256="$(sha256sum "$OUTPUT_DIR/docs/specs/SEW_CUSTODY_EXPOSURE_V1.md" | awk '{print $1}')"
BENCHMARK_INPUT_SHA256="$(sha256sum "$OUTPUT_DIR/inputs/benchmarks/force-authorisation-custody-v1.edn" | awk '{print $1}')"
PRO_RATA_INSUFFICIENT_VECTOR_SHA256="$(sha256sum "$OUTPUT_DIR/inputs/test-vectors/pro-rata/liquidity-fulfillment-liquidity-insufficient.json" | awk '{print $1}')"
PRO_RATA_DUST_VECTOR_SHA256="$(sha256sum "$OUTPUT_DIR/inputs/test-vectors/pro-rata/liquidity-fulfillment-liquidity-equal-buckets-dust.json" | awk '{print $1}')"

cat > "$OUTPUT_DIR/REVIEW_PACKET_MANIFEST.json" <<EOF
{
  "schema_version": "prf-ef-review-packet-manifest.v1",
  "jar": {
    "path": "bin/prf-runner-sew-0.1.0-uber.jar",
    "sha256": "sha256:$JAR_SHA256"
  },
  "provenance_ref": "PROVENANCE.txt",
  "evidence_profiles": [
    {
      "id": "sew-custody-exposure.v1",
      "path": "docs/specs/SEW_CUSTODY_EXPOSURE_V1.md",
      "sha256": "sha256:$CUSTODY_EXPOSURE_SPEC_SHA256",
      "role": "custody-at-settlement-deadline evidence profile",
      "instantiated_by": "benchmark-force-authorisation"
    }
  ],
  "reference_vectors": [
    {
      "path": "inputs/test-vectors/pro-rata/liquidity-fulfillment-liquidity-insufficient.json",
      "sha256": "sha256:$PRO_RATA_INSUFFICIENT_VECTOR_SHA256",
      "role": "independent deterministic calculator reference; not a Y06 replay witness"
    },
    {
      "path": "inputs/test-vectors/pro-rata/liquidity-fulfillment-liquidity-equal-buckets-dust.json",
      "sha256": "sha256:$PRO_RATA_DUST_VECTOR_SHA256",
      "role": "deterministic rounding and dust calculator reference; not a Y06 replay witness"
    }
  ],
  "examples": [
    {
      "id": "scenario-rejected",
      "input": "inputs/scenarios/S-DR-084-evidence-after-settlement-rejected.edn",
      "input_sha256": "sha256:$REJECTED_INPUT_SHA256",
      "bundle_root": "evidence/scenario-rejected",
      "semantic_outcome": "pass",
      "verify": ["verify-scenario", "--run-root", "evidence/scenario-rejected"]
    },
    {
      "id": "scenario-pro-rata",
      "input": "inputs/scenarios/Y06_multi-party-pro-rata-shortfall.edn",
      "input_sha256": "sha256:$PRO_RATA_INPUT_SHA256",
      "bundle_root": "evidence/scenario-pro-rata",
      "semantic_outcome": "pass",
      "verify": ["verify-scenario", "--run-root", "evidence/scenario-pro-rata"]
    },
    {
      "id": "scenario-semantic-failure",
      "input": "inputs/scenarios/DR-N-002-reversal-slash-appeal-rejected.edn",
      "input_sha256": "sha256:$FAILURE_INPUT_SHA256",
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
      "input_sha256": "sha256:$BENCHMARK_INPUT_SHA256",
      "bundle_root": "evidence/benchmark-force-authorisation",
      "verify": ["verify-benchmark", "--run-root", "evidence/benchmark-force-authorisation"]
    }
  ]
}
EOF

(
  cd "$OUTPUT_DIR"
  sha256sum \
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
    docs/BENCHMARK_ASSURANCE_SPEC_V1.md \
    PROVENANCE.txt \
    REVIEW_PACKET_MANIFEST.json \
    diagnostics/scenario-semantic-failure/diagnostic.mmd \
    diagnostics/scenario-semantic-failure/diagnostic.md \
    > SHA256SUMS
)

echo "Review packet created: $OUTPUT_DIR"
