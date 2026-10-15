#!/usr/bin/env python3
"""Run the bounded PRO-28 investigation task allowlist.

This dispatcher executes a declared task, but never admits its output into the
canonical forensic graph. V0 supports one task: directly verify the retained
PRO-28 Groth16 proof when the independently reconstructed report still records
that verification as an open gap.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))
from forensic import investigation_journal as journal

SCRIPT = PROJECT_ROOT / "scripts/forensic/pro28_receipts.py"
CASE_SCHEMA = "prf.investigation-case.v0"
TASK_TYPE = "sp1-groth16-reference-check.v1"
TASK_DEFINITION = {
    "type": TASK_TYPE,
    "purpose": "resolve the recorded not-verified Groth16 proof gap",
    "runner": "scripts/forensic/pro28_receipts.py --verify-groth16",
    "network": "disposable local Anvil only",
    "foundryVersion": "1.7.1",
    "foundryCommit": "4072e48705af9d93e3c0f6e29e93b5e9a40caed8",
    "timeoutSeconds": 120,
    "maxAddressSpaceBytes": 2_147_483_648,
    "maxOutputBytes": 1_048_576,
    "idempotent": True,
    "effectClass": "local-read-only-reference-verification",
    "maxAttempts": 3,
    "maxElapsedSeconds": 600,
}
MAX_TASK_SECONDS = 120
MAX_OUTPUT_BYTES = 1_048_576
MAX_ADDRESS_SPACE_BYTES = 2_147_483_648
CASE_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{0,79}$")


class DispatchError(ValueError):
    """A task request is invalid, unsupported, or cannot safely execute."""


def _canonical_json(value: Any) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":"),
                        ensure_ascii=False, allow_nan=False) + "\n").encode("utf-8")


def _sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def _load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DispatchError(f"cannot load JSON artifact {path.name}: {exc}") from exc
    if not isinstance(value, dict):
        raise DispatchError(f"JSON artifact {path.name} must be an object")
    return value


def _repo_path(value: Any, *, label: str, must_exist: bool = True) -> Path:
    if not isinstance(value, str) or not value or Path(value).is_absolute():
        raise DispatchError(f"{label} must be a non-empty repository-relative path")
    if any(part in ("", ".", "..") for part in Path(value).parts):
        raise DispatchError(f"{label} contains a path traversal component")
    candidate = PROJECT_ROOT / value
    try:
        resolved = candidate.resolve(strict=must_exist)
    except OSError as exc:
        raise DispatchError(f"{label} does not resolve within the repository: {exc}") from exc
    try:
        relative = resolved.relative_to(PROJECT_ROOT.resolve())
    except ValueError as exc:
        raise DispatchError(f"{label} escapes the repository") from exc
    cursor = PROJECT_ROOT
    for part in Path(value).parts:
        cursor = cursor / part
        if cursor.is_symlink():
            raise DispatchError(f"{label} may not traverse symlinks")
    if must_exist and not resolved.is_file() and not resolved.is_dir():
        raise DispatchError(f"{label} does not exist")
    return PROJECT_ROOT / relative


def _write_immutable(directory: Path, value: dict[str, Any]) -> dict[str, str]:
    payload = _canonical_json(value)
    digest = hashlib.sha256(payload).hexdigest()
    target_dir = directory / "sha256"
    target_dir.mkdir(parents=True, exist_ok=True)
    target = target_dir / f"{digest}.json"
    if target.exists():
        if target.read_bytes() != payload:
            raise DispatchError("immutable candidate evidence path contains conflicting bytes")
    else:
        fd, temporary = tempfile.mkstemp(prefix=".candidate-", dir=target_dir)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(payload)
                stream.flush()
                os.fsync(stream.fileno())
            try:
                os.link(temporary, target)
            except FileExistsError:
                if target.read_bytes() != payload:
                    raise DispatchError("immutable candidate evidence collision")
        finally:
            Path(temporary).unlink(missing_ok=True)
    try:
        displayed_path = str(target.relative_to(PROJECT_ROOT))
    except ValueError:
        displayed_path = str(target)
    return {"path": displayed_path, "sha256": "sha256:" + digest}


def _set_child_limits() -> None:
    """Constrain the fixed child command on POSIX/Linux hosts."""
    try:
        import resource
        resource.setrlimit(resource.RLIMIT_CPU, (MAX_TASK_SECONDS, MAX_TASK_SECONDS))
        resource.setrlimit(resource.RLIMIT_AS,
                           (MAX_ADDRESS_SPACE_BYTES, MAX_ADDRESS_SPACE_BYTES))
        resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_OUTPUT_BYTES, MAX_OUTPUT_BYTES))
        resource.setrlimit(resource.RLIMIT_NOFILE, (64, 64))
    except (ImportError, OSError, ValueError) as exc:
        raise DispatchError(f"required local task resource limits are unavailable: {exc}") from exc


def _pinned_toolchain(bundle: Path) -> tuple[dict[str, str] | None, str | None]:
    """Require the captured Foundry release and record the exact local binaries."""
    capture = _load_json(bundle / "capture-metadata.json")
    expected = capture.get("toolchain", {})
    if (expected.get("foundry") != "forge 1.7.1"
            or expected.get("foundryCommit") != TASK_DEFINITION["foundryCommit"]):
        return None, "blocked: source capture does not identify the pinned Foundry toolchain"
    tools: dict[str, str] = {}
    for name in ("anvil", "cast"):
        executable = shutil.which(name)
        if not executable:
            return None, f"blocked: required local tool {name} is unavailable"
        executable_path = Path(executable).resolve()
        try:
            version = subprocess.run([str(executable_path), "--version"], check=False,
                                     capture_output=True, text=True, timeout=10)
        except (OSError, subprocess.TimeoutExpired):
            return None, f"blocked: cannot inspect required local tool {name}"
        text = version.stdout + version.stderr
        commit_match = re.search(r"Commit SHA:\s*([0-9a-f]{40})", text, re.IGNORECASE)
        version_match = re.search(r"(?:anvil|cast) Version:\s*([0-9.]+)", text, re.IGNORECASE)
        if (version.returncode != 0 or not commit_match or not version_match
                or version_match.group(1) != TASK_DEFINITION["foundryVersion"]
                or commit_match.group(1).lower() != TASK_DEFINITION["foundryCommit"]):
            return None, f"blocked: local {name} identity differs from the captured Foundry 1.7.1 release"
        binary_digest = hashlib.sha256(executable_path.read_bytes()).hexdigest()
        tools[name] = "sha256:" + binary_digest
    return tools, None


def _run_fixed_groth16_check(bundle: Path, proof_report_path: Path,
                             tools: dict[str, str]) -> tuple[int | None, bool, str]:
    """Execute the one compiled-in task; no request-supplied command or network URL."""
    for name, expected_hash in tools.items():
        executable = shutil.which(name)
        if not executable or _sha256(Path(executable).resolve().read_bytes()) != expected_hash:
            raise DispatchError(f"pinned local {name} changed before task execution")
    command = [sys.executable, str(SCRIPT), str(bundle), "--verify-groth16",
               "--write-proof-check", str(proof_report_path)]
    tool_dirs = {str(Path(shutil.which(name) or "").resolve().parent) for name in ("anvil", "cast")}
    environment = {"PATH": os.pathsep.join(sorted(tool_dirs)), "HOME": str(Path.home()),
                   "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8"}
    with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
        process = subprocess.Popen(command, cwd=PROJECT_ROOT, env=environment,
                                   stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr,
                                   start_new_session=True, preexec_fn=_set_child_limits)
        try:
            return_code = process.wait(timeout=MAX_TASK_SECONDS)
            timed_out = False
        except subprocess.TimeoutExpired:
            timed_out = True
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            return_code = None
        stdout.seek(0)
        stderr.seek(0)
        stdout_bytes = stdout.read(MAX_OUTPUT_BYTES + 1)
        stderr_bytes = stderr.read(MAX_OUTPUT_BYTES + 1)
        if len(stdout_bytes) > MAX_OUTPUT_BYTES or len(stderr_bytes) > MAX_OUTPUT_BYTES:
            return return_code, timed_out, "task output exceeded the configured bound"
        logs = (stdout_bytes + b"\n" + stderr_bytes).decode("utf-8", errors="replace")
    return return_code, timed_out, logs


def _validate_case(case: dict[str, Any]) -> tuple[str, Path, Path, str]:
    allowed = {"schema", "caseId", "bundle", "expectedReport", "expectedReportSha256",
               "task"}
    if set(case) != allowed or case.get("schema") != CASE_SCHEMA:
        raise DispatchError("case schema or field set is unsupported")
    case_id = case.get("caseId")
    if not isinstance(case_id, str) or not CASE_ID_PATTERN.fullmatch(case_id):
        raise DispatchError("caseId is malformed")
    task = case.get("task")
    if not isinstance(task, dict) or set(task) != {"id", "type", "gapId"}:
        raise DispatchError("task definition has missing or unknown fields")
    if task.get("type") != TASK_TYPE or task.get("id") != "verify-groth16-reference":
        raise DispatchError("unsupported investigation task type or task id")
    if task.get("gapId") != "groth16-cryptographic-verification-unverified":
        raise DispatchError("task does not reference the supported typed evidence gap")
    bundle = _repo_path(case.get("bundle"), label="bundle")
    expected = _repo_path(case.get("expectedReport"), label="expectedReport")
    if not bundle.is_dir() or not expected.is_file():
        raise DispatchError("bundle must be a directory and expectedReport must be a file")
    if not isinstance(case.get("expectedReportSha256"), str):
        raise DispatchError("expectedReportSha256 is required")
    if _sha256(expected.read_bytes()) != case["expectedReportSha256"]:
        raise DispatchError("expected reconstruction report digest mismatch")
    case_root = _sha256(_canonical_json(case))
    return case_id, bundle, expected, case_root


def _require_open_groth16_gap(report: dict[str, Any], requested_gap_id: str) -> dict[str, str]:
    verification = report.get("verificationResults", {}).get("cryptographicVerification", {})
    if verification.get("status") != "not verified" or verification.get("referenceCheckAvailable") is not True:
        raise DispatchError("declared Groth16 gap is absent or no longer applicable")
    gap = {
        "id": "groth16-cryptographic-verification-unverified",
        "type": "unverified-claim",
        "path": "verificationResults.cryptographicVerification",
    }
    if requested_gap_id != gap["id"]:
        raise DispatchError("task does not reference the reconstructed Groth16 gap")
    return gap


def _reference_result_matches(reference: dict[str, Any], report: dict[str, Any]) -> bool:
    expected = report.get("proof", {})
    words = expected.get("words", [])
    return bool(
        reference.get("status") == "verified"
        and reference.get("chainId") == 31337
        and reference.get("proofSystem") == expected.get("proofSystem") == "groth16"
        and reference.get("programId") == expected.get("programId")
        and reference.get("vkey", "").lower() == expected.get("vkey", "").lower()
        and reference.get("elfSha256", "").lower() == expected.get("elfSha256", "").lower()
        and reference.get("publicValuesSchema") == expected.get("schema")
        and reference.get("proofSha256", "").lower() == expected.get("proofSha256", "").lower()
        and reference.get("publicValuesSha256", "").lower() == expected.get("publicValuesSha256", "").lower()
        and reference.get("witnessSha256", "").lower() == expected.get("witnessSha256", "").lower()
        and reference.get("operationId") == report.get("operationId")
        and reference.get("source", {}).get("revision") == report.get("source", {}).get("revision")
        and reference.get("source", {}).get("deploymentManifestDigest") == report.get("deploymentManifestDigest")
        and len(words) == 9
        and reference.get("statementRoot", "").lower() == words[3].lower()
        and reference.get("providerScope", "").split(";")[0] == "local-only"
    )


def _existing_candidate(output: dict[str, Any] | None, output_root: Path, case_id: str,
                        case_root: str, task_root: str,
                        expected_status: str) -> dict[str, Any]:
    if not isinstance(output, dict) or set(output) != {"path", "sha256"}:
        raise DispatchError("journal terminal state has no valid candidate output reference")
    path_value = output["path"]
    path = Path(path_value)
    if not path.is_absolute():
        path = PROJECT_ROOT / path
    try:
        resolved = path.resolve(strict=True)
        resolved.relative_to((output_root / case_id / "candidate-evidence").resolve())
        path = resolved
        payload = path.read_bytes()
    except (OSError, ValueError) as exc:
        raise DispatchError("journal candidate output is unavailable or outside its case store") from exc
    if _sha256(payload) != output["sha256"]:
        raise DispatchError("journal candidate output digest mismatch")
    candidate = _load_json(path)
    if (candidate.get("case", {}).get("root") != case_root
            or candidate.get("task", {}).get("root") != task_root):
        raise DispatchError("journal candidate output belongs to another task")
    if candidate.get("execution", {}).get("status") != expected_status:
        raise DispatchError("journal candidate output disagrees with replayed task state")
    return output


def run_case(case_path: Path, output_root: Path, *, retry: bool = False,
             cancel_reason: str | None = None,
             metadata_event: dict[str, str] | None = None,
             verify_journal: bool = False) -> dict[str, Any]:
    case_path = case_path.resolve(strict=True)
    case = _load_json(case_path)
    case_id, bundle, expected_report_path, case_root = _validate_case(case)

    # Reconstruct the report from raw evidence. The report file and its asserted
    # status are not trusted as authority; its digest only pins the case input.
    sys.path.insert(0, str(PROJECT_ROOT / "scripts"))
    from forensic.pro28_receipts import EvidenceError, reconstruct  # noqa: E402
    try:
        report = reconstruct(bundle)
    except EvidenceError as exc:
        raise DispatchError(f"source evidence reconstruction failed: {exc}") from exc
    retained_report = _load_json(expected_report_path)
    if retained_report != report:
        raise DispatchError("retained reconstruction report differs from independent reconstruction")
    _require_open_groth16_gap(report, case["task"]["gapId"])

    manifest_digest = report.get("deploymentManifestDigest")
    operation_id = report.get("operationId")
    proof_sha256 = report.get("proof", {}).get("proofSha256")
    public_values_sha256 = report.get("proof", {}).get("publicValuesSha256")
    if not all(isinstance(value, str) and value for value in
               (manifest_digest, operation_id, proof_sha256, public_values_sha256)):
        raise DispatchError("reconstructed subject lacks deployment, operation, or proof identity")

    task_inputs = {
        "caseRoot": case_root,
        "caseId": case_id,
        "gapId": case["task"]["gapId"],
        "taskType": TASK_TYPE,
        "deploymentManifestDigest": manifest_digest,
        "operationId": operation_id,
        "reconstructionReportSha256": case["expectedReportSha256"],
        "proofSha256": proof_sha256,
        "publicValuesSha256": public_values_sha256,
    }
    task_root = _sha256(_canonical_json(task_inputs))
    definition_hash = _sha256(_canonical_json(TASK_DEFINITION))

    raw_output = Path(output_root).expanduser().absolute()
    cursor = Path(raw_output.anchor)
    for part in raw_output.parts[1:]:
        cursor = cursor / part
        if cursor.exists() and cursor.is_symlink():
            raise DispatchError("output directory may not traverse symlinks")
    if verify_journal:
        if not raw_output.is_dir():
            raise DispatchError("journal output root does not exist")
        output_root = raw_output.resolve()
    else:
        raw_output.mkdir(parents=True, exist_ok=True)
        output_root = raw_output.resolve()
        if not output_root.exists():
            output_root.mkdir(parents=True, exist_ok=True)
        if not output_root.is_dir():
            raise DispatchError("output root is not a directory")
    policy = TASK_DEFINITION
    if verify_journal:
        try:
            return journal.verify_history(
                output_root, case_id=case_id, case_root=case_root, task_root=task_root,
                task_id=case["task"]["id"], definition_root=definition_hash,
                idempotent=bool(policy["idempotent"]), max_attempts=int(policy["maxAttempts"]),
                max_elapsed_seconds=int(policy["maxElapsedSeconds"]))
        except journal.JournalError as exc:
            raise DispatchError(f"investigation journal rejected: {exc}") from exc
    try:
        session_context = journal.open_session(
            output_root, case_id=case_id, case_root=case_root, task_root=task_root,
            task_id=case["task"]["id"], definition_root=definition_hash,
            idempotent=bool(policy["idempotent"]), max_attempts=int(policy["maxAttempts"]),
            max_elapsed_seconds=int(policy["maxElapsedSeconds"]))
        with session_context as session:
            if metadata_event is not None:
                if set(metadata_event) != {"kind", "id", "status", "artifactRoot"}:
                    raise DispatchError("metadata event has missing or unknown fields")
                event = session.append("case-metadata-updated", {
                    "kind": metadata_event["kind"], "id": metadata_event["id"],
                    "status": metadata_event["status"],
                    "artifactRoot": metadata_event["artifactRoot"]})
                return {"caseId": case_id, "caseRoot": case_root,
                        "status": "metadata-recorded", "eventRoot": event["eventRoot"]}
            if cancel_reason is not None:
                session.append("task-cancelled", {"reason": cancel_reason})
                return {"caseId": case_id, "caseRoot": case_root, "status": "cancelled",
                        "eventRoot": session.state["eventRoot"]}
            if session.state["status"] == "succeeded":
                return _existing_candidate(session.state.get("lastOutput"), output_root, case_id,
                                           case_root, task_root, "succeeded")
            if session.state["status"] in {"cancelled", "budget-exceeded"}:
                raise DispatchError(f"investigation task is terminal: {session.state['status']}")
            if session.state["attempt"] >= policy["maxAttempts"]:
                if session.state["status"] in {"failed", "blocked", "interrupted", "ready"}:
                    session.append("task-budget-exceeded", {
                        "budget": "attempts", "limit": policy["maxAttempts"]})
                raise DispatchError("investigation task retry budget is exhausted")
            if (session.elapsed_seconds() >= policy["maxElapsedSeconds"]
                    and session.state["attempt"] > 0):
                session.append("task-budget-exceeded", {
                    "budget": "elapsed-seconds", "limit": policy["maxElapsedSeconds"]})
                raise DispatchError("investigation task elapsed-time budget is exhausted")
            if session.state["status"] in {"failed", "blocked"}:
                if not session.state.get("retryAllowed") or not retry:
                    return _existing_candidate(session.state.get("lastOutput"), output_root, case_id,
                                               case_root, task_root, session.state["status"])
            session.append("task-started", {"attempt": session.state["attempt"] + 1,
                                             "idempotent": bool(policy["idempotent"])})
            tool_identities, toolchain_block = _pinned_toolchain(bundle)
            with tempfile.TemporaryDirectory(prefix="prf-investigation-", dir=output_root) as temp:
                proof_report_path = Path(temp) / "proof-check.json"
                if toolchain_block:
                    exit_code, timed_out, task_output = None, False, toolchain_block
                else:
                    exit_code, timed_out, task_output = _run_fixed_groth16_check(
                        bundle, proof_report_path, tool_identities or {})
                if timed_out:
                    state, proof_result = "failed", None
                    failure, retryable = "task timed out; local process group terminated", True
                elif "task output exceeded" in task_output:
                    state, proof_result = "failed", None
                    failure, retryable = task_output, False
                elif exit_code != 0:
                    state = "blocked" if "blocked:" in task_output or "unsupported:" in task_output else "failed"
                    proof_result = None
                    failure = (task_output.strip() if state == "blocked"
                               else f"reference verifier process exited with status {exit_code}")
                    retryable = state == "blocked"
                else:
                    try:
                        proof_result = _load_json(proof_report_path)
                    except DispatchError as exc:
                        raise DispatchError("reference verifier exited successfully without a valid report") from exc
                    if not _reference_result_matches(proof_result, report):
                        raise DispatchError("reference result does not bind the declared case subject")
                    state, failure, retryable = "succeeded", None, False

            elapsed_budget_exceeded = session.elapsed_seconds() >= policy["maxElapsedSeconds"]
            if elapsed_budget_exceeded:
                state, proof_result = "failed", None
                failure, retryable = "case elapsed-time budget exceeded before task completion", False

            candidate = {
                "schema": "prf.investigation-task-result.v0",
                "case": {"id": case_id, "root": case_root, "deploymentManifestDigest": manifest_digest,
                         "operationId": operation_id},
                "task": {"id": case["task"]["id"], "type": TASK_TYPE, "root": task_root,
                         "definitionSha256": definition_hash, "gapId": case["task"]["gapId"],
                         "inputs": task_inputs},
                "execution": {"status": state, "exitCode": exit_code,
                              "runner": "fixed-local-groth16-reference-check",
                              "timeoutSeconds": MAX_TASK_SECONDS, "networkScope": "local-only",
                              "hostTools": tool_identities},
                "disposition": ("candidate-evidence; graph admission requires independent validation"
                                if state == "succeeded" else "execution record only; no evidence accepted"),
                "failure": failure, "proofReference": proof_result,
            }
            output = _write_immutable(output_root / case_id / "candidate-evidence", candidate)
            session.append("task-finished", {
                "outcome": state, "retryable": retryable,
                "output": output, "reason": failure})
            if elapsed_budget_exceeded:
                session.append("task-budget-exceeded", {
                    "budget": "elapsed-seconds", "limit": policy["maxElapsedSeconds"]})
            elif retryable and session.state["attempt"] >= policy["maxAttempts"]:
                session.append("task-budget-exceeded", {
                    "budget": "attempts", "limit": policy["maxAttempts"]})
            return output
    except journal.JournalError as exc:
        raise DispatchError(f"investigation journal rejected: {exc}") from exc


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("case", type=Path, help="versioned investigation case JSON")
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="directory for immutable candidate evidence")
    parser.add_argument("--retry", action="store_true",
                        help="retry a prior retryable failure within the persisted task budget")
    parser.add_argument("--cancel-reason", help="record an explicit cancellation without running the task")
    parser.add_argument("--metadata-event", type=Path,
                        help="append a hypothesis/evidence-request event from a closed JSON object")
    parser.add_argument("--verify-journal", action="store_true",
                        help="replay retained history without executing or mutating it")
    args = parser.parse_args()
    try:
        if sum((bool(args.cancel_reason), bool(args.metadata_event), bool(args.verify_journal))) > 1:
            raise DispatchError("journal verification, cancellation, and metadata update are exclusive")
        if args.verify_journal and args.retry:
            raise DispatchError("read-only journal verification cannot request a retry")
        metadata = _load_json(args.metadata_event) if args.metadata_event else None
        result = run_case(args.case, args.output_dir, retry=args.retry,
                          cancel_reason=args.cancel_reason, metadata_event=metadata,
                          verify_journal=args.verify_journal)
    except DispatchError as exc:
        print(f"BLOCKED: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
