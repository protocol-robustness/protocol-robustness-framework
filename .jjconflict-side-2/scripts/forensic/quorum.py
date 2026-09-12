#!/usr/bin/env python3
"""Local repeated-execution quorum: run the same suite N times and compare.

This is NOT remote consensus.  It validates local determinism by executing
the same run request multiple times and verifying that all runs produce
identical stable hashes.  Useful for establishing confidence in pipeline
determinism before relying on forensic output.

Usage:
    bb forensic:quorum [--suite <key>] [--runs 3] [--threshold 2]
    bb forensic:quorum --runs 5 --threshold 3
"""

from __future__ import annotations

import concurrent.futures
import json
import os
import subprocess
import sys
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

PRF_RUNS_ROOT = Path(os.environ.get("PRF_RUNS_ROOT", "~/prf-runs")).expanduser()

# Stable fields to compare across runs
QUORUM_FIELDS = [
    "bundle/hash",
    "overview/hash",
    "execution/summary/status",
    "execution/summary/totals/total",
    "execution/summary/totals/passed",
    "execution/summary/totals/failed",
    "execution/summary/totals/expected-failed",
    "execution/summary/totals/unexpected-failed",
    "source/tree-hash",
    "source/tree-hash-algorithm",
]

# Registry snapshot keys are added dynamically.


def load_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text())
    except Exception:
        return None


def extract_field(bundle: dict, field: str) -> Any:
    """Get a field from a Clojure bundle root dict.

    Bundle roots use /-separated flat keys for direct values and
    /-separated keys pointing to nested dicts for structured data.
    Examples:
      bundle/hash              -> bundle["bundle/hash"]
      overview/hash            -> bundle["overview/hash"]
      execution/summary/status -> bundle["execution/summary"]["status"]
      registry/snapshot/X      -> bundle["registry/snapshot"]["X"]
    """
    if field in bundle:
        return bundle[field]
    # Find the longest /-prefix that exists as a flat key, then navigate
    parts = field.split("/")
    for prefix_len in range(len(parts) - 1, 0, -1):
        prefix = "/".join(parts[:prefix_len])
        if prefix in bundle:
            val = bundle[prefix]
            for p in parts[prefix_len:]:
                if isinstance(val, dict):
                    val = val.get(p)
                else:
                    return None
            return val
    return None


def collect_snapshot_fields(bundle: dict) -> list[str]:
    """Get all registry/snapshot/* field names present in this bundle."""
    snap = bundle.get("registry/snapshot") or {}
    return [f"registry/snapshot/{k}" for k in snap]


def run_execution(suite_key: str | None, output_base: Path,
                  label: str) -> tuple[int, dict | None, float]:
    """Run one forensic execution. Returns (exit_code, clojure_bundle, elapsed_ms)."""
    env = os.environ.copy()
    env["PRF_ORCHESTRATION_RUNNER_ID"] = "forensic-quorum.py"

    run_expr = ("(require '[resolver-sim.core]) "
                "(->> *command-line-args* (map str) (apply resolver-sim.core/-main))")
    cmd = ["clojure", "-M:with-sew", "-e", run_expr, "--", "--invariants"]
    if suite_key:
        cmd.extend(["--suite", str(suite_key)])
    output_dir = output_base / label
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / "clojure-bundle-root.json"
    cmd.extend(["--output-file", str(output_path)])

    print(f"  run {label}: executing...", file=sys.stderr)
    t0 = time.time()
    r = subprocess.run(cmd, capture_output=False, timeout=600, env=env)
    elapsed_ms = int((time.time() - t0) * 1000)

    if not output_path.exists():
        print(f"  run {label}: FAILED — no Clojure bundle root", file=sys.stderr)
        return r.returncode, None, elapsed_ms

    bundle = load_json(output_path)
    print(f"  run {label}: exit={r.returncode} ({elapsed_ms}ms)", file=sys.stderr)
    return r.returncode, bundle, elapsed_ms


