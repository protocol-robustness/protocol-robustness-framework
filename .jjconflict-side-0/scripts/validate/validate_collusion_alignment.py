#!/usr/bin/env python3
"""Validate collusion scenario/claim alignment.

Canonical scenarios are EDN.  Clojure performs the EDN decoding so this
validator does not introduce a second EDN parser or interpretation in Python.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any


def load_edn(path: Path) -> dict[str, Any]:
    command = (
        "(require '[clojure.edn :as edn] '[clojure.data.json :as json]) "
        f"(println (json/write-str (edn/read-string (slurp {json.dumps(str(path))}))))"
    )
    result = subprocess.run(
        ["clojure", "-M", "-e", command],
        check=True,
        capture_output=True,
        text=True,
    )
    payload = result.stdout.strip().splitlines()
    if not payload:
        raise ValueError("empty EDN decoding result")
    document = json.loads(payload[-1])
    if not isinstance(document, dict):
        raise ValueError("scenario must decode to a map")
    return document


def requires_collusion_alignment(document: dict[str, Any]) -> bool:
    theory = document.get("theory") or {}
    mechanism_properties = theory.get("mechanism-properties") or []
    claim = str(theory.get("claim", "")).lower()
    tags = [str(tag).lower() for tag in (document.get("threat-tags") or [])]
    return (
        "collusion-resistance" in mechanism_properties
        or "collusion" in claim
        or any("collusion" in tag for tag in tags)
    )


def is_inconclusive_fixture(document: dict[str, Any]) -> bool:
    scenario_id = str(document.get("scenario-id", ""))
    title = str(document.get("scenario-title") or document.get("title") or "").lower()
    return "collusion-resistance-inconclusive" in scenario_id or "inconclusive" in title


def has_collusive_actor(document: dict[str, Any]) -> bool:
    return any(
        str(agent.get("strategy") or agent.get("type") or "").lower() == "collusive"
        for agent in (document.get("agents") or [])
        if isinstance(agent, dict)
    )


def main() -> int:
    root = Path("scenarios/edn")
    errors: list[str] = []

    for path in sorted(root.glob("*.edn")):
        # Collusion is the only property this validator reads; avoid starting
        # Clojure for every scenario in the catalog.
        if "collusion" not in path.read_text(encoding="utf-8").lower():
            continue
        try:
            document = load_edn(path)
        except (OSError, subprocess.CalledProcessError, ValueError, json.JSONDecodeError) as exc:
            errors.append(f"collusion-alignment-unreadable:{path}:{exc}")
            continue

        if (
            requires_collusion_alignment(document)
            and not has_collusive_actor(document)
            and not is_inconclusive_fixture(document)
        ):
            errors.append(f"collusion-alignment-missing:{path}:expected at least one agent.strategy='collusive'")

    if errors:
        print("Collusion fixture/claim alignment checks failed:")
        for error in errors:
            print(" -", error)
        return 1

    print("Collusion fixture/claim alignment checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
