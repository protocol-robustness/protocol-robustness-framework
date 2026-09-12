"""OS-level isolation checks for forensic runs.

Each check is a standalone function that returns a structured result dict
rather than raising.  The caller collects results and records them in the
run metadata alongside `isolation/mode`.

Usage:
    report = run_isolation_checks()
    # report = {"isolation/all-pass": bool, "isolation/grade": str, "isolation/checks": [...]}
"""

from __future__ import annotations

import os
import pwd
from pathlib import Path
from typing import Any

# Configurable hardening constants (override via env)
# Set PRF_EVIDENCE_USER and PRF_EVIDENCE_WORKSPACE in your environment
# to enable the corresponding isolation checks.
PRF_EVIDENCE_USER = os.environ.get("PRF_EVIDENCE_USER", "")
PRF_EVIDENCE_WORKSPACE = os.environ.get("PRF_EVIDENCE_WORKSPACE", "")

HARDENING_CONFIG = {"hardening": {"minimum_ptrace_scope": 3}}


def check_uid(expected_user: str = "") -> dict[str, Any]:
    """Verify the process is running under the expected dedicated UID.
    Skipped if no expected user is configured (set PRF_EVIDENCE_USER)."""
    if not expected_user:
        expected_user = PRF_EVIDENCE_USER
    if not expected_user:
        return {
            "check": "uid",
            "status": "skip",
            "detail": "No expected user configured (set PRF_EVIDENCE_USER)",
            "expected": None,
            "actual": None,
        }
    try:
        uid = os.geteuid()
        pw = pwd.getpwuid(uid)
        actual = pw.pw_name
        if actual != expected_user:
            return {
                "check": "uid",
                "status": "fail",
                "detail": f"uid={uid} ({actual}), expected {expected_user}",
                "expected": expected_user,
                "actual": actual,
            }
        return {
            "check": "uid",
            "status": "pass",
            "detail": f"uid={uid} ({actual})",
            "expected": expected_user,
            "actual": actual,
        }
    except Exception as e:
        return {
            "check": "uid",
            "status": "error",
            "detail": str(e),
            "expected": expected_user,
            "actual": None,
        }


def check_ptrace_scope() -> dict[str, Any]:
    """Verify ptrace_scope >= 3 (disables ptrace from non-parent processes)."""
    path = Path("/proc/sys/kernel/yama/ptrace_scope")
    if not path.exists():
        return {
            "check": "ptrace-scope",
            "status": "skip",
            "detail": "/proc/sys/kernel/yama/ptrace_scope not found (YAMA LSM may not be enabled)",
            "expected": 3,
            "actual": None,
        }
    try:
        val = int(path.read_text().strip())
        if val < 3:
            return {
                "check": "ptrace-scope",
                "status": "fail",
                "detail": f"ptrace_scope={val}, expected >= 3",
                "expected": 3,
                "actual": val,
            }
        return {
            "check": "ptrace-scope",
            "status": "pass",
            "detail": f"ptrace_scope={val}",
            "expected": 3,
            "actual": val,
        }
    except Exception as e:
        return {
            "check": "ptrace-scope",
            "status": "error",
            "detail": str(e),
            "expected": 3,
            "actual": None,
        }


def check_proc_isolation() -> dict[str, Any]:
    """Verify /proc is accessible for own process (heuristic — ensures
    /proc is functioning normally but does not prove full isolation)."""
    my_pid = os.getpid()
    proc_path = Path(f"/proc/{my_pid}/status")
    try:
        data = proc_path.read_text()
        if "Name" not in data:
            return {
                "check": "proc-isolation",
                "status": "fail",
                "detail": "/proc/self/status missing expected fields",
                "expected": True,
                "actual": False,
            }
        return {
            "check": "proc-isolation",
            "status": "pass",
            "detail": f"/proc (pid={my_pid}) accessible, Name field present",
            "expected": True,
            "actual": True,
        }
    except Exception as e:
        return {
            "check": "proc-isolation",
            "status": "fail",
            "detail": f"Cannot access /proc/{my_pid}/status: {e}",
            "expected": True,
            "actual": False,
        }


