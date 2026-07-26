#!/usr/bin/env python3
"""Forensic bundle protocol-semantic validation dispatcher.

Reads a forensic run bundle root and dispatches to a registered protocol
validator based on the declared ``:protocol`` descriptor.

A bundle declares its protocol identity (e.g. ``{"id": "sew", "version": "1"}``)
at the bundle root level, outside of ``:protocol/state``.  The verifier selects
the appropriate semantic validator.

Outcomes:

- ``pass`` — the protocol validator found semantically valid evidence
- ``fail`` — the protocol validator found invalid lifecycle/state transitions
- ``not-verified`` — legacy (no descriptor) or no validator registered
- ``error`` — the bundle could not be read or the validator crashed

``not-verified`` means the verifier has no semantic validator for the declared
protocol; it does not mean the bundle is correct.  Verification policies
(``--require-protocol-semantics`` in ``verify.py``) may upgrade ``not-verified``
to a required failure.

A **malformed** protocol descriptor (present but invalid shape) is treated as
a failure, not as ``not-verified`` — it is not the same as a legacy absence.

Usage:
    python3 scripts/forensic/validate.py <bundle-root.json> [--run-dir <dir>]
    python3 scripts/forensic/validate.py <run-dir>
"""

from __future__ import annotations

import json
import sys
import traceback
from collections.abc import Callable
from pathlib import Path
from typing import Any

from . import validate_sew

SCHEMA_VERSION = "forensic-validate.v1"

# Protocol validator contract
# --------------------------
# Entry point: validator(bundle_root_path: Path, run_dir: Path | None) -> dict
#
# Top-level fields are protocol-qualified, e.g.:
#   sew/status                  — "pass" | "fail" | "error" | "not-verified"
#   sew/checks                  — list[dict]
#   sew/summary                 — dict with passed/failed/warned/skipped counts
#   sew/force-auth-evidence-count — int
#
# Individual check records use the dispatcher-standard fields:
#   check                       — string identifier (no "check/key" prefix)
#   status                      — "pass" | "fail" | "warn" | "skip" | "not-verified"
#   message                     — human-readable string
#   details (optional)          — list of structured violation records
#
# The dispatcher owns generic aggregate fields (validate/schema-version,
# validate/status, validate/protocol) and exit-code interpretation.
# Protocol-specific validators should not attempt to set these.
ProtocolValidator = Callable[[Path, Path | None], dict[str, Any]]

VALIDATORS: dict[tuple[str, str], ProtocolValidator] = {
    ("sew", "1"): validate_sew.validate_protocol_bundle,
}

# Canonical protocol-name grammar.
# Deliberately excludes hyphens and underscores from the name segment:
#   <name> = [a-z][a-z0-9]*   (lowercase, single token, no hyphens/underscores)
# This avoids ambiguous splitting in "<name>-v<N>" — compound names like
# "partial-fill-v1" would require a different delimiter convention.
# See src/resolver_sim/run/bundle_root.clj for the producer-side grammar.
#
# The version is always a string, never an integer, so that cross-language
# consumers compare by string equality ("1" == "1", not 1 == "1").
VALID_PATTERN = r"^[a-z][a-z0-9]*$"


def load_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text())
    except Exception:
        return None


def _validate_descriptor_shape(pd: Any) -> dict | None:
    """Validate and return a normalised protocol descriptor, or None if
    the descriptor is absent entirely (legacy bundle).

    Returns a ``{"descriptor": ..., "errors": [...]}`` dict when malformed.
    """
    if pd is None:
        return None

    if not isinstance(pd, dict):
        return None  # absent — not a dict at all

    pid = pd.get("id")
    pv = pd.get("version")

    # Both must be present
    if pid is None and pv is None:
        return None  # completely empty protocol map → treat as absent

    errors: list[str] = []

    if not isinstance(pid, str) or not pid:
        errors.append("protocol.id must be a non-empty string")
    elif not __import__("re").match(VALID_PATTERN, pid):
        errors.append(f"protocol.id '{pid}' does not match expected pattern "
                       f"(lowercase alphanumeric, no hyphens)")

    if not isinstance(pv, str) or not pv:
        errors.append("protocol.version must be a non-empty string")
    elif isinstance(pv, str) and not pv.isdigit():
        errors.append(f"protocol.version '{pv}' is not a numeric string")

    if errors:
        return {"descriptor": {"id": pid, "version": pv}, "errors": errors}

    return {"descriptor": {"id": pid, "version": pv}, "errors": []}


