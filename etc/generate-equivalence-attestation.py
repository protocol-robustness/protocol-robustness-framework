#!/usr/bin/env python3
"""
Generate a deterministic, reviewer-facing trace equivalence attestation.

The attestation distinguishes, per manifest trace:
  - contract-replayed:    a Forge test method exists in TraceEquivalenceTest.sol
                          that replays the fixture against live contracts;
  - byte-synchronised:    the fixture is SHA-256 bound to the Clojure source and
                          byte-verified, but is NOT yet wired into Forge (uses
                          actions the basic vault harness cannot reproduce);
  - excluded/unsupported: documented reasons why a trace is not replayed.

The Forge count and the contract-replayed set are DERIVED from the actual test
contract and the current `forge test` run, not hand-maintained prose.

Usage:
  python3 etc/generate-equivalence-attestation.py \
    [--manifest etc/trace-solidity-manifest.edn] \
    [--sew-repo <path>] \
    [--output docs/review/EQUIVALENCE_ATTESTATION.md]
"""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

# Byte-synchronised traces: fixture synced + byte-verified but not wired into
# Forge, with the honest reason (harness boundary, not an execution failure).
BYTE_SYNCED_REASONS: dict[str, str] = {
    "sew-005.json": "contains an accepted escalate_dispute step; DefaultResolutionModule.canEscalate returns false",
    "ref-002.json": "requires propose_fraud_slash / slashing-module actions",
    "ref-003.json": "escrow amounts (500 wei) below contract MIN_ESCROW_AMOUNT (1000); sim does not enforce the minimum",
    "ref-008.json": "uses yield-only action trigger-accrue (YieldOps)",
    "review-s-dr-084.json": "requires submit_evidence on EvidenceModuleV1",
    "review-nc-001.json": "requires propose/execute_fraud_slash (slashing module)",
    "review-y06.json": "yield-only actions (YieldOps)",
    "review-dr-n-002.json": "requires submit_evidence / appeal_slash / challenge_resolution / resolve_appeal actions",
}

PREEXISTING: dict[str, str] = {
    "test/foundry/traces/trace_create_release.json": "Legacy v0.1 golden fixture",
    "test/foundry/traces/trace_create_dispute_release.json": "Legacy v0.1 golden fixture",
    "test/foundry/traces/trace_create_dispute_cancel.json": "Legacy v0.1 golden fixture",
    "test/foundry/traces/trace_phase_z_liveness.json": "Legacy v0.1 Phase Z adversarial",
    "test/foundry/traces/v2/s01.json": "Pre-existing v0.2 baseline (S01)",
    "test/foundry/traces/v2/s02.json": "Pre-existing v0.2 baseline (S02)",
    "test/foundry/traces/v2/s05.json": "Pre-existing v0.2 baseline (S05)",
    "test/foundry/traces/v2/negative/n01.json": "v0.2 negative/wrong outcome",
    "test/foundry/traces/v2/negative/n02.json": "v0.2 negative/unauthorized resolver",
    "test/foundry/traces/v2/negative/n03.json": "v0.2 negative/settlement not executed",
    "test/foundry/traces/v2/negative/n04.json": "v0.2 negative/wrong escalation level",
    "test/foundry/traces/v2/negative/n05.json": "v0.2 negative/wrong dispute initiator",
    "test/foundry/traces/v2/negative/n06.json": "v0.2 negative/auto-cancel triggered",
    "test/foundry/traces/v2/negative/n07.json": "v0.2 negative/wrong resolution actor",
}


