#!/usr/bin/env python3
"""Forensic run orchestrator.

Runs preflight checks, creates an isolated output directory in ~/prf-runs/,
invokes the scenario execution pipeline, and produces a run bundle.

Usage:
    python3 scripts/forensic/run.py --run-request <path> [--label <label>] [--dry-run]
"""

from __future__ import annotations

import fcntl
import json
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

# Ensure project root is on sys.path for sibling module imports
_project_root = Path(__file__).resolve().parent.parent.parent
if str(_project_root) not in sys.path:
    sys.path.insert(0, str(_project_root))

from scripts.forensic.preflight import (run_preflight, parse_edn_or_json,
                                        _get_key, write_sealed_json,
                                        compute_sha256)
from scripts.forensic.verify import verify_run
from scripts.forensic.isolation_checks import run_isolation_checks
from scripts.forensic.source_hash import (SOURCE_TREE_HASH_ALGORITHM,
                                          compute_source_byte_size,
                                          compute_source_tree_hash,
                                          normalize_source_roots)

SIGN_EXCLUDE_KEYS = frozenset({"bundle/id", "bundle/hash",
                                "bundle/signature", "bundle/signing-key-id"})

SCHEMA_VERSION = "forensic-run.v1"
PRF_RUNS_ROOT = Path(os.environ.get("PRF_RUNS_ROOT", "~/prf-runs")).expanduser()

# Configurable hardening constants (override via env)
PRF_SOURCE_ROOTS = normalize_source_roots(os.environ.get("PRF_SOURCE_ROOTS"))
PRF_ARTIFACT_DIR = os.environ.get("PRF_ARTIFACT_DIR", "results/test-artifacts")
PRF_MAX_RUNS = int(os.environ.get("PRF_MAX_RUNS", "0"))  # 0 = unlimited
PRF_TSA_URL = os.environ.get("PRF_TSA_URL") or None  # RFC 3161 TSA URL
_LOCK_PATH = Path("results/.forensic-run.lock").resolve()

# ── Helpers ────────────────────────────────────────────────────────────────

def make_run_id(label: str | None = None) -> str:
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    if label:
        # Sanitize: keep alphanumeric, hyphens, underscores
        safe = "".join(c if c.isalnum() or c in "-_" else "-" for c in label)
        return f"{ts}-{safe}"
    return ts


def _acquire_lock() -> Any:
    """Acquire an exclusive file lock on the shared artifact directory.
    Blocks until the lock is available.  Auto-releases when the process dies.
    Returns the lock file handle (must be kept alive while locked)."""
    _LOCK_PATH.parent.mkdir(parents=True, exist_ok=True)
    lf = open(_LOCK_PATH, "w")
    try:
        fcntl.flock(lf, fcntl.LOCK_EX)
    except Exception:
        lf.close()
        raise
    print(f"  acquired lock {_LOCK_PATH}", file=sys.stderr)
    return lf


def _release_lock(lf: Any) -> None:
    """Release the file lock."""
    try:
        fcntl.flock(lf, fcntl.LOCK_UN)
        lf.close()
    except Exception:
        pass
    print(f"  released lock {_LOCK_PATH}", file=sys.stderr)


def _enforce_retention(output_base: Path, max_runs: int) -> None:
    """Remove oldest run directories beyond max_runs."""
    if max_runs <= 0:
        return
    if not output_base.exists():
        return
    dirs = sorted([d for d in output_base.iterdir()
                   if d.is_dir() and _is_forensic_run_dir(d)])
    if len(dirs) <= max_runs:
        return
    to_remove = dirs[:len(dirs) - max_runs]
    for d in to_remove:
        try:
            shutil.rmtree(str(d), ignore_errors=True)
            print(f"  removed old run: {d.name}", file=sys.stderr)
        except Exception as e:
            print(f"  warning: could not remove {d.name}: {e}", file=sys.stderr)


_RUN_DIR_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}Z")


def _is_forensic_run_dir(d: Path) -> bool:
    """Check if a directory matches the forensic run naming pattern."""
    return bool(_RUN_DIR_PATTERN.match(d.name))


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


def snapshot_source(repo_root: Path, run_dir: Path) -> dict:
    info: dict[str, Any] = {}
    try:
        info["git_commit"] = _vcs().commit_sha() or "unknown"
    except Exception:
        info["git_commit"] = "unknown"
    try:
        info["dirty"] = bool(_vcs().repo_is_dirty())
    except Exception:
        info["dirty"] = True

    info["repo_root"] = str(repo_root.resolve())

    try:
        info["byte-size"] = compute_source_byte_size(repo_root, PRF_SOURCE_ROOTS)
    except Exception:
        info["byte-size"] = 0

    try:
        ch, included_roots = compute_source_tree_hash(repo_root, PRF_SOURCE_ROOTS)
        info["code-hash"] = ch
        info["code-hash-algorithm"] = SOURCE_TREE_HASH_ALGORITHM
        info["included-roots"] = included_roots
        info["source-hash"] = ch
        info["source-hash-algorithm"] = SOURCE_TREE_HASH_ALGORITHM
        info["source-hash-roots"] = included_roots
    except Exception:
        info["code-hash"] = "unknown"
        info["code-hash-algorithm"] = "unknown"
        info["included-roots"] = []
        info["source-hash"] = "unknown"
        info["source-hash-algorithm"] = "unknown"
        info["source-hash-roots"] = []

    return info


