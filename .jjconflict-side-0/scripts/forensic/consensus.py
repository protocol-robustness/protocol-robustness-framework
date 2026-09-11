#!/usr/bin/env python3
"""Phase 1 local consensus coordinator for forensic evidence runners.

Implements RUNNER_CONSENSUS_SPEC_V1: orchestrates N runs, collects result
submissions, compares stable fields, and produces consensus certificate
and disagreement artifacts.

Transport-agnostic: uses filesystem-based messages (JSON files in a staging
directory). No network, no IPC, no libp2p.

Usage:
    python3 scripts/forensic/consensus.py --runs 3 --suite sew-scenarios
    python3 scripts/forensic/consensus.py --from-dirs dir1 dir2 dir3
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

_project_root = Path(__file__).resolve().parent.parent.parent
if str(_project_root) not in sys.path:
    sys.path.insert(0, str(_project_root))

from scripts.forensic.preflight import write_sealed_json, compute_sha256

SCHEMA_VERSION = "consensus-round.v1"
CERT_SCHEMA_VERSION = "consensus-certificate.v1"
DISAGREEMENT_SCHEMA_VERSION = "disagreement-report.v1"

# Fields compared for agreement (the stable comparison surface)
CONSENSUS_FIELDS = [
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

VOLATILE_FIELDS_EXCLUDED = [
    {"field": "execution/node-hash",
     "reason": "includes wall-clock timestamp — always differs across runs",
     "resolution": "use execution/content-hash for reproduce/quorum"},
    {"field": "execution/content-hash",
     "reason": "per-run execution identity — differs across independent executions",
     "resolution": "none — execution identity is inherently per-run"},
    {"field": "execution/record-hash",
     "reason": "audit trail hash with timestamp — always differs",
     "resolution": "use overview/hash for cross-run comparison"},
]


def _deep_get(d: dict, path: str) -> Any:
    """Navigate a nested dict using '/' delimited path, e.g. 'execution/summary/status'."""
    parts = path.split("/")
    current: Any = d
    for p in parts:
        if isinstance(current, dict):
            current = current.get(p)
        else:
            return None
    return current


def _build_summary(bundle: dict) -> dict:
    """Extract the stable comparison surface from a Clojure bundle root."""
    summary = {}
    for field in CONSENSUS_FIELDS:
        val = _deep_get(bundle, field)
        if val is not None:
            summary[field] = val
    # Also extract registry/snapshot/* keys (nested format)
    snapshot = _deep_get(bundle, "registry/snapshot") or {}
    if isinstance(snapshot, dict):
        for k, v in snapshot.items():
            if v is not None:
                summary[f"registry/snapshot/{k}"] = v
    return summary


def load_bundle(path: Path) -> dict | None:
    """Load a Clojure bundle root JSON, or None on failure."""
    try:
        data = json.loads(path.read_text())
        if not isinstance(data, dict):
            return None
        return data
    except Exception:
        return None


def _run_single(run_id: str, suite_key: str | None = None,
                output_base: str | Path | None = None,
                label: str | None = None,
                no_harden: bool = True) -> dict:
    """Execute a single forensic run and return its result message.
    
    Returns a dict with runner-message fields, or error info on failure.
    """
    from scripts.forensic.run import run_forensic
    start = time.time()
    try:
        exit_code = run_forensic(
            suite_key=suite_key,
            label=label or run_id,
            no_harden=no_harden,
            output_base=output_base,
        )
        elapsed_ms = int((time.time() - start) * 1000)
        # Re-read the bundle root to get the summary
        return {"status": "pass" if exit_code == 0 else "fail",
                "exit-code": exit_code,
                "elapsed-ms": elapsed_ms}
    except Exception as e:
        elapsed_ms = int((time.time() - start) * 1000)
        return {"status": "error", "exit-code": -1,
                "elapsed-ms": elapsed_ms, "error": str(e)}


def _find_bundle_root(run_dir: Path) -> Path | None:
    """Find the clojure-bundle-root.json in a run directory."""
    for candidate in ["clojure-bundle-root.json", "run-bundle-root.json"]:
        p = run_dir / candidate
        if p.exists():
            return p
    return None


def collect_submissions(run_dirs: list[Path]) -> list[dict]:
    """Collect result submissions from a list of forensic run directories.
    
    Returns a list of submission dicts (with runner-message format).
    """
    submissions = []
    for i, run_dir in enumerate(run_dirs):
        run_dir = Path(run_dir).expanduser().resolve()
        bundle_path = _find_bundle_root(run_dir)
        bundle = load_bundle(bundle_path) if bundle_path else None
        submission = {
            "runner-message/schema-version": "runner-message.v1",
            "runner-message/type": "result-submission",
            "runner-message/timestamp": datetime.now(timezone.utc).isoformat(),
            "runner-message/runner-id": f"runner/local-forensic-{i}",
            "runner-message/run-id": run_dir.name,
            "runner-message/status": "pass" if bundle else "fail",
            "runner-message/exit-code": _deep_get(bundle, "run/exit-code") if bundle else -1,
            "runner-message/bundle-path": str(run_dir),
        }
        if bundle:
            submission["runner-message/summary"] = _build_summary(bundle)
            # Volatile fields for reference
            volatile = {}
            for vf in ["execution/node-hash", "execution/content-hash",
                        "execution/record-hash"]:
                val = _deep_get(bundle, vf)
                if val:
                    volatile[vf] = val
            if volatile:
                submission["runner-message/volatile"] = volatile
        submissions.append(submission)
    return submissions


def compute_agreement(submissions: list[dict],
                      threshold: int | None = None) -> list[dict]:
    """Compute per-field agreement across submissions.
    
    Returns a list of per-field agreement results.
    """
    if not submissions:
        return []
    n = len(submissions)
    if threshold is None:
        threshold = max(2, n - 1)
    
    # Collect all field names from all submissions
    all_fields: set[str] = set()
    for s in submissions:
        summary = s.get("runner-message/summary", {})
        all_fields.update(summary.keys())
    
    results = []
    for field in sorted(all_fields):
        values = []
        for s in submissions:
            summary = s.get("runner-message/summary", {})
            val = summary.get(field)
            if val is not None:
                values.append(str(val))
        if not values:
            continue
        counter = Counter(values)
        winning_val, winning_count = counter.most_common(1)[0]
        if winning_count >= threshold:
            status = "confirmed"
        else:
            status = "diverged"
        results.append({
            "field": field,
            "status": status,
            "winning-value": winning_val,
            "agreement-count": winning_count,
            "total-runs": n,
            "threshold": threshold,
            "values": dict(counter.most_common()),
        })
    return results


def compute_verdict(agreement: list[dict], exit_codes: list[int]) -> str:
    """Compute the overall consensus verdict from per-field agreement."""
    if not agreement:
        return "inconclusive"
    all_diverged = all(a["status"] == "diverged" for a in agreement)
    if all_diverged:
        return "diverged"
    some_diverged = any(a["status"] == "diverged" for a in agreement)
    any_failed = any(ec != 0 for ec in exit_codes)
    if some_diverged:
        return "diverged"
    if any_failed:
        return "confirmed-with-failures"
    return "confirmed"


def agreed_hash(agreement: list[dict]) -> str | None:
    """Extract the agreed overview hash from agreement results, if consistent."""
    for a in agreement:
        if a["field"] == "overview/hash" and a["status"] == "confirmed":
            return str(a["winning-value"])
    return None


def build_certificate(round_id: str, verdict: str, agreement: list[dict],
                      submissions: list[dict], threshold: int) -> dict:
    """Build the consensus certificate artifact.
    Participants are sorted deterministically by runner-id then bundle/hash
    so that identical rounds produce identical certificate hashes."""
    ah = agreed_hash(agreement)
    participants = []
    for s in submissions:
        summary = s.get("runner-message/summary", {})
        participants.append({
            "runner-id": s.get("runner-message/runner-id", "?"),
            "bundle/hash": summary.get("bundle/hash"),
            "overview/hash": summary.get("overview/hash"),
            "status": s.get("runner-message/status", "?"),
            "agree": (summary.get("overview/hash") == ah) if ah else False,
        })
    # Sort deterministically so certificate hash is stable across runs
    participants.sort(key=lambda p: (p.get("runner-id", ""), p.get("bundle/hash", "") or ""))
    cert = {
        "consensus-certificate/schema-version": CERT_SCHEMA_VERSION,
        "consensus-certificate/round-id": round_id,
        "consensus-certificate/status": verdict,
        "consensus-certificate/agreed-hash": ah,
        "consensus-certificate/agreed-hash-type": "overview/hash",
        "consensus-certificate/participants": len(submissions),
        "consensus-certificate/threshold": threshold,
        "consensus-certificate/agreement-count": sum(
            1 for p in participants if p.get("agree")),
        "consensus-certificate/timestamp": datetime.now(timezone.utc).isoformat(),
        "consensus-certificate/participant-list": participants,
        "consensus-certificate/hash": None,
        "consensus-certificate/signature": None,
    }
    # Seal: compute hash over canonical content (excluding hash and signature)
    exclude = ["consensus-certificate/hash", "consensus-certificate/signature"]
    can_bytes = json.dumps(
        {k: v for k, v in cert.items() if k not in exclude},
        indent=2, default=str, sort_keys=True).encode("utf-8")
    cert_hash = hashlib.sha256(can_bytes).hexdigest()
    cert["consensus-certificate/hash"] = cert_hash
    return cert


def build_disagreement(round_id: str, verdict: str, agreement: list[dict],
                       submissions: list[dict]) -> dict:
    """Build the disagreement report artifact."""
    diverged = [a for a in agreement if a["status"] == "diverged"]
    runner_divergence = []
    for s in submissions:
        rid = s.get("runner-message/runner-id", "?")
        sfields = []
        for a in diverged:
            summary = s.get("runner-message/summary", {})
            if str(summary.get(a["field"])) != str(a["winning-value"]):
                sfields.append(a["field"])
        runner_divergence.append({
            "runner-id": rid,
            "status": s.get("runner-message/status", "?"),
            "divergent-fields": sfields,
        })
    report = {
        "disagreement/schema-version": DISAGREEMENT_SCHEMA_VERSION,
        "disagreement/round-id": round_id,
        "disagreement/status": verdict,
        "disagreement/timestamp": datetime.now(timezone.utc).isoformat(),
        "disagreement/summary": {
            "fields-compared": len(agreement),
            "fields-agreed": len(agreement) - len(diverged),
            "fields-diverged": len(diverged),
        },
        "disagreement/fields": diverged,
        "disagreement/runners": runner_divergence,
        "disagreement/hash": None,
    }
    exclude = ["disagreement/hash"]
    can_bytes = json.dumps(
        {k: v for k, v in report.items() if k not in exclude},
        indent=2, default=str, sort_keys=True).encode("utf-8")
    report["disagreement/hash"] = hashlib.sha256(can_bytes).hexdigest()
    return report


def write_evidence_node(consensus_dir: Path, cert_hash: str,
                        verdict: str) -> dict | None:
    """Write a lightweight evidence node pointing to the consensus certificate.
    
    Returns the node dict or None on failure.
    """
    if not consensus_dir.exists():
        return None
    try:
        ts = datetime.now(timezone.utc).isoformat()
        node = {
            "node-hash": cert_hash,
            "schema-version": 1,
            "node-id": cert_hash,
            "timestamp": ts,
            "execution": {
                "execution-id": "execution/consensus",
                "execution-kind": "consensus-verification",
                "runner": "coordinator/local-filesystem",
            },
            "result": {
                "status": "pass" if verdict == "confirmed" else "fail",
            },
            "evidence": {
                "inputs-hash": cert_hash,
                "outputs-hash": cert_hash,
            },
            "parent-hashes": [],
        }
        out_path = consensus_dir / f"node-{cert_hash[:16]}.json"
        out_path.write_text(json.dumps(node, indent=2, default=str))
        return node
    except Exception:
        return None


def run_consensus(suite_key: str | None = None,
                  run_dirs: list[Path] | None = None,
                  run_count: int = 3,
                  threshold: int | None = None,
                  output_dir: str | Path | None = None,
                  output_base: str | Path | None = None,
                  label: str | None = None,
                  no_harden: bool = True,
                  mailbox_dir: str | Path | None = None,
                  run_request_hash: str | None = None) -> dict:
    """Run the full consensus lifecycle.
    
    Either specify run_dirs (pre-existing bundles), run_count (will execute),
    or mailbox_dir + run_request_hash (mailbox submissions).
    Returns the consensus round manifest.
    """
    round_id = f"round-{datetime.now(timezone.utc).strftime('%Y-%m-%dT%H-%M-%SZ')}"
    if output_dir is None:
        output_dir = Path.cwd() / "consensus-results" / round_id
    output_dir = Path(output_dir).expanduser().resolve()
    staging_dir = output_dir / "staging"
    consensus_dir = output_dir / "consensus"
    staging_dir.mkdir(parents=True, exist_ok=True)
    consensus_dir.mkdir(parents=True, exist_ok=True)
    
    started_at = datetime.now(timezone.utc).isoformat()
    
    # Phase: Collect — from mailbox, dirs, or fresh runs
    if mailbox_dir and run_request_hash:
        from scripts.forensic.mailbox import load_result_submissions as mb_load
        mb_path = Path(mailbox_dir).expanduser().resolve()
        submissions, mb_warnings = mb_load(mb_path, run_request_hash)
        for w in mb_warnings:
            code = w.get("warning/code", "?")
            msg = w.get("warning/message", "")
            print(f"  mailbox warning [{code}]: {msg}", file=sys.stderr)
        print(f"  collected {len(submissions)} valid submission(s) from mailbox",
              file=sys.stderr)
    elif run_dirs:
        submissions = collect_submissions(run_dirs)
    else:
        from scripts.forensic.run import run_forensic
        submissions = []
        for i in range(run_count):
            run_id = f"{round_id}-runner-{i}"
            print(f"  [{i+1}/{run_count}] executing {run_id}...", file=sys.stderr)
            start = time.time()
            exit_code = run_forensic(
                label=f"consensus-{i}",
                no_harden=no_harden,
                output_base=output_base,
            )
            elapsed_ms = int((time.time() - start) * 1000)
            # Find the run directory
            if output_base:
                ob = Path(output_base).expanduser().resolve()
            else:
                from scripts.forensic.run import PRF_RUNS_ROOT
                ob = PRF_RUNS_ROOT
            latest = sorted(ob.iterdir())[-1] if ob.exists() else None
            submission = {
                "runner-message/schema-version": "runner-message.v1",
                "runner-message/type": "result-submission",
                "runner-message/timestamp": datetime.now(timezone.utc).isoformat(),
                "runner-message/runner-id": f"runner/local-forensic-{i}",
                "runner-message/run-id": latest.name if latest else run_id,
                "runner-message/status": "pass" if exit_code == 0 else "fail",
                "runner-message/exit-code": exit_code,
                "runner-message/elapsed-ms": elapsed_ms,
                "runner-message/bundle-path": str(latest) if latest else None,
            }
            if latest:
                bp = _find_bundle_root(latest)
                bundle = load_bundle(bp) if bp else None
                if bundle:
                    submission["runner-message/summary"] = _build_summary(bundle)
            submissions.append(submission)
            # Stash the submission
            sf = staging_dir / f"runner-{i}-submission.json"
            sf.write_text(json.dumps(submission, indent=2, default=str))
    
    # Phase: Compare
    n = len(submissions)
    if threshold is None:
        threshold = max(2, n - 1)
    agreement = compute_agreement(submissions, threshold)
    exit_codes = [s.get("runner-message/exit-code", -1) for s in submissions]
    verdict = compute_verdict(agreement, exit_codes)
    completed_at = datetime.now(timezone.utc).isoformat()
    
    # Phase: Finalize — write artifacts
    cert = build_certificate(round_id, verdict, agreement, submissions, threshold)
    cert_path = consensus_dir / "consensus-certificate.json"
    cert_path.write_text(json.dumps(cert, indent=2, default=str))
    write_sealed_json(cert_path, cert)
    print(f"  wrote consensus certificate: {cert['consensus-certificate/hash'][:16]}...",
          file=sys.stderr)
    
    if verdict == "diverged":
        disagreement = build_disagreement(round_id, verdict, agreement, submissions)
        dis_path = consensus_dir / "disagreement-report.json"
        write_sealed_json(dis_path, disagreement)
        print(f"  wrote disagreement report: {disagreement['disagreement/hash'][:16]}...",
              file=sys.stderr)
    
    # Evidence node
    ch = agreed_hash(agreement)
    node = write_evidence_node(consensus_dir, ch or "none", verdict)
    if node:
        print(f"  wrote consensus evidence node: {node['node-hash'][:16]}...",
              file=sys.stderr)
    
    # Mailbox writeback if mailbox mode
    if mailbox_dir and run_request_hash:
        from scripts.forensic.mailbox import write_consensus_outputs as mb_write
        mb_path = Path(mailbox_dir).expanduser().resolve()
        dis = build_disagreement(round_id, verdict, agreement, submissions) if verdict == "diverged" else None
        mb_write(mb_path, run_request_hash,
                 certificate=cert, disagreement=dis, evidence_node=node)
        print(f"  wrote consensus outputs to mailbox", file=sys.stderr)
    
    # Round manifest
    manifest = {
        "consensus/schema-version": SCHEMA_VERSION,
        "consensus/round-id": round_id,
        "consensus/status": verdict,
        "consensus/type": "local-repeated-execution",
        "consensus/coordinator-id": "coordinator/local-filesystem",
        "consensus/started-at": started_at,
        "consensus/completed-at": completed_at,
        "consensus/runner-count": n,
        "consensus/threshold": threshold,
        "consensus/participants": [
            {"runner-id": s.get("runner-message/runner-id", "?"),
             "status": s.get("runner-message/status", "?")}
            for s in submissions],
        "consensus/submission-count": len(submissions),
        "consensus/fields-compared": len(agreement),
        "consensus/fields-agreed": sum(1 for a in agreement if a["status"] == "confirmed"),
        "consensus/fields-diverged": sum(1 for a in agreement if a["status"] == "diverged"),
        "consensus/volatile-fields-excluded": [v["field"] for v in VOLATILE_FIELDS_EXCLUDED],
        "consensus/agreement": agreement,
        "consensus/verdict": verdict,
    }
    manifest_path = output_dir / "round-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, default=str))
    print(f"\n  Consensus verdict: {verdict}", file=sys.stderr)
    print(f"  Output: {output_dir}", file=sys.stderr)
    return manifest


# ── CLI entry point ────────────────────────────────────────────────────────


def main():
    import argparse
    parser = argparse.ArgumentParser(
        description="Phase 1 local consensus coordinator for forensic evidence runners")
    parser.add_argument("--suite", help="Suite key to run")
    parser.add_argument("--runs", type=int, default=3,
                        help="Number of runs to execute (default: 3)")
    parser.add_argument("--threshold", type=int, default=None,
                        help="Agreement threshold (default: max(2, runs-1))")
    parser.add_argument("--from-dirs", nargs="+", default=None,
                        help="Use existing run directories instead of executing")
    parser.add_argument("--output-dir", default=None,
                        help="Output directory for consensus artifacts")
    parser.add_argument("--output-base", default=None,
                        help="Base directory for run output")
    parser.add_argument("--label", default=None, help="Label for the consensus round")
    parser.add_argument("--no-harden", action="store_true",
                        help="Skip output hardening")
    parser.add_argument("--from-mailbox", default=None,
                        help="Use mailbox directory instead of executing or dirs")
    parser.add_argument("--run-request-hash", default=None,
                        help="Run request hash (required with --from-mailbox)")
    args = parser.parse_args()
    
    run_dirs = [Path(d) for d in args.from_dirs] if args.from_dirs else None
    
    manifest = run_consensus(
        suite_key=args.suite,
        run_dirs=run_dirs,
        run_count=args.runs,
        threshold=args.threshold,
        output_dir=args.output_dir,
        output_base=args.output_base,
        label=args.label,
        no_harden=args.no_harden,
        mailbox_dir=args.from_mailbox,
        run_request_hash=args.run_request_hash,
    )
    status = manifest.get("consensus/status", "failed")
    sys.exit(0 if status in ("confirmed", "confirmed-with-failures") else 1)


if __name__ == "__main__":
    main()
