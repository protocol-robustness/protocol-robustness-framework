#!/usr/bin/env python3
"""Stamp :scenario-author on invariant scenario defs and trace JSON files."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AUTHOR = "@grifma"
SCENARIO_DIR = ROOT / "src" / "resolver_sim" / "protocols" / "sew" / "invariant_scenarios"
TRACE_DIR = ROOT / "data" / "fixtures" / "traces"
SCENARIOS_JSON = ROOT / "scenarios"


def stamp_clj_file(path: Path) -> bool:
    text = path.read_text()
    if ":scenario-author" in text:
        return False
    new_text, n = re.subn(
        r'(:schema-version\s+"[^"]+")',
        rf'\1\n   :scenario-author "{AUTHOR}"',
        text,
    )
    if n:
        path.write_text(new_text)
    return n > 0


def stamp_trace_json(path: Path) -> bool:
    try:
        obj = json.loads(path.read_text())
    except json.JSONDecodeError:
        return False
    if obj.get("scenario-author") == AUTHOR:
        return False
    obj["scenario-author"] = AUTHOR
    path.write_text(json.dumps(obj, indent=2) + "\n")
    return True


def stamp_scenarios_json(path: Path) -> bool:
    try:
        obj = json.loads(path.read_text())
    except json.JSONDecodeError:
        return False
    if not isinstance(obj, dict):
        return False
    changed = False
    if obj.get("scenario-author") != AUTHOR:
        obj["scenario-author"] = AUTHOR
        changed = True
    prov = obj.setdefault("provenance", {})
    if prov.get("author-id") != AUTHOR:
        prov["author-id"] = AUTHOR
        prov.setdefault("author", "Marc Griffiths")
        changed = True
    if changed:
        path.write_text(json.dumps(obj, indent=2) + "\n")
    return changed


def main() -> None:
    clj = sum(stamp_clj_file(p) for p in SCENARIO_DIR.glob("*.clj"))
    clj += 1 if stamp_clj_file(ROOT / "src/resolver_sim/protocols/sew/invariant_scenarios.clj") else 0
    traces = sum(stamp_trace_json(p) for p in TRACE_DIR.rglob("*.trace.json"))
    scenarios = sum(stamp_scenarios_json(p) for p in SCENARIOS_JSON.glob("*.json"))
    print(f"Stamped {clj} clj files, {traces} traces, {scenarios} scenario JSON files")


if __name__ == "__main__":
    main()
