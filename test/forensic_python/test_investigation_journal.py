"""Crash recovery and tamper tests for the local investigation journal."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

PROJECT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT / "scripts"))
from forensic import investigation_journal as journal

DEFINITION_ROOT = "sha256:" + "d" * 64


def opened(tmp_path: Path, *, idempotent: bool = True, max_attempts: int = 3):
    return journal.open_session(
        tmp_path, case_id="case-1", case_root="sha256:case", task_root="sha256:task",
        task_id="task-1", definition_root=DEFINITION_ROOT, idempotent=idempotent,
        max_attempts=max_attempts, max_elapsed_seconds=600)


def test_journal_replays_running_task_as_interrupted_and_allows_safe_retry(tmp_path: Path) -> None:
    with opened(tmp_path) as session:
        session.append("task-started", {"attempt": 1, "idempotent": True})
        first_root = session.state["eventRoot"]

    with opened(tmp_path) as resumed:
        assert resumed.state["status"] == "interrupted"
        assert resumed.state["attempt"] == 1
        assert resumed.events[-1]["type"] == "task-interrupted"
        resumed.append("task-started", {"attempt": 2, "idempotent": True})
        resumed.append("task-finished", {
            "outcome": "succeeded", "retryable": False,
            "output": {"path": "candidate.json", "sha256": "sha256:" + "a" * 64}, "reason": None})
        assert resumed.state["status"] == "succeeded"
        assert resumed.state["eventRoot"] != first_root

    with opened(tmp_path) as reconstructed:
        assert reconstructed.state["status"] == "succeeded"
        assert reconstructed.state["sequence"] == 5


def test_process_crash_releases_lock_and_restart_classifies_interrupted_task(
    tmp_path: Path,
) -> None:
    code = (
        "import os,sys; from pathlib import Path; "
        f"sys.path.insert(0, {str(PROJECT / 'scripts')!r}); "
        "from forensic import investigation_journal as j; "
        f"s=j.open_session(Path({str(tmp_path)!r}), case_id='case-1', "
        "case_root='sha256:case', task_root='sha256:task', task_id='task-1', "
        f"definition_root={DEFINITION_ROOT!r}, idempotent=True, max_attempts=3, "
        "max_elapsed_seconds=600); "
        "s.__enter__(); s.append('task-started', {'attempt':1,'idempotent':True}); os._exit(0)"
    )
    process = subprocess.run([sys.executable, "-c", code], check=False,
                             capture_output=True, text=True)
    assert process.returncode == 0
    with opened(tmp_path) as restarted:
        assert restarted.state["status"] == "interrupted"
        assert restarted.events[-1]["type"] == "task-interrupted"
        restarted.append("task-started", {"attempt": 2, "idempotent": True})


def test_non_idempotent_task_cannot_retry_after_interruption(tmp_path: Path) -> None:
    with opened(tmp_path, idempotent=False) as session:
        session.append("task-started", {"attempt": 1, "idempotent": False})
    with opened(tmp_path, idempotent=False) as resumed:
        assert resumed.state["status"] == "interrupted"
        prior_count = len(resumed.events)
        with pytest.raises(journal.JournalError, match="non-idempotent"):
            resumed.append("task-started", {"attempt": 2, "idempotent": False})
        assert len(resumed.events) == prior_count
    with opened(tmp_path, idempotent=False) as checked:
        assert checked.state["status"] == "interrupted"


def test_journal_rejects_tampered_event_and_wrong_task_binding(tmp_path: Path) -> None:
    with opened(tmp_path) as session:
        session.append("task-started", {"attempt": 1, "idempotent": True})
    events_path = tmp_path / "case-1/journal/events.jsonl"
    event = json.loads(events_path.read_text().splitlines()[0])
    event["data"]["taskId"] = "substituted"
    events_path.write_text(journal._canonical_json(event).decode()
                           + events_path.read_text().splitlines()[1] + "\n")
    with pytest.raises(journal.JournalError, match="hash chain/binding"):
        with opened(tmp_path):
            pass

    events_path.unlink()
    (tmp_path / "case-1/journal/state.json").unlink()
    with opened(tmp_path):
        pass
    with pytest.raises(journal.JournalError, match="hash chain/binding"):
        with journal.open_session(
                tmp_path, case_id="case-1", case_root="sha256:case", task_root="sha256:other",
                task_id="task-1", definition_root=DEFINITION_ROOT, idempotent=True,
                max_attempts=3, max_elapsed_seconds=600):
            pass


def test_projection_tamper_fails_closed_but_stale_projection_repairs(tmp_path: Path) -> None:
    with opened(tmp_path) as session:
        previous_projection = (tmp_path / "case-1/journal/state.json").read_bytes()
        session.append("case-metadata-updated", {
            "kind": "hypothesis", "id": "h1", "status": "open",
            "artifactRoot": "sha256:" + "4" * 64})
    projection = tmp_path / "case-1/journal/state.json"
    projection.write_bytes(previous_projection)
    with opened(tmp_path) as recovered:
        assert recovered.state["metadata"][("hypothesis", "h1")]["status"] == "open"
    value = json.loads(projection.read_text())
    value["status"] = "resolved"
    projection.write_text(json.dumps(value))
    with pytest.raises(journal.JournalError, match="projection digest"):
        with opened(tmp_path):
            pass


def test_competing_hypotheses_and_evidence_requests_remain_typed_and_append_only(
    tmp_path: Path,
) -> None:
    with opened(tmp_path) as session:
        session.append("case-metadata-updated", {
            "kind": "hypothesis", "id": "h1", "status": "conflicted",
            "artifactRoot": "sha256:" + "1" * 64})
        session.append("case-metadata-updated", {
            "kind": "hypothesis", "id": "h2", "status": "open",
            "artifactRoot": "sha256:" + "2" * 64})
        session.append("case-metadata-updated", {
            "kind": "evidence-request", "id": "r1", "status": "blocked",
            "artifactRoot": "sha256:" + "3" * 64})
        assert len(session.events) == 4
        assert session.state["metadata"][("hypothesis", "h1")]["status"] == "conflicted"
        assert session.state["metadata"][("hypothesis", "h2")]["status"] == "open"
        assert session.state["metadata"][("evidence-request", "r1")]["status"] == "blocked"


def test_separate_process_reconstructs_same_checkpoint_and_projection_root(tmp_path: Path) -> None:
    with opened(tmp_path) as session:
        session.append("task-started", {"attempt": 1, "idempotent": True})
        session.append("task-finished", {
            "outcome": "succeeded", "retryable": False,
            "output": {"path": "candidate.json", "sha256": "sha256:" + "a" * 64}, "reason": None})
        expected = {"eventRoot": session.state["eventRoot"],
                    "status": session.state["status"], "sequence": session.state["sequence"]}
    code = (
        "import json,sys; from pathlib import Path; "
        f"sys.path.insert(0, {str(PROJECT / 'scripts')!r}); "
        "from forensic import investigation_journal as j; "
        f"s=j.open_session(Path({str(tmp_path)!r}), case_id='case-1', "
        "case_root='sha256:case', task_root='sha256:task', task_id='task-1', "
        f"definition_root={DEFINITION_ROOT!r}, idempotent=True, max_attempts=3, "
        "max_elapsed_seconds=600); "
        "s.__enter__(); print(json.dumps({k:s.state[k] for k in ('eventRoot','status','sequence')})); "
        "s.__exit__(None,None,None)"
    )
    process = subprocess.run([sys.executable, "-c", code], check=True,
                             capture_output=True, text=True, env={**os.environ})
    assert json.loads(process.stdout) == expected


def test_retry_budget_and_cancel_are_explicit_non_success_states(tmp_path: Path) -> None:
    with opened(tmp_path, max_attempts=1) as session:
        session.append("task-started", {"attempt": 1, "idempotent": True})
        session.append("task-finished", {
            "outcome": "blocked", "retryable": True,
            "output": {"path": "blocked.json", "sha256": "sha256:" + "b" * 64},
            "reason": "provider unavailable"})
        with pytest.raises(journal.JournalError, match="attempt budget"):
            session.append("task-started", {"attempt": 2, "idempotent": True})

    with opened(tmp_path / "cancelled") as session:
        session.append("task-cancelled", {"reason": "operator cancelled"})
        assert session.state["status"] == "cancelled"

