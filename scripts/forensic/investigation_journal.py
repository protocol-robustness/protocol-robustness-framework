"""Append-only, replayable local investigation case journal.

The journal is coordination state, not PRF evidence and not proof of a finding.
Every task output remains candidate evidence until independently validated.
"""

from __future__ import annotations

import copy
import fcntl
import hashlib
import json
import os
import re
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

JOURNAL_SCHEMA = "prf.investigation-case-journal.v1"
PROJECTION_SCHEMA = "prf.investigation-case-state.v1"
MAX_JOURNAL_BYTES = 4 * 1024 * 1024
MAX_EVENTS = 4096
METADATA_STATUSES = {"open", "blocked", "conflicted", "resolved"}


class JournalError(ValueError):
    """Journal corruption, incompatible resume, or invalid transition."""


def _canonical_json(value: Any) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":"),
                        ensure_ascii=False, allow_nan=False) + "\n").encode("utf-8")


def _digest(value: Any) -> str:
    payload = value if isinstance(value, bytes) else _canonical_json(value)
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


def _elapsed_since(timestamp: str | None) -> float:
    if timestamp is None:
        return 0.0
    started = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    return max(0.0, (datetime.now(timezone.utc) - started).total_seconds())


def _event_body(event: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in event.items() if key != "eventRoot"}


def _reject_json_constant(value: str) -> None:
    raise ValueError(f"non-JSON numeric constant {value}")


def _new_event(state: dict[str, Any], event_type: str, data: dict[str, Any]) -> dict[str, Any]:
    event = {"schema": JOURNAL_SCHEMA, "sequence": state["sequence"] + 1,
             "caseRoot": state["caseRoot"], "taskRoot": state["taskRoot"],
             "taskId": state["taskId"], "executionId": state["executionId"],
             "previousEventRoot": state["eventRoot"], "occurredAt": _now(),
             "type": event_type, "data": data}
    event["eventRoot"] = _digest(_event_body(event))
    return event


def _empty_state(case_id: str, case_root: str, task_root: str, task_id: str) -> dict[str, Any]:
    execution_id = _digest({"caseRoot": case_root, "taskRoot": task_root, "taskId": task_id})
    return {"schema": PROJECTION_SCHEMA, "caseId": case_id, "caseRoot": case_root,
            "taskRoot": task_root, "taskId": task_id, "executionId": execution_id,
            "sequence": 0,
            "eventRoot": None, "status": "new", "attempt": 0,
            "firstStartedAt": None, "lastOutput": None, "metadata": {}}


