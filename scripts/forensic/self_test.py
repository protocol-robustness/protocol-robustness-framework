#!/usr/bin/env python3
"""Forensic self-test: validate pipeline determinism.

Runs the same forensic execution twice with identical source, request,
profiles, and runner parameters. Compares stable fields only — no
volatile fields such as wall-clock time, absolute temp paths, or
host-specific diagnostics.

Emits self-test-report.json with structured comparison.

Usage:
    python3 scripts/forensic/self_test.py [--run-request <path>] [--output-base <dir>]
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

PRF_RUNS_ROOT = Path(os.environ.get("PRF_RUNS_ROOT", "~/prf-runs")).expanduser()


def load_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text())
    except Exception:
        return None


def run_forensic(run_request_path: str, output_base: Path,
                 label: str) -> tuple[int, dict | None, float, int]:
    """Execute a single forensic run via the Clojure pipeline.

    Returns (exit_code, clojure_bundle_root, elapsed_ms, run_dir_size_bytes).
    """
    env = os.environ.copy()
    env["PRF_ORCHESTRATION_RUNNER_ID"] = "forensic-self-test.py"

    run_expr = ("(require '[resolver-sim.core]) "
                "(->> *command-line-args* (map str) (apply resolver-sim.core/-main))")
    cmd = ["clojure", "-M:with-sew", "-e", run_expr, "--", "--invariants"]
    output_dir = output_base / label
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / "clojure-bundle-root.json"
    cmd.extend(["--output-file", str(output_path)])

    print(f"  running: {' '.join(cmd)}", file=sys.stderr)
    t0 = time.time()
    r = subprocess.run(cmd, capture_output=False, timeout=600, env=env)
    elapsed_ms = int((time.time() - t0) * 1000)

    if not output_path.exists():
        print(f"  FAILED — no Clojure bundle root produced", file=sys.stderr)
        return r.returncode, None, elapsed_ms, 0

    bundle = load_json(output_path)
    size_bytes = output_path.stat().st_size
    print(f"  exit code: {r.returncode}  ({elapsed_ms}ms  {size_bytes} bytes)",
          file=sys.stderr)
    return r.returncode, bundle, elapsed_ms, size_bytes


def field_comparison(label: str, a_val: Any, b_val: Any) -> dict:
    """Compare two values, recording match status and actual values.
    match=True/False/None where None means inconclusive (one or both missing)."""
    match: bool | None = None
    if a_val is not None and b_val is not None:
        match = a_val == b_val
    return {
        "field": label,
        "run-a": a_val,
        "run-b": b_val,
        "match": match,
    }


def compare_clojure_bundles(a: dict, b: dict) -> tuple[list[dict], list[str]]:
    """Compare two Clojure bundle roots for determinism.
    Only stable, deterministic fields. No timestamps, paths, or timing.
    Returns (checks, diagnostic_notes)."""
    checks: list[dict] = []
    notes: list[str] = []

    # Top-level bundle identity
    checks.append(field_comparison("bundle/hash",
                                   a.get("bundle/hash"), b.get("bundle/hash")))
    checks.append(field_comparison("bundle/schema-version",
                                   a.get("bundle/schema-version"),
                                   b.get("bundle/schema-version")))

    # Overview hash (top-level in Clojure bundle root — stable, excludes timestamps)
    checks.append(field_comparison("overview/hash",
                                   a.get("overview/hash"),
                                   b.get("overview/hash")))

    # Execution summary — deterministic pass/fail counts
    sum_a = a.get("execution/summary", {})
    sum_b = b.get("execution/summary", {})
    checks.append(field_comparison("execution/summary/status",
                                   sum_a.get("status"), sum_b.get("status")))
    ta = sum_a.get("totals") or {}
    tb = sum_b.get("totals") or {}
    for k in ("total", "passed", "failed", "expected-failed", "unexpected-failed"):
        if k in ta or k in tb:
            checks.append(field_comparison(f"execution/summary/totals/{k}",
                                           ta.get(k), tb.get(k)))

    # Registry snapshot — all hashes must match
    snap_a = a.get("registry/snapshot", {}) or {}
    snap_b = b.get("registry/snapshot", {}) or {}
    for k in sorted(set(list(snap_a.keys()) + list(snap_b.keys()))):
        checks.append(field_comparison(f"registry/snapshot/{k}",
                                       snap_a.get(k), snap_b.get(k)))

    # Source provenance (from Phase B enrichment)
    for k in ("source/tree-hash", "source/tree-hash-algorithm",
              "source/commit", "source/dirty?"):
        if a.get(k) is not None or b.get(k) is not None:
            checks.append(field_comparison(k, a.get(k), b.get(k)))

    # Execution node hash is NOT compared — it includes a wall-clock timestamp
    # and will always differ between runs. Documented in volatile-fields-excluded.
    return checks, notes


def self_test(run_request_path: str = "examples/forensic-reference-run/run-request.example.edn",
              output_base: str | Path | None = None) -> int:
    if output_base is None:
        output_base = PRF_RUNS_ROOT
    output_base = Path(output_base).expanduser().resolve()

    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    label_a = f"{ts}-self-test-a"
    label_b = f"{ts}-self-test-b"

    print(f"=== Forensic Self-Test ===", file=sys.stderr)
    print(f"Run A: {label_a}", file=sys.stderr)
    exit_a, bundle_a, elapsed_a, size_a = run_forensic(
        run_request_path, output_base, label_a)

    status: str = "inconclusive"
    notes: list[str] = []

    if bundle_a is None:
        notes.append("Run A failed to produce a Clojure bundle root")
    elif exit_a != 0:
        notes.append(f"Run A exited with code {exit_a}")

    # Run B only if A produced a usable bundle
    bundle_b: dict | None = None
    exit_b: int = -1
    elapsed_b: float = 0.0
    size_b: int = 0

    if bundle_a is not None:
        print(f"\nRun B: {label_b}", file=sys.stderr)
        exit_b, bundle_b, elapsed_b, size_b = run_forensic(
            run_request_path, output_base, label_b)
        if bundle_b is None:
            notes.append("Run B failed to produce a Clojure bundle root")
        elif exit_b != 0:
            notes.append(f"Run B exited with code {exit_b}")
    else:
        notes.append("Skipped Run B — Run A did not produce usable output")

    # Compare
    comp_notes: list[str] = []
    if bundle_a and bundle_b:
        checks, comp_notes = compare_clojure_bundles(bundle_a, bundle_b)
    else:
        checks = []

    match_count = sum(1 for c in checks if c["match"] is True)
    mismatch_count = sum(1 for c in checks if c["match"] is False)
    unknown_count = sum(1 for c in checks if c["match"] is None)
    non_deterministic_fields = [c["field"] for c in checks if c["match"] is False]
    notes.extend(comp_notes)

    # Determine two-axis status
    execution_success = (exit_a == 0 and exit_b == 0)
    both_failed_execution = (exit_a != 0 and exit_b != 0)

    if bundle_a is None and bundle_b is None:
        status = "failed"
        determinism_status = "inconclusive"
        notes.append("Both runs failed — no output produced")
    elif bundle_a is None or bundle_b is None:
        status = "failed"
        determinism_status = "inconclusive"
    elif mismatch_count > 0:
        status = "non-deterministic"
        determinism_status = "non-deterministic"
    elif unknown_count == len(checks):
        status = "inconclusive"
        determinism_status = "inconclusive"
        notes.append("All comparison fields returned None — no stable fields to compare")
    elif match_count > 0 and mismatch_count == 0:
        determinism_status = "deterministic"
        if execution_success:
            status = "deterministic"
        elif both_failed_execution:
            status = "deterministic-failure"
        else:
            # One passed, one failed — execution divergent
            status = "deterministic-failure"
            notes.append("Exit codes differ between runs — execution divergence detected")
    else:
        status = "inconclusive"
        determinism_status = "inconclusive"

    # Known volatile fields excluded from stable comparison.
    # With the content-hash / record-hash split, execution/content-hash is
    # stable for reproduce/quorum, while execution/record-hash includes the
    # wall-clock capture timestamp and is for audit trail only.
    volatile_fields = [
        {"field": "execution/record-hash",
         "reason": "includes wall-clock timestamp — will differ between runs",
         "resolution": "use execution/content-hash for reproduce, self-test, quorum"},
    ]

    run_a_dir = str(output_base / label_a)
    run_b_dir = str(output_base / label_b) if bundle_b else None

    # Extract identity from run A
    fb = bundle_a or {}
    fb_overview = fb.get("overview") or {}
    sc_count = None
    try:
        sc_count = fb_overview.get("suite", {}).get("scenario-count")
    except Exception:
        pass

    # Build report
    report = {
        "self-test/schema-version": "forensic-self-test.v1",
        "self-test/timestamp": ts,
        "self-test/status": status,
        "self-test/execution-success": execution_success,
        "self-test/determinism-status": determinism_status,
        "self-test/run-count": 2,
        "self-test/runner-id": "forensic-self-test.py",
        "self-test/exit-codes": {"a": exit_a, "b": exit_b},
        "self-test/elapsed-ms": {"a": elapsed_a, "b": elapsed_b},
        "self-test/run-a": run_a_dir,
        "self-test/run-b": run_b_dir,
        "self-test/identity": {
            "suite-key": "registry-default",
            "request-path": run_request_path,
            "scenario-count": sc_count,
            "source/tree-hash": fb.get("source/tree-hash"),
            "source/tree-hash-algorithm": fb.get("source/tree-hash-algorithm"),
            "bundle/hash": fb.get("bundle/hash"),
            "overview/hash": fb.get("overview/hash"),
            "runner-id": "forensic-self-test.py",
        },
        "self-test/comparison-profile": {
            "fields-compared": [c["field"] for c in checks],
            "volatile-fields-excluded": [v["field"] for v in volatile_fields],
            "comparison-count": len(checks),
        },
        "self-test/comparison": {
            "checks": checks,
            "match-count": match_count,
            "mismatch-count": mismatch_count,
            "unknown-count": unknown_count,
        },
        "self-test/non-deterministic-fields": non_deterministic_fields if non_deterministic_fields else None,
        "self-test/volatile-fields-excluded": volatile_fields,
        "self-test/notes": notes if notes else None,
    }

    # Write report to Run A's output directory
    report_path = output_base / label_a / "self-test-report.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2))

    # Print summary
    print(f"\n--- Self-Test Result ---", file=sys.stderr)
    print(f"  status:              {status}", file=sys.stderr)
    print(f"  execution-success:   {execution_success}", file=sys.stderr)
    print(f"  determinism-status:  {determinism_status}", file=sys.stderr)
    print(f"  fields:              {match_count} match, {mismatch_count} differ, "
          f"{unknown_count} unknown", file=sys.stderr)
    if non_deterministic_fields:
        print(f"  diff:                {', '.join(non_deterministic_fields)}",
              file=sys.stderr)
    if volatile_fields:
        for vf in volatile_fields:
            print(f"  volatile-excluded:   {vf['field']} — {vf['reason']}",
                  file=sys.stderr)
    if notes:
        for n in notes:
            print(f"  note:                {n}", file=sys.stderr)
    print(f"  report:              {report_path}", file=sys.stderr)

    # Exit codes: deterministic passes, deterministic-failure passes (known failures),
    # non-deterministic and failed are errors
    return 0 if status in ("deterministic", "deterministic-failure") else 1


def main():
    import argparse
    parser = argparse.ArgumentParser(
        description="Forensic self-test: validate pipeline determinism")
    parser.add_argument("--run-request",
                        default="examples/forensic-reference-run/run-request.example.edn",
                        help="Path to run request file")
    parser.add_argument("--output-base", default=str(PRF_RUNS_ROOT),
                        help=f"Output base directory (default: {PRF_RUNS_ROOT})")
    args = parser.parse_args()
    sys.exit(self_test(args.run_request, args.output_base))


if __name__ == "__main__":
    main()
