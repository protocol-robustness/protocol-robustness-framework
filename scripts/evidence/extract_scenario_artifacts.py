#!/usr/bin/env python3
"""
Extract scenario-scoped artifacts from a replay output JSON into a
browsable, per-run artifact bundle.

Usage:
  python3 scripts/extract_scenario_artifacts.py \
    --replay /tmp/s19-result.json \
    --run-dir results/runs/s19-.../

Outputs:
  summaries/trace-summary.json
  summaries/metrics.json
  summaries/claimable-classification.json
  summaries/mechanism-summary.json
  state/world-final.json
  raw/replay-output.json

All artifacts include a 'derived_from' reference to raw/replay-output.json.
"""

from __future__ import annotations

SCHEMA_MAP = {
    "mechanism-summary.v1": {
        "description": "High-level summary of simulation mechanism outcomes (escrow, disputes, slashing).",
        "fields": {
            "scenario_id": "Unique identifier of the scenario.",
            "outcome": "Final simulation outcome (pass/fail).",
            "escrow": "Aggregated stats on escrow lifecycles.",
            "dispute": "Aggregated stats on dispute resolutions.",
            "slashing": "Aggregated stats on slashing actions.",
            "claimable": "Summary of claimable fund classifications.",
            "temporal": "Temporal statistics (steps, consistency)."
        }
    },
    "scenario-metrics.v1": {
        "description": "Raw numeric metrics collected during simulation.",
        "fields": {
            "scenario_id": "Unique identifier of the scenario.",
            "metrics": "Detailed numeric metrics."
        }
    }
}

ACTION_DESCRIPTIONS = {
    "create_escrow": "Created a new escrow",
    "release_escrow": "Released funds from escrow",
    "raise_dispute": "Raised a dispute",
    "execute_resolution": "Executed a dispute resolution",
    "escalate_dispute": "Escalated a dispute",
    "propose_fraud_slash": "Proposed a fraud slash",
    "execute_fraud_slash": "Executed a fraud slash",
}

import argparse
import hashlib
import json
import os
import pathlib
import sys
import copy

from schema_validator import SchemaValidator


# ── Bundle root normalization ─────────────────────────────────────────────────


def is_bundle_root(data: dict) -> bool:
    """Detect whether the data is a bundle-root.v1 (vs raw replay format)."""
    return data.get("bundle/schema-version") == "bundle-root.v1"


def normalize_replay(data: dict) -> dict:
    """Normalize the replay data into a raw-replay-like shape for extraction.

    If the data is a bundle-root.v1, extract scenario-level fields from
    the overview and raw result payload.  Otherwise return the data as-is.
    """
    if not is_bundle_root(data):
        return data

    results = data.get("overview", {}).get("results", [])
    raw_results = data.get("run/scenario-results", [])

    if not results:
        return data

    # Build a normalized top-level view from the first scenario result
    first_result = results[0] if results else {}
    first_raw = raw_results[0] if raw_results else {}

    normalized = copy.deepcopy(data)
    normalized["scenario-id"] = first_result.get("scenario-id")
    normalized["outcome"] = first_result.get("outcome", "unknown")
    normalized["pass?"] = first_result.get("pass?", False)
    normalized["events-processed"] = len(first_raw.get("trace", [])) if first_raw else 0
    normalized["source"] = {"scenario-id": first_result.get("scenario-id")}

    # Inject world, trace, metrics from raw scenario results (if available)
    normalized["world"] = first_raw.get("world", {})
    normalized["trace"] = first_raw.get("trace", [])
    normalized["metrics"] = first_raw.get("metrics", {})

    return normalized


# ── Helpers ──────────────────────────────────────────────────────────────────


def sha256_file(path: pathlib.Path) -> str | None:
    if not path.exists():
        return None
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def write_json(path: pathlib.Path, data: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, default=str)


def derived_from(replay_path: pathlib.Path) -> dict:
    return {"derived_from": {"path": str(replay_path), "sha256": sha256_file(replay_path)}}


def safe_int(v, default=0):
    if v is None:
        return default
    if isinstance(v, int):
        return v
    try:
        return int(v)
    except (ValueError, TypeError):
        return default


# ── Extractors ───────────────────────────────────────────────────────────────


