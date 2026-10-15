"""Tests for the bounded investigation-task execution boundary."""

from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

import pytest

PROJECT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT / "scripts"))
from forensic import investigation_dispatcher as dispatcher


CASE = PROJECT / (
    "examples/partial-fill/evidence/pro-28-v1/investigation-cases/"
    "groth16-reference-gap-v0/case.json"
)
REFERENCE = PROJECT / "examples/partial-fill/evidence/pro-28-v1/groth16-reference-verification.json"


def load(path: Path) -> dict:
    return json.loads(path.read_text())


def test_case_gap_is_derived_from_current_reconstruction() -> None:
    report = load(PROJECT / "examples/partial-fill/evidence/pro-28-v1/fresh-replay-reconstruction.json")
    gap = dispatcher._require_open_groth16_gap(
        report, "groth16-cryptographic-verification-unverified"
    )
    assert gap == {
        "id": "groth16-cryptographic-verification-unverified",
        "type": "unverified-claim",
        "path": "verificationResults.cryptographicVerification",
    }
    report["verificationResults"]["cryptographicVerification"]["status"] = "verified"
    with pytest.raises(dispatcher.DispatchError, match="absent or no longer applicable"):
        dispatcher._require_open_groth16_gap(
            report, "groth16-cryptographic-verification-unverified"
        )


def test_reference_result_must_match_all_nine_word_successor_proof_identity() -> None:
    report = load(PROJECT / "examples/partial-fill/evidence/pro-28-v1/fresh-replay-reconstruction.json")
    reference = load(REFERENCE)
    assert dispatcher._reference_result_matches(reference, report)
    reference["vkey"] = "0x" + "ff" * 32
    assert not dispatcher._reference_result_matches(reference, report)
    reference = load(REFERENCE)
    reference["statementRoot"] = "0x" + "00" * 32
    assert not dispatcher._reference_result_matches(reference, report)
    reference = load(REFERENCE)
    reference["chainId"] = 84532
    assert not dispatcher._reference_result_matches(reference, report)


def test_task_schema_rejects_arbitrary_commands() -> None:
    case = load(CASE)
    case["task"]["command"] = "sh -c something"
    with pytest.raises(dispatcher.DispatchError, match="unknown fields"):
        dispatcher._validate_case(case)


def test_task_rejects_unsupported_type() -> None:
    case = load(CASE)
    case["task"]["type"] = "arbitrary-shell.v1"
    with pytest.raises(dispatcher.DispatchError, match="unsupported investigation task"):
        dispatcher._validate_case(case)


def test_case_path_escape_rejects() -> None:
    case = load(CASE)
    case["bundle"] = "../../etc"
    with pytest.raises(dispatcher.DispatchError, match="path traversal"):
        dispatcher._validate_case(case)


def test_report_digest_substitution_rejects() -> None:
    case = load(CASE)
    case["expectedReportSha256"] = "sha256:" + "00" * 32
    with pytest.raises(dispatcher.DispatchError, match="digest mismatch"):
        dispatcher._validate_case(case)


def test_succeeded_task_produces_immutable_candidate_bound_to_case(tmp_path: Path,
                                                                  monkeypatch: pytest.MonkeyPatch) -> None:
    reference = load(REFERENCE)

    def fake_run(bundle: Path, proof_report_path: Path, tools: dict[str, str]):
        assert bundle == PROJECT / "examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261013"
        assert set(tools) == {"anvil", "cast"}
        shutil.copyfile(REFERENCE, proof_report_path)
        return 0, False, "PASS"

    monkeypatch.setattr(dispatcher, "_run_fixed_groth16_check", fake_run)
    first = dispatcher.run_case(CASE, tmp_path)
    candidate_path = Path(first["path"])
    candidate = load(candidate_path)
    second = dispatcher.run_case(CASE, tmp_path)
    assert second == first
    assert candidate["execution"]["status"] == "succeeded"
    assert candidate["execution"]["exitCode"] == 0
    assert candidate["disposition"].startswith("candidate-evidence")
    assert candidate["proofReference"] == reference
    assert candidate["case"]["deploymentManifestDigest"] == reference["source"]["deploymentManifestDigest"]
    assert candidate["case"]["operationId"] == reference["operationId"]
    assert candidate["task"]["gapId"] == "groth16-cryptographic-verification-unverified"
    assert candidate["task"]["root"] == dispatcher._sha256(
        dispatcher._canonical_json(candidate["task"]["inputs"])
    )
    assert candidate["task"]["definitionSha256"] == dispatcher._sha256(
        dispatcher._canonical_json(dispatcher.TASK_DEFINITION)
    )
    assert first["sha256"] == dispatcher._sha256(candidate_path.read_bytes())


@pytest.mark.parametrize(
    ("outcome", "expected_status", "expected_exit"),
    [
        ((None, True, ""), "failed", None),
        ((1, False, "unsupported: Anvil unavailable"), "blocked", 1),
        ((7, False, "invalid proof"), "failed", 7),
    ],
)
def test_failure_or_unavailable_provider_is_retained_without_admission(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
    outcome: tuple[int | None, bool, str], expected_status: str, expected_exit: int | None,
) -> None:
    monkeypatch.setattr(dispatcher, "_run_fixed_groth16_check", lambda *_: outcome)
    result = dispatcher.run_case(CASE, tmp_path)
    candidate = load(Path(result["path"]))
    assert candidate["execution"]["status"] == expected_status
    assert candidate["execution"]["exitCode"] == expected_exit
    assert candidate["proofReference"] is None
    assert candidate["disposition"] == "execution record only; no evidence accepted"

