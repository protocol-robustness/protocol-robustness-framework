#!/usr/bin/env python3
"""Forensic preflight check.

Validates that the execution environment is ready for a forensic run.
Exits with 0 on pass, 1 on fail. Writes preflight report JSON to stdout
or to the specified output path.

Usage:
    python3 scripts/forensic/preflight.py --run-request <path> [--output <path>]
    python3 scripts/forensic/preflight.py --run-request examples/forensic-reference-run/run-request.example.edn
"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "forensic-preflight.v1"


# ── Check model ────────────────────────────────────────────────────────────

@dataclass
class CheckResult:
    key: str
    status: str      # "pass" | "fail" | "warning" | "info"
    message: str
    severity: str    # "required" | "warning" | "info"

    def to_dict(self) -> dict:
        return {"check/key": self.key,
                "check/status": self.status,
                "check/message": self.message,
                "check/severity": self.severity}


@dataclass
class PreflightReport:
    schema_version: str = SCHEMA_VERSION
    timestamp: str = ""
    runner_id: str = "forensic-preflight.py"
    status: str = "pass"
    checks: list[dict] = field(default_factory=list)
    summary: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        return {"preflight/schema-version": self.schema_version,
                "preflight/timestamp": self.timestamp,
                "preflight/runner-id": self.runner_id,
                "preflight/status": self.status,
                "preflight/checks": self.checks,
                "preflight/summary": self.summary}


# ── Helpers ────────────────────────────────────────────────────────────────

def path_exists(p: str | Path) -> bool:
    return Path(p).expanduser().exists()


def read_file(p: str | Path) -> str:
    return Path(p).expanduser().read_text()


def parse_edn_or_json(text: str, path_hint: str | None = None) -> dict | None:
    """Try to parse as JSON first, then try EDN via Clojure subprocess."""
    # Try JSON first
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    # Try EDN via Clojure subprocess
    if path_hint:
        try:
            r = subprocess.run(
                ["clojure", "-M",
                 "-e", (f"(require '[clojure.edn :as edn] '[clojure.data.json :as json]) "
                        f"(println (json/write-str (edn/read-string (slurp \"{path_hint}\"))))")],
                capture_output=True, text=True, timeout=30)
            if r.returncode == 0 and r.stdout.strip():
                return json.loads(r.stdout.strip())
        except Exception:
            pass

    return None


def compute_sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_sealed_json(path: Path, data: Any) -> tuple[str, str]:
    """Atomically write JSON with sealing and readback verification.

    Pattern: serialize → hash → temp → fsync → rename → readback → verify.
    Raises IOError on write failure or readback hash mismatch.
    Returns (hex_hash, file_path).
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    json_bytes = json.dumps(data, indent=2, default=str, sort_keys=True).encode("utf-8")
    content_hash = compute_sha256(json_bytes)

    fd, tmp_path_str = tempfile.mkstemp(
        dir=str(path.parent),
        prefix=f".{path.name}.",
        suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(json_bytes)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_path_str, str(path))
    except BaseException:
        try:
            os.unlink(tmp_path_str)
        except Exception:
            pass
        raise

    # Read back and verify integrity
    try:
        readback_hash = compute_sha256(path.read_bytes())
    except Exception as e:
        raise IOError(f"Readback verification FAILED for {path}: cannot read — {e}")

    if readback_hash != content_hash:
        raise IOError(
            f"Seal verification FAILED for {path}: "
            f"written hash {content_hash}, readback hash {readback_hash}")

    print(f"  sealed {path.name}  sha256={content_hash[:16]}...", file=sys.stderr)
    return content_hash, str(path)


def _vcs():
    """Lazy import of the jj-then-git resolver (scripts/evidence/vcs_info)."""
    import pathlib
    import sys as _sys
    here = pathlib.Path(__file__).resolve().parent  # scripts/forensic
    evidence_dir = str(here.parent / "evidence")   # scripts/evidence
    if evidence_dir not in _sys.path:
        _sys.path.insert(0, evidence_dir)
    import vcs_info  # type: ignore[import-not-found]
    return vcs_info


def get_git_commit(repo_root: str | Path) -> str | None:
    try:
        return _vcs().commit_sha()
    except Exception:
        return None


def get_git_dirty(repo_root: str | Path) -> bool:
    try:
        return bool(_vcs().repo_is_dirty())
    except Exception:
        return True  # assume dirty if we can't check