def read_protocol_descriptor(bundle: dict) -> dict | None:
    """Extract and validate the ``:protocol`` descriptor from a bundle root.

    Returns ``None`` for legacy bundles without a protocol descriptor.
    Returns a dict with ``"descriptor"`` and ``"errors"`` keys when the
    descriptor is present but malformed.
    """
    pd = bundle.get("protocol")
    return _validate_descriptor_shape(pd)


def _validate_validator_result(result: Any, protocol: dict) -> list[dict]:
    """Validate that a protocol validator returned the expected shape.

    Returns a list of diagnostic check dicts.  If the result is malformed
    the checks include a hard failure.
    """
    issues: list[dict] = []

    if not isinstance(result, dict):
        return [{
            "check": "protocol-validator-result",
            "status": "fail",
            "message": f"Validator for {protocol['id']} v{protocol['version']} "
                       f"returned {type(result).__name__}, expected dict",
        }]

    # Check that the report has at least one recognised namespace key
    ns_keys = [k for k in result if "/" in k]
    if not ns_keys:
        issues.append({
            "check": "protocol-validator-result",
            "status": "fail",
            "message": f"Validator for {protocol['id']} v{protocol['version']} "
                       f"returned result with no namespace-qualified keys",
        })

    status = result.get("sew/status") or result.get("validate/status") or "error"
    if status not in ("pass", "fail", "error", "not-verified"):
        issues.append({
            "check": "protocol-validator-status",
            "status": "fail",
            "message": f"Validator returned unrecognised status '{status}'",
        })

    checks = result.get("sew/checks") or result.get("validate/checks") or []
    if not isinstance(checks, list):
        issues.append({
            "check": "protocol-validator-checks",
            "status": "fail",
            "message": "Validator returned non-list checks",
        })
    else:
        for i, c in enumerate(checks):
            if not isinstance(c, dict):
                issues.append({
                    "check": f"protocol-validator-check-{i}",
                    "status": "fail",
                    "message": f"Check {i} is {type(c).__name__}, expected dict",
                })
                continue
            if "check" not in c:
                issues.append({
                    "check": f"protocol-validator-check-{i}",
                    "status": "fail",
                    "message": f"Check {i} is missing 'check' field",
                })
            if c.get("status") not in ("pass", "fail", "warn", "skip", "not-verified"):
                issues.append({
                    "check": f"protocol-validator-check-{i}",
                    "status": "fail",
                    "message": f"Check {i} has unrecognised status '{c.get('status')}'",
                })

    return issues


