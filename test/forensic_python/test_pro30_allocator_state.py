"""Adversarial tests for V2 allocator snapshots at claim boundaries."""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

PROJECT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT / "scripts"))
from forensic import pro28_receipts, pro30_allocator_state


PRO29_SOURCE = PROJECT / "examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-pro29-20261018"


def _snapshots() -> list[dict]:
    states = [
        (2, 0, 0, 0, [False, False, False]),
        (3, 0, 0, 70, [False, False, False]),
        (4, 35, 1, 35, [True, False, False]),
        (4, 35, 1, 35, [True, False, False]),
        (4, 56, 2, 14, [True, True, False]),
        (4, 56, 2, 14, [True, True, False]),
        (5, 70, 3, 0, [True, True, True]),
    ]
    output = []
    for i, (state, amount, count, liability, consumed) in enumerate(states):
        output.append({
            "id": pro30_allocator_state.SNAPSHOT_IDS[i],
            "block": {"number": 31 + i, "hash": "0x" + f"{i + 1:064x}", "parentHash": "0x" + f"{i:064x}"},
            "transactionBoundary": {"transactionCount": 1, "transactionHash": "0x" + f"{i + 10:064x}", "transactionIndex": 0},
            "state": {
                "operationState": state,
                "operationStateName": pro30_allocator_state.STATE_NAMES[state],
                "entitlementCount": 3,
                "entitlementTotal": 70,
                "token": "0x" + "aa" * 20,
                "fundedAmount": 70,
                "claimedAmount": amount,
                "claimedCount": count,
                "outstandingLiability": liability,
                "allocatorTokenBalance": liability,
                "consumedByIndex": consumed,
                "consumedClaimIdentity": consumed.copy(),
                "allocationBindingRoot": "0x" + "11" * 32,
                "descriptorRoot": "0x" + "22" * 32,
                "evmEntitlementTableRoot": "0x" + "33" * 32,
                "allocationClaimRoot": "0x" + "44" * 32,
            },
        })
    return output


def test_retained_claim_receipts_define_unambiguous_before_and_after_boundaries() -> None:
    reconstructed = pro28_receipts.reconstruct(PRO29_SOURCE)
    boundaries = pro30_allocator_state._claim_boundaries(PRO29_SOURCE, reconstructed)
    assert [row["id"] for row in boundaries] == list(pro30_allocator_state.SNAPSHOT_IDS)
    assert [row["blockNumber"] for row in boundaries] == [31, 32, 33, 36, 37, 40, 41]
    assert boundaries[0]["anchor"]["relation"] == "before"
    assert boundaries[0]["anchor"]["blockNumber"] == 32
    assert boundaries[2]["anchor"]["relation"] == "after"
    assert boundaries[2]["anchor"]["transactionHash"] == (
        "0x9acb21e6f3d9313913c530c1c115064c7b927625ecea08820a2c5275405fed17"
    )


def test_timeline_reconciles_claims_liability_and_both_consumption_views() -> None:
    snapshots = _snapshots()
    pro30_allocator_state._validate_timeline(snapshots)

    changed_bitmap = json.loads(json.dumps(snapshots))
    changed_bitmap[2]["state"]["consumedByIndex"] = [False, True, False]
    with pytest.raises(pro28_receipts.EvidenceError, match="allocator state transition"):
        pro30_allocator_state._validate_timeline(changed_bitmap)

    changed_mapping = json.loads(json.dumps(snapshots))
    changed_mapping[2]["state"]["consumedClaimIdentity"] = [False, False, False]
    with pytest.raises(pro28_receipts.EvidenceError, match="identity mapping"):
        pro30_allocator_state._validate_timeline(changed_mapping)

    wrong_liability = json.loads(json.dumps(snapshots))
    wrong_liability[4]["state"]["outstandingLiability"] = 15
    with pytest.raises(pro28_receipts.EvidenceError, match="allocator state transition"):
        pro30_allocator_state._validate_timeline(wrong_liability)


