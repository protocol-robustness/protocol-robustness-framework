#!/usr/bin/env python3
"""Consolidate test artifacts from parallel producers into one canonical
run-root with an idempotent, content-addressed merge and a unified
test-artifacts.v1.2 registry.

Producers (test targets, fixture suites, per-namespace runners, forensic
workspaces) write into their own isolated, ownership-marked roots.  This
collector:

  * discovers producer roots (a root carrying ``_owner.edn`` / ``_manifest.json``,
    or a container whose immediate subdirs each carry a marker),
  * reads each producer's ``_manifest.json`` (the JSON export written by
    ``scripts/artifact_scope.clj finalize-scope!``) when present, otherwise
    falls back to a config-driven directory scan,
  * always also registers config-registered files written directly into the
    canonical run-root (e.g. ``test-summary.json`` / ``test-run.json`` emitted by
    the summary step, or target outputs in sequential mode),
  * merges files into the canonical run-root with content-addressed
    idempotence — absent -> hardlink (fallback copy), identical bytes -> reuse,
    different bytes under the same path -> hard conflict (no partial write),
  * builds a merged ``test-artifacts.v1.2`` registry from the union of producer
    entries (config-driven, deterministic ordering, dependency resolution),
  * validates the registry against ``schemas/test-artifacts-v1.2.json`` and
    writes it atomically.

Only artifact ids declared in ``config/evidence.json`` are registered;
undeclared files are reported and skipped.  Running the collector twice on the
same inputs is structurally idempotent (same artifact ids, hashes, ordering);
the ``generated_at`` timestamp is informational and will differ.

Usage:
  python3 scripts/evidence/consolidate_test_artifacts.py \
    --run-root results/test-artifacts \
    --producer-roots targets/unit targets/suites targets/invariants

  # container mode: every immediate subdir with an ownership marker is a producer
  python3 scripts/evidence/consolidate_test_artifacts.py \
    --run-root results/test-artifacts \
    --producer-roots targets

  # loose mode: treat an unmarked directory as a single producer root
  python3 scripts/evidence/consolidate_test_artifacts.py \
    --run-root results/test-artifacts-unified \
    --producer-roots results/test-artifacts \
    --allow-loose

  # run-root only: register the run-root's own config files (no producers)
  python3 scripts/evidence/consolidate_test_artifacts.py \
    --run-root results/test-artifacts \
    --run-id run-2026

Environment:
  PRF_RUN_ID   run id used when --run-id is not supplied
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import pathlib
import re
import shutil
import sys
from collections import Counter

from evidence_config import EvidenceConfig
from schema_validator import SchemaValidator
from write_scenario_run_manifest import artifact_meta, sha256_file, write_atomic_json

# Marker/manifest files and temp files that are never treated as artifacts.
_META_NAMES = {"_owner.edn", "_manifest.json"}

# Project root resolved from this file (<project>/scripts/evidence/…).
_REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


class ConsolidationError(Exception):
    """Hard failure during consolidation (conflict, hash mismatch, validation)."""


# ── producer discovery ────────────────────────────────────────────────────────


def _has_marker(root: pathlib.Path) -> bool:
    return (root / "_owner.edn").exists() or (root / "_manifest.json").exists()


def discover_producer_roots(specs: list[str], allow_loose: bool = False) -> list[pathlib.Path]:
    """Resolve producer-root specs.

    A spec is either a single producer root (has an ownership marker), a
    container whose immediate subdirs each carry a marker, or — when
    ``allow_loose`` is set — a bare directory treated as a single unmarked
    producer root (for directories that predate ownership markers, e.g. the
    shared sequential-mode artifact dir).  Missing paths are hard errors;
    unmarked subdirs inside a container are skipped with a warning.
    """
    roots: list[pathlib.Path] = []
    for spec in specs:
        p = pathlib.Path(spec)
        if not p.exists():
            raise ConsolidationError(f"producer root does not exist: {p}")
        if _has_marker(p):
            roots.append(p)
            continue
        # container mode
        subdirs = sorted(d for d in p.iterdir() if d.is_dir())
        found = [d for d in subdirs if _has_marker(d)]
        if found:
            unmarked = [d for d in subdirs if d not in found]
            for d in unmarked:
                print(f"  WARN: skipping unmarked subdir (no _owner.edn/_manifest.json): {d}")
            roots.extend(found)
            continue
        if allow_loose:
            print(f"  WARN: {p} has no ownership marker — treating as loose producer root")
            roots.append(p)
            continue
        raise ConsolidationError(
            f"no ownership-marked producer roots found under container: {p}"
        )
    return roots


def _edn_owner_value(text: str, key: str) -> str | None:
    """Extract a simple scalar value for *key* from an EDN owner marker.

    Handles the subset written by scripts/artifact_scope.clj
    write-owner-marker!: quoted strings (:run-id "x") and bare symbols or
    keywords (:namespace resolver-sim.foo / :run-root).
    """
    m = re.search(r":%s\s+\"([^\"]*)\"" % re.escape(key), text)
    if m:
        return m.group(1)
    m = re.search(r":%s\s+([^\s,}:]+)" % re.escape(key), text)
    return m.group(1) if m else None


def read_owner_marker(root: pathlib.Path) -> dict | None:
    marker = root / "_owner.edn"
    if not marker.exists():
        return None
    text = marker.read_text(encoding="utf-8")
    # Ownership markers are written as EDN by artifact-scope/write-owner-marker!;
    # accept JSON too for forward/backward tolerance.
    try:
        return json.loads(text)
    except (json.JSONDecodeError, ValueError):
        return {
            "run-id": _edn_owner_value(text, "run-id"),
            "namespace": _edn_owner_value(text, "namespace"),
        }


def read_manifest(root: pathlib.Path) -> dict | None:
    manifest = root / "_manifest.json"
    if not manifest.exists():
        return None
    try:
        return json.loads(manifest.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as e:
        raise ConsolidationError(f"invalid producer manifest {manifest}: {e}")


def _is_temp(name: str) -> bool:
    return name.startswith(".tmp-") or name.endswith(".art") or name.endswith(".tmp")


# ── config-driven file matching ───────────────────────────────────────────────


def _config_file_to_id(cfg: EvidenceConfig) -> dict[str, str]:
    return {
        a["file"]: a["id"]
        for a in cfg._data.get("artifacts", [])
        if a.get("file")
    }


def _event_evidence_dir(cfg: EvidenceConfig) -> str:
    return cfg._data.get("event_evidence", {}).get("dir") or "event-evidence"


def _event_evidence_meta(cfg: EvidenceConfig) -> dict:
    return dict(cfg._data.get("event_evidence", {}))


def _scan_dir_entries(producer_root: pathlib.Path, cfg: EvidenceConfig) -> list[dict]:
    """Config-driven scan of a producer root without a _manifest.json.

    Matches artifact ids by configured file basename, synthesizes
    event-evidence entries for the configured event-evidence directory, and
    reports (but skips) everything else.
    """
    file_to_id = _config_file_to_id(cfg)
    ev_dir = _event_evidence_dir(cfg)
    entries: list[dict] = []
    undeclared: list[str] = []
    for f in sorted(producer_root.rglob("*")):
        if not f.is_file():
            continue
        rel = f.relative_to(producer_root)
        if rel.name in _META_NAMES or _is_temp(rel.name):
            continue
        parts = rel.parts
        if parts and parts[0] == ev_dir:
            entries.append(_make_event_evidence_entry(f, rel, cfg))
            continue
        aid = file_to_id.get(rel.name)
        if aid:
            entries.append(
                {
                    "logical-id": aid,
                    "rel-path": rel.as_posix(),
                    "source": f,
                    "declared-hash": None,
                    "declared-size": None,
                    "kind": None,
                }
            )
        else:
            undeclared.append(rel.as_posix())
    if undeclared:
        print(
            f"  WARN: {producer_root} — {len(undeclared)} undeclared file(s) "
            f"skipped (not registered): {', '.join(undeclared[:8])}"
            + (" ..." if len(undeclared) > 8 else "")
        )
    return entries


def _make_event_evidence_entry(
    f: pathlib.Path, rel: pathlib.PurePath, cfg: EvidenceConfig
) -> dict:
    ev = _event_evidence_meta(cfg)
    return {
        "logical-id": f"event-evidence-{f.stem}",
        "rel-path": rel.as_posix(),
        "source": f,
        "declared-hash": None,
        "declared-size": None,
        "kind": ev.get("kind"),
    }


def _manifest_entries(
    producer_root: pathlib.Path, manifest: dict, cfg: EvidenceConfig
) -> list[dict]:
    """Entries from a _manifest.json produced by artifact-scope/finalize-scope!."""
    entries: list[dict] = []
    for a in manifest.get("artifacts", []):
        rel = a.get("relative-path")
        logical_id = a.get("logical-id")
        if not rel or not logical_id:
            continue
        source = producer_root / rel
        if not source.exists():
            raise ConsolidationError(
                f"{producer_root}: manifest declares missing artifact "
                f"{logical_id} at {rel}"
            )
        entries.append(
            {
                "logical-id": str(logical_id),
                "rel-path": pathlib.PurePosixPath(rel).as_posix(),
                "source": source,
                "declared-hash": a.get("content-hash") or a.get("byte-hash"),
                "declared-size": a.get("size"),
                "kind": a.get("kind"),
            }
        )
    return entries


def _scan_run_root_top_level(
    run_root: pathlib.Path, cfg: EvidenceConfig
) -> list[dict]:
    """Config-driven scan of the canonical run-root's immediate files.

    Files written directly into the run-root by the summary step (e.g.
    test-summary.json, test-run.json) or by targets in sequential mode are
    always part of the registry.  Only immediate files and the immediate
    event-evidence directory are considered — never nested producer roots.
    """
    entries: list[dict] = []
    if not run_root.exists():
        return entries
    file_to_id = _config_file_to_id(cfg)
    for f in sorted(run_root.iterdir()):
        if not f.is_file() or f.name in _META_NAMES or _is_temp(f.name):
            continue
        aid = file_to_id.get(f.name)
        if aid:
            entries.append(
                {
                    "logical-id": aid,
                    "rel-path": f.name,
                    "source": f,
                    "declared-hash": None,
                    "declared-size": None,
                    "kind": None,
                }
            )
    ev_dir = _event_evidence_dir(cfg)
    ev_dir_path = run_root / ev_dir
    if ev_dir_path.is_dir():
        for f in sorted(ev_dir_path.iterdir()):
            if f.is_file():
                rel = pathlib.PurePosixPath(ev_dir) / f.name
                entries.append(_make_event_evidence_entry(f, rel, cfg))
    return entries


def collect_producer_entries(
    producer_root: pathlib.Path, cfg: EvidenceConfig
) -> list[dict]:
    marker = read_owner_marker(producer_root)
    if marker is None:
        print(f"  WARN: no _owner.edn in {producer_root} — loose producer root")
    else:
        run_id = marker.get("run-id")
        ns = marker.get("namespace")
        print(f"  producer {producer_root} (run-id={run_id} namespace={ns})")
    manifest = read_manifest(producer_root)
    if manifest is not None:
        scope_status = manifest.get("scope-status")
        if scope_status != "complete":
            print(
                f"  WARN: producer manifest scope-status={scope_status!r} "
                f"for {producer_root}"
            )
        entries = _manifest_entries(producer_root, manifest, cfg)
        # The manifest records only artifacts published through
        # artifact-scope/write!; evidence written directly by the chain (e.g.
        # event-evidence/, evidence-nodes/) is not covered.  Fall back to a
        # config-driven scan for on-disk files the manifest did not claim so
        # those evidence artifacts are not dropped.
        claimed = {(e["rel-path"], e["logical-id"]) for e in entries}
        for e in _scan_dir_entries(producer_root, cfg):
            if (e["rel-path"], e["logical-id"]) not in claimed:
                entries.append(e)
        return entries
    return _scan_dir_entries(producer_root, cfg)


# ── content-addressed merge ───────────────────────────────────────────────────


def _resolve_merge_actions(
    entries: list[dict], run_root: pathlib.Path
) -> tuple[list[dict], list[dict]]:
    """Two-pass planning: return (actions, conflicts).

    An action is {logical-id, rel-path, source, dest, action} where action is
    'link' (dest absent) or 'reuse' (identical bytes present).  Any destination
    whose bytes differ is a hard conflict; nothing is written on conflict.
    """
    by_rel: dict[str, dict] = {}
    conflicts: list[dict] = []
    for e in entries:
        rel = e["rel-path"]
        source = e["source"]
        src_hash = sha256_file(source)
        if src_hash is None:
            raise ConsolidationError(f"source artifact unreadable: {source}")
        declared = e["declared-hash"]
        if declared and declared != src_hash:
            raise ConsolidationError(
                f"content-hash mismatch for {e['logical-id']} at {source}: "
                f"declared {declared}, actual {src_hash}"
            )
        dest = run_root / rel
        prev = by_rel.get(rel)
        if prev is None:
            by_rel[rel] = {
                "logical-id": e["logical-id"],
                "rel-path": rel,
                "source": source,
                "dest": dest,
                "hash": src_hash,
            }
            continue
        # Same relative path from two producers.
        if prev["hash"] != src_hash:
            conflicts.append(
                {
                    "rel-path": rel,
                    "logical-id": prev["logical-id"],
                    "conflicting-id": e["logical-id"],
                    "left-hash": prev["hash"],
                    "right-hash": src_hash,
                    "left-source": prev["source"],
                    "right-source": source,
                }
            )
            continue
        # Identical bytes under the same path: keep the first (deterministic).
        if str(e["source"]) < str(prev["source"]):
            prev["source"] = e["source"]

    actions: list[dict] = []
    for rel in sorted(by_rel):
        info = by_rel[rel]
        dest = info["dest"]
        if dest.exists():
            dest_hash = sha256_file(dest)
            if dest_hash == info["hash"]:
                actions.append({**info, "action": "reuse"})
            else:
                conflicts.append(
                    {
                        "rel-path": rel,
                        "logical-id": info["logical-id"],
                        "conflicting-id": "<existing run-root>",
                        "left-hash": dest_hash,
                        "right-hash": info["hash"],
                        "left-source": dest,
                        "right-source": info["source"],
                    }
                )
        else:
            actions.append({**info, "action": "link"})
    return actions, conflicts


def _link_or_copy(source: pathlib.Path, dest: pathlib.Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.link(source, dest)
    except OSError:
        shutil.copy2(source, dest)


def apply_merge_actions(actions: list[dict]) -> None:
    for action in actions:
        if action["action"] == "link":
            _link_or_copy(action["source"], action["dest"])


# ── registry construction ─────────────────────────────────────────────────────


def _logical_id_entries(actions: list[dict]) -> dict[str, dict]:
    """Deduplicate merged actions by logical id (deterministic smallest path).

    Same id with different hashes was already surfaced as a path conflict by
    the merge planner only when paths matched; guard the remaining cross-path
    case here (same id, different content) as a hard error.
    """
    by_id: dict[str, dict] = {}
    for a in sorted(actions, key=lambda x: x["rel-path"]):
        logical_id = a["logical-id"]
        prev = by_id.get(logical_id)
        if prev is None:
            by_id[logical_id] = a
            continue
        if prev["hash"] != a["hash"]:
            raise ConsolidationError(
                f"logical artifact id {logical_id!r} produced with conflicting "
                f"content: {prev['rel-path']} vs {a['rel-path']}"
            )
    return by_id


def _build_registry(
    cfg: EvidenceConfig,
    run_id: str,
    run_root: pathlib.Path,
    actions: list[dict],
) -> dict:
    by_id = _logical_id_entries(actions)
    entries: list[dict] = []
    for logical_id in sorted(by_id):
        action = by_id[logical_id]
        meta = artifact_meta(action["dest"])
        if meta is None:
            raise ConsolidationError(
                f"merged artifact missing after merge: {action['dest']}"
            )
        cfg_art = cfg.artifact(logical_id)
        if cfg_art is not None:
            kind = cfg_art["kind"]
            schema_version = cfg.schema(cfg_art["schema_key"])
            importance = cfg_art["importance"]
            producer = cfg.producer(cfg_art["producer_key"])
            verifies = list(cfg_art.get("verifies_against", []))
            input_deps = list(cfg_art.get("input_dependencies", []))
        elif logical_id.startswith("event-evidence-"):
            ev = _event_evidence_meta(cfg)
            kind = ev.get("kind", "event-evidence")
            schema_version = cfg.schema(ev.get("schema_key", "event-evidence"))
            importance = ev.get("importance", "CORE")
            producer = cfg.producer(ev.get("producer_key", "simulation-engine"))
            verifies = list(ev.get("verifies_against", []))
            input_deps = list(ev.get("input_dependencies", []))
        else:
            raise ConsolidationError(
                f"artifact id {logical_id!r} is not declared in "
                f"config/evidence.json and is not event-evidence"
            )
        entry = {
            "id": logical_id,
            "kind": kind,
            "path": action["rel-path"],
            "importance": importance,
            "schema_version": schema_version,
            "contract_version": cfg.contract_version,
            "producer": producer,
            "verifies_against": verifies,
            "dependencies": [],
            "input_dependencies": input_deps,
            **meta,
        }
        entries.append(entry)

    registered = {e["id"]: e for e in entries}
    missing_deps: Counter[tuple[str, str]] = Counter()
    for entry in entries:
        entry["dependencies"] = []
        for dep_id in entry.get("input_dependencies", []):
            dep = registered.get(dep_id)
            if dep is not None:
                entry["dependencies"].append({"id": dep_id, "sha256": dep["sha256"]})
            else:
                missing_deps[(entry["id"], dep_id)] += 1
        del entry["input_dependencies"]
    for (entry_id, dep_id), count in sorted(missing_deps.items()):
        print(f"  WARN: missing required dependency {dep_id} for {entry_id}"
              + (f" ({count} artifact(s))" if count > 1 else ""))

    registry = {
        "schema_version": cfg.schema("test-artifacts"),
        "contract_version": cfg.contract_version,
        "run_id": run_id,
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "generator": {
            "name": "artifact-registry-consolidator",
            "version": "v1",
        },
        "root_dir": str(run_root.resolve()),
        "artifacts": entries,
    }

    run_manifest_path = run_root / "test-run.json"
    if run_manifest_path.exists():
        meta = artifact_meta(run_manifest_path)
        if meta is not None:
            registry["run_manifest"] = {
                "path": "test-run.json",
                "schema_version": cfg.schema("test-run"),
                "sha256": meta["sha256"],
                "bytes": meta["bytes"],
                "mtime_utc": meta["mtime_utc"],
            }
    return registry


# ── CLI ───────────────────────────────────────────────────────────────────────


def _default_run_id() -> str:
    env = os.environ.get("PRF_RUN_ID")
    if env:
        return env
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d-%H%M%S")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="Consolidate parallel test artifacts into a canonical "
        "run-root with a unified test-artifacts.v1.2 registry."
    )
    ap.add_argument(
        "--run-root",
        required=True,
        help="Canonical output directory (e.g. results/test-artifacts)",
    )
    ap.add_argument(
        "--producer-roots",
        nargs="*",
        default=[],
        help="Producer root dirs (or containers whose marked subdirs are "
        "producers).  Optional: when omitted only the canonical run-root's own "
        "config files are registered.",
    )
    ap.add_argument(
        "--run-id",
        default=None,
        help="Run id recorded in the registry (default: $PRF_RUN_ID or timestamp)",
    )
    ap.add_argument(
        "--allow-loose",
        action="store_true",
        help="Treat an unmarked directory as a single producer root instead of "
        "requiring an ownership marker or marked subdirs",
    )
    ap.add_argument(
        "--config",
        default=str(_REPO_ROOT / "config/evidence.json"),
        help="Path to config/evidence.json (default: resolved from this script)",
    )
    args = ap.parse_args(argv)

    run_root = pathlib.Path(args.run_root)
    run_root.mkdir(parents=True, exist_ok=True)
    cfg = EvidenceConfig(args.config)
    run_id = args.run_id or _default_run_id()

    print(f"[consolidate] run-root: {run_root}  run-id: {run_id}")

    try:
        producer_roots = discover_producer_roots(args.producer_roots, args.allow_loose)
    except ConsolidationError as e:
        print(f"[consolidate] ERROR: {e}", file=sys.stderr)
        return 1

    entries: list[dict] = []
    for root in producer_roots:
        try:
            entries.extend(collect_producer_entries(root, cfg))
        except ConsolidationError as e:
            print(f"[consolidate] ERROR: {e}", file=sys.stderr)
            return 1

    # The canonical run-root's own config files (summary/run-manifest written by
    # the summary step, plus any target outputs in sequential mode) are always
    # registered.  Producer entries come first so they take precedence for an
    # identical relative path.
    entries.extend(_scan_run_root_top_level(run_root, cfg))

    try:
        actions, conflicts = _resolve_merge_actions(entries, run_root)
    except ConsolidationError as e:
        print(f"[consolidate] ERROR: {e}", file=sys.stderr)
        return 1

    if conflicts:
        print(f"[consolidate] FAIL: {len(conflicts)} content conflict(s); "
              f"nothing written", file=sys.stderr)
        for c in conflicts:
            print(
                f"  CONFLICT  {c['rel-path']}"
                f"  [{c['logical-id']} vs {c['conflicting-id']}]",
                file=sys.stderr,
            )
            print(f"    left:  {c['left-hash']}  {c['left-source']}", file=sys.stderr)
            print(f"    right: {c['right-hash']}  {c['right-source']}", file=sys.stderr)
        return 1

    apply_merge_actions(actions)
    n_linked = sum(1 for a in actions if a["action"] == "link")
    n_reused = sum(1 for a in actions if a["action"] == "reuse")
    print(f"[consolidate] merged {len(actions)} artifact file(s) "
          f"({n_linked} linked/copied, {n_reused} reused)")

    try:
        registry = _build_registry(cfg, run_id, run_root, actions)
    except ConsolidationError as e:
        print(f"[consolidate] ERROR: {e}", file=sys.stderr)
        return 1

    struct_errors = SchemaValidator().validate(registry)
    if struct_errors:
        print(f"[consolidate] FAIL: registry structurally invalid "
              f"({len(struct_errors)} error(s))", file=sys.stderr)
        for e in struct_errors:
            print(f"  {e.path}: {e.message}", file=sys.stderr)
        return 1

    registry_file = run_root / "test-artifacts.json"
    write_atomic_json(registry_file, registry)
    print(f"[consolidate] wrote unified registry: {registry_file} "
          f"({len(registry['artifacts'])} artifacts)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
