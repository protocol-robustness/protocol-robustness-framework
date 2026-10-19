"""Capture and independently recheck transaction-level ERC-20 accounting.

This local-only profile supplements PRO-28 receipt reconstruction. It compares
every retained token Transfer log with an RPC-wide log sweep and checks the
per-transaction balanceOf/totalSupply changes at canonical block boundaries.
It deliberately rejects multiple transactions in a block because end-of-block
eth_call cannot distinguish their intermediate states.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from forensic import pro28_receipts


TRANSFER_TOPIC = pro28_receipts.TRANSFER_TOPIC
ZERO = "0x" + "00" * 20
BALANCE_OF_SELECTOR = "70a08231"
TOTAL_SUPPLY_SELECTOR = "18160ddd"
DECIMALS_SELECTOR = "313ce567"


def _norm(value: str) -> str:
    return value.lower()


def _quantity(value: int) -> str:
    return hex(value)


def _int(value: str | int) -> int:
    return int(value, 16) if isinstance(value, str) and value.startswith("0x") else int(value)


def _word(data: str, label: str) -> int:
    if not isinstance(data, str) or not data.startswith("0x") or len(data) != 66:
        raise pro28_receipts.EvidenceError(f"{label} must return one ABI word")
    try:
        return int(data[2:], 16)
    except ValueError as exc:
        raise pro28_receipts.EvidenceError(f"{label} returned non-hex data") from exc


def _address_word(address: str) -> str:
    pro28_receipts._hex_bytes(address, 20, "token-account address")
    return address.removeprefix("0x").lower().rjust(64, "0")


def _transfer_rows_from_receipts(raw: Path) -> list[dict[str, Any]]:
    transactions, _ = pro28_receipts._load_receipts(raw)
    token = _norm(pro28_receipts._json(raw / "deployment-manifest.json")["contracts"]["token"])
    rows: list[dict[str, Any]] = []
    for tx_hash, entry in transactions.items():
        receipt = entry["receipt"]
        for log in receipt.get("logs", []):
            topics = log.get("topics", [])
            if _norm(log.get("address", "")) != token or not topics or _norm(topics[0]) != TRANSFER_TOPIC:
                continue
            if len(topics) != 3:
                raise pro28_receipts.EvidenceError("token Transfer has malformed topic count")
            from_word = pro28_receipts._hex_bytes(topics[1], 32, "Transfer from topic")
            to_word = pro28_receipts._hex_bytes(topics[2], 32, "Transfer to topic")
            if from_word[:12] != bytes(12) or to_word[:12] != bytes(12):
                raise pro28_receipts.EvidenceError("Transfer address topic is not canonically padded")
            amount = int.from_bytes(pro28_receipts._hex_bytes(log.get("data"), 32, "Transfer amount"), "big")
            rows.append({
                "transactionHash": tx_hash,
                "chainId": pro28_receipts._int(entry["transaction"]["transaction"]["chainId"]),
                "blockNumber": _int(receipt["blockNumber"]),
                "blockHash": _norm(receipt["blockHash"]),
                "transactionIndex": _int(receipt["transactionIndex"]),
                "logIndex": _int(log["logIndex"]),
                "token": token,
                "from": "0x" + from_word[12:].hex(),
                "to": "0x" + to_word[12:].hex(),
                "amount": amount,
            })
    return sorted(rows, key=lambda row: (row["blockNumber"], row["transactionIndex"], row["logIndex"]))


def _rpc_transfer_rows(rpc_url: str, token: str, chain_id: int, start: int, end: int) -> list[dict[str, Any]]:
    logs = pro28_receipts._rpc(rpc_url, "eth_getLogs", [{
        "address": token,
        "fromBlock": _quantity(start),
        "toBlock": _quantity(end),
        "topics": [TRANSFER_TOPIC],
    }])
    if not isinstance(logs, list):
        raise pro28_receipts.EvidenceError("eth_getLogs did not return a list")
    rows = []
    for log in logs:
        normalized = pro28_receipts._normalize_log(log)
        if normalized["removed"]:
            raise pro28_receipts.EvidenceError("chain-wide Transfer sweep contains a removed log")
        topics = normalized["topics"]
        if len(topics) != 3:
            raise pro28_receipts.EvidenceError("chain-wide token Transfer has malformed topic count")
        from_word = bytes.fromhex(topics[1][2:])
        to_word = bytes.fromhex(topics[2][2:])
        if len(from_word) != 32 or len(to_word) != 32 or from_word[:12] != bytes(12) or to_word[:12] != bytes(12):
            raise pro28_receipts.EvidenceError("chain-wide Transfer address topic is malformed")
        amount = int.from_bytes(pro28_receipts._hex_bytes(normalized["data"], 32, "Transfer amount"), "big")
        rows.append({
            "transactionHash": normalized["transactionHash"],
            "chainId": chain_id,
            "blockNumber": normalized["blockNumber"],
            "blockHash": normalized["blockHash"],
            "transactionIndex": normalized["transactionIndex"],
            "logIndex": normalized["logIndex"],
            "token": _norm(normalized["address"]),
            "from": "0x" + from_word[12:].hex(),
            "to": "0x" + to_word[12:].hex(),
            "amount": amount,
        })
    return sorted(rows, key=lambda row: (row["blockNumber"], row["transactionIndex"], row["logIndex"]))


def _assert_transfer_coverage(receipt_rows: list[dict[str, Any]], rpc_rows: list[dict[str, Any]]) -> None:
    if receipt_rows != rpc_rows:
        raise pro28_receipts.EvidenceError(
            "chain-wide Transfer sweep differs from all retained transaction receipts"
        )


def _validate_transaction_deltas(
    events: list[dict[str, Any]],
    balances_before: dict[str, int],
    balances_after: dict[str, int],
    total_supply_before: int,
    total_supply_after: int,
) -> dict[str, int]:
    """Require observed per-address and supply deltas to equal emitted events."""
    accounts = set(balances_before)
    if set(balances_after) != accounts:
        raise pro28_receipts.EvidenceError("before/after balance snapshots cover different accounts")
    expected = {account: 0 for account in accounts}
    supply_delta = 0
    for event in events:
        source, target, amount = event["from"], event["to"], event["amount"]
        if not isinstance(amount, int) or amount < 0:
            raise pro28_receipts.EvidenceError("Transfer amount must be a nonnegative integer")
        if source != ZERO:
            if source not in expected:
                raise pro28_receipts.EvidenceError("Transfer source is absent from balance observations")
            expected[source] -= amount
        else:
            supply_delta += amount
        if target != ZERO:
            if target not in expected:
                raise pro28_receipts.EvidenceError("Transfer recipient is absent from balance observations")
            expected[target] += amount
        else:
            supply_delta -= amount
    actual = {}
    for account in accounts:
        before, after = balances_before[account], balances_after[account]
        if not isinstance(before, int) or not isinstance(after, int) or before < 0 or after < 0:
            raise pro28_receipts.EvidenceError("balance snapshots must be nonnegative integers")
        actual[account] = after - before
    if actual != expected:
        raise pro28_receipts.EvidenceError("balance deltas do not reconcile to Transfer events")
    if total_supply_after - total_supply_before != supply_delta:
        raise pro28_receipts.EvidenceError("totalSupply delta does not reconcile to mint/burn events")
    return actual


def _call_word(rpc_url: str, token: str, calldata: str, block_ref: dict[str, Any], label: str) -> int:
    result = pro28_receipts._rpc(rpc_url, "eth_call", [{"to": token, "data": calldata}, block_ref])
    return _word(result, label)


def capture(raw: Path, rpc_url: str) -> dict[str, Any]:
    """Re-read all transaction boundaries and complete Transfer coverage."""
    raw = Path(raw)
    pro28_receipts.reconstruct(raw)
    live_context = pro28_receipts.verify_live_chain_context(raw, rpc_url)
    manifest = pro28_receipts._json(raw / "deployment-manifest.json")
    capture_metadata = pro28_receipts._json(raw / "capture-metadata.json")
    transactions, _ = pro28_receipts._load_receipts(raw)
    token = _norm(manifest["contracts"]["token"])
    ordered = sorted(transactions.items(), key=lambda pair: (
        _int(pair[1]["receipt"]["blockNumber"]), _int(pair[1]["receipt"]["transactionIndex"])
    ))
    blocks = [_int(entry["receipt"]["blockNumber"]) for _, entry in ordered]
    if len(blocks) != len(set(blocks)):
        raise pro28_receipts.EvidenceError("per-transaction snapshots require one transaction per block")
    start, end = min(blocks), max(blocks)
    if set(blocks) != set(range(start, end + 1)):
        raise pro28_receipts.EvidenceError("captured execution has an unobserved block between transaction boundaries")
    tx_blocks: dict[int, dict[str, Any]] = {}
    for number in blocks:
        block = pro28_receipts._block_by_number(rpc_url, number)
        txs = pro28_receipts._rpc(rpc_url, "eth_getBlockByNumber", [_quantity(number), True]).get("transactions", [])
        if len(txs) != 1:
            raise pro28_receipts.EvidenceError(f"block {number} has {len(txs)} transactions; intermediate balances are unavailable")
        receipt_entry = next(entry for _, entry in ordered if _int(entry["receipt"]["blockNumber"]) == number)
        if (_norm(txs[0].get("hash", "")) != _norm(receipt_entry["receipt"]["transactionHash"])
                or _int(receipt_entry["receipt"]["transactionIndex"]) != 0):
            raise pro28_receipts.EvidenceError(f"block {number} transaction does not match retained receipt at index zero")
        tx_blocks[number] = block

    receipt_transfers = _transfer_rows_from_receipts(raw)
    rpc_transfers = _rpc_transfer_rows(rpc_url, token, manifest["chainId"], start, end)
    _assert_transfer_coverage(receipt_transfers, rpc_transfers)

    deployment_events = [row for row in receipt_transfers if row["from"] == ZERO]
    token_deployments = [row for row in deployment_events if row["to"] != ZERO]
    if len(token_deployments) != 1:
        raise pro28_receipts.EvidenceError("expected exactly one local token mint/deployment event")
    deployment_block = token_deployments[0]["blockNumber"]

    known = {
        _norm(manifest["deployer"]),
        _norm(manifest["contracts"]["reserve"]),
        _norm(manifest["contracts"]["allocator"]),
        *(_norm(address) for address in pro28_receipts.RECIPIENTS),
        *(row["from"] for row in receipt_transfers if row["from"] != ZERO),
        *(row["to"] for row in receipt_transfers if row["to"] != ZERO),
    }
    accounts = sorted(known)
    before_by_account = {address: 0 for address in accounts}
    supply_before = 0
    tx_observations: list[dict[str, Any]] = []
    transfers_by_tx: dict[str, list[dict[str, Any]]] = {}
    for event in receipt_transfers:
        transfers_by_tx.setdefault(event["transactionHash"], []).append(event)

    for tx_hash, entry in ordered:
        receipt = entry["receipt"]
        number = _int(receipt["blockNumber"])
        block = tx_blocks[number]
        before_number = number - 1
        before_block = pro28_receipts._block_by_number(rpc_url, before_number) if before_number >= 0 else None
        before_ref = {"blockHash": before_block["hash"], "requireCanonical": True} if before_block else None
        after_ref = {"blockHash": block["hash"], "requireCanonical": True}
        code_before = (pro28_receipts._rpc(rpc_url, "eth_getCode", [token, before_ref])
                       if before_ref else "0x")
        code_after = pro28_receipts._rpc(rpc_url, "eth_getCode", [token, after_ref])
        if number < deployment_block and code_after != "0x":
            raise pro28_receipts.EvidenceError("token code appeared before its retained deployment event")
        if number >= deployment_block and code_after == "0x":
            raise pro28_receipts.EvidenceError("token code is absent after its retained deployment event")
        if code_before == "0x":
            before_balances = {address: 0 for address in accounts}
            before_supply = 0
        else:
            if before_ref is None:
                raise pro28_receipts.EvidenceError("deployed token lacks a preceding canonical block")
            before_balances = {
                address: _call_word(rpc_url, token, "0x" + BALANCE_OF_SELECTOR + _address_word(address), before_ref, "balanceOf(before)")
                for address in accounts
            }
            before_supply = _call_word(rpc_url, token, "0x" + TOTAL_SUPPLY_SELECTOR, before_ref, "totalSupply(before)")
        if code_after == "0x":
            after_balances = {address: 0 for address in accounts}
            after_supply = 0
        else:
            after_balances = {
                address: _call_word(rpc_url, token, "0x" + BALANCE_OF_SELECTOR + _address_word(address), after_ref, "balanceOf(after)")
                for address in accounts
            }
            after_supply = _call_word(rpc_url, token, "0x" + TOTAL_SUPPLY_SELECTOR, after_ref, "totalSupply(after)")

        row_events = transfers_by_tx.get(tx_hash, [])
        try:
            actual_delta = _validate_transaction_deltas(
                row_events, before_balances, after_balances, before_supply, after_supply
            )
        except pro28_receipts.EvidenceError as exc:
            raise pro28_receipts.EvidenceError(f"{exc} for {tx_hash}") from exc
        tx_observations.append({
            "transactionHash": tx_hash,
            "blockNumber": number,
            "blockHash": _norm(block["hash"]),
            "parentHash": _norm(block["parentHash"]),
            "transactionIndex": _int(receipt["transactionIndex"]),
            "beforeBlock": {"number": before_number, "hash": _norm(before_block["hash"])} if before_block else None,
            "afterBlock": {"number": number, "hash": _norm(block["hash"])},
            "tokenCodePresentBefore": code_before != "0x",
            "tokenCodePresentAfter": code_after != "0x",
            "totalSupplyBefore": before_supply,
            "totalSupplyAfter": after_supply,
            "balancesBefore": before_balances,
            "balancesAfter": after_balances,
            "balanceDeltas": actual_delta,
            "transferEvents": row_events,
        })
        before_by_account = after_balances
        supply_before = after_supply

    latest_ref = {"blockHash": _norm(tx_blocks[end]["hash"]), "requireCanonical": True}
    decimals = _call_word(rpc_url, token, "0x" + DECIMALS_SELECTOR, latest_ref, "decimals")
    runtime = pro28_receipts._json(raw / "runtime-code-observations.json")["contracts"]["token"]
    report = {
        "schema": "eae.pro-29-token-accounting-observations.v1",
        "classification": "localOnly provider-relative Anvil observation; not public-chain finality or consensus proof",
        "deploymentManifestDigest": manifest["manifestDigest"],
        "captureId": capture_metadata["captureId"],
        "sourceRevision": capture_metadata.get("source", {}).get("revision"),
        "chainId": manifest["chainId"],
        "localChainIdentity": live_context["localChainIdentity"],
        "token": {
            "address": token,
            "runtimeCodeSha256": runtime["sha256"].lower(),
            "runtimeCodeKeccak256": runtime["keccak256"].lower(),
            "decimals": decimals,
            "behaviorScope": "Pinned local token runtime only; no fee-on-transfer, rebasing, native-value, or arbitrary-token claim.",
        },
        "coverage": {
            "executionTransactions": len(ordered),
            "transactionsWithUniqueBlockBoundary": len(tx_observations),
            "transferLogsInReceipts": len(receipt_transfers),
            "transferLogsFromRpcSweep": len(rpc_transfers),
            "transferReceiptCoverage": "complete within captured blocks for this RPC",
            "fromBlock": start,
            "toBlock": end,
            "checkedAccounts": accounts,
            "allBalanceDeltasMatchTransferLogs": True,
            "supplyDeltasMatchMintBurnLogs": True,
        },
        "transactions": tx_observations,
        "finalBalances": before_by_account,
        "finalTotalSupply": supply_before,
        "limitations": [
            "The RPC provider supplies both historical state reads and eth_getLogs; this is provider-relative evidence.",
            "Intermediate same-block states are unsupported and cause rejection rather than inferred ordering.",
            "Only the pinned local token implementation is covered; fee-on-transfer, rebasing and arbitrary token behavior are excluded.",
            "This is not a public-chain finality or consensus proof.",
        ],
    }
    return report


def verify(raw: Path, observations_path: Path, rpc_url: str) -> dict[str, Any]:
    supplied = json.loads(Path(observations_path).read_text())
    regenerated = capture(raw, rpc_url)
    if supplied != regenerated:
        raise pro28_receipts.EvidenceError("token accounting observations differ from independent live reconstruction")
    return regenerated


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw", type=Path, help="retained local-only PRO-28 raw evidence bundle")
    parser.add_argument("--rpc-url", required=True, help="same live local RPC used for the capture")
    parser.add_argument("--write", type=Path, help="capture observations to this new path")
    parser.add_argument("--verify", type=Path, help="independently regenerate and compare this observations file")
    args = parser.parse_args()
    try:
        if args.write and args.verify:
            parser.error("choose --write or --verify")
        report = (verify(args.raw, args.verify, args.rpc_url) if args.verify
                  else capture(args.raw, args.rpc_url))
        if args.write:
            if args.write.exists():
                raise pro28_receipts.EvidenceError("refusing to overwrite an existing observation file")
            args.write.parent.mkdir(parents=True, exist_ok=True)
            args.write.write_text(json.dumps(report, sort_keys=True, indent=2) + "\n")
        print("PASS: token accounting observations independently reconstructed from live RPC" if args.verify
              else "PASS: token accounting observations captured from live RPC")
        print(f"transactions={report['coverage']['executionTransactions']}")
        print(f"transfer_logs={report['coverage']['transferLogsFromRpcSweep']}")
        if args.write:
            print(f"observations={args.write}")
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        parser.exit(1, f"FAIL: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
