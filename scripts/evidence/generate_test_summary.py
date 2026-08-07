#!/usr/bin/env python3
"""Write the concise, machine-readable result for scripts/test.sh.

Emits both:
  test-summary.json   schema: test-summary.v2
  test-run.json       schema: test-run.v1   (run manifest)

The unified test-artifacts.json registry is produced afterwards by
scripts/evidence/consolidate_test_artifacts.py.
"""

from __future__ import annotations

import csv
import json
import pathlib
import sys
from datetime import datetime, timezone


def main(argv: list[str]) -> int:
    if len(argv) != 8:
        print(
            "usage: generate_test_summary.py ARTIFACT_DIR RUN_ID FAILURES MODE "
            "SUMMARY_FILE RUN_MANIFEST_FILE REGISTRY_FILE CLAIMABLE_FILE",
            file=sys.stderr,
        )
        return 2

    (
        artifact_dir_s,
        run_id,
        failures_s,
        mode,
        summary_file_s,
        run_manifest_file_s,
        _registry_file,
        _claimable_file,
    ) = argv
    artifact_dir = pathlib.Path(artifact_dir_s)
    summary_file = pathlib.Path(summary_file_s)
    run_manifest_file = pathlib.Path(run_manifest_file_s)
    failures = int(failures_s)
    targets_file = artifact_dir / f".targets-{run_id}.csv"

    targets = []
    if targets_file.exists():
        with targets_file.open(newline="", encoding="utf-8") as handle:
            for target, status, exit_code, duration_ms, log_file in csv.reader(handle):
                targets.append(
                    {
                        "target": target,
                        "status": status,
                        "exit_code": int(exit_code),
                        "duration_ms": int(duration_ms),
                        "log_file": log_file,
                    }
                )

    created_at = datetime.now(timezone.utc).isoformat()
    summary = {
        "schema_version": "test-summary.v2",
        "run_id": run_id,
        "mode": mode,
        "created_at": created_at,
        "overall_status": "pass" if failures == 0 else "fail",
        "failure_count": failures,
        "target_count": len(targets),
        "targets": targets,
        "target_index": str(targets_file),
    }
    summary_file.parent.mkdir(parents=True, exist_ok=True)
    summary_file.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote machine-readable test summary: {summary_file}")

    # Run manifest (test-run.v1).  This is the dependency target for
    # test-summary in the artifact registry, so it must exist before the
    # consolidation step builds test-artifacts.json.
    run_manifest = {
        "schema_version": "test-run.v1",
        "run_id": run_id,
        "created_at": created_at,
        "framework": {"name": "protocol-robustness-framework-test-runner",
                      "version": "0.1.0"},
        "model": {},
        "suite": {"mode": mode},
        "capabilities_resolved": {},
        "artifacts": {},
        "overall_status": summary["overall_status"],
        "failure_count": failures,
        "target_count": len(targets),
        "targets": targets,
    }
    run_manifest_file.parent.mkdir(parents=True, exist_ok=True)
    run_manifest_file.write_text(json.dumps(run_manifest, indent=2) + "\n",
                                 encoding="utf-8")
    print(f"Wrote run manifest: {run_manifest_file}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
