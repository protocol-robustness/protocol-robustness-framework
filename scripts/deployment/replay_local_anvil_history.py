#!/usr/bin/env python3
"""Replay a retained local Anvil transaction history and verify every block hash.

The target must be a fresh Anvil instance started with the genesis settings in
the replay bundle. This is a local evidence utility; it does not connect to a
public chain or carry signing keys (the transactions are already signed).
"""

from __future__ import annotations

import argparse
import gzip
import json
import urllib.request
from pathlib import Path
from typing import Any


class Rpc:
    def __init__(self, url: str):
        self.url = url
        self.request_id = 0

    def call(self, method: str, params: list[Any]) -> Any:
        self.request_id += 1
        body = json.dumps({
            "jsonrpc": "2.0",
            "id": self.request_id,
            "method": method,
            "params": params,
        }).encode()
        request = urllib.request.Request(
            self.url, body, {"content-type": "application/json"}
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            result = json.load(response)
        if "error" in result:
            raise RuntimeError(f"{method}: {result['error']}")
        if result.get("id") != self.request_id:
            raise RuntimeError(f"{method}: JSON-RPC response id mismatch")
        return result.get("result")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--rpc-url", required=True, help="Fresh local Anvil RPC")
    args = parser.parse_args()

    opener = gzip.open if args.bundle.suffix == ".gz" else open
    with opener(args.bundle, "rt", encoding="utf-8") as source:
        bundle = json.load(source)
    if bundle.get("schema") != "prf-local-anvil-history-replay.v1":
        raise SystemExit("unsupported history replay bundle schema")

    rpc = Rpc(args.rpc_url)
    chain_id = int(rpc.call("eth_chainId", []), 16)
    if chain_id != bundle["chainId"]:
        raise SystemExit(f"wrong target chain ID: {chain_id}")
    genesis = rpc.call("eth_getBlockByNumber", ["0x0", False])
    expected_genesis = bundle["genesis"]
    for field in ("hash", "timestamp", "gasLimit", "baseFeePerGas"):
        if str(genesis.get(field, "")).lower() != str(expected_genesis[field]).lower():
            raise SystemExit(f"target genesis {field} mismatch")

    rpc.call("evm_setAutomine", [False])
    matched = 0
    transaction_count = 0
    for block in bundle["blocks"]:
        number = block["number"]
        head = int(rpc.call("eth_blockNumber", []), 16)
        if head != number - 1:
            raise SystemExit(f"target head {head} is not before replay block {number}")
        rpc.call("evm_setNextBlockTimestamp", [int(block["timestamp"], 16)])
        for transaction in block["transactions"]:
            submitted = rpc.call("eth_sendRawTransaction", [transaction["raw"]])
            if submitted.lower() != transaction["hash"].lower():
                raise SystemExit(f"transaction hash mismatch in block {number}")
            transaction_count += 1
        rpc.call("evm_mine", [])
        actual = rpc.call("eth_getBlockByNumber", [hex(number), False])
        checks = {
            "hash": block["hash"],
            "parentHash": block["parentHash"],
            "timestamp": block["timestamp"],
            "transactions": [tx["hash"] for tx in block["transactions"]],
        }
        for field, expected in checks.items():
            observed = actual[field]
            if isinstance(expected, list):
                equal = [value.lower() for value in observed] == [value.lower() for value in expected]
            else:
                equal = str(observed).lower() == str(expected).lower()
            if not equal:
                raise SystemExit(f"replayed block {number} {field} mismatch")
        matched += 1

    head = rpc.call("eth_blockNumber", [])
    result = {
        "schema": "prf-local-anvil-history-replay-result.v1",
        "qualification": "local-history-replayed-not-deployment-approval",
        "chainId": chain_id,
        "matchedBlocks": matched,
        "matchedTransactions": transaction_count,
        "head": head,
        "block128Hash": rpc.call("eth_getBlockByNumber", ["0x80", False])["hash"],
    }
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