def test_reordered_stale_and_ambiguous_snapshots_fail_closed() -> None:
    reordered = _snapshots()
    reordered[2], reordered[3] = reordered[3], reordered[2]
    with pytest.raises(pro28_receipts.EvidenceError, match="missing, duplicated or reordered"):
        pro30_allocator_state._validate_timeline(reordered)

    stale = _snapshots()
    stale[4]["block"]["number"] = stale[3]["block"]["number"]
    with pytest.raises(pro28_receipts.EvidenceError, match="stale or not strictly ordered"):
        pro30_allocator_state._validate_timeline(stale)

    ambiguous = _snapshots()
    ambiguous[1]["transactionBoundary"]["transactionCount"] = 2
    with pytest.raises(pro28_receipts.EvidenceError, match="ambiguous transaction boundary"):
        pro30_allocator_state._validate_timeline(ambiguous)


def test_operation_roots_cannot_change_between_snapshots() -> None:
    snapshots = _snapshots()
    snapshots[5]["state"]["evmEntitlementTableRoot"] = "0x" + "55" * 32
    with pytest.raises(pro28_receipts.EvidenceError, match="operation identity changed"):
        pro30_allocator_state._validate_timeline(snapshots)


def test_offline_verification_rejects_substituted_observation(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    report = {
        "schema": "eae.pro-30-allocator-state-observations.v1",
        "snapshots": _snapshots(),
    }
    path = tmp_path / "observations.json"
    path.write_text(json.dumps(report))
    monkeypatch.setattr(pro30_allocator_state, "capture", lambda raw, rpc: report)
    assert pro30_allocator_state.verify(tmp_path, path, "http://127.0.0.1:1") == report

    report["snapshots"][2]["state"]["claimedAmount"] = 36
    path.write_text(json.dumps(report))
    with pytest.raises(pro28_receipts.EvidenceError, match="allocator state transition"):
        pro30_allocator_state.verify(tmp_path, path, "http://127.0.0.1:1")

    report["snapshots"] = _snapshots()
    report["snapshots"][0], report["snapshots"][1] = report["snapshots"][1], report["snapshots"][0]
    path.write_text(json.dumps(report))
    with pytest.raises(pro28_receipts.EvidenceError, match="missing, duplicated or reordered"):
        pro30_allocator_state.verify(tmp_path, path, "http://127.0.0.1:1")


def test_canonical_claim_identity_hash_matches_pinned_solidity_rule() -> None:
    assert pro30_allocator_state._claim_identity_hash("claim-a") == (
        "0x8cf848e36296772b88c8a72941350bbccbd157fc19a9a4ac9eaa4557262eab88"
    )
    assert pro30_allocator_state._claim_identity_hash("claim-b") == (
        "0x53e73c2ead3aa8da4c9aeff45cae85da91627499fc586cd2efa50e299b8ea84d"
    )
    assert pro30_allocator_state._claim_identity_hash("claim-c") == (
        "0x1d45922891140031bbdbabe1c2a933d174ea1feadef867bc29333473e00fd0c9"
    )


def test_replay_probe_requires_the_specific_consumed_index_revert() -> None:
    valid = {"error": {"code": 3, "data": pro30_allocator_state.CONSUMED_INDEX_ZERO_ERROR}}
    assert pro30_allocator_state._assert_replay_rejected(valid)["reasonClass"] == "EntitlementAlreadyConsumed(0)"
    with pytest.raises(pro28_receipts.EvidenceError, match="EntitlementAlreadyConsumed"):
        pro30_allocator_state._assert_replay_rejected({"error": {"code": 3, "data": "0xdeadbeef"}})
    with pytest.raises(pro28_receipts.EvidenceError, match="unexpectedly succeeded"):
        pro30_allocator_state._assert_replay_rejected({"result": "0x"})