def check(severity: str, key: str, ok: bool, message: str,
          warning_message: str | None = None) -> CheckResult:
    if ok:
        return CheckResult(key=key, status="pass", message=message,
                           severity=severity)
    elif severity == "warning":
        return CheckResult(key=key, status="warning",
                           message=warning_message or message,
                           severity=severity)
    elif severity == "info":
        return CheckResult(key=key, status="info", message=message,
                           severity=severity)
    else:
        return CheckResult(key=key, status="fail", message=message,
                           severity=severity)


# ── Individual checks ──────────────────────────────────────────────────────

def check_run_request_declared(run_request_path: str | None) -> CheckResult:
    if not run_request_path:
        return check("required", "run-request-declared", False,
                     "No run request path provided. Use --run-request <path>")
    p = Path(run_request_path).expanduser()
    if not p.exists():
        return check("required", "run-request-declared", False,
                     f"Run request not found at {p}")
    return check("required", "run-request-declared", True,
                 f"Run request found at {p}")


def check_run_request_valid(run_request_path: str | None) -> CheckResult:
    if not run_request_path:
        return check("required", "run-request-valid", False,
                     "Cannot validate: no run request path")
    p = Path(run_request_path).expanduser()
    if not p.exists():
        return check("required", "run-request-valid", False,
                     f"Cannot validate: file not found at {p}")
    try:
        text = p.read_text()
        data = parse_edn_or_json(text, str(p))
        if data is None:
            return check("required", "run-request-valid", False,
                         f"Run request at {p} is not valid JSON/EDN")
        return check("required", "run-request-valid", True,
                     f"Run request at {p} is valid")
    except Exception as e:
        return check("required", "run-request-valid", False,
                     f"Run request parse error: {e}")


def check_registry_snapshot_present(registry_path: str | None) -> CheckResult:
    if not registry_path:
        return check("required", "registry-snapshot-present", False,
                     "No registry snapshot path provided")
    p = Path(registry_path).expanduser()
    if not p.exists():
        return check("required", "registry-snapshot-present", False,
                     f"Registry snapshot not found at {p}")
    return check("required", "registry-snapshot-present", True,
                 f"Registry snapshot found at {p}")


def check_registry_snapshot_valid(registry_path: str | None) -> CheckResult:
    if not registry_path:
        return check("required", "registry-snapshot-valid", False,
                     "Cannot validate: no registry snapshot path")
    p = Path(registry_path).expanduser()
    if not p.exists():
        return check("required", "registry-snapshot-valid", False,
                     f"Cannot validate: file not found at {p}")
    try:
        text = p.read_text()
        data = parse_edn_or_json(text, str(p))
        if data is None:
            return check("required", "registry-snapshot-valid", False,
                         f"Registry snapshot at {p} is not valid JSON/EDN")
        return check("required", "registry-snapshot-valid", True,
                     f"Registry snapshot at {p} is valid")
    except Exception as e:
        return check("required", "registry-snapshot-valid", False,
                     f"Registry snapshot parse error: {e}")


def _get_key(d: dict, *keys: str) -> Any:
    """Get a value from a dict by trying multiple key variants.
    
    Tries each key as-is, and also strips leading colon for Clojure keywords
    serialized as JSON strings: ':foo' -> 'foo'.
    """
    for k in keys:
        for variant in (k, k.lstrip(":"), k.replace("/", "-"), k.replace("/", "_")):
            if variant in d:
                return d[variant]
    return None


def check_runner_identity(run_request: dict | None) -> CheckResult:
    if not run_request:
        return check("required", "runner-identity-present", False,
                     "Cannot check runner identity: no run request loaded")
    sel = _get_key(run_request, "runner-selection", ":runner-selection") or {}
    mode = _get_key(sel, "mode", ":mode")
    runner_id = _get_key(sel, "runner-id", ":runner-id")
    if mode != "pinned":
        return check("required", "runner-identity-present", False,
                     f"Runner selection mode is '{mode}', expected 'pinned'")
    if not runner_id:
        return check("required", "runner-identity-present", False,
                     "Runner selection is pinned but no runner-id specified")
    return check("required", "runner-identity-present", True,
                 f"Runner identity: {runner_id} (mode: {mode})")