def validate_protocol_bundle(
    bundle_root_path: Path,
    run_dir: Path | None = None,
) -> dict:
    """Run protocol-semantic validation by dispatching to the registered validator.

    Returns a report with a uniform top-level shape.
    """
    bundle = load_json(bundle_root_path)
    if bundle is None:
        return {
            "validate/schema-version": SCHEMA_VERSION,
            "validate/status": "error",
            "validate/errors": [f"Cannot read bundle root: {bundle_root_path}"],
            "validate/protocol": None,
            "validate/protocol-dispatched": False,
            "validate/checks": [],
            "validate/summary": {"passed": 0, "failed": 0, "warned": 0, "skipped": 0},
            "validate/evidence-count": 0,
        }

    raw = read_protocol_descriptor(bundle)

    # Absent protocol descriptor — legacy bundle, no inference
    if raw is None:
        return {
            "validate/schema-version": SCHEMA_VERSION,
            "validate/status": "not-verified",
            "validate/protocol": None,
            "validate/protocol-dispatched": False,
            "validate/checks": [{
                "check": "protocol-identity",
                "status": "not-verified",
                "message": "Bundle does not declare a protocol identity — "
                           "legacy bundle without protocol-semantic verification",
            }],
            "validate/summary": {
                "passed": 0, "failed": 0, "warned": 0, "skipped": 0, "not-verified": 1,
            },
            "validate/evidence-count": 0,
        }

    # Malformed protocol descriptor — present but invalid shape
    # This is NOT a legacy fallback; malformed is a failure
    if raw.get("errors"):
        errors = raw["errors"]
        return {
            "validate/schema-version": SCHEMA_VERSION,
            "validate/status": "fail",
            "validate/protocol": raw.get("descriptor"),
            "validate/protocol-dispatched": False,
            "validate/checks": [{
                "check": "protocol-identity-malformed",
                "status": "fail",
                "message": "Malformed protocol descriptor: " + "; ".join(errors),
                "errors": errors,
            }],
            "validate/summary": {
                "passed": 0, "failed": 1, "warned": 0, "skipped": 0,
            },
            "validate/evidence-count": 0,
        }

    protocol = raw["descriptor"]
    key = (protocol["id"], protocol["version"])
    validator = VALIDATORS.get(key)

    # Unknown protocol — no validator registered
    if validator is None:
        return {
            "validate/schema-version": SCHEMA_VERSION,
            "validate/status": "not-verified",
            "validate/protocol": protocol,
            "validate/protocol-dispatched": True,
            "validate/checks": [{
                "check": "protocol-lifecycle",
                "status": "not-verified",
                "message": f"No validator registered for protocol {protocol['id']} "
                           f"version {protocol['version']}",
                "protocol_id": protocol["id"],
                "protocol_version": protocol["version"],
            }],
            "validate/summary": {
                "passed": 0, "failed": 0, "warned": 0, "skipped": 0, "not-verified": 1,
            },
            "validate/evidence-count": 0,
        }

    # Dispatch — with exception safety
    try:
        result = validator(bundle_root_path, run_dir)
    except Exception as exc:
        tb = traceback.format_exc()
        return {
            "validate/schema-version": SCHEMA_VERSION,
            "validate/status": "error",
            "validate/protocol": protocol,
            "validate/protocol-dispatched": True,
            "validate/checks": [{
                "check": "protocol-validator-execution",
                "status": "fail",
                "message": f"Validator for {protocol['id']} v{protocol['version']} "
                           f"raised {type(exc).__name__}: {exc}",
                "traceback": tb,
            }],
            "validate/summary": {
                "passed": 0, "failed": 1, "warned": 0, "skipped": 0,
            },
            "validate/evidence-count": 0,
        }

    # Validate the result shape
    shape_issues = _validate_validator_result(result, protocol)
    if shape_issues:
        return {
            "validate/schema-version": SCHEMA_VERSION,
            "validate/status": "error",
            "validate/protocol": protocol,
            "validate/protocol-dispatched": True,
            "validate/checks": shape_issues,
            "validate/summary": {
                "passed": 0, "failed": len(shape_issues), "warned": 0, "skipped": 0,
            },
            "validate/evidence-count": 0,
        }

    # Normalise: wrap the protocol-specific result into a generic report
    status = result.get("sew/status") or "error"
    return {
        "validate/schema-version": SCHEMA_VERSION,
        "validate/status": status,
        "validate/protocol": protocol,
        "validate/protocol-dispatched": True,
        "validate/checks": result.get("sew/checks", []),
        "validate/summary": result.get("sew/summary",
                                        {"passed": 0, "failed": 0, "warned": 0, "skipped": 0}),
        "validate/evidence-count": result.get("sew/force-auth-evidence-count", 0),
    }


def main():
    import argparse

    parser = argparse.ArgumentParser(
        description="Forensic bundle protocol-semantic validation dispatcher"
    )
    parser.add_argument("input", help="Path to bundle root JSON or run directory")
    parser.add_argument(
        "--run-dir",
        default=None,
        help="Path to forensic run directory (for evidence scanning)",
    )
    parser.add_argument(
        "--list-validators",
        action="store_true",
        help="List registered protocol validators and exit",
    )
    args = parser.parse_args()

    if args.list_validators:
        if not VALIDATORS:
            print("No protocol validators registered.")
            sys.exit(0)
        print("Registered protocol validators:")
        for (pid, pv), fn in sorted(VALIDATORS.items()):
            print(f"  {pid} version {pv} → {fn.__module__}.{fn.__name__}")
        sys.exit(0)

    input_path = Path(args.input).expanduser().resolve()

    if input_path.is_dir():
        bundle_path = input_path / "run-bundle-root.json"
        if not bundle_path.exists():
            bundle_path = input_path / "clojure-bundle-root.json"
        if not bundle_path.exists():
            print(json.dumps({
                "validate/schema-version": SCHEMA_VERSION,
                "validate/status": "error",
                "validate/errors": [f"No bundle root JSON found in {input_path}"],
            }, indent=2))
            sys.exit(1)
        report = validate_protocol_bundle(bundle_path, run_dir=input_path)
    else:
        run_dir = Path(args.run_dir).expanduser().resolve() if args.run_dir else None
        report = validate_protocol_bundle(input_path, run_dir=run_dir)

    print(json.dumps(report, indent=2))
    status = report.get("validate/status", "error")
    sys.exit(0 if status == "pass" else 1)


if __name__ == "__main__":
    main()