def extract_trace_summary(replay: dict, replay_path: pathlib.Path) -> dict:
    trace = replay.get("trace", [])
    steps = []
    for i, ev in enumerate(trace):
        seq = safe_int(ev.get("seq"), i)
        steps.append({
            "seq": seq,
            "time": ev.get("time", ev.get("block-time", None)),
            "actor": ev.get("caller", ev.get("actor", ev.get("agent", None))),
            "action": ev.get("action", ev.get("event-type", "?")),
            "result": ev.get("result", ev.get("outcome", "?")),
            "evidence_refs": [f"evidence/events/{seq:03d}-{ev.get('action', 'event')}.json"],
        })
    return {
        "schema_version": "trace-summary.v1",
        "scenario_id": replay.get("scenario-id"),
        "scenario_title": replay.get("source", {}).get("description",
                         replay.get("source", {}).get("scenario-id", "unknown")),
        "outcome": replay.get("outcome", "unknown"),
        "events_processed": replay.get("events-processed", len(trace)),
        "steps": steps,
        **derived_from(replay_path),
    }


def _find_available_ratio(world: dict) -> float | None:
    """Extract available-ratio from yield risk state if not in top-level metrics."""
    risk = world.get("yield/risk", {})
    for module_data in risk.values():
        if isinstance(module_data, dict):
            for token_data in module_data.values():
                if isinstance(token_data, dict):
                    shortfall = token_data.get("shortfall")
                    if isinstance(shortfall, dict):
                        ratio = shortfall.get("available-ratio")
                        if ratio is not None:
                            return ratio
    return None


def extract_metrics(replay: dict, replay_path: pathlib.Path) -> dict:
    metrics = replay.get("metrics", {})
    return {
        "schema_version": "scenario-metrics.v1",
        "scenario_id": replay.get("source", {}).get("scenario-id"),
        "outcome": replay.get("outcome", "unknown"),
        "events_processed": replay.get("events-processed", 0),
        "metrics": {
            "escrow_unrealized": metrics.get("escrow-unrealized", 0),
            "escrow_realized": metrics.get("escrow-realized", 0),
            "invariant_violations": metrics.get("invariant-violations", 0),
            "attack_attempts": metrics.get("attack-attempts", 0),
            "claimable": metrics.get("claimable", 0),
            "batch_conflicts": metrics.get("batch-conflicts", 0),
            "available_ratio": metrics.get("available-ratio",
                              _find_available_ratio(replay.get("world", {}))),
        },
        **derived_from(replay_path),
    }


def _workflow_token_map(world: dict) -> dict:
    """Infer settlement token by workflow from custody records.

    Generic Sew claimables are keyed by workflow and recipient, while token is
    recorded in the corresponding held-custody adjustment or claimable-v2
    settlement domain. This joins those normalized state projections without
    guessing a token from an amount.
    """
    tokens = {}
    for adjustment in world.get("held-adjustments", []):
        if not isinstance(adjustment, dict):
            continue
        workflow_id = adjustment.get("held/workflow-id")
        token = adjustment.get("token")
        if workflow_id is not None and token is not None:
            tokens[str(workflow_id)] = token
    return tokens


