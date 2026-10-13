"""Reconstruct local protected-withdrawal receipt identities for PRO-28.

The verifier consumes only retained raw deployment/proof/broadcast artifacts.
It never treats a caller-provided verification label as evidence.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any


TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
GROUPS = ("deployment", "funding", "activation", "withdrawals")
RECIPIENTS = {
    "0x1111111111111111111111111111111111111111": ("claim-a", 35),
    "0x2222222222222222222222222222222222222222": ("claim-b", 21),
    "0x3333333333333333333333333333333333333333": ("claim-c", 14),
}


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
        tx_hashes = [_norm(row["hash"]) for row in tx_rows]
        receipt_hashes = [_norm(row["transactionHash"]) for row in receipt_rows]
        if len(tx_hashes) != len(set(tx_hashes)) or set(tx_hashes) != set(receipt_hashes):
            raise EvidenceError(f"{group} transaction/receipt identities do not match one-to-one")
        source_files[group] = _sha256(path.read_bytes())
        tx_by_hash = {_norm(row["hash"]): row for row in tx_rows}
        for receipt in receipt_rows:
            tx_hash = _norm(receipt["transactionHash"])
            tx = tx_by_hash[tx_hash]
            tx_context = tx.get("transaction", {})
            if _norm(tx.get("hash", "")) != tx_hash:
                raise EvidenceError("broadcast transaction hash mismatch")
            if _norm(tx_context.get("chainId", "")) != "0x7a69":
                raise EvidenceError("broadcast transaction is not on local chain 31337")
            if receipt.get("status") != "0x1":
                raise EvidenceError(f"transaction {tx_hash} did not succeed")
            for key in ("transactionIndex", "blockNumber", "blockHash"):
                if key not in receipt or receipt[key] is None:
                    raise EvidenceError(f"transaction {tx_hash} receipt lacks {key}")
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


def reconstruct(raw: Path) -> dict[str, Any]:
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
    artifact_paths += [raw / "fork-test.log", raw / "final-state.log"]
    if runtime_observations is not None:
        artifact_paths.append(raw / "runtime-code-observations.json")
    capture_path = raw / "capture-metadata.json"
    capture = _json(capture_path) if capture_path.exists() else None
    if capture is not None:
        if capture.get("schema") != "eae.pro-28-capture-metadata.v1" or capture.get("chainId") != 31337:
            raise EvidenceError("capture metadata schema or chain identity is invalid")
        if capture.get("deploymentManifestDigest") != manifest_digest:
            raise EvidenceError("capture metadata does not bind the retained deployment manifest")
        artifact_paths.append(capture_path)
    return {
        "schema": "eae.pro-28-authentic-local-execution.v1",
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
                "status": "passed by local Foundry execution" if "Suite result: ok. 4 passed; 0 failed; 0 skipped" in test_log else "failed or incomplete",
                "programId": fixture["program_id"],
                "proofSystem": fixture["proof_system"],
                "vkey": fixture["vkey"],
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
                "status": "passed against retained transfer receipts and terminal state reads",
                "sourceDeposit": 80,
                "allocatorFunding": 70,
                "claimPayouts": {item["claimId"]: item["amount"] for item in claimant_events},
                "liabilityClosed": "outstanding_liability=0" in final_state,
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
            "cryptographicVerification": "passed by the pinned SP1 Groth16 verifier in the retained fresh-fork test run",
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
            "finalStateObservation": final_state,
            "carolEntitlement": 14,
            "carolProtectedRefundInThisExecution": 0,
            "note": "Carol's 14-unit entitlement is recorded separately from the 10-unit protected-capital refund described by the superseded V3 Foundry summary.",
        },
        "artifactHashes": {str(path.relative_to(raw)): file_hash(path) for path in artifact_paths},
    }


def verify(raw: Path, expected_report: Path | None = None) -> dict[str, Any]:
    report = reconstruct(raw)
    if expected_report is not None and _json(expected_report) != report:
        raise EvidenceError("reconstructed receipt report differs from retained expected report")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path, help="directory containing retained raw artifacts")
    parser.add_argument("--expected-report", type=Path, help="compare with a previously retained reconstruction")
    parser.add_argument("--write-report", type=Path, help="write the independently reconstructed report")
    args = parser.parse_args()
    try:
        report = verify(args.bundle, args.expected_report)
    except EvidenceError as exc:
        parser.exit(1, f"FAIL: {exc}\n")
    rendered = json.dumps(report, sort_keys=True, indent=2) + "\n"
    if args.write_report:
        args.write_report.write_text(rendered)
    print(f"PASS: {report['successfulReceiptCount']} successful receipts cross-linked")
    print("PASS: sponsor deposit 80, allocator funding 70, claims 35/21/14 bound to receipt and log identities")
    print(f"classification: {report['classification']}")
    if args.write_report:
        print(f"report: {args.write_report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
