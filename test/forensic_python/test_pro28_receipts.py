"""Adversarial checks for the retained PRO-28 receipt reconstruction bundle."""

from __future__ import annotations

import json
import hashlib
import shutil
import sys
from pathlib import Path

import pytest

PROJECT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT / "scripts"))
from forensic import pro28_receipts


SOURCE = PROJECT / "examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261013"


@pytest.fixture
def bundle(tmp_path: Path) -> Path:
    target = tmp_path / "bundle"
    shutil.copytree(SOURCE, target)
    return target


def load(path: Path) -> dict:
    return json.loads(path.read_text())


def save(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n")


def test_fresh_replay_reconstructs_receipts_runtime_and_accounting(bundle: Path) -> None:
    report = pro28_receipts.reconstruct(bundle)
    assert report["successfulReceiptCount"] == 41
    assert report["runtimeCodeIdentity"]["status"] == "captured and content-hash verified from local Anvil RPC"
    assert len(report["runtimeCodeIdentity"]["contracts"]) == 12
    assert report["accounting"]["claimPayouts"] == {"claim-a": 35, "claim-b": 21, "claim-c": 14}


def test_changed_witness_artifact_rejects(bundle: Path) -> None:
    path = bundle / "witness.json"
    path.write_bytes(path.read_bytes() + b" ")
    with pytest.raises(pro28_receipts.EvidenceError, match="witness bytes"):
        pro28_receipts.reconstruct(bundle)


def test_substituted_manifest_rejects(bundle: Path) -> None:
    path = bundle / "deployment-manifest.json"
    manifest = load(path)
    manifest["contracts"]["allocator"] = "0x0000000000000000000000000000000000000001"
    save(path, manifest)
    with pytest.raises(pro28_receipts.EvidenceError, match="manifest digest"):
        pro28_receipts.reconstruct(bundle)


def test_receipt_transaction_substitution_rejects(bundle: Path) -> None:
    path = bundle / "broadcast/withdrawals.json"
    payload = load(path)
    payload["receipts"][0]["transactionHash"] = payload["receipts"][1]["transactionHash"]
    save(path, payload)
    with pytest.raises(pro28_receipts.EvidenceError, match="transaction/receipt identities"):
        pro28_receipts.reconstruct(bundle)


def test_receipt_block_context_mismatch_rejects(bundle: Path) -> None:
    path = bundle / "broadcast/withdrawals.json"
    payload = load(path)
    receipt = next(row for row in payload["receipts"] if row["logs"])
    receipt["blockHash"] = "0x" + "00" * 32
    save(path, payload)
    with pytest.raises(pro28_receipts.EvidenceError, match="log context differs from receipt"):
        pro28_receipts.reconstruct(bundle)


def test_proof_from_another_operation_rejects(bundle: Path) -> None:
    path = bundle / "deployment-manifest.json"
    manifest = load(path)
    manifest["operationId"] = "0x" + "ab" * 32
    canonical = dict(manifest)
    canonical.pop("manifestDigest", None)
    digest = hashlib.sha256((json.dumps(canonical, sort_keys=True, separators=(",", ":")) + "\n").encode()).hexdigest()
    manifest["manifestDigest"] = "sha256:" + digest
    save(path, manifest)
    with pytest.raises(pro28_receipts.EvidenceError, match="proof fixture does not bind the retained deployment manifest"):
        pro28_receipts.reconstruct(bundle)


def test_carol_log_index_substitution_rejects(bundle: Path) -> None:
    path = bundle / "broadcast/withdrawals.json"
    payload = load(path)
    carol = next(
        log
        for receipt in payload["receipts"]
        for log in receipt["logs"]
        if log["address"].lower() == "0x367761085bf3c12e5da2df99ac6e1a824612b8fb"
        and len(log.get("topics", [])) == 3
        and log["topics"][2].lower().endswith("3" * 40)
        and int(log["data"], 16) == 14
    )
    competing_index = next(
        log["logIndex"]
        for group_path in (bundle / "broadcast").glob("*.json")
        for receipt in load(group_path)["receipts"]
        for log in receipt["logs"]
        if log["blockHash"].lower() == carol["blockHash"].lower()
        and log["logIndex"].lower() != carol["logIndex"].lower()
    )
    carol["logIndex"] = competing_index
    save(path, payload)
    with pytest.raises(pro28_receipts.EvidenceError, match="duplicate log index"):
        pro28_receipts.reconstruct(bundle)


def test_forged_verification_labels_do_not_replace_validators(bundle: Path) -> None:
    (bundle / "caller-verification.json").write_text('{"verified":true,"claim":"all evidence authentic"}\n')
    report = pro28_receipts.reconstruct(bundle)
    assert report["successfulReceiptCount"] == 41
    assert report["proof"]["cryptographicVerification"].startswith("passed by the pinned SP1")


def test_incomplete_and_conflicting_observations_reject(bundle: Path) -> None:
    (bundle / "broadcast/activation.json").unlink()
    with pytest.raises(pro28_receipts.EvidenceError, match="cannot read valid JSON"):
        pro28_receipts.reconstruct(bundle)

    clean = shutil.copytree(SOURCE, bundle.parent / "conflicting")
    state_path = clean / "final-state.log"
    state_path.write_text(state_path.read_text().replace("beneficiary_3333_balance=14", "beneficiary_3333_balance=15"))
    with pytest.raises(pro28_receipts.EvidenceError, match="terminal state lacks expected accounting"):
        pro28_receipts.reconstruct(clean)


def test_tampered_runtime_observation_rejects(bundle: Path) -> None:
    path = bundle / "runtime-code-observations.json"
    payload = load(path)
    payload["contracts"]["allocator"]["address"] = "0x0000000000000000000000000000000000000001"
    save(path, payload)
    with pytest.raises(pro28_receipts.EvidenceError, match="runtime-code address differs"):
        pro28_receipts.reconstruct(bundle)
