"""Replay retained signed Gate E transactions on a fresh local Anvil.

This local-only utility preserves signed transaction identities while creating
new receipts, runtime observations, and V2 chain-identity capture metadata.
It does not generate or independently verify an SP1 proof.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlparse
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from forensic import pro28_receipts


GROUPS = pro28_receipts.GROUPS
LOCAL_CHAIN_ID = 31337
FIRST_NONCE = 0x4E


def _json(path: Path) -> Any:
    return json.loads(path.read_text())


def _quantity(value: int) -> str:
    return hex(value)


def _int(value: str | int) -> int:
    if isinstance(value, int):
        return value
    return int(value, 16) if value.startswith("0x") else int(value)


def _hex_bytes(value: str | None) -> bytes:
    if value is None:
        return b""
    if not isinstance(value, str) or not value.startswith("0x"):
        raise ValueError("transaction byte fields must be 0x-prefixed hex")
    return bytes.fromhex(value[2:])


def _int_bytes(value: int) -> bytes:
    if value < 0:
        raise ValueError("RLP integers cannot be negative")
    return b"" if value == 0 else value.to_bytes((value.bit_length() + 7) // 8, "big")


def _rlp(value: bytes | list[Any]) -> bytes:
    if isinstance(value, list):
        payload = b"".join(_rlp(item) for item in value)
        if len(payload) <= 55:
            return bytes([0xC0 + len(payload)]) + payload
        length = _int_bytes(len(payload))
        return bytes([0xF7 + len(length)]) + length + payload
    if len(value) == 1 and value[0] < 0x80:
        return value
    if len(value) <= 55:
        return bytes([0x80 + len(value)]) + value
    length = _int_bytes(len(value))
    return bytes([0xB7 + len(length)]) + length + value


def _access_list(rows: list[dict[str, Any]]) -> list[Any]:
    return [
        [_hex_bytes(row["address"]), [_hex_bytes(key) for key in row.get("storageKeys", [])]]
        for row in rows
    ]


def signed_raw_transaction(tx: dict[str, Any]) -> bytes:
    """Reconstruct an EIP-1559 signed envelope from retained RPC fields."""
    if _int(tx.get("type", "0x0")) != 2:
        raise ValueError("only retained EIP-1559 transactions are supported")
    fields = [
        _int_bytes(_int(tx["chainId"])),
        _int_bytes(_int(tx["nonce"])),
        _int_bytes(_int(tx["maxPriorityFeePerGas"])),
        _int_bytes(_int(tx["maxFeePerGas"])),
        _int_bytes(_int(tx["gas"])),
        _hex_bytes(tx.get("to")),
        _int_bytes(_int(tx.get("value", "0x0"))),
        _hex_bytes(tx.get("input", "0x")),
        _access_list(tx.get("accessList", [])),
        _int_bytes(_int(tx.get("yParity", tx["v"]))),
        _int_bytes(_int(tx["r"])),
        _int_bytes(_int(tx["s"])),
    ]
    return b"\x02" + _rlp(fields)


def _keccak(raw: bytes) -> str:
    result = subprocess.run(
        ["cast", "keccak", "0x" + raw.hex()], capture_output=True, text=True, check=False
    )
    if result.returncode != 0:
        raise RuntimeError("cast keccak failed; Foundry cast is required")
    return result.stdout.strip().lower()


def _rpc(rpc_url: str, method: str, params: list[Any]) -> Any:
    import urllib.request

    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    request = urllib.request.Request(
        rpc_url, data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        result = json.loads(response.read())
    if result.get("error") or "result" not in result:
        raise RuntimeError(f"RPC {method} failed: {result.get('error')}")
    return result["result"]


def _write(path: Path, payload: Any) -> None:
    path.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n")


def _replay_transactions(source: Path, rpc_url: str) -> dict[str, dict[str, Any]]:
    rows: list[tuple[str, dict[str, Any]]] = []
    for group in GROUPS:
        payload = _json(source / "broadcast" / f"{group}.json")
        if payload.get("schema") != "eae.rpc-bound-broadcast.v1":
            raise ValueError(f"{group} source is not an RPC-bound broadcast")
        rows.extend((group, row) for row in payload["transactions"])

    rows.sort(key=lambda pair: _int(pair[1]["transaction"]["nonce"]))
    if not rows:
        raise ValueError("source bundle has no signed transactions")
    sender = rows[0][1]["transaction"]["from"].lower()
    expected_nonce = FIRST_NONCE
    replayed: dict[str, dict[str, Any]] = {group: {"transactions": [], "receipts": []} for group in GROUPS}
    for group, row in rows:
        tx = row["transaction"]
        if tx["from"].lower() != sender or _int(tx["nonce"]) != expected_nonce:
            raise ValueError("signed transaction sender or nonce sequence is not contiguous")
        if _int(tx["chainId"]) != LOCAL_CHAIN_ID:
            raise ValueError("refusing a transaction not signed for local chain ID 31337")
        raw = signed_raw_transaction(tx)
        expected_hash = row["hash"].lower()
        if _keccak(raw) != expected_hash:
            raise ValueError(f"reconstructed signed transaction hash differs at nonce {expected_nonce}")
        sent_hash = _rpc(rpc_url, "eth_sendRawTransaction", ["0x" + raw.hex()]).lower()
        if sent_hash != expected_hash:
            raise ValueError("local node returned a different transaction hash")
        receipt = None
        for _ in range(80):
            receipt = _rpc(rpc_url, "eth_getTransactionReceipt", [expected_hash])
            if receipt is not None:
                break
            time.sleep(0.25)
        if not isinstance(receipt, dict) or _int(receipt.get("status", "0x0")) != 1:
            raise RuntimeError(f"local transaction failed or has no receipt: {expected_hash}")
        live_tx = _rpc(rpc_url, "eth_getTransactionByHash", [expected_hash])
        if not isinstance(live_tx, dict) or _int(live_tx.get("chainId", "-1")) != LOCAL_CHAIN_ID:
            raise RuntimeError(f"local transaction identity is unavailable: {expected_hash}")
        replayed[group]["transactions"].append({"hash": expected_hash, "transaction": live_tx})
        replayed[group]["receipts"].append(receipt)
        expected_nonce += 1
    if len(rows) != 41:
        raise ValueError(f"expected 41 signed transactions, found {len(rows)}")
    return replayed


def _runtime_observations(source: Path, rpc_url: str, block: dict[str, Any]) -> dict[str, Any]:
    manifest = _json(source / "deployment-manifest.json")
    original = _json(source / "runtime-code-observations.json")
    rows: dict[str, Any] = {}
    block_ref = {"blockHash": block["hash"], "requireCanonical": True}
    for name, address in manifest["contracts"].items():
        code = _rpc(rpc_url, "eth_getCode", [address, block_ref])
        if not isinstance(code, str) or len(code) <= 2 or len(code[2:]) % 2:
            raise RuntimeError(f"runtime code missing for {name}")
        body = bytes.fromhex(code[2:])
        sha256 = "0x" + hashlib.sha256(body).hexdigest()
        keccak = _keccak(body)
        prior = original["contracts"][name]
        if sha256 != prior["sha256"].lower() or keccak != prior["keccak256"].lower():
            raise RuntimeError(f"replayed deployed runtime differs from retained identity for {name}")
        rows[name] = {
            **{key: value for key, value in prior.items() if key not in {"runtimeCode", "runtimeCodeBytes", "sha256", "keccak256"}},
            "address": address,
            "runtimeCode": code,
            "runtimeCodeBytes": len(body),
            "sha256": sha256,
            "keccak256": keccak,
        }
    return {
        "schema": "eae.local-runtime-code-observations.v1",
        "classification": "localOnly Anvil RPC observation; not a public-chain attestation",
        "chainId": LOCAL_CHAIN_ID,
        "blockNumber": _int(block["number"]),
        "blockHash": block["hash"].lower(),
        "parentHash": block["parentHash"].lower(),
        "contracts": rows,
    }


def replay(source: Path, output: Path, rpc_url: str) -> Path:
    host = urlparse(rpc_url).hostname
    if host not in {"localhost", "127.0.0.1", "::1"}:
        raise ValueError("PRO-31 capture replay is restricted to a loopback RPC URL")
    source = source.resolve()
    output = output.resolve()
    if not source.is_dir() or output.exists():
        raise ValueError("source bundle must exist and output directory must not exist")
    if _int(_rpc(rpc_url, "eth_chainId", [])) != LOCAL_CHAIN_ID:
        raise ValueError("RPC chain ID must be 31337")
    try:
        _rpc(rpc_url, "anvil_nodeInfo", [])
    except Exception as exc:
        raise ValueError("RPC must be a local Anvil instance") from exc
    if _int(_rpc(rpc_url, "eth_blockNumber", [])) != 0:
        raise ValueError("local Anvil must be fresh at block zero")

    identity_block = _rpc(rpc_url, "eth_getBlockByNumber", ["0x0", False])
    genesis_hash = identity_block["hash"].lower()
    fingerprint = pro28_receipts.local_chain_fingerprint(LOCAL_CHAIN_ID, genesis_hash)
    first_tx = _json(source / "broadcast" / f"{GROUPS[0]}.json")["transactions"][0]["transaction"]
    sender = first_tx["from"].lower()
    _rpc(rpc_url, "anvil_setNonce", [sender, _quantity(FIRST_NONCE)])

    replayed = _replay_transactions(source, rpc_url)
    latest = _rpc(rpc_url, "eth_getBlockByNumber", ["latest", False])
    runtime = _runtime_observations(source, rpc_url, latest)

    output.mkdir(parents=True, exist_ok=False)
    shutil.copytree(source, output, dirs_exist_ok=True, ignore=shutil.ignore_patterns("live-chain-check.json"))
    for group in GROUPS:
        source_payload = _json(source / "broadcast" / f"{group}.json")
        _write(output / "broadcast" / f"{group}.json", {
            "schema": "eae.rpc-bound-broadcast.v1",
            "sourceBroadcastSha256": source_payload["sourceBroadcastSha256"],
            **replayed[group],
        })
    _write(output / "runtime-code-observations.json", runtime)

    capture = _json(source / "capture-metadata.json")
    capture["schema"] = "eae.pro-28-capture-metadata.v2"
    capture["captureId"] = f"pro-28-local-replay-{fingerprint.removeprefix('sha256:')[:16]}"
    capture["localChainIdentity"] = {
        "schema": "prf.local-chain-fingerprint.v1",
        "chainId": LOCAL_CHAIN_ID,
        "genesisBlockHash": genesis_hash,
        "fingerprint": fingerprint,
        "scope": "chain lineage fingerprint observed before replay; not a unique RPC provider or Anvil process",
    }
    capture["runtimeObservation"] = {
        "blockHash": runtime["blockHash"],
        "codeFetchedAtBlock": runtime["blockNumber"],
        "contractsObserved": len(runtime["contracts"]),
        "path": "runtime-code-observations.json",
    }
    capture["executionOrder"] = [
        "fresh loopback Anvil verified at chain ID 31337 and block zero",
        "local chain ID and genesis block hash captured before replay",
        "41 retained signed EIP-1559 transaction envelopes hash-checked and replayed in contiguous nonce order",
        "all transaction hashes preserved; receipts re-fetched from this local Anvil instance",
        "runtime bytecode re-fetched at the final canonical block and matched to retained identities",
        "historical Foundry log retained as a log observation only; this replay did not rerun SP1 SDK verification",
    ]
    capture["replay"] = (
        "Fresh local Anvil replay of the 41 retained signed transaction envelopes. "
        "Each reconstructed envelope matched its retained transaction hash; receipts and runtime code were fetched "
        "from the same loopback RPC after recording the genesis identity. No public transaction was submitted."
    )
    _write(output / "capture-metadata.json", capture)

    report_path = output.parent.parent / f"{output.name}-reconstruction.json"
    report = pro28_receipts.reconstruct(output)
    _write(report_path, report)
    live_report = pro28_receipts.verify_live_chain_context(output, rpc_url)
    _write(output / "live-chain-check.json", live_report)
    return report_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="retained V1 evidence bundle with signed transactions")
    parser.add_argument("output", type=Path, help="new output directory; must not exist")
    parser.add_argument("--rpc-url", required=True, help="loopback fresh Anvil RPC endpoint")
    args = parser.parse_args()
    try:
        report = replay(args.source, args.output, args.rpc_url)
    except (OSError, ValueError, RuntimeError, KeyError, json.JSONDecodeError) as exc:
        parser.exit(1, f"FAIL: {exc}\n")
    print(f"PASS: replayed 41 signed transactions and wrote V2 capture {args.output}")
    print(f"report: {report}")
    print(f"live chain report: {args.output / 'live-chain-check.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