def compute_field_agreement(
    bundles: list[dict | None],
    field: str,
    threshold: int,
) -> dict:
    """Compute per-field agreement across runs."""
    values = [extract_field(b, field) if b else None for b in bundles]
    non_null = [v for v in values if v is not None]

    if not non_null:
        return {
            "field": field,
            "status": "insufficient-data",
            "winning-value": None,
            "agreement-count": 0,
            "total-runs": len(bundles),
            "threshold": threshold,
        }

    counter = Counter(non_null)
    winning_value, winning_count = counter.most_common(1)[0]

    if winning_count >= threshold:
        status = "confirmed"
    elif winning_count == len(non_null):
        # All present values agree but didn't meet threshold (shouldn't happen)
        status = "confirmed"
    elif len(set(non_null)) == 1:
        status = "confirmed"
    else:
        status = "insufficient-agreement"

    return {
        "field": field,
        "status": status,
        "winning-value": str(winning_value)[:40] if winning_value else None,
        "agreement-count": winning_count,
        "total-runs": len(bundles),
        "threshold": threshold,
    }


def quorum(suite_key: str | None = None,
           run_count: int = 3,
           threshold: int | None = None,
           output_base: str | Path | None = None,
           workers: int | None = None) -> int:
    if threshold is None:
        threshold = max(2, run_count - 1)  # default: all but one
    if output_base is None:
        output_base = PRF_RUNS_ROOT
    output_base = Path(output_base).expanduser().resolve()

    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")

    print(f"=== Local Repeated-Execution Quorum ===", file=sys.stderr)
    print(f"  runs: {run_count}, threshold: {threshold}", file=sys.stderr)
    print(f"  suite: {suite_key or 'registry-default'}", file=sys.stderr)
    print(f"  output: {output_base}", file=sys.stderr)

    # Execute all runs in parallel
    bundles: list[dict | None] = []
    exit_codes: list[int] = []
    elapsed_list: list[int] = []
    run_labels: list[str] = [f"{ts}-quorum-{chr(ord('a') + i)}" for i in range(run_count)]

    workers = min(run_count, workers or (os.cpu_count() or 4) + 1)
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        fut_map = {pool.submit(run_execution, suite_key, output_base, label): i
                   for i, label in enumerate(run_labels)}
        results = [None] * run_count
        for fut in concurrent.futures.as_completed(fut_map):
            idx = fut_map[fut]
            results[idx] = fut.result()
        for code, bundle, elapsed in results:
            bundles.append(bundle)
            exit_codes.append(code)
            elapsed_list.append(int(elapsed))

    # Collect all field names from any bundle that produced output
    all_snapshot_fields: set[str] = set()
    first_bundle: dict | None = None
    for b in bundles:
        if b:
            first_bundle = b
            all_snapshot_fields.update(collect_snapshot_fields(b))

    fields_to_check = list(QUORUM_FIELDS) + sorted(all_snapshot_fields)

    # Compute per-field agreement
    agreement_results: list[dict] = []
    for field in fields_to_check:
        result = compute_field_agreement(bundles, field, threshold)
        if result["status"] != "insufficient-data":
            agreement_results.append(result)

    # Compute overall status
    execution_success_count = sum(1 for c in exit_codes if c == 0)
    execution_failure_count = sum(1 for c in exit_codes if c != 0)
    all_executed = all(b is not None for b in bundles)

    confirmed_fields = sum(1 for a in agreement_results
                          if a["status"] == "confirmed")
    insufficient_fields = sum(1 for a in agreement_results
                              if a["status"] == "insufficient-agreement")
    total_compared = len(agreement_results)

    if not all_executed:
        status = "failed"
    elif insufficient_fields > 0:
        status = "non-deterministic"
    elif confirmed_fields == total_compared and total_compared > 0:
        status = "confirmed"
    elif total_compared == 0:
        status = "inconclusive"
    else:
        status = "inconclusive"

    # Build identity metadata from the first successful bundle
    identity: dict[str, Any] = {"suite-key": suite_key or "registry-default"}
    fb = first_bundle
    if fb:
        identity["bundle/hash"] = fb.get("bundle/hash")
        identity["overview/hash"] = fb.get("overview/hash")
        identity["execution/summary"] = fb.get("execution/summary")
        identity["source/tree-hash"] = fb.get("source/tree-hash")
        identity["source/tree-hash-algorithm"] = fb.get("source/tree-hash-algorithm")
        snap = fb.get("registry/snapshot") or {}
        identity["registry/snapshot-hashes"] = {k: str(v)[:16]
                                                for k, v in snap.items()}
        ov = fb.get("overview") or {}
        identity["overview"] = ov

    # Build report
    report = {
        "quorum/schema-version": "forensic-quorum.v1",
        "quorum/type": "local-repeated-execution",
        "quorum/status": status,
        "quorum/run-count": run_count,
        "quorum/threshold": threshold,
        "quorum/timestamp": ts,
        "quorum/runner-id": "forensic-quorum.py",
        "quorum/execution-success-count": execution_success_count,
        "quorum/execution-failure-count": execution_failure_count,
        "quorum/exit-codes": {run_labels[i]: exit_codes[i]
                              for i in range(run_count)},
        "quorum/elapsed-ms": {run_labels[i]: elapsed_list[i]
                              for i in range(run_count)},
        "quorum/run-directories": {run_labels[i]: str(output_base / run_labels[i])
                                   for i in range(run_count)},
        "quorum/identity": identity,
        "quorum/agreement": agreement_results,
        "quorum/volatile-fields-excluded": [
            {"field": "execution/node-hash",
             "reason": "includes wall-clock timestamp — always differs",
             "resolution": "use execution/content-hash for reproduce/quorum"},
            {"field": "execution/record-hash",
             "reason": "audit trail hash with timestamp — always differs",
             "resolution": "use execution/content-hash for reproduce/quorum"},
        ],
        "quorum/limitations": [
            "All runs executed locally on the same machine.",
            "This confirms local repeatability, not independent remote verification.",
        ],
    }

    # Write report to first run's directory
    report_path = output_base / run_labels[0] / "quorum-report.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2))

    # Print summary
    print(f"\n--- Local Quorum Result ---", file=sys.stderr)
    print(f"  status:     {status}", file=sys.stderr)
    print(f"  quorum:     {confirmed_fields}/{total_compared} fields agree, "
          f"threshold {threshold}", file=sys.stderr)
    print(f"  execution:  {execution_success_count}/{run_count} succeeded",
          file=sys.stderr)
    if execution_failure_count > 0:
        print(f"  failures:   {execution_failure_count} run(s) failed",
              file=sys.stderr)
    print(f"  volatile:   execution/node-hash, execution/record-hash excluded",
          file=sys.stderr)
    print(f"  report:     {report_path}", file=sys.stderr)

    return 0 if status == "confirmed" else 1


def main():
    import argparse
    parser = argparse.ArgumentParser(
        description="Local repeated-execution quorum")
    parser.add_argument("--suite", default=None,
                        help="Suite key (default: registry suite)")
    parser.add_argument("--runs", type=int, default=3,
                        help="Number of runs (default: 3)")
    parser.add_argument("--threshold", type=int, default=None,
                        help="Agreement threshold (default: runs - 1)")
    parser.add_argument("--output-base", default=str(PRF_RUNS_ROOT),
                        help=f"Output base directory (default: {PRF_RUNS_ROOT})")
    parser.add_argument("--workers", type=int, default=None,
                        help="Parallel workers (default: cpu_count + 1, capped at run count)")
    args = parser.parse_args()
    sys.exit(quorum(args.suite, args.runs, args.threshold, args.output_base, args.workers))


if __name__ == "__main__":
    main()
