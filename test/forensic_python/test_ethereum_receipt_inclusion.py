"""Receipt proof checks against a fixed synthetic header/receipt fixture."""

from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

import pytest
from eth_hash.auto import keccak
import rlp

TRUST_SHA256 = "8e0f9cea44901c36ddff1f53b84b4f7e325cae36ea19f5a8701a87fba8084d0a"

PROJECT = Path(__file__).resolve().parents[2]
FIXTURE_DIR = PROJECT / "test/forensic_python/fixtures"
CASE_PATH = FIXTURE_DIR / "pro95-stage-c-receipt-case.json"
TRUST_PATH = FIXTURE_DIR / "pro95-stage-c-trust-context.json"
import sys
sys.path.insert(0, str(PROJECT / "scripts"))
from forensic import ethereum_receipt_inclusion as inclusion


@pytest.fixture
def evidence() -> tuple[dict, bytes]:
    return json.loads(CASE_PATH.read_text()), TRUST_PATH.read_bytes()


def verify(case: dict, context: bytes, now: int = 1_750_000_000) -> dict:
    return inclusion.verify_case(case, context, TRUST_SHA256, now)


def test_fixed_header_fixture_verifies_receipt_and_signed_sender(evidence: tuple[dict, bytes]) -> None:
    case, context = evidence
    assert hashlib.sha256(context).hexdigest() == TRUST_SHA256
    result = verify(case, context)
    assert result["status"] == "receipt_inclusion_verified_under_external_header_pin"
    assert result["transactionHash"] == "0x43cd1745500b9362f5b59bebc8b2789d1de4dd1777e788aee2bdf8aee0eaac94"
    assert result["recoveredSender"] == "0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"
    assert result["receiptsRoot"] == "0x84a464ec96f61d82ef160612da1f2894699d23f126c977509dd597637dc128ca"
    assert result["transactionsRoot"] == "0x324d70a782e3f1382b8071c69e4e8192979424ebf98c4ebf4207756bc1ebb1f4"
    assert result["transactionInclusion"] == "verified-at-receipt-index"
    assert result["finality"] == "unverified"


def test_receipt_block_must_link_to_trusted_anchor(evidence: tuple[dict, bytes]) -> None:
    case, context = evidence
    header = rlp.decode(bytes.fromhex(case["blockHeaderRlp"][2:]))
    child = copy.deepcopy(header)
    child[0] = keccak(rlp.encode(header))
    child[8] = (int.from_bytes(child[8], "big") + 1).to_bytes(1, "big")
    child_raw = rlp.encode(child)
    case["headerAncestry"] = ["0x" + child_raw.hex()]

    trust = json.loads(context)
    trust["anchorHeaderHash"] = "0x" + keccak(child_raw).hex()
    trust["anchorHeaderNumber"] = 43
    anchored = (json.dumps(trust, sort_keys=True, indent=2) + "\n").encode()
    result = inclusion.verify_case(case, anchored, hashlib.sha256(anchored).hexdigest(), 1_750_000_000)
    assert result["verifiedAncestryHeaderCount"] == 1
    assert result["trustedAnchorNumber"] == 43

    child[0] = bytes.fromhex("aa" * 32)
    case["headerAncestry"] = ["0x" + rlp.encode(child).hex()]
    with pytest.raises(inclusion.InclusionError, match="parent or height link"):
        inclusion.verify_case(case, anchored, hashlib.sha256(anchored).hexdigest(), 1_750_000_000)


def test_receipt_index_must_match_proof_key(evidence: tuple[dict, bytes]) -> None:
    case, context = evidence
    changed = copy.deepcopy(case)
    changed["transactionIndex"] = 1
    changed["receipt"]["transactionIndex"] = "0x1"
    with pytest.raises(inclusion.InclusionError, match="does not match transaction index"):
        verify(changed, context)


def test_substituted_receipt_value_fails_trie_inclusion(evidence: tuple[dict, bytes]) -> None:
    case, context = evidence
    changed = copy.deepcopy(case)
    changed["receipt"]["cumulativeGasUsed"] = "0x5209"
    with pytest.raises(inclusion.InclusionError, match="proof value differs"):
        verify(changed, context)


def test_transaction_must_be_included_at_same_index_as_receipt(evidence: tuple[dict, bytes]) -> None:
    case, context = evidence
    changed = copy.deepcopy(case)
    changed["transactionProof"] = ["0x" + "00" * 32]
    with pytest.raises(inclusion.InclusionError, match="node hash does not match"):
        verify(changed, context)