def snapshot_environment() -> dict:
    import platform
    return {
        "os": platform.system(),
        "os_release": platform.release(),
        "python_version": platform.python_version(),
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }


# write_json replaced by write_sealed_json from preflight module


def sign_bundle_hash(bundle_hash: str, key_path: str,
                     key_id: str | None = None) -> tuple[str, str]:
    """Sign a bundle hash with an Ed25519 key using the Clojure signing
    infrastructure. Returns (hex_signature, key_id)."""
    if key_id is None:
        key_id = Path(key_path).name
    try:
        clj_code = (
            f"(require '[resolver-sim.benchmark.signing :as s]) "
            f"(println (s/sign-hash \"{bundle_hash}\" \"{key_path}\" nil))")
        r = subprocess.run(
            ["clojure", "-M:with-sew", "-e", clj_code],
            capture_output=True, text=True, timeout=60)
        if r.returncode != 0:
            raise RuntimeError(f"Clojure signing failed: {r.stderr.strip()}")
        sig = r.stdout.strip()
        if not sig:
            raise RuntimeError("Clojure signing returned empty signature")
        print(f"  signed bundle root with key {key_id}", file=sys.stderr)
        return sig, key_id
    except Exception as e:
        raise RuntimeError(f"Bundle signing failed: {e}") from e


def make_output_immutable(run_dir: Path) -> None:
    """Make output directory read-only after run completes.
    First removes write permission from all files and directories,
    then tries to set the immutable filesystem attribute (best-effort)."""
    try:
        subprocess.run(["chmod", "-R", "a-w", str(run_dir)],
                       check=True, capture_output=True, timeout=30)
        print(f"  output tree set to read-only (chmod -R a-w)", file=sys.stderr)
    except Exception as e:
        print(f"  warning: could not set read-only: {e}", file=sys.stderr)
        return

    # Best-effort: try to set immutable filesystem attribute
    try:
        subprocess.run(["chattr", "-R", "+i", str(run_dir)],
                       capture_output=True, timeout=30)
        print(f"  immutable flag set (chattr +i)", file=sys.stderr)
    except Exception:
        pass  # chattr typically requires root or CAP_LINUX_IMMUTABLE


def run_invoke_scenario_pipeline(run_request_path: str,
                                 run_dir: Path,
                                 source_info: dict | None = None,
                                 run_id: str = "",
                                 workspace_dir: Path | None = None,
                                 tsa_url: str | None = None) -> int:
    """Invoke the existing scenario run pipeline.
    Returns exit code. Output passes through to terminal (not captured)."""
    run_request = None
    try:
        p = Path(run_request_path).expanduser()
        text = p.read_text()
        run_request = parse_edn_or_json(text, str(p)) or {}
    except Exception:
        pass

    suite_key = _get_key(run_request, "suite/key", ":suite/key", "key") if run_request else None

    # Build command using -e directly (the :run alias's :main-opts doesn't
    # reliably pass --output-file through the Clojure CLI with -M).
    run_expr = ("(require '[resolver-sim.core]) "
                "(->> *command-line-args* (map str) (apply resolver-sim.core/-main))")
    cmd = ["clojure", "-M:with-sew", "-e", run_expr,
           "--", "--invariants"]
    if suite_key:
        cmd.extend(["--suite", str(suite_key)])
    output_path = run_dir / "clojure-bundle-root.json"
    cmd.extend(["--output-file", str(output_path)])

    # Set PRF_* environment variables for attribution bridge (Phase B)
    env = os.environ.copy()
    if source_info:
        env["PRF_SOURCE_TREE_HASH"] = source_info.get("source-hash", "")
        env["PRF_SOURCE_TREE_HASH_ALGORITHM"] = \
            source_info.get("source-hash-algorithm", "")
        env["PRF_SOURCE_TREE_HASH_ROOTS"] = ",".join(source_info.get("source-hash-roots", []))
        env["PRF_SOURCE_COMMIT"] = source_info.get("git_commit", "") or ""
        env["PRF_SOURCE_DIRTY"] = str(
            bool(source_info.get("dirty", False))).lower()
    env["PRF_ORCHESTRATION_RUNNER_ID"] = "forensic-runner.py"
    env["PRF_BUNDLE_ID"] = run_id
    if tsa_url:
        env["PRF_TSA_URL"] = tsa_url

    # Per-run workspace: redirect Clojure artifact output to private workspace dir
    if workspace_dir:
        workspace_dir.mkdir(parents=True, exist_ok=True)
        env["PRF_ARTIFACT_DIR"] = str(workspace_dir)

    print(f"  executing: {' '.join(cmd)}", file=sys.stderr)
    # Run with capture_output=False so output passes through to terminal
    # (Clojure/JVM output goes through paths that require a TTY and cannot be
    #  reliably captured via PIPE. Exit code is the reliable signal.)
    r = subprocess.run(cmd, capture_output=False, timeout=600, env=env)
    if output_path.exists():
        print(f"  captured Clojure bundle root ({output_path.stat().st_size} bytes)",
              file=sys.stderr)
    return r.returncode