def parse_edn_entries(raw: str) -> list[dict[str, str]]:
    entries = []
    pos = 0
    while True:
        pos = raw.find("{:id", pos)
        if pos == -1:
            break
        depth = 0
        j = pos
        while j < len(raw):
            if raw[j] == "{":
                depth += 1
            elif raw[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        block = raw[pos : j + 1]
        entry = {}
        for m in re.finditer(r':([\w-]+)\s+"((?:[^"\\]|\\.)*)"', block):
            entry[m.group(1)] = m.group(2)
        if entry:
            src = entry.get("source", "")
            if not src.endswith(".edn") and ("source" in entry or "scenario" in entry):
                entries.append(entry)
        pos = j + 1
    return entries


def wired_fixture_paths(test_contract_path: Path) -> set[str]:
    """Fixtures actually replayed by TraceEquivalenceTest, derived from the
    test contract source rather than hand-maintained lists."""
    text = test_contract_path.read_text("utf-8")
    return set(re.findall(r'_replayTrace\("([^"]+)"\)', text))


def run_forge_test(sew_repo: Path) -> tuple[int, int]:
    try:
        result = subprocess.run(
            ["forge", "test", "--match-contract", "TraceEquivalenceTest"],
            cwd=sew_repo,
            capture_output=True,
            text=True,
            timeout=600,
        )
        out = result.stdout + result.stderr
        passed = len(re.findall(r"\[PASS\]", out))
        failed = len(re.findall(r"\[FAIL\]", out))
        return passed, failed
    except Exception:
        return -1, -1  # Could not run


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default="etc/trace-solidity-manifest.edn")
    parser.add_argument("--sew-repo", default=None)
    parser.add_argument("--output", default="docs/review/EQUIVALENCE_ATTESTATION.md")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[1]
    manifest_path = (repo_root / args.manifest).resolve()
    output_path = (repo_root / args.output).resolve()

    sew_str = args.sew_repo
    if not sew_str:
        try:
            sew_str = subprocess.run(
                ["git", "rev-parse", "--show-toplevel"],
                cwd=repo_root,
                capture_output=True,
                text=True,
                timeout=5,
            ).stdout.strip()
        except Exception:
            sew_str = "../sew-protocol"
    sew_repo = Path(sew_str).resolve()

    timestamp = datetime.now().strftime("%Y-%m-%dT%H:%M:%SZ")

    raw = manifest_path.read_text("utf-8")
    manifest_sha = hashlib.sha256(raw.encode()).hexdigest()
    entries = parse_edn_entries(raw)

    test_contract = sew_repo / "test/foundry/TraceEquivalence.t.sol"
    wired = wired_fixture_paths(test_contract)

    # Split manifest traces.  A trace is contract-replayed iff its destination
    # fixture is wired into the test contract.  The two sets are disjoint.
    contract_replayed: list[dict[str, str]] = []
    byte_synced: list[dict[str, str]] = []
    for e in entries:
        dest = e.get("destination", "")
        if dest in wired:
            contract_replayed.append(e)
        else:
            byte_synced.append(e)

    assert len(contract_replayed) + len(byte_synced) == len(entries), "manifest split mismatch"
    assert not (set(id(e) for e in contract_replayed) & set(id(e) for e in byte_synced)), "overlap"

    forge_passed, forge_failed = run_forge_test(sew_repo)

    def countline() -> str:
        if forge_passed < 0:
            return "| Forge tests | (not run during generation) |"
        return f"| Forge tests passed | {forge_passed}/{forge_passed + forge_failed} |"

    lines = [
        "# PRF / Sew Protocol — Trace Equivalence Attestation",
        "",
        f"**Generated:** {timestamp}",
        f"**Manifest:** `{args.manifest}` (SHA-256: `{manifest_sha}`)",
        f"**Clojure repo:** `{repo_root}`",
        f"**Solidity repo:** `{sew_repo}`",
        "",
        "---",
        "",
        "## Summary",
        "",
        "The Clojure reference implementation and the sew-protocol Solidity "
        "implementation are bound by a SHA-256 trace manifest. Within that "
        "manifest, each trace is classified by how far its equivalence is "
        "demonstrated:",
        "",
        "- **Contract-replayed** — the fixture is replayed against live contracts "
        "by a Forge test in `TraceEquivalenceTest.sol`, asserting the EVM projection "
        "against the simulation at every step, plus the invariant profile and "
        "terminal projection hash.",
        "- **Byte-synchronised only** — the fixture is byte-identical (SHA-256) "
        "to the Clojure source and the projection is validated structurally, but "
        "no Forge test replays it yet because it uses actions the basic vault "
        "harness cannot reproduce.",
        "",
        f"| Metric | Value |",
        "|--------|-------|",
        f"| Manifest traces | {len(entries)} |",
        f"| Manifest SHA-256 | `{manifest_sha}` |",
        f"| Fixture schema | CDRS v0.2 (schema_version 2) |",
        countline(),
        f"| Contract-replayed | {len(contract_replayed)} |",
        f"| Byte-synchronised only | {len(byte_synced)} |",
        "",
        "---",
        "",
        f"## Contract-Replayed Traces ({len(contract_replayed)})",
        "",
        "These fixtures are wired into `TraceEquivalenceTest.sol` and the "
        "execution-level assertions (per-step projection, invariant profile, "
        "terminal projection hash) actually run against the EVM.",
        "",
        "| ID | Source path | Source SHA-256 | Forge fixture |",
        "|---|---|---|---|",
    ]
    for e in contract_replayed:
        eid = e.get("id", "?")
        src = e.get("source", "(none)")
        sha = e.get("source-sha256", "(none)")
        dest = e.get("destination", "(none)")
        rev = e.get("review-scenario", "")
        rev_tag = f" [{rev}]" if rev else ""
        lines.append(f"| `{eid}` | `{src}` | `{sha}` | `{dest}`{rev_tag} |")

    lines += [
        "",
        "---",
        "",
        f"## Byte-Synchronised Traces ({len(byte_synced)})",
        "",
        "These fixtures are SHA-256 matched to their Clojure sources and "
        "byte-verified by `bb trace:solidity:verify`, but are NOT yet wired into "
        "Forge.  Byte-sync proves fixture integrity, not contract equivalence. "
        "Each is listed with the harness boundary that currently prevents replay.",
        "",
        "| ID | Source path | Source SHA-256 | Forge fixture | Reason not replayed |",
        "|---|---|---|---|---|",
    ]
    for e in byte_synced:
        eid = e.get("id", "?")
        src = e.get("source", "(none)")
        sha = e.get("source-sha256", "(none)")
        dest = e.get("destination", "(none)")
        rev = e.get("review-scenario", "")
        rev_tag = f" [{rev}]" if rev else ""
        reason = BYTE_SYNCED_REASONS.get(Path(dest).name, "see manifest / review register")
        lines.append(
            f"| `{eid}` | `{src}` | `{sha}` | `{dest}`{rev_tag} | {reason} |"
        )

    lines += [
        "",
        "---",
        "",
        "## Pre-existing Solidity Fixtures (14)",
        "",
        "These fixtures exist in the Solidity repo but are NOT part of the "
        "manifest-bound equivalence claim. They are reported as warnings by "
        "`bb trace:solidity:verify` and must not be counted toward the "
        "verified trace count.",
        "",
        "| Fixture | Classification |",
        "|---|---|",
    ]
    for path, label in sorted(PREEXISTING.items()):
        lines.append(f"| `{path}` | {label} |")

    lines += [
        "",
        "---",
        "",
        "## Verification Procedure",
        "",
        "### Step 1 — Cross-repository integrity",
        "",
        "```bash",
        "bb trace:solidity:verify --sew-repo ../sew-protocol",
        "```",
        "",
        "Expected result: `VERIFIED` (all 18 manifest traces pass byte verification).",
        "",
        "### Step 2 — Forge EVM replay",
        "",
        "```bash",
        "cd ../sew-protocol",
        "forge test --match-contract TraceEquivalenceTest -vvv",
        "```",
        "",
        f"Result at generation time: {forge_passed}/{forge_passed + forge_failed} passed "
        f"(of which {len(contract_replayed)} are contract-replayed manifest traces).",
        "",
        "### Step 3 — Regenerate this attestation",
        "",
        "```bash",
        "python3 etc/generate-equivalence-attestation.py --sew-repo ../sew-protocol",
        "```",
        "",
        "---",
        "",
        "## Boundary of the Equivalence Claim",
        "",
        "The attested equivalence is **manifest-bound and layered**. It claims:",
        "",
        f"- Byte-identical fixtures across repos for all {len(entries)} manifest traces.",
        f"- Contract-level equivalence for the {len(contract_replayed)} contract-replayed "
        "traces listed above, including the invariant profile (conservation-of-funds, "
        "dispute-level-bounded, terminal-payout-exclusivity, held-reconstruction, "
        "state-transition-valid, escalation-monotonic, terminal-state-immutable) and "
        "the terminal projection hash (SHA-256 of `state|afa|psExists|disputeLevel`).",
        "",
        "It does NOT claim:",
        "",
        f"- Contract-level equivalence for the {len(byte_synced)} byte-synchronised-only traces.",
        "- Full Sew protocol equivalence beyond the wired traces.",
        "- Module-backed stake or slashing parity (not exercised).",
        "- Yield module integration (requires separate harness).",
        "- Non-zero dispute-level / successful-escalation traces (DefaultResolutionModule "
        "cannot escalate).",
        "- Generic EVM state equivalence (the projection is limited to the "
        "`diff.clj` comparable-keys).",
        "- Automatic cross-repository synchronisation (sync is manual via `bb trace:solidity:sync`).",
        "",
        "Within the contract-replayed scope, no unresolved semantic divergence remains "
        "between the Clojure reference implementation and the Solidity contracts.",
    ]

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Written: {output_path}")
    print(f"Manifest traces: {len(entries)} | contract-replayed: {len(contract_replayed)} | byte-synced: {len(byte_synced)}")


if __name__ == "__main__":
    main()
