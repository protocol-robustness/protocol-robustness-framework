"""Capture and recheck V2 allocator state at each protected claim boundary.

This profile uses ABI-readable getters and block-hash-pinned local Anvil
observations. It does not infer private storage slots or claim a state proof.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from forensic import pro28_receipts


ZERO = "0x" + "00" * 20
CLAIMS = (
    {"claimId": "claim-a", "index": 0, "amount": 35, "beneficiary": "0x1111111111111111111111111111111111111111"},
    {"claimId": "claim-b", "index": 1, "amount": 21, "beneficiary": "0x2222222222222222222222222222222222222222"},
    {"claimId": "claim-c", "index": 2, "amount": 14, "beneficiary": "0x3333333333333333333333333333333333333333"},
)
STATE_NAMES = {0: "None", 1: "Created", 2: "Funded", 3: "Activated", 4: "Claims", 5: "Closed", 6: "Cancelled"}
SELECTORS = {
    "claimedAmount": "afa718ac",
    "claimedCount": "7f6d64ce",
    "outstandingLiability": "665a61ca",
    "consumed": "bb8f2054",
    "consumedClaimIdentity": "8cb0da26",
    "entitlementOperation": "fa6efec5",
    "balanceOf": "70a08231",
}
SNAPSHOT_IDS = (
    "beforeActivation", "beforeAlice", "afterAlice", "beforeBob", "afterBob", "beforeCarol", "afterCarolAndClose"
)
CONSUMED_INDEX_ZERO_ERROR = "0xa3be6582" + ("0" * 64)


def _norm(value: str) -> str:
    return value.lower()


def _quantity(value: int) -> str:
    return hex(value)


def _word(value: int) -> str:
    if value < 0 or value >= 1 << 256:
        raise pro28_receipts.EvidenceError("ABI integer is outside uint256")
    return f"{value:064x}"


def _abi_word(data: str, index: int, label: str) -> int:
    encoded = data.removeprefix("0x")
    if len(encoded) != 12 * 64:
        raise pro28_receipts.EvidenceError(f"{label} must return the pinned 12-word operation ABI")
    try:
        return int(encoded[index * 64:(index + 1) * 64], 16)
    except ValueError as exc:
        raise pro28_receipts.EvidenceError(f"{label} contains malformed ABI data") from exc


def _operation_words(data: str, label: str) -> list[str]:
    encoded = data.removeprefix("0x")
    if len(encoded) != 12 * 64:
        raise pro28_receipts.EvidenceError(f"{label} must return the pinned 12-word operation ABI")
    try:
        bytes.fromhex(encoded)
    except ValueError as exc:
        raise pro28_receipts.EvidenceError(f"{label} contains malformed ABI data") from exc
    return [encoded[i:i + 64].lower() for i in range(0, len(encoded), 64)]


def _claim_identity_hash(claim_id: str) -> str:
    """Apply the allocator's canonical 0x20 || unsigned-varint(len) || UTF-8 rule."""
    raw = claim_id.encode("utf-8")
    remaining = len(raw)
    length = bytearray()
    while remaining >= 0x80:
        length.append((remaining & 0x7f) | 0x80)
        remaining >>= 7
    length.append(remaining & 0x7f)
    payload = bytes([0x20]) + bytes(length) + raw
    try:
        import subprocess

        result = subprocess.run(
            ["cast", "keccak", "0x" + payload.hex()], capture_output=True, text=True, check=False
        )
    except OSError as exc:
        raise pro28_receipts.EvidenceError("Foundry cast is required to derive Solidity claim identity hashes") from exc
    digest = result.stdout.strip().lower()
    if result.returncode != 0 or len(digest) != 66 or not digest.startswith("0x"):
        raise pro28_receipts.EvidenceError("cast keccak failed for canonical claim identity")
    try:
        bytes.fromhex(digest[2:])
    except ValueError as exc:
        raise pro28_receipts.EvidenceError("cast returned a malformed claim identity hash") from exc
    return digest


def _call(rpc_url: str, target: str, calldata: str, block_ref: dict[str, Any], label: str) -> str:
    result = pro28_receipts._rpc(rpc_url, "eth_call", [{"to": target, "data": calldata}, block_ref])
    if not isinstance(result, str) or not result.startswith("0x"):
        raise pro28_receipts.EvidenceError(f"{label} returned malformed ABI data")
    return result.lower()