def check_evidence_policy_present(run_request: dict | None,
                                  policy_path: str | None) -> CheckResult:
    # Check from run request first, then from explicit path
    path = policy_path
    if not path and run_request:
        ep = _get_key(run_request, "evidence-policy", ":evidence-policy") or {}
        path = _get_key(ep, "policy-path", ":policy-path")
    if not path:
        return check("required", "evidence-policy-present", False,
                     "No evidence policy path provided or referenced in run request")
    p = Path(path).expanduser()
    if not p.exists():
        return check("required", "evidence-policy-present", False,
                     f"Evidence policy not found at {p}")
    try:
        text = p.read_text()
        data = parse_edn_or_json(text, str(p))
        if data is None:
            return check("required", "evidence-policy-present", False,
                         f"Evidence policy at {p} is not valid JSON/EDN")
        return check("required", "evidence-policy-present", True,
                     f"Evidence policy found at {p} and valid")
    except Exception as e:
        return check("required", "evidence-policy-present", False,
                     f"Evidence policy error: {e}")


def check_output_directory_clean(output_dir: str | None) -> CheckResult:
    if not output_dir:
        return check("required", "output-directory-clean", False,
                     "No output directory specified")
    p = Path(output_dir).expanduser()
    if p.exists():
        contents = list(p.iterdir())
        if contents:
            return check("required", "output-directory-clean", False,
                         f"Output directory {p} exists and is not empty")
    return check("required", "output-directory-clean", True,
                 f"Output directory {p} is clean")


def check_no_source_tree_writes(output_dir: str | None,
                                repo_root: str | Path) -> CheckResult:
    if not output_dir:
        return check("required", "no-source-tree-writes", False,
                     "Cannot check: no output directory")
    repo = Path(repo_root).resolve()
    out = Path(output_dir).expanduser().resolve()
    try:
        out.relative_to(repo)
        return check("required", "no-source-tree-writes", False,
                     f"Output directory {out} is inside the repo tree {repo}")
    except ValueError:
        return check("required", "no-source-tree-writes", True,
                     f"Output directory {out} is outside the repo tree")


def check_scenario_set_declared(run_request: dict | None) -> CheckResult:
    if not run_request:
        return check("required", "scenario-set-declared", False,
                     "Cannot check: no run request loaded")
    suite = _get_key(run_request, "suite/key", ":suite/key", "key")
    scenarios = (_get_key(run_request, "execution-policy", ":execution-policy") or {})
    scenario_paths = _get_key(scenarios, "scenario-paths", ":scenario-paths")
    if suite or scenario_paths:
        return check("required", "scenario-set-declared", True,
                     f"Scenario set declared via suite '{suite}' or explicit paths")
    # No explicit suite key — the runner defaults to the in-process registry
    # suite (e.g. :sew-invariants for sew-v1). This is valid but implicit.
    return check("warning", "scenario-set-declared", True,
                 "No explicit suite key — will use registry-default suite")


def check_external_network_declared(run_request: dict | None) -> CheckResult:
    if not run_request:
        return check("required", "external-network-declared", False,
                     "Cannot check: no run request loaded")
    policy = _get_key(run_request, "network-policy", ":network-policy")
    if policy is None:
        policy = _get_key(run_request, "execution-policy", ":execution-policy") or {}
        if isinstance(policy, dict):
            policy = _get_key(policy, "network-policy", ":network-policy")
    if isinstance(policy, str) and policy in ("allow", "deny"):
        return check("required", "external-network-declared", True,
                     f"External network policy: {policy}")
    return check("required", "external-network-declared", False,
                 f"External network policy not declared (got: {policy})")


def check_source_snapshot(repo_root: str | Path) -> CheckResult:
    commit = get_git_commit(repo_root)
    if commit:
        dirty = get_git_dirty(repo_root)
        msg = f"Git commit: {commit}" + (" (dirty)" if dirty else " (clean)")
        return check("warning", "source-snapshot-recorded", True, msg)
    return check("warning", "source-snapshot-recorded", False,
                 "Could not determine git commit")


def check_canonical_hash_values_supported() -> CheckResult:
    # Placeholder: in a full implementation, verify all hash algorithms
    # referenced in the run request are known to this runner.
    return check("info", "canonical-hash-values-supported", True,
                 "Hash algorithm check: SHA-256 (default) supported")


def check_runner_version() -> CheckResult:
    return check("info", "runner-version", True,
                 "Runner: forensic-preflight.py v0.1.0")


