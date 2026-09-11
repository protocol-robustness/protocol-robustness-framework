#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLAJAR="/home/user/Desktop/ef-out/bin/prf-runner-sew-0.1.0-uber.jar"

if [ $# -lt 1 ]; then
  echo "Usage: $0 <run-root-dir>"
  echo "  run-root-dir: evidence/scenario-pro-rata-signed (default)"
  exit 1
fi

RUN_ROOT_DIR="${1:-evidence/scenario-pro-rata-signed}"

echo "=== Forensic Claims Signing Runner ==="
echo "Run root directory: $RUN_ROOT_DIR"
echo ""

# Change to the script directory so relative paths work
cd "$SCRIPT_DIR"

# Run the Clojure runner
echo "Running Clojure runner..."
cd "$SCRIPT_DIR"
clojure -M -M /home/user/Code/.workspaces/agent-c/scripts/sign-forensic-runner.clj "$RUN_ROOT_DIR"