def _call_uint(rpc_url: str, target: str, calldata: str, block_ref: dict[str, Any], label: str) -> int:
    result = _call(rpc_url, target, calldata, block_ref, label)
    if len(result) != 66:
        raise pro28_receipts.EvidenceError(f"{label} must return one ABI word")
    try:
        return int(result[2:], 16)
    except ValueError as exc:
        raise pro28_receipts.EvidenceError(f"{label} returned malformed ABI data") from exc


def _claim_boundaries(raw: Path, reconstructed: dict[str, Any]) -> list[dict[str, Any]]:
    transactions, _ = pro28_receipts._load_receipts(raw)
    activation_rows = [row for row in transactions.values() if row["group"] == "activation"]
    if not activation_rows:
        raise pro28_receipts.EvidenceError("activation receipt is missing")
    activation = max(activation_rows, key=lambda row: pro28_receipts._int(row["receipt"]["blockNumber"]))
    activation_block = pro28_receipts._int(activation["receipt"]["blockNumber"])
    claim_events = [event for event in reconstructed["economicEvents"] if event.get("kind") == "entitlementClaim"]
    by_id = {event.get("claimId"): event for event in claim_events}
    if set(by_id) != {claim["claimId"] for claim in CLAIMS} or len(claim_events) != len(CLAIMS):
        raise pro28_receipts.EvidenceError("retained execution does not contain the three expected unique claims")
    activation_receipt = activation["receipt"]

    def boundary(snapshot_id: str, block_number: int, anchor_receipt: dict[str, Any], relation: str,
                 claim: dict[str, Any] | None) -> dict[str, Any]:
        return {
            "id": snapshot_id,
            "blockNumber": block_number,
            "anchor": {
                "relation": relation,
                "transactionHash": _norm(anchor_receipt["transactionHash"]),
                "blockNumber": pro28_receipts._int(anchor_receipt["blockNumber"]),
                "blockHash": _norm(anchor_receipt["blockHash"]),
            },
            "claim": claim,
        }

    boundaries = [boundary("beforeActivation", activation_block - 1, activation_receipt, "before", None)]
    for index, claim in enumerate(CLAIMS):
        event = by_id[claim["claimId"]]
        if event.get("amount") != claim["amount"] or _norm(event.get("to", "")) != claim["beneficiary"]:
            raise pro28_receipts.EvidenceError("claim receipt differs from the controlled entitlement identity")
        receipt = event["receipt"]
        block_number = receipt["blockNumber"]
        if index == 0:
            boundaries.append(boundary("beforeAlice", block_number - 1, receipt, "before", claim))
        elif index == 1:
            boundaries.append(boundary("beforeBob", block_number - 1, receipt, "before", claim))
        else:
            boundaries.append(boundary("beforeCarol", block_number - 1, receipt, "before", claim))
        after_id = "afterCarolAndClose" if index == 2 else f"after{claim['claimId'][-1].upper()}"
        if after_id == "afterA":
            after_id = "afterAlice"
        elif after_id == "afterB":
            after_id = "afterBob"
        boundaries.append(boundary(after_id, block_number, receipt, "after", claim))
    if [row["id"] for row in boundaries] != list(SNAPSHOT_IDS):
        raise pro28_receipts.EvidenceError("allocator claim boundaries are incomplete or duplicated")
    return boundaries


def _assert_replay_rejected(probe_response: dict[str, Any]) -> dict[str, Any]:
    error = probe_response.get("error")
    if not isinstance(error, dict):
        raise pro28_receipts.EvidenceError("already-consumed claim eth_call unexpectedly succeeded")
    revert_data = error.get("data")
    if not isinstance(revert_data, str) or _norm(revert_data) != CONSUMED_INDEX_ZERO_ERROR:
        raise pro28_receipts.EvidenceError("consumed-claim probe did not return EntitlementAlreadyConsumed(0)")
    return {"errorCode": error.get("code"), "revertData": _norm(revert_data),
            "reasonClass": "EntitlementAlreadyConsumed(0)"}


