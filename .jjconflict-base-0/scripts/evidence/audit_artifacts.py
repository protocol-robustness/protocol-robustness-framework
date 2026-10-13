#!/usr/bin/env python3
"""Audit a consolidated test-artifact registry and its producer roots.

Checks
  AUD-1  registry exists and parses
  AUD-2  registry is structurally valid (schemas/test-artifacts-v1.2.json)
  AUD-3  every registered path is canonically contained under root_dir and
         exists; symlinked artifact paths are rejected (containment is decided
         on the resolved/canonical path, never on string prefixes)
  AUD-4  every artifact identity is authorized by the configured vocabulary:
         schema_version / producer / kind / importance agree with the config
         declaration; event-evidence-* ids must conform to a closed structural
         rule (prefix + path under the event-evidence dir + matching schema),
         not an open wildcard
  AUD-5  registry identities are unique (raw id, NFC-normalized id, and
         one-id-per-resolved-path)
  AUD-6  references close over the registry (no dangling ids / verifies_against)
         and the dependency graph is acyclic; exempt schemas must not also be
         provided by an artifact (an exemption may not mask a resolvable
         dependency)
  AUD-7  producer provenance is bound: a marked producer must carry a
         parseable, structurally valid _manifest.json whose run id / namespace /
         scope-status bind to its _owner.edn and whose claimed artifacts exist,
         are contained, hash-match, and are represented in the registry
  AUD-8  completeness bijection: every config-relevant file on disk in an
         audited producer root is represented in the registry by id + content
         hash (no producer output silently dropped); symlinked producer files
         are rejected

Usage:
  python3 scripts/evidence/audit_artifacts.py
      [--registry results/test-artifacts/test-artifacts.json]
      [--producer-roots targets/unit targets/suites ...]
      [--config config/evidence.json]

Exit 0 on pass, 1 on failure.

REUSE BOUNDARY (see docs): parsing/schema/config-vocabulary utilities
(evidence_config, schema_validator, EDN/JSON loading) are reused freely, but
the claim-bearing audit logic below — containment, uniqueness, dependency
closure, producer<->manifest binding, registry<->filesystem bijection — is
implemented independently here so that a bug in the producer (collector)
cannot make the auditor pass for the same wrong reason.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
import unicodedata
from collections import Counter

from evidence_config import EvidenceConfig
from schema_validator import SchemaValidator

import consolidate_test_artifacts as cta

_META_NAMES = {"_owner.edn", "_manifest.json"}
_EVENT_EVIDENCE_ID = re.compile(r"^event-evidence-[A-Za-z0-9._-]+$")

# ── independent helpers ───────────────────────────────────────────────────────


def _sha256(path: pathlib.Path) -> str | None:
    try:
        h = hashlib.sha256()
        with path.open("rb") as f:
            for chunk in iter(lambda: f.read(8192), b""):
                h.update(chunk)
        return h.hexdigest()
    except OSError:
        return None


def _is_temp(name: str) -> bool:
    return name.startswith(".tmp-") or name.endswith(".art") or name.endswith(".tmp")


def _resolve_contained(root: pathlib.Path, rel: str) -> tuple[bool, pathlib.Path | None]:
    """Canonical containment of *rel* under *root*.

    Returns (ok, path).  Rejects absolute paths, backslashes and any ``..``
    component lexically; rejects symlinks at any path component; then requires
    the resolved target to sit under the resolved root (``relative_to``, never a
    string prefix), which defeats prefix attacks such as /evidence vs
    /evidence-evil.
    """
    if not rel:
        return False, None
    p = pathlib.PurePosixPath(rel)
    if p.is_absolute() or "\\" in rel or any(part == ".." for part in p.parts):
        return False, None
    root_resolved = root.resolve()
    cur = root_resolved
    for part in p.parts:
        cur = cur / part
        if cur.is_symlink():
            return False, None
    try:
        cur.resolve().relative_to(root_resolved)
    except ValueError:
        return False, None
    return True, cur


def _path_has_symlink(p: pathlib.Path) -> bool:
    """True if any component of *p* (from the filesystem root) is a symlink."""
    abs_parts = p.absolute().parts
    cur = pathlib.Path(p.absolute().anchor)
    for part in abs_parts[1:]:
        cur = cur / part
        if cur.is_symlink():
            return True
    return False


def _validate_manifest_structure(m) -> bool:
    """Structural validation of the artifact-scope _manifest.json export."""
    if not isinstance(m, dict):
        return False
    for key in ("run-id", "namespace", "scope-status", "artifacts"):
        if key not in m:
            return False
    arts = m.get("artifacts")
    if not isinstance(arts, list):
        return False
    return all(
        isinstance(a, dict) and a.get("logical-id") and a.get("relative-path")
        for a in arts
    )


def _config_relevant_files(root: pathlib.Path, cfg: EvidenceConfig):
    """Independent walk of a producer root: classify config-relevant files.

    Returns (files, violations) where files maps posix-relative path to
    (logical-id, abs path) and violations lists symlinked paths encountered.
    The classification contract mirrors the collector's config vocabulary
    (config artifact file names + the event-evidence directory), but the
    traversal itself is independent audit logic and never follows symlinks.
    Marked subdirectories (separate producers) are skipped.
    """
    file_to_id = {
        a["file"]: a["id"]
        for a in cfg._data.get("artifacts", [])
        if a.get("file")
    }
    ev_dir = cfg._data.get("event_evidence", {}).get("dir") or "event-evidence"
    files: dict[str, tuple[str, pathlib.Path]] = {}
    violations: list[str] = []

    def walk(d: pathlib.Path) -> None:
        for entry in sorted(d.iterdir()):
            if entry.name in _META_NAMES or _is_temp(entry.name):
                continue
            if entry.is_symlink():
                violations.append(entry.relative_to(root).as_posix())
                continue
            if entry.is_dir():
                if (entry / "_owner.edn").exists() or (entry / "_manifest.json").exists():
                    continue  # separate producer root
                walk(entry)
            elif entry.is_file():
                rel = entry.relative_to(root).as_posix()
                parts = pathlib.PurePosixPath(rel).parts
                if parts and parts[0] == ev_dir:
                    files[rel] = (f"event-evidence-{entry.stem}", entry)
                elif entry.name in file_to_id:
                    files[rel] = (file_to_id[entry.name], entry)

    walk(root)
    return files, violations


def _find_cycles(reg: dict) -> list[list[str]]:
    """Dependency cycles over the canonical :dependencies id graph."""
    deps = {
        a["id"]: [d["id"] for d in a.get("dependencies", [])]
        for a in reg.get("artifacts", [])
    }
    WHITE, GRAY, BLACK = 0, 1, 2
    color = {n: WHITE for n in deps}
    cycles: list[list[str]] = []

    def dfs(node: str, stack: list[str]) -> None:
        color[node] = GRAY
        stack.append(node)
        for nxt in deps.get(node, []):
            if nxt not in deps:
                continue  # dangling -> AUD-6a
            if color.get(nxt) == GRAY:
                idx = stack.index(nxt)
                cycles.append(stack[idx:] + [nxt])
            elif color.get(nxt) == WHITE:
                dfs(nxt, stack)
        stack.pop()
        color[node] = BLACK

    for node in deps:
        if color[node] == WHITE:
            dfs(node, [])
    return cycles


# ── main ──────────────────────────────────────────────────────────────────────


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="Audit a consolidated test-artifact registry."
    )
    ap.add_argument("--registry", default="results/test-artifacts/test-artifacts.json")
    ap.add_argument("--producer-roots", nargs="*", default=[])
    ap.add_argument("--config", default=str(cta._REPO_ROOT / "config/evidence.json"))
    args = ap.parse_args(argv)

    cfg = EvidenceConfig(args.config)
    checks: list[tuple[bool, str, str]] = []
    failed = 0

    def add(ok: bool, label: str, detail: str = "") -> None:
        nonlocal failed
        if not ok:
            failed += 1
        checks.append((ok, label, detail))

    # ── AUD-1 / AUD-2 ────────────────────────────────────────────────────────
    reg: dict | None = None
    reg_path = pathlib.Path(args.registry)
    if not reg_path.exists():
        add(False, "AUD-1: registry exists", str(reg_path))
    else:
        try:
            reg = json.loads(reg_path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError) as e:
            add(False, "AUD-1: registry parses", str(e))
        else:
            add(True, "AUD-1: registry parses")

    if reg is not None:
        errors = SchemaValidator().validate(reg)
        add(not errors, "AUD-2: structurally valid",
            "; ".join(f"{e.path}: {e.message}" for e in errors))

        root_dir = pathlib.Path(reg.get("root_dir", "."))
        arts = reg.get("artifacts", [])
        ev_dir = cfg._data.get("event_evidence", {}).get("dir") or "event-evidence"
        ev = dict(cfg._data.get("event_evidence", {}))

        # ── AUD-3: canonical containment + existence ─────────────────────────
        bad = []
        for a in arts:
            ok, resolved = _resolve_contained(root_dir, a.get("path", ""))
            if not ok or resolved is None or not resolved.exists():
                bad.append(f"{a.get('id')}->{a.get('path')}")
        add(not bad, "AUD-3: registered paths canonically contained and exist",
            "; ".join(bad))

        # ── AUD-4: identity authorized by configured vocabulary ──────────────
        identity_bad = []
        for a in arts:
            aid = a.get("id")
            decl = cfg.artifact(aid)
            if decl is not None:
                wanted = {
                    "schema_version": cfg.schema(decl["schema_key"]),
                    "producer": cfg.producer(decl["producer_key"]),
                    "kind": decl["kind"],
                    "importance": decl["importance"],
                }
            elif aid and aid.startswith("event-evidence-") and _EVENT_EVIDENCE_ID.match(aid):
                wanted = {
                    "schema_version": cfg.schema(ev.get("schema_key", "event-evidence")),
                    "producer": cfg.producer(ev.get("producer_key", "simulation-engine")),
                    "kind": ev.get("kind", "event-evidence"),
                    "importance": ev.get("importance", "CORE"),
                }
                if not a.get("path", "").startswith(f"{ev_dir}/"):
                    identity_bad.append(
                        f"{aid}: event-evidence path must be under {ev_dir}/")
                    continue
            else:
                identity_bad.append(f"{aid}: not a config-declared id")
                continue
            for field, expected in wanted.items():
                if a.get(field) != expected:
                    identity_bad.append(
                        f"{aid}: {field}={a.get(field)!r} != configured {expected!r}")
        add(not identity_bad, "AUD-4: identities authorized by config vocabulary",
            "; ".join(identity_bad))

        # ── AUD-5: uniqueness (raw id, NFC, one-id-per-path) ─────────────────
        ids = [a["id"] for a in arts]
        dupes = sorted({i for i, c in Counter(ids).items() if c > 1})
        nfc_groups: dict[str, set[str]] = {}
        for i in ids:
            nfc_groups.setdefault(unicodedata.normalize("NFC", i), set()).add(i)
        norm_collisions = sorted(
            n for n, grp in nfc_groups.items() if len(grp) > 1)
        path_to_ids: dict[str, list[str]] = {}
        for a in arts:
            ok, resolved = _resolve_contained(root_dir, a.get("path", ""))
            if ok and resolved is not None:
                path_to_ids.setdefault(str(resolved), []).append(a["id"])
        path_collisions = sorted(
            p for p, idents in path_to_ids.items() if len(set(idents)) > 1)
        add(not dupes, "AUD-5a: no duplicate ids", str(dupes))
        add(not norm_collisions, "AUD-5b: no NFC-normalization id collisions",
            str(norm_collisions))
        add(not path_collisions, "AUD-5c: no id collisions on a shared resolved path",
            str(path_collisions))

        # ── AUD-6: referential closure + acyclic graph + exemption honesty ───
        provided_ids = {a["id"] for a in arts}
        provided_schemas = {a["schema_version"] for a in arts}
        exempt = set(cfg._data.get("exempt_schemas", []))
        dangling_refs = sorted(
            {
                d["id"]
                for a in arts
                for d in a.get("dependencies", [])
                if d["id"] not in provided_ids
            }
        )
        dangling_va = sorted(
            {
                s
                for a in arts
                for s in a.get("verifies_against", [])
                if s not in provided_schemas and s not in exempt
            }
        )
        cycles = _find_cycles(reg)
        masked = sorted(s for s in exempt if s in provided_schemas)
        add(not dangling_refs, "AUD-6a: no dangling :dependencies refs",
            str(dangling_refs))
        add(not dangling_va, "AUD-6b: no dangling verifies_against",
            str(dangling_va))
        add(not cycles, "AUD-6c: dependency graph is acyclic",
            "; ".join("->".join(c) for c in cycles))
        add(not masked, "AUD-6d: exempt schemas are not also provided",
            f"exempt-but-provided: {masked}")

    # ── AUD-7 / AUD-8: producer provenance and completeness ──────────────────
    if args.producer_roots:
        try:
            roots = cta.discover_producer_roots(args.producer_roots, allow_loose=True)
        except cta.ConsolidationError as e:
            add(False, "AUD-7: producer roots discoverable", str(e))
            roots = []
        else:
            add(True, "AUD-7: producer roots discoverable",
                f"{len(roots)} producer root(s)")
        for root in roots:
            if _path_has_symlink(root):
                add(False, "AUD-7: producer root is not a symlink", str(root))
                continue

            marker = cta.read_owner_marker(root)
            manifest = cta.read_manifest(root)
            marked = cta._has_marker(root)

            if not marked:
                add(True, "AUD-7: loose producer has no provenance binding",
                    f"skipped ({root})")
            else:
                if manifest is None:
                    add(False, "AUD-7: marked producer carries _manifest.json",
                        str(root))
                else:
                    if not _validate_manifest_structure(manifest):
                        add(False, "AUD-7: manifest structure valid", str(root))
                    else:
                        add(True, "AUD-7: manifest structure valid", str(root))
                        add(manifest.get("scope-status") == "complete",
                            "AUD-7: manifest scope-status complete",
                            f"{root}: {manifest.get('scope-status')!r}")
                        if marker:
                            add(str(manifest.get("run-id")) == str(marker.get("run-id")),
                                "AUD-7: manifest run-id binds to _owner.edn",
                                f"{root}: {manifest.get('run-id')!r} vs {marker.get('run-id')!r}")
                            add(str(manifest.get("namespace")) == str(marker.get("namespace")),
                                "AUD-7: manifest namespace binds to _owner.edn",
                                f"{root}: {manifest.get('namespace')!r} vs {marker.get('namespace')!r}")
                        for art in manifest.get("artifacts", []):
                            rel = art.get("relative-path")
                            ok, resolved = _resolve_contained(root, rel)
                            if not ok or resolved is None or not resolved.exists():
                                add(False, "AUD-7: manifest artifact exists+contained",
                                    f"{root}: {art.get('logical-id')} -> {rel}")
                                continue
                            disk_hash = _sha256(resolved)
                            claim = art.get("content-hash") or art.get("byte-hash")
                            if disk_hash and claim and disk_hash != claim:
                                add(False, "AUD-7: manifest artifact hash matches disk",
                                    f"{root}: {art.get('logical-id')}")
                            represented = (
                                reg is not None
                                and any(
                                    e.get("id") == art.get("logical-id")
                                    and e.get("sha256") == claim
                                    for e in reg.get("artifacts", [])
                                )
                            )
                            add(represented,
                                "AUD-7: manifest artifact represented in registry",
                                f"{root}: {art.get('logical-id')}")

            # ── AUD-8: completeness bijection for this producer ──────────────
            files, symlinks = _config_relevant_files(root, cfg)
            add(not symlinks, "AUD-8: no symlinked producer files",
                f"{root}: {symlinks}")
            dropped = []
            for rel, (logical_id, f) in sorted(files.items()):
                file_hash = _sha256(f)
                represented = (
                    reg is not None
                    and file_hash is not None
                    and any(
                        e.get("id") == logical_id and e.get("sha256") == file_hash
                        for e in reg.get("artifacts", [])
                    )
                )
                if not represented:
                    dropped.append(f"{logical_id} ({rel})")
            add(not dropped, "AUD-8: producer output fully represented in registry",
                f"{root}: {', '.join(dropped)}")
    else:
        add(True, "AUD-7/8: producer roots", "skipped (none supplied)")

    print(f"=== audit-artifacts: {args.registry} ===")
    for ok, label, detail in checks:
        if ok:
            print(f"  PASS  {label}")
        else:
            print(f"  FAIL  {label}" + (f"  — {detail}" if detail else ""))
    print(f"=== {len(checks) - failed} passed, {failed} failed ===")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
