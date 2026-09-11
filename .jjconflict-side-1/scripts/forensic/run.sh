#!/usr/bin/env bash
# Forensic run wrapper — optionally wraps execution in private tmpfs via unshare.
#
# Without --private-tmpfs: delegates directly to run.py with --isolation shared-filesystem.
# With --private-tmpfs:    creates private mount namespace, mounts tmpfs, runs inside,
#                          copies output to ~/prf-runs/, records isolation=private-tmpfs.
#
# Usage: bash scripts/forensic/run.sh [--private-tmpfs] [other run.py args]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PRF_RUNS="${PRF_RUNS_ROOT:-${HOME}/prf-runs}"
PRIVATE_TMPFS=false
PYTHON_ARGS=()

for arg in "$@"; do
  if [ "$arg" = "--private-tmpfs" ]; then
    PRIVATE_TMPFS=true
  else
    PYTHON_ARGS+=("$arg")
  fi
done

if [ "$PRIVATE_TMPFS" = false ]; then
  exec python3 "${SCRIPT_DIR}/run.py" --isolation shared-filesystem "${PYTHON_ARGS[@]}"
fi

# ── Private tmpfs mode ───────────────────────────────────────────────

WS_DIR="$(mktemp -d -t prf-forensic-run-XXXXX)"
trap 'echo "  cleaning up tmpfs workspace" >&2; rm -rf "$WS_DIR"' EXIT

echo "  setting up private tmpfs: $WS_DIR" >&2

if ! unshare --user --map-root-user -m mount -t tmpfs tmpfs "$WS_DIR" 2>/dev/null; then
  echo "  private tmpfs not available (unshare/mount may require user namespace support)" >&2
  echo "  falling back to shared filesystem" >&2
  exec python3 "${SCRIPT_DIR}/run.py" --isolation shared-filesystem "${PYTHON_ARGS[@]}"
fi

# Run inside private mount namespace
python3 "${SCRIPT_DIR}/run.py" --isolation private-tmpfs --output-base "$WS_DIR" "${PYTHON_ARGS[@]}"
RUN_EXIT=$?

# Find the run directory inside the workspace
RUN_DIR=$(ls "$WS_DIR" 2>/dev/null | head -1)
if [ -n "$RUN_DIR" ] && [ -d "$WS_DIR/$RUN_DIR" ]; then
  mkdir -p "$PRF_RUNS/$RUN_DIR"
  cp -a "$WS_DIR/$RUN_DIR/." "$PRF_RUNS/$RUN_DIR/"
  echo "  copied forensic run to $PRF_RUNS/$RUN_DIR" >&2
fi

exit $RUN_EXIT
