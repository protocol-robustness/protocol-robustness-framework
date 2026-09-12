#!/usr/bin/env python3
"""
Compare two simulation replay trace JSON files (baseline vs candidate) and emit:
  - JSON summary (metrics, projection-hash, first structural divergence)
  - Markdown summary (research-shareable)
"""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path
from typing import Any


JsonValue = dict[str, Any] | list[Any] | str | int | float | bool | None


def _metric_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    if isinstance(value, str):
        stripped = value.strip()
        if stripped.isdigit() or (stripped.startswith("-") and stripped[1:].isdigit()):
            return int(stripped)
    return None


def extract_metrics(doc: dict[str, Any]) -> dict[str, int]:
    if isinstance(doc.get("metrics"), dict):
        out: dict[str, int] = {}
        for key, value in doc["metrics"].items():
            parsed = _metric_int(value)
            if parsed is not None:
                out[key] = parsed
        return out
    return {}


def metric_delta(base_m: dict[str, int], cand_m: dict[str, int]) -> dict[str, dict[str, int]]:
    out: dict[str, dict[str, int]] = {}
    all_keys = sorted(set(base_m.keys()) | set(cand_m.keys()))
    for k in all_keys:
        b = base_m.get(k, 0)
        c = cand_m.get(k, 0)
        out[k] = {"baseline": b, "candidate": c, "delta": c - b}
    return out


def extract_outcome(doc: dict[str, Any]) -> str:
    return str(doc.get("outcome") or doc.get("purpose") or "unknown")


def extract_events_processed(doc: dict[str, Any]) -> int:
    if "events-processed" in doc:
        return int(doc.get("events-processed", 0) or 0)
    return len(doc.get("events", []) or doc.get("trace", []) or [])


def extract_trace(doc: dict[str, Any]) -> list[dict[str, Any]]:
    trace = doc.get("trace", [])
    return trace if isinstance(trace, list) else []


def extract_projection_hash(doc: dict[str, Any]) -> str | None:
    trace = extract_trace(doc)
    if not trace:
        return None
    last = trace[-1]
    if not isinstance(last, dict):
        return None
    value = last.get("projection-hash")
    return str(value) if value else None


def extract_terminal_entity_count(doc: dict[str, Any]) -> int:
    trace = extract_trace(doc)
    if not trace:
        return 0
    world = trace[-1].get("world", {}) or {}
    return int(world.get("entity_count", 0) or 0)


def world_without_trace(step: dict[str, Any]) -> dict[str, Any]:
    world = step.get("world", {}) or {}
    if not isinstance(world, dict):
        return {}
    return {k: v for k, v in world.items() if k != "trace"}


def structural_diff(left: JsonValue, right: JsonValue) -> tuple[Any | None, Any | None]:
    """Clojure data/diff-style partial diff for maps and lists."""
    if left == right:
        return None, None

    if isinstance(left, dict) and isinstance(right, dict):
        only_left: dict[str, Any] = {}
        only_right: dict[str, Any] = {}
        for key in sorted(set(left) | set(right)):
            if key not in right:
                only_left[key] = left[key]
            elif key not in left:
                only_right[key] = right[key]
            else:
                sub_left, sub_right = structural_diff(left[key], right[key])
                if sub_left is not None:
                    only_left[key] = sub_left
                if sub_right is not None:
                    only_right[key] = sub_right
        return (only_left or None, only_right or None)

    if isinstance(left, list) and isinstance(right, list):
        only_left: list[Any] = []
        only_right: list[Any] = []
        max_len = max(len(left), len(right))
        for idx in range(max_len):
            if idx >= len(left):
                only_right.append(right[idx])
            elif idx >= len(right):
                only_left.append(left[idx])
            else:
                sub_left, sub_right = structural_diff(left[idx], right[idx])
                if sub_left is not None:
                    only_left.append(sub_left)
                if sub_right is not None:
                    only_right.append(sub_right)
        return (only_left or None, only_right or None)

    return left, right


