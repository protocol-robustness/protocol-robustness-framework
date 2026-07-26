#!/usr/bin/env bash
# Acquire an exclusive flock on results/.test-artifact.lock, then execute the
# provided command.  Blocks until the lock is available.  Auto-releases when
# the locked process exits or dies.
#
# Prevents cross-process clobbering of results/test-artifacts/ and adjacent
# shared test output paths when multiple CI jobs or ad-hoc tasks write to
# them concurrently (e.g. nb-ok vs test:notebooks vs test:framework).
#
# Does NOT address:
#   - Slow scenario replay times (SPEDS, invariants).
#   - In-JVM alter-var-root global mutation hazards in test code.
#
# Usage:
#   scripts/with-test-artifact-lock.sh <command> [args...]
#
# Example:
#   scripts/with-test-artifact-lock.sh clojure -M:with-sew -e '(println "hi")'
#
# The wrapped command's exit code is preserved.
#
# If the command hangs, a previous process may still hold the lock.
# Check with: lsof results/.test-artifact.lock
# Remove stale lock with: rm -f results/.test-artifact.lock

set -euo pipefail

LOCK_FILE="results/.test-artifact.lock"

mkdir -p "$(dirname "${LOCK_FILE}")"

# Try to acquire the lock in non-blocking mode first to report helpful messages.
if ! flock -n "${LOCK_FILE}" true 2>/dev/null; then
  echo " Waiting for test artifact lock (${LOCK_FILE})..."
  echo " If no other test is running, the lock may be stale."
  echo " Remove with: rm -f ${LOCK_FILE}"
fi

# Acquire lock with a 5-minute timeout to prevent indefinite hangs
exec flock -w 300 "${LOCK_FILE}" "$@"
