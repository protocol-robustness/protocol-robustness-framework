#!/usr/bin/env python3
"""Forensic run verification.

Validates a forensic run output directory: checks bundle root integrity,
verifies all referenced files exist, and reports on evidence DAG consistency.

Usage:
    python3 scripts/forensic/verify.py <run-dir>
    python3 scripts/forensic/verify.py ~/prf-runs/2026-06-26T17-30Z-sew-yield-suite
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

# Bundle root keys excluded from self-referential hash computation
_SIGN_EXCLUDE_KEYS = {"bundle/id", "bundle/hash",
                       "bundle/signature", "bundle/signing-key-id"}

# Ensure project root is on sys.path for sibling module imports
_project_root = Path(__file__).resolve().parent.parent.parent
if str(_project_root) not in sys.path:
    sys.path.insert(0, str(_project_root))


SCHEMA_VERSION = "forensic-verify.v1"


# ── Verification model ─────────────────────────────────────────────────────

@dataclass
class VerifyCheck:
    key: str
    status: str   # "pass" | "fail" | "skip"
    message: str
    severity: str  # "required" | "warning" | "info"

    def to_dict(self) -> dict:
        return {"check/key": self.key,
                "check/status": self.status,
                "check/message": self.message,
                "check/severity": self.severity}


@dataclass
class VerifyReport:
    schema_version: str = SCHEMA_VERSION
    run_dir: str = ""
    status: str = "pass"
    checks: list[dict] = field(default_factory=list)
    summary: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        return {"verify/schema-version": self.schema_version,
                "verify/run-dir": self.run_dir,
                "verify/status": self.status,
                "verify/checks": self.checks,
                "verify/summary": self.summary}


# ── Required files ─────────────────────────────────────────────────────────

REQUIRED_FILES = [
    "preflight-report.json",
    "source-snapshot.json",
    "environment.json",
    "input-manifest.json",
    "run-overview.json",
    "run-bundle-root.json",
]

OPTIONAL_FILES = [
    "run-request.json",
    "registry-snapshot.json",
    "evidence-policy.edn",
    "run-output.log",
    "run-output.json",
    "results-summary.json",
    "evidence-dag-inventory.json",
    "deps.edn",
    "clojure-bundle-root.json",
]

MECHANISM_DERIVED_FILES = [
    "mechanism-persistence-index.json",
    "mechanism-persistence-summary.json",
    "mechanism-scenario-matrix.json",
]

OPTIONAL_DIRS = [
    "scenarios",
]

REQUIRED_DIRS = [
    "evidence-dag",
    "claims",
    "attestations",
    "anchors",
]


# ── Checks ─────────────────────────────────────────────────────────────────

def check_exists(run_dir: Path, rel_path: str,
                 severity: str = "required") -> VerifyCheck:
    full = run_dir / rel_path
    key = f"file-exists-{rel_path.replace('/', '-')}"
    if full.exists():
        return VerifyCheck(key=key, status="pass",
                           message=f"{rel_path} exists", severity=severity)
    if severity == "info":
        return VerifyCheck(key=key, status="info",
                           message=f"Optional {rel_path} not found at {full}",
                           severity=severity)
    return VerifyCheck(key=key, status="fail",
                       message=f"{rel_path} not found at {full}",
                       severity=severity)


def check_dir_exists(run_dir: Path, rel_path: str) -> VerifyCheck:
    full = run_dir / rel_path
    key = f"dir-exists-{rel_path.replace('/', '-')}"
    if full.exists() and full.is_dir():
        return VerifyCheck(key=key, status="pass",
                           message=f"Directory {rel_path}/ exists",
                           severity="required")
    return VerifyCheck(key=key, status="fail",
                       message=f"Directory {rel_path}/ not found at {full}",
                       severity="required")


def _load_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text())
    except Exception:
        return None


def _bundle_root_reference(bundle_root: dict) -> str | None:
    return (bundle_root.get("dag/root-node-hash")
            or bundle_root.get("execution/node-hash")
            or bundle_root.get("execution/content-hash"))


def _detect_dag_cycles(edges: list[dict],
                       node_hashes: set[str]) -> list[list[str]]:
    """Detect cycles in a DAG using DFS.
    Returns a list of cycles, each as a list of hashes forming the cycle."""
    adj: dict[str, list[str]] = {h: [] for h in node_hashes}
    for e in edges:
        p, c = e.get("parent"), e.get("child")
        if p in adj and c in adj:
            adj[p].append(c)

    WHITE, GRAY, BLACK = 0, 1, 2
    color = {h: WHITE for h in node_hashes}
    cycles: list[list[str]] = []
    parent: dict[str, str | None] = {h: None for h in node_hashes}

    def dfs(u: str) -> None:
        color[u] = GRAY
        for v in adj.get(u, []):
            if v not in color:
                continue
            if color[v] == GRAY:
                # Found a cycle, reconstruct it
                cycle = [v, u]
                cur = u
                while cur != v and parent.get(cur) is not None:
                    cur = parent[cur]  # type: ignore
                    if cur is not None:
                        cycle.append(cur)
                if cycle and cycle[0] == cycle[-1]:
                    cycles.append(list(reversed(cycle[:-1])))
                else:
                    cycles.append(list(reversed(cycle)))
            elif color[v] == WHITE:
                parent[v] = u
                dfs(v)
        color[u] = BLACK

    for h in node_hashes:
        if color.get(h, WHITE) == WHITE:
            dfs(h)

    return cycles


def _validate_evidence_dag_inventory(inventory: dict,
                                     bundle_roots: list[dict],
                                     dag_file_count: int) -> tuple[bool, list[str]]:
    errors: list[str] = []
    nodes = inventory.get("dag/nodes", [])
    edges = inventory.get("dag/edges", [])
    file_hashes = inventory.get("dag/file-hashes", [])
    dag_index = inventory.get("dag/index")
    parseable_nodes = [n for n in nodes if not n.get("parse-error")]
    node_hashes = {n.get("node-hash") for n in parseable_nodes if n.get("node-hash")}

    if inventory.get("dag/semantic-status") == "inventory-only" and dag_file_count > 0:
        errors.append("evidence-dag inventory did not semantically parse any EDN nodes")

    if len(file_hashes) != dag_file_count:
        errors.append(f"inventory file count mismatch: inventory={len(file_hashes)} disk={dag_file_count}")

    duplicate_hashes = sorted({h for h in node_hashes if list(map(lambda n: n.get("node-hash"), parseable_nodes)).count(h) > 1})
    if duplicate_hashes:
        errors.append(f"duplicate node hashes found: {duplicate_hashes}")

    parsed_names = {n.get("file") for n in nodes if n.get("file")}
    if len(parsed_names) != len(nodes):
        errors.append("one or more evidence-dag nodes are missing file names")

    parse_errors = [n for n in nodes if n.get("parse-error")]
    if parse_errors:
        errors.append(f"{len(parse_errors)} evidence-dag file(s) failed to parse")

    # Cycle detection via DFS
    if node_hashes:
        cycles = _detect_dag_cycles(edges, node_hashes)
        if cycles:
            cycle_descs = [" -> ".join(c[:5]) + ("..." if len(c) > 5 else "")
                           for c in cycles[:5]]
            errors.append(f"evidence DAG contains {len(cycles)} cycle(s): {cycle_descs}")

    unresolved_parents: set[str] = set()
    edge_pairs = {(e.get("parent"), e.get("child")) for e in edges if e.get("parent") and e.get("child")}
    edge_count = 0
    for node in parseable_nodes:
        child = node.get("node-hash")
        parents = [p for p in node.get("parent-hashes", []) if p]
        edge_count += len(parents)
        for parent in parents:
            if parent not in node_hashes:
                unresolved_parents.add(parent)
            if (parent, child) not in edge_pairs:
                errors.append(f"missing DAG edge {parent} -> {child}")

    if unresolved_parents:
        errors.append(f"unresolved parent hashes: {sorted(unresolved_parents)}")

    if len(edge_pairs) != edge_count:
        errors.append(f"edge count mismatch: inventory={len(edge_pairs)} expected={edge_count}")

    root_refs = [ref for ref in (_bundle_root_reference(root) for root in bundle_roots) if ref]
    if root_refs:
        if not any(ref in node_hashes for ref in root_refs):
            errors.append("bundle root references do not resolve to an evidence-dag node: "
                          + ", ".join(root_refs))
    elif parseable_nodes:
            errors.append("bundle root does not include a DAG root reference")

    # Validate dag/index if present
    if dag_index is not None:
        idx_summary = dag_index.get("dag-index/summary", {})
        idx_node_count = idx_summary.get("node-count", 0)
        if idx_node_count != len(parseable_nodes):
            errors.append(f"dag/index node count mismatch: index={idx_node_count} parsed={len(parseable_nodes)}")
        idx_roots = dag_index.get("dag-index/roots", [])
        if parseable_nodes and not idx_roots:
            errors.append("dag/index reports no roots but parsed nodes exist")
        idx_orphans = idx_summary.get("orphan-count", 0)
        if idx_orphans > 0:
            errors.append(f"dag/index reports {idx_orphans} orphan node(s) with unresolved parents")

    return (not errors, errors)


def _canonical_json_bytes(data: dict, exclude_keys: list[str]) -> bytes:
    return json.dumps(
        {k: v for k, v in data.items() if k not in exclude_keys},
        indent=2, default=str, sort_keys=True).encode("utf-8")


def check_bundle_root_hash(run_dir: Path) -> VerifyCheck:
    """Recompute bundle root self-referential hash from canonical fields."""
    path = run_dir / "run-bundle-root.json"
    data = _load_json(path)
    if not data:
        return VerifyCheck(key="bundle-root-hash", status="skip",
                           message="Cannot verify hash: bundle root not parseable",
                           severity="warning")
    recorded = data.get("bundle/hash") or data.get("bundle/id")
    if not recorded:
        return VerifyCheck(key="bundle-root-hash", status="fail",
                           message="Bundle root has no bundle/hash or bundle/id",
                           severity="warning")
    expected = hashlib.sha256(
        _canonical_json_bytes(data, list(_SIGN_EXCLUDE_KEYS))).hexdigest()
    if recorded == expected:
        return VerifyCheck(key="bundle-root-hash", status="pass",
                           message=f"Bundle root hash matches ({recorded[:16]}...)",
                           severity="warning")
    return VerifyCheck(key="bundle-root-hash", status="fail",
                       message=f"Bundle root hash MISMATCH: recorded={recorded[:16]}... expected={expected[:16]}...",
                       severity="warning")


def check_bundle_signature(run_dir: Path,
                           public_key_path: str | None = None) -> VerifyCheck:
    """Verify Ed25519 signature on bundle root hash.
    Requires --public-key to verify; without it the check is skipped."""
    path = run_dir / "run-bundle-root.json"
    data = _load_json(path)
    if not data:
        return VerifyCheck(key="bundle-signature", status="skip",
                           message="Cannot verify signature: bundle root not parseable",
                           severity="warning")

    sig = data.get("bundle/signature")
    kid = data.get("bundle/signing-key-id")
    bundle_hash = data.get("bundle/hash")

    if not sig or not bundle_hash:
        return VerifyCheck(key="bundle-signature", status="info",
                           message="Bundle is not signed (no signature field)",
                           severity="info")

    if not public_key_path:
        return VerifyCheck(key="bundle-signature", status="info",
                           message=f"Bundle signed with key '{kid}' but no --public-key provided to verify",
                           severity="info")

    pk_path = Path(public_key_path).expanduser()
    if not pk_path.exists():
        return VerifyCheck(key="bundle-signature", status="fail",
                           message=f"Public key not found at {pk_path}",
                           severity="required")

    try:
        clj_code = (
            f"(require '[resolver-sim.benchmark.signing :as s]) "
            f"(println (s/verify-signature \"{bundle_hash}\" \"{sig}\" "
            f"\"{pk_path}\"))")
        r = subprocess.run(
            ["clojure", "-M:with-sew", "-e", clj_code],
            capture_output=True, text=True, timeout=60)
        valid = r.stdout.strip() == "true"
        if valid:
            key_label = kid or public_key_path
            return VerifyCheck(key="bundle-signature", status="pass",
                               message=f"Ed25519 signature valid (key: {key_label})",
                               severity="warning")
        return VerifyCheck(key="bundle-signature", status="fail",
                           message=f"Ed25519 signature INVALID (key: {kid or public_key_path})",
                           severity="required")
    except Exception as e:
        return VerifyCheck(key="bundle-signature", status="fail",
                           message=f"Signature verification error: {e}",
                           severity="required")


def check_results_summary(run_dir: Path) -> VerifyCheck:
    """Validate results-summary.json if present."""
    path = run_dir / "results-summary.json"
    if not path.exists():
        return VerifyCheck(key="results-summary", status="info",
                           message="No results-summary.json (not produced by older runs)",
                           severity="info")
    data = _load_json(path)
    if not data:
        return VerifyCheck(key="results-summary", status="fail",
                           message="results-summary.json is not valid JSON",
                           severity="warning")
    status = data.get("results/status", "unknown")
    suite_key = data.get("results/suite-key", "unknown")
    return VerifyCheck(key="results-summary", status="pass",
                       message=f"Status: {status}, suite: {suite_key}",
                       severity="info")


def check_mechanism_persistence_artifacts(run_dir: Path) -> VerifyCheck:
    """Report presence and schema versions for derived mechanism artifacts."""
    present = []
    for fname in MECHANISM_DERIVED_FILES:
        path = run_dir / fname
        if not path.exists():
            continue
        data = _load_json(path)
        sv = data.get("schema-version") if isinstance(data, dict) else None
        present.append(f"{fname} ({sv or 'unparseable'})")
    if not present:
        return VerifyCheck(
            key="mechanism-persistence-artifacts",
            status="info",
            message="No mechanism persistence artifacts present (optional derived output)",
            severity="info",
        )
    return VerifyCheck(
        key="mechanism-persistence-artifacts",
        status="info",
        message="Mechanism persistence artifacts present: " + ", ".join(present),
        severity="info",
    )


def check_overview_hash(run_dir: Path) -> VerifyCheck:
    """Recompute overview self-referential hash from canonical fields."""
    path = run_dir / "run-overview.json"
    data = _load_json(path)
    if not data:
        return VerifyCheck(key="overview-hash", status="skip",
                           message="Cannot verify hash: overview not parseable",
                           severity="warning")
    recorded = data.get("overview/hash")
    if not recorded:
        return VerifyCheck(key="overview-hash", status="fail",
                           message="Run overview has no overview/hash field",
                           severity="warning")
    expected = hashlib.sha256(
        _canonical_json_bytes(data, ["overview/hash"])).hexdigest()
    if recorded == expected:
        return VerifyCheck(key="overview-hash", status="pass",
                           message=f"Overview hash matches ({recorded[:16]}...)",
                           severity="warning")
    return VerifyCheck(key="overview-hash", status="fail",
                       message=f"Overview hash MISMATCH: recorded={recorded[:16]}... expected={expected[:16]}...",
                       severity="warning")


def check_bundle_root_valid(run_dir: Path) -> VerifyCheck:
    path = run_dir / "run-bundle-root.json"
    if not path.exists():
        return VerifyCheck(key="bundle-root-valid", status="fail",
                           message="run-bundle-root.json not found",
                           severity="required")
    try:
        data = json.loads(path.read_text())
        required_keys = ["bundle/schema-version", "bundle/id",
                         "overview/hash", "preflight"]
        missing = [k for k in required_keys if k not in data]
        if missing:
            return VerifyCheck(key="bundle-root-valid", status="fail",
                               message=f"Bundle root missing keys: {missing}",
                               severity="required")
        if data.get("bundle/schema-version") != "bundle-root.v1":
            return VerifyCheck(key="bundle-root-valid", status="fail",
                               message="Bundle root has wrong schema version",
                               severity="required")
        return VerifyCheck(key="bundle-root-valid", status="pass",
                           message=f"Bundle root valid (id: {data.get('bundle/id', '?')})",
                           severity="required")
    except Exception as e:
        return VerifyCheck(key="bundle-root-valid", status="fail",
                           message=f"Bundle root parse error: {e}",
                           severity="required")


def check_overview_valid(run_dir: Path) -> VerifyCheck:
    path = run_dir / "run-overview.json"
    if not path.exists():
        return VerifyCheck(key="overview-valid", status="fail",
                           message="run-overview.json not found",
                           severity="required")
    try:
        data = json.loads(path.read_text())
        required = ["run-id", "status", "exit-code"]
        missing = [k for k in required if k not in data]
        if missing:
            return VerifyCheck(key="overview-valid", status="fail",
                               message=f"Run overview missing keys: {missing}",
                               severity="required")
        return VerifyCheck(key="overview-valid", status="pass",
                           message=f"Run overview valid (id: {data.get('run-id', '?')})",
                           severity="required")
    except Exception as e:
        return VerifyCheck(key="overview-valid", status="fail",
                           message=f"Run overview parse error: {e}",
                           severity="required")


def check_preflight_valid(run_dir: Path) -> VerifyCheck:
    path = run_dir / "preflight-report.json"
    if not path.exists():
        return VerifyCheck(key="preflight-valid", status="fail",
                           message="preflight-report.json not found",
                           severity="required")
    try:
        data = json.loads(path.read_text())
        status = data.get("preflight/status", "unknown")
        if status in ("pass", "pass-with-warnings"):
            return VerifyCheck(key="preflight-valid", status="pass",
                               message=f"Preflight status: {status}",
                               severity="required")
        return VerifyCheck(key="preflight-valid", status="fail",
                           message=f"Preflight status: {status}",
                           severity="required")
    except Exception as e:
        return VerifyCheck(key="preflight-valid", status="fail",
                           message=f"Preflight parse error: {e}",
                           severity="required")


ALLOWED_CLAIM_STATUSES = frozenset({":pass", ":fail", ":inconclusive", ":not-evaluated",
                                     "pass", "fail", "inconclusive", "not-evaluated"})
ALLOWED_CLAIM_RESULTS = frozenset({":verified", ":reproduced", ":certified", ":approved", ":rejected",
                                    "verified", "reproduced", "certified", "approved", "rejected"})


def _sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _canonical_json(data: dict, exclude: list[str]) -> bytes:
    return json.dumps(
        {k: v for k, v in data.items() if k not in exclude},
        indent=2, default=str, sort_keys=True).encode("utf-8")


def check_dir_has_json(run_dir: Path, rel_dir: str) -> VerifyCheck:
    """Check that a required directory exists and contains at least one .json file."""
    d = run_dir / rel_dir
    key = f"dir-has-files-{rel_dir.replace('/', '-')}"
    if not d.exists() or not d.is_dir():
        return VerifyCheck(key=key, status="fail",
                           message=f"Directory {rel_dir}/ not found",
                           severity="required")
    files = [f for f in d.iterdir() if f.is_file() and f.suffix == ".json"]
    if not files:
        return VerifyCheck(key=key, status="fail",
                           message=f"Directory {rel_dir}/ exists but contains no .json files",
                           severity="required")
    return VerifyCheck(key=key, status="pass",
                       message=f"Directory {rel_dir}/ contains {len(files)} .json file(s)",
                       severity="required")


def check_claim_file(run_dir: Path, fname: str) -> VerifyCheck:
    """Validate a single claim result file: hash integrity, schema version, status."""
    path = run_dir / "claims" / fname
    key = f"claim-valid-{fname}"
    data = _load_json(path)
    if not data:
        return VerifyCheck(key=key, status="fail",
                           message=f"Claim {fname}: not valid JSON",
                           severity="required")
    # Schema version
    sv = data.get("result/schema-version")
    if sv != "forensic-claim-result.v1":
        return VerifyCheck(key=key, status="fail",
                           message=f"Claim {fname}: expected schema-version forensic-claim-result.v1, got {sv}",
                           severity="required")
    # Self-referential hash
    recorded = data.get("result/hash")
    if not recorded:
        return VerifyCheck(key=key, status="fail",
                           message=f"Claim {fname}: missing result/hash",
                           severity="required")
    expected = _sha256_hex(_canonical_json(data, ["result/hash"]))
    if recorded != expected:
        return VerifyCheck(key=key, status="fail",
                           message=f"Claim {fname}: hash MISMATCH (recorded={recorded[:16]}... expected={expected[:16]}...)",
                           severity="required")
    # Status
    status = data.get("result/status")
    if status not in ALLOWED_CLAIM_STATUSES:
        return VerifyCheck(key=key, status="warning",
                           message=f"Claim {fname}: unexpected status '{status}'",
                           severity="warning")
    return VerifyCheck(key=key, status="pass",
                       message=f"Claim {fname}: valid (status={status}, hash={recorded[:16]}...)",
                       severity="required")


def check_claims_content(run_dir: Path) -> list[VerifyCheck]:
    """Phase C: validate all claim result files in claims/."""
    d = run_dir / "claims"
    if not d.exists() or not d.is_dir():
        return [VerifyCheck(key="claims-content", status="fail",
                            message="claims/ directory not found",
                            severity="required")]
    results = []
    files = sorted([f for f in d.iterdir() if f.is_file() and f.suffix == ".json"])
    if not files:
        return [VerifyCheck(key="claims-content", status="fail",
                            message="claims/ contains no .json files",
                            severity="required")]
    for f in files:
        results.append(check_claim_file(run_dir, f.name))
    return results


def check_attestation_file(run_dir: Path, fname: str) -> VerifyCheck:
    """Validate a single attestation file: hash integrity, schema version, claim-result."""
    path = run_dir / "attestations" / fname
    key = f"attestation-valid-{fname}"
    data = _load_json(path)
    if not data:
        return VerifyCheck(key=key, status="fail",
                           message=f"Attestation {fname}: not valid JSON",
                           severity="required")
    sv = data.get("attestation/schema-version")
    if sv != "forensic-attestation.v1":
        return VerifyCheck(key=key, status="fail",
                           message=f"Attestation {fname}: expected schema-version forensic-attestation.v1, got {sv}",
                           severity="required")
    cr = data.get("attestation/claim-result")
    if cr not in ALLOWED_CLAIM_RESULTS:
        return VerifyCheck(key=key, status="warning",
                           message=f"Attestation {fname}: unexpected claim-result '{cr}'",
                           severity="warning")
    # Self-referential hash (exclude id, hash, signature)
    recorded = data.get("attestation/hash")
    if not recorded:
        return VerifyCheck(key=key, status="fail",
                           message=f"Attestation {fname}: missing attestation/hash",
                           severity="required")
    expected = _sha256_hex(_canonical_json(data, ["attestation/id", "attestation/hash",
                                                   "attestation/signature"]))
    if recorded != expected:
        return VerifyCheck(key=key, status="fail",
                           message=f"Attestation {fname}: hash MISMATCH (recorded={recorded[:16]}... expected={expected[:16]}...)",
                           severity="required")
    return VerifyCheck(key=key, status="pass",
                       message=f"Attestation {fname}: valid (result={cr}, hash={recorded[:16]}...)",
                       severity="required")


def check_attestations_content(run_dir: Path) -> list[VerifyCheck]:
    """Phase C: validate all attestation files in attestations/."""
    d = run_dir / "attestations"
    if not d.exists() or not d.is_dir():
        return [VerifyCheck(key="attestations-content", status="fail",
                            message="attestations/ directory not found",
                            severity="required")]
    results = []
    files = sorted([f for f in d.iterdir() if f.is_file() and f.suffix == ".json"])
    if not files:
        return [VerifyCheck(key="attestations-content", status="fail",
                            message="attestations/ contains no .json files",
                            severity="required")]
    for f in files:
        results.append(check_attestation_file(run_dir, f.name))
    return results


def check_evidence_dag(run_dir: Path) -> VerifyCheck:
    dag_dir = run_dir / "evidence-dag"
    if not dag_dir.exists():
        return VerifyCheck(key="evidence-dag-present", status="fail",
                           message="evidence-dag/ directory not found",
                           severity="required")
    files = [f for f in dag_dir.iterdir() if f.is_file()]
    edn_files = [f for f in files if f.suffix == ".edn"]
    inventory_path = run_dir / "evidence-dag-inventory.json"
    inventory = _load_json(inventory_path) if inventory_path.exists() else None
    bundle_root = _load_json(run_dir / "run-bundle-root.json")
    clojure_bundle_root = _load_json(run_dir / "clojure-bundle-root.json")
    bundle_root_ref = _bundle_root_reference(clojure_bundle_root or bundle_root or {})
    bundle_roots = [root for root in [clojure_bundle_root, bundle_root] if root]

    if inventory:
        ok, errors = _validate_evidence_dag_inventory(inventory,
                                                      bundle_roots,
                                                      len(edn_files))
        if ok:
            ref_msg = f", root={bundle_root_ref[:16]}..." if bundle_root_ref else ""
            return VerifyCheck(
                key="evidence-dag-present",
                status="pass",
                message=(f"evidence-dag/ contains {len(files)} file(s); "
                         f"parsed {len(inventory.get('dag/nodes', []))} node(s){ref_msg}"),
                severity="required")
        return VerifyCheck(
            key="evidence-dag-present",
            status="fail",
            message="; ".join(errors),
            severity="required")

    # Older runs may not have the inventory file yet; keep this as an
    # informational structural check, but require some node content.
    if not edn_files:
        return VerifyCheck(key="evidence-dag-present", status="info",
                           message=f"evidence-dag/ contains {len(files)} file(s)",
                           severity="info")
    if bundle_root_ref:
        return VerifyCheck(key="evidence-dag-present", status="pass",
                           message=(f"evidence-dag/ contains {len(files)} file(s); "
                                    f"bundle root references {bundle_root_ref[:16]}..."),
                           severity="required")
    return VerifyCheck(key="evidence-dag-present", status="info",
                       message=f"evidence-dag/ contains {len(files)} file(s)",
                       severity="info")


def check_anchor_content(run_dir: Path) -> VerifyCheck:
    """Validate anchor-cursor.json and associated TSA artifacts."""
    path = run_dir / "anchors" / "anchor-cursor.json"
    key = "anchor-valid"
    if not path.exists():
        return VerifyCheck(key=key, status="info",
                           message="No anchor-cursor.json (pre-Phase C bundles)",
                           severity="info")
    data = _load_json(path)
    if not data:
        return VerifyCheck(key=key, status="fail",
                           message="anchor-cursor.json is not valid JSON",
                           severity="warning")
    atype = data.get("anchor/type", "?")
    sv = data.get("anchor/schema-version")
    if sv != "anchor-cursor.v1":
        return VerifyCheck(key=key, status="warning",
                           message=f"anchor-cursor.json: unexpected schema-version {sv}",
                           severity="warning")
    if atype == "rfc3161":
        tsr_path = run_dir / "anchors" / (data.get("anchor/tsa-token-path", "registry.tsr"))
        tsa_json_path = run_dir / "anchors" / "registry.tsa.json"
        if not tsr_path.exists():
            return VerifyCheck(key=key, status="fail",
                               message=f"anchor type=rfc3161 but TSA token not found at {tsr_path.name}",
                               severity="required")
        if not tsa_json_path.exists():
            return VerifyCheck(key=key, status="pass",
                               message="anchor type=rfc3161 (TSA token present, no metadata file)",
                               severity="info")
        return VerifyCheck(key=key, status="pass",
                           message=f"anchor type=rfc3161, TSA URL: {data.get('anchor/tsa-url', '?')}",
                           severity="info")
    elif atype == "local-proof":
        return VerifyCheck(key=key, status="pass",
                           message="anchor type=local-proof (no external TSA)",
                           severity="info")
    elif atype == "mock":
        return VerifyCheck(key=key, status="info",
                           message="anchor type=mock (placeholder, no timestamp anchoring)",
                           severity="info")
    else:
        return VerifyCheck(key=key, status="info",
                           message=f"anchor type={atype}",
                           severity="info")


def check_no_extra_dirs(run_dir: Path) -> VerifyCheck:
    """Warn about unexpected top-level entries in the run directory."""
    known = set(REQUIRED_FILES + OPTIONAL_FILES + MECHANISM_DERIVED_FILES
                + [d + "/" for d in REQUIRED_DIRS]
                + [d + "/" for d in OPTIONAL_DIRS])
    extra = []
    for entry in run_dir.iterdir():
        name = entry.name + "/" if entry.is_dir() else entry.name
        if name not in known:
            extra.append(entry.name)
    if extra:
        return VerifyCheck(key="no-extra-entries", status="info",
                           message=f"Extra entries in run dir: {extra}",
                           severity="info")
    return VerifyCheck(key="no-extra-entries", status="pass",
                       message="No unexpected entries in run directory",
                       severity="info")


# ── Main verification ─────────────────────────────────────────────────────

def verify_run(run_dir: str | Path,
               public_key_path: str | None = None) -> VerifyReport:
    run_dir = Path(run_dir).expanduser().resolve()

    if not run_dir.exists():
        return VerifyReport(
            schema_version=SCHEMA_VERSION,
            run_dir=str(run_dir),
            status="fail",
            checks=[VerifyCheck(key="run-dir-exists", status="fail",
                                message=f"Run directory not found: {run_dir}",
                                severity="required").to_dict()],
            summary={"total": 1, "pass": 0, "fail": 1, "warning": 0, "info": 0})

    results: list[VerifyCheck] = []

    # Check required files
    for f in REQUIRED_FILES:
        results.append(check_exists(run_dir, f, severity="required"))

    # Check optional files
    for f in OPTIONAL_FILES:
        results.append(check_exists(run_dir, f, severity="info"))

    # Check required directories
    for d in REQUIRED_DIRS:
        results.append(check_dir_exists(run_dir, d))

    # Check optional directories
    for d in OPTIONAL_DIRS:
        results.append(check_exists(run_dir, d, severity="info"))

    # Structural checks
    results.append(check_bundle_root_valid(run_dir))
    results.append(check_overview_valid(run_dir))
    results.append(check_preflight_valid(run_dir))
    results.append(check_evidence_dag(run_dir))
    results.append(check_no_extra_dirs(run_dir))

    # Sealing integrity checks (warning severity)
    results.append(check_bundle_root_hash(run_dir))
    results.append(check_overview_hash(run_dir))
    results.append(check_bundle_signature(run_dir, public_key_path))
    results.append(check_results_summary(run_dir))
    results.append(check_mechanism_persistence_artifacts(run_dir))

    # Phase C: claims and attestations content validation
    results.extend(check_claims_content(run_dir))
    results.extend(check_attestations_content(run_dir))

    # Anchor content validation
    results.append(check_anchor_content(run_dir))

    # Aggregate
    check_dicts = [r.to_dict() for r in results]
    required_fails = sum(1 for r in results
                         if r.severity == "required" and r.status == "fail")
    warnings = sum(1 for r in results if r.status == "warning")
    passes = sum(1 for r in results if r.status == "pass")
    infos = sum(1 for r in results if r.severity == "info")

    if required_fails > 0:
        status = "fail"
    elif warnings > 0:
        status = "pass-with-warnings"
    else:
        status = "pass"

    return VerifyReport(
        schema_version=SCHEMA_VERSION,
        run_dir=str(run_dir),
        status=status,
        checks=check_dicts,
        summary={
            "total": len(results),
            "pass": passes,
            "fail": required_fails,
            "warning": warnings,
            "info": infos,
        })


# ── CLI entry point ────────────────────────────────────────────────────────

def main():
    import argparse
    parser = argparse.ArgumentParser(
        description="Forensic run verification")
    parser.add_argument("run_dir", help="Path to forensic run directory")
    parser.add_argument("--public-key",
                        help="Path to Ed25519 public key for signature verification")
    parser.add_argument("--output", "-o",
                        help="Write verification report to file")
    args = parser.parse_args()

    report = verify_run(args.run_dir, public_key_path=args.public_key)

    output = json.dumps(report.to_dict(), indent=2)
    if args.output:
        Path(args.output).expanduser().write_text(output)
        print(f"Verification report written to {args.output}", file=sys.stderr)
    else:
        print(output)

    s = report.summary
    print(f"\nVerify status: {report.status} "
          f"({s['pass']} pass, {s['fail']} fail, "
          f"{s['warning']} warn, {s['info']} info)",
          file=sys.stderr)
    sys.exit(0 if report.status in ("pass", "pass-with-warnings") else 1)


if __name__ == "__main__":
    main()