def diff_traces(trace_a: list[dict[str, Any]], trace_b: list[dict[str, Any]]) -> dict[str, Any] | None:
    """Return first step where world states diverge (mirrors resolver-sim.io.diff/diff-traces)."""
    len_a = len(trace_a)
    len_b = len(trace_b)
    min_len = min(len_a, len_b)

    for idx in range(min_len):
        step_a = trace_a[idx]
        step_b = trace_b[idx]
        only_a, only_b = structural_diff(
            world_without_trace(step_a),
            world_without_trace(step_b),
        )
        if only_a or only_b:
            return {
                "divergence_at": idx,
                "action": step_a.get("action"),
                "only_in_baseline": only_a,
                "only_in_candidate": only_b,
            }

    if len_a != len_b:
        return {
            "divergence_at": min_len,
            "reason": "trace-length-mismatch",
            "length_baseline": len_a,
            "length_candidate": len_b,
        }
    return None


def truncate_value(value: Any, *, max_depth: int = 4, max_items: int = 24) -> Any:
    if max_depth <= 0:
        return "…"
    if isinstance(value, dict):
        out: dict[str, Any] = {}
        for idx, (key, item) in enumerate(sorted(value.items(), key=lambda kv: str(kv[0]))):
            if idx >= max_items:
                out["…"] = f"{len(value) - max_items} more keys"
                break
            out[str(key)] = truncate_value(item, max_depth=max_depth - 1, max_items=max_items)
        return out
    if isinstance(value, list):
        out_list = [
            truncate_value(item, max_depth=max_depth - 1, max_items=max_items)
            for item in value[:max_items]
        ]
        if len(value) > max_items:
            out_list.append(f"… {len(value) - max_items} more items")
        return out_list
    return value


def build_structural_summary(base: dict[str, Any], cand: dict[str, Any]) -> dict[str, Any]:
    base_hash = extract_projection_hash(base)
    cand_hash = extract_projection_hash(cand)
    divergence = diff_traces(extract_trace(base), extract_trace(cand))
    truncated = None
    if divergence and ("only_in_baseline" in divergence or "only_in_candidate" in divergence):
        truncated = {
            **{k: v for k, v in divergence.items() if k not in {"only_in_baseline", "only_in_candidate"}},
            "only_in_baseline": truncate_value(divergence.get("only_in_baseline")),
            "only_in_candidate": truncate_value(divergence.get("only_in_candidate")),
        }

    return {
        "baseline_projection_hash": base_hash,
        "candidate_projection_hash": cand_hash,
        "projection_hash_match": base_hash == cand_hash if base_hash and cand_hash else None,
        "worlds_identical": divergence is None,
        "first_divergence": truncated or divergence,
    }


def make_headline(
    delta: dict[str, dict[str, int]],
    base_outcome: str,
    cand_outcome: str,
    structural: dict[str, Any],
) -> str:
    parts: list[str] = []

    if structural.get("projection_hash_match") is False:
        parts.append("projection-hash differs")
    elif structural.get("projection_hash_match") is True:
        parts.append("projection-hash match")

    divergence = structural.get("first_divergence")
    if divergence:
        if divergence.get("reason") == "trace-length-mismatch":
            parts.append(
                "trace length mismatch "
                f"({divergence.get('length_baseline')} vs {divergence.get('length_candidate')})"
            )
        else:
            parts.append(
                f"first world divergence at seq={divergence.get('divergence_at')} "
                f"({divergence.get('action')})"
            )
    elif structural.get("worlds_identical"):
        parts.append("worlds identical")

    non_zero = {k: v["delta"] for k, v in delta.items() if v["delta"] != 0}
    sorted_deltas = sorted(non_zero.items(), key=lambda x: abs(x[1]), reverse=True)[:3]
    if sorted_deltas:
        parts.append(", ".join(f"{k} Δ={v:+d}" for k, v in sorted_deltas))
    elif not parts:
        parts.append("no metric deltas")

    return f"Candidate ({cand_outcome}) vs baseline ({base_outcome}): {'; '.join(parts)}."


