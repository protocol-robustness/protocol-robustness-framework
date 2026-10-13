#!/usr/bin/env python3
"""Reproduce a forensic run from its bundle and compare results.

Phase 1+: field classification, mismatch reasons, reproduction verdict.

Usage:
    python3 scripts/forensic/reproduce.py <run-dir> [--output-base <dir>]
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

from forensic.source_hash import (SOURCE_TREE_HASH_ALGORITHM,
                                  compute_source_tree_hash,
                                  normalize_source_roots)

PRF_RUNS_ROOT = Path(os.environ.get("PRF_RUNS_ROOT", "~/prf-runs")).expanduser()
PRF_SOURCE_ROOTS = normalize_source_roots(os.environ.get("PRF_SOURCE_ROOTS"))

# Field classification by severity
FIELD_CLASSIFICATION: dict[str, str] = {
    "bundle/hash": "core",
    "overview/overview-hash": "core",
    "execution/summary/status": "core",
    "execution/summary/totals/total": "core",
    "execution/summary/totals/passed": "core",
    "execution/summary/totals/failed": "core",
    "execution/summary/totals/expected-failed": "core",
    "execution/summary/totals/unexpected-failed": "core",
    "source/tree-hash": "core",
    "protocol/state-hashes/force-authorisations/hash": "core",
    "protocol/state-hashes/force-authorisations/consumed-hash": "core",
}
# Registry snapshot hashes are all CORE (added dynamically)
# Protocol state hashes are all CORE (added explicitly)
# Everything else defaults to DIAGNOSTIC


def classify_field(label: str) -> str:
    return FIELD_CLASSIFICATION.get(label, "diagnostic")


def load_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text())
    except Exception:
        return None


def current_code_hash(repo_root: Path) -> tuple[str, str, list[str]]:
    try:
        ch, included_roots = compute_source_tree_hash(repo_root, PRF_SOURCE_ROOTS)
    except Exception as exc:
        print(f"  ⚠ Failed to compute source tree hash: {exc}", file=sys.stderr)
        ch = "unknown"
        included_roots = []
    return ch, SOURCE_TREE_HASH_ALGORITHM, included_roots


def classify_source_precheck(bundle_hash: str | None,
                             bundle_algorithm: str | None,
                             current_hash: str,
                             current_algorithm: str) -> dict[str, Any]:
    if not bundle_hash:
        return {"match": None, "reason": "missing/original-field"}
    if not bundle_algorithm:
        return {"match": None, "reason": "missing/algorithm-field"}
    if bundle_algorithm != current_algorithm:
        return {"match": None, "reason": "algorithm-mismatch"}
    return {
        "match": current_hash == bundle_hash,
        "reason": "matched" if current_hash == bundle_hash else "expected-drift/source-changed",
    }


def classify_mismatch(label: str, orig: Any, new: Any,
                      source_status: dict[str, Any] | None) -> str:
    """Return a reason string for the comparison result."""
    source_matches = None if source_status is None else source_status.get("match")
    source_reason = None if source_status is None else source_status.get("reason")
    if orig == new and orig is not None:
        return "matched"
    if orig is None and new is None:
        return "matched"
    if orig is None and new is not None:
        return "missing/original-field"
    if orig is not None and new is None:
        return "missing/reproduced-field"
    # Values differ — classify the reason
    if label == "source/tree-hash":
        if source_reason in ("missing/algorithm-field", "algorithm-mismatch"):
            return "legacy-algorithm"
        if source_matches is False:
            return "expected-drift/source-changed"
        if source_matches is True:
            return "mismatch/non-deterministic"
        return "mismatch/unexplained"
    if label == "source/tree-hash-algorithm":
        if source_reason in ("missing/algorithm-field", "algorithm-mismatch"):
            return "legacy-algorithm"
        return "mismatch/unexplained"
    # Registry snapshot or execution summary — unexpected divergence
    if source_matches is False:
        return "expected-drift/source-changed"
    return "mismatch/unexplained"


def compare_bundle_fields(orig: dict, new: dict,
                          source_status: dict[str, Any] | None) -> list[dict]:
    """Compare two Clojure bundle roots with classification and reason."""
    checks: list[dict] = []

    def add_check(label: str, ov: Any, nv: Any) -> None:
        cls = classify_field(label)
        reason = classify_mismatch(label, ov, nv, source_status)
        checks.append({
            "field": label,
            "classification": cls,
            "original": ov,
            "new": nv,
            "match": ov == nv if ov is not None and nv is not None else None,
            "reason": reason,
        })

    add_check("bundle/hash", orig.get("bundle/hash"), new.get("bundle/hash"))
    add_check("bundle/schema-version",
              orig.get("bundle/schema-version"), new.get("bundle/schema-version"))

    # Overview hash (top-level in Clojure bundle root)
    add_check("overview/overview-hash",
              orig.get("overview/hash"), new.get("overview/hash"))

    # Execution summary
    os_a = orig.get("execution/summary", {})
    os_b = new.get("execution/summary", {})
    add_check("execution/summary/status", os_a.get("status"), os_b.get("status"))
    ta = os_a.get("totals") or {}
    tb = os_b.get("totals") or {}
    for k in ("total", "passed", "failed", "expected-failed", "unexpected-failed"):
        if k in ta or k in tb:
            add_check(f"execution/summary/totals/{k}", ta.get(k), tb.get(k))

    # Registry snapshot — all CORE
    snap_a = orig.get("registry/snapshot", {}) or {}
    snap_b = new.get("registry/snapshot", {}) or {}
    for k in sorted(set(list(snap_a.keys()) + list(snap_b.keys()))):
        add_check(f"registry/snapshot/{k}", snap_a.get(k), snap_b.get(k))

    # Protocol state hashes — all CORE
    proto_a = orig.get("protocol/state-hashes", {}) or {}
    proto_b = new.get("protocol/state-hashes", {}) or {}
    for k in sorted(set(list(proto_a.keys()) + list(proto_b.keys()))):
        add_check(f"protocol/state-hashes/{k}", proto_a.get(k), proto_b.get(k))

    # Source provenance
    for k in ("source/tree-hash", "source/tree-hash-algorithm"):
        if orig.get(k) is not None or new.get(k) is not None:
            add_check(k, orig.get(k), new.get(k))

    return checks


def compute_verdict(checks: list[dict],
                    bundle_a_ok: bool, bundle_b_ok: bool) -> tuple[str, list[str]]:
    """Compute top-level reproduction verdict."""
    notes: list[str] = []

    if not bundle_a_ok and not bundle_b_ok:
        return "reproduce-failed", ["Both original and reproduce produced no bundle"]

    if not bundle_b_ok:
        return "reproduce-failed", ["Reproduce run produced no bundle"]

    core_checks = [c for c in checks if c.get("classification") == "core"]
    diag_checks = [c for c in checks if c.get("classification") == "diagnostic"]

    core_mismatch = [c for c in core_checks if c.get("match") is False]
    core_unknown = [c for c in core_checks if c.get("match") is None]
    diag_mismatch = [c for c in diag_checks if c.get("match") is False]
    # Check for entirely unknown fields (original missing)
    all_unknown_all = len(checks) > 0 and len(checks) == len(
        [c for c in checks if c.get("match") is None])

    if all_unknown_all:
        return "invalid-original-bundle", ["All fields returned None — bundle predates comparison schema"]

    # Classify mismatches by reason
    expected_mismatches = [c for c in core_mismatch
                           if c.get("reason", "").startswith("expected-drift/")]
    legacy_mismatches = [c for c in core_mismatch
                         if c.get("reason") == "legacy-algorithm"]
    unexpected_mismatches = [c for c in core_mismatch
                             if not c.get("reason", "").startswith("expected-drift/")
                             and c.get("reason") != "legacy-algorithm"]

    if len(core_checks) == 0:
        return "inconclusive", ["No core fields available for comparison"]

    if unexpected_mismatches:
        fields = ", ".join(c["field"] for c in unexpected_mismatches)
        return "diverged", [f"{len(unexpected_mismatches)} core field(s) diverged: {fields}"]

    if expected_mismatches:
        fields = ", ".join(c["field"] for c in expected_mismatches)
        reason = expected_mismatches[0].get("reason", "unknown")
        notes.append(f"Core divergence explained: {fields} ({reason})")

    if legacy_mismatches and not expected_mismatches:
        fields = ", ".join(c["field"] for c in legacy_mismatches)
        notes.append(f"Core comparison limited by legacy source hash algorithm: {fields}")
        return "reproduced-with-legacy-gaps", notes

    if core_unknown and not expected_mismatches and not unexpected_mismatches:
        fields = ", ".join(c["field"] for c in core_unknown)
        notes.append(f"Core fields unknown (original bundle format): {fields}")
        return "reproduced-with-legacy-gaps", notes

    if expected_mismatches:
        return "reproduced-with-explained-drift", notes

    if diag_mismatch:
        fields = ", ".join(c["field"] for c in diag_mismatch)
        notes.append(f"Diagnostic fields differ (non-essential): {fields}")

    return "verified", notes


def reproduce_run(run_dir: str | Path,
                  output_base: str | Path | None = None) -> int:
    run_dir = Path(run_dir).expanduser().resolve()
    if not run_dir.exists():
        print(f"Run directory not found: {run_dir}", file=sys.stderr)
        return 1

    if output_base is None:
        output_base = PRF_RUNS_ROOT
    output_base = Path(output_base).expanduser().resolve()

    # Step 1: Source pre-check
    print("--- Source Pre-check ---", file=sys.stderr)
    orig_overview = load_json(run_dir / "run-overview.json")
    orig_src = (orig_overview or {}).get("source", {})
    bundle_hash = orig_src.get("source-hash")
    if not bundle_hash:
        bundle_hash = orig_src.get("code-hash")
    bundle_hash_algorithm = orig_src.get("source-hash-algorithm")
    if not bundle_hash_algorithm:
        bundle_hash_algorithm = orig_src.get("code-hash-algorithm")
    bundle_hash_roots = orig_src.get("source-hash-roots")
    if not bundle_hash_roots:
        bundle_hash_roots = orig_src.get("included-roots")
    current_ch, current_algo, current_roots = current_code_hash(Path.cwd())
    source_status = classify_source_precheck(bundle_hash,
                                             bundle_hash_algorithm,
                                             current_ch,
                                             current_algo)

    print(f"  Bundle source-hash: {bundle_hash[:24] if bundle_hash else 'N/A'}...",
          file=sys.stderr)
    print(f"  Bundle algorithm: {bundle_hash_algorithm or 'N/A'}", file=sys.stderr)
    print(f"  Bundle roots: {', '.join(bundle_hash_roots or []) or 'N/A'}", file=sys.stderr)
    print(f"  Current source-hash: {current_ch[:24]}...", file=sys.stderr)
    print(f"  Current algorithm: {current_algo}", file=sys.stderr)
    print(f"  Current roots: {', '.join(current_roots) or 'N/A'}", file=sys.stderr)
    if source_status["reason"] == "missing/original-field":
        print(f"  ⚠ No source-hash in original bundle (older run format)", file=sys.stderr)
    elif source_status["reason"] in ("missing/algorithm-field", "algorithm-mismatch"):
        print(f"  ⚠ Source hash algorithm unmatched; comparison is limited ({source_status['reason']})",
              file=sys.stderr)
    elif source_status["match"]:
        print(f"  ✅ Source code matches bundle", file=sys.stderr)
    else:
        print(f"  ⚠ Source code CHANGED — expected drift", file=sys.stderr)

    # Step 2: Read original Clojure bundle root
    orig_clojure_bundle = load_json(run_dir / "clojure-bundle-root.json")
    if not orig_clojure_bundle:
        print("Cannot read original clojure-bundle-root.json", file=sys.stderr)
        return 1

    # Step 3: Execute reproduce
    run_req = load_json(run_dir / "run-request.json")
    suite_key = None
    if run_req:
        suite_key = (run_req.get("suite/key") or run_req.get("key"))

    label = f"reproduce-{run_dir.name[:30]}"
    repro_id = f"{datetime.now(timezone.utc).strftime('%Y-%m-%dT%H-%M-%SZ')}-{label}"
    repro_dir = output_base / repro_id
    repro_dir.mkdir(parents=True, exist_ok=False)

    print(f"\n--- Execute Reproduce ---", file=sys.stderr)
    print(f"  id:     {repro_id}", file=sys.stderr)
    print(f"  output: {repro_dir}", file=sys.stderr)

    env = os.environ.copy()
    env["PRF_SOURCE_TREE_HASH"] = current_ch
    env["PRF_SOURCE_TREE_HASH_ALGORITHM"] = current_algo
    env["PRF_ORCHESTRATION_RUNNER_ID"] = "forensic-reproduce.py"
    env["PRF_BUNDLE_ID"] = repro_id

    run_expr = ("(require '[resolver-sim.core]) "
                "(->> *command-line-args* (map str) (apply resolver-sim.core/-main))")
    cmd = ["clojure", "-M:with-sew", "-e", run_expr, "--", "--invariants"]
    if suite_key:
        cmd.extend(["--suite", str(suite_key)])
    output_path = repro_dir / "clojure-bundle-root.json"
    cmd.extend(["--output-file", str(output_path)])

    print(f"  cmd: {' '.join(cmd)}", file=sys.stderr)
    t0 = time.time()
    r = subprocess.run(cmd, capture_output=False, timeout=600, env=env)
    elapsed_ms = int((time.time() - t0) * 1000)
    print(f"  exit code: {r.returncode}  ({elapsed_ms}ms)", file=sys.stderr)

    # Step 4: Read new bundle root
    new_bundle = load_json(output_path)
    bundle_b_ok = new_bundle is not None
    if not bundle_b_ok:
        print("Reproduce produced no Clojure bundle root", file=sys.stderr)

    # Step 5: Full field comparison with classification + reasons
    checks: list[dict] = []
    if new_bundle:
        checks = compare_bundle_fields(orig_clojure_bundle, new_bundle, source_status)

    match_count = sum(1 for c in checks if c.get("match") is True)
    mismatch_count = sum(1 for c in checks if c.get("match") is False)
    unknown_count = sum(1 for c in checks if c.get("match") is None)

    print(f"\n--- Comparison ---", file=sys.stderr)
    for c in checks:
        symbol = "✅" if c["match"] is True else \
                 "⚠" if c.get("reason", "").startswith("expected-") else \
                 "❌" if c["match"] is False else "❓"
        ov = str(c["original"])[:30] if c["original"] is not None else "—"
        nv = str(c["new"])[:30] if c["new"] is not None else "—"
        reason = c.get("reason", "")
        print(f"  {symbol} [{c['classification']:>9}] {c['field']}: "
              f"{ov} vs {nv}  ({reason})", file=sys.stderr)

    # Aggregate by classification
    core_match = sum(1 for c in checks
                     if c.get("classification") == "core" and c.get("match") is True)
    core_mismatch = sum(1 for c in checks
                        if c.get("classification") == "core" and c.get("match") is False)
    core_unknown = sum(1 for c in checks
                       if c.get("classification") == "core" and c.get("match") is None)
    diag_match = sum(1 for c in checks
                     if c.get("classification") == "diagnostic" and c.get("match") is True)
    diag_mismatch = sum(1 for c in checks
                        if c.get("classification") == "diagnostic" and c.get("match") is False)

    # Step 6: Compute verdict
    verdict, verdict_notes = compute_verdict(
        checks, True, bundle_b_ok)

    # Step 7: Write report
    repro_summary = {
        "reproduce/schema-version": "forensic-reproduce.v2",
        "reproduce/verdict": verdict,
        "reproduce/original-run": str(run_dir),
        "reproduce/reproduce-run": repro_id,
        "reproduce/timestamp": datetime.now(timezone.utc).isoformat(),
        "reproduce/elapsed-ms": elapsed_ms,
        "reproduce/exit-code": r.returncode if bundle_b_ok else None,
        "reproduce/source-pre-check": {
            "bundle-source-hash": bundle_hash or None,
            "bundle-source-hash-algorithm": bundle_hash_algorithm,
            "bundle-source-hash-roots": bundle_hash_roots,
            "current-source-hash": current_ch,
            "current-source-hash-algorithm": current_algo,
            "current-source-hash-roots": current_roots,
            "match": source_status["match"],
            "reason": source_status["reason"],
        },
        "reproduce/comparison": {
            "checks": checks,
            "match-count": match_count,
            "mismatch-count": mismatch_count,
            "unknown-count": unknown_count,
            "core": {"matched": core_match, "mismatched": core_mismatch,
                     "unknown": core_unknown},
            "diagnostic": {"matched": diag_match, "mismatched": diag_mismatch},
        },
        "reproduce/verdict-notes": verdict_notes if verdict_notes else None,
    }
    repro_file = repro_dir / "reproduce-report.json"
    repro_file.write_text(json.dumps(repro_summary, indent=2))
    print(f"  report: {repro_file}", file=sys.stderr)

    # Print verdict
    verdict_symbol = "✅" if verdict == "verified" else \
                     "⚠" if "explained" in verdict or "legacy" in verdict else \
                     "❌"
    print(f"\n{verdict_symbol} Verdict: {verdict}", file=sys.stderr)
    for vn in verdict_notes:
        print(f"     {vn}", file=sys.stderr)
    print(f"     Core: {core_match} match, {core_mismatch} differ, "
          f"{core_unknown} unknown", file=sys.stderr)
    print(f"     Diagnostic: {diag_match} match, {diag_mismatch} differ",
          file=sys.stderr)

    # Exit: success for verified and explained-drift, failure for diverged/failed
    return 0 if verdict in ("verified", "reproduced-with-explained-drift",
                            "reproduced-with-legacy-gaps") else 1


def main():
    import argparse
    parser = argparse.ArgumentParser(
        description="Reproduce a forensic run from its bundle")
    parser.add_argument("run_dir", help="Path to forensic run directory")
    parser.add_argument("--output-base", default=str(PRF_RUNS_ROOT),
                        help=f"Base directory for reproduce output (default: {PRF_RUNS_ROOT})")
    args = parser.parse_args()
    sys.exit(reproduce_run(args.run_dir, args.output_base))


if __name__ == "__main__":
    main()