def _parse_edn_nodes_semantic(dag_dir: Path) -> tuple[list[dict], list[dict], list[dict]]:
    """Parse EDN evidence node files semantically using Clojure reader.
    Returns (parsed_nodes, parse_errors, edge_list).
    parsed_nodes is a list of dicts with node-hash, execution-id, etc.
    parse_errors is a list of dicts with :file and :parse-error for unparseable EDN.
    edge_list is a list of {parent, child} dicts for DAG structure.
    Returns ([], [], []) on failure or if no EDN files present."""
    edn_files = sorted([f for f in dag_dir.iterdir()
                        if f.is_file() and f.suffix == ".edn"])
    if not edn_files:
        return [], [], []
    try:
        import tempfile
        # Write Clojure code to a temp file to avoid shell escaping issues
        # Use %-formatting for the path vector to avoid f-string brace conflicts
        path_vec = "[" + " ".join('"%s"' % p for p in edn_files) + "]"
        clj_template = """\
(require '[clojure.edn :as edn] '[clojure.data.json :as json])

(defn parse-node [path]
  (try
    (let [node (edn/read-string (slurp path))]
      {:node-hash (:node-hash node)
       :execution-id (str (:execution-id (:execution node)))
       :execution-kind (str (:execution-kind (:execution node)))
       :runner (str (:runner (:execution node)))
       :result-status (str (:status (:result node)))
       :parent-hashes (vec (map str (:parent-hashes node)))
       :record-hash (:record-hash node)
       :file (.getName (java.io.File. path))})
    (catch Exception e
      {:file (.getName (java.io.File. path))
       :parse-error (.getMessage e)})))

(defn separate-errors [nodes]
  (let [good (filter :node-hash nodes)
        bad (filter :parse-error nodes)]
    {:parsed (vec good) :parse-errors (vec bad)}))

(defn build-edges [nodes]
  (mapcat
    (fn [node]
      (let [ch (:node-hash node)]
        (map (fn [ph] {:parent ph :child ch})
             (:parent-hashes node))))
    nodes))

(let [paths %s
      raw-nodes (doall (map parse-node paths))
      {:keys [parsed parse-errors]} (separate-errors raw-nodes)
      edges (build-edges parsed)]
  (println (json/write-str {:parsed parsed :parse-errors parse-errors :edges edges})))
""" % path_vec
        with tempfile.NamedTemporaryFile(mode="w", suffix=".clj",
                                         delete=False) as tmp:
            tmp_path = tmp.name
            tmp.write(clj_template)
        r = subprocess.run(
            ["clojure", "-M:with-sew", tmp_path],
            capture_output=True, text=True, timeout=120)
        os.unlink(tmp_path)
        if r.returncode != 0:
            print(f"  warning: EDN semantic parse failed: {r.stderr.strip()[:300]}",
                  file=sys.stderr)
            return [], [], []
        import json as _json
        out = r.stdout.strip()
        if not out:
            print("  warning: EDN semantic parse returned empty output",
                  file=sys.stderr)
            return [], [], []
        result = _json.loads(out)
        nodes = result.get("parsed", [])
        parse_errors = result.get("parse-errors", [])
        edges = result.get("edges", [])
        if parse_errors:
            print(f"  warning: {len(parse_errors)} EDN file(s) failed to parse",
                  file=sys.stderr)
            for pe in parse_errors:
                print(f"    {pe.get('file')}: {pe.get('parse-error', '?')[:120]}",
                      file=sys.stderr)
        print(f"  parsed {len(nodes)} EDN node(s) semantically ({len(edges)} DAG edge(s))",
              file=sys.stderr)
        return nodes, parse_errors, edges
    except Exception as e:
        print(f"  warning: EDN semantic parse error: {e}", file=sys.stderr)
        return [], [], []


def _build_dag_index(nodes: list[dict],
                     edges: list[dict]) -> dict:
    """Build a navigation index from parsed evidence DAG nodes and edges.
    Returns a map suitable for dag/index in the inventory."""
    by_hash: dict[str, dict] = {}
    children_by_parent: dict[str, list[str]] = {}
    parents_by_child: dict[str, list[str]] = {}
    by_execution_id: dict[str, list[str]] = {}
    by_status: dict[str, list[str]] = {}
    short_hashes: dict[str, str] = {}
    all_hashes: set[str] = set()

    for n in nodes:
        h = n.get("node-hash")
        if not h:
            continue
        all_hashes.add(h)
        by_hash[h] = n
        short_hashes[h[:12]] = h
        eid = n.get("execution-id")
        if eid:
            by_execution_id.setdefault(eid, []).append(h)
        st = n.get("result-status")
        if st:
            by_status.setdefault(st, []).append(h)

    for n in nodes:
        h = n.get("node-hash")
        if not h:
            continue
        parents = [p for p in n.get("parent-hashes", []) if p]
        parents_by_child[h] = parents
        for p in parents:
            children_by_parent.setdefault(p, []).append(h)

    roots = sorted(h for h in all_hashes
                   if all((p not in all_hashes) for p in parents_by_child.get(h, [])))
    leaves = sorted([h for h in all_hashes
                     if h not in children_by_parent
                     or not children_by_parent[h]])

    failure_count = len(by_status.get("fail", []))
    error_count = len(by_status.get("error", []))
    orphan_count = len([h for h in all_hashes
                        if any(p not in all_hashes for p in parents_by_child.get(h, []))])

    return {
        "dag-index/schema-version": "evidence-dag-index.v0",
        "dag-index/nodes-by-hash": {h: {
            "node-hash": h,
            "short-hash": h[:12],
            "execution-id": n.get("execution-id"),
            "result-status": n.get("result-status"),
        } for h, n in by_hash.items()},
        "dag-index/children-by-parent": children_by_parent,
        "dag-index/parents-by-child": parents_by_child,
        "dag-index/roots": roots,
        "dag-index/leaves": leaves,
        "dag-index/by-execution-id": by_execution_id,
        "dag-index/by-status": by_status,
        "dag-index/short-hashes": short_hashes,
        "dag-index/summary": {
            "node-count": len(nodes),
            "root-count": len(roots),
            "leaf-count": len(leaves),
            "failure-count": failure_count,
            "error-count": error_count,
            "orphan-count": orphan_count,
            "execution-id-counts": {k: len(v) for k, v in by_execution_id.items()},
            "status-counts": {k: len(v) for k, v in by_status.items()},
        },
    }


