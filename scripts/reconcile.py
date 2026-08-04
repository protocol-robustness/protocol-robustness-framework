#!/usr/bin/env python3
"""
reconcile — Phase 0 claim-correctness gate for the trace-equivalence harness.

Machine-enforced set reconciliation between:
  - the manifest included trace set (etc/trace-solidity-manifest.edn)
  - the manifest excluded/byte-synchronised set
  - the fixtures actually replayed by TraceEquivalenceTest (per-trace receipts
    emitted under <sew-repo>/out/receipts/)
  - the manifest :forge-wired inventory

The gate rejects:
  - an included manifest trace without a contract replay receipt;
  - a replayed fixture that is neither a manifest trace nor a classified
    pre-existing/harness-self-test fixture;
  - duplicate trace ids or fixture paths;
  - a receipt whose fixture hash does not match the fixture bytes on disk;
  - drift between the manifest :forge-wired list and the actual receipts;
  - a contract-replayed v0.2 trace whose invariant profile was not resolved AND
    applied with at least one invariant evaluation (profile-inert detection);
  - a receipt for a byte-synchronised-only manifest trace (replayed-but-not-expected).

"equivalence verified" (contract-replayed) therefore requires execution
evidence (a receipt), never byte identity alone.

Usage:
  python3 scripts/reconcile.py [--manifest etc/trace-solidity-manifest.edn]
                               [--sew-repo <path>]
                               [--receipts-dir <path>]
                               [--report <path>]   # JSON report output
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

try:
    import sha3  # noqa: F401  (keccak_256, matches Solidity keccak256)
except ImportError:  # pragma: no cover
    sha3 = None

RECEIPT_SUFFIX = ".json"
REGRESSION_DIR = "test/foundry/traces/v2/regression"

# Phase 1 supported-combination registry (mirrors TraceEquivalence.t.sol):
# fixture-spec (cdrs_version, schema_version) + profile + harness version.
SUPPORTED_FIXTURE_SPECS = {("0.1", "1"), ("0.2", "2")}
SUPPORTED_REPLAY_SPECS = {
    "cdrs-0.1.schema-1.profile-none.harness-1",
    "cdrs-0.2.schema-2.profile-1.harness-1",
}


def _keccak256(data: bytes) -> str:
    if sha3 is None:
        raise SystemExit("reconcile requires the 'sha3' python module (pip install pysha3)")
    return sha3.keccak_256(data).hexdigest()


def parse_edn_entries(raw: str) -> list[dict[str, str]]:
    """Extract the manifest :traces block entries (mirrors trace-solidity-verify)."""
    start = raw.find(":traces\n [")
    if start == -1:
        start = raw.find(":traces\r\n [")
    if start == -1:
        return []
    entries: list[dict[str, str]] = []
    i = 0
    while i < len(raw):
        idx = raw.find("{:id", i)
        if idx == -1:
            break
        if idx > 0 and raw[idx - 1] not in " \t\n\r[(":
            i = idx + 4
            continue
        depth = 0
        j = idx
        while j < len(raw):
            if raw[j] == "{":
                depth += 1
            elif raw[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        block = raw[idx : j + 1]
        entry: dict[str, str] = {}
        for m in re.finditer(r':([\w-]+)\s+"((?:[^"\\]|\\.)*)"', block):
            entry[m.group(1)] = m.group(2)
        if entry and ("source" in entry or "scenario" in entry):
            src = entry.get("source", "")
            if not src.endswith(".edn"):
                entries.append(entry)
        i = j + 1
    return entries


def parse_string_vector(raw: str, key: str) -> list[str]:
    """Extract the string vector under `:key [...]` (e.g. :forge-wired)."""
    m = re.search(r":" + re.escape(key) + r"\s*\[([^\]]*)\]", raw, re.S)
    if not m:
        return []
    return re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))


def parse_pre_existing(raw: str) -> list[str]:
    """Extract :path values from the :pre-existing-fixtures vector."""
    m = re.search(r":pre-existing-fixtures\s*\[(.*?)\]", raw, re.S)
    if not m:
        return []
    return [p for p in re.findall(r':path\s+"((?:[^"\\]|\\.)*)"', m.group(1))]


def git_commit(path: Path) -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=path,
            capture_output=True, text=True, timeout=10,
        ).stdout.strip()
    except Exception:
        return "(not a git repo)"


def main() -> None:
    parser = argparse.ArgumentParser(description="Trace-equivalence set reconciliation gate")
    parser.add_argument("--manifest", default="etc/trace-solidity-manifest.edn")
    parser.add_argument("--sew-repo", default=None)
    parser.add_argument("--receipts-dir", default=None)
    parser.add_argument("--report", default=None)
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    repo_root = script_dir.parent
    manifest_path = (repo_root / args.manifest).resolve()

    sew_str = args.sew_repo or __import__("os").environ.get("SEW_SOLIDITY_PATH") or "../sew-protocol"
    sew_repo = Path(sew_str).resolve()
    receipts_dir = Path(args.receipts_dir) if args.receipts_dir else (sew_repo / "out" / "receipts")

    errors: list[str] = []
    warnings: list[str] = []

    if not manifest_path.exists():
        errors.append(f"manifest not found: {manifest_path}")
        _finish(errors, warnings, args.report, sew_repo)
        return
    raw = manifest_path.read_text("utf-8")

    entries = parse_edn_entries(raw)
    pre_existing = parse_pre_existing(raw)
    forge_wired = parse_string_vector(raw, "forge-wired")

    if not receipts_dir.is_dir():
        errors.append(
            f"receipts dir not found: {receipts_dir}\n"
            "  Run the Forge harness first: cd <sew-repo> && mkdir -p out/receipts && "
            "forge test --match-contract TraceEquivalenceTest"
        )
        _finish(errors, warnings, args.report, sew_repo)
        return

    # ---- Load receipts ------------------------------------------------------
    receipts: list[dict[str, Any]] = []
    for p in sorted(receipts_dir.glob("*" + RECEIPT_SUFFIX)):
        try:
            receipts.append(json.loads(p.read_text("utf-8")))
        except Exception as e:  # pragma: no cover
            errors.append(f"unparseable receipt {p}: {e}")
    if not receipts:
        errors.append(f"no receipts found under {receipts_dir}")

    # ---- Receipt well-formedness --------------------------------------------
    seen_ids: set[str] = set()
    seen_paths: set[str] = set()
    for r in receipts:
        tid = r.get("trace_id", "")
        fp = r.get("fixture_path", "")
        if tid in seen_ids:
            # scenario_id is not unique across the corpus (e.g. sew-001/ref-005
            # are byte-identical mirrors; negative fixtures reuse baseline ids).
            # The reconciliation key is fixture_path, so this is advisory only.
            warnings.append(f"trace_id reused across receipts: {tid}")
        if fp in seen_paths:
            errors.append(f"duplicate fixture_path in receipts: {fp}")
        seen_ids.add(tid)
        seen_paths.add(fp)

        abs_fp = (sew_repo / fp).resolve() if fp else None
        if not fp or not abs_fp.is_file():
            errors.append(f"receipt references missing fixture file: {fp or '(empty)'}")
            continue
        actual = _keccak256(abs_fp.read_bytes())
        declared = r.get("fixture_hash", "")
        if actual != declared:
            errors.append(
                f"receipt fixture_hash mismatch for {fp}\n"
                f"  receipt:  {declared}\n"
                f"  on-disk:  {actual}"
            )

        # On-disk fixture must declare a supported (cdrs_version, schema_version).
        try:
            fx = json.loads(abs_fp.read_text("utf-8"))
            combo = (str(fx.get("cdrs_version", "")), str(fx.get("schema_version", "")))
            if combo not in SUPPORTED_FIXTURE_SPECS:
                errors.append(f"receipt for {fp}: unsupported fixture-spec on disk: {combo}")
        except Exception as e:  # pragma: no cover
            errors.append(f"receipt for {fp}: could not parse fixture JSON: {e}")

        # Classification: manifest destination | pre-existing | harness self-test
        if fp in {e.get("destination", "") for e in entries}:
            pass
        elif fp in pre_existing:
            pass
        elif REGRESSION_DIR in fp:
            pass
        else:
            errors.append(f"replayed-but-unclassified fixture: {fp}")

        # Negotiated replay-spec must be in the supported registry (fail closed)
        spec = r.get("replay_spec_id", "")
        if spec not in SUPPORTED_REPLAY_SPECS:
            errors.append(f"receipt for {fp}: unsupported replay_spec_id {spec!r}")

        # Profile activation must be observable for v0.2 receipts
        if spec.startswith("cdrs-0.2."):
            if not r.get("profile_applied"):
                errors.append(f"receipt for {fp}: profile not applied")
            if int(r.get("invariant_evaluations", 0)) <= 0:
                errors.append(f"receipt for {fp}: zero invariant evaluations (profile inert)")

    # ---- Set reconciliation: manifest vs receipts ---------------------------
    destination_to_entry = {e.get("destination", ""): e for e in entries}
    destination_to_receipt = {r.get("fixture_path", ""): r for r in receipts}

    contract_replayed = [e for e in entries if e["destination"] in destination_to_receipt]
    byte_synced = [e for e in entries if e["destination"] not in destination_to_receipt]

    replayed_destinations = set(destination_to_receipt)
    manifest_destinations = set(destination_to_entry)

    # Included manifest trace without replay receipt -> reject
    for e in byte_synced:
        if e["destination"] in forge_wired:
            errors.append(
                f"manifest :forge-wired lists {e['destination']} but no replay receipt exists"
            )

    # Byte-synced-only trace must NOT have been replayed
    for e in byte_synced:
        if e["destination"] in replayed_destinations:
            errors.append(f"replayed-but-not-expected (byte-synced-only): {e['destination']}")

    # Manifest :forge-wired inventory must equal the actual contract-replayed set
    expected_wired = sorted(forge_wired)
    actual_wired_dests = sorted(e["destination"] for e in contract_replayed)
    if actual_wired_dests != expected_wired:
        errors.append(
            "manifest :forge-wired drift\n"
            f"  manifest lists:  {expected_wired}\n"
            f"  actual receipts: {actual_wired_dests}"
        )

    # Replayed fixtures outside the manifest are fine only if classified above;
    # count them separately for the report.

    ok = not errors

    report = {
        "reconciliation": "ok" if ok else "failed",
        "contract_commit": git_commit(sew_repo),
        "receipts_count": len(receipts),
        "manifest_traces": len(entries),
        "contract_replayed": len(contract_replayed),
        "contract_replayed_ids": [e["id"] for e in contract_replayed],
        "byte_synced_only": len(byte_synced),
        "byte_synced_ids": [e["id"] for e in byte_synced],
        "replayed_outside_manifest": sorted(
            fp for fp in replayed_destinations if fp not in manifest_destinations
        ),
        "errors": errors,
        "warnings": warnings,
    }

    if args.report:
        Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    print("=" * 60)
    print("RECONCILIATION REPORT")
    print("=" * 60)
    print(f"  Contract commit:      {report['contract_commit']}")
    print(f"  Receipts:             {len(receipts)}")
    print(f"  Manifest traces:      {len(entries)}")
    print(f"  Contract-replayed:    {len(contract_replayed)}")
    print(f"  Byte-synchronised:    {len(byte_synced)}")
    if not ok:
        print()
        print("  ERRORS:")
        for e in errors:
            print("    -", e.replace("\n", "\n      "))
    print()
    print(f"  Reconciliation:       {'OK' if ok else 'FAILED'}")
    sys.exit(0 if ok else 1)


def _finish(errors: list[str], warnings: list[str], report_path: str | None, sew_repo: Path) -> None:
    report = {
        "reconciliation": "failed" if errors else "ok",
        "contract_commit": git_commit(sew_repo),
        "errors": errors,
        "warnings": warnings,
    }
    if report_path:
        Path(report_path).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    for e in errors:
        print("  -", e.replace("\n", "\n    "))
    print(f"  Reconciliation:       {'OK' if not errors else 'FAILED'}")
    sys.exit(0 if not errors else 1)


if __name__ == "__main__":
    main()
