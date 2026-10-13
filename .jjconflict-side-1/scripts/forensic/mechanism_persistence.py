#!/usr/bin/env python3
"""Build derived mechanism-persistence artifacts for forensic runs.

This is a PRF-owned semantic layer over the existing evidence DAG. It does not
change canonical evidence hashes; it records how a versioned mechanism map
classified run evidence for researcher-facing analysis.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

_project_root = Path(__file__).resolve().parent.parent.parent
if str(_project_root) not in sys.path:
    sys.path.insert(0, str(_project_root))

from scripts.forensic.preflight import compute_sha256, parse_edn_or_json, write_sealed_json


SCHEMA_VERSION = "mechanism-persistence-index.v1"
SUMMARY_SCHEMA_VERSION = "mechanism-persistence-summary.v1"
MATRIX_SCHEMA_VERSION = "mechanism-scenario-matrix.v1"
VALID_STATUSES = {
    "passed",
    "failed",
    "not-exercised",
    "not-applicable",
    "inconclusive",
    "evidence-missing",
    "invalid-index",
}


def _load_structured(path: Path) -> dict:
    data = parse_edn_or_json(path.read_text(), str(path))
    if not isinstance(data, dict):
        raise ValueError(f"Expected map/object at {path}")
    return data


def _load_json_files(directory: Path) -> list[dict]:
    if not directory.exists():
        return []
    out = []
    for path in sorted(directory.glob("*.json")):
        try:
            out.append(json.loads(path.read_text()))
        except Exception:
            continue
    return out


def _norm(value: Any) -> str:
    if value is None:
        return ""
    text = str(value)
    return text[1:] if text.startswith(":") else text


def _source_ids(source_group: dict) -> set[str]:
    return {_norm(v) for v in source_group.get("ids", [])}


def _nodes_by_execution_id(inventory: dict) -> dict[str, list[dict]]:
    by_id: dict[str, list[dict]] = {}
    for node in inventory.get("dag/nodes", []):
        if node.get("parse-error"):
            continue
        by_id.setdefault(_norm(node.get("execution-id")), []).append(node)
    return by_id


def _claims_by_id(claims: list[dict]) -> dict[str, list[dict]]:
    by_id: dict[str, list[dict]] = {}
    for claim in claims:
        cid = _norm(claim.get("result/claim-id") or claim.get("claim/id"))
        if cid:
            by_id.setdefault(cid, []).append(claim)
    return by_id


def _attestations_by_claim_id(attestations: list[dict]) -> dict[str, list[dict]]:
    by_id: dict[str, list[dict]] = {}
    for attestation in attestations:
        cid = _norm(attestation.get("attestation/claim-id"))
        if cid:
            by_id.setdefault(cid, []).append(attestation)
    return by_id


def _node_hash(node: dict) -> str:
    return _norm(node.get("node-hash"))


def _claim_hash(claim: dict) -> str:
    return _norm(claim.get("result/hash") or claim.get("claim/hash"))


def _attestation_hash(attestation: dict) -> str:
    return _norm(attestation.get("attestation/hash") or attestation.get("attestation/id"))


def _parent_edges(inventory: dict) -> dict[str, list[str]]:
    parents: dict[str, list[str]] = {}
    for node in inventory.get("dag/nodes", []):
        child = _node_hash(node)
        if child:
            parents[child] = [_norm(p) for p in node.get("parent-hashes", []) if p]
    return parents


def _ancestor_path(node_hashes: list[str], parents_by_child: dict[str, list[str]]) -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []

    def visit(h: str) -> None:
        if not h or h in seen:
            return
        for parent in parents_by_child.get(h, []):
            visit(parent)
        seen.add(h)
        ordered.append(h)

    for node_hash in node_hashes:
        visit(node_hash)
    return ordered


def _scenario_applicability(mechanism_map: dict) -> list[dict]:
    return mechanism_map.get("scenario-applicability", [])


def _scenario_ids(mechanism_map: dict, benchmark: dict | None) -> list[str]:
    ids = []
    if benchmark:
        for scenario in benchmark.get("benchmark/scenarios", []):
            sid = _norm(scenario.get("scenario/id"))
            if sid:
                ids.append(sid)
    for row in _scenario_applicability(mechanism_map):
        sid = _norm(row.get("scenario/id"))
        if sid and sid not in ids:
            ids.append(sid)
    return ids


def _applicability_for(mechanism_map: dict, scenario_id: str, mechanism_id: str) -> str:
    for row in _scenario_applicability(mechanism_map):
        if _norm(row.get("scenario/id")) == scenario_id:
            mechanisms = row.get("mechanisms", {})
            value = mechanisms.get(mechanism_id) or mechanisms.get(f":{mechanism_id}")
            return _norm(value) if value else "not-applicable"
    return "not-applicable"


def _derive_source_matches(source_group: dict,
                           nodes_by_execution: dict[str, list[dict]],
                           claims_by_id: dict[str, list[dict]],
                           attestations_by_claim: dict[str, list[dict]],
                           mechanism_map_id: str) -> tuple[list[dict], dict[str, list[str]], list[dict]]:
    source = _norm(source_group.get("source"))
    ids = _source_ids(source_group)
    derivation: list[dict] = []
    evidence = {
        "node-hashes": [],
        "claim-result-hashes": [],
        "attestation-hashes": [],
        "invariant-result-hashes": [],
    }
    failures: list[dict] = []

    if source == "execution/id":
        for sid in ids:
            for node in nodes_by_execution.get(sid, []):
                h = _node_hash(node)
                evidence["node-hashes"].append(h)
                derivation.append({"source": source, "id": sid,
                                   "matched-node": h,
                                   "matched-by": mechanism_map_id})
                if _norm(node.get("result-status")) in {"fail", "error"}:
                    failures.append({"failure/type": "execution-failed",
                                     "failed-source": sid,
                                     "node-hash": h})
    elif source == "claim/id":
        for sid in ids:
            for claim in claims_by_id.get(sid, []):
                h = _claim_hash(claim)
                evidence["claim-result-hashes"].append(h)
                derivation.append({"source": source, "id": sid,
                                   "matched-result": h,
                                   "matched-by": mechanism_map_id})
                if _norm(claim.get("result/status")) == "fail":
                    failures.append({"failure/type": "claim-failed",
                                     "failed-claim": sid,
                                     "claim-result-hash": h})
    elif source == "attestation/claim-id":
        for sid in ids:
            for attestation in attestations_by_claim.get(sid, []):
                h = _attestation_hash(attestation)
                evidence["attestation-hashes"].append(h)
                derivation.append({"source": source, "id": sid,
                                   "matched-attestation": h,
                                   "matched-by": mechanism_map_id})
                if _norm(attestation.get("attestation/claim-result")) in {"rejected", "fail"}:
                    failures.append({"failure/type": "attestation-failed",
                                     "failed-attestation": h})
    elif source == "invariant/id":
        # V1 records invariant requirements, but most current forensic bundles
        # do not yet expose standalone invariant-result hashes.
        pass
    return derivation, evidence, failures


def _merge_evidence(parts: list[dict[str, list[str]]]) -> dict[str, list[str]]:
    keys = ["node-hashes", "event-hashes", "claim-result-hashes",
            "attestation-hashes", "invariant-result-hashes"]
    merged: dict[str, list[str]] = {k: [] for k in keys}
    for part in parts:
        for key in keys:
            for value in part.get(key, []):
                if value and value not in merged[key]:
                    merged[key].append(value)
    return merged


def _episode_status(applicability: str,
                    required_sources: list[dict],
                    derivation: list[dict],
                    failures: list[dict]) -> str:
    if applicability == "not-applicable":
        return "not-applicable"
    if applicability == "optional" and not derivation:
        return "not-exercised"
    if failures:
        return "failed"

    matched_groups = {(d.get("source"), d.get("id")) for d in derivation}
    for group in required_sources:
        source = _norm(group.get("source"))
        ids = _source_ids(group)
        if not ids:
            return "invalid-index"
        if not all((source, sid) in matched_groups for sid in ids):
            return "evidence-missing"
    return "passed" if derivation else "not-exercised"


def build_mechanism_artifacts(run_dir: Path,
                              mechanism_map_path: Path,
                              benchmark_path: Path | None = None) -> tuple[dict, dict, dict]:
    mechanism_map = _load_structured(mechanism_map_path)
    benchmark = _load_structured(benchmark_path) if benchmark_path else None
    inventory = _load_structured(run_dir / "evidence-dag-inventory.json")
    claims = _load_json_files(run_dir / "claims")
    attestations = _load_json_files(run_dir / "attestations")

    mechanism_map_bytes = mechanism_map_path.read_bytes()
    mechanism_map_hash = compute_sha256(mechanism_map_bytes)
    mechanism_map_id = _norm(mechanism_map.get("mechanism-map/id"))
    mechanism_map_version = mechanism_map.get("mechanism-map/version")
    nodes_by_execution = _nodes_by_execution_id(inventory)
    claims_by = _claims_by_id(claims)
    attestations_by = _attestations_by_claim_id(attestations)
    parents_by_child = _parent_edges(inventory)
    scenarios = _scenario_ids(mechanism_map, benchmark)

    episodes = []
    for scenario_id in scenarios:
        for mechanism in mechanism_map.get("mechanisms", []):
            mechanism_id = _norm(mechanism.get("mechanism/id"))
            applicability = _applicability_for(mechanism_map, scenario_id, mechanism_id)
            required_sources = mechanism.get("required-sources", [])
            derivation_parts = []
            evidence_parts = []
            failures = []
            for group in required_sources:
                derivation, evidence, group_failures = _derive_source_matches(
                    group, nodes_by_execution, claims_by, attestations_by, mechanism_map_id)
                derivation_parts.extend(derivation)
                evidence_parts.append(evidence)
                failures.extend(group_failures)

            evidence = _merge_evidence(evidence_parts)
            status = _episode_status(applicability, required_sources,
                                     derivation_parts, failures)
            node_path = _ancestor_path(evidence["node-hashes"], parents_by_child)
            failure = None
            if failures:
                first = failures[0]
                failure = {
                    "failure/type": first.get("failure/type"),
                    "failure/point": {"node-hash": first.get("node-hash")},
                    "failed-claim": first.get("failed-claim"),
                    "counterexample-path": node_path,
                }

            episodes.append({
                "episode/id": f"{scenario_id}:{mechanism_id}",
                "mechanism/id": mechanism_id,
                "scenario/id": scenario_id,
                "benchmark/id": _norm(benchmark.get("benchmark/id")) if benchmark else None,
                "applicability": applicability,
                "episode/window": None,
                "episode/path": node_path,
                "status": status,
                "evidence": evidence,
                "derivation": derivation_parts,
                "failure": failure,
            })

    index = {
        "schema-version": SCHEMA_VERSION,
        "mechanism-map/version": mechanism_map_version,
        "mechanism-map/hash": mechanism_map_hash,
        "mechanisms": mechanism_map.get("mechanisms", []),
        "episodes": episodes,
    }
    status_counts = {status: 0 for status in sorted(VALID_STATUSES)}
    for episode in episodes:
        status_counts[episode["status"]] = status_counts.get(episode["status"], 0) + 1
    summary = {
        "schema-version": SUMMARY_SCHEMA_VERSION,
        "mechanism-map/version": mechanism_map_version,
        "mechanism-map/hash": mechanism_map_hash,
        "episode-count": len(episodes),
        "status-counts": status_counts,
        "mechanism-count": len(mechanism_map.get("mechanisms", [])),
        "scenario-count": len(scenarios),
    }
    matrix = {
        "schema-version": MATRIX_SCHEMA_VERSION,
        "mechanism-map/version": mechanism_map_version,
        "mechanism-map/hash": mechanism_map_hash,
        "scenarios": scenarios,
        "mechanisms": [_norm(m.get("mechanism/id")) for m in mechanism_map.get("mechanisms", [])],
        "cells": {
            f"{episode['scenario/id']}|{episode['mechanism/id']}": {
                "status": episode["status"],
                "applicability": episode["applicability"],
            }
            for episode in episodes
        },
    }
    return index, summary, matrix


def write_mechanism_artifacts(run_dir: Path,
                              mechanism_map_path: Path,
                              benchmark_path: Path | None = None) -> dict[str, str]:
    index, summary, matrix = build_mechanism_artifacts(
        run_dir, mechanism_map_path, benchmark_path)
    index_hash, _ = write_sealed_json(run_dir / "mechanism-persistence-index.json", index)
    summary_hash, _ = write_sealed_json(run_dir / "mechanism-persistence-summary.json", summary)
    matrix_hash, _ = write_sealed_json(run_dir / "mechanism-scenario-matrix.json", matrix)
    return {
        "mechanism-persistence-index": index_hash,
        "mechanism-persistence-summary": summary_hash,
        "mechanism-scenario-matrix": matrix_hash,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir")
    parser.add_argument("--mechanism-map", required=True)
    parser.add_argument("--benchmark")
    args = parser.parse_args()
    hashes = write_mechanism_artifacts(
        Path(args.run_dir),
        Path(args.mechanism_map),
        Path(args.benchmark) if args.benchmark else None,
    )
    print(json.dumps(hashes, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