def compare_documents(
    base: dict[str, Any],
    cand: dict[str, Any],
    *,
    baseline_path: str = "",
    candidate_path: str = "",
) -> dict[str, Any]:
    base_m = extract_metrics(base)
    cand_m = extract_metrics(cand)
    deltas = metric_delta(base_m, cand_m)
    structural = build_structural_summary(base, cand)

    summary = {
        "baseline": {
            "path": baseline_path,
            "outcome": extract_outcome(base),
            "events_processed": extract_events_processed(base),
            "projection_hash": structural["baseline_projection_hash"],
        },
        "candidate": {
            "path": candidate_path,
            "outcome": extract_outcome(cand),
            "events_processed": extract_events_processed(cand),
            "projection_hash": structural["candidate_projection_hash"],
        },
        "metric_delta": deltas,
        "terminal_entity_counts": {
            "baseline": extract_terminal_entity_count(base),
            "candidate": extract_terminal_entity_count(cand),
        },
        "structural_diff": structural,
    }
    summary["headline"] = make_headline(
        deltas,
        summary["baseline"]["outcome"],
        summary["candidate"]["outcome"],
        structural,
    )
    return summary


def to_markdown(summary: dict[str, Any]) -> str:
    b = summary["baseline"]
    c = summary["candidate"]
    structural = summary.get("structural_diff", {})

    funds_keywords = {"volume", "funds", "held", "fee", "stake", "profit", "slash", "bond"}
    deltas = summary["metric_delta"]
    funds_metrics = {k: v for k, v in deltas.items() if any(kw in k.lower() for kw in funds_keywords)}
    other_metrics = {k: v for k, v in deltas.items() if k not in funds_metrics}

    hash_match = structural.get("projection_hash_match")
    if hash_match is True:
        hash_status = "✅ MATCH"
    elif hash_match is False:
        hash_status = "❌ DIVERGED"
    else:
        hash_status = "⚠️ UNKNOWN"

    divergence = structural.get("first_divergence")
    if divergence is None:
        divergence_status = "✅ none (worlds identical)"
        divergence_detail = "—"
    elif divergence.get("reason") == "trace-length-mismatch":
        divergence_status = "⚠️ trace length mismatch"
        divergence_detail = (
            f"seq {divergence.get('divergence_at')}: "
            f"{divergence.get('length_baseline')} vs {divergence.get('length_candidate')} steps"
        )
    else:
        divergence_status = "❌ world divergence"
        divergence_detail = f"seq {divergence.get('divergence_at')} action `{divergence.get('action')}`"

    lines = [
        "# Simulation Regression Report",
        "",
        f"- Baseline: `{b['path']}`",
        f"- Candidate: `{c['path']}`",
        "",
        "## Summary",
        "",
        summary["headline"],
        "",
        "## Outcome Comparison",
        "",
        "| Dimension | Baseline | Candidate | Status |",
        "|---|---|---|---|",
        f"| **Outcome** | {b['outcome']} | {c['outcome']} | {'✅ MATCH' if b['outcome'] == c['outcome'] else '❌ DIVERGED'} |",
        f"| **Events** | {b['events_processed']} | {c['events_processed']} | {'✅ MATCH' if b['events_processed'] == c['events_processed'] else '⚠️ DIFF'} |",
        f"| **Entities** | {summary['terminal_entity_counts']['baseline']} | {summary['terminal_entity_counts']['candidate']} | {'✅ MATCH' if summary['terminal_entity_counts']['baseline'] == summary['terminal_entity_counts']['candidate'] else '⚠️ DIFF'} |",
        f"| **projection-hash** | {b.get('projection_hash') or '—'} | {c.get('projection_hash') or '—'} | {hash_status} |",
        f"| **First divergence** | {divergence_detail} | | {divergence_status} |",
    ]

    if divergence and divergence.get("only_in_baseline") is not None:
        lines += [
            "",
            "### Divergence (baseline-only fields)",
            "",
            "```json",
            json.dumps(divergence.get("only_in_baseline"), indent=2, sort_keys=True),
            "```",
        ]
    if divergence and divergence.get("only_in_candidate") is not None:
        lines += [
            "",
            "### Divergence (candidate-only fields)",
            "",
            "```json",
            json.dumps(divergence.get("only_in_candidate"), indent=2, sort_keys=True),
            "```",
        ]

    if funds_metrics:
        lines += [
            "",
            "## 💰 Funds & Economic Usage",
            "",
            "| Economic Metric | Baseline | Candidate | Δ |",
            "|---|---:|---:|---:|",
        ]
        for k, row in sorted(funds_metrics.items()):
            lines.append(f"| {k} | {row['baseline']} | {row['candidate']} | {row['delta']:+d} |")

    lines += [
        "",
        "## 📊 Other Lifecycle Metrics",
        "",
        "| Metric | Baseline | Candidate | Δ |",
        "|---|---:|---:|---:|",
    ]
    for k, row in sorted(other_metrics.items()):
        lines.append(f"| {k} | {row['baseline']} | {row['candidate']} | {row['delta']:+d} |")

    return "\n".join(lines)


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def replay_scenario(trace_path: Path, out_path: Path, *, cwd: Path | None = None) -> None:
    """Replay a fixture trace via Clojure (-M:test) and write JSON replay result."""
    root = cwd or repo_root()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    clj = f"""
(require '[resolver-sim.io.scenarios :as io-sc]
         '[resolver-sim.scenario.normalize :as normalize]
         '[resolver-sim.protocols.sew :as sew]
         '[resolver-sim.io.serialization :as ser])
(let [raw (io-sc/load-scenario-file {json.dumps(str(trace_path.resolve()))})
      scenario (normalize/normalize-scenario raw)
      result (sew/replay-with-sew-protocol scenario)]
  (spit {json.dumps(str(out_path.resolve()))} (ser/serialize-artifact result)))
"""
    subprocess.run(
        ["clojure", "-M:test", "-e", clj],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )


