#!/usr/bin/env python3
"""
Write lightweight run-manifest artifacts for a bb scenario:run,
run:scenario:search, or evidence:build invocation.

Reads canonical evidence chain configuration from config/evidence.json.

Produces into results/test-artifacts/:
  test-run.json                 schema: test-run.v1
  test-summary.json             schema: test-summary.v2
  claimable-classification.json schema: claimable-classification.v2
  test-artifacts.json           schema: test-artifacts.v1.2

Only artifacts produced by this invocation are registered.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile

from evidence_config import EvidenceConfig
from schema_validator import SchemaValidator

SCENARIOS_DIR = pathlib.Path("scenarios")


# ── file helpers ──────────────────────────────────────────────────────────────

def write_atomic_json(path: pathlib.Path, data: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_path = tempfile.mkstemp(dir=path.parent, text=True)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            json.dump(data, f, indent=2)
        os.replace(temp_path, path)
    except Exception as e:
        if os.path.exists(temp_path):
            os.remove(temp_path)
        raise e

def sha256_file(path: pathlib.Path) -> str | None:
    if not path.exists():
        return None
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()

def artifact_meta(path: pathlib.Path) -> dict | None:
    if not path.exists():
        return None
    st = path.stat()
    return {
        "sha256": sha256_file(path),
        "bytes": st.st_size,
        "mtime_utc": datetime.datetime.fromtimestamp(
            st.st_mtime, datetime.timezone.utc
        ).isoformat(),
    }

def structured_artifact_entry(aid: str, kind: str, schema_version: str,
                              path: pathlib.Path, run_root: pathlib.Path) -> dict | None:
    """Create a structured registry entry with a run-root-relative path."""
    m = artifact_meta(path)
    if not m:
        return None
    relative = path.relative_to(run_root).as_posix()
    if ".." in pathlib.PurePosixPath(relative).parts:
        raise ValueError("structured artifact path must remain beneath run root")
    return {"id": aid, "kind": kind, "path": relative, "importance": "CORE",
            "schema_version": schema_version, "contract_version": "evidence-contract.v1",
            "producer": "scenario-run-manifest.v1", "verifies_against": [],
            "dependencies": [], **m}


def mk_artifact_entry(
    cfg: EvidenceConfig,
    aid: str,
    path_s: str | None,
) -> dict | None:
    a = cfg.artifact(aid)
    if not a or not path_s:
        return None
    p = pathlib.Path(path_s)
    m = artifact_meta(p)
    if not m:
        return None
    return {
        "id": aid,
        "kind": a["kind"],
        "path": str(p),
        "importance": a["importance"],
        "schema_version": cfg.schema(a["schema_key"]),
        "contract_version": cfg.contract_version,
        "producer": cfg.producer(a["producer_key"]),
        "verifies_against": list(a.get("verifies_against", [])),
        "dependencies": [],
        "input_dependencies": list(a.get("input_dependencies", [])),
        **m,
    }

# ── environment probes ────────────────────────────────────────────────────────

def get_vcs_info() -> dict:
    try:
        if not sys.path or not any("scripts" in p for p in sys.path):
            here = pathlib.Path(__file__).resolve().parent
            scripts_root = str(here.parent)  # <project>/scripts/
            if scripts_root not in sys.path:
                sys.path.insert(0, scripts_root)
        from vcs_info import commit_sha, commit_message  # type: ignore[import-untyped]
        return {"git_commit": commit_sha(), "git_message": commit_message()}
    except Exception:
        return {"git_commit": None, "git_message": None}

def make_scenario_slug(scenario_path: str | None, suite: str) -> str:
    if not scenario_path or scenario_path == "unknown":
        return suite
    return pathlib.Path(scenario_path).stem

def write_artifacts_to(
    artifact_dir: pathlib.Path,
    cfg: EvidenceConfig,
    args: argparse.Namespace,
    run_id: str,
    created_at: str,
    git_info: dict,
    summary: dict,
    run_manifest: dict,
    claimable_classification: dict,
    new_format: bool = False,
) -> None:
    artifact_dir.mkdir(parents=True, exist_ok=True)

    if new_format:
        artifact_file     = artifact_dir / "summary.json"
        run_manifest_file = artifact_dir / "run.json"
        registry_file     = artifact_dir / "artifacts.json"
    else:
        artifact_file     = artifact_dir / cfg.artifact("test-summary")["file"]
        run_manifest_file = artifact_dir / cfg.artifact("test-run")["file"]
        registry_file     = artifact_dir / "test-artifacts.json"
    claimable_file    = artifact_dir / cfg.artifact("claimable-classification")["file"]
    envelope_file     = artifact_dir / cfg.artifact("envelope")["file"]
    signature_file    = artifact_dir / cfg.artifact("signature")["file"]

    # Overwrite protection: If the bundle is already signed, abort.
    # This enforces that once an evidence chain is final, it cannot be changed.
    if signature_file.exists() or envelope_file.exists():
        print(f"Error: Target directory {artifact_dir} contains a signed envelope.")
        print("Evidence chain is FINAL and cannot be modified.")
        sys.exit(1)

    # Enrich run manifest
    if new_format:
        run_manifest.setdefault("implementation", {})
        run_manifest["implementation"]["source_commit"] = git_info.get("git_commit")
        run_manifest["implementation"]["source_message"] = git_info.get("git_message")
    else:
        run_manifest["framework"]["git_commit"] = git_info.get("git_commit")
        run_manifest["framework"]["git_message"] = git_info.get("git_message")

    write_atomic_json(run_manifest_file, run_manifest)
    write_atomic_json(artifact_file, summary)
    write_atomic_json(claimable_file, claimable_classification)

    # Structured runs are completed by extract_scenario_artifacts.py after all
    # writers finish. It registers only files under the supplied run root with
    # paths relative to that root. Legacy mode retains its config-root registry.
    all_potential_entries = []
    if new_format:
        run_root = pathlib.Path(args.run_root)
        structured_files = [
            ("manifest.run", "run-manifest", "run-manifest.v1", run_manifest_file),
            ("manifest.summary", "summary", "summary.v1", artifact_file),
            ("manifest.claimable-classification", "summary", "claimable-classification.v2", claimable_file),
        ]
        # The runner produces these before this script is called. They are
        # re-registered by the extractor after all derived artifacts exist.
        for aid, kind, schema_version, path in structured_files:
            entry = structured_artifact_entry(aid, kind, schema_version, path, run_root)
            if entry:
                all_potential_entries.append(entry)
    else:
        for aid in cfg.all_artifact_ids:
            if aid == "results-artifact":
                continue
            try:
                p = cfg.artifact_path(aid)
            except KeyError:
                continue
            e = mk_artifact_entry(cfg, aid, p)
            if e:
                all_potential_entries.append(e)

        if args.output_file:
            e = mk_artifact_entry(cfg, "results-artifact", args.output_file)
            if e:
                # Bind the results artifact to the run explicitly: the accepted
                # registry must prove the results artifact belongs to this run.
                e.setdefault("extensions", {})["run_id"] = run_id
                all_potential_entries.append(e)

        for sid in ("signature", "envelope"):
            f = cfg.artifact(sid)["file"]
            if (artifact_dir / f).exists():
                e = mk_artifact_entry(cfg, sid, str(artifact_dir / f))
                if e:
                    all_potential_entries.append(e)

        event_cfg = cfg._data.get("event_evidence", {})
        event_evidence_dir = artifact_dir / (event_cfg.get("dir") or "event-evidence")
        if event_evidence_dir.exists():
            for p in event_evidence_dir.glob("*.json"):
                m = artifact_meta(p)
                if not m:
                    continue
                all_potential_entries.append({
                    "id": f"event-evidence-{p.stem}", "kind": event_cfg["kind"], "path": str(p),
                    "importance": event_cfg["importance"], "schema_version": cfg.schema(event_cfg["schema_key"]),
                    "contract_version": cfg.contract_version, "producer": cfg.producer(event_cfg["producer_key"]),
                    "verifies_against": list(event_cfg.get("verifies_against", [])), "dependencies": [],
                    "input_dependencies": list(event_cfg.get("input_dependencies", [])),
                    "evidence_reason": p.name.split("-")[0], **m})

    # Filtering phase
    imp_map = cfg.importance_map
    min_importance = imp_map.get(args.registry_level, 1)

    root_entries = [
        e for e in all_potential_entries if e
        and imp_map.get(e.get("importance", "CORE"), 1) <= min_importance
    ]

    registered_entries = {e["id"]: e for e in root_entries}
    to_resolve = list(root_entries)

    while to_resolve:
        curr = to_resolve.pop()
        for dep_id in curr.get("input_dependencies", []):
            if dep_id not in registered_entries:
                dep_art = next((e for e in all_potential_entries if e and e["id"] == dep_id), None)
                if dep_art:
                    registered_entries[dep_id] = dep_art
                    to_resolve.append(dep_art)
                else:
                    print(f"Warning: Missing required dependency {dep_id} for {curr['id']}")

    final_entries = list(registered_entries.values())

    for entry in final_entries:
        entry["dependencies"] = []
        for dep_id in entry.get("input_dependencies", []):
            dep_art = registered_entries.get(dep_id)
            if dep_art:
                entry["dependencies"].append({"id": dep_art["id"], "sha256": dep_art["sha256"]})
        if "input_dependencies" in entry:
            del entry["input_dependencies"]

    registry = {
        "schema_version":   cfg.schema("test-artifacts"),
        "contract_version": cfg.contract_version,
        "run_id":           run_id,
        "generated_at":     created_at,
        "generator":        {"name": "artifact-registry-emitter", "version": "v1.2"},
        "root_dir":         "." if new_format else str(artifact_dir),
        "artifacts":        final_entries,
    }
    struct_errors = SchemaValidator().validate(registry)
    if struct_errors:
        print(f"Error: Generated registry is structurally invalid ({len(struct_errors)} error(s))")
        for e in struct_errors:
            print(f"  {e.path}: {e.message}")
        sys.exit(1)
    write_atomic_json(registry_file, registry)


def main() -> int:
    cfg = EvidenceConfig()
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenario", default="unknown")
    ap.add_argument("--suite", default="unknown")
    ap.add_argument("--status", default="unknown")
    ap.add_argument("--duration-ms", type=int, default=0)
    ap.add_argument("--output-file")
    ap.add_argument("--output-dir")
    ap.add_argument("--manifest-dir",
                    help="Override output dir for manifest files only. Falls back to --output-dir.")
    ap.add_argument("--artifact-dir", default=cfg.artifact_dir)
    ap.add_argument(
        "--registry-level", default="DIAGNOSTIC",
        choices=list(cfg.importance_map.keys()),
    )
    ap.add_argument("--risk-digest-file")
    ap.add_argument("--long-horizon-meta-file")
    ap.add_argument("--target-log-csv")
    ap.add_argument("--run-id",
                    help="Human-readable unique operational ID (e.g. run-20260715T103414Z-S-DR-084)")
    ap.add_argument("--scenario-file",
                    help="Path to the scenario EDN/JSON file for content hashing")
    ap.add_argument("--run-root",
                    help="Run root directory (for artifact base-path resolution)")
    ap.add_argument("--total", type=int, default=0,
                    help="Total number of assertions/checks")
    ap.add_argument("--passed", type=int, default=0,
                    help="Number of passed assertions/checks")
    ap.add_argument("--failed", type=int, default=0,
                    help="Number of failed assertions/checks")
    args = ap.parse_args()

    now        = datetime.datetime.now(datetime.timezone.utc)
    run_id     = args.run_id or now.strftime("%Y%m%d-%H%M%S")
    created_at = now.isoformat()
    git_info   = get_vcs_info()

    # Compute scenario content hash
    scenario_content_ref = None
    if args.scenario_file:
        sp = pathlib.Path(args.scenario_file)
        if sp.exists():
            h = sha256_file(sp)
            if h:
                scenario_content_ref = f"artifact:sha256:{h}"

    new_format = args.run_root is not None

    if new_format:
        run_status_complete = "complete" if args.status == "pass" else "failed"
        exit_code = 0 if args.status == "pass" else 1
        run_manifest = {
            "manifest": {
                "schema_version": "run-manifest.v1",
                "generated_at": created_at,
            },
            "run": {
                "id": run_id,
                "type": "scenario",
                "status": run_status_complete,
                "exit_code": exit_code,
                "duration_ms": args.duration_ms,
            },
            "scenario": {
                "id": args.scenario,
                "path": args.scenario_file or args.scenario,
                "content_ref": scenario_content_ref,
            },
            "outcome": {
                "status": args.status,
                "total": args.total,
                "passed": args.passed,
                "failed": args.failed,
                "exit_code": exit_code,
                "duration_ms": args.duration_ms,
            },
            "artifacts": {
                "base_path": ".",
            },
        }
        summary = {
            "manifest": {
                "schema_version": "summary.v1",
                "generated_at": created_at,
            },
            "run": {
                "id": run_id,
                "overall_status": args.status,
                "outcome": {
                    "status": args.status,
                    "total": args.total,
                    "passed": args.passed,
                    "failed": args.failed,
                    "exit_code": exit_code,
                    "duration_ms": args.duration_ms,
                },
            },
        }
    else:
        summary = {
            "schema_version": cfg.schema("test-summary"),
            "run_id": run_id,
            "overall_status": args.status,
            "risk_digest": {},
        }
        run_manifest = {
            "schema_version": cfg.schema("test-run"),
            "run_id": run_id,
            "framework": dict(cfg.framework),
        }

    claimable = {
        "schema_version": cfg.schema("claimable-classification"),
        "run_id": run_id,
    }

    per_run_dir = (pathlib.Path(args.output_dir)
                   if args.output_dir else
                   pathlib.Path(cfg._data.get("runs_root", "results/runs")) / f"{make_scenario_slug(args.scenario, args.suite)}-{run_id}")
    manifest_dir = (pathlib.Path(args.manifest_dir)
                    if args.manifest_dir else per_run_dir)
    write_artifacts_to(manifest_dir, cfg, args, run_id, created_at, git_info, summary, run_manifest, claimable, new_format=new_format)

    # Phase 2: Merge Clojure-side enrichment into run.json
    if new_format:
        enrichment_path = manifest_dir / "run-enrichment.json"
        run_json_path = manifest_dir / "run.json"
        if enrichment_path.exists() and run_json_path.exists():
            try:
                with enrichment_path.open("r") as f:
                    enrichment = json.load(f)
                with run_json_path.open("r") as f:
                    run_data = json.load(f)
                # Merge enrichment sections into run.json (top-level key merge)
                for k, v in enrichment.items():
                    if k in run_data and isinstance(run_data[k], dict) and isinstance(v, dict):
                        run_data[k].update(v)
                    else:
                        run_data[k] = v
                with run_json_path.open("w") as f:
                    json.dump(run_data, f, indent=2)
                print(f"[run-enrichment] Merged {enrichment_path} into {run_json_path}")
            except Exception as e:
                print(f"[run-enrichment] Failed: {e}")

    print(f"[artifact-registry] Emitted v1.2 to {manifest_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
