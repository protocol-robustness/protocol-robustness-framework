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


def _mock_rpc(bundle: Path, *, replace_anchor: bool = False, wrong_parent: bool = False,
              wrong_code: bool = False, wrong_latest_code: bool = False, wrong_chain: bool = False):
    runtime = load(bundle / "runtime-code-observations.json")
    manifest = load(bundle / "deployment-manifest.json")
    transactions, _ = pro28_receipts._load_receipts(bundle)
    by_number: dict[int, list[tuple[str, dict]]] = {}
    for tx_hash, row in transactions.items():
        number = pro28_receipts._int(row["receipt"]["blockNumber"])
        by_number.setdefault(number, []).append((tx_hash, row))
    max_block = runtime["blockNumber"]
    headers = {}
    for number in range(max_block + 1):
        hashes = {pro28_receipts._norm(row["receipt"]["blockHash"])
                  for _, row in by_number.get(number, [])}
        if number == runtime["blockNumber"]:
            hashes.add(pro28_receipts._norm(runtime["blockHash"]))
        block_hash = next(iter(hashes)) if hashes else "0x" + f"{number + 1:064x}"
        if len(hashes) > 1:
            raise AssertionError(f"fixture has conflicting hashes in block {number}")
        parent_hash = headers[number - 1]["hash"] if number else "0x" + "00" * 32
        headers[number] = {"number": hex(number), "hash": block_hash, "parentHash": parent_hash}
    tx_lookup = {}
    tx_by_block = {}
    receipts = {}
    for tx_hash, row in transactions.items():
        receipt = row["receipt"]
        number = pro28_receipts._int(receipt["blockNumber"])
        index = pro28_receipts._int(receipt["transactionIndex"])
        tx = {"hash": tx_hash, "chainId": hex(manifest["chainId"]), "blockHash": headers[number]["hash"],
              "blockNumber": hex(number), "transactionIndex": hex(index)}
        tx_lookup[tx_hash] = tx
        rows = tx_by_block.setdefault(number, [])
        while len(rows) <= index:
            rows.append({"hash": "0x" + "00" * 32})
        rows[index] = tx
        receipts[tx_hash] = receipt
    runtime_by_address = {row["address"].lower(): row["runtimeCode"] for row in runtime["contracts"].values()}

    def rpc(_url, method, params):
        if method == "eth_chainId":
            return "0x1" if wrong_chain else hex(manifest["chainId"])
        if method == "eth_blockNumber":
            return hex(max_block)
        if method == "eth_getBlockByHash":
            return next((h for h in headers.values() if h["hash"].lower() == params[0].lower()), None)
        if method == "eth_getBlockByNumber":
            number = int(params[0], 16)
            block = dict(headers[number])
            if replace_anchor and number == runtime["blockNumber"]:
                block["hash"] = "0x" + "ee" * 32
            if wrong_parent and number == runtime["blockNumber"]:
                block["parentHash"] = "0x" + "dd" * 32
            if params[1]:
                block["transactions"] = tx_by_block.get(number, [])
            else:
                block["transactions"] = []
            return block
        if method == "eth_getTransactionReceipt":
            return receipts.get(params[0].lower())
        if method == "eth_getTransactionByHash":
            return tx_lookup.get(params[0].lower())
        if method == "eth_getCode":
            code = runtime_by_address.get(params[0].lower(), "0x")
            if wrong_code or (wrong_latest_code and params[1] == "latest"):
                return "0x00"
            return code
        raise AssertionError(f"unexpected RPC method {method}")

    return rpc


def test_live_check_revalidates_block_receipts_and_runtime_against_rpc(bundle: Path, monkeypatch) -> None:
    monkeypatch.setattr(pro28_receipts, "_rpc", _mock_rpc(bundle))
    report = pro28_receipts.verify_live_chain_context(bundle, "http://unused.local")
    assert report["transactionsChecked"] == 41
    assert report["contractsChecked"] == 12
    assert report["runtimeCodeCurrentAtLatest"] is True
    assert report["finalityClaimed"] is False
    assert report["environmentId"] == "pro-28-fresh-local-replay-20261013"


def test_live_check_rejects_replaced_canonical_block(bundle: Path, monkeypatch) -> None:
    monkeypatch.setattr(pro28_receipts, "_rpc", _mock_rpc(bundle, replace_anchor=True))
    with pytest.raises(pro28_receipts.EvidenceError, match="orphaned or replaced"):
        pro28_receipts.verify_live_chain_context(bundle, "http://unused.local")


def test_live_check_rejects_replaced_parent_context(bundle: Path, monkeypatch) -> None:
    monkeypatch.setattr(pro28_receipts, "_rpc", _mock_rpc(bundle, wrong_parent=True))
    with pytest.raises(pro28_receipts.EvidenceError, match="parent link"):
        pro28_receipts.verify_live_chain_context(bundle, "http://unused.local")


def test_live_check_rejects_chain_and_runtime_substitution(bundle: Path, monkeypatch) -> None:
    monkeypatch.setattr(pro28_receipts, "_rpc", _mock_rpc(bundle, wrong_chain=True))
    with pytest.raises(pro28_receipts.EvidenceError, match="chain ID"):
        pro28_receipts.verify_live_chain_context(bundle, "http://unused.local")
    monkeypatch.setattr(pro28_receipts, "_rpc", _mock_rpc(bundle, wrong_code=True))
    with pytest.raises(pro28_receipts.EvidenceError, match="runtime code"):
        pro28_receipts.verify_live_chain_context(bundle, "http://unused.local")
    monkeypatch.setattr(pro28_receipts, "_rpc", _mock_rpc(bundle, wrong_latest_code=True))
    with pytest.raises(pro28_receipts.EvidenceError, match="changed after"):
        pro28_receipts.verify_live_chain_context(bundle, "http://unused.local")


def test_live_check_rejects_nonlocal_evidence_profile(bundle: Path, monkeypatch) -> None:
    capture_path = bundle / "capture-metadata.json"
    capture = load(capture_path)
    capture["classification"] = "public chain observation; finality not asserted"
    save(capture_path, capture)
    monkeypatch.setattr(pro28_receipts, "_rpc", _mock_rpc(bundle))
    with pytest.raises(pro28_receipts.EvidenceError, match="explicitly local-only evidence"):
        pro28_receipts.verify_live_chain_context(bundle, "http://unused.local")