def _extract_claimable_from_world(world: dict) -> list:
    """Discover claimable entries and their available token amounts."""
    entries = []
    workflow_tokens = _workflow_token_map(world)
    claimable_v2 = world.get("claimable-v2", {})

    # Generic Sew claimables: workflow -> recipient -> amount.
    claimable = world.get("claimable", {})
    if isinstance(claimable, dict):
        for workflow_id, recipients in claimable.items():
            if not isinstance(recipients, dict):
                continue
            token = workflow_tokens.get(str(workflow_id))
            v2_domains = claimable_v2.get(str(workflow_id), {}) if isinstance(claimable_v2, dict) else {}
            entries.append({
                "source": "claimable",
                "key": workflow_id,
                "type": "settlement-claimable",
                "token": token,
                "available_to_claim": recipients,
                "available_total": sum(v for v in recipients.values() if isinstance(v, (int, float))),
                "claimable_v2": v2_domains if isinstance(v2_domains, dict) else {},
            })

    # Yield protocol: partial-fill decisions with deferred amounts
    pfd = world.get("yield/partial-fill-decisions", {})
    if isinstance(pfd, dict):
        for dec_id, dec in pfd.items():
            deferred = dec.get("deferred", {})
            if isinstance(deferred, dict) and any(v not in (None, 0) for v in deferred.values()):
                entries.append({
                    "source": "yield/partial-fill-decisions",
                    "key": dec_id,
                    "type": "deferred-fill",
                    "position": dec.get("position/id"),
                    "deferred": deferred,
                    "filled": dec.get("filled"),
                })

    # Yield protocol: positions with deferred-yield
    positions = world.get("yield/positions", {})
    if isinstance(positions, dict):
        for pos_id, pos in positions.items():
            if pos.get("deferred-yield", 0) > 0 or pos.get("status") == "unwinding":
                shortfall = pos.get("shortfall")
                entries.append({
                    "source": "yield/positions",
                    "key": pos_id,
                    "type": "shortfall",
                    "deferred-amount": shortfall.get("deferred-amount") if isinstance(shortfall, dict) else None,
                    "fulfilled-amount": shortfall.get("fulfilled-amount") if isinstance(shortfall, dict) else None,
                    "deferred-yield": pos.get("deferred-yield", 0),
                })

    return entries


def extract_claimable_classification(replay: dict, replay_path: pathlib.Path) -> dict:
    world = replay.get("world", {})
    entries = _extract_claimable_from_world(world)
    available_by_token = {}
    for entry in entries:
        token = entry.get("token")
        amount = entry.get("available_total")
        if token is not None and isinstance(amount, (int, float)):
            available_by_token[token] = available_by_token.get(token, 0) + amount

    # Fees are not claimable, but reporting them beside claimable balances makes
    # the settlement disposition auditable: gross = claimable + fees.
    fees_by_token = world.get("total-fees", {})
    if not isinstance(fees_by_token, dict):
        fees_by_token = {}
    settlement_accounting_by_token = {}
    for token in set(available_by_token) | set(fees_by_token):
        claimable_amount = available_by_token.get(token, 0)
        fee_amount = fees_by_token.get(token, 0)
        if not isinstance(fee_amount, (int, float)):
            fee_amount = 0
        settlement_accounting_by_token[token] = {
            "claimable": claimable_amount,
            "fees": fee_amount,
            "gross_settlement": claimable_amount + fee_amount,
        }

    result = {
        "schema_version": "claimable-classification.v2",
        "scenario_id": replay.get("source", {}).get("scenario-id"),
        "claimable_entries": len(entries),
        "available_to_claim_by_token": available_by_token,
        "settlement_accounting_by_token": settlement_accounting_by_token,
        "escrow_count": len([
            k for k in world if k.startswith("escrow") and isinstance(world[k], dict)
        ]) if isinstance(world, dict) else 0,
        "entries": entries,
        **derived_from(replay_path),
    }
    # Try run_id from test-run.json (v1 manifest) or from bundle root
    test_run_path = replay_path.parent / "test-run.json"
    run_id = None
    if test_run_path.exists():
        try:
            with test_run_path.open("r") as f:
                run_id = json.load(f).get("run_id")
        except (json.JSONDecodeError, IOError):
            pass
    if not run_id:
        run_id = replay.get("run/request", {}).get("run-id")
    if not run_id:
        run_id = replay.get("overview", {}).get("suite", {}).get("suite/key")
    if run_id:
        result["run_id"] = run_id
    return result


