#!/usr/bin/env bash
# Compatibility entry point for the authoritative packaged-JAR acceptance gate.
# The legacy benchmark-only artifact no longer exists; keep this path so callers
# use the supported PRF and Sew distribution smoke rather than a permissive,
# obsolete portability check.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"

exec bash "$PROJECT_DIR/scripts/portability-smoke-test.sh"
