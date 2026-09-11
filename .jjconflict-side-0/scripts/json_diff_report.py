#!/usr/bin/env python3
"""Print leaf-level JSON diffs for reference-validation verify failures."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


JsonValue = dict[str, Any] | list[Any] | str | int | float | bool | None


def collect_diffs(expected: JsonValue, actual: JsonValue, path: str = "") -> list[dict[str, Any]]:
    if expected == actual:
        return []

    if isinstance(expected, dict) and isinstance(actual, dict):
        out: list[dict[str, Any]] = []
        for key in sorted(set(expected) | set(actual)):
            child = f"{path}.{key}" if path else str(key)
            if key not in actual:
                out.append({"path": child, "expected": expected[key], "actual": None})
            elif key not in expected:
                out.append({"path": child, "expected": None, "actual": actual[key]})
            else:
                out.extend(collect_diffs(expected[key], actual[key], child))
        return out

    if isinstance(expected, list) and isinstance(actual, list):
        out: list[dict[str, Any]] = []
        max_len = max(len(expected), len(actual))
        for idx in range(max_len):
            child = f"{path}[{idx}]"
            if idx >= len(expected):
                out.append({"path": child, "expected": None, "actual": actual[idx]})
            elif idx >= len(actual):
                out.append({"path": child, "expected": expected[idx], "actual": None})
            else:
                out.extend(collect_diffs(expected[idx], actual[idx], child))
        return out

    return [{"path": path or "$", "expected": expected, "actual": actual}]


def format_value(value: Any, *, max_len: int = 120) -> str:
    text = json.dumps(value, sort_keys=True, separators=(",", ":"))
    if len(text) > max_len:
        return text[: max_len - 3] + "..."
    return text


def render_report(
    expected_path: Path,
    actual_path: Path,
    *,
    limit: int = 20,
) -> str:
    expected = json.loads(expected_path.read_text(encoding="utf-8"))
    actual = json.loads(actual_path.read_text(encoding="utf-8"))
    diffs = collect_diffs(expected, actual)
    lines = [
        f"Leaf diff for {actual_path.name}:",
        f"  expected: {expected_path}",
        f"  actual:   {actual_path}",
        f"  differences: {len(diffs)} (showing up to {limit})",
    ]
    for diff in diffs[:limit]:
        lines.append(
            "  - "
            f"{diff['path']}: expected {format_value(diff['expected'])} "
            f"actual {format_value(diff['actual'])}"
        )
    if len(diffs) > limit:
        lines.append(f"  … {len(diffs) - limit} more")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Print leaf-level JSON diffs")
    parser.add_argument("expected", help="Expected JSON file")
    parser.add_argument("actual", help="Actual JSON file")
    parser.add_argument("--limit", type=int, default=20, help="Max diffs to print")
    args = parser.parse_args(argv)

    print(render_report(Path(args.expected), Path(args.actual), limit=args.limit))
    return 0


if __name__ == "__main__":
    sys.exit(main())