def write_comparison_report(
    summary: dict[str, Any],
    out_dir: Path,
    *,
    basename: str = "comparison",
) -> tuple[Path, Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / f"{basename}.json"
    md_path = out_dir / f"{basename}.md"
    json_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    md_path.write_text(to_markdown(summary), encoding="utf-8")
    return json_path, md_path


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare baseline and candidate simulation trace JSON outputs")
    parser.add_argument("--baseline", help="Path to baseline replay JSON")
    parser.add_argument("--candidate", help="Path to candidate replay JSON")
    parser.add_argument("--scenario-a", help="Replay fixture trace A, compare against scenario B")
    parser.add_argument("--scenario-b", help="Replay fixture trace B (requires --scenario-a)")
    parser.add_argument("--out-dir", default="results/trace-compare", help="Output directory")
    parser.add_argument("--basename", default="comparison", help="Output file basename (without extension)")
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    root = repo_root()

    if args.scenario_a or args.scenario_b:
        if not (args.scenario_a and args.scenario_b):
            parser.error("--scenario-a and --scenario-b must be used together")
        baseline_path = out_dir / "baseline.json"
        candidate_path = out_dir / "candidate.json"
        replay_scenario(Path(args.scenario_a), baseline_path, cwd=root)
        replay_scenario(Path(args.scenario_b), candidate_path, cwd=root)
    elif args.baseline and args.candidate:
        baseline_path = Path(args.baseline)
        candidate_path = Path(args.candidate)
    else:
        parser.error("Provide --baseline/--candidate or --scenario-a/--scenario-b")

    summary = compare_documents(
        load_json(baseline_path),
        load_json(candidate_path),
        baseline_path=str(baseline_path),
        candidate_path=str(candidate_path),
    )
    json_path, md_path = write_comparison_report(summary, out_dir, basename=args.basename)

    print(f"Wrote: {json_path}")
    print(f"Wrote: {md_path}")
    print(summary["headline"])


if __name__ == "__main__":
    main()