def _snapshot(rpc_url: str, manifest: dict[str, Any], boundary: dict[str, Any],
              claim_hashes: dict[str, str]) -> dict[str, Any]:
    block_number = boundary["blockNumber"]
    snapshot_id = boundary["id"]
    block = pro28_receipts._block_by_number(rpc_url, block_number)
    tx_block = pro28_receipts._rpc(rpc_url, "eth_getBlockByNumber", [_quantity(block_number), True])
    txs = tx_block.get("transactions", []) if isinstance(tx_block, dict) else []
    if len(txs) != 1:
        raise pro28_receipts.EvidenceError(
            f"allocator snapshot block {block_number} contains {len(txs)} transactions; transaction boundary is ambiguous"
        )
    anchor = boundary["anchor"]
    if anchor["relation"] == "after":
        if (block_number != anchor["blockNumber"] or _norm(block["hash"]) != anchor["blockHash"]
                or _norm(txs[0]["hash"]) != anchor["transactionHash"]):
            raise pro28_receipts.EvidenceError(f"post-claim snapshot is not anchored to its exact receipt at {snapshot_id}")
    elif anchor["relation"] == "before":
        next_block = pro28_receipts._block_by_number(rpc_url, anchor["blockNumber"])
        if (anchor["blockNumber"] != block_number + 1 or _norm(next_block.get("parentHash", "")) != _norm(block["hash"])
                or _norm(next_block.get("hash", "")) != anchor["blockHash"]):
            raise pro28_receipts.EvidenceError(f"pre-transition snapshot is not the parent of its receipt block at {snapshot_id}")
    else:
        raise pro28_receipts.EvidenceError(f"unknown allocator snapshot boundary relation at {snapshot_id}")
    block_ref = {"blockHash": _norm(block["hash"]), "requireCanonical": True}
    allocator = _norm(manifest["contracts"]["allocator"])
    token = _norm(manifest["contracts"]["token"])
    operation_id = manifest["operationId"]
    operation_arg = operation_id.removeprefix("0x").lower()
    reads: dict[str, Any] = {}

    def read_uint(name: str, calldata: str) -> int:
        value = _call_uint(rpc_url, allocator, "0x" + calldata, block_ref, name)
        reads[name] = {"to": allocator, "calldata": "0x" + calldata, "rawResult": _word(value)}
        return value

    claimed_amount = read_uint("claimedAmount", SELECTORS["claimedAmount"] + operation_arg)
    claimed_count = read_uint("claimedCount", SELECTORS["claimedCount"] + operation_arg)
    liability = read_uint("outstandingLiability", SELECTORS["outstandingLiability"] + operation_arg)
    consumed = []
    consumed_identities = []
    for claim in CLAIMS:
        index_data = SELECTORS["consumed"] + operation_arg + _word(claim["index"])
        index_value = read_uint(f"consumed:{claim['claimId']}", index_data)
        if index_value not in (0, 1):
            raise pro28_receipts.EvidenceError("consumed(index) returned a non-boolean ABI word")
        consumed.append(index_value == 1)
        identity_hash = claim_hashes[claim["claimId"]]
        identity_data = SELECTORS["consumedClaimIdentity"] + operation_arg + identity_hash.removeprefix("0x")
        identity_value = read_uint(f"consumedClaimIdentity:{claim['claimId']}", identity_data)
        if identity_value not in (0, 1):
            raise pro28_receipts.EvidenceError("consumedClaimIdentity returned a non-boolean ABI word")
        consumed_identities.append(identity_value == 1)
    operation_result = _call(rpc_url, allocator, "0x" + SELECTORS["entitlementOperation"] + operation_arg,
                              block_ref, "entitlementOperation")
    operation_words = _operation_words(operation_result, "entitlementOperation")
    reads["entitlementOperation"] = {"to": allocator,
        "calldata": "0x" + SELECTORS["entitlementOperation"] + operation_arg, "rawResult": operation_result}
    token_balance = _call_uint(rpc_url, token,
        "0x" + SELECTORS["balanceOf"] + allocator.removeprefix("0x").rjust(64, "0"), block_ref, "allocator token balance")
    reads["allocatorTokenBalance"] = {"to": token,
        "calldata": "0x" + SELECTORS["balanceOf"] + allocator.removeprefix("0x").rjust(64, "0"),
        "rawResult": _word(token_balance)}
    return {
        "id": snapshot_id,
        "boundaryAnchor": anchor,
        "block": {"number": block_number, "hash": _norm(block["hash"]), "parentHash": _norm(block["parentHash"])},
        "transactionBoundary": {"transactionCount": len(txs), "transactionHash": _norm(txs[0]["hash"]),
                                "transactionIndex": 0},
        "reads": reads,
        "state": {
            "operationState": _abi_word(operation_result, 0, "entitlementOperation"),
            "operationStateName": STATE_NAMES.get(_abi_word(operation_result, 0, "entitlementOperation"), "Unknown"),
            "entitlementCount": _abi_word(operation_result, 4, "entitlementOperation"),
            "entitlementTotal": _abi_word(operation_result, 5, "entitlementOperation"),
            "token": "0x" + operation_words[8][-40:],
            "fundedAmount": int(operation_words[9], 16),
            "claimedAmount": claimed_amount,
            "claimedCount": claimed_count,
            "outstandingLiability": liability,
            "allocatorTokenBalance": token_balance,
            "consumedByIndex": consumed,
            "consumedClaimIdentity": consumed_identities,
            "consumedClaimIds": [claim["claimId"] for claim, value in zip(CLAIMS, consumed) if value],
            "allocationBindingRoot": "0x" + operation_words[1],
            "descriptorRoot": "0x" + operation_words[2],
            "evmEntitlementTableRoot": "0x" + operation_words[3],
            "allocationClaimRoot": "0x" + operation_words[6],
            "beneficiaryProfileWord": "0x" + operation_words[7],
        },
    }