def _write_dag_inventory(run_dir: Path, dag_dir: Path,
                          node_file_srcs: list[Path],
                          repo_root: Path,
                          workspace_dir: Path | None = None) -> str:
    """Produce evidence-dag-inventory.json — Phase B (semantic EDN parsing).
    EDN files are parsed to extract node-hash, execution-id, result-status,
    parent-hashes, and DAG edge structure.
    Returns the SHA-256 hash of the written inventory file.
    Uses workspace_dir for registry/cursor reads (per-run protected workspace)."""
    dag_dir.mkdir(exist_ok=True)
    json_count = 0
    edn_count = 0
    total_bytes = 0
    file_hashes: list[dict] = []
    edn_nodes: list[dict] = []
    parse_errors: list[dict] = []
    dag_edges: list[dict] = []
    for f in dag_dir.iterdir():
        if not f.is_file():
            continue
        ext = f.suffix.lower()
        if ext not in (".json", ".edn"):
            continue
        if ext == ".json":
            json_count += 1
        else:
            edn_count += 1
        try:
            fb = f.read_bytes()
            fh = compute_sha256(fb)
            total_bytes += len(fb)
            file_hashes.append({"name": f.name, "sha256": fh, "bytes": len(fb)})
        except Exception:
            file_hashes.append({"name": f.name, "sha256": "unreadable", "bytes": 0})
    total = json_count + edn_count
    # Phase B: semantic EDN parsing
    if edn_count > 0:
        edn_nodes, parse_errors, dag_edges = _parse_edn_nodes_semantic(dag_dir)
    dag_index = _build_dag_index(edn_nodes, dag_edges) if edn_nodes else None
    inventory = {
        "dag/schema-version": "evidence-dag-inventory.v0",
        "dag/phase": "B" if edn_nodes else "A",
        "dag/semantic-status": "parsed" if edn_nodes else "inventory-only",
        "dag/files": {"total": total, "json": json_count,
                      "edn": edn_count, "unparsed": edn_count - len(edn_nodes)},
        "dag/hashes": {"algorithm": "sha256-file-bytes"},
        "dag/total-bytes": total_bytes,
        "dag/file-hashes": file_hashes,
        "dag/nodes": edn_nodes,
        "dag/parse-errors": parse_errors,
        "dag/edges": dag_edges,
        "dag/index": dag_index,
    }
    # Registry consistency check: read from per-run workspace (not shared dir)
    artifact_root = workspace_dir if workspace_dir else (repo_root / PRF_ARTIFACT_DIR)
    registry_path = artifact_root / "test-artifacts.json"
    cursor_path = artifact_root / "chain-cursor-final.json"
    reg_check: dict[str, Any] = {"files-on-disk": total}
    try:
        if registry_path.exists():
            reg_data = json.loads(registry_path.read_text())
            entries = len(reg_data.get("artifacts", reg_data.get("entries", [])))
            reg_check["registry-entries"] = entries
        else:
            reg_check["registry-entries"] = None
    except Exception:
        reg_check["registry-entries"] = None
    try:
        if cursor_path.exists():
            cursor_data = json.loads(cursor_path.read_text())
            reg_check["chain-cursor-seq"] = cursor_data.get("seq", cursor_data.get("chain-cursor/seq"))
        else:
            reg_check["chain-cursor-seq"] = None
    except Exception:
        reg_check["chain-cursor-seq"] = None
    # Determine status
    reg_check["consistency-status"] = "warning"
    inventory["dag/registry-check"] = reg_check
    # Warnings
    warnings: list[dict] = []
    unparsed = edn_count - len(edn_nodes)
    if unparsed > 0:
        warnings.append({
            "code": "edn-unparseable",
            "message": (f"{unparsed} EDN evidence nodes could not be "
                        f"semantically parsed."),
        })
    if parse_errors:
        for pe in parse_errors:
            warnings.append({
                "code": "edn-parse-error",
                "message": f"Parse error in {pe.get('file')}: {pe.get('parse-error', '?')[:200]}",
            })
    inventory["dag/warnings"] = warnings
    write_sealed_json(run_dir / "evidence-dag-inventory.json", inventory)
    dag_inv_hash = compute_sha256(
        (run_dir / "evidence-dag-inventory.json").read_bytes())
    print(f"  wrote evidence-dag-inventory.json  ({total} files, {total_bytes} bytes, "
          f"phase={'B' if edn_nodes else 'A'}, hash={dag_inv_hash[:16]}...)",
          file=sys.stderr)
    return dag_inv_hash