def extract_mechanism_summary(replay: dict, replay_path: pathlib.Path) -> dict:
    trace = replay.get("trace", [])
    metrics = replay.get("metrics", {})
    temporal_ok = all(ev.get("time") is not None or ev.get("block-time") is not None for ev in trace)
    return {
        "schema_version": "mechanism-summary.v1",
        "scenario_id": replay.get("source", {}).get("scenario-id"),
        "outcome": replay.get("outcome", "unknown"),
        "escrow": {
            "created": sum(1 for ev in trace if ev.get("action") == "create_escrow"),
            "released": sum(1 for ev in trace if ev.get("action") == "release_escrow"),
            "disputed": sum(1 for ev in trace if ev.get("action") == "raise_dispute"),
            "finalized": sum(1 for ev in trace if ev.get("action") in ("release_escrow", "release_claimable", "finalize_claimable")),
        },
        "dispute": {
            "raised": sum(1 for ev in trace if ev.get("action") == "raise_dispute"),
            "resolved": sum(1 for ev in trace if ev.get("action") == "execute_resolution"),
            "appealed": sum(1 for ev in trace if ev.get("action") == "escalate_dispute"),
            "outcome": replay.get("outcome"),
        },
        "slashing": {
            "attempts": sum(1 for ev in trace if ev.get("action") == "propose_fraud_slash"),
            "executed": sum(1 for ev in trace if ev.get("action") == "execute_fraud_slash"),
            "unmet_obligations": metrics.get("unrealized", 0),
        },
        "claimable": {
            "total_claimable": metrics.get("claimable", 0),
            "stale_claimables": 0,
        },
        "temporal": {
            "steps": len(trace),
            "clock_mode": "discrete-step",
            "consistent": temporal_ok,
        },
        **derived_from(replay_path),
    }


def extract_trace_plain(replay: dict, replay_path: pathlib.Path) -> str:
    trace = replay.get("trace", [])
    lines = ["# Plain Language Trace Summary\n"]
    for i, ev in enumerate(trace):
        action = ev.get("action", "unknown")
        outcome = ev.get("outcome", "success")
        desc = ACTION_DESCRIPTIONS.get(action, f"Performed unknown action: {action}")
        if outcome != "success":
            desc = f"{desc} (Status: {outcome})"
        lines.append(f"{i+1}. **{action}**: {desc}")
    return "\n".join(lines)


def extract_final_world(replay: dict, replay_path: pathlib.Path) -> dict:
    world = replay.get("world", {})
    return {
        "schema_version": "world-final.v1",
        "scenario_id": replay.get("source", {}).get("scenario-id"),
        "outcome": replay.get("outcome", "unknown"),
        "events_processed": replay.get("events-processed", 0),
        "world": world,
        **derived_from(replay_path),
    }


def extract_schema_map(replay: dict, replay_path: pathlib.Path) -> dict:
    return {
        "schema_version": "schema-map.v1",
        "map": SCHEMA_MAP,
        **derived_from(replay_path),
    }


# ── Main ─────────────────────────────────────────────────────────────────────


def _now_utc() -> str:
    """Return ISO-8601 UTC timestamp."""
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).isoformat()


def _sha256_path(path: pathlib.Path) -> str | None:
    return sha256_file(path)


def _artifact_entry(artifact_id: str, kind: str, importance: str,
                    schema_version: str, path: pathlib.Path) -> dict:
    """Build an artifact registry entry for one generated file."""
    return {
        "id": artifact_id,
        "kind": kind,
        "path": str(path),
        "importance": importance,
        "schema_version": schema_version,
        "contract_version": "evidence-contract.v1",
        "producer": "extract-scenario-artifacts.v1",
        "verifies_against": [],
        "dependencies": [],
        "sha256": _sha256_path(path),
        "bytes": path.stat().st_size if path.exists() else 0,
        "mtime_utc": _now_utc(),
    }


def update_artifact_registry(run_dir: pathlib.Path, new_entries: list[dict],
                             run_root: pathlib.Path | None = None):
    """Update the legacy registry or the structured run-root registry.

    Structured registry paths are relative to the complete run root, never to
    its manifest directory. The legacy behavior remains unchanged.
    """
    registry_path = ((run_root / "manifest" / "artifacts.json")
                     if run_root else run_dir / "test-artifacts.json")
    if not registry_path.exists():
        return
    with registry_path.open("r", encoding="utf-8") as f:
        registry = json.load(f)

    # Structural validation before modification
    struct_errors = SchemaValidator().validate(registry)
    if struct_errors:
        print(f"Error: Existing registry is structurally invalid ({len(struct_errors)} error(s))")
        for e in struct_errors:
            print(f"  {e.path}: {e.message}")
        return
    existing_ids = {a["id"] for a in registry.get("artifacts", [])}
    added = 0
    for entry in new_entries:
        if entry["id"] not in existing_ids:
            registry.setdefault("artifacts", []).append(entry)
            existing_ids.add(entry["id"])
            added += 1
    if added:
        registry["generated_at"] = _now_utc()
        with registry_path.open("w", encoding="utf-8") as f:
            json.dump(registry, f, indent=2)
        print(f"  [registry] Added {added} new artifact(s) to {registry_path}")