def _validate_timeline(snapshots: list[dict[str, Any]]) -> None:
    if [row.get("id") for row in snapshots] != list(SNAPSHOT_IDS):
        raise pro28_receipts.EvidenceError("allocator snapshots are missing, duplicated or reordered")
    if any(row.get("transactionBoundary", {}).get("transactionCount") != 1
           or row.get("transactionBoundary", {}).get("transactionIndex") != 0 for row in snapshots):
        raise pro28_receipts.EvidenceError("allocator snapshot has an ambiguous transaction boundary")
    by_id = {row["id"]: row for row in snapshots}
    invariant_fields = ("entitlementCount", "entitlementTotal", "fundedAmount", "token",
                        "allocationBindingRoot", "descriptorRoot", "evmEntitlementTableRoot", "allocationClaimRoot")
    first_state = by_id[SNAPSHOT_IDS[0]].get("state", {})
    for snapshot in snapshots[1:]:
        state = snapshot.get("state", {})
        if any(state.get(field) != first_state.get(field) for field in invariant_fields):
            raise pro28_receipts.EvidenceError("operation identity changed between allocator snapshots")
    expected = {
        "beforeActivation": (2, 0, 0, 0, [False, False, False]),
        "beforeAlice": (3, 0, 0, 70, [False, False, False]),
        "afterAlice": (4, 35, 1, 35, [True, False, False]),
        "beforeBob": (4, 35, 1, 35, [True, False, False]),
        "afterBob": (4, 56, 2, 14, [True, True, False]),
        "beforeCarol": (4, 56, 2, 14, [True, True, False]),
        "afterCarolAndClose": (5, 70, 3, 0, [True, True, True]),
    }
    previous_block = -1
    for snapshot_id in SNAPSHOT_IDS:
        snapshot = by_id[snapshot_id]
        state = snapshot.get("state", {})
        block_number = snapshot.get("block", {}).get("number", -1)
        if not isinstance(block_number, int) or block_number <= previous_block:
            raise pro28_receipts.EvidenceError("allocator snapshots are stale or not strictly ordered by block")
        previous_block = block_number
        expected_state, amount, count, liability, consumed = expected[snapshot_id]
        if (state.get("operationState"), state.get("claimedAmount"), state.get("claimedCount"),
                state.get("outstandingLiability"), state.get("consumedByIndex")) != (
                expected_state, amount, count, liability, consumed):
            raise pro28_receipts.EvidenceError(f"allocator state transition differs at {snapshot_id}")
        if state.get("operationStateName") != STATE_NAMES[expected_state]:
            raise pro28_receipts.EvidenceError(f"allocator state name differs at {snapshot_id}")
        if state.get("consumedClaimIdentity") != consumed:
            raise pro28_receipts.EvidenceError(f"claim identity mapping differs at {snapshot_id}")
        if state.get("claimedCount") != sum(state.get("consumedByIndex", [])):
            raise pro28_receipts.EvidenceError(f"claimed count differs from consumed indices at {snapshot_id}")
        expected_amount = sum(claim["amount"] for claim, was_consumed in zip(CLAIMS, consumed) if was_consumed)
        if state.get("claimedAmount") != expected_amount:
            raise pro28_receipts.EvidenceError(f"claimed amount differs from consumed indices at {snapshot_id}")
        if snapshot_id != "beforeActivation":
            if amount + liability != 70:
                raise pro28_receipts.EvidenceError(f"claimed plus remaining liability differs from 70 at {snapshot_id}")
            if state.get("allocatorTokenBalance") != liability:
                raise pro28_receipts.EvidenceError(f"allocator custody differs from remaining liability at {snapshot_id}")
        if state.get("entitlementCount") != 3 or state.get("entitlementTotal") != 70 or state.get("fundedAmount") != 70:
            raise pro28_receipts.EvidenceError(f"entitlement operation parameters differ at {snapshot_id}")