def _bundle_scenario_files(run_dir: Path, suite_key: str) -> None:
    """Look up scenario file paths for a named suite and copy them into the
    bundle's scenarios/ directory for portability."""
    try:
        clj_code = (
            f"(require '[resolver-sim.scenario.suites :as suites]) "
            f"(println (pr-str (suites/suite-paths (keyword \"{suite_key}\"))))")
        r = subprocess.run(
            ["clojure", "-M:with-sew", "-e", clj_code],
            capture_output=True, text=True, timeout=30)
        if r.returncode != 0:
            print(f"  warning: could not look up suite '{suite_key}': {r.stderr.strip()[:100]}",
                  file=sys.stderr)
            return
        paths_str = r.stdout.strip()
        paths = re.findall(r'"([^"]*)"', paths_str)
        if not paths:
            print(f"  warning: could not parse suite paths: {paths_str[:100]}",
                  file=sys.stderr)
            return
        if not paths:
            print(f"  warning: suite '{suite_key}' has no file paths (in-process scenarios)",
                  file=sys.stderr)
            return
        scenario_dir = run_dir / "scenarios"
        scenario_dir.mkdir(exist_ok=True)
        count = 0
        for rel_path in paths:
            src = Path(rel_path)
            if src.exists():
                shutil.copy2(str(src), str(scenario_dir / src.name))
                count += 1
            else:
                print(f"  warning: scenario file not found: {src}", file=sys.stderr)
        print(f"  bundled {count} scenario file(s) to scenarios/", file=sys.stderr)
    except Exception as e:
        print(f"  warning: scenario bundling failed: {e}", file=sys.stderr)


# ── Main run orchestrator ──────────────────────────────────────────────────