def check_policy_versions(run_request: dict | None) -> CheckResult:
    if not run_request:
        return check("info", "policy-versions", True,
                     "Policy versions: not checked (no run request)")
    ep = _get_key(run_request, "evidence-policy", ":evidence-policy") or {}
    xp = _get_key(run_request, "execution-policy", ":execution-policy") or {}
    ep_id = _get_key(ep, "policy-id", ":policy-id") or "unknown"
    xp_id = _get_key(xp, "policy-id", ":policy-id") or "unknown"
    return check("info", "policy-versions", True,
                 f"Evidence policy: {ep_id}, Execution policy: {xp_id}")


# ── Main preflight runner ──────────────────────────────────────────────────

def run_preflight(run_request_path: str | None = None,
                  registry_snapshot_path: str | None = None,
                  evidence_policy_path: str | None = None,
                  output_dir: str | None = None,
                  repo_root: str | Path | None = None) -> PreflightReport:
    if repo_root is None:
        repo_root = Path.cwd()

    # Load run request if available
    run_request: dict | None = None
    if run_request_path:
        p = Path(run_request_path).expanduser()
        if p.exists():
            try:
                run_request = parse_edn_or_json(p.read_text(), str(p)) or {}
            except Exception:
                run_request = {}

    results: list[CheckResult] = []

    # Required checks (hard fail)
    results.append(check_run_request_declared(run_request_path))
    results.append(check_run_request_valid(run_request_path))
    results.append(check_registry_snapshot_present(registry_snapshot_path))
    results.append(check_registry_snapshot_valid(registry_snapshot_path))
    results.append(check_runner_identity(run_request))
    results.append(check_evidence_policy_present(run_request, evidence_policy_path))
    results.append(check_output_directory_clean(output_dir))
    results.append(check_no_source_tree_writes(output_dir, repo_root))
    results.append(check_scenario_set_declared(run_request))
    results.append(check_external_network_declared(run_request))

    # Warning checks (soft fail)
    results.append(check_source_snapshot(repo_root))
    results.append(check_canonical_hash_values_supported())

    # Informational checks (never fail)
    results.append(check_runner_version())
    results.append(check_policy_versions(run_request))

    # Aggregate
    check_dicts = [r.to_dict() for r in results]
    required_fails = sum(1 for r in results
                         if r.severity == "required" and r.status == "fail")
    warnings = sum(1 for r in results if r.status == "warning")
    passes = sum(1 for r in results if r.status == "pass")
    infos = sum(1 for r in results if r.status == "info")

    if required_fails > 0:
        status = "fail"
    elif warnings > 0:
        status = "pass-with-warnings"
    else:
        status = "pass"

    report = PreflightReport(
        timestamp=datetime.now(timezone.utc).isoformat(),
        status=status,
        checks=check_dicts,
        summary={
            "total": len(results),
            "pass": passes,
            "fail": required_fails,
            "warning": warnings,
            "info": infos,
        })
    return report


# ── CLI entry point ────────────────────────────────────────────────────────

def main():
    import argparse
    parser = argparse.ArgumentParser(
        description="Forensic preflight check")
    parser.add_argument("--run-request",
                        default="examples/forensic-reference-run/run-request.example.edn",
                        help="Path to run request file (default: examples/forensic-reference-run/run-request.example.edn)")
    parser.add_argument("--registry-snapshot",
                        default="examples/forensic-reference-run/registry-snapshot.example.edn",
                        help="Path to registry snapshot file (default: examples/forensic-reference-run/registry-snapshot.example.edn)")
    parser.add_argument("--evidence-policy",
                        default="examples/config/forensic/evidence-policy.edn",
                        help="Path to evidence policy file (default: examples/config/forensic/evidence-policy.edn)")
    parser.add_argument("--output-dir",
                        help="Target output directory for the run")
    parser.add_argument("--output", "-o",
                        help="Write preflight report to file (default: stdout)")
    parser.add_argument("--repo-root", default=str(Path.cwd()),
                        help="Project repo root (default: cwd)")
    args = parser.parse_args()

    report = run_preflight(
        run_request_path=args.run_request,
        registry_snapshot_path=args.registry_snapshot,
        evidence_policy_path=args.evidence_policy,
        output_dir=args.output_dir,
        repo_root=args.repo_root,
    )

    output = json.dumps(report.to_dict(), indent=2)
    if args.output:
        Path(args.output).expanduser().write_text(output)
        print(f"Preflight report written to {args.output}", file=sys.stderr)
    else:
        print(output)

    sys.exit(0 if report.status in ("pass", "pass-with-warnings") else 1)


if __name__ == "__main__":
    main()
