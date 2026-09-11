#!/usr/bin/env python3
"""Validate claim registry integrity against scenario and invariant references."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any


def fail(msg: str) -> None:
    print(f"[claim-registry] FAIL: {msg}")
    raise SystemExit(1)


def warn(msg: str) -> None:
    print(f"[claim-registry] WARN: {msg}")


def load_registry_json() -> dict:
    """Read registry claims/mappings/invariants via Clojure to avoid EDN parsing in Python."""
    cmd = [
        "clojure",
        "-M",
        "-e",
        (
            "(require '[clojure.data.json :as json]"
            " '[resolver-sim.definitions.registry :as defs])"
            "(println (json/write-str "
            "{:claims (into {} (map (fn [[k v]] [(name k) (assoc v :claim/id (name (:claim/id v)))]) defs/claims))"
            " :claim-scenario-map (into {} (map (fn [[k v]] [(name k) v]) defs/claim-scenario-map))"
            " :invariants (into #{} (map (comp name key)) defs/invariants)}))"
        ),
    ]
    try:
        proc = subprocess.run(cmd, check=True, capture_output=True, text=True)
    except subprocess.CalledProcessError as exc:
        fail(f"could not load Clojure registry data: {(exc.stderr or '').strip()}")
    except OSError as exc:
        fail(f"failed to execute clojure command: {exc}")
    txt = proc.stdout.strip().splitlines()
    if not txt:
        fail("empty registry payload from Clojure")
    try:
        payload: dict[str, Any] = json.loads(txt[-1])
    except json.JSONDecodeError as exc:
        fail(f"invalid JSON payload from Clojure registry export: {exc}")
    return payload


def normalize_claim_id(raw: str) -> str:
    s = str(raw).strip()
    if s.startswith(":"):
        s = s[1:]
    return s


def normalize_scenario_id(raw: str) -> str:
    """Normalize historical `S29_name` aliases to canonical EDN scenario IDs."""
    return str(raw).strip().lower().replace("_", "-")


def load_edn_scenarios(scenarios_dir: Path) -> list[dict[str, Any]]:
    """Read canonical scenario metadata with Clojure's EDN parser."""
    command = (
        "(require '[clojure.edn :as edn] '[clojure.data.json :as json] '[clojure.java.io :as io]) "
        f"(let [root (io/file {json.dumps(str(scenarios_dir / 'edn'))}) "
        "scenarios (for [path (file-seq root) :when (.endsWith (.getName path) \".edn\") "
        ":let [doc (edn/read-string (slurp path))]] "
        "{:path (.getPath path) :scenario-id (:scenario-id doc) :theory (:theory doc)})] "
        "(println (json/write-str scenarios)))"
    )
    try:
        result = subprocess.run(["clojure", "-M", "-e", command], check=True, capture_output=True, text=True)
    except (subprocess.CalledProcessError, OSError) as exc:
        fail(f"could not load canonical EDN scenarios: {exc}")
    lines = result.stdout.strip().splitlines()
    if not lines:
        fail("empty EDN scenario payload from Clojure")
    payload = json.loads(lines[-1])
    if not isinstance(payload, list):
        fail("EDN scenario payload must be a list")
    return [scenario for scenario in payload if isinstance(scenario, dict)]


def collect_theory_claim_ids(scenarios: list[dict[str, Any]]) -> set[str]:
    found: set[str] = set()
    for scenario in scenarios:
        theory = scenario.get("theory") or {}
        if not isinstance(theory, dict):
            continue
        claim_id = theory.get("claim-id")
        if claim_id:
            found.add(normalize_claim_id(claim_id))
    return found


def load_sew_claims(path: Path) -> list[dict[str, Any]]:
    """Load data/claims/sew-claims.edn via Clojure (EDN list of claim maps)."""
    cmd = [
        "clojure",
        "-M",
        "-e",
        (
            "(require '[clojure.data.json :as json] '[clojure.edn :as edn])"
            f"(println (json/write-str (edn/read-string (slurp {json.dumps(str(path))}))))"
        ),
    ]
    try:
        proc = subprocess.run(cmd, check=True, capture_output=True, text=True)
    except (subprocess.CalledProcessError, OSError) as exc:
        fail(f"could not load sew-claims EDN: {exc}")
    txt = proc.stdout.strip().splitlines()
    if not txt:
        fail("empty sew-claims payload from Clojure")
    payload = json.loads(txt[-1])
    if not isinstance(payload, list):
        fail("sew-claims.edn must be a vector of claim maps")
    return payload