def capture(raw: Path, rpc_url: str) -> dict[str, Any]:
    raw = Path(raw)
    reconstructed = pro28_receipts.reconstruct(raw)
    live_context = pro28_receipts.verify_live_chain_context(raw, rpc_url)
    manifest = pro28_receipts._json(raw / "deployment-manifest.json")
    capture_metadata = pro28_receipts._json(raw / "capture-metadata.json")
    runtime = pro28_receipts._json(raw / "runtime-code-observations.json")
    runtime_allocator = runtime.get("contracts", {}).get("allocator", {})
    if _norm(runtime_allocator.get("address", "")) != _norm(manifest["contracts"]["allocator"]):
        raise pro28_receipts.EvidenceError("allocator runtime identity is not bound to the deployment manifest")
    claim_hashes = {claim["claimId"]: _claim_identity_hash(claim["claimId"]) for claim in CLAIMS}
    boundaries = _claim_boundaries(raw, reconstructed)
    snapshots = [_snapshot(rpc_url, manifest, row, claim_hashes) for row in boundaries]
    _validate_timeline(snapshots)
    fixture = pro28_receipts._json(raw / "proof-fixture.json")
    public_values = pro28_receipts._words(fixture["public_values"])
    expected_roots = {
        "allocationBindingRoot": public_values[3],
        "descriptorRoot": public_values[4],
        "evmEntitlementTableRoot": public_values[5],
        "allocationClaimRoot": public_values[8],
    }
    for snapshot in snapshots:
        state = snapshot["state"]
        if any(_norm(state[key]) != _norm(value) for key, value in expected_roots.items()):
            raise pro28_receipts.EvidenceError(f"allocator operation proof roots differ at {snapshot['id']}")
        if _norm(state["token"]) != _norm(manifest["contracts"]["token"]):
            raise pro28_receipts.EvidenceError(f"allocator operation token differs at {snapshot['id']}")
    operation_roots = {key: snapshots[0]["state"][key] for key in (
        "allocationBindingRoot", "descriptorRoot", "evmEntitlementTableRoot", "allocationClaimRoot")}
    final = snapshots[-1]["state"]
    report = {
        "schema": "eae.pro-30-allocator-state-observations.v1",
        "classification": "localOnly provider-relative block-hash-pinned eth_call observations; no state proof, public-chain consensus or finality claim",
        "deploymentManifestDigest": manifest["manifestDigest"],
        "captureId": capture_metadata["captureId"],
        "sourceRevision": capture_metadata.get("source", {}).get("revision"),
        "sourceReplayDirectory": f"{raw.parent.name}/{raw.name}",
        "chainId": manifest["chainId"],
        "localChainIdentity": live_context["localChainIdentity"],
        "operation": {
            "operationId": manifest["operationId"],
            "preparedOperationRoot": manifest["preparedOperationRoot"],
            "allocator": manifest["contracts"]["allocator"],
            "allocatorRuntimeCodeSha256": runtime_allocator["sha256"].lower(),
            "allocatorRuntimeCodeKeccak256": runtime_allocator["keccak256"].lower(),
            "operationRoots": operation_roots,
        },
        "claimIdentityRule": "keccak256(0x20 || minimal-unsigned-varint(UTF8-byte-length) || UTF8 claim ID), matching pinned ProductionProRataAllocatorV2 source",
        "claimIdentities": [{**claim, "canonicalHash": claim_hashes[claim["claimId"]]} for claim in CLAIMS],
        "snapshots": snapshots,
        "replayProbe": {
            "kind": "eth_call",
            "claimId": "claim-a",
            "transactionHash": next(event["receipt"]["transactionHash"] for event in reconstructed["economicEvents"]
                                     if event.get("kind") == "entitlementClaim" and event.get("claimId") == "claim-a"),
            "blockNumber": next(event["receipt"]["blockNumber"] for event in reconstructed["economicEvents"]
                                 if event.get("kind") == "entitlementClaim" and event.get("claimId") == "claim-a"),
            "result": "not yet checked",
        },
        "accounting": {
            "fundedEntitlement": 70,
            "finalClaimedAmount": final["claimedAmount"],
            "finalRemainingFundedLiability": final["outstandingLiability"],
            "finalAllocatorTokenBalance": final["allocatorTokenBalance"],
            "sumClaimedAndRemaining": final["claimedAmount"] + final["outstandingLiability"],
            "claimsMatchConsumedBitmapAndIdentityMapping": final["consumedByIndex"] == [True, True, True]
                and final["consumedClaimIdentity"] == [True, True, True],
        },
        "verification": {
            "historicalReadsUseEip1898RequireCanonical": True,
            "transactionBoundaryRule": "each sampled block contains exactly one transaction; same-block intermediate states reject",
            "liveReceiptAndRuntimeContext": "rechecked through PRO-28 live-chain verifier",
            "stateProof": "not provided; observations are read from the same local RPC",
        },
        "limitations": [
            "The RPC provider supplies block headers, receipts, runtime identity, and historical eth_call results; this is provider-relative evidence.",
            "Private packed bitmap storage is not decoded. The public consumed(operationId,index) getter and public consumedClaimIdentity mapping are queried instead.",
            "The replay probe is an eth_call rejection and is not a submitted replay transaction.",
            "No public-chain qualification, consensus proof, or finality claim is made.",
        ],
    }
    # Probe the already-consumed Alice claim at its exact post-claim block.
    alice_event = next(event for event in reconstructed["economicEvents"]
                       if event.get("kind") == "entitlementClaim" and event.get("claimId") == "claim-a")
    transactions, _ = pro28_receipts._load_receipts(raw)
    alice = transactions[_norm(alice_event["receipt"]["transactionHash"])]
    tx = alice["transaction"]["transaction"]
    probe_ref = {"blockHash": _norm(alice["receipt"]["blockHash"]), "requireCanonical": True}
    request = urllib.request.Request(rpc_url, data=json.dumps({"jsonrpc": "2.0", "id": 1, "method": "eth_call",
        "params": [{"from": tx["from"], "to": tx["to"], "data": tx["input"]}, probe_ref]}).encode(),
        headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            probe_response = json.loads(response.read())
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as exc:
        raise pro28_receipts.EvidenceError("live RPC failed during the consumed-claim replay probe") from exc
    if "error" not in probe_response:
        raise pro28_receipts.EvidenceError("already-consumed claim eth_call unexpectedly succeeded")
    replay_rejection = _assert_replay_rejected(probe_response)
    report["replayProbe"].update({"result": "rejected", **replay_rejection})
    return report


def verify(raw: Path, observations_path: Path, rpc_url: str) -> dict[str, Any]:
    supplied = json.loads(Path(observations_path).read_text())
    if supplied.get("schema") != "eae.pro-30-allocator-state-observations.v1":
        raise pro28_receipts.EvidenceError("unexpected allocator-state observation schema")
    _validate_timeline(supplied.get("snapshots", []))
    regenerated = capture(raw, rpc_url)
    if supplied != regenerated:
        raise pro28_receipts.EvidenceError("allocator-state observations differ from independent live reconstruction")
    return regenerated


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw", type=Path, help="retained local-only PRO-28 V2 raw evidence bundle")
    parser.add_argument("--rpc-url", required=True, help="same live local Anvil RPC used for the replay")
    parser.add_argument("--write", type=Path, help="capture observations to a new path")
    parser.add_argument("--verify", type=Path, help="independently regenerate and compare observations")
    args = parser.parse_args()
    try:
        if args.write and args.verify:
            parser.error("choose --write or --verify")
        report = verify(args.raw, args.verify, args.rpc_url) if args.verify else capture(args.raw, args.rpc_url)
        if args.write:
            if args.write.exists():
                raise pro28_receipts.EvidenceError("refusing to overwrite an existing observation file")
            args.write.parent.mkdir(parents=True, exist_ok=True)
            args.write.write_text(json.dumps(report, sort_keys=True, indent=2) + "\n")
        print("PASS: allocator state independently reconstructed from live RPC" if args.verify
              else "PASS: allocator state captured from live RPC")
        print(f"snapshots={len(report['snapshots'])}")
        print(f"claimed={report['accounting']['finalClaimedAmount']} liability={report['accounting']['finalRemainingFundedLiability']}")
        print(f"replay_probe={report['replayProbe']['result']}")
        if args.write:
            print(f"observations={args.write}")
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        parser.exit(1, f"FAIL: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
