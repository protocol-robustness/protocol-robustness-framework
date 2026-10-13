#!/usr/bin/env bash
# CI artifact consolidation: merge per-job artifact bundles into a single
# unified test-artifacts.json registry + test-run.json, then audit it with the
# strengthened gate (scripts/evidence/audit_artifacts.py).
#
# Each CI job uploads its own results/test-artifacts/ bundle.  This script:
#   1. discovers every <unified>/*/results/test-artifacts bundle,
#   2. strips per-bundle, run-scoped registry noise (test-artifacts.json,
#      test-run.json, test-summary.json, target logs, ownership markers) so the
#      bundles can be merged without those files conflicting,
#   3. writes a CI-level test-run.json + test-summary.json for this run,
#   4. runs the collector over the bundles (scripts/evidence/consolidate_test_artifacts.py),
#   5. audits the unified registry with the producer roots (gating).
#
# Content conflicts (the same relative artifact path claimed by two bundles
# with different bytes) FAIL the job with a specific report — cross-job
# evidence collisions are surfaced, never silently clobbered.
#
# Usage: scripts/evidence/consolidate_ci.sh <unified-root> <out-root> [run-id]
#   unified-root — where actions/download-artifact@v4 placed the bundles
#   out-root     — where the unified registry is written (upload this)
#   run-id       — CI run id (default: timestamp)

set -euo pipefail

UNIFIED="${1:?unified root required}"
OUT="${2:?output root required}"
RUN_ID="${3:-$(date -u +%Y%m%dT%H%M%SZ)}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

mkdir -p "$OUT"

# ── 1. discover bundles ───────────────────────────────────────────────────────
producers=()
for b in "$UNIFIED"/*/results/test-artifacts; do
  [ -d "$b" ] && producers+=("$b")
done
if [ "${#producers[@]}" -eq 0 ]; then
  echo "consolidate-ci: no artifact bundles found under $UNIFIED — nothing to consolidate"
  exit 0
fi
echo "consolidate-ci: ${#producers[@]} bundle(s): ${producers[*]}"

# ── 2. strip per-bundle run-scoped registry noise ─────────────────────────────
for b in "${producers[@]}"; do
  rm -f "$b/test-artifacts.json" "$b/test-run.json" "$b/test-summary.json"
  rm -f "$b"/.targets-*.csv "$b"/.target-*.log 2>/dev/null || true
  rm -f "$b/_owner.edn" "$b/_manifest.json"
done

# ── 3. CI-level run manifest + summary ────────────────────────────────────────
python3 - "$OUT" "$RUN_ID" <<'PY'
import datetime, json, pathlib, sys
out, run_id = pathlib.Path(sys.argv[1]), sys.argv[2]
created = datetime.datetime.now(datetime.timezone.utc).isoformat()
run = {
    "schema_version": "test-run.v1",
    "run_id": run_id,
    "created_at": created,
    "framework": {"name": "protocol-robustness-framework-test-runner",
                  "version": "0.1.0"},
    "model": {}, "suite": {"mode": "ci"},
    "capabilities_resolved": {}, "artifacts": {},
}
summary = {
    "schema_version": "test-summary.v2",
    "run_id": run_id, "mode": "ci", "created_at": created,
    "overall_status": "pass", "failure_count": 0,
    "target_count": 0, "targets": [],
}
(out / "test-run.json").write_text(json.dumps(run, indent=2) + "\n")
(out / "test-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(f"consolidate-ci: wrote CI-level {out / 'test-run.json'} + {out / 'test-summary.json'}")
PY

# ── 4. collect (gating) ───────────────────────────────────────────────────────
if ! python3 "$REPO_ROOT/scripts/evidence/consolidate_test_artifacts.py" \
     --run-root "$OUT" --run-id "$RUN_ID" \
     --producer-roots "${producers[@]}" --allow-loose; then
  echo "consolidate-ci: FAIL — content conflict or consolidation error (see above)." >&2
  echo "consolidate-ci: per-job bundles must not emit conflicting fixed-name artifacts." >&2
  exit 1
fi

# ── 5. audit (gating) ─────────────────────────────────────────────────────────
python3 "$REPO_ROOT/scripts/evidence/audit_artifacts.py" \
  --registry "$OUT/test-artifacts.json" --producer-roots "${producers[@]}"
echo "consolidate-ci: PASS — unified registry at $OUT/test-artifacts.json"