def test_wrong_receipt_root_or_header_fails(evidence: tuple[dict, bytes]) -> None:
    case, context = evidence
    changed_root = copy.deepcopy(case)
    changed_root["receiptProof"] = ["0x" + "00" * 32]
    with pytest.raises(inclusion.InclusionError, match="node hash does not match"):
        verify(changed_root, context)

    # Even a context that is re-pinned to a header with a substituted root
    # cannot make the old inclusion path verify under that root.
    forged_root = copy.deepcopy(case)
    fields = rlp.decode(bytes.fromhex(forged_root["blockHeaderRlp"][2:]))
    fields[5] = bytes.fromhex("aa" * 32)
    forged_root["blockHeaderRlp"] = "0x" + rlp.encode(fields).hex()
    trust = json.loads(context)
    rebound_hash = "0x" + keccak(rlp.encode(fields)).hex()
    trust["anchorHeaderHash"] = rebound_hash
    forged_root["receipt"]["blockHash"] = rebound_hash
    rebound_context = (json.dumps(trust, sort_keys=True, indent=2) + "\n").encode()
    with pytest.raises(inclusion.InclusionError, match="node hash does not match"):
        inclusion.verify_case(forged_root, rebound_context,
                              hashlib.sha256(rebound_context).hexdigest(), 1_750_000_000)

    changed_header = copy.deepcopy(case)
    encoded = bytearray.fromhex(changed_header["blockHeaderRlp"][2:])
    encoded[-1] ^= 1
    changed_header["blockHeaderRlp"] = "0x" + encoded.hex()
    with pytest.raises(inclusion.InclusionError, match="receipt block is not an ancestor"):
        verify(changed_header, context)


def test_trust_pin_freshness_and_chain_identity_fail_closed(evidence: tuple[dict, bytes]) -> None:
    case, context = evidence
    with pytest.raises(inclusion.InclusionError, match="independent pin"):
        inclusion.verify_case(case, context, "0" * 64, 1_750_000_000)
    with pytest.raises(inclusion.InclusionError, match="stale"):
        verify(case, context, now=1_900_000_000)

    context_obj = json.loads(context)
    context_obj["chainId"] = 1
    changed_context = (json.dumps(context_obj, sort_keys=True, indent=2) + "\n").encode()
    changed_pin = hashlib.sha256(changed_context).hexdigest()
    with pytest.raises(inclusion.InclusionError, match="chain ID"):
        inclusion.verify_case(case, changed_context, changed_pin, 1_750_000_000)


def test_signed_transaction_mutation_and_finality_claim_are_rejected(evidence: tuple[dict, bytes]) -> None:
    case, context = evidence
    changed_tx = copy.deepcopy(case)
    changed_tx["transactionRaw"] = changed_tx["transactionRaw"][:-2] + "00"
    with pytest.raises(inclusion.InclusionError):
        verify(changed_tx, context)

    trust = json.loads(context)
    trust["finalityStatus"] = "finalized"
    finality_context = (json.dumps(trust, sort_keys=True, indent=2) + "\n").encode()
    with pytest.raises(inclusion.InclusionError, match="cannot establish a finality claim"):
        inclusion.verify_case(case, finality_context, hashlib.sha256(finality_context).hexdigest(), 1_750_000_000)


def test_receipt_log_substitution_and_transaction_type_mismatch_fail(evidence: tuple[dict, bytes]) -> None:
    case, context = evidence
    changed_log = copy.deepcopy(case)
    address = bytes.fromhex("aa" * 20)
    topic = bytes.fromhex("bb" * 32)
    log = {"address": "0x" + address.hex(), "topics": ["0x" + topic.hex()], "data": "0x"}
    bloom = bytearray(256)
    for value in (address, topic):
        digest = keccak(value)
        for offset in (0, 2, 4):
            bit = int.from_bytes(digest[offset:offset + 2], "big") & 2047
            bloom[255 - bit // 8] |= 1 << (bit % 8)
    changed_log["receipt"]["logs"] = [log]
    changed_log["receipt"]["logsBloom"] = "0x" + bytes(bloom).hex()
    with pytest.raises(inclusion.InclusionError, match="proof value differs"):
        verify(changed_log, context)

    changed_type = copy.deepcopy(case)
    changed_type["receipt"]["type"] = "0x1"
    with pytest.raises(inclusion.InclusionError, match="types differ"):
        verify(changed_type, context)


def test_unsupported_typed_transaction_format_fails_closed(evidence: tuple[dict, bytes]) -> None:
    case, context = evidence
    changed = copy.deepcopy(case)
    changed["transactionRaw"] = "0x03" + changed["transactionRaw"][4:]
    with pytest.raises(inclusion.InclusionError, match="unsupported typed transaction type"):
        verify(changed, context)
