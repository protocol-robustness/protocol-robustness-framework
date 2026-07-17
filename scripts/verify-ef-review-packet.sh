#!/usr/bin/env bash
# Verify a generated EF technical-review packet without a source checkout.
# Usage: bash scripts/verify-ef-review-packet.sh /path/to/review-packet

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 REVIEW_PACKET_DIR" >&2
  exit 2
fi

PACKET_DIR="$1"
JAR="$PACKET_DIR/bin/prf-runner-sew-0.1.0-uber.jar"
CHECKSUMS="$PACKET_DIR/SHA256SUMS"
MANIFEST="$PACKET_DIR/REVIEW_PACKET_MANIFEST.json"

for required in "$JAR" "$CHECKSUMS" "$MANIFEST"; do
  if [ ! -f "$required" ]; then
    echo "Review packet is missing required file: $required" >&2
    exit 2
  fi
done

(
  cd "$PACKET_DIR"
  sha256sum --check --strict SHA256SUMS

  java -jar "$JAR" verify-scenario --run-root evidence/scenario-rejected
  java -jar "$JAR" verify-scenario --run-root evidence/scenario-pro-rata
  java -jar "$JAR" verify-scenario --run-root evidence/scenario-semantic-failure
  java -jar "$JAR" verify-benchmark --run-root evidence/benchmark-force-authorisation
)

echo "PASS: EF review packet checksums and evidence bundles verified"
