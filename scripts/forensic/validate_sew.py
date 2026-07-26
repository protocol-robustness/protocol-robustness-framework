#!/usr/bin/env python3
"""SEW-protocol-specific semantic validation for forensic run bundles.

Validates force-authorisation lifecycle consistency for bundles declared
as protocol ``sew`` version ``1``.

Checks:
  1. If force-authorisation evidence events exist, the bundle root must
     contain ``:protocol/state-hashes`` with ``force-authorisations/hash``
     and ``force-authorisations/consumed-hash``.
  2. If ``:protocol/state-hashes`` is present, the hash structure must be
     non-empty and well-formed.
  3. Evidence lifecycle consistency: grant events must precede execute
     events for each auth-id; no double-execute of the same auth-id.
  4. Protocol state witness must agree with evidence events.

Usage (via dispatcher):
    python3 -m forensic.validate <bundle-root.json> [--run-dir <dir>]
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

SCHEMA_VERSION = "forensic-validate-sew.v1"

SEW_EVENT_REASONS = {
    "force-authorisation-granted",
    "force-authorisation-revoked",
    "force-authorisation-executed",
}

SEW_PROTOCOL_ID = "sew"
SEW_PROTOCOL_VERSION = "1"


def load_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text())
    except Exception:
        return None


def find_sew_evidence_files(run_dir: Path) -> list[dict]:
    """Scan for Sew force-authorisation evidence events."""
    results: list[dict] = []
    ev_dir = run_dir / "event-evidence"
    if not ev_dir.is_dir():
        return results
    for f in sorted(ev_dir.iterdir()):
        if not f.name.endswith(".json"):
            continue
        ev = load_json(f)
        if ev is None:
            continue
        etype = ev.get("evidence/type") or ev.get(":evidence/type") or ""
        rtype = ev.get("evidence/reason") or ev.get(":evidence/reason") or ""
        key = str(etype or rtype)
        if key in SEW_EVENT_REASONS:
            results.append({
                "file": str(f),
                "type": key,
                "auth-id": (ev.get("force-auth/auth-id")
                            or ev.get(":force-auth/auth-id")),
                "seq": ev.get("event/seq") or ev.get(":event/seq") or 0,
            })
    return results


def validate_bundle_root(bundle: dict) -> list[dict]:
    """Validate the ``:protocol/state-hashes`` section for Sew shape."""
    checks: list[dict] = []
    proto = bundle.get("protocol/state-hashes") or bundle.get(":protocol/state-hashes")

    if proto is None:
        checks.append({
            "check": "protocol-state-hashes-present",
            "status": "skip",
            "message": ":protocol/state-hashes not present in bundle root",
        })
        return checks

    checks.append({
        "check": "protocol-state-hashes-present",
        "status": "pass",
        "message": ":protocol/state-hashes present",
    })

    fa_hash = proto.get("force-authorisations/hash") or proto.get(":force-authorisations/hash")
    fa_consumed_hash = (
        proto.get("force-authorisations/consumed-hash")
        or proto.get(":force-authorisations/consumed-hash")
    )

    if fa_hash:
        checks.append({
            "check": "force-authorisations-hash-well-formed",
            "status": "pass" if isinstance(fa_hash, str) and len(fa_hash) > 0 else "fail",
            "message": f"force-authorisations/hash: {fa_hash[:16] if fa_hash else 'empty'}...",
        })
    else:
        checks.append({
            "check": "force-authorisations-hash-well-formed",
            "status": "fail",
            "message": "force-authorisations/hash is missing or empty",
        })

    if fa_consumed_hash:
        checks.append({
            "check": "force-authorisations-consumed-hash-well-formed",
            "status": "pass" if isinstance(fa_consumed_hash, str) and len(fa_consumed_hash) > 0 else "fail",
            "message": f"force-authorisations/consumed-hash: {fa_consumed_hash[:16] if fa_consumed_hash else 'empty'}...",
        })
    else:
        checks.append({
            "check": "force-authorisations-consumed-hash-well-formed",
            "status": "fail",
            "message": "force-authorisations/consumed-hash is missing or empty",
        })

    return checks


def _field(mapping: dict[str, Any], name: str, default: Any = None) -> Any:
    return mapping.get(name, mapping.get(f":{name}", default))


def canonical_protocol_state_hash(state: dict) -> str:
    """Cross-language SHA-256 commitment for the JSON-native state witness."""
    encoded = json.dumps(state, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _scoped_protocol_state(state: dict) -> list[tuple[str, dict, dict, list]]:
    """Normalize legacy flat and v2 scenario-keyed Sew protocol-state witnesses."""
    records = _field(state, "force-authorisations", {}) or {}
    consumed = _field(state, "force-authorisations/consumed", {}) or {}
    adjustments = _field(state, "held-adjustments", None)
    is_flat = (not records
               or all(isinstance(record, dict) and _field(record, "authorization/id")
                      for record in records.values()))
    if is_flat:
        return [("legacy", records, consumed, adjustments or [])]

    scoped_adjustments = adjustments if isinstance(adjustments, dict) else {}
    scenario_ids = set(records) | set(consumed) | set(scoped_adjustments)
    return [(str(scenario_id), records.get(scenario_id, {}) or {},
             consumed.get(scenario_id, {}) or {}, scoped_adjustments.get(scenario_id, []) or [])
            for scenario_id in sorted(scenario_ids)]


def validate_protocol_state(bundle: dict, require_witness: bool = False) -> list[dict]:
    """Verify the Sew force-authorisation state witness semantically."""
    state = _field(bundle, "protocol/state")
    if state is None:
        return [{"check": "protocol-state-witness-present",
                 "status": "fail" if require_witness else "skip",
                 "message": "protocol/state witness is missing"}]

    witness_hash = _field(bundle, "protocol/state-witness-hash")
    hash_violations: list[dict] = []
    if require_witness and not witness_hash:
        hash_violations.append({"type": "missing-state-witness-hash"})
    elif witness_hash and witness_hash != canonical_protocol_state_hash(state):
        hash_violations.append({"type": "state-witness-hash-mismatch",
                                "expected": canonical_protocol_state_hash(state),
                                "actual": witness_hash})

    violations: list[dict] = list(hash_violations)
    valid_statuses = {"active", "consumed", "revoked", ":active", ":consumed", ":revoked"}
    for scenario_id, records, consumed, adjustments in _scoped_protocol_state(state):
        adjustments_by_id = {_field(a, "held-adjustment/id"): a for a in adjustments}
        adjustments_by_auth: dict[str, list[dict]] = {}
        for adjustment in adjustments:
            provenance = _field(adjustment, "authorization/provenance", {}) or {}
            auth_id = _field(provenance, "authorization/id")
            if auth_id:
                adjustments_by_auth.setdefault(auth_id, []).append(adjustment)
        for auth_id, record in records.items():
            status = _field(record, "authorization/status")
            is_consumed = bool(_field(record, "consumed?", False))
            scope = _field(record, "authorization/scope")
            scope_hash = _field(record, "authorization/scope-hash")
            linked = adjustments_by_auth.get(auth_id, [])
            entry = consumed.get(auth_id)
            if (_field(record, "authorization/id") != auth_id
                    or status not in valid_statuses
                    or not scope
                    or not scope_hash):
                violations.append({"scenario/id": scenario_id, "authorization/id": auth_id, "type": "invalid-record"})
                continue
            if status in {"active", ":active"} and (is_consumed or entry is not None):
                violations.append({"scenario/id": scenario_id, "authorization/id": auth_id, "type": "active-record-consumed"})
            if status in {"consumed", ":consumed"}:
                adjustment_id = _field(entry or {}, "held-adjustment/id")
                if (not is_consumed or entry is None or len(linked) != 1
                        or adjustment_id not in adjustments_by_id
                        or _field(_field(linked[0], "authorization/provenance", {}) or {},
                                  "authorization/scope-hash") != scope_hash):
                    violations.append({"scenario/id": scenario_id,
                                       "authorization/id": auth_id,
                                       "type": "invalid-consumption-link",
                                       "linked-adjustments": len(linked)})

        for auth_id, entry in consumed.items():
            record = records.get(auth_id)
            if (record is None
                    or _field(record, "authorization/status") not in {"consumed", ":consumed"}
                    or _field(entry, "held-adjustment/id") not in adjustments_by_id):
                violations.append({"scenario/id": scenario_id,
                                   "authorization/id": auth_id,
                                   "type": "orphan-consumption"})

    return [{"check": "force-authorisation-state-witness-consistent",
             "status": "pass" if not violations else "fail",
             "message": "authorization, consumption, and held-adjustment links verified"
                        if not violations else "protocol state witness has lifecycle violations",
             "details": violations}]


def validate_evidence_lifecycle(events: list[dict]) -> list[dict]:
    """Validate Sew force-authorisation lifecycle from evidence events."""
    checks: list[dict] = []
    if not events:
        return checks

    grants: dict[str, int] = {}
    executes: dict[str, list[int]] = {}
    revokes: dict[str, int] = {}

    for ev in events:
        aid = ev.get("auth-id")
        if not aid:
            continue
        seq = ev.get("seq", 0)
        t = ev.get("type", "")
        if t == "force-authorisation-granted":
            grants[aid] = seq
        elif t == "force-authorisation-revoked":
            revokes[aid] = seq
        elif t == "force-authorisation-executed":
            executes.setdefault(aid, []).append(seq)

    for aid, exec_seqs in executes.items():
        grant_seq = grants.get(aid)
        if grant_seq is None:
            checks.append({
                "check": f"execute-without-grant-{aid[:20]}",
                "status": "fail",
                "message": f"auth-id {aid} has execute event but no matching grant",
            })
            continue

        if len(exec_seqs) > 1:
            checks.append({
                "check": f"double-execute-{aid[:20]}",
                "status": "fail",
                "message": f"auth-id {aid} executed {len(exec_seqs)} times",
            })
            continue

        exec_seq = exec_seqs[0]
        if exec_seq < grant_seq:
            checks.append({
                "check": f"execute-before-grant-{aid[:20]}",
                "status": "fail",
                "message": f"auth-id {aid} executed at seq {exec_seq} before grant at seq {grant_seq}",
            })
            continue

        if aid in revokes:
            revoke_seq = revokes[aid]
            if exec_seq > revoke_seq:
                checks.append({
                    "check": f"revoke-before-execute-{aid[:20]}",
                    "status": "fail",
                    "message": f"auth-id {aid} revoked at seq {revoke_seq} but executed at "
                    f"seq {exec_seq} — protocol should prevent this",
                })

    for aid, grant_seq in grants.items():
        if aid not in executes:
            checks.append({
                "check": f"grant-without-execute-{aid[:20]}",
                "status": "warn",
                "message": f"auth-id {aid} was granted but never executed",
            })

    return checks


def validate_evidence_against_protocol_state(bundle: dict, events: list[dict]) -> list[dict]:
    """Cross-check Sew force-authorisation evidence against committed state."""
    if not events:
        return []
    state = _field(bundle, "protocol/state", {}) or {}
    scoped = _scoped_protocol_state(state)
    violations: list[dict] = []
    for event in events:
        auth_id = event.get("auth-id")
        if not auth_id:
            violations.append({"type": "evidence-missing-auth-id", "event": event.get("file")})
            continue
        matches = [(scenario_id, records.get(auth_id), consumed)
                   for scenario_id, records, consumed, _ in scoped
                   if auth_id in records]
        scenario_id, record, consumed = matches[0] if len(matches) == 1 else (None, None, {})
        event_type = event.get("type", "")
        if len(matches) > 1:
            violations.append({"authorization/id": auth_id,
                               "type": "ambiguous-evidence-state-record",
                               "event/type": event_type})
        elif record is None:
            violations.append({"authorization/id": auth_id,
                               "type": "evidence-without-state-record",
                               "event/type": event_type})
        elif event_type == "force-authorisation-executed":
            if (_field(record, "authorization/status") not in {"consumed", ":consumed"}
                    or auth_id not in consumed):
                violations.append({"scenario/id": scenario_id,
                                   "authorization/id": auth_id,
                                   "type": "execute-evidence-state-mismatch"})
        elif event_type == "force-authorisation-revoked":
            if _field(record, "authorization/status") not in {"revoked", ":revoked"}:
                violations.append({"scenario/id": scenario_id,
                                   "authorization/id": auth_id,
                                   "type": "revoke-evidence-state-mismatch"})
    return [{"check": "force-authorisation-evidence-state-consistent",
             "status": "pass" if not violations else "fail",
             "message": "force-authorisation evidence agrees with protocol-state witness"
                        if not violations else "evidence and protocol-state witness disagree",
             "details": violations}]


def validate_protocol_bundle(bundle_root_path: Path, run_dir: Path | None = None) -> dict:
    """Run Sew protocol-semantic validation on a bundle root.

    This is the entry point registered in ``validate.VALIDATORS`` for
    ``("sew", "1")`` bundles.
    """
    bundle = load_json(bundle_root_path)
    if bundle is None:
        return {
            "sew/schema-version": SCHEMA_VERSION,
            "sew/status": "error",
            "sew/errors": [f"Cannot read bundle root: {bundle_root_path}"],
        }

    checks: list[dict] = []

    events: list[dict] = []
    if run_dir and run_dir.is_dir():
        events = find_sew_evidence_files(run_dir)

    force_auth_used = len(events) > 0

    bundle_checks = validate_bundle_root(bundle)
    checks.extend(bundle_checks)

    proto_present = any(c["check"] == "protocol-state-hashes-present"
                        and c["status"] == "pass" for c in bundle_checks)
    if force_auth_used and not proto_present:
        checks.append({
            "check": "sew-evidence-without-state-hashes",
            "status": "fail",
            "message": f"Found {len(events)} Sew force-authorisation evidence events but "
            "bundle root has no :protocol/state-hashes",
        })

    checks.extend(validate_protocol_state(bundle, require_witness=force_auth_used))
    lifecycle_checks = validate_evidence_lifecycle(events)
    checks.extend(lifecycle_checks)
    checks.extend(validate_evidence_against_protocol_state(bundle, events))

    passed = sum(1 for c in checks if c["status"] == "pass")
    failed = sum(1 for c in checks if c["status"] == "fail")
    warned = sum(1 for c in checks if c["status"] == "warn")
    skipped = sum(1 for c in checks if c["status"] == "skip")

    status = "pass" if failed == 0 else "fail"

    return {
        "sew/schema-version": SCHEMA_VERSION,
        "sew/status": status,
        "sew/checks": checks,
        "sew/summary": {
            "passed": passed,
            "failed": failed,
            "warned": warned,
            "skipped": skipped,
        },
        "sew/force-auth-evidence-count": len(events),
    }
