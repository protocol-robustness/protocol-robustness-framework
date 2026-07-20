#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$(cd "$ROOT/../.." && pwd)"
ARTIFACT_DIR="$(python3 -c "from scripts.evidence_config import EvidenceConfig; print(EvidenceConfig().artifact_dir)" 2>/dev/null)" || ARTIFACT_DIR="results/test-artifacts"
mkdir -p "$ARTIFACT_DIR"
clojure -M:reference-validation --suite-root "$ROOT" --protocol yield "$@"
