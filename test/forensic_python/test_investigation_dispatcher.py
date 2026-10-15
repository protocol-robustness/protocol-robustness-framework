"""Tests for the bounded investigation-task execution boundary."""

from __future__ import annotations

import json
import shutil
import sys
from datetime import datetime, timedelta, timezone
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
    calls = []

    def fake_run(bundle: Path, proof_report_path: Path, tools: dict[str, str]):
        calls.append(1)
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
    assert len(calls) == 1, "replaying a succeeded task must not execute it again"
    replay = dispatcher.run_case(CASE, tmp_path, verify_journal=True)
    assert replay["status"] == "succeeded"
    assert replay["historyEventCount"] == 3
    assert replay["storedProjectionCurrent"] is True
    assert len(calls) == 1, "read-only journal verification must not execute the task"


def test_retryable_failure_requires_explicit_retry_and_reuses_case_journal(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    reference = load(REFERENCE)
    outcomes = [(1, False, "unsupported: temporary verifier provider unavailable")]

    monkeypatch.setattr(dispatcher, "_pinned_toolchain", lambda _bundle: ({"anvil": "sha256:a", "cast": "sha256:c"}, None))

    def fake_run(_bundle: Path, proof_report_path: Path, _tools: dict[str, str]):
        if outcomes:
            return outcomes.pop(0)
        shutil.copyfile(REFERENCE, proof_report_path)
        return 0, False, "PASS"

    monkeypatch.setattr(dispatcher, "_run_fixed_groth16_check", fake_run)
    first = dispatcher.run_case(CASE, tmp_path)
    first_candidate = load(Path(first["path"]))
    assert first_candidate["execution"]["status"] == "blocked"
    assert first_candidate["disposition"] == "execution record only; no evidence accepted"

    # A retryable result is retained and not retried unless the operator opts in.
    assert dispatcher.run_case(CASE, tmp_path) == first
    second = dispatcher.run_case(CASE, tmp_path, retry=True)
    second_candidate = load(Path(second["path"]))
    assert second_candidate["execution"]["status"] == "succeeded"
    journal_path = tmp_path / "pro-28-groth16-reference-gap-v0/journal/events.jsonl"
    events = [json.loads(line) for line in journal_path.read_text().splitlines()]
    assert [event["type"] for event in events] == [
        "case-opened", "task-started", "task-finished", "task-started", "task-finished"
    ]
    assert len([event for event in events if event["type"] == "task-started"]) == 2
    assert events[-2]["data"]["attempt"] == 2


def test_retry_after_elapsed_case_deadline_is_recorded_as_budget_exceeded(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    old_time = (datetime.now(timezone.utc) - timedelta(seconds=700)).isoformat().replace("+00:00", "Z")
    monkeypatch.setattr(dispatcher.journal, "_now", lambda: old_time)
    monkeypatch.setattr(dispatcher, "_pinned_toolchain", lambda _bundle: (None, "blocked: temporary tools unavailable"))
    first = dispatcher.run_case(CASE, tmp_path)
    assert load(Path(first["path"]))["execution"]["status"] == "failed"
    with pytest.raises(dispatcher.DispatchError, match="terminal: budget-exceeded"):
        dispatcher.run_case(CASE, tmp_path, retry=True)
    journal_path = tmp_path / "pro-28-groth16-reference-gap-v0/journal/events.jsonl"
    events = [json.loads(line) for line in journal_path.read_text().splitlines()]
    assert events[-1]["type"] == "task-budget-exceeded"
    assert events[-1]["data"]["budget"] == "elapsed-seconds"


def test_cli_schema_exposes_explicit_retry_cancel_and_metadata_controls() -> None:
    import inspect
    source = inspect.getsource(dispatcher.main)
    assert 'parser.add_argument("--retry"' in source
    assert 'parser.add_argument("--cancel-reason"' in source
    assert 'parser.add_argument("--metadata-event"' in source
    assert 'parser.add_argument("--verify-journal"' in source


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