def run_forensic(run_request_path: str = "examples/forensic-reference-run/run-request.example.edn",
                 label: str | None = None,
                 registry_snapshot_path: str = "examples/forensic-reference-run/registry-snapshot.example.edn",
                 evidence_policy_path: str = "examples/config/forensic/evidence-policy.edn",
                 output_base: str | Path | None = None,
                 repo_root: str | Path | None = None,
                 dry_run: bool = False,
                 isolation: str = "shared-filesystem",
                  signing_key_path: str | None = None,
                  signing_key_id: str | None = None,
                  no_harden: bool = False,
                  tsa_url: str | None = None) -> int:
    if repo_root is None:
        repo_root = Path.cwd()
    repo_root = Path(repo_root).resolve()

    if output_base is None:
        output_base = PRF_RUNS_ROOT
    output_base = Path(output_base).expanduser().resolve()

    # Determine output directory
    run_id = make_run_id(label)
    run_dir = output_base / run_id

    print(f"=== Forensic Run ===", file=sys.stderr)
    print(f"  run-id:    {run_id}", file=sys.stderr)
    print(f"  output:    {run_dir}", file=sys.stderr)
    print(f"  workspace: {run_dir / 'workspace'}", file=sys.stderr)
    print(f"  isolation: {isolation}", file=sys.stderr)
    tsa_url = tsa_url or PRF_TSA_URL
    print(f"  tsa-url:   {tsa_url or '(none)'}", file=sys.stderr)
    print(f"  request:   {run_request_path}", file=sys.stderr)
    print(f"  repo:      {repo_root}", file=sys.stderr)
    if dry_run:
        print(f"  DRY RUN — no execution", file=sys.stderr)

    # Step 1: Preflight
    print(f"\n--- Preflight ---", file=sys.stderr)
    report = run_preflight(
        run_request_path=run_request_path,
        registry_snapshot_path=registry_snapshot_path,
        evidence_policy_path=evidence_policy_path,
        output_dir=str(run_dir),
        repo_root=repo_root,
    )
    preflight_status = report.status

    # Print preflight summary
    s = report.summary
    print(f"  Status: {preflight_status} "
          f"({s['pass']} pass, {s['fail']} fail, "
          f"{s['warning']} warn, {s['info']} info)",
          file=sys.stderr)

    if preflight_status == "fail":
        print(f"  Preflight FAILED — aborting", file=sys.stderr)
        # Write preflight report to temp location
        if not dry_run:
            tmp_dir = Path("/tmp") / f"forensic-preflight-{run_id}"
            tmp_dir.mkdir(parents=True, exist_ok=True)
            write_sealed_json(tmp_dir / "preflight-report.json", report.to_dict())
            print(f"  Preflight report: {tmp_dir / 'preflight-report.json'}",
                  file=sys.stderr)
        return 1

    if dry_run:
        print(f"\n  DRY RUN — stopping before execution", file=sys.stderr)
        print(f"  Preflight would pass. Output would go to: {run_dir}",
              file=sys.stderr)
        return 0

    # Step 1b: Disk space check (warning only)
    import shutil as _su
    try:
        usage = _su.disk_usage(str(output_base))
        free_gb = usage.free / (1024**3)
        if free_gb < 1.0:
            print(f"  ⚠ Low disk space: {free_gb:.1f} GB free on {output_base}",
                  file=sys.stderr)
    except Exception:
        pass  # non-critical check, skip on error

    # Step 1c: Acquire exclusive lock on artifact directory
    lock_file = _acquire_lock()
    lock_held = True
    try:
        # Step 2: Create output directory and per-run protected workspace
        print(f"\n--- Output Setup ---", file=sys.stderr)
        run_dir.mkdir(parents=True, exist_ok=False)
        (run_dir / "evidence-dag").mkdir()
        (run_dir / "claims").mkdir()
        (run_dir / "attestations").mkdir()
        (run_dir / "anchors").mkdir()
        workspace_dir = run_dir / "workspace"
        workspace_dir.mkdir(parents=True, exist_ok=False)

        # Write preflight report to output
        write_sealed_json(run_dir / "preflight-report.json", report.to_dict())

        # Step 3: Snapshot source and environment
        print(f"\n--- Snapshots ---", file=sys.stderr)
        source_info = snapshot_source(repo_root, run_dir)
        env_info = snapshot_environment()
        write_sealed_json(run_dir / "source-snapshot.json", source_info)
        write_sealed_json(run_dir / "environment.json", env_info)

        # Step 3b: OS-level isolation checks
        print(f"\n--- Isolation Checks ---", file=sys.stderr)
        isolation_report = run_isolation_checks(isolation_mode=isolation)
        for c in isolation_report["isolation/checks"]:
            status = c.get("status", "?")
            label = c.get("check", "?")
            detail = c.get("detail", "")
            print(f"  [{status:>5}] {label}: {detail}", file=sys.stderr)
        print(f"  grade: {isolation_report['isolation/grade']}", file=sys.stderr)

        # Step 4: Copy inputs to output
        print(f"\n--- Inputs ---", file=sys.stderr)
        if run_request_path:
            shutil.copy2(run_request_path, run_dir / "run-request.json")
        if registry_snapshot_path:
            rsp = Path(registry_snapshot_path).expanduser()
            if rsp.exists():
                shutil.copy2(rsp, run_dir / "registry-snapshot.json")
        if evidence_policy_path:
            epp = Path(evidence_policy_path).expanduser()
            if epp.exists():
                shutil.copy2(epp, run_dir / "evidence-policy.edn")

        # Step 5: Write input manifest
        input_manifest = {
            "run-request": str(run_request_path),
            "registry-snapshot": str(registry_snapshot_path or ""),
            "evidence-policy": str(evidence_policy_path or ""),
            "source-snapshot": source_info,
            "environment": env_info,
            "run-timestamp": datetime.now(timezone.utc).isoformat(),
        }
        write_sealed_json(run_dir / "input-manifest.json", input_manifest)

        # Step 6: Execute scenarios
        print(f"\n--- Execution ---", file=sys.stderr)
        t0 = time.time()
        # Resolve TSA URL: CLI arg > PRF_TSA_URL env var > None
        tsa_url = tsa_url or PRF_TSA_URL
        exit_code = run_invoke_scenario_pipeline(
            run_request_path, run_dir,
            source_info=source_info, run_id=run_id,
            workspace_dir=workspace_dir,
            tsa_url=tsa_url)
        elapsed_ms = int((time.time() - t0) * 1000)
        print(f"  exit code: {exit_code}  ({elapsed_ms}ms)", file=sys.stderr)

        # Step 6b: Bundle scenario files and deps.edn for portability
        run_request_data = None
        if run_request_path:
            try:
                run_request_data = parse_edn_or_json(
                    Path(run_request_path).expanduser().read_text(), run_request_path)
            except Exception:
                pass
        suite_key = _get_key(run_request_data, "suite/key", ":suite/key", "key") if run_request_data else None
        if suite_key:
            _bundle_scenario_files(run_dir, suite_key)
        # Snapshot deps.edn for dependency tracking
        deps_path = Path("deps.edn")
        if deps_path.exists():
            try:
                shutil.copy2(str(deps_path), str(run_dir / "deps.edn"))
                print(f"  bundled deps.edn", file=sys.stderr)
            except Exception as e:
                print(f"  warning: could not bundle deps.edn: {e}", file=sys.stderr)

        # Step 6c: Write results summary (minimal — detailed results in overview)
        results_summary = {
            "results/schema-version": "results-summary.v1",
            "results/status": "pass" if exit_code == 0 else "fail",
            "results/suite-key": suite_key or "registry-default",
        }
        write_sealed_json(run_dir / "results-summary.json", results_summary)

        # Step 6d: Copy evidence nodes from per-run workspace (private, not shared dir)
        dag_dir = run_dir / "evidence-dag"
        workspace_evidence_dir = workspace_dir / "evidence-nodes" if workspace_dir else \
            (repo_root / PRF_ARTIFACT_DIR / "evidence-nodes")
        node_files = []
        if workspace_evidence_dir.exists():
            for f in workspace_evidence_dir.iterdir():
                if f.is_file() and f.suffix in (".json", ".edn"):
                    shutil.copy2(str(f), str(dag_dir / f.name))
                    node_files.append(f)
            print(f"  copied {len(node_files)} evidence node(s) to evidence-dag/", file=sys.stderr)

        # Step 6e: Populate claims/ and attestations/ from per-run workspace
        for src_subdir, dst_dir_name in [("claims", "claims"), ("attestations", "attestations")]:
            src_dir = workspace_dir / src_subdir
            dst_dir = run_dir / dst_dir_name
            if src_dir.exists():
                count = 0
                for f in src_dir.iterdir():
                    if f.is_file() and f.suffix == ".json":
                        shutil.copy2(str(f), str(dst_dir / f.name))
                        count += 1
                print(f"  copied {count} file(s) to {dst_dir_name}/", file=sys.stderr)

        # Step 6f: Evidence DAG inventory manifest (Phase B — semantic EDN parsing)
        dag_inv_hash = _write_dag_inventory(run_dir, dag_dir, node_files, repo_root, workspace_dir=workspace_dir)

        # Step 6g: Read execution node hash from Clojure bundle root for bridge
        execution_node_hash = None
        clj_bundle_path = run_dir / "clojure-bundle-root.json"
        if clj_bundle_path.exists():
            try:
                clj_data = json.loads(clj_bundle_path.read_text())
                execution_node_hash = clj_data.get("execution/node-hash")
            except Exception:
                pass

        # Step 6h: Populate anchors/ from TSA artifacts or produce local proof
        tsa_anchor_type = "mock"
        anchor_tsa_url = None
        anchor_tsa_token = None
        if workspace_dir:
            for ext, name in [(".tsr", "registry.tsr"), (".tsq", "registry.tsq"),
                              (".json", "registry.tsa.json")]:
                src = workspace_dir / name
                if src.exists():
                    shutil.copy2(str(src), str(run_dir / "anchors" / name))
            if (workspace_dir / "registry.tsa.json").exists():
                tsa_anchor_type = "rfc3161"
                try:
                    tsa_meta = json.loads((workspace_dir / "registry.tsa.json").read_text())
                    anchor_tsa_url = tsa_meta.get("timestamp/provider-url")
                except Exception:
                    pass
                anchor_tsa_token = "registry.tsr"
            elif tsa_url:
                tsa_anchor_type = "tsa-requested-no-response"
            else:
                tsa_anchor_type = "local-proof"
        print(f"  anchor type: {tsa_anchor_type}", file=sys.stderr)

        # Step 7: Write run overview (self-referential SHA-256)
        run_overview = {
            "overview/schema-version": "run-overview.v1",
            "overview/hash": None,   # filled in step below
            "run-id": run_id,
            "run-timestamp": datetime.now(timezone.utc).isoformat(),
            "exit-code": exit_code,
            "elapsed-ms": elapsed_ms,
            "status": "pass" if exit_code == 0 else "fail",
            "source": source_info,
        }
        # Build canonical dict (without hash), serialize, hash, set hash, seal
        overview_canonical = {k: v for k, v in run_overview.items()
                              if k not in ("overview/hash",)}
        can_bytes = json.dumps(overview_canonical, indent=2, default=str,
                               sort_keys=True).encode("utf-8")
        overview_hash = compute_sha256(can_bytes)
        run_overview["overview/hash"] = overview_hash
        write_sealed_json(run_dir / "run-overview.json", run_overview)
        print(f"  overview hash: {overview_hash[:16]}...", file=sys.stderr)

        # Step 8: Write bundle root (self-referential SHA-256 + optional signing)
        bundle_root = {
            "bundle/schema-version": "bundle-root.v1",
            "bundle/id": None,        # filled after hash
            "bundle/hash": None,      # self-referential hash
            "bundle/signature": None, # filled after signing
            "bundle/signing-key-id": None,
            "bundle/timestamp": datetime.now(timezone.utc).isoformat(),
            "run/request-path": str(run_request_path),
            "run/exit-code": exit_code,
            "run/status": "pass" if exit_code == 0 else "fail",
            "overview/hash": overview_hash,
            "evidence-dag/hash": dag_inv_hash,
            "execution/node-hash": execution_node_hash,
            "preflight": {
                "status": preflight_status,
                "summary": report.summary,
            },
        }
        bundle_root.update(isolation_report)
        bundle_canonical = {k: v for k, v in bundle_root.items()
                            if k not in SIGN_EXCLUDE_KEYS}
        can_bytes = json.dumps(bundle_canonical, indent=2, default=str,
                               sort_keys=True).encode("utf-8")
        bundle_hash = compute_sha256(can_bytes)
        bundle_root["bundle/id"] = bundle_hash
        bundle_root["bundle/hash"] = bundle_hash

        # Optional: sign the bundle hash with Ed25519
        if signing_key_path:
            sig, kid = sign_bundle_hash(bundle_hash, signing_key_path, signing_key_id)
            bundle_root["bundle/signature"] = sig
            bundle_root["bundle/signing-key-id"] = kid

        write_sealed_json(run_dir / "run-bundle-root.json", bundle_root)
        print(f"  bundle root hash: {bundle_hash[:16]}...", file=sys.stderr)

        # Step 9: Write anchor cursor (TSA-aware)
        anchor_cursor = {
            "anchor/schema-version": "anchor-cursor.v1",
            "anchor/type": tsa_anchor_type,
            "anchor/target": f"file://{run_dir}",
            "anchor/timestamp": datetime.now(timezone.utc).isoformat(),
        }
        if anchor_tsa_url:
            anchor_cursor["anchor/tsa-url"] = anchor_tsa_url
        if anchor_tsa_token:
            anchor_cursor["anchor/tsa-token-path"] = anchor_tsa_token
        if tsa_anchor_type == "rfc3161":
            anchor_cursor["anchor/note"] = "RFC 3161 timestamp from configured TSA"
        elif tsa_anchor_type == "tsa-requested-no-response":
            anchor_cursor["anchor/note"] = "TSA was configured but returned no response"
        else:
            anchor_cursor["anchor/note"] = "Local timestamp — no external TSA configured"
        write_sealed_json(run_dir / "anchors/anchor-cursor.json", anchor_cursor)

        # Step 10: Post-run full verification. A bundle is not successful unless
        # it verifies, even when the scenario pipeline itself exited successfully.
        print(f"\n--- Post-run Verification ---", file=sys.stderr)
        verification_failed = False
        try:
            v_report = verify_run(str(run_dir))
            v_summary = v_report.summary
            if v_report.status == "fail":
                verification_failed = True
                print(f"  Verification FAILED: {v_summary['fail']} check(s) failed",
                      file=sys.stderr)
                for check in v_report.checks:
                    if check.get("check/status") == "fail":
                        print(f"    - {check.get('check/key')}: {check.get('check/message')}",
                              file=sys.stderr)
            else:
                print(f"  Verification {v_report.status} "
                      f"({v_summary['pass']} pass, {v_summary['fail']} fail, "
                      f"{v_summary['warning']} warn)",
                      file=sys.stderr)
        except Exception as e:
            verification_failed = True
            print(f"  Verification error: {e}", file=sys.stderr)

        if verification_failed:
            exit_code = 1

        # Step 11: Make output tree immutable (skip on failure — enables cleanup)
        if no_harden:
            print(f"  output hardening skipped (--no-harden)", file=sys.stderr)
        elif exit_code != 0:
            print(f"  output hardening skipped (exit code {exit_code} — partial bundle may need cleanup)",
                  file=sys.stderr)
        else:
            print(f"\n--- Output Hardening ---", file=sys.stderr)
            # Hardens entire run tree including per-run workspace (chmod -R a-w)
            make_output_immutable(run_dir)

        print(f"\n=== Run Complete ===", file=sys.stderr)
        print(f"  Output: {run_dir}", file=sys.stderr)
        print(f"  Status: {'PASS' if exit_code == 0 else 'FAIL'}", file=sys.stderr)
    finally:
        # Always release the lock, even on failure
        if lock_held:
            _release_lock(lock_file)

    # Step 13: Enforce retention policy (outside lock — doesn't need it)
    _enforce_retention(output_base, PRF_MAX_RUNS)
    return exit_code


