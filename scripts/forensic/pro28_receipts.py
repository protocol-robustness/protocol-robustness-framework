"""Reconstruct local protected-withdrawal receipt identities for PRO-28.

The verifier consumes only retained raw deployment/proof/broadcast artifacts.
It never treats a caller-provided verification label as evidence.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
GROUPS = ("deployment", "funding", "activation", "withdrawals")
RECIPIENTS = {
    "0x1111111111111111111111111111111111111111": ("claim-a", 35),
    "0x2222222222222222222222222222222222222222": ("claim-b", 21),
    "0x3333333333333333333333333333333333333333": ("claim-c", 14),
}
EIP1967_IMPLEMENTATION_SLOT = "0x360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbc"
SP1_GROTH16_VERIFY_SELECTOR = "0x41493c60"
SP1_GROTH16_VERIFIER_V6_1_0_KECCAK = "0x728b036f7dbc79d6186237350e02c61bcc1cbe4ff0681ba75b8df14546d0fd71"


class EvidenceError(ValueError):
    """Raised when retained evidence is incomplete or internally inconsistent."""


def _json(path: Path) -> Any:
    try:
        return json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as exc:
        raise EvidenceError(f"cannot read valid JSON: {path.name}") from exc


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _norm(value: str) -> str:
    return value.lower()


def _hex_bytes(value: Any, size: int, label: str) -> bytes:
    if not isinstance(value, str) or not value.startswith("0x") or len(value) != 2 + size * 2:
        raise EvidenceError(f"{label} must be exactly {size} bytes of 0x-prefixed hex")
    if any(char not in "0123456789abcdefABCDEF" for char in value[2:]):
        raise EvidenceError(f"{label} contains non-hex data")
    return bytes.fromhex(value[2:])


def _int(value: str | int) -> int:
    if isinstance(value, int):
        return value
    return int(value, 16) if value.startswith("0x") else int(value)


def _words(public_values: str) -> list[str]:
    encoded = public_values.removeprefix("0x")
    if len(encoded) != 9 * 64:
        raise EvidenceError("public values must contain exactly nine 32-byte words")
    try:
        bytes.fromhex(encoded)
    except ValueError as exc:
        raise EvidenceError("public values contain non-hex data") from exc
    return ["0x" + encoded[i : i + 64].lower() for i in range(0, len(encoded), 64)]


def _load_receipts(raw: Path) -> tuple[dict[str, dict[str, Any]], dict[str, str]]:
    transactions: dict[str, dict[str, Any]] = {}
    source_files: dict[str, str] = {}
    seen_log_indexes: set[tuple[str, int]] = set()
    for group in GROUPS:
        path = raw / "broadcast" / f"{group}.json"
        payload = _json(path)
        tx_rows = payload.get("transactions")
        receipt_rows = payload.get("receipts")
        if not isinstance(tx_rows, list) or not isinstance(receipt_rows, list):
            raise EvidenceError(f"{group} broadcast must contain transactions and receipts")
        if payload.get("schema") == "eae.rpc-bound-broadcast.v1":
            runner_path = raw / "runner-broadcast" / f"{group}.json"
            if not runner_path.is_file():
                raise EvidenceError(f"{group} RPC-bound broadcast lacks its retained runner source")
            expected_source_digest = "sha256:" + _sha256(runner_path.read_bytes())
            if payload.get("sourceBroadcastSha256") != expected_source_digest:
                raise EvidenceError(f"{group} RPC-bound broadcast does not bind its runner source bytes")
            source_files[f"runner-broadcast/{group}.json"] = _sha256(runner_path.read_bytes())
        tx_hashes = [_norm(row["hash"]) for row in tx_rows]
        receipt_hashes = [_norm(row["transactionHash"]) for row in receipt_rows]
        if (
            len(tx_hashes) != len(receipt_hashes)
            or len(tx_hashes) != len(set(tx_hashes))
            or len(receipt_hashes) != len(set(receipt_hashes))
            or set(tx_hashes) != set(receipt_hashes)
        ):
            raise EvidenceError(f"{group} transaction/receipt identities do not match one-to-one")
        for tx_hash in tx_hashes:
            _hex_bytes(tx_hash, 32, "transaction hash")
        source_files[group] = _sha256(path.read_bytes())
        tx_by_hash = {_norm(row["hash"]): row for row in tx_rows}
        for receipt in receipt_rows:
            tx_hash = _norm(receipt["transactionHash"])
            tx = tx_by_hash[tx_hash]
            tx_context = tx.get("transaction", {})
            if _norm(tx.get("hash", "")) != tx_hash:
                raise EvidenceError("broadcast transaction hash mismatch")
            if not isinstance(tx_context, dict) or _norm(tx_context.get("hash", "")) != tx_hash:
                raise EvidenceError("broadcast transaction envelope hash mismatch")
            if _norm(tx_context.get("chainId", "")) != "0x7a69":
                raise EvidenceError("broadcast transaction is not on local chain 31337")
            if receipt.get("status") != "0x1":
                raise EvidenceError(f"transaction {tx_hash} did not succeed")
            for key in ("transactionIndex", "blockNumber", "blockHash"):
                if key not in receipt or receipt[key] is None:
                    raise EvidenceError(f"transaction {tx_hash} receipt lacks {key}")
            _hex_bytes(receipt["blockHash"], 32, "receipt block hash")
            _hex_bytes(tx_context.get("from"), 20, "broadcast transaction sender")
            calldata = tx_context.get("input")
            if (
                not isinstance(calldata, str)
                or not calldata.startswith("0x")
                or len(calldata) % 2 != 0
                or any(char not in "0123456789abcdefABCDEF" for char in calldata[2:])
            ):
                raise EvidenceError("broadcast transaction calldata is malformed")
            target = tx_context.get("to")
            if target is not None:
                _hex_bytes(target, 20, "broadcast transaction target")
            for log in receipt.get("logs", []):
                if log.get("removed") is True:
                    raise EvidenceError(f"transaction {tx_hash} has a removed log")
                if (
                    _norm(log.get("transactionHash", "")) != tx_hash
                    or _norm(log.get("blockHash", "")) != _norm(receipt["blockHash"])
                    or _int(log.get("blockNumber", "-1")) != _int(receipt["blockNumber"])
                    or _int(log.get("transactionIndex", "-1")) != _int(receipt["transactionIndex"])
                ):
                    raise EvidenceError(f"transaction {tx_hash} log context differs from receipt")
                log_identity = (_norm(receipt["blockHash"]), _int(log["logIndex"]))
                if log_identity in seen_log_indexes:
                    raise EvidenceError("duplicate log index in one observed block")
                seen_log_indexes.add(log_identity)
            transactions[tx_hash] = {"group": group, "transaction": tx, "receipt": receipt}
    if len(transactions) != 41:
        raise EvidenceError(f"expected 41 successful transactions, found {len(transactions)}")
    return transactions, source_files


def _verify_runtime_observations(raw: Path, manifest: dict[str, Any]) -> dict[str, Any] | None:
    path = raw / "runtime-code-observations.json"
    if not path.exists():
        return None
    observed = _json(path)
    if observed.get("schema") != "eae.local-runtime-code-observations.v1":
        raise EvidenceError("unexpected runtime-code observation schema")
    if observed.get("classification") != "localOnly Anvil RPC observation; not a public-chain attestation":
        raise EvidenceError("runtime-code observations lost their local-only classification")
    if observed.get("chainId") != manifest.get("chainId") or observed.get("chainId") != 31337:
        raise EvidenceError("runtime-code observation chain differs from deployment")
    if not isinstance(observed.get("blockNumber"), int) or not observed.get("blockHash", "").startswith("0x"):
        raise EvidenceError("runtime-code observations lack a block identity")
    rows = observed.get("contracts")
    expected = manifest.get("contracts", {})
    if not isinstance(rows, dict) or set(rows) != set(expected):
        raise EvidenceError("runtime-code observations must cover exactly the deployed contracts")
    for name, address in expected.items():
        row = rows[name]
        code = row.get("runtimeCode", "")
        if _norm(row.get("address", "")) != _norm(address):
            raise EvidenceError(f"runtime-code address differs for {name}")
        if not code.startswith("0x") or len(code) <= 2 or len(code[2:]) % 2:
            raise EvidenceError(f"runtime code is missing or malformed for {name}")
        try:
            body = bytes.fromhex(code[2:])
        except ValueError as exc:
            raise EvidenceError(f"runtime code is not hexadecimal for {name}") from exc
        if len(body) != row.get("runtimeCodeBytes") or "0x" + _sha256(body) != row.get("sha256", "").lower():
            raise EvidenceError(f"runtime-code byte length or SHA-256 differs for {name}")
        keccak = subprocess.run(["cast", "keccak", code], capture_output=True, text=True, check=False)
        if keccak.returncode != 0 or keccak.stdout.strip().lower() != row.get("keccak256", "").lower():
            raise EvidenceError(f"runtime-code Keccak identity differs for {name}")
    return observed


def _rpc(rpc_url: str, method: str, params: list[Any]) -> Any:
    request_body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    request = urllib.request.Request(
        rpc_url, data=request_body, headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            payload = json.loads(response.read())
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as exc:
        raise EvidenceError(f"live RPC failed for {method}") from exc
    if payload.get("error") or "result" not in payload:
        raise EvidenceError(f"live RPC returned an error for {method}: {payload.get('error')}")
    return payload["result"]


def _quantity(value: int) -> str:
    return hex(value)


def local_chain_fingerprint(chain_id: int, genesis_block_hash: str) -> str:
    """Fingerprint the observed local chain lineage, not a unique RPC process."""
    normalized_hash = _norm(genesis_block_hash)
    if len(normalized_hash) != 66 or not normalized_hash.startswith("0x"):
        raise EvidenceError("local chain identity has an invalid genesis block hash")
    try:
        bytes.fromhex(normalized_hash[2:])
    except ValueError as exc:
        raise EvidenceError("local chain identity has an invalid genesis block hash") from exc
    return "sha256:" + _sha256(
        f"prf.local-chain-fingerprint.v1\n{chain_id}\n{normalized_hash}\n".encode()
    )


def _block_by_number(rpc_url: str, number: int) -> dict[str, Any]:
    block = _rpc(rpc_url, "eth_getBlockByNumber", [_quantity(number), False])
    if not isinstance(block, dict) or _int(block.get("number", "-1")) != number:
        raise EvidenceError(f"canonical block {number} is unavailable")
    return block


def _block_reference(rpc_url: str, block_hash: str) -> dict[str, str]:
    """Use EIP-1898 so historical reads fail if the block is no longer canonical."""
    block = _rpc(rpc_url, "eth_getBlockByHash", [block_hash, False])
    if not isinstance(block, dict) or _norm(block.get("hash", "")) != _norm(block_hash):
        raise EvidenceError(f"block {block_hash} is unavailable by hash")
    return {"blockHash": block_hash, "requireCanonical": True}


def _abi_word(value: int) -> bytes:
    if value < 0 or value >= 1 << 256:
        raise EvidenceError("ABI word value is outside uint256 range")
    return value.to_bytes(32, "big")


def _abi_dynamic(value: bytes) -> bytes:
    padding = (-len(value)) % 32
    return _abi_word(len(value)) + value + bytes(padding)


def _sp1_verify_calldata(vkey: str, public_values: bytes, proof: bytes) -> str:
    try:
        key = bytes.fromhex(vkey.removeprefix("0x"))
    except ValueError as exc:
        raise EvidenceError("Groth16 VKey is not hexadecimal") from exc
    if len(key) != 32:
        raise EvidenceError("Groth16 VKey must be exactly bytes32")
    values_offset = 96
    proof_offset = values_offset + len(_abi_dynamic(public_values))
    return "0x" + (
        bytes.fromhex(SP1_GROTH16_VERIFY_SELECTOR.removeprefix("0x"))
        + key
        + _abi_word(values_offset)
        + _abi_word(proof_offset)
        + _abi_dynamic(public_values)
        + _abi_dynamic(proof)
    ).hex()


def verify_groth16_reference(raw: Path) -> dict[str, Any]:
    """Verify the exact retained proof against the pinned SP1 verifier runtime.

    Runs a read-only verifyProof eth_call on a fresh disposable local Anvil,
    after checking the captured verifier code identity and proof provenance.
    """
    raw = Path(raw)
    reconstruct(raw)
    manifest = _json(raw / "deployment-manifest.json")
    capture = _json(raw / "capture-metadata.json")
    fixture_path = raw / "proof-fixture.json"
    fixture = _json(fixture_path)
    runtime = _json(raw / "runtime-code-observations.json")
    capture_proof = capture.get("proof", {})
    fixture_digest = "0x" + _sha256(fixture_path.read_bytes())
    if (
        capture_proof.get("fixtureSha256", "").lower() != fixture_digest
        or capture_proof.get("proofSystem") != "groth16"
        or capture_proof.get("programId") != fixture.get("program_id")
        or capture_proof.get("vkey", "").lower() != fixture.get("vkey", "").lower()
        or capture_proof.get("elfSha256", "").lower() != fixture.get("elf_sha256", "").lower()
        or capture_proof.get("witnessSha256", "").lower() != fixture.get("gate_e", {}).get("witness_sha256", "").lower()
    ):
        raise EvidenceError("proof fixture differs from the captured qualified execution identity")

    verifier_row = runtime.get("contracts", {}).get("verifier", {})
    verifier_address = manifest.get("contracts", {}).get("verifier", "")
    verifier_code = verifier_row.get("runtimeCode", "")
    if _norm(verifier_row.get("address", "")) != _norm(verifier_address):
        raise EvidenceError("reference verifier address differs from the deployment manifest")
    if verifier_row.get("keccak256", "").lower() != SP1_GROTH16_VERIFIER_V6_1_0_KECCAK:
        raise EvidenceError("reference verifier runtime is not the pinned SP1 Groth16 v6.1.0 identity")

    try:
        bytes.fromhex(verifier_code.removeprefix("0x"))
        proof_bytes = bytes.fromhex(fixture["proof"].removeprefix("0x"))
        public_values = bytes.fromhex(fixture["public_values"].removeprefix("0x"))
    except (KeyError, ValueError) as exc:
        raise EvidenceError("proof or verifier runtime bytes are missing or malformed") from exc
    if not shutil.which("cast"):
        raise EvidenceError("unsupported: cast is required to verify the pinned verifier runtime hash")
    verifier_keccak = subprocess.run(["cast", "keccak", verifier_code], capture_output=True, text=True, check=False)
    if verifier_keccak.returncode != 0 or verifier_keccak.stdout.strip().lower() != SP1_GROTH16_VERIFIER_V6_1_0_KECCAK:
        raise EvidenceError("reference verifier runtime does not match the pinned v6.1.0 code hash")
    if len(public_values) != 288 or len(proof_bytes) != 356:
        raise EvidenceError("pinned successor reference check requires 288 public bytes and a 356-byte Groth16 proof")

    if not shutil.which("anvil"):
        raise EvidenceError("unsupported: anvil is required for direct Groth16 reference verification")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    process = subprocess.Popen(
        ["anvil", "--silent", "--chain-id", "31337", "--host", "127.0.0.1", "--port", str(port)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    rpc_url = f"http://127.0.0.1:{port}"
    try:
        for _ in range(100):
            if process.poll() is not None:
                raise EvidenceError("local Anvil exited before reference verification")
            try:
                chain_id = _int(_rpc(rpc_url, "eth_chainId", []))
                break
            except EvidenceError:
                time.sleep(0.05)
        else:
            raise EvidenceError("local Anvil did not become ready for reference verification")
        if chain_id != 31337:
            raise EvidenceError("reference verifier must run on disposable local chain 31337")
        _rpc(rpc_url, "anvil_setCode", [verifier_address, verifier_code])
        if _norm(_rpc(rpc_url, "eth_getCode", [verifier_address, "latest"])) != _norm(verifier_code):
            raise EvidenceError("local reference verifier runtime differs from the pinned artifact")
        result = _rpc(
            rpc_url,
            "eth_call",
            [{"to": verifier_address, "data": _sp1_verify_calldata(fixture["vkey"], public_values, proof_bytes)}, "latest"],
        )
        if result != "0x":
            raise EvidenceError("SP1 Groth16 verifier returned unexpected data")
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)

    words = _words(fixture["public_values"])
    return {
        "schema": "prf.source-bound-entitlement-groth16-reference.v1",
        "status": "verified",
        "method": "direct eth_call to pinned SP1VerifierGroth16 v6.1.0 on disposable local Anvil",
        "providerScope": "local-only; this check establishes proof validity, not public-chain execution or finality",
        "chainId": 31337,
        "verifier": {"address": _norm(verifier_address), "runtimeKeccak256": SP1_GROTH16_VERIFIER_V6_1_0_KECCAK},
        "source": {
            "repository": capture.get("source", {}).get("repository"),
            "revision": capture.get("source", {}).get("revision"),
            "captureId": capture.get("captureId"),
            "deploymentManifestDigest": manifest.get("manifestDigest"),
        },
        "toolchain": capture.get("toolchain", {}),
        "programId": fixture["program_id"],
        "proofSystem": fixture["proof_system"],
        "vkey": fixture["vkey"],
        "elfSha256": fixture["elf_sha256"],
        "publicValuesSchema": fixture["public_values_schema"],
        "proofSha256": "0x" + _sha256(proof_bytes),
        "publicValuesSha256": "0x" + _sha256(public_values),
        "fixtureSha256": fixture_digest,
        "witnessSha256": fixture["gate_e"]["witness_sha256"],
        "operationId": manifest["operationId"],
        "preparedOperationRoot": words[1],
        "reserveStateCommitment": words[2],
        "statementRoot": words[3],
        "configurationRoot": manifest["configurationRoot"],
        "referenceCheckExecuted": True,
    }


def _normalize_log(log: dict[str, Any]) -> dict[str, Any]:
    return {
        "address": _norm(log.get("address", "")),
        "topics": [_norm(topic) for topic in log.get("topics", [])],
        "data": _norm(log.get("data", "")),
        "logIndex": _int(log.get("logIndex", "-1")),
        "transactionHash": _norm(log.get("transactionHash", "")),
        "transactionIndex": _int(log.get("transactionIndex", "-1")),
        "blockNumber": _int(log.get("blockNumber", "-1")),
        "blockHash": _norm(log.get("blockHash", "")),
        "removed": bool(log.get("removed", False)),
    }


def verify_live_chain_context(raw: Path, rpc_url: str, min_confirmations: int | None = None) -> dict[str, Any]:
    """Recheck retained transaction and code observations against one live RPC.

    The result establishes provider-relative canonicality at verification time.
    It does not authenticate the RPC provider or claim consensus finality.
    """
    raw = Path(raw)
    reconstruct(raw)
    manifest = _json(raw / "deployment-manifest.json")
    capture = _json(raw / "capture-metadata.json")
    runtime = _json(raw / "runtime-code-observations.json")
    if (capture.get("schema") not in {"eae.pro-28-capture-metadata.v1", "eae.pro-28-capture-metadata.v2"}
            or not isinstance(capture.get("captureId"), str)
            or not capture["captureId"]
            or not manifest.get("localOnly")
            or not capture.get("classification", "").startswith("localOnly")
            or capture.get("publicNetworkTransactionsSubmitted") is not False):
        raise EvidenceError("live check requires explicitly local-only evidence")
    if capture.get("chainId") != manifest.get("chainId") or runtime.get("chainId") != manifest.get("chainId"):
        raise EvidenceError("deployment, capture and runtime observation chain IDs differ")
    if (capture.get("runtimeObservation", {}).get("codeFetchedAtBlock") != runtime.get("blockNumber")
            or capture.get("runtimeObservation", {}).get("contractsObserved") != len(runtime.get("contracts", {}))):
        raise EvidenceError("capture metadata does not bind the runtime observation block and contract count")
    captured_identity = capture.get("localChainIdentity")
    if (capture.get("schema") != "eae.pro-28-capture-metadata.v2"
            or not isinstance(captured_identity, dict)
            or captured_identity.get("schema") != "prf.local-chain-fingerprint.v1"
            or captured_identity.get("chainId") != manifest["chainId"]
            or not isinstance(captured_identity.get("genesisBlockHash"), str)
            or not isinstance(captured_identity.get("fingerprint"), str)):
        raise EvidenceError("capture metadata lacks an observed local-chain fingerprint; fresh capture required")
    chain_id = _int(_rpc(rpc_url, "eth_chainId", []))
    if chain_id != manifest["chainId"]:
        raise EvidenceError(f"live RPC chain ID {chain_id} differs from evidence chain ID {manifest['chainId']}")
    if captured_identity["chainId"] != chain_id:
        raise EvidenceError("captured local-chain identity has a different chain ID")
    genesis = _block_by_number(rpc_url, 0)
    live_genesis_hash = _norm(genesis.get("hash", ""))
    expected_fingerprint = local_chain_fingerprint(chain_id, captured_identity["genesisBlockHash"])
    if (live_genesis_hash != _norm(captured_identity["genesisBlockHash"])
            or captured_identity["fingerprint"] != expected_fingerprint):
        raise EvidenceError("live RPC local-chain fingerprint differs from captured identity")

    threshold = max(0, min_confirmations or 0)
    latest_number = _int(_rpc(rpc_url, "eth_blockNumber", []))

    transactions, _ = _load_receipts(raw)
    anchor_number = runtime["blockNumber"]
    anchor_hash = _norm(runtime["blockHash"])
    if anchor_number > latest_number:
        raise EvidenceError("runtime observation block is above the live chain head")

    observed_blocks = {anchor_number}
    for entry in transactions.values():
        receipt = entry["receipt"]
        number = _int(receipt["blockNumber"])
        if number > latest_number:
            raise EvidenceError("retained transaction block is above the live chain head")
        observed_blocks.add(number)

    headers: dict[int, dict[str, Any]] = {}
    for number in sorted(observed_blocks):
        expected_hashes: set[str] = set()
        if number == anchor_number:
            expected_hashes.add(_norm(runtime["blockHash"]))
        expected_hashes.update(
            _norm(entry["receipt"]["blockHash"])
            for entry in transactions.values()
            if _int(entry["receipt"]["blockNumber"]) == number
        )
        if len(expected_hashes) != 1:
            raise EvidenceError(f"retained observations conflict on block {number}")
        expected_hash = next(iter(expected_hashes))
        header = _block_by_number(rpc_url, number)
        if _norm(header.get("hash", "")) != expected_hash:
            raise EvidenceError(f"block {number} is orphaned or replaced on the live RPC")
        if number > 0:
            parent = _block_by_number(rpc_url, number - 1)
            if _norm(header.get("parentHash", "")) != _norm(parent.get("hash", "")):
                raise EvidenceError(f"block {number} parent link differs from the live canonical chain")
        headers[number] = header

    confirmations_by_block = {number: latest_number - number + 1 for number in observed_blocks}
    if any(value < threshold for value in confirmations_by_block.values()):
        raise EvidenceError("one or more source blocks do not meet the requested confirmation threshold")

    checked_transactions = 0
    for tx_hash, entry in transactions.items():
        expected_receipt = entry["receipt"]
        live_receipt = _rpc(rpc_url, "eth_getTransactionReceipt", [tx_hash])
        if not isinstance(live_receipt, dict):
            raise EvidenceError(f"transaction receipt {tx_hash} is unavailable")
        for key in ("transactionHash", "blockHash", "blockNumber", "transactionIndex", "status"):
            if key not in expected_receipt or key not in live_receipt:
                raise EvidenceError(f"transaction {tx_hash} is missing {key} in a receipt")
            if key in ("blockNumber", "transactionIndex", "status"):
                matches = _int(expected_receipt[key]) == _int(live_receipt[key])
            else:
                matches = _norm(expected_receipt[key]) == _norm(live_receipt[key])
            if not matches:
                raise EvidenceError(f"live receipt {tx_hash} differs at {key}")
        expected_logs = [_normalize_log(log) for log in expected_receipt.get("logs", [])]
        live_logs = [_normalize_log(log) for log in live_receipt.get("logs", [])]
        if expected_logs != live_logs:
            raise EvidenceError(f"live receipt {tx_hash} logs differ from retained evidence")
        block_number = _int(expected_receipt["blockNumber"])
        block = headers[block_number]
        tx_index = _int(expected_receipt["transactionIndex"])
        block_txs = _rpc(rpc_url, "eth_getBlockByNumber", [_quantity(block_number), True]).get("transactions", [])
        if tx_index >= len(block_txs) or _norm(block_txs[tx_index].get("hash", "")) != tx_hash:
            raise EvidenceError(f"transaction {tx_hash} is not at its retained index in the live block")
        live_tx = _rpc(rpc_url, "eth_getTransactionByHash", [tx_hash])
        if not isinstance(live_tx, dict) or _int(live_tx.get("chainId", "-1")) != chain_id:
            raise EvidenceError(f"transaction {tx_hash} is missing or has a different live chain identity")
        if _int(live_tx.get("transactionIndex", "-1")) != tx_index or _norm(live_tx.get("blockHash", "")) != _norm(block["hash"]):
            raise EvidenceError(f"transaction {tx_hash} live block/index differs from its receipt")
        retained_tx = entry["transaction"].get("transaction", {})
        for key in ("from", "to", "input", "value", "nonce", "gas", "chainId"):
            if key not in retained_tx or key not in live_tx:
                raise EvidenceError(f"transaction {tx_hash} is missing {key} from its retained or live call payload")
            if key in ("from", "to", "input"):
                if key == "to" and (retained_tx[key] is None or live_tx[key] is None):
                    matches = retained_tx[key] is None and live_tx[key] is None
                else:
                    matches = _norm(retained_tx[key]) == _norm(live_tx[key])
            else:
                matches = _int(retained_tx[key]) == _int(live_tx[key])
            if not matches:
                raise EvidenceError(f"live transaction {tx_hash} differs from retained {key}")
        checked_transactions += 1

    anchor_ref = _block_reference(rpc_url, anchor_hash)
    checked_contracts = 0
    for name, row in runtime["contracts"].items():
        address = row["address"]
        anchored_code = _rpc(rpc_url, "eth_getCode", [address, anchor_ref])
        current_code = _rpc(rpc_url, "eth_getCode", [address, "latest"])
        if _norm(anchored_code) != _norm(row["runtimeCode"]):
            raise EvidenceError(f"runtime code for {name} differs at the captured block")
        if _norm(current_code) != _norm(anchored_code):
            raise EvidenceError(f"runtime code for {name} changed after the captured block")
        if row.get("implementationAddress"):
            implementation = row["implementationAddress"]
            slot_at_anchor = _rpc(rpc_url, "eth_getStorageAt", [address, EIP1967_IMPLEMENTATION_SLOT, anchor_ref])
            slot_latest = _rpc(rpc_url, "eth_getStorageAt", [address, EIP1967_IMPLEMENTATION_SLOT, "latest"])
            if _norm("0x" + slot_at_anchor[-40:]) != _norm(implementation) or _norm(slot_at_anchor) != _norm(slot_latest):
                raise EvidenceError(f"proxy implementation for {name} changed or differs from the captured identity")
            implementation_code = _rpc(rpc_url, "eth_getCode", [implementation, anchor_ref])
            if _norm(implementation_code) != _norm(row.get("implementationRuntimeCode", "")):
                raise EvidenceError(f"implementation code for {name} differs at the captured block")
            latest_implementation_code = _rpc(rpc_url, "eth_getCode", [implementation, "latest"])
            if _norm(latest_implementation_code) != _norm(implementation_code):
                raise EvidenceError(f"implementation code for {name} changed after the captured block")
        checked_contracts += 1

    return {
        "schema": "prf.forensic-live-chain-check.v2",
        "status": "passed",
        "classification": "localOnly provider-relative canonicality check; not public-chain finality or independent consensus proof",
        "environmentId": capture["captureId"],
        "localChainIdentity": {
            "schema": "prf.local-chain-fingerprint.v1",
            "chainId": chain_id,
            "genesisBlockHash": live_genesis_hash,
            "fingerprint": expected_fingerprint,
            "scope": "chain lineage fingerprint; does not uniquely identify an RPC provider or Anvil process",
        },
        "chainId": chain_id,
        "deploymentManifestDigest": manifest["manifestDigest"],
        "operationId": manifest["operationId"],
        "preparedOperationRoot": manifest["preparedOperationRoot"],
        "configurationRoot": manifest["configurationRoot"],
        "sourceRevision": capture.get("source", {}).get("revision"),
        "runtimeAnchor": {"blockNumber": anchor_number, "blockHash": anchor_hash,
                           "parentHash": _norm(headers[anchor_number]["parentHash"])},
        "latestBlockNumber": latest_number,
        "minimumConfirmationsRequested": threshold,
        "minimumConfirmationsObserved": min(confirmations_by_block.values()),
        "canonicality": "all transaction and runtime anchors matched this RPC's current canonical chain",
        "transactionsChecked": checked_transactions,
        "transactionAnchors": [
            {"transactionHash": tx_hash,
             "blockNumber": _int(row["receipt"]["blockNumber"]),
             "blockHash": _norm(row["receipt"]["blockHash"]),
             "transactionIndex": _int(row["receipt"]["transactionIndex"]),
             "status": _int(row["receipt"]["status"])}
            for tx_hash, row in sorted(transactions.items())
        ],
        "contractsChecked": checked_contracts,
        "contractRuntimeIdentities": {
            name: {key: row[key] for key in ("address", "sha256", "keccak256")}
            | ({"implementationAddress": row["implementationAddress"]} if row.get("implementationAddress") else {})
            for name, row in sorted(runtime["contracts"].items())
        },
        "runtimeCodeCurrentAtLatest": True,
        "validAtCheckOnly": True,
        "finalityClaimed": False,
    }


def _transfer_events(
    transactions: dict[str, dict[str, Any]], token: str, chain_id: int
) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for tx_hash, row in transactions.items():
        receipt = row["receipt"]
        for log in receipt.get("logs", []):
            topics = log.get("topics", [])
            if _norm(log.get("address", "")) != token or not topics or _norm(topics[0]) != TRANSFER_TOPIC:
                continue
            if len(topics) != 3 or len(log.get("data", "")) != 66:
                raise EvidenceError("ERC-20 Transfer log has malformed ABI fields")
            if _norm(log["transactionHash"]) != tx_hash:
                raise EvidenceError("Transfer log references a different transaction")
            events.append(
                {
                    "chainId": chain_id,
                    "transactionHash": tx_hash,
                    "transactionIndex": _int(receipt["transactionIndex"]),
                    "blockNumber": _int(receipt["blockNumber"]),
                    "blockHash": _norm(receipt["blockHash"]),
                    "receiptStatus": _int(receipt["status"]),
                    "logIndex": _int(log["logIndex"]),
                    "emitter": _norm(log["address"]),
                    "from": "0x" + topics[1][-40:].lower(),
                    "to": "0x" + topics[2][-40:].lower(),
                    "amount": int(log["data"], 16),
                    "group": row["group"],
                }
            )
    return events


def _exact_event(events: list[dict[str, Any]], *, source: str, target: str, amount: int, group: str) -> dict[str, Any]:
    matches = [
        event
        for event in events
        if event["from"] == source
        and event["to"] == target
        and event["amount"] == amount
        and event["group"] == group
    ]
    if len(matches) != 1:
        raise EvidenceError(
            f"expected one {amount}-unit Transfer {source}->{target} in {group}; found {len(matches)}"
        )
    return matches[0]


def _calldata_word(calldata: bytes, offset: int) -> int:
    if offset < 0 or offset + 32 > len(calldata):
        raise EvidenceError("transaction calldata is truncated")
    return int.from_bytes(calldata[offset : offset + 32], "big")


def _call_data(row: dict[str, Any]) -> tuple[dict[str, Any], bytes]:
    tx = row["transaction"].get("transaction", {})
    encoded = tx.get("input", "")
    if not isinstance(encoded, str) or not encoded.startswith("0x"):
        raise EvidenceError("transaction is missing its signed call payload")
    try:
        return tx, bytes.fromhex(encoded[2:])
    except ValueError as exc:
        raise EvidenceError("transaction call payload is not hexadecimal") from exc


def _verify_economic_transaction_bindings(
    transactions: dict[str, dict[str, Any]],
    manifest: dict[str, Any],
    source_deposit: dict[str, Any],
    allocation_funding: dict[str, Any],
    claimant_events: list[dict[str, Any]],
) -> None:
    """Bind observed transfer logs to the actual top-level calls that caused them."""
    contracts = manifest["contracts"]
    sponsor = _norm(manifest["deployer"])
    reserve = _norm(contracts["reserve"])
    allocator = _norm(contracts["allocator"])
    operation_id = int(manifest["operationId"], 16)

    def row_for(event: dict[str, Any]) -> dict[str, Any]:
        try:
            return transactions[event["transactionHash"]]
        except KeyError as exc:
            raise EvidenceError("economic event references an unknown transaction") from exc

    deposit_row = row_for(source_deposit)
    deposit_tx, deposit_data = _call_data(deposit_row)
    if (
        deposit_row["group"] != "deployment"
        or _norm(deposit_tx.get("from", "")) != sponsor
        or _norm(deposit_tx.get("to", "")) != reserve
        or deposit_data[:4].hex() != "b6b55f25"  # OnchainShortfallReserveV2.deposit(uint256)
        or len(deposit_data) != 36
        or _calldata_word(deposit_data, 4) != source_deposit["amount"]
    ):
        raise EvidenceError("sponsor deposit log is not bound to the matching reserve.deposit(amount) call")
    deposit_logs = [
        log for log in deposit_row["receipt"].get("logs", [])
        if _norm(log.get("address", "")) == reserve
        and log.get("topics", [""])[0].lower()
        == "0x2da466a7b24304f47e87fa2e1e5a81b9831ce54fec19055ce277ca2f39ba42c4"
    ]
    if (
        len(deposit_logs) != 1
        or len(deposit_logs[0].get("topics", [])) != 2
        or not deposit_logs[0]["topics"][1].lower().endswith(sponsor[2:])
        or _int(deposit_logs[0].get("data", "0x0")) != source_deposit["amount"]
    ):
        raise EvidenceError("reserve deposit call lacks its matching Deposited(from, amount) event")

    funding_row = row_for(allocation_funding)
    funding_tx, funding_data = _call_data(funding_row)
    binding_calls = [
        row for row in transactions.values()
        if row["group"] == "funding"
        and _call_data(row)[1][:4].hex() == "28bf4b07"  # bindExecutionCaller(address)
    ]
    if len(binding_calls) != 1:
        raise EvidenceError("funding evidence lacks exactly one execution-caller binding call")
    binding_tx, binding_data = _call_data(binding_calls[0])
    operator = "0x" + _calldata_word(binding_data, 4).to_bytes(32, "big")[-20:].hex()
    if (
        _norm(binding_tx.get("to", "")) != _norm(contracts["ratificationController"])
        or len(binding_data) != 36
        or _norm(funding_tx.get("to", "")) != _norm(operator)
        or funding_data[:4].hex() != "9a5a276f"  # GateEEntitlementLocalOperator.fund(bytes32,address)
        or len(funding_data) != 68
        or _calldata_word(funding_data, 4) != int(manifest["reserveStateCommitment"], 16)
        or _norm("0x" + _calldata_word(funding_data, 36).to_bytes(32, "big")[-20:].hex())
        != _norm(manifest["localOnly"]["residualRecipient"])
    ):
        raise EvidenceError("allocator funding log is not bound to the prepared operation's fund call")
    funding_logs = [
        log for log in funding_row["receipt"].get("logs", [])
        if _norm(log.get("address", "")) == _norm(contracts["ratificationController"])
        and log.get("topics", [""])[0].lower()
        == "0xcf3311c8bdef5e68826cabf9475980bb15b15f4b9d1bfdfc3308b1be90de815c"
    ]
    if (
        len(funding_logs) != 1
        or len(funding_logs[0].get("topics", [])) != 3
        or _norm(funding_logs[0]["topics"][1]) != _norm(manifest["operationId"])
        or _int(funding_logs[0].get("data", "0x0")) != allocation_funding["amount"]
    ):
        raise EvidenceError("allocator funding receipt lacks the operation-bound ratification event")

    for claim in claimant_events:
        transfer = claim["transfer"]
        claim_row = row_for(transfer)
        claim_tx, claim_data = _call_data(claim_row)
        if (
            claim_row["group"] != "withdrawals"
            or _norm(claim_tx.get("to", "")) != allocator
            or claim_data[:4].hex() != "7c365406"  # ProductionProRataAllocatorV2.withdraw(...)
            or _calldata_word(claim_data, 4) != operation_id
        ):
            raise EvidenceError("claim payout is not bound to the matching allocator.withdraw call")
        tuple_offset = _calldata_word(claim_data, 36)
        tuple_start = 4 + tuple_offset
        claim_index = _calldata_word(claim_data, tuple_start)
        claim_bytes_offset = _calldata_word(claim_data, tuple_start + 32)
        beneficiary = "0x" + _calldata_word(claim_data, tuple_start + 64).to_bytes(32, "big")[-20:].hex()
        amount = _calldata_word(claim_data, tuple_start + 96)
        claim_bytes_start = tuple_start + claim_bytes_offset
        claim_bytes_length = _calldata_word(claim_data, claim_bytes_start)
        claim_bytes = claim_data[claim_bytes_start + 32 : claim_bytes_start + 32 + claim_bytes_length]
        try:
            claim_id = claim_bytes.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise EvidenceError("claim transaction identity is not UTF-8") from exc
        expected_index = {"claim-a": 0, "claim-b": 1, "claim-c": 2}.get(claim["claimId"])
        if (
            claim_id != claim["claimId"]
            or claim_index != expected_index
            or _norm(beneficiary) != claim["beneficiary"]
            or amount != claim["amount"]
            or _norm(transfer["from"]) != allocator
            or _norm(transfer["to"]) != claim["beneficiary"]
        ):
            raise EvidenceError("claim transaction opening differs from the observed payout identity")


def _verify_terminal_state_observation(
    observation_path: Path,
    raw: Path,
    manifest: dict[str, Any],
    manifest_digest: str,
    capture: dict[str, Any],
    public_values: list[str],
    transactions: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    """Validate the retained block-pinned eth_call snapshot and its chain check."""
    observation = _json(observation_path)
    if observation.get("schema") != "eae.pro-56-live-state-observation.v1":
        raise EvidenceError("unexpected terminal-state observation schema")
    if observation.get("chainId") != manifest.get("chainId") or observation.get("chainId") != 31337:
        raise EvidenceError("terminal-state observation chain identity differs from deployment")
    if observation.get("classification") != "localOnly provider-relative eth_call observation; no public-chain or consensus claim":
        raise EvidenceError("terminal-state observation lost its local-only qualification")
    if (
        observation.get("deploymentManifestDigest") != manifest_digest
        or observation.get("environmentId") != capture.get("captureId")
        or observation.get("sourceRevision") != capture.get("source", {}).get("revision")
        or observation.get("operationId", "").lower() != manifest.get("operationId", "").lower()
        or observation.get("preparedOperationRoot", "").lower()
        != manifest.get("preparedOperationRoot", "").lower()
    ):
        raise EvidenceError("terminal-state observation does not bind the captured deployment and operation")
    expected_source = f"{raw.parent.name}/{raw.name}"
    if observation.get("sourceReplayDirectory") != expected_source:
        raise EvidenceError("terminal-state observation names a different source replay")

    block = observation.get("observationBlock", {})
    block_number = block.get("number")
    block_hash = _norm(block.get("hash", ""))
    if not isinstance(block_number, int) or block_number < 0 or len(block_hash) != 66:
        raise EvidenceError("terminal-state observation lacks a valid block identity")
    try:
        bytes.fromhex(block_hash.removeprefix("0x"))
    except ValueError as exc:
        raise EvidenceError("terminal-state observation block hash is malformed") from exc
    latest_receipt_block = max(_int(row["receipt"]["blockNumber"]) for row in transactions.values())
    if block_number < latest_receipt_block:
        raise EvidenceError("terminal-state observation predates the latest retained execution receipt")

    live_check_path = raw / "live-chain-check.json"
    if not live_check_path.is_file():
        raise EvidenceError("terminal-state observation lacks its retained live-chain context check")
    live_check = _json(live_check_path)
    anchor = live_check.get("runtimeAnchor", {})
    if (
        live_check.get("schema") != "prf.forensic-live-chain-check.v2"
        or live_check.get("status") != "passed"
        or live_check.get("chainId") != manifest["chainId"]
        or live_check.get("environmentId") != observation.get("environmentId")
        or live_check.get("deploymentManifestDigest") != manifest_digest
        or live_check.get("operationId", "").lower() != manifest["operationId"].lower()
        or live_check.get("preparedOperationRoot", "").lower() != manifest["preparedOperationRoot"].lower()
        or anchor.get("blockNumber") != block_number
        or _norm(anchor.get("blockHash", "")) != block_hash
        or live_check.get("latestBlockNumber", -1) < block_number
    ):
        raise EvidenceError("terminal-state block does not match its live-chain context check")
    receipt_anchors = {
        (_norm(tx_hash), _int(row["receipt"]["blockNumber"]), _norm(row["receipt"]["blockHash"]))
        for tx_hash, row in transactions.items()
    }
    check_anchors = {
        (_norm(row.get("transactionHash", "")), row.get("blockNumber"), _norm(row.get("blockHash", "")))
        for row in live_check.get("transactionAnchors", [])
    }
    if not receipt_anchors.issubset(check_anchors):
        raise EvidenceError("live-chain context check omits or substitutes retained transaction anchors")

    contracts = manifest["contracts"]
    reads = observation.get("reads")
    if not isinstance(reads, list):
        raise EvidenceError("terminal-state observation has no read list")
    read_by_name = {row.get("name"): row for row in reads if isinstance(row, dict)}
    expected_names = {
        "reserveTokenBalance", "allocatorTokenBalance", "aliceTokenBalance", "bobTokenBalance",
        "carolTokenBalance", "claimedAmount", "claimedCount", "outstandingLiability",
        "reserveTotalReserved", "entitlementOperation",
    }
    if len(read_by_name) != len(reads) or set(read_by_name) != expected_names:
        raise EvidenceError("terminal-state observation reads are incomplete, duplicated or unexpected")
    for row in reads:
        if row.get("blockNumber") != block_number or _norm(row.get("blockHash", "")) != block_hash:
            raise EvidenceError("terminal-state reads do not share the declared block context")

    addresses = {
        "reserveTokenBalance": (contracts["token"], [contracts["reserve"]], "balanceOf(address)(uint256)"),
        "allocatorTokenBalance": (contracts["token"], [contracts["allocator"]], "balanceOf(address)(uint256)"),
        "aliceTokenBalance": (contracts["token"], ["0x1111111111111111111111111111111111111111"], "balanceOf(address)(uint256)"),
        "bobTokenBalance": (contracts["token"], ["0x2222222222222222222222222222222222222222"], "balanceOf(address)(uint256)"),
        "carolTokenBalance": (contracts["token"], ["0x3333333333333333333333333333333333333333"], "balanceOf(address)(uint256)"),
        "claimedAmount": (contracts["allocator"], [manifest["operationId"]], "claimedAmount(bytes32)(uint256)"),
        "claimedCount": (contracts["allocator"], [manifest["operationId"]], "claimedCount(bytes32)(uint256)"),
        "outstandingLiability": (contracts["allocator"], [manifest["operationId"]], "outstandingLiability(bytes32)(uint256)"),
        "reserveTotalReserved": (contracts["reserve"], [], "totalReserved()(uint256)"),
        "entitlementOperation": (contracts["allocator"], [manifest["operationId"]], "entitlementOperation(bytes32)"),
    }
    for name, row in read_by_name.items():
        target, arguments, signature = addresses[name]
        if (
            _norm(row.get("to", "")) != _norm(target)
            or [_norm(value) for value in row.get("arguments", [])] != [_norm(value) for value in arguments]
            or row.get("signature") != signature
        ):
            raise EvidenceError(f"terminal-state read target or selector differs for {name}")

    state = observation.get("decodedState", {})
    operation = state.get("entitlementOperation", {})
    expected_scalars = {
        "reserveTokenBalance": ("reserveTokenBalance", 10),
        "allocatorTokenBalance": ("allocatorTokenBalance", 0),
        "aliceTokenBalance": ("recipientTokenBalances.alice", 35),
        "bobTokenBalance": ("recipientTokenBalances.bob", 21),
        "carolTokenBalance": ("recipientTokenBalances.carol", 14),
        "claimedAmount": ("claimedAmount", 70),
        "claimedCount": ("claimedCount", 3),
        "outstandingLiability": ("outstandingLiability", 0),
        "reserveTotalReserved": ("reserveTotalReserved", 0),
    }
    for name, (decoded_key, expected_value) in expected_scalars.items():
        row = read_by_name[name]
        decoded = state
        for key in decoded_key.split("."):
            decoded = decoded.get(key, {}) if isinstance(decoded, dict) else {}
        if _int(row.get("rawResult", "-1")) != expected_value or decoded != expected_value:
            raise EvidenceError(f"terminal-state accounting read differs for {name}")

    raw_result = read_by_name["entitlementOperation"].get("rawResult", "")
    encoded = raw_result.removeprefix("0x")
    if len(encoded) != 12 * 64:
        raise EvidenceError("terminal-state entitlement operation has an unexpected ABI shape")
    try:
        words = ["0x" + encoded[i : i + 64].lower() for i in range(0, len(encoded), 64)]
        bytes.fromhex(encoded)
    except ValueError as exc:
        raise EvidenceError("terminal-state entitlement operation is malformed ABI data") from exc
    expected_operation = {
        "stateEnum": 5,
        "allocationBindingRoot": public_values[3],
        "descriptorRoot": public_values[4],
        "evmEntitlementTableRoot": public_values[5],
        "entitlementCount": 3,
        "entitlementTotal": 70,
        "allocationClaimRoot": public_values[8],
        "beneficiaryProfileWord": words[7],
        "token": contracts["token"],
        "amount": 70,
        "residualRecipient": manifest["localOnly"]["residualRecipient"],
        "representation": operation.get("representation"),
    }
    for key, expected_value in expected_operation.items():
        actual = operation.get(key)
        if key in {"allocationBindingRoot", "descriptorRoot", "evmEntitlementTableRoot", "allocationClaimRoot", "beneficiaryProfileWord", "token", "residualRecipient", "representation"}:
            if not isinstance(actual, str) or not isinstance(expected_value, str) or _norm(actual) != _norm(expected_value):
                raise EvidenceError(f"terminal-state entitlement operation differs for {key}")
        elif actual != expected_value:
            raise EvidenceError(f"terminal-state entitlement operation differs for {key}")
    word_expectations = [
        (0, 5), (1, int(public_values[3], 16)), (2, int(public_values[4], 16)),
        (3, int(public_values[5], 16)), (4, 3), (5, 70), (6, int(public_values[8], 16)),
        (7, int(operation["beneficiaryProfileWord"], 16)),
        (8, int(contracts["token"], 16)), (9, 70),
        (10, int(manifest["localOnly"]["residualRecipient"], 16)),
        (11, int(operation["representation"], 16)),
    ]
    if any(int(words[index], 16) != value for index, value in word_expectations):
        raise EvidenceError("terminal-state entitlement ABI words differ from the decoded operation")
    if operation.get("stateName") != "Closed":
        raise EvidenceError("terminal-state entitlement operation is not Closed")
    reconciliation = observation.get("reconciliation", {})
    if (
        reconciliation.get("matches") is not True
        or reconciliation.get("allocatorFunding") != 70
        or reconciliation.get("sourceDeposit") != 80
        or reconciliation.get("liabilityExpected") != 0
        or reconciliation.get("allocatorBalanceExpected") != 0
        or reconciliation.get("reserveBalanceExpected") != 10
        or reconciliation.get("payouts") != {"alice": 35, "bob": 21, "carol": 14}
    ):
        raise EvidenceError("terminal-state observation's declared reconciliation does not match the validated state")

    return {
        "schema": observation["schema"],
        "status": "validated provider-relative local eth_call observation",
        "chainId": 31337,
        "environmentId": observation["environmentId"],
        "operationId": observation["operationId"],
        "preparedOperationRoot": observation["preparedOperationRoot"],
        "blockNumber": block_number,
        "blockHash": block_hash,
        "artifactSha256": _sha256(observation_path.read_bytes()),
        "decodedState": state,
        "limitations": observation.get("limitations", []),
    }


def reconstruct(raw: Path, terminal_state_observation: Path | None = None) -> dict[str, Any]:
    """Validate raw cross-links and reconstruct the funding and claim journal."""
    raw = Path(raw)
    manifest_path = raw / "deployment-manifest.json"
    witness_path = raw / "witness.json"
    provenance_path = raw / "witness-provenance.json"
    fixture_path = raw / "proof-fixture.json"
    vector_path = raw / "source" / "source-bound-entitlement-v1.json"
    manifest, witness, provenance, fixture, vector = map(
        _json, (manifest_path, witness_path, provenance_path, fixture_path, vector_path)
    )

    manifest_digest = manifest.get("manifestDigest", "")
    canonical_manifest = dict(manifest)
    canonical_manifest.pop("manifestDigest", None)
    calculated_manifest_digest = "sha256:" + _sha256(
        (json.dumps(canonical_manifest, sort_keys=True, separators=(",", ":")) + "\n").encode()
    )
    if manifest_digest != calculated_manifest_digest:
        raise EvidenceError("deployment manifest digest mismatch")
    if manifest.get("chainId") != 31337 or not manifest.get("localOnly"):
        raise EvidenceError("evidence must be explicitly local-only chain 31337")
    if manifest.get("schema") != "prf-evm.gate-e-entitlement-deployment.v1":
        raise EvidenceError("unexpected deployment manifest schema")

    witness_hash = _sha256(witness_path.read_bytes())
    if fixture.get("source", {}).get("witness_sha256", "").lower() != "0x" + witness_hash:
        raise EvidenceError("proof fixture does not bind the retained witness bytes")
    if provenance.get("witness_sha256") != "sha256:" + witness_hash:
        raise EvidenceError("witness provenance digest mismatch")
    vector_hash = _sha256(vector_path.read_bytes())
    if fixture.get("gate_e", {}).get("source_bundle_sha256", "").lower() != "0x" + vector_hash:
        raise EvidenceError("proof fixture does not bind the retained conformance bundle")
    if provenance.get("bundle_sha256") != "sha256:" + vector_hash:
        raise EvidenceError("witness provenance bundle digest mismatch")
    if fixture.get("gate_e", {}).get("manifest_digest") != manifest_digest:
        raise EvidenceError("proof fixture does not bind the retained deployment manifest")
    if provenance.get("manifest_digest") != manifest_digest:
        raise EvidenceError("witness provenance does not bind the retained manifest")
    if fixture.get("proof_system") != "groth16":
        raise EvidenceError("only the pinned Groth16 proof fixture is accepted")
    if (
        fixture.get("program_id") != manifest.get("programId")
        or fixture.get("vkey", "").lower() != manifest.get("programVKey", "").lower()
        or fixture.get("elf_sha256", "").lower() != manifest.get("programElfSha256", "").lower()
        or fixture.get("public_values_schema") != "prf/source-bound-entitlement-public-values.v1"
    ):
        raise EvidenceError("proof/program identity differs from deployment identity")
    proof_bytes = bytes.fromhex(fixture["proof"].removeprefix("0x"))
    if fixture.get("gate_e", {}).get("proof_sha256", "").lower() != "0x" + _sha256(proof_bytes):
        raise EvidenceError("proof byte digest mismatch")
    capture_path = raw / "capture-metadata.json"
    capture = _json(capture_path) if capture_path.exists() else None
    if capture is not None and isinstance(capture.get("proof"), dict):
        captured_proof = capture.get("proof", {})
        expected_fixture_sha256 = "0x" + _sha256(fixture_path.read_bytes())
        if (
            captured_proof.get("fixtureSha256", "").lower() != expected_fixture_sha256
            or captured_proof.get("proofSystem") != fixture.get("proof_system")
            or captured_proof.get("programId") != fixture.get("program_id")
            or captured_proof.get("vkey", "").lower() != fixture.get("vkey", "").lower()
            or captured_proof.get("elfSha256", "").lower() != fixture.get("elf_sha256", "").lower()
            or captured_proof.get("witnessSha256", "").lower()
            != fixture.get("gate_e", {}).get("witness_sha256", "").lower()
        ):
            raise EvidenceError("captured proof identity does not bind the retained fixture and operation")
    public_values = _words(fixture["public_values"])
    expected_vector = vector["expected"][fixture["gate_e"]["vector_id"]]
    if fixture["gate_e"]["vector_id"] != "A-minimum-realistic":
        raise EvidenceError("unexpected source vector selection")
    if witness.get("realized-statement-input") != vector.get("base-input", {}).get("realized-statement-input"):
        raise EvidenceError("deployment witness economic allocation facts differ from selected source vector")
    if public_values[4] != "0x" + expected_vector["entitlement-representation-descriptor-root"].removeprefix("0x").lower():
        raise EvidenceError("public descriptor root differs from the selected source vector")
    if _int(public_values[6]) != int(expected_vector["entitlement-count"]):
        raise EvidenceError("public entitlement count differs from the selected source vector")
    if _int(public_values[7]) != int(expected_vector["entitlement-total"]):
        raise EvidenceError("public entitlement total differs from the selected source vector")
    if public_values[1] != manifest["preparedOperationRoot"].lower():
        raise EvidenceError("public prepared-operation root differs from deployment manifest")
    if public_values[2] != manifest["reserveStateCommitment"].lower():
        raise EvidenceError("public reserve commitment differs from deployment manifest")
    if fixture["gate_e"]["operation_id"].lower() != manifest["operationId"].lower():
        raise EvidenceError("proof fixture operation identity differs from manifest")

    prepared = witness.get("prepared-operation", {})
    if (
        str(prepared.get("chain-id")) != "31337"
        or prepared.get("operation-id", "").lower() != manifest["operationId"].lower()
        or prepared.get("allocator", "").lower() != manifest["contracts"]["allocator"].lower()
        or prepared.get("reserve", "").lower() != manifest["contracts"]["reserve"].lower()
        or prepared.get("token", "").lower() != manifest["contracts"]["token"].lower()
        or prepared.get("configuration-root", "").lower() != manifest["configurationRoot"].lower()
    ):
        raise EvidenceError("raw witness prepared-operation context differs from deployment manifest")

    transactions, source_files = _load_receipts(raw)
    runtime_observations = _verify_runtime_observations(raw, manifest)
    terminal_observation = None
    if terminal_state_observation is not None:
        if capture is None:
            raise EvidenceError("terminal-state verification requires capture metadata")
        terminal_observation = _verify_terminal_state_observation(
            Path(terminal_state_observation), raw, manifest, manifest_digest, capture, public_values, transactions
        )
    token = _norm(manifest["contracts"]["token"])
    reserve = _norm(manifest["contracts"]["reserve"])
    allocator = _norm(manifest["contracts"]["allocator"])
    sponsor = _norm(manifest["deployer"])
    events = _transfer_events(transactions, token, manifest["chainId"])
    source_deposit = _exact_event(events, source=sponsor, target=reserve, amount=80, group="deployment")
    allocation_funding = _exact_event(events, source=reserve, target=allocator, amount=70, group="funding")

    claimant_events = []
    for beneficiary, (claim_id, amount) in RECIPIENTS.items():
        claimant_events.append(
            {
                "claimId": claim_id,
                "beneficiary": beneficiary,
                "amount": amount,
                "transfer": _exact_event(
                    events, source=allocator, target=beneficiary, amount=amount, group="withdrawals"
                ),
            }
        )
    _verify_economic_transaction_bindings(
        transactions, manifest, source_deposit, allocation_funding, claimant_events
    )
    entitlements = {row["claim-id"]: row for row in expected_vector["ordered-entitlements"]}
    for item in claimant_events:
        expected = entitlements.get(item["claimId"])
        if not expected or _norm(expected["beneficiary"]) != item["beneficiary"] or int(expected["amount"]) != item["amount"]:
            raise EvidenceError("observed claimant transfer does not match vector entitlement identity")

    test_log = (raw / "fork-test.log").read_text()
    if "Suite result: ok. 4 passed; 0 failed; 0 skipped" not in test_log:
        raise EvidenceError("retained real-proof Foundry suite did not report four passing tests")
    final_state = (raw / "final-state.log").read_text().strip()
    for required in ("reserve_balance=10", "allocator_balance=0", "beneficiary_1111_balance=35", "beneficiary_2222_balance=21", "beneficiary_3333_balance=14"):
        if required not in final_state:
            raise EvidenceError(f"terminal state lacks expected accounting observation: {required}")

    def file_hash(path: Path) -> dict[str, Any]:
        body = path.read_bytes()
        return {"sha256": _sha256(body), "bytes": len(body)}

    artifact_paths = [manifest_path, witness_path, provenance_path, fixture_path, vector_path]
    artifact_paths += [raw / "broadcast" / f"{group}.json" for group in GROUPS]
    artifact_paths += [
        raw / "runner-broadcast" / f"{group}.json"
        for group in GROUPS
        if (raw / "runner-broadcast" / f"{group}.json").is_file()
    ]
    artifact_paths += [raw / "fork-test.log", raw / "final-state.log"]
    if terminal_observation is not None:
        artifact_paths.append(raw / "live-chain-check.json")
    artifact_paths += [
        raw / name
        for name in ("funding.log", "activation.log", "withdrawal.log")
        if (raw / name).is_file()
    ]
    if runtime_observations is not None:
        artifact_paths.append(raw / "runtime-code-observations.json")
    if capture is not None:
        if capture.get("schema") not in {"eae.pro-28-capture-metadata.v1", "eae.pro-28-capture-metadata.v2"} or capture.get("chainId") != 31337:
            raise EvidenceError("capture metadata schema or chain identity is invalid")
        if capture.get("deploymentManifestDigest") != manifest_digest:
            raise EvidenceError("capture metadata does not bind the retained deployment manifest")
        artifact_paths.append(capture_path)
    return {
        "schema": "eae.pro-28-authentic-local-execution.v2",
        "classification": "local Anvil observation on chain 31337; not public-chain execution or chain-state proof",
        "source": {
            "repository": "protocol-robustness/prf-evm",
            "revision": "4cfa2f96a4938d23abda609dfff9052b177ef8af",
            "replay": capture["replay"] if capture is not None else "fresh Anvil replay by a separate agent/workspace; see issue evidence provenance",
        },
        "verificationResults": {
            "structuralIntegrity": {
                "status": "passed",
                "validator": "scripts/forensic/pro28_receipts.py",
                "checkedArtifacts": [str(path.relative_to(raw)) for path in artifact_paths],
            },
            "cryptographicVerification": {
                "status": "not_independently_verified",
                "programId": fixture["program_id"],
                "proofSystem": fixture["proof_system"],
                "vkey": fixture["vkey"],
                "reason": "the retained Foundry log is mutable execution output; this verifier does not rerun the pinned SP1 verifier",
            },
            "proofTestRunObservation": {
                "status": "success_string_observed" if "Suite result: ok. 4 passed; 0 failed; 0 skipped" in test_log else "success_string_absent",
                "scope": "retained Foundry test-log text only; does not establish cryptographic proof validity",
                "testOutputSha256": "0x" + _sha256((raw / "fork-test.log").read_bytes()),
            },
            "executionObservation": {
                "status": "receipt cross-links passed",
                "chainId": 31337,
                "localOnly": True,
                "successfulReceiptCount": len(transactions),
                "finalObservedBlock": max(_int(row["receipt"]["blockNumber"]) for row in transactions.values()),
            },
            "economicReconciliation": {
                "status": (
                    "passed against receipt transfers and validated provider-relative terminal eth_call observation"
                    if terminal_observation is not None
                    else "receipt transfers cross-linked; terminal balances and liability remain unverified"
                ),
                "sourceDeposit": 80,
                "allocatorFunding": 70,
                "claimPayouts": {item["claimId"]: item["amount"] for item in claimant_events},
                "liabilityClosed": (
                    terminal_observation["decodedState"]["outstandingLiability"] == 0
                    if terminal_observation is not None else None
                ),
                "terminalStateObservation": terminal_observation or {
                    "status": "not independently observed",
                    "reason": "final-state.log is retained as text and is not accepted as authoritative terminal state",
                },
            },
            "provenanceLimitations": [
                "Local Anvil observations are not public-chain evidence or proof of canonical chain state.",
                "Runtime code bytes are retained and their hashes recomputed; the local RPC observation is unsigned.",
                "The superseded V3 summary lacks receipt and log indexes and is not receipt-level payout evidence.",
            ],
            "unverifiedClaims": ["No Base Sepolia execution, public deployment qualification, or organizationally independent verification is claimed."],
        },
        "chainId": 31337,
        "deploymentManifestDigest": manifest_digest,
        "operationId": manifest["operationId"],
        "preparedOperationRoot": manifest["preparedOperationRoot"],
        "proof": {
            "programId": fixture["program_id"],
            "proofSystem": fixture["proof_system"],
            "vkey": fixture["vkey"],
            "elfSha256": fixture["elf_sha256"],
            "schema": fixture["public_values_schema"],
            "publicValuesBytes": fixture["public_values_length"],
            "proofSha256": fixture["gate_e"]["proof_sha256"],
            "publicValuesSha256": "0x" + _sha256(bytes.fromhex(fixture["public_values"].removeprefix("0x"))),
            "sourceBundleSha256": fixture["gate_e"]["source_bundle_sha256"],
            "vectorId": fixture["gate_e"]["vector_id"],
            "witnessSha256": fixture["gate_e"]["witness_sha256"],
            "words": public_values,
            "allocationSpecificOutput": "deployment-bound roots are checked against the manifest and witness; portable source-vector roots are not substituted for deployment-specific roots",
            "cryptographicVerification": "not independently verified; retained Foundry output is not treated as cryptographic verification",
        },
        "contractAddresses": manifest["contracts"],
        "runtimeCodeIdentity": (
            {
                "status": "captured and content-hash verified from local Anvil RPC",
                "classification": runtime_observations["classification"],
                "blockNumber": runtime_observations["blockNumber"],
                "blockHash": runtime_observations["blockHash"],
                "contracts": {
                    name: {key: row[key] for key in ("address", "runtimeCodeBytes", "keccak256", "sha256")}
                    for name, row in runtime_observations["contracts"].items()
                },
            }
            if runtime_observations is not None
            else {
                "status": "not present in retained replay package",
                "binding": "source revision, compiler settings and deployment transaction inputs are retained; runtime code hashes were not captured",
            }
        ),
        "receiptCounts": {group: sum(1 for row in transactions.values() if row["group"] == group) for group in GROUPS},
        "successfulReceiptCount": len(transactions),
        "economicEvents": [
            {"kind": "sponsorDeposit", "from": sponsor, "to": reserve, "amount": 80, "receipt": source_deposit},
            {"kind": "allocatorFunding", "from": reserve, "to": allocator, "amount": 70, "receipt": allocation_funding},
            *[
                {
                    "kind": "entitlementClaim",
                    "claimId": item["claimId"],
                    "from": allocator,
                    "to": item["beneficiary"],
                    "amount": item["amount"],
                    "receipt": item["transfer"],
                }
                for item in claimant_events
            ],
        ],
        "accounting": {
            "sponsorDeposit": 80,
            "protectedReserveRemainder": 10,
            "allocatorFunding": 70,
            "claimPayouts": {item["claimId"]: item["amount"] for item in claimant_events},
            "totalClaimPayouts": sum(item["amount"] for item in claimant_events),
            "finalStateObservation": (
                terminal_observation or {
                    "status": "unverified log text",
                    "text": final_state,
                    "receiptMaxBlock": max(_int(row["receipt"]["blockNumber"]) for row in transactions.values()),
                }
            ),
            "carolEntitlement": 14,
            "carolProtectedRefundInThisExecution": 0,
            "note": "Carol's 14-unit entitlement is recorded separately from the 10-unit protected-capital refund described by the superseded V3 Foundry summary.",
        },
        "artifactHashes": {
            **{str(path.relative_to(raw)): file_hash(path) for path in artifact_paths},
            **(
                {"terminal-state-observation.json": file_hash(Path(terminal_state_observation))}
                if terminal_state_observation is not None else {}
            ),
        },
        "runnerBroadcastSourceHashes": {
            key: "sha256:" + value
            for key, value in sorted(source_files.items())
            if key.startswith("runner-broadcast/")
        },
    }


def verify(
    raw: Path,
    expected_report: Path | None = None,
    expected_report_sha256: str | None = None,
    terminal_state_observation: Path | None = None,
) -> dict[str, Any]:
    report = reconstruct(raw, terminal_state_observation)
    rendered = json.dumps(report, sort_keys=True, indent=2) + "\n"
    if expected_report_sha256 is not None:
        pinned = expected_report_sha256.removeprefix("sha256:").lower()
        if len(pinned) != 64 or any(char not in "0123456789abcdef" for char in pinned):
            raise EvidenceError("trusted report SHA-256 pin is malformed")
        if _sha256(rendered.encode()) != pinned:
            raise EvidenceError("reconstructed receipt report differs from trusted SHA-256 pin")
    if expected_report is not None and _json(expected_report) != report:
        raise EvidenceError("reconstructed receipt report differs from retained expected report")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path, help="directory containing retained raw artifacts")
    parser.add_argument("--expected-report", type=Path, help="compare with a previously retained reconstruction")
    parser.add_argument("--expected-report-sha256", help="compare canonical reconstructed report bytes with an independently retained SHA-256 pin")
    parser.add_argument("--write-report", type=Path, help="write the independently reconstructed report")
    parser.add_argument("--rpc-url", help="optionally recheck captured blocks, receipts and code against a live JSON-RPC endpoint")
    parser.add_argument("--min-confirmations", type=int, help="optional observed block-depth threshold for local-only evidence; does not establish finality")
    parser.add_argument("--write-chain-report", type=Path, help="write the provider-relative live chain check separately from canonical receipt reconstruction")
    parser.add_argument("--terminal-state-observation", type=Path, help="validate and bind an external block-pinned terminal eth_call observation")
    parser.add_argument("--verify-groth16", action="store_true", help="execute the pinned SP1 Groth16 verifier against this exact fixture using a disposable local Anvil")
    parser.add_argument("--write-proof-check", type=Path, help="write the direct Groth16 reference-check report separately")
    args = parser.parse_args()
    if args.write_proof_check and not args.verify_groth16:
        parser.error("--write-proof-check requires --verify-groth16")
    try:
        report = verify(
            args.bundle,
            args.expected_report,
            args.expected_report_sha256,
            args.terminal_state_observation,
        )
        chain_report = verify_live_chain_context(args.bundle, args.rpc_url, args.min_confirmations) if args.rpc_url else None
        proof_report = verify_groth16_reference(args.bundle) if args.verify_groth16 else None
    except EvidenceError as exc:
        parser.exit(1, f"FAIL: {exc}\n")
    if proof_report is not None:
        report["proof"]["cryptographicVerification"] = proof_report
        report["verificationResults"]["cryptographicVerification"] = proof_report
    rendered = json.dumps(report, sort_keys=True, indent=2) + "\n"
    if args.write_report:
        args.write_report.write_text(rendered)
    print(f"PASS: {report['successfulReceiptCount']} successful receipts cross-linked")
    print("PASS: sponsor deposit 80, allocator funding 70, claims 35/21/14 bound to receipt and log identities")
    print(f"classification: {report['classification']}")
    if proof_report is None:
        print("cryptographic proof: NOT_INDEPENDENTLY_VERIFIED (retained test-log text is not proof verification)")
    if args.write_report:
        print(f"report: {args.write_report}")
    if chain_report is not None:
        chain_rendered = json.dumps(chain_report, sort_keys=True, indent=2) + "\n"
        if args.write_chain_report:
            args.write_chain_report.write_text(chain_rendered)
        print(f"PASS: live RPC rechecked {chain_report['transactionsChecked']} receipts and {chain_report['contractsChecked']} runtime identities")
        print(f"chain-context: {chain_report['classification']}")
        if args.write_chain_report:
            print(f"chain report: {args.write_chain_report}")
    if proof_report is not None:
        proof_rendered = json.dumps(proof_report, sort_keys=True, indent=2) + "\n"
        if args.write_proof_check:
            args.write_proof_check.write_text(proof_rendered)
        print("PASS: pinned SP1 Groth16 reference verifier accepted the exact retained proof and public values")
        print(f"proof-reference: {proof_report['proofSha256']} / {proof_report['publicValuesSha256']}")
        if args.write_proof_check:
            print(f"proof check report: {args.write_proof_check}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