def check_fs_access(write_target: str = "") -> dict[str, Any]:
    """Verify write access to the expected isolated workspace directory.
    Skipped if no workspace path is configured (set PRF_EVIDENCE_WORKSPACE)."""
    if not write_target:
        write_target = PRF_EVIDENCE_WORKSPACE
    if not write_target:
        return {
            "check": "fs-access",
            "status": "skip",
            "detail": "No workspace path configured (set PRF_EVIDENCE_WORKSPACE)",
            "expected": None,
            "actual": None,
        }
    path = Path(write_target)
    try:
        if not path.exists():
            return {
                "check": "fs-access",
                "status": "skip",
                "detail": f"Target path {write_target} does not exist",
                "expected": True,
                "actual": False,
            }
        ok = os.access(str(path), os.W_OK)
        if ok:
            return {
                "check": "fs-access",
                "status": "pass",
                "detail": f"Write access to {write_target}",
                "expected": True,
                "actual": True,
            }
        return {
            "check": "fs-access",
            "status": "fail",
            "detail": f"No write access to {write_target}",
            "expected": True,
            "actual": False,
        }
    except Exception as e:
        return {
            "check": "fs-access",
            "status": "error",
            "detail": str(e),
            "expected": True,
            "actual": False,
        }


def check_privileges() -> dict[str, Any]:
    """Verify the process is not running as root (privilege escalation
    signal)."""
    try:
        uid = os.geteuid()
        if uid == 0:
            return {
                "check": "privileges",
                "status": "fail",
                "detail": "Running as root (uid=0) — isolation broken",
                "expected": "non-root",
                "actual": "root",
            }
        return {
            "check": "privileges",
            "status": "pass",
            "detail": f"Not root (uid={uid})",
            "expected": "non-root",
            "actual": uid,
        }
    except Exception as e:
        return {
            "check": "privileges",
            "status": "error",
            "detail": str(e),
            "expected": "non-root",
            "actual": None,
        }


def compute_isolation_grade(isolation_mode: str, checks: list[dict[str, Any]]) -> str:
    """Compute an overall isolation grade from mode and check results.

    Grades:
      full     — private-tmpfs mode + all checks pass
      good     — private-tmpfs mode + some checks fail
      partial  — shared-filesystem mode + all checks pass
      basic    — shared-filesystem mode + some checks fail
      unknown  — not enough information
    """
    all_pass = all(c.get("status") == "pass" for c in checks)
    any_fail = any(c.get("status") == "fail" for c in checks)

    if isolation_mode == "private-tmpfs":
        if all_pass:
            return "full"
        return "good"
    elif isolation_mode == "shared-filesystem":
        if all_pass:
            return "partial"
        return "basic"
    return "unknown"


def run_isolation_checks(
    expected_user: str = "",
    write_target: str = "",
    isolation_mode: str = "shared-filesystem",
) -> dict[str, Any]:
    if not expected_user:
        expected_user = PRF_EVIDENCE_USER
    if not write_target:
        write_target = PRF_EVIDENCE_WORKSPACE
    """Run all OS-level isolation checks and return a structured report.

    The report is safe to embed directly into run-overview.json or
    run-bundle-root.json under the ``isolation`` key.
    """
    checks = [
        check_uid(expected_user),
        check_ptrace_scope(),
        check_proc_isolation(),
        check_fs_access(write_target),
        check_privileges(),
    ]

    all_pass = all(c.get("status") == "pass" for c in checks)
    grade = compute_isolation_grade(isolation_mode, checks)

    return {
        "isolation/checks": checks,
        "isolation/all-pass": all_pass,
        "isolation/grade": grade,
        "isolation/mode": isolation_mode,
    }