# ── CLI entry point ────────────────────────────────────────────────────────

def main():
    import argparse
    parser = argparse.ArgumentParser(
        description="Forensic run orchestrator")
    parser.add_argument("--run-request",
                        default="examples/forensic-reference-run/run-request.example.edn",
                        help="Path to run request file (default: examples/forensic-reference-run/run-request.example.edn)")
    parser.add_argument("--label", help="Human-readable label for the run")
    parser.add_argument("--registry-snapshot",
                        default="examples/forensic-reference-run/registry-snapshot.example.edn",
                        help="Path to registry snapshot file (default: examples/forensic-reference-run/registry-snapshot.example.edn)")
    parser.add_argument("--evidence-policy",
                        default="examples/config/forensic/evidence-policy.edn",
                        help="Path to evidence policy file (default: examples/config/forensic/evidence-policy.edn)")
    parser.add_argument("--output-base",
                        default=str(PRF_RUNS_ROOT),
                        help="Base directory for run output (default: ~/prf-runs)")
    parser.add_argument("--repo-root", default=str(Path.cwd()),
                        help="Project repo root (default: cwd)")
    parser.add_argument("--isolation",
                        default="shared-filesystem",
                        choices=["shared-filesystem", "private-tmpfs"],
                        help="Filesystem isolation level (default: shared-filesystem)")
    parser.add_argument("--sign", dest="signing_key_path",
                        default=None,
                        help="Path to Ed25519 private key for bundle signing")
    parser.add_argument("--signing-key-id",
                        default=None,
                        help="Key identifier (defaults to key filename)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Run preflight only, no execution")
    parser.add_argument("--no-harden", action="store_true",
                        help="Skip post-run output hardening (chmod a-w)")
    parser.add_argument("--tsa-url", default=None,
                        help="RFC 3161 Time-Stamp Authority URL (e.g. https://freetsa.org/tsr)")
    args = parser.parse_args()

    exit_code = run_forensic(
        run_request_path=args.run_request,
        label=args.label,
        registry_snapshot_path=args.registry_snapshot,
        evidence_policy_path=args.evidence_policy,
        output_base=args.output_base,
        repo_root=args.repo_root,
        dry_run=args.dry_run,
        isolation=args.isolation,
        signing_key_path=args.signing_key_path,
        signing_key_id=args.signing_key_id,
        no_harden=args.no_harden,
        tsa_url=args.tsa_url,
    )
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