def _apply(state: dict[str, Any], event: dict[str, Any]) -> dict[str, Any]:
    event_type, data = event["type"], event["data"]
    if event_type == "case-opened":
        if state["status"] != "new" or set(data) != {"caseId", "taskId", "taskDefinitionRoot", "policy"}:
            raise JournalError("invalid or duplicate case-opened event")
        if data["caseId"] != state["caseId"] or data["taskId"] != state["taskId"]:
            raise JournalError("case-opened event does not match journal identity")
        policy = data["policy"]
        if (not isinstance(data["taskDefinitionRoot"], str)
                or not re.fullmatch(r"sha256:[0-9a-f]{64}", data["taskDefinitionRoot"])
                or not isinstance(policy, dict)
                or set(policy) != {"idempotent", "maxAttempts", "maxElapsedSeconds"}
                or not isinstance(policy["idempotent"], bool)
                or type(policy["maxAttempts"]) is not int or policy["maxAttempts"] < 1
                or type(policy["maxElapsedSeconds"]) is not int or policy["maxElapsedSeconds"] < 1):
            raise JournalError("case-opened task definition or policy is malformed")
        state.update(status="ready", taskDefinitionRoot=data["taskDefinitionRoot"], policy=data["policy"])
    elif event_type == "task-started":
        if state["status"] not in {"ready", "failed", "blocked", "interrupted"}:
            raise JournalError(f"task cannot start from {state['status']}")
        if (set(data) != {"attempt", "idempotent"} or data["attempt"] != state["attempt"] + 1
                or type(data["attempt"]) is not int or not isinstance(data["idempotent"], bool)):
            raise JournalError("task attempt sequence is invalid")
        if data["attempt"] > state["policy"]["maxAttempts"]:
            raise JournalError("task attempt budget is exhausted")
        if state["attempt"] and not (data["idempotent"] and state["policy"]["idempotent"]):
            raise JournalError("non-idempotent task cannot be retried")
        if state["status"] in {"failed", "blocked", "interrupted"} and not state.get("retryAllowed", False):
            raise JournalError("task retry is not authorized by its prior outcome")
        state.update(status="running", attempt=data["attempt"], retryAllowed=False)
        state["firstStartedAt"] = state["firstStartedAt"] or event["occurredAt"]
    elif event_type == "task-interrupted":
        if state["status"] != "running" or set(data) != {"classification"} \
                or data["classification"] != "process-stop-before-terminal-record":
            raise JournalError("invalid interrupted-task classification")
        state.update(status="interrupted", retryAllowed=bool(state["policy"]["idempotent"]))
    elif event_type == "task-finished":
        allowed = {"outcome", "retryable", "output", "reason"}
        if state["status"] != "running" or set(data) != allowed:
            raise JournalError("invalid task-finished event")
        if (not isinstance(data["retryable"], bool)
                or (data["reason"] is not None and not isinstance(data["reason"], str))
                or not isinstance(data["output"], dict)
                or set(data["output"]) != {"path", "sha256"}
                or not all(isinstance(data["output"][key], str) and data["output"][key]
                           for key in ("path", "sha256"))
                or not re.fullmatch(r"sha256:[0-9a-f]{64}", data["output"]["sha256"])):
            raise JournalError("task-finished output/retry metadata is malformed")
        if not isinstance(data["outcome"], str) \
                or data["outcome"] not in {"succeeded", "failed", "blocked", "budget-exceeded"}:
            raise JournalError("unsupported task outcome")
        if data["outcome"] == "succeeded" and (data["retryable"] or not data["output"]):
            raise JournalError("successful task must have an output and cannot be retryable")
        state.update(status=data["outcome"], lastOutput=data["output"],
                     retryAllowed=bool(data["retryable"]) and bool(state["policy"]["idempotent"]))
    elif event_type == "task-cancelled":
        if state["status"] not in {"ready", "failed", "blocked", "interrupted"} \
                or set(data) != {"reason"} or not isinstance(data["reason"], str) or not data["reason"]:
            raise JournalError("invalid task cancellation")
        state.update(status="cancelled", retryAllowed=False, cancellationReason=data["reason"])
    elif event_type == "task-budget-exceeded":
        if state["status"] not in {"ready", "failed", "blocked", "interrupted"} \
                or set(data) != {"budget", "limit"}:
            raise JournalError("invalid budget-exceeded event")
        if (not isinstance(data["budget"], str)
                or data["budget"] not in {"attempts", "elapsed-seconds"}
                or type(data["limit"]) is not int or data["limit"] < 1):
            raise JournalError("budget-exceeded event has an invalid budget")
        state.update(status="budget-exceeded", retryAllowed=False, exceededBudget=data)
    elif event_type == "case-metadata-updated":
        if set(data) != {"kind", "id", "status", "artifactRoot"}:
            raise JournalError("invalid case metadata event")
        if not isinstance(data["kind"], str) or data["kind"] not in {"hypothesis", "evidence-request"} \
                or not isinstance(data["status"], str) or data["status"] not in METADATA_STATUSES \
                or not isinstance(data["id"], str) or not data["id"] \
                or not isinstance(data["artifactRoot"], str) \
                or not re.fullmatch(r"sha256:[0-9a-f]{64}", data["artifactRoot"]):
            raise JournalError("unsupported case metadata value")
        state["metadata"][(data["kind"], data["id"])] = {
            "status": data["status"], "artifactRoot": data["artifactRoot"]}
    else:
        raise JournalError(f"unknown journal event type {event_type!r}")
    state["sequence"] = event["sequence"]
    state["eventRoot"] = event["eventRoot"]
    return state


def _replay(journal: Path, case_id: str, case_root: str, task_root: str,
            task_id: str) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    state = _empty_state(case_id, case_root, task_root, task_id)
    if not journal.exists():
        return state, []
    raw = journal.read_bytes()
    if len(raw) > MAX_JOURNAL_BYTES or (raw and not raw.endswith(b"\n")):
        raise JournalError("journal exceeds size limit or ends with a partial record")
    lines = raw.splitlines()
    if len(lines) > MAX_EVENTS:
        raise JournalError("journal exceeds event limit")
    events: list[dict[str, Any]] = []
    previous = None
    for sequence, line in enumerate(lines, start=1):
        try:
            event = json.loads(line, parse_constant=_reject_json_constant)
        except ValueError as exc:
            raise JournalError(f"journal event {sequence} is malformed") from exc
        if not isinstance(event, dict) or set(event) != {
                "schema", "sequence", "caseRoot", "taskRoot", "taskId", "executionId", "previousEventRoot",
                "occurredAt", "type", "data", "eventRoot"}:
            raise JournalError(f"journal event {sequence} has an invalid field set")
        if (event["schema"] != JOURNAL_SCHEMA or event["sequence"] != sequence
                or event["caseRoot"] != case_root or event["taskRoot"] != task_root
                or event["taskId"] != task_id or event["executionId"] != state["executionId"]
                or event["previousEventRoot"] != previous
                or event["eventRoot"] != _digest(_event_body(event))):
            raise JournalError(f"journal hash chain/binding invalid at event {sequence}")
        if not isinstance(event["data"], dict) or not isinstance(event["occurredAt"], str):
            raise JournalError(f"journal event {sequence} is malformed")
        try:
            timestamp = datetime.fromisoformat(event["occurredAt"].replace("Z", "+00:00"))
        except ValueError as exc:
            raise JournalError(f"journal event {sequence} timestamp is malformed") from exc
        if timestamp.tzinfo is None or timestamp.utcoffset() is None:
            raise JournalError(f"journal event {sequence} timestamp must include a timezone")
        if sequence == 1 and event["type"] != "case-opened":
            raise JournalError("journal must begin with case-opened")
        if sequence > 1 and event["type"] == "case-opened":
            raise JournalError("journal contains duplicate case-opened")
        _apply(state, event)
        previous = event["eventRoot"]
        events.append(event)
    return state, events


