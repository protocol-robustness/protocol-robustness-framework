#!/usr/bin/env python3
"""Audit a consolidated test-artifact registry and its producer roots.

Verifies:
  AUD-1  registry exists and parses
  AUD-2  registry is structurally valid (schemas/test-artifacts-v1.2.json)
  AUD-3  every artifact path resolves to a real file under root_dir
  AUD-4  every artifact id is declared in config/evidence.json (or is
         event-evidence-*)
  AUD-5  no duplicate artifact ids
  AUD-6  no dangling dependencies (dependencies[].id is provided by an artifact
         id; verifies_against is provided as a schema_version or exempted in
         config/evidence.json)
  AUD-7  every supplied producer root is discoverable (marked producer or
         container of marked producers / loose dir) and every marked producer
         also carries a _manifest.json

Usage:
  python3 scripts/evidence/audit_artifacts.py
      [--registry results/test-artifacts/test-artifacts.json]
      [--producer-roots targets/unit targets/suites ...]
      [--config config/evidence.json]

Exit 0 on pass, 1 on failure.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
from collections import Counter

from evidence_config import EvidenceConfig
from schema_validator import SchemaValidator

import consolidate_test_artifacts as cta


def _dangling(reg: dict, cfg: EvidenceConfig) -> tuple[list[str], list[str]]:
    """Mirror resolver-sim.validation.integration.artifact-registry's checks."""
    provided_ids = {a["id"] for a in reg.get("artifacts", [])}
    provided_schemas = {a["schema_version"] for a in reg.get("artifacts", [])}
    exempt = set(cfg._data.get("exempt_schemas", []))
    dangling_refs = sorted(
        {
            d["id"]
            for a in reg.get("artifacts", [])
            for d in a.get("dependencies", [])
            if d["id"] not in provided_ids
        }
    )
    dangling_va = sorted(
        {
            s
            for a in reg.get("artifacts", [])
            for s in a.get("verifies_against", [])
            if s not in provided_schemas and s not in exempt
        }
    )
    return dangling_refs, dangling_va


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

    reg: dict | None = None
    reg_path = pathlib.Path(args.registry)
    if not reg_path.exists():
        add(False, "AUD-1: registry exists", str(reg_path))
    else:
        try:
            reg = json.loads(reg_path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError) as e:
            add(False, "AUD-1: registry parses", str(e))
            reg = None
        else:
            add(True, "AUD-1: registry parses")

    if reg is not None:
        errors = SchemaValidator().validate(reg)
        add(not errors, "AUD-2: structurally valid",
            "; ".join(f"{e.path}: {e.message}" for e in errors))

        root_dir = pathlib.Path(reg.get("root_dir", "."))
        arts = reg.get("artifacts", [])
        ids = [a["id"] for a in arts]
        dupes = sorted({i for i, c in Counter(ids).items() if c > 1})
        add(not dupes, "AUD-5: no duplicate artifact ids", str(dupes))

        missing = [
            a["id"] for a in arts
            if not (root_dir / a.get("path", "")).exists()
        ]
        add(not missing, "AUD-3: artifact paths resolve under root_dir", str(missing))

        undeclared = [
            a["id"] for a in arts
            if cfg.artifact(a["id"]) is None
            and not a["id"].startswith("event-evidence-")
        ]
        add(not undeclared, "AUD-4: artifact ids declared in config", str(undeclared))

        dangling_refs, dangling_va = _dangling(reg, cfg)
        add(not dangling_refs, "AUD-6a: no dangling :dependencies refs",
            str(dangling_refs))
        add(not dangling_va, "AUD-6b: no dangling verifies_against",
            str(dangling_va))

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
            if cta._has_marker(root) and not (root / "_manifest.json").exists():
                add(False, "AUD-7: marked producer carries _manifest.json", str(root))
    else:
        add(True, "AUD-7: producer roots", "skipped (none supplied)")

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
