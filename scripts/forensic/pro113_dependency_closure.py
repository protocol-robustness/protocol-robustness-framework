#!/usr/bin/env python3
"""Project validated local execution evidence into a dependency-closure report.

This adapter deliberately reuses the PRO-28 receipt reconstruction and its
out-of-band report pin. It does not create an approved dependency profile or
upgrade local RPC observations into authenticated chain state.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from forensic import pro28_receipts


SCHEMA = "prf.deployment-dependency-closure.v1"


def build_closure(
    raw: Path,
    *,
    trusted_report_sha256: str,
    rpc_url: str | None = None,
    min_confirmations: int | None = None,
) -> dict[str, Any]:
    """Reconstruct dependencies; report missing approval/effective-state inputs as unknown."""
    report = pro28_receipts.verify(raw, expected_report_sha256=trusted_report_sha256)
    pin = pro28_receipts.verification_result(
        report, mode="verify", trusted_report_sha256=trusted_report_sha256
    )
    raw = Path(raw)
    manifest = pro28_receipts._json(raw / "deployment-manifest.json")
    capture_path = raw / "capture-metadata.json"
    capture = pro28_receipts._json(capture_path) if capture_path.is_file() else {}
    live_check = (
        pro28_receipts.verify_live_chain_context(raw, rpc_url, min_confirmations)
        if rpc_url is not None else None
    )

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
            "deploymentReceiptBindingStatus": "unknown_per_contract",
            "libraryAndImmutableReferenceStatus": "runtime_bytes_observed; build-link provenance unknown",
            "proxyImplementationStatus": "unknown",
            "upgradeAuthorityStatus": "unknown",
            "evidenceRefs": ["deployment-manifest.json", "runtime-code-observations.json"]
            if observed else ["deployment-manifest.json"],
        })

    proof = report["proof"]
    verification = report["verificationResults"]
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
            },
            "approvalStatus": "unknown",
            "cryptographicVerification": verification["cryptographicVerification"]["status"],
            "evidenceRefs": ["proof-fixture.json", "witness.json", "deployment-manifest.json"],
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
            "liveStateStatus": "chain_receipts_and_code_rechecked_against_one_rpc" if live_check else "not_rechecked",
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
            "failedTransferBehavior": "unknown",
            "issuerAndPauseControls": "unknown_external_assumptions",
            "evidenceRefs": ["broadcast/", "deployment-manifest.json"],
        },
    ]
    unknowns = [
        "No owner-approved dependency profile is pinned; matching the reconstruction digest is integrity evidence, not approval.",
        "Proxy/effective implementation relationships and upgrade authorities are not established by this bundle.",
        "Configuration and registry state are bound by manifest claims but not re-observed from a live chain here.",
        "The proof is structurally bound, but cryptographic verification is not independently performed by the receipt verifier.",
        "Local Anvil execution does not establish public-chain inclusion, consensus, safe/finalized status, or finality.",
        "ERC-20 transfer behavior is observed for these transfers only; issuer pause/freeze/blacklist/upgrade rights remain unknown.",
    ]
    return {
        "schema": SCHEMA,
        "profile": {
            "id": "pro28-local-reconstruction-pin-v1",
            "approvalStatus": "unknown",
            "trustAnchor": pin["trustContext"],
        },
        "result": "not_qualified",
        "source": report["source"],
        "sourceBindingStatus": "declared_by_existing_reconstruction; checkout identity requires separate verification",
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