def _write_projection(path: Path, state: dict[str, Any], events: list[dict[str, Any]]) -> None:
    # Tuple-key metadata is projected as a sorted list for stable JSON.
    serializable = dict(state)
    serializable["metadata"] = [
        {"kind": kind, "id": identifier, **value}
        for (kind, identifier), value in sorted(state["metadata"].items())]
    projection = {**serializable, "historyRoot": state["eventRoot"],
                  "historyEventCount": len(events)}
    projection["projectionRoot"] = _digest(projection)
    payload = _canonical_json(projection)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".state-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        Path(temporary).unlink(missing_ok=True)


def verify_history(output_root: Path, *, case_id: str, case_root: str, task_root: str,
                   task_id: str, definition_root: str, idempotent: bool,
                   max_attempts: int, max_elapsed_seconds: int) -> dict[str, Any]:
    """Read-only replay used by an independent process to verify case history."""
    directory = output_root / case_id / "journal"
    journal_path, projection_path = directory / "events.jsonl", directory / "state.json"
    if not journal_path.is_file():
        raise JournalError("case journal does not exist")
    lock_path = directory / ".lock"
    if not lock_path.is_file():
        raise JournalError("case journal lock metadata is unavailable")
    with lock_path.open("rb") as lock_stream:
        fcntl.flock(lock_stream.fileno(), fcntl.LOCK_EX)
        try:
            state, events = _replay(journal_path, case_id, case_root, task_root, task_id)
            policy = {"idempotent": bool(idempotent), "maxAttempts": max_attempts,
                      "maxElapsedSeconds": max_elapsed_seconds}
            if (state.get("taskDefinitionRoot") != definition_root or state.get("policy") != policy):
                raise JournalError("task definition or retry policy differs from journal")
            if projection_path.is_file():
                try:
                    stored = json.loads(projection_path.read_text(encoding="utf-8"))
                except (OSError, json.JSONDecodeError) as exc:
                    raise JournalError("state projection is unreadable") from exc
                stored_root = stored.pop("projectionRoot", None)
                if stored_root != _digest(stored):
                    raise JournalError("state projection digest is invalid")
                known_roots = {None, *(event["eventRoot"] for event in events)}
                if stored.get("historyRoot") not in known_roots:
                    raise JournalError("state projection refers to a conflicting journal history")
                projection_current = stored.get("historyRoot") == state["eventRoot"]
                if projection_current:
                    expected_session = JournalSession(
                        output_root, case_id, case_root, task_root, task_id,
                        definition_root, policy)
                    expected_session.state, expected_session.events = state, events
                    expected = expected_session._projection_value()
                    expected.pop("projectionRoot", None)
                    if stored != expected:
                        raise JournalError("state projection conflicts with replayed journal")
            else:
                projection_current = False
            output = JournalSession(output_root, case_id, case_root, task_root, task_id,
                                    definition_root, policy)
            output.state, output.events = state, events
            expected = output._projection_value()
            return {"caseId": case_id, "caseRoot": case_root, "taskRoot": task_root,
                    "status": state["status"], "attempt": state["attempt"],
                    "historyRoot": state["eventRoot"], "historyEventCount": len(events),
                    "projectionRoot": expected["projectionRoot"],
                    "storedProjectionCurrent": projection_current}
        finally:
            fcntl.flock(lock_stream.fileno(), fcntl.LOCK_UN)


