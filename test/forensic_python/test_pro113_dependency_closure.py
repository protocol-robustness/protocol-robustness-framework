from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

import pytest

PROJECT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT / "scripts"))
from forensic import pro28_receipts
from forensic import pro113_dependency_closure as sut

SOURCE = PROJECT / "examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261018"
TRUSTED_REPORT_SHA256 = "9ba1ffccdc9acb94fe525dde1ea93e78d3fc986448ae0ccdc5884625cdc14982"
@pytest.fixture
def bundle(tmp_path: Path) -> Path:
    target = tmp_path / "raw"
    shutil.copytree(SOURCE, target)
    return target


def load(path: Path) -> dict:
    return json.loads(path.read_text())


def save(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n")


def test_report_reconstructs_dependencies_but_does_not_claim_qualification(bundle: Path) -> None:
    result = sut.build_closure(bundle, trusted_report_sha256=TRUSTED_REPORT_SHA256)
    assert result["schema"] == "prf.deployment-dependency-closure.v1"
    assert result["result"] == "not_qualified"
    assert result["profile"]["approvalStatus"] == "unknown"
    assert result["chain"]["chainId"] == 31337
    assert result["chain"]["localOnly"] is True
    by_id = {row["dependencyId"]: row for row in result["dependencies"]}
    assert by_id["proof-profile"]["identity"]["publicValuesBytes"] == 288
    assert by_id["proof-profile"]["cryptographicVerification"] == "not_independently_verified"
    assert by_id["configuration-and-registry"]["liveStateStatus"] == "not_rechecked"
    assert by_id["authority-and-upgrade-controls"]["observationStatus"] == "unknown"
    assert by_id["asset-token"]["issuerAndPauseControls"] == "unknown_external_assumptions"
    assert len([key for key in by_id if key.startswith("contract:")]) == 12


def test_report_pin_is_mandatory_and_external(bundle: Path) -> None:
    with pytest.raises(TypeError):
        sut.build_closure(bundle)
    with pytest.raises(pro28_receipts.EvidenceError, match="differs from trusted SHA-256 pin"):
        sut.build_closure(bundle, trusted_report_sha256="00" * 32)


def test_tampered_runtime_observation_is_rejected_before_projection(bundle: Path) -> None:
    path = bundle / "runtime-code-observations.json"
    runtime = load(path)
    runtime["contracts"]["verifier"]["address"] = "0x" + "11" * 20
    save(path, runtime)
    with pytest.raises(pro28_receipts.EvidenceError, match="runtime-code address differs"):
        sut.build_closure(bundle, trusted_report_sha256=TRUSTED_REPORT_SHA256)


def test_requested_live_revalidation_fails_closed(bundle: Path, monkeypatch) -> None:
    def reject_live_change(*args, **kwargs):
        raise pro28_receipts.EvidenceError("live runtime changed")

    monkeypatch.setattr(pro28_receipts, "verify_live_chain_context", reject_live_change)
    with pytest.raises(pro28_receipts.EvidenceError, match="live runtime changed"):
        sut.build_closure(bundle, trusted_report_sha256=TRUSTED_REPORT_SHA256, rpc_url="http://rpc.invalid")


def test_closure_is_deterministically_reconstructed(bundle: Path) -> None:
    first = sut.build_closure(bundle, trusted_report_sha256=TRUSTED_REPORT_SHA256)
    second = sut.build_closure(bundle, trusted_report_sha256=TRUSTED_REPORT_SHA256)
    assert json.dumps(first, sort_keys=True) == json.dumps(second, sort_keys=True)
