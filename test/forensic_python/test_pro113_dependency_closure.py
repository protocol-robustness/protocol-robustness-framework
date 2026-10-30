from __future__ import annotations

import json
import hashlib
import shutil
import sys
from pathlib import Path

import pytest

PROJECT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT / "scripts"))
from forensic import pro28_receipts
from forensic import pro113_dependency_closure as sut

SOURCE = PROJECT / "examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-pro56-20261018"
TERMINAL_STATE = PROJECT / "examples/partial-fill/evidence/pro-28-v1/pro-56-live-state-observation-20261018.json"
TRUSTED_REPORT_SHA256 = "97daff0e6ae6907d4c0bc501f091f41a5d7d160d227cee031c2118ce2f8ba26f"
@pytest.fixture
def bundle(tmp_path: Path) -> Path:
    target = tmp_path / "raw" / "fresh-replay-pro56-20261018"
    shutil.copytree(SOURCE, target)
    return target


def build_closure(bundle: Path, **kwargs) -> dict:
    kwargs.setdefault("terminal_state_observation", TERMINAL_STATE)
    return sut.build_closure(bundle, **kwargs)


def load(path: Path) -> dict:
    return json.loads(path.read_text())


def save(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n")


def proof_check_result(raw: Path, status: str = "verified") -> dict:
    fixture = load(raw / "proof-fixture.json")
    manifest = load(raw / "deployment-manifest.json")
    runtime = load(raw / "runtime-code-observations.json")
    proof = bytes.fromhex(fixture["proof"].removeprefix("0x"))
    public_values = bytes.fromhex(fixture["public_values"].removeprefix("0x"))
    words = pro28_receipts._words(fixture["public_values"])
    return {
        "schema": "prf.source-bound-entitlement-groth16-reference.v1",
        "status": status,
        "referenceCheckExecuted": status != "unsupported",
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
        "verifier": {
            "address": manifest["contracts"]["verifier"],
            "runtimeKeccak256": runtime["contracts"]["verifier"]["keccak256"],
        },
    }


def test_report_reconstructs_dependencies_but_does_not_claim_qualification(bundle: Path) -> None:
    result = build_closure(bundle, trusted_report_sha256=TRUSTED_REPORT_SHA256)
    assert result["schema"] == "prf.deployment-dependency-closure.v1"
    assert result["result"] == "not_qualified"
    assert result["profile"]["approvalStatus"] == "unknown"
    assert result["chain"]["chainId"] == 31337
    assert result["chain"]["localOnly"] is True
    by_id = {row["dependencyId"]: row for row in result["dependencies"]}
    assert by_id["proof-profile"]["identity"]["publicValuesBytes"] == 288
    assert by_id["proof-profile"]["cryptographicVerification"] == "not_run"
    assert by_id["configuration-and-registry"]["liveStateStatus"] == "not_rechecked"
    assert by_id["configuration-and-registry"]["configurationObservationStatus"] == "not_rechecked"
    assert by_id["configuration-and-registry"]["registryObservationStatus"] == "not_rechecked"
    assert by_id["proof-profile"]["identity"]["verifierAddress"] == by_id["contract:verifier"]["address"]
    assert by_id["proof-profile"]["identity"]["verifierRuntimeIdentity"] == by_id["contract:verifier"]["runtimeIdentity"]
    assert by_id["authority-and-upgrade-controls"]["observationStatus"] == "unknown"
    assert by_id["asset-token"]["issuerAndPauseControls"] == "unknown_external_assumptions"
    assert by_id["asset-token"]["failedTransferBehavior"] == "unknown"
    assert by_id["asset-token"]["terminalBalanceObservationStatus"] == "provider_relative_block_pinned_observation_validated"
    assert by_id["asset-token"]["perTransactionBalanceDeltas"] == "not_retained"
    assert result["qualificationGates"]["authorityAndUpgradeControls"] == "unknown"
    assert len([key for key in by_id if key.startswith("contract:")]) == 12
    assert result["qualificationGates"]["ownerApprovedDependencyProfile"] == "unknown"
    assert result["qualificationGates"]["publicChainInclusionAndFinality"] == "not_established"
    assert all(
        row["deploymentReceiptBindingStatus"] == "matched_to_retained_successful_creation_receipt"
        for row in by_id.values() if row["dependencyId"].startswith("contract:")
    )


def test_report_pin_is_mandatory_and_external(bundle: Path) -> None:
    with pytest.raises(TypeError):
        build_closure(bundle)
    with pytest.raises(pro28_receipts.EvidenceError, match="differs from trusted SHA-256 pin"):
        build_closure(bundle, trusted_report_sha256="00" * 32)


def test_tampered_runtime_observation_is_rejected_before_projection(bundle: Path) -> None:
    path = bundle / "runtime-code-observations.json"
    runtime = load(path)
    runtime["contracts"]["verifier"]["address"] = "0x" + "11" * 20
    save(path, runtime)
    with pytest.raises(pro28_receipts.EvidenceError, match="runtime-code address differs"):
        build_closure(bundle, trusted_report_sha256=TRUSTED_REPORT_SHA256)


def test_effective_verifier_runtime_substitution_is_rejected(bundle: Path) -> None:
    path = bundle / "runtime-code-observations.json"
    runtime = load(path)
    runtime["contracts"]["verifier"]["keccak256"] = "0x" + "11" * 32
    save(path, runtime)
    with pytest.raises(pro28_receipts.EvidenceError, match="Keccak identity differs for verifier"):
        build_closure(bundle, trusted_report_sha256=TRUSTED_REPORT_SHA256)


def _repin_manifest_dependents(bundle: Path) -> None:
    manifest_path = bundle / "deployment-manifest.json"
    manifest = load(manifest_path)
    unsigned = dict(manifest)
    unsigned.pop("manifestDigest", None)
    manifest["manifestDigest"] = "sha256:" + hashlib.sha256(
        (json.dumps(unsigned, sort_keys=True, separators=(",", ":")) + "\n").encode()
    ).hexdigest()
    save(manifest_path, manifest)

    fixture_path = bundle / "proof-fixture.json"
    fixture = load(fixture_path)
    fixture["gate_e"]["manifest_digest"] = manifest["manifestDigest"]
    save(fixture_path, fixture)
    fixture_digest = "0x" + hashlib.sha256(fixture_path.read_bytes()).hexdigest()

    provenance_path = bundle / "witness-provenance.json"
    provenance = load(provenance_path)
    provenance["manifest_digest"] = manifest["manifestDigest"]
    save(provenance_path, provenance)

    capture_path = bundle / "capture-metadata.json"
    capture = load(capture_path)
    capture["deploymentManifestDigest"] = manifest["manifestDigest"]
    capture["proof"]["fixtureSha256"] = fixture_digest
    save(capture_path, capture)


def test_stale_configuration_substitution_rejects_even_with_recomputed_outer_digests(bundle: Path) -> None:
    manifest_path = bundle / "deployment-manifest.json"
    manifest = load(manifest_path)
    manifest["configurationRoot"] = "0x" + "22" * 32
    save(manifest_path, manifest)
    _repin_manifest_dependents(bundle)
    with pytest.raises(pro28_receipts.EvidenceError, match="prepared-operation context differs"):
        build_closure(bundle, trusted_report_sha256=TRUSTED_REPORT_SHA256)


def test_requested_live_revalidation_fails_closed(bundle: Path, monkeypatch) -> None:
    def reject_live_change(*args, **kwargs):
        raise pro28_receipts.EvidenceError("live runtime changed")

    monkeypatch.setattr(pro28_receipts, "verify_live_chain_context", reject_live_change)
    with pytest.raises(pro28_receipts.EvidenceError, match="live runtime changed"):
        build_closure(bundle, trusted_report_sha256=TRUSTED_REPORT_SHA256, rpc_url="http://rpc.invalid")


def test_live_receipt_and_runtime_check_does_not_claim_configuration_or_registry_observation(
    bundle: Path, monkeypatch
) -> None:
    monkeypatch.setattr(
        pro28_receipts,
        "verify_live_chain_context",
        lambda *_args, **_kwargs: {"schema": "prf.forensic-live-chain-check.v2", "status": "passed"},
    )
    result = build_closure(
        bundle,
        trusted_report_sha256=TRUSTED_REPORT_SHA256,
        rpc_url="http://rpc.invalid",
    )
    dependency = next(
        row for row in result["dependencies"] if row["dependencyId"] == "configuration-and-registry"
    )
    assert "configuration_and_registry_not_rechecked" in dependency["liveStateStatus"]
    assert dependency["configurationObservationStatus"] == "not_rechecked"
    assert dependency["registryObservationStatus"] == "not_rechecked"
    assert result["qualificationGates"]["configurationState"] == "not_rechecked"
    assert result["qualificationGates"]["verifierRegistryState"] == "not_rechecked"


@pytest.mark.parametrize(
    ("status", "expected"),
    [("verified", "verified"), ("not_verified", "not_verified"), ("unsupported", "unsupported")],
)
def test_optional_pinned_proof_check_is_reported_without_qualifying_fixture(
    bundle: Path, monkeypatch, status: str, expected: str
) -> None:
    monkeypatch.setattr(
        pro28_receipts,
        "verify_groth16_reference",
        lambda raw: proof_check_result(raw, status),
    )
    result = build_closure(
        bundle,
        trusted_report_sha256=TRUSTED_REPORT_SHA256,
        verify_groth16=True,
    )
    proof = next(row for row in result["dependencies"] if row["dependencyId"] == "proof-profile")
    assert proof["cryptographicVerification"] == expected
    assert proof["proofVerification"]["status"] == expected
    assert result["qualificationGates"]["cryptographicProof"] == expected
    assert result["result"] == "not_qualified"


def test_proof_check_is_not_run_unless_explicitly_requested(bundle: Path, monkeypatch) -> None:
    def unexpected(_raw):
        raise AssertionError("proof verifier must be opt-in")

    monkeypatch.setattr(pro28_receipts, "verify_groth16_reference", unexpected)
    result = build_closure(bundle, trusted_report_sha256=TRUSTED_REPORT_SHA256)
    assert result["proofVerification"] is None
    assert result["qualificationGates"]["cryptographicProof"] == "not_run"


def test_cli_exposes_opt_in_reference_proof_check(monkeypatch, capsys, bundle: Path, tmp_path: Path) -> None:
    output = tmp_path / "closure.json"
    called = []
    monkeypatch.setattr(
        pro28_receipts,
        "verify_groth16_reference",
        lambda raw: called.append(raw) or proof_check_result(raw),
    )
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "pro113_dependency_closure.py",
            str(bundle),
            "--trusted-report-sha256",
            TRUSTED_REPORT_SHA256,
            "--terminal-state-observation",
            str(TERMINAL_STATE),
            "--verify-groth16",
            "--output",
            str(output),
        ],
    )
    assert sut.main() == 0
    assert called == [bundle]
    result = load(output)
    assert result["proofVerification"]["status"] == "verified"
    assert result["qualificationGates"]["cryptographicProof"] == "verified"
    assert result["result"] == "not_qualified"
    assert capsys.readouterr().out == ""


def test_substituted_proof_verification_result_is_rejected(bundle: Path, monkeypatch) -> None:
    def substituted(raw):
        result = proof_check_result(raw)
        result["proofSha256"] = "0x" + "11" * 32
        return result

    monkeypatch.setattr(pro28_receipts, "verify_groth16_reference", substituted)
    with pytest.raises(pro28_receipts.EvidenceError, match="does not bind the current proofSha256"):
        build_closure(
            bundle,
            trusted_report_sha256=TRUSTED_REPORT_SHA256,
            verify_groth16=True,
        )


def test_closure_is_deterministically_reconstructed(bundle: Path) -> None:
    first = build_closure(bundle, trusted_report_sha256=TRUSTED_REPORT_SHA256)
    second = build_closure(bundle, trusted_report_sha256=TRUSTED_REPORT_SHA256)
    assert json.dumps(first, sort_keys=True) == json.dumps(second, sort_keys=True)