def main():
    parser = argparse.ArgumentParser(
        description="Extract scenario-scoped artifacts from replay output JSON")
    parser.add_argument("--replay", required=True,
                        help="Path to replay output JSON (--output-file)")
    parser.add_argument("--run-dir", required=True,
                        help="Run-specific directory (e.g. results/runs/s19-.../)")
    parser.add_argument("--run-root",
                        help="Complete structured bundle root. Enables manifest/artifacts.json updates.")
    args = parser.parse_args()

    replay_path = pathlib.Path(args.replay)
    run_dir = pathlib.Path(args.run_dir)
    run_root = pathlib.Path(args.run_root) if args.run_root else None

    if not replay_path.exists():
        print(f"Error: replay file not found: {replay_path}", file=sys.stderr)
        sys.exit(1)

    with replay_path.open("r", encoding="utf-8") as f:
        replay_raw = json.load(f)

    # Normalize: handle both bundle-root.v1 and raw replay formats
    replay = normalize_replay(replay_raw)

    # The execution replay is canonical in structured mode. Legacy bundles keep
    # a raw copy for compatibility; it is not an independent semantic artifact.
    if not run_root:
        write_json(run_dir / "raw" / "replay-output.json", replay_raw)
        print(f"  raw/replay-output.json ({os.path.getsize(run_dir / 'raw' / 'replay-output.json')} bytes)")

    # 2. summaries/trace-summary.json
    trace_summ = extract_trace_summary(replay, replay_path)
    write_json(run_dir / "summaries" / "trace-summary.json", trace_summ)
    print(f"  summaries/trace-summary.json ({len(trace_summ['steps'])} events)")

    # 3. summaries/metrics.json
    metrics = extract_metrics(replay, replay_path)
    write_json(run_dir / "summaries" / "metrics.json", metrics)
    print(f"  summaries/metrics.json ({len(metrics['metrics'])} metrics)")

    # 4. summaries/claimable-classification.json
    claimable = extract_claimable_classification(replay, replay_path)
    write_json(run_dir / "summaries" / "claimable-classification.json", claimable)
    print(f"  summaries/claimable-classification.json")

    # 5. summaries/mechanism-summary.json
    mech_summ = extract_mechanism_summary(replay, replay_path)
    write_json(run_dir / "summaries" / "mechanism-summary.json", mech_summ)
    print(f"  summaries/mechanism-summary.json")

    # 6. state/world-final.json
    final_world = extract_final_world(replay, replay_path)
    write_json(run_dir / "state" / "world-final.json", final_world)
    print(f"  state/world-final.json ({len(json.dumps(final_world.get('world', {})))} bytes)")

    # 7. summaries/schema-map.json
    schema_map = extract_schema_map(replay, replay_path)
    write_json(run_dir / "summaries" / "schema-map.json", schema_map)
    print(f"  summaries/schema-map.json")

    # 8. summaries/trace-plain.md
    trace_plain = extract_trace_plain(replay, replay_path)
    with (run_dir / "summaries" / "trace-plain.md").open("w") as f:
        f.write(trace_plain)
    print(f"  summaries/trace-plain.md")

    # Structured bundles use manifest/run.json and manifest/summary.json as
    # authoritative documents; do not create legacy scenario-local views.
    test_run_path = run_dir / "test-run.json"
    test_summary_path = run_dir / "test-summary.json"
    if not run_root:
        run_id = run_dir.name.split("-", 1)[-1] if "-" in run_dir.name else "unknown"
        if not test_run_path.exists():
            write_json(test_run_path, {"schema_version": "test-run.v1", "run_id": run_id,
                                       "framework": {"name": "protocol-robustness-framework-test-runner", "version": "0.1.0", "git_commit": None, "git_message": None}})
        if not test_summary_path.exists():
            write_json(test_summary_path, {"schema_version": "test-summary.v2", "run_id": run_id,
                                           "overall_status": "pass" if replay.get("pass?") else "fail", "risk_digest": {}})

    print(f"\nArtifacts written to {run_dir}")

    # 10. Register generated artifacts. Structured paths are relative to run root.
    new_entries = []
    if not run_root:
        new_entries.extend([
        _artifact_entry("test-run", "run-manifest", "CORE", "test-run.v1", test_run_path),
        _artifact_entry("test-summary", "summary", "CORE", "test-summary.v2", test_summary_path),
        _artifact_entry("raw.replay-output", "raw.replay", "DIAGNOSTIC",
                        "raw-replay.v1", run_dir / "raw" / "replay-output.json")])
    else:
        canonical = [
            ("manifest.run", "run-manifest", "run-manifest.v1", run_root / "manifest" / "run.json"),
            ("manifest.summary", "summary", "summary.v1", run_root / "manifest" / "summary.json"),
            ("manifest.claimable-classification", "summary", "claimable-classification.v2", run_root / "manifest" / "claimable-classification.json"),
            ("manifest.run-enrichment", "run-enrichment", "run-enrichment.v1", run_root / "manifest" / "run-enrichment.json"),
            ("manifest.artifact-registry-validation", "validation", "validation-root.v1", run_root / "manifest" / "artifact-registry-validation.json"),
            ("execution.replay-output", "raw.replay", "bundle-root.v1", replay_path),
            ("execution.dag", "execution.dag", "execution-dag.v1", run_dir / "execution" / "execution-dag.json"),
            ("execution.pre-run-commitment", "pre-run-commitment", "pre-run-commitment.v1", run_dir / "execution" / "pre-run-commitment.json"),
        ]
        for artifact_id, kind, schema_version, path in canonical:
            if path.exists():
                entry = _artifact_entry(artifact_id, kind, "CORE", schema_version, path)
                entry["path"] = str(path.relative_to(run_root))
                new_entries.append(entry)
    new_entries.extend([
        _artifact_entry("summaries.trace", "summary.trace", "CORE",
                        "trace-summary.v1", run_dir / "summaries" / "trace-summary.json"),
        _artifact_entry("summaries.trace-plain", "summary.trace-plain", "DIAGNOSTIC",
                        "trace-plain.v1", run_dir / "summaries" / "trace-plain.md"),
        _artifact_entry("summaries.metrics", "summary.metrics", "CORE",
                        "scenario-metrics.v1", run_dir / "summaries" / "metrics.json"),
        _artifact_entry("summaries.claimable", "summary.claimable", "CORE",
                        "claimable-classification.v2", run_dir / "summaries" / "claimable-classification.json"),
                                _artifact_entry("summaries.mechanisms", "summary.mechanisms", "CORE",
                        "mechanism-summary.v1", run_dir / "summaries" / "mechanism-summary.json"),
        _artifact_entry("summaries.schema-map", "summary.schema-map", "DIAGNOSTIC",
                        "schema-map.v1", run_dir / "summaries" / "schema-map.json"),
        _artifact_entry("state.world-final", "state.final", "CORE",
                        "world-final.v1", run_dir / "state" / "world-final.json"),
    ])
    if run_root:
        # The existing forensic producers do not return a file inventory. Scan
        # only their supplied scenario directory, never a shared/global root.
        forensic_dir = run_dir / "forensic"
        if forensic_dir.exists():
            for path in sorted(p for p in forensic_dir.rglob("*") if p.is_file()):
                entry = _artifact_entry(f"forensic.{path.relative_to(forensic_dir).as_posix().replace('/', '.')}",
                                        "forensic.evidence", "DIAGNOSTIC", "unknown", path)
                entry["path"] = str(path.relative_to(run_root))
                new_entries.append(entry)
        resolved_root = run_root.resolve()
        for entry in new_entries:
            raw_path = pathlib.Path(entry["path"])
            path = ((run_root / raw_path).resolve()
                    if not raw_path.is_absolute() and (run_root / raw_path).exists()
                    else raw_path.resolve())
            entry["path"] = path.relative_to(resolved_root).as_posix()
        if any(".." in pathlib.PurePosixPath(entry["path"]).parts for entry in new_entries):
            raise ValueError("structured registry paths must not contain '..'")
    update_artifact_registry(run_dir, new_entries, run_root)

    return 0


if __name__ == "__main__":
    sys.exit(main())