class JournalSession:
    def __init__(self, output_root: Path, case_id: str, case_root: str, task_root: str,
                 task_id: str, definition_root: str, policy: dict[str, Any]):
        self.directory = output_root / case_id / "journal"
        self.journal = self.directory / "events.jsonl"
        self.projection = self.directory / "state.json"
        self.lock_path = self.directory / ".lock"
        self.case_id, self.case_root, self.task_root = case_id, case_root, task_root
        self.task_id, self.definition_root, self.policy = task_id, definition_root, policy
        self._lock_stream = None
        self.state: dict[str, Any] = {}
        self.events: list[dict[str, Any]] = []

    def __enter__(self) -> "JournalSession":
        self.directory.mkdir(parents=True, exist_ok=True)
        self._lock_stream = self.lock_path.open("a+b")
        fcntl.flock(self._lock_stream.fileno(), fcntl.LOCK_EX)
        try:
            self.state, self.events = _replay(
                self.journal, self.case_id, self.case_root, self.task_root, self.task_id)
            if self.state["status"] == "new":
                self.append("case-opened", {"caseId": self.case_id, "taskId": self.task_id,
                                             "taskDefinitionRoot": self.definition_root,
                                             "policy": self.policy})
            else:
                if (self.state.get("taskDefinitionRoot") != self.definition_root
                        or self.state.get("policy") != self.policy):
                    raise JournalError("resume task definition or retry policy differs from journal")
                if self.state["status"] == "running":
                    self.append("task-interrupted", {
                        "classification": "process-stop-before-terminal-record"})
            self._verify_projection()
            return self
        except Exception:
            fcntl.flock(self._lock_stream.fileno(), fcntl.LOCK_UN)
            self._lock_stream.close()
            self._lock_stream = None
            raise

    def __exit__(self, exc_type, exc, tb) -> None:
        if self._lock_stream:
            fcntl.flock(self._lock_stream.fileno(), fcntl.LOCK_UN)
            self._lock_stream.close()

    def append(self, event_type: str, data: dict[str, Any]) -> dict[str, Any]:
        if len(self.events) >= MAX_EVENTS:
            raise JournalError("journal event limit reached")
        event = _new_event(self.state, event_type, data)
        next_state = copy.deepcopy(self.state)
        _apply(next_state, event)
        payload = _canonical_json(event)
        if self.journal.exists() and self.journal.stat().st_size + len(payload) > MAX_JOURNAL_BYTES:
            raise JournalError("journal size limit reached")
        fd = os.open(self.journal, os.O_CREAT | os.O_WRONLY | os.O_APPEND, 0o600)
        try:
            with os.fdopen(fd, "ab", closefd=False) as stream:
                stream.write(payload)
                stream.flush()
                os.fsync(stream.fileno())
        finally:
            os.close(fd)
        self.state = next_state
        self.events.append(event)
        _write_projection(self.projection, self.state, self.events)
        return event

    def _verify_projection(self) -> None:
        if not self.projection.exists():
            if self.events:
                _write_projection(self.projection, self.state, self.events)
            return
        try:
            stored = json.loads(self.projection.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise JournalError("state projection is unreadable") from exc
        projection_root = stored.pop("projectionRoot", None)
        if projection_root != _digest(stored):
            raise JournalError("state projection digest is invalid")
        stored_history_root = stored.get("historyRoot")
        known_roots = {None, *(event["eventRoot"] for event in self.events)}
        if stored_history_root not in known_roots:
            raise JournalError("state projection refers to a conflicting journal history")
        if stored_history_root == self.state["eventRoot"]:
            expected = self._projection_value()
            expected.pop("projectionRoot", None)
            if stored != expected:
                raise JournalError("state projection conflicts with replayed journal")
        else:
            # Crash after durable append but before projection replacement: replay repairs it.
            _write_projection(self.projection, self.state, self.events)

    def _projection_value(self) -> dict[str, Any]:
        serializable = dict(self.state)
        serializable["metadata"] = [
            {"kind": kind, "id": identifier, **value}
            for (kind, identifier), value in sorted(self.state["metadata"].items())]
        projection = {**serializable, "historyRoot": self.state["eventRoot"],
                      "historyEventCount": len(self.events)}
        projection["projectionRoot"] = _digest(projection)
        return projection

    def elapsed_seconds(self) -> float:
        return _elapsed_since(self.state.get("firstStartedAt"))


def open_session(output_root: Path, *, case_id: str, case_root: str, task_root: str,
                 task_id: str, definition_root: str, idempotent: bool,
                 max_attempts: int, max_elapsed_seconds: int) -> JournalSession:
    if not isinstance(max_attempts, int) or max_attempts < 1 \
            or not isinstance(max_elapsed_seconds, int) or max_elapsed_seconds < 1:
        raise JournalError("task execution budgets must be positive")
    policy = {"idempotent": bool(idempotent), "maxAttempts": max_attempts,
              "maxElapsedSeconds": max_elapsed_seconds}
    return JournalSession(output_root, case_id, case_root, task_root, task_id,
                          definition_root, policy)

