#!/usr/bin/env bash
# Compute the conformance vector-set root from the pinned PRF JAR.
#
# The vector-set root identifies the exact corpus the native Rust kernel was
# tested against. It is a SHA-256 over the vector suite ordered by vector_id,
# committing each vector's vector_id and its full expected public-value
# projection. The artifact lock stores this root so the executable and the
# corpus it was validated against are bound together.
#
# Prints: <hex-root> <vector-count>
set -euo pipefail

DEMO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PRF_RUNNER="$DEMO_ROOT/prf-runner/run-prf.sh"

"$PRF_RUNNER" allocation vectors 2>/dev/null | python3 -c '
import json, sys, hashlib

vectors = json.load(sys.stdin)
ordered = sorted(vectors, key=lambda v: v["vector_id"])

h = hashlib.sha256()
for v in ordered:
    h.update(v["vector_id"].encode("utf-8"))
    h.update(b"\x00")
    h.update(json.dumps(v["expected"], sort_keys=True, separators=(",", ":")).encode("utf-8"))
    h.update(b"\x00")

print(h.hexdigest(), len(ordered))
'
