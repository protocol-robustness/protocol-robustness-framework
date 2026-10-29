#!/usr/bin/env python3
"""Project validated local execution evidence into a dependency-closure report.

This adapter deliberately reuses the PRO-28 receipt reconstruction and its
out-of-band report pin. It does not create an approved dependency profile or
upgrade local RPC observations into authenticated chain state.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from forensic import pro28_receipts


SCHEMA = "prf.deployment-dependency-closure.v1"
REPO_ROOT = Path(__file__).resolve().parents[2]


def _source_identity(path: Path) -> dict[str, str]:
    resolved = path.resolve()
    try:
        relative = resolved.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        relative = resolved.name
    return {
        "path": relative,
        "sha256": hashlib.sha256(resolved.read_bytes()).hexdigest(),
    }


def _deployment_receipt_bindings(raw: Path, manifest: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """Project creation receipts already validated by the PRO-28 receipt loader."""
    transactions, _ = pro28_receipts._load_receipts(raw)
    creations: dict[str, list[dict[str, Any]]] = {}
    for tx_hash, entry in transactions.items():
        receipt = entry["receipt"]
        transaction = entry["transaction"].get("transaction", {})
        created = receipt.get("contractAddress")
        if entry["group"] != "deployment" or transaction.get("to") is not None or not created:
            continue
        creations.setdefault(created.lower(), []).append({
            "transactionHash": tx_hash,
            "blockNumber": pro28_receipts._int(receipt["blockNumber"]),
            "blockHash": receipt["blockHash"].lower(),
            "transactionIndex": pro28_receipts._int(receipt["transactionIndex"]),
            "status": pro28_receipts._int(receipt["status"]),
            "deployer": transaction["from"].lower(),
            "contractAddress": created.lower(),
            "chainId": pro28_receipts._int(transaction["chainId"]),
        })

    result: dict[str, dict[str, Any]] = {}
    for role, address in manifest.get("contracts", {}).items():
        matches = creations.get(address.lower(), [])
        if len(matches) > 1:
            raise pro28_receipts.EvidenceError(
                f"deployment evidence has multiple creation receipts for contract {role}"
            )
        if len(matches) == 1:
            result[role] = matches[0]
    return result


def _validate_proof_check_binding(
    raw: Path, manifest: dict[str, Any], proof_check: dict[str, Any]
) -> None:
    """Ensure the reused verifier result is for this exact proof and deployment."""
    if proof_check.get("schema") != "prf.source-bound-entitlement-groth16-reference.v1":
        raise pro28_receipts.EvidenceError("unexpected Groth16 reference-verification result schema")
    if proof_check.get("status") not in {"verified", "not_verified", "unsupported"}:
        raise pro28_receipts.EvidenceError("Groth16 reference-verification result has an unknown status")
    fixture = pro28_receipts._json(raw / "proof-fixture.json")
    runtime = pro28_receipts._json(raw / "runtime-code-observations.json")
    proof = bytes.fromhex(fixture["proof"].removeprefix("0x"))
    public_values = bytes.fromhex(fixture["public_values"].removeprefix("0x"))
    words = pro28_receipts._words(fixture["public_values"])
    verifier = runtime["contracts"]["verifier"]
    expected = {
        "chainId": manifest["chainId"],
        "programId": fixture["program_id"],
        "proofSystem": fixture["proof_system"],
        "vkey": fixture["vkey"],
        "elfSha256": fixture["elf_sha256"],
        "publicValuesSchema": fixture["public_values_schema"],
        "proofSha256": "0x" + hashlib.sha256(proof).hexdigest(),
        "publicValuesSha256": "0x" + hashlib.sha256(public_values).hexdigest(),
        "fixtureSha256": "0x" + hashlib.sha256((raw / "proof-fixture.json").read_bytes()).hexdigest(),
        "witnessSha256": fixture["gate_e"]["witness_sha256"],
        "operationId": manifest["operationId"],
        "preparedOperationRoot": words[1],
        "reserveStateCommitment": words[2],
        "statementRoot": words[3],
        "configurationRoot": manifest["configurationRoot"],
    }
    for key, value in expected.items():
        observed = proof_check.get(key)
        if isinstance(value, str) and isinstance(observed, str):
            matches = observed.lower() == value.lower()
        else:
            matches = observed == value
        if not matches:
            raise pro28_receipts.EvidenceError(
                f"Groth16 reference-verification result does not bind the current {key}"
            )
    verifier_identity = proof_check.get("verifier", {})
    if (
        verifier_identity.get("address", "").lower() != manifest["contracts"]["verifier"].lower()
        or verifier_identity.get("runtimeKeccak256", "").lower() != verifier["keccak256"].lower()
    ):
        raise pro28_receipts.EvidenceError(
            "Groth16 reference-verification result does not bind the deployed verifier identity"
        )


def build_closure(
    raw: Path,
    *,
    trusted_report_sha256: str,
    rpc_url: str | None = None,
    min_confirmations: int | None = None,
    verify_groth16: bool = False,
    terminal_state_observation: Path | None = None,
) -> dict[str, Any]:
    """Reconstruct dependencies; report missing approval/effective-state inputs as unknown."""
    report = pro28_receipts.verify(
        raw,
        expected_report_sha256=trusted_report_sha256,
        terminal_state_observation=terminal_state_observation,
    )
    pin = pro28_receipts.verification_result(
        report, mode="verify", trusted_report_sha256=trusted_report_sha256
    )
    raw = Path(raw)
    manifest = pro28_receipts._json(raw / "deployment-manifest.json")
    deployment_receipts = _deployment_receipt_bindings(raw, manifest)
    capture_path = raw / "capture-metadata.json"
    capture = pro28_receipts._json(capture_path) if capture_path.is_file() else {}
    live_check = (
        pro28_receipts.verify_live_chain_context(raw, rpc_url, min_confirmations)
        if rpc_url is not None else None
    )
    proof_check = (
        pro28_receipts.verify_groth16_reference(raw)
        if verify_groth16 else None
    )
    if proof_check is not None:
        _validate_proof_check_binding(raw, manifest, proof_check)

    runtime = report["runtimeCodeIdentity"]
    runtime_rows = runtime.get("contracts", {})
    contracts = []
    for role, address in sorted(report["contractAddresses"].items()):
        observed = runtime_rows.get(role)
        contracts.append({
            "dependencyId": f"contract:{role}",
            "address": address.lower(),
            "runtimeIdentity": observed,
            "observationStatus": "observed_and_hash_checked" if observed else "unknown",
            "approvedIdentityStatus": "unknown",
            "deploymentReceiptBindingStatus": (
                "matched_to_retained_successful_creation_receipt"
                if role in deployment_receipts else "unknown_no_matching_creation_receipt"
            ),
            "deploymentReceipt": deployment_receipts.get(role),
            "libraryAndImmutableReferenceStatus": "runtime_bytes_observed; build-link provenance unknown",
            "proxyImplementationStatus": "unknown",
            "upgradeAuthorityStatus": "unknown",
            "evidenceRefs": ["deployment-manifest.json", "runtime-code-observations.json"]
            if observed else ["deployment-manifest.json"],
        })

    proof = report["proof"]
    verification = report["verificationResults"]
    verifier_contract = next(
        (contract for contract in contracts if contract["dependencyId"] == "contract:verifier"),
        None,
    )
    proof_verification_status = (
        proof_check["status"] if proof_check is not None else "not_run"
    )
    terminal_state_status = verification["economicReconciliation"].get(
        "terminalStateObservation", {}
    ).get("status")
    terminal_balance_status = (
        "provider_relative_block_pinned_observation_validated"
        if terminal_state_status == "validated provider-relative local eth_call observation"
        else "not_independently_observed"
    )
    chain_identity = {
        "chainId": report["chainId"],
        "localOnly": True,
        "environmentId": capture.get("captureId"),
        "genesisBlockHash": capture.get("localChainIdentity", {}).get("genesisBlockHash"),
        "receiptObservation": verification["executionObservation"]["status"],
        "trustStatus": "out_of_band_reconstruction_digest_matched; local provider observation only",
    }
    dependencies = [
        {
            "dependencyId": "chain-instance",
            "observationStatus": "observed_local_only",
            "identity": chain_identity,
            "approvalStatus": "unknown",
            "evidenceRefs": ["deployment-manifest.json", "capture-metadata.json", "broadcast/"],
        },
        *contracts,
        {
            "dependencyId": "proof-profile",
            "observationStatus": "bound_between_manifest_witness_and_fixture",
            "identity": {
                "programId": proof["programId"],
                "vkey": proof["vkey"],
                "elfSha256": proof["elfSha256"],
                "publicValuesSchema": proof["schema"],
                "publicValuesBytes": proof["publicValuesBytes"],
                "proofSha256": proof["proofSha256"],
                "verifierAddress": verifier_contract["address"] if verifier_contract else None,
                "verifierRuntimeIdentity": verifier_contract["runtimeIdentity"] if verifier_contract else None,
            },
            "approvalStatus": "unknown",
            "cryptographicVerification": proof_verification_status,
            "evidenceRefs": ["proof-fixture.json", "witness.json", "deployment-manifest.json"],
            "proofVerification": proof_check,
        },
        {
            "dependencyId": "configuration-and-registry",
            "observationStatus": "manifest_bound_only",
            "identity": {
                "configurationRoot": manifest.get("configurationRoot"),
                "configurationEpoch": manifest.get("configurationEpoch"),
                "registryRoot": manifest.get("registryRoot"),
                "policyRoot": manifest.get("policyRoot"),
            },
            "approvalStatus": "unknown",
            "liveStateStatus": (
                "receipts_and_runtime_rechecked_against_one_rpc; configuration_and_registry_not_rechecked"
                if live_check else "not_rechecked"
            ),
            "configurationObservationStatus": "not_rechecked",
            "registryObservationStatus": "not_rechecked",
            "liveObservation": live_check,
            "evidenceRefs": ["deployment-manifest.json", "proof-fixture.json"],
        },
        {
            "dependencyId": "authority-and-upgrade-controls",
            "observationStatus": "unknown",
            "identity": {"deployer": manifest.get("deployer")},
            "approvalStatus": "unknown",
            "reason": "The retained bundle does not independently establish effective role holders, proxy/admin paths, or external upgrade controls.",
            "evidenceRefs": ["deployment-manifest.json"],
        },
        {
            "dependencyId": "asset-token",
            "observationStatus": "transfer_events_cross_linked",
            "identity": {"address": report["contractAddresses"].get("token", "").lower()},
            "observedTransferEvidence": "exact transfer events are cross-linked for this local execution",
            "terminalBalanceObservationStatus": terminal_balance_status,
            "perTransactionBalanceDeltas": "not_retained",
            "failedTransferBehavior": "unknown",
            "issuerAndPauseControls": "unknown_external_assumptions",
            "evidenceRefs": ["broadcast/", "deployment-manifest.json"]
            + (["terminal-state-observation.json"] if terminal_state_observation is not None else []),
        },
    ]
    unknowns = [
        "No owner-approved dependency profile is pinned; matching the reconstruction digest is integrity evidence, not approval.",
        "Proxy/effective implementation relationships and upgrade authorities are not established by this bundle.",
        "Configuration and registry state are bound by manifest claims but not re-observed from a live chain here.",
        (
            "The proof is structurally bound, but cryptographic verification was not requested."
            if proof_check is None
            else "The proof was checked by the pinned local reference verifier; this does not establish approved program admission or source authority."
            if proof_check["status"] == "verified"
            else "The requested pinned local proof check did not establish proof validity."
        ),
        "Local Anvil execution does not establish public-chain inclusion, consensus, safe/finalized status, or finality.",
        "ERC-20 transfer behavior is observed for these transfers only; issuer pause/freeze/blacklist/upgrade rights remain unknown.",
    ]
    return {
        "schema": SCHEMA,
        "evaluator": {
            "identityKind": "source-file-digests; commit identity not asserted",
            "files": [
                _source_identity(Path(__file__)),
                _source_identity(Path(pro28_receipts.__file__)),
            ],
            "options": {
                "trustedReceiptReportSha256": trusted_report_sha256.removeprefix("sha256:").lower(),
                "liveRpcRevalidationRequested": rpc_url is not None,
                "groth16ReferenceVerificationRequested": verify_groth16,
                "minimumConfirmations": min_confirmations,
                "terminalStateObservationProvided": terminal_state_observation is not None,
            },
        },
        "profile": {
            "id": "pro28-local-reconstruction-pin-v1",
            "approvalStatus": "unknown",
            "trustAnchor": pin["trustContext"],
        },
        "result": "not_qualified",
        "source": report["source"],
        "sourceBindingStatus": "bundle_source_claim_retained; evaluator_file_digests_recorded; source_checkout_commit_not_asserted",
        "chain": chain_identity,
        "deploymentManifestDigest": report["deploymentManifestDigest"],
        "operation": {
            "operationId": report["operationId"],
            "preparedOperationRoot": report["preparedOperationRoot"],
            "configurationRoot": manifest.get("configurationRoot"),
            "configurationEpoch": manifest.get("configurationEpoch"),
        },
        "dependencies": dependencies,
        "executionSummary": {
            "successfulReceiptCount": report["successfulReceiptCount"],
            "receiptReconstructionStatus": verification["executionObservation"]["status"],
            "economicReconciliationStatus": verification["economicReconciliation"]["status"],
            "claimPayouts": report["accounting"]["claimPayouts"],
        },
        "qualificationGates": {
            "ownerApprovedDependencyProfile": "unknown",
            "chainInstance": "observed_local_only",
            "contractRuntimeApproval": "unknown",
            "configurationState": "not_rechecked",
            "verifierRegistryState": "not_rechecked",
            "authorityAndUpgradeControls": "unknown",
            "cryptographicProof": proof_verification_status,
            "localTerminalAccounting": terminal_balance_status,
            "publicChainInclusionAndFinality": "not_established",
        },
        "proofVerification": proof_check,
        "liveRevalidation": live_check or {
            "status": "not_requested",
            "meaning": "No current-state or canonicality claim is made from retained artifacts alone.",
        },
        "detectedOnly": [
            "The receipt verifier can detect substituted chain, receipt, proof, and captured runtime evidence; those checks are retrospective and do not prevent a transaction.",
        ],
        "unknowns": unknowns,
        "limitations": [
            "The reconstruction digest is an integrity pin only; it does not authorize the profile or establish chain truth.",
            "No new canonical root is introduced by this report.",
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw", type=Path, help="retained PRO-28 raw execution bundle")
    parser.add_argument("--trusted-report-sha256", required=True,
                        help="independently sourced pin for the existing reconstructed report")
    parser.add_argument("--rpc-url", help="optional RPC for provider-relative current-chain revalidation")
    parser.add_argument("--min-confirmations", type=int,
                        help="optional receipt confirmation threshold passed to the existing live-chain verifier")
    parser.add_argument("--verify-groth16", action="store_true",
                        help="optionally recheck the exact proof through the pinned verifier on disposable local Anvil")
    parser.add_argument("--terminal-state-observation", type=Path,
                        help="optional block-pinned state observation validated by the existing PRO-28 verifier")
    parser.add_argument("--output", type=Path, help="write JSON report here; stdout if omitted")
    args = parser.parse_args(argv)
    if args.min_confirmations is not None and args.rpc_url is None:
        parser.error("--min-confirmations requires --rpc-url")
    try:
        result = build_closure(
            args.raw,
            trusted_report_sha256=args.trusted_report_sha256,
            rpc_url=args.rpc_url,
            min_confirmations=args.min_confirmations,
            verify_groth16=args.verify_groth16,
            terminal_state_observation=args.terminal_state_observation,
        )
    except (pro28_receipts.EvidenceError, OSError, ValueError) as exc:
        parser.error(str(exc))
    rendered = json.dumps(result, sort_keys=True, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered)
    else:
        sys.stdout.write(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
