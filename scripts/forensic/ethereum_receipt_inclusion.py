"""Verify one Ethereum-family transaction and receipt against a pinned header.

This adapter verifies receipt-trie inclusion and signed transaction identity.
It does not discover or authenticate the trusted header context, verify a
consensus light client, or establish chain finality. The expected digest for
the trust-context file must come from a verifier-selected external source.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

try:
    import rlp
    from eth_hash.auto import keccak
    from eth_keys.constants import SECPK1_N
    from eth_keys.datatypes import Signature
except ImportError as exc:  # pragma: no cover - environment dependent
    raise RuntimeError(
        "Ethereum receipt verification requires pinned rlp, eth-hash, and eth-keys packages"
    ) from exc


class InclusionError(ValueError):
    """The supplied transaction, receipt, proof, or trusted context is invalid."""


TRUST_SCHEMA = "prf-ethereum-header-trust-context.v1"
CASE_SCHEMA = "prf-ethereum-receipt-inclusion-case.v1"
ADDRESS_RE = re.compile(r"^0x[0-9a-fA-F]{40}$")
HEX_RE = re.compile(r"^0x(?:[0-9a-fA-F]{2})*$")


def _json_no_duplicates(raw: bytes, label: str) -> Any:
    def pairs(rows: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in rows:
            if key in result:
                raise InclusionError(f"duplicate JSON field in {label}: {key}")
            result[key] = value
        return result

    try:
        return json.loads(raw, object_pairs_hook=pairs)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise InclusionError(f"invalid JSON in {label}") from exc


def _hex_bytes(value: Any, label: str, size: int | None = None) -> bytes:
    if not isinstance(value, str) or not HEX_RE.fullmatch(value):
        raise InclusionError(f"{label} must be even-length 0x-prefixed hex")
    data = bytes.fromhex(value[2:])
    if size is not None and len(data) != size:
        raise InclusionError(f"{label} must be {size} bytes")
    return data


def _quantity(value: Any, label: str) -> int:
    if not isinstance(value, str) or not re.fullmatch(r"0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)", value):
        raise InclusionError(f"{label} must be a canonical JSON-RPC quantity")
    return int(value, 16)


def _rlp_int(value: bytes, label: str) -> int:
    if value and value[0] == 0:
        raise InclusionError(f"{label} is not a canonical RLP integer")
    return int.from_bytes(value, "big")


def _nibbles(raw: bytes) -> list[int]:
    return [n for byte in raw for n in (byte >> 4, byte & 0x0F)]


def _compact_path(raw: bytes) -> tuple[list[int], bool]:
    ns = _nibbles(raw)
    if not ns or ns[0] not in (0, 1, 2, 3):
        raise InclusionError("invalid hex-prefix path")
    leaf = ns[0] >= 2
    odd = ns[0] % 2 == 1
    if odd:
        return ns[1:], leaf
    if len(ns) < 2 or ns[1] != 0:
        raise InclusionError("non-canonical even hex-prefix path")
    return ns[2:], leaf


def verify_indexed_trie_proof(trie_root: bytes, transaction_index: int,
                              proof_nodes: list[bytes]) -> bytes:
    """Return the value proven at RLP(transaction_index) in an Ethereum trie."""
    if len(trie_root) != 32 or transaction_index < 0 or not proof_nodes:
        raise InclusionError("invalid trie root, index, or empty proof")
    key = _nibbles(rlp.encode(transaction_index))
    remaining = key
    expected_ref = trie_root
    cursor = 0
    steps = 0
    while steps < 128:
        steps += 1
        if len(expected_ref) == 32:
            if cursor >= len(proof_nodes):
                raise InclusionError("proof ended before reaching a referenced node")
            node_raw = proof_nodes[cursor]
            cursor += 1
            if keccak(node_raw) != expected_ref:
                raise InclusionError("trie node hash does not match its parent reference")
        elif len(expected_ref) < 32:
            node_raw = expected_ref
            if cursor < len(proof_nodes) and proof_nodes[cursor] == node_raw:
                cursor += 1
        else:
            raise InclusionError("invalid trie child reference width")

        try:
            node = rlp.decode(node_raw, strict=True)
        except Exception as exc:
            raise InclusionError("malformed RLP trie node") from exc
        if rlp.encode(node) != node_raw:
            raise InclusionError("non-canonical RLP trie node")
        if not isinstance(node, list):
            raise InclusionError("trie node must be an RLP list")

        if len(node) == 17:
            if not remaining:
                value = node[16]
                if not isinstance(value, bytes) or cursor != len(proof_nodes):
                    raise InclusionError("invalid branch value or unused proof nodes")
                return value
            nibble = remaining[0]
            remaining = remaining[1:]
            child = node[nibble]
            expected_ref = rlp.encode(child) if isinstance(child, list) else child
            if not isinstance(expected_ref, bytes) or not expected_ref:
                raise InclusionError("receipt path terminates at an empty trie branch")
            continue

        if len(node) != 2 or not isinstance(node[0], bytes):
            raise InclusionError("trie node must be a branch or compact-path pair")
        segment, is_leaf = _compact_path(node[0])
        if remaining[:len(segment)] != segment:
            raise InclusionError("trie node path does not match transaction index")
        remaining = remaining[len(segment):]
        if is_leaf:
            value = node[1]
            if remaining or not isinstance(value, bytes) or cursor != len(proof_nodes):
                raise InclusionError("invalid leaf value, path remainder, or unused proof nodes")
            return value
        if not isinstance(node[1], (bytes, list)):
            raise InclusionError("extension child has invalid RLP type")
        expected_ref = rlp.encode(node[1]) if isinstance(node[1], list) else node[1]
        if not expected_ref:
            raise InclusionError("extension node has an empty child reference")

    raise InclusionError("trie proof exceeded maximum node depth")


def _logs_bloom(logs: list[dict[str, Any]]) -> bytes:
    bloom = bytearray(256)
    for log in logs:
        values = [_hex_bytes(log.get("address"), "log address", 20)]
        topics = log.get("topics")
        if not isinstance(topics, list):
            raise InclusionError("log topics must be an array")
        values.extend(_hex_bytes(topic, "log topic", 32) for topic in topics)
        for value in values:
            digest = keccak(value)
            for offset in (0, 2, 4):
                bit = int.from_bytes(digest[offset:offset + 2], "big") & 2047
                bloom[255 - bit // 8] |= 1 << (bit % 8)
    return bytes(bloom)


def encode_rpc_receipt(receipt: dict[str, Any]) -> tuple[bytes, int]:
    """Serialize a JSON-RPC receipt to the consensus receipt encoding."""
    if not isinstance(receipt, dict):
        raise InclusionError("receipt must be an object")
    receipt_type = _quantity(receipt.get("type", "0x0"), "receipt type")
    if receipt_type > 0x7F:
        raise InclusionError("typed receipt type must fit in one EIP-2718 byte")
    if receipt_type == 0:
        prefix = b""
    else:
        prefix = bytes([receipt_type])

    if "status" in receipt and receipt["status"] is not None:
        status = _quantity(receipt["status"], "receipt status")
        if status not in (0, 1):
            raise InclusionError("receipt status must be zero or one")
        state = status.to_bytes((status.bit_length() + 7) // 8, "big")
    elif "root" in receipt and receipt["root"] is not None:
        state = _hex_bytes(receipt["root"], "pre-Byzantium receipt root", 32)
    else:
        raise InclusionError("receipt must contain status or pre-Byzantium root")

    cumulative_gas = _quantity(receipt.get("cumulativeGasUsed"), "cumulativeGasUsed")
    gas_bytes = cumulative_gas.to_bytes((cumulative_gas.bit_length() + 7) // 8, "big")
    bloom = _hex_bytes(receipt.get("logsBloom"), "logsBloom", 256)
    logs = receipt.get("logs")
    if not isinstance(logs, list):
        raise InclusionError("receipt logs must be an array")
    encoded_logs = []
    for log in logs:
        if not isinstance(log, dict):
            raise InclusionError("receipt log must be an object")
        address = _hex_bytes(log.get("address"), "log address", 20)
        topics = log.get("topics")
        if not isinstance(topics, list):
            raise InclusionError("log topics must be an array")
        topic_bytes = [_hex_bytes(topic, "log topic", 32) for topic in topics]
        data = _hex_bytes(log.get("data"), "log data")
        encoded_logs.append([address, topic_bytes, data])
    if _logs_bloom(logs) != bloom:
        raise InclusionError("logsBloom does not match the receipt logs")
    return prefix + rlp.encode([state, gas_bytes, bloom, encoded_logs]), receipt_type


def _recover_transaction(raw: bytes, expected_chain_id: int) -> tuple[str, int]:
    if not raw:
        raise InclusionError("signed transaction bytes are empty")
    tx_type = 0
    if raw[0] < 0x80:
        tx_type = raw[0]
        if tx_type != 2:
            raise InclusionError(f"unsupported typed transaction type: {tx_type}")
        payload_raw = raw[1:]
    else:
        payload_raw = raw
    try:
        payload = rlp.decode(payload_raw, strict=True)
    except Exception as exc:
        raise InclusionError("malformed signed transaction RLP") from exc
    if not isinstance(payload, list) or rlp.encode(payload) != payload_raw:
        raise InclusionError("signed transaction must be a canonical RLP list")

    if tx_type == 2:
        if (len(payload) != 12
                or not all(isinstance(field, bytes) for field in payload[:8])
                or not isinstance(payload[8], list)
                or not all(isinstance(field, bytes) for field in payload[9:])):
            raise InclusionError("EIP-1559 transaction fields are malformed")
        chain_id = _rlp_int(payload[0], "transaction chain ID")
        nonce = _rlp_int(payload[1], "transaction nonce")
        priority_fee = _rlp_int(payload[2], "transaction priority fee")
        max_fee = _rlp_int(payload[3], "transaction maximum fee")
        gas_limit = _rlp_int(payload[4], "transaction gas limit")
        value = _rlp_int(payload[6], "transaction value")
        if chain_id < 1 or min(nonce, priority_fee, max_fee, gas_limit, value) < 0 or max_fee < priority_fee:
            raise InclusionError("EIP-1559 transaction numeric fields are invalid")
        if len(payload[5]) not in (0, 20):
            raise InclusionError("EIP-1559 transaction destination must be empty or 20 bytes")
        for access_entry in payload[8]:
            if (not isinstance(access_entry, list) or len(access_entry) != 2
                    or not isinstance(access_entry[0], bytes) or len(access_entry[0]) != 20
                    or not isinstance(access_entry[1], list)
                    or not all(isinstance(slot, bytes) and len(slot) == 32 for slot in access_entry[1])):
                raise InclusionError("EIP-1559 access-list entry is malformed")
        parity = _rlp_int(payload[9], "transaction y parity")
        r_value = _rlp_int(payload[10], "transaction signature r")
        s_value = _rlp_int(payload[11], "transaction signature s")
        signing_payload = b"\x02" + rlp.encode(payload[:9])
    else:
        if len(payload) != 9 or not all(isinstance(field, bytes) for field in payload):
            raise InclusionError("legacy transaction must have 9 byte-string fields")
        for index, label in ((0, "nonce"), (1, "gas price"), (2, "gas limit"), (4, "value")):
            _rlp_int(payload[index], f"legacy transaction {label}")
        if len(payload[3]) not in (0, 20):
            raise InclusionError("legacy transaction destination must be empty or 20 bytes")
        v = _rlp_int(payload[6], "legacy transaction v")
        if v < 35:
            raise InclusionError("unprotected legacy transactions are not accepted")
        chain_id, parity = divmod(v - 35, 2)
        r_value = _rlp_int(payload[7], "transaction signature r")
        s_value = _rlp_int(payload[8], "transaction signature s")
        signing_payload = rlp.encode(payload[:6] + [
            chain_id.to_bytes((chain_id.bit_length() + 7) // 8, "big"), b"", b""])
    if chain_id != expected_chain_id:
        raise InclusionError("signed transaction chain ID differs from trusted context")
    if parity not in (0, 1) or not (0 < r_value < SECPK1_N
                                     and 0 < s_value <= SECPK1_N // 2):
        raise InclusionError("transaction signature values are outside secp256k1 range")
    try:
        public_key = Signature(vrs=(parity, r_value, s_value)).recover_public_key_from_msg_hash(
            keccak(signing_payload))
    except Exception as exc:
        raise InclusionError("transaction sender recovery failed") from exc
    return public_key.to_checksum_address().lower(), tx_type


def _header_identity(header_raw: bytes) -> tuple[bytes, int, bytes, bytes, bytes]:
    try:
        fields = rlp.decode(header_raw, strict=True)
    except Exception as exc:
        raise InclusionError("malformed block header RLP") from exc
    if not isinstance(fields, list) or rlp.encode(fields) != header_raw or len(fields) < 15:
        raise InclusionError("block header is not canonical or lacks required fields")
    if not all(isinstance(field, bytes) for field in fields):
        raise InclusionError("block header has malformed fixed fields")
    if (len(fields[0]) != 32 or len(fields[1]) != 32 or len(fields[2]) != 20
            or len(fields[3]) != 32 or len(fields[4]) != 32 or len(fields[5]) != 32
            or len(fields[6]) != 256 or len(fields[12]) > 32
            or len(fields[13]) != 32 or len(fields[14]) != 8):
        raise InclusionError("block header fixed-width fields are malformed")
    gas_limit = _rlp_int(fields[9], "header gas limit")
    gas_used = _rlp_int(fields[10], "header gas used")
    if gas_used > gas_limit:
        raise InclusionError("header gas used exceeds gas limit")
    if not all(not field or field[0] != 0 for field in fields[7:12]):
        raise InclusionError("block header numeric fields are not canonical")
    transactions_root = fields[4]
    receipts_root = fields[5]
    number = _rlp_int(fields[8], "header number")
    return keccak(header_raw), number, fields[0], transactions_root, receipts_root


def verify_case(case: dict[str, Any], trust_context_bytes: bytes,
                expected_trust_context_sha256: str, now: int) -> dict[str, Any]:
    """Verify exact receipt inclusion under a verifier-pinned header context."""
    required = {"schema", "receipt", "transactionRaw", "transactionIndex",
                "transactionProof", "receiptProof", "blockHeaderRlp", "headerAncestry",
                "expectedSender"}
    if not isinstance(case, dict) or set(case) != required or case.get("schema") != CASE_SCHEMA:
        raise InclusionError("case schema or fields are invalid")
    if len(trust_context_bytes) > 64 * 1024:
        raise InclusionError("trusted context exceeds size limit")
    trust_digest = hashlib.sha256(trust_context_bytes).hexdigest()
    if expected_trust_context_sha256 != trust_digest:
        raise InclusionError("trusted context digest does not match independent pin")
    trust = _json_no_duplicates(trust_context_bytes, "trusted context")
    trust_required = {"schema", "network", "chainFamily", "chainId", "genesisHash",
                      "anchorHeaderHash", "anchorHeaderNumber", "sourceId", "sourceRevision",
                      "observedAt", "validUntil", "finalityStatus"}
    if not isinstance(trust, dict) or set(trust) != trust_required or trust.get("schema") != TRUST_SCHEMA:
        raise InclusionError("trusted context schema or fields are invalid")
    if trust["chainFamily"] not in ("ethereum-l1", "op-stack-l2"):
        raise InclusionError("unsupported chain family")
    if type(trust["chainId"]) is not int or trust["chainId"] < 1:
        raise InclusionError("trusted chain ID is invalid")
    if not all(isinstance(trust[key], str) and trust[key] for key in ("network", "sourceId", "sourceRevision")):
        raise InclusionError("trusted network or source identity is missing")
    if not all(type(trust[key]) is int for key in ("anchorHeaderNumber", "observedAt", "validUntil")):
        raise InclusionError("trusted header time or height is invalid")
    if (trust["observedAt"] < 0 or trust["validUntil"] < trust["observedAt"]
            or now < 0 or not trust["observedAt"] <= now <= trust["validUntil"]):
        raise InclusionError("trusted header context is stale or not yet valid")
    if trust["finalityStatus"] != "unverified":
        raise InclusionError("this verifier cannot establish a finality claim")

    genesis_hash = _hex_bytes(trust["genesisHash"], "trusted genesis hash", 32)
    pinned_anchor_hash = _hex_bytes(trust["anchorHeaderHash"], "trusted anchor header hash", 32)
    header_raw = _hex_bytes(case["blockHeaderRlp"], "block header RLP")
    if len(header_raw) > 64 * 1024:
        raise InclusionError("block header exceeds size limit")
    header_hash, header_number, _parent_hash, transactions_root, receipts_root = _header_identity(header_raw)
    ancestry = case["headerAncestry"]
    if not isinstance(ancestry, list) or len(ancestry) > 8192:
        raise InclusionError("header ancestry must be an array within the header-count limit")
    current_hash = header_hash
    current_number = header_number
    ancestry_bytes = 0
    for index, encoded_header in enumerate(ancestry):
        ancestor_raw = _hex_bytes(encoded_header, f"ancestry header {index}")
        ancestry_bytes += len(ancestor_raw)
        if ancestry_bytes > 8 * 1024 * 1024:
            raise InclusionError("header ancestry exceeds byte-size limit")
        ancestor_hash, ancestor_number, ancestor_parent, _, _ = _header_identity(ancestor_raw)
        if ancestor_parent != current_hash or ancestor_number != current_number + 1:
            raise InclusionError("header ancestry parent or height link is invalid")
        current_hash, current_number = ancestor_hash, ancestor_number
    if current_hash != pinned_anchor_hash or current_number != trust["anchorHeaderNumber"]:
        raise InclusionError("receipt block is not an ancestor of the trusted header anchor")

    tx_index = case["transactionIndex"]
    if type(tx_index) is not int or tx_index < 0:
        raise InclusionError("transaction index must be a non-negative integer")
    transaction_raw = _hex_bytes(case["transactionRaw"], "signed transaction")
    if len(transaction_raw) > 128 * 1024:
        raise InclusionError("signed transaction exceeds size limit")
    sender, tx_type = _recover_transaction(transaction_raw, trust["chainId"])
    if not isinstance(case["expectedSender"], str) or not ADDRESS_RE.fullmatch(case["expectedSender"]):
        raise InclusionError("expected sender must be an Ethereum address")
    if sender != case["expectedSender"].lower():
        raise InclusionError("recovered sender differs from expected sender")
    transaction_hash = "0x" + keccak(transaction_raw).hex()
    if not isinstance(case["receipt"], dict):
        raise InclusionError("receipt must be an object")
    if _hex_bytes(case["receipt"].get("transactionHash"), "receipt transaction hash", 32) != keccak(transaction_raw):
        raise InclusionError("receipt transaction hash differs from signed transaction")
    if _quantity(case["receipt"].get("transactionIndex"), "receipt transaction index") != tx_index:
        raise InclusionError("receipt transaction index differs from proof key")
    if _hex_bytes(case["receipt"].get("blockHash"), "receipt block hash", 32) != header_hash:
        raise InclusionError("receipt block hash differs from trusted header")
    if _quantity(case["receipt"].get("blockNumber"), "receipt block number") != header_number:
        raise InclusionError("receipt block number differs from trusted header")
    receipt_raw, receipt_type = encode_rpc_receipt(case["receipt"])
    if receipt_type != tx_type:
        raise InclusionError("typed receipt and signed transaction types differ")
    if not isinstance(case["transactionProof"], list) or len(case["transactionProof"]) > 128:
        raise InclusionError("transaction proof is malformed or exceeds node-count limit")
    transaction_proof = [_hex_bytes(item, "transaction proof node")
                         for item in case["transactionProof"]]
    if not isinstance(case["receiptProof"], list):
        raise InclusionError("receipt proof must be an array")
    if len(case["receiptProof"]) > 128:
        raise InclusionError("receipt proof exceeds node-count limit")
    proof = [_hex_bytes(item, "receipt proof node") for item in case["receiptProof"]]
    if sum(map(len, transaction_proof + proof)) > 4 * 1024 * 1024:
        raise InclusionError("transaction and receipt proofs exceed byte-size limit")
    included_transaction = verify_indexed_trie_proof(transactions_root, tx_index, transaction_proof)
    if included_transaction != transaction_raw:
        raise InclusionError("transaction proof value differs from signed transaction bytes")
    included = verify_indexed_trie_proof(receipts_root, tx_index, proof)
    if included != receipt_raw:
        raise InclusionError("receipt proof value differs from serialized RPC receipt")

    return {
        "schema": "prf-ethereum-receipt-inclusion-result.v1",
        "status": "receipt_inclusion_verified_under_external_header_pin",
        "network": trust["network"],
        "chainFamily": trust["chainFamily"],
        "chainId": trust["chainId"],
        "genesisHash": "0x" + genesis_hash.hex(),
        "blockHash": "0x" + header_hash.hex(),
        "blockNumber": header_number,
        "trustedAnchorHash": "0x" + pinned_anchor_hash.hex(),
        "trustedAnchorNumber": trust["anchorHeaderNumber"],
        "verifiedAncestryHeaderCount": len(ancestry),
        "transactionHash": transaction_hash,
        "transactionInclusion": "verified-at-receipt-index",
        "transactionIndex": tx_index,
        "transactionType": tx_type,
        "recoveredSender": sender,
        "receiptStatus": (_quantity(case["receipt"]["status"], "receipt status")
                          if case["receipt"].get("status") is not None else "pre-Byzantium"),
        "transactionsRoot": "0x" + transactions_root.hex(),
        "receiptsRoot": "0x" + receipts_root.hex(),
        "trustedContextSha256": trust_digest,
        "trustedHeaderSource": {"id": trust["sourceId"], "revision": trust["sourceRevision"]},
        "finality": "unverified",
        "limitations": [
            "trusted context digest must be obtained independently of the case bundle",
            "header and ancestry are not verified against a consensus light client",
            "finality is not established by this adapter",
            "provider agreement and signed observer statements do not authenticate chain state",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", required=True, type=Path)
    parser.add_argument("--trusted-context", required=True, type=Path)
    parser.add_argument("--trusted-context-sha256", required=True)
    parser.add_argument("--now", required=True, type=int, help="Verifier-supplied Unix time for freshness check")
    args = parser.parse_args()
    try:
        case_bytes = args.case.read_bytes()
        if len(case_bytes) > 8 * 1024 * 1024:
            raise InclusionError("case file exceeds size limit")
        case = _json_no_duplicates(case_bytes, "case")
        result = verify_case(case, args.trusted_context.read_bytes(),
                             args.trusted_context_sha256, args.now)
        print(json.dumps(result, sort_keys=True, indent=2))
        return 0
    except (OSError, InclusionError) as exc:
        print(json.dumps({"status": "invalid", "reason": str(exc)}, sort_keys=True), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