def validate_sew_claims(scenarios_dir: Path, sew_claims_path: Path) -> None:
    if not sew_claims_path.exists():
        fail(f"sew-claims file missing: {sew_claims_path}")
    scenario_files = {p.name for p in scenarios_dir.rglob("*.edn")}
    claims = load_sew_claims(sew_claims_path)
    for claim in claims:
        cid = claim.get("claim/id") or claim.get("claim-id") or claim.get("id")
        if not cid:
            fail(f"sew-claims entry missing :claim/id: {claim}")
        cid_s = normalize_claim_id(str(cid))
        for ref in claim.get("validated-by") or []:
            ref_s = str(ref).strip()
            if ref_s.startswith("scenarios/"):
                fname = Path(ref_s).name
                if fname not in scenario_files:
                    fail(
                        f"sew-claims claim '{cid_s}' references missing scenario file '{fname}' "
                        f"(from '{ref_s}')"
                    )
            elif not Path(ref_s).exists():
                fail(f"sew-claims claim '{cid_s}' references missing evidence path '{ref_s}'")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenarios-dir", default="scenarios")
    ap.add_argument("--sew-claims", default="data/claims/sew-claims.edn")
    ap.add_argument("--strict-theory-claims", action="store_true")
    args = ap.parse_args()

    scenarios_dir = Path(args.scenarios_dir)
    if not scenarios_dir.exists():
        fail(f"scenarios dir missing: {scenarios_dir}")

    payload = load_registry_json()
    claims: dict = payload.get("claims") or {}
    claim_map: dict = payload.get("claim-scenario-map") or {}
    invariants: set[str] = set(payload.get("invariants") or [])

    if not claims:
        fail("registry claims map is empty")

    if set(claims.keys()) != set(claim_map.keys()):
        missing_in_map = sorted(set(claims.keys()) - set(claim_map.keys()))
        extra_in_map = sorted(set(claim_map.keys()) - set(claims.keys()))
        fail(
            "claims and claim-scenario-map keys differ; "
            f"missing_in_map={missing_in_map}, extra_in_map={extra_in_map}"
        )

    scenarios = load_edn_scenarios(scenarios_dir)
    scenario_ids = {
        normalize_scenario_id(scenario_id)
        for scenario in scenarios
        if (scenario_id := scenario.get("scenario-id"))
    }

    for cid, cdef in claims.items():
        claim_id_field = normalize_claim_id(cdef.get("claim/id") or cid)
        if claim_id_field != cid:
            fail(f"claim/id mismatch for {cid}: claim/id={claim_id_field}")

        rel_invs = cdef.get("claim/related-invariants") or []
        for inv in rel_invs:
            inv_name = str(inv).lstrip(":")
            if inv_name not in invariants:
                fail(f"unknown related invariant '{inv}' for claim '{cid}'")

        entry = claim_map.get(cid) or {}
        for bucket in ("supporting", "falsifying"):
            scenarios = entry.get(bucket) or []
            for sid in scenarios:
                canonical_id = normalize_scenario_id(sid)
                if canonical_id not in scenario_ids:
                    fail(f"unknown scenario '{sid}' referenced by claim '{cid}' ({bucket})")

    theory_claim_ids = collect_theory_claim_ids(scenarios)
    registry_ids = set(claims.keys())
    unregistered_theory_claims = sorted(theory_claim_ids - registry_ids)

    if unregistered_theory_claims:
        msg = (
            "theory claim IDs found in scenarios but not in registry: "
            + ", ".join(unregistered_theory_claims)
        )
        if args.strict_theory_claims:
            fail(msg)
        warn(msg)

    validate_sew_claims(scenarios_dir, Path(args.sew_claims))

    print("[claim-registry] PASS: claims/scenarios/invariants integrity checks succeeded")
    return 0


if __name__ == "__main__":
    sys.exit(main())
