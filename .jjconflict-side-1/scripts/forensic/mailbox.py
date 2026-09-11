#!/usr/bin/env python3
"""Phase 2 shared artifact mailbox for runner consensus.

Implements RUNNER_MAILBOX_SPEC_V1: a filesystem-backed, content-addressed
message store for runners and coordinators.  Transport-agnostic — messages
are JSON files that can later map to HTTP, Nostr, IPFS, or libp2p.

Usage:
    python3 scripts/forensic/mailbox.py init <dir>
    python3 scripts/forensic/mailbox.py publish-run <dir> <request-edn>
    python3 scripts/forensic/mailbox.py publish-submission <dir> <bundle-dir> --runner-id <id>
"""

from __future__ import annotations

import hashlib
import json
import shutil
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

_project_root = Path(__file__).resolve().parent.parent.parent
if str(_project_root) not in sys.path:
    sys.path.insert(0, str(_project_root))

from scripts.forensic.preflight import compute_sha256

MAILBOX_SCHEMA_VERSION = "forensic-mailbox.v1"
MESSAGE_SCHEMA_VERSION = "runner-message.v1"
OBJECT_SCHEMA_VERSION = "mailbox-object.v1"
OBJECT_HASH_ALGORITHM = "sha256-json-canonical-v1"

# ── Hash Class Reference ───────────────────────────────────────────────────
# Mailbox object hashes (sha256-json-canonical-v1) are transport/storage
# integrity hashes.  They are NOT PRF semantic artifact hashes.
#
# PRF artifact hashes use domain-separated canonical hashing via
# resolver-sim.hash.canonical/domain-hash with registered intent tags
# (e.g. :evidence-record, :bundle-root).  Those are protocol-level
# semantic hashes with domain separation.
#
# Mailbox hashes are a simpler SHA-256 of canonical JSON with sort_keys.
# They verify transport integrity, not semantic protocol identity.
# Consumers MUST NOT treat a mailbox object hash as equivalent to a
# PRF domain-hash unless the same domain-tagged projection was used.


# ── Canonical JSON Helpers ─────────────────────────────────────────────────
# These match the conventions in verify.py and consensus.py so that hashes
# are compatible across modules.


def canonical_json_bytes(data: dict,
                          exclude_keys: list[str] | None = None) -> bytes:
    """Deterministic canonical JSON encoding: sort_keys, indent=2, UTF-8.
    If exclude_keys is given, those keys are stripped before serialization."""
    if exclude_keys:
        data = {k: v for k, v in data.items() if k not in exclude_keys}
    return json.dumps(data, indent=2, default=str, sort_keys=True).encode("utf-8")


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def self_hash(data: dict, exclude: list[str]) -> str:
    """Compute a self-referential SHA-256 hash for a dict.
    Strips the exclude keys, canonical-serializes, hashes, returns hex."""
    return sha256_hex(canonical_json_bytes(data, exclude))


# ── Object Store ───────────────────────────────────────────────────────────


def _object_dir(mailbox_dir: Path) -> Path:
    return mailbox_dir / "objects" / "sha256"


def _object_path(mailbox_dir: Path, h: str) -> Path:
    return _object_dir(mailbox_dir) / h[:2] / f"{h}.json"


_OBJECT_META_KEYS = ["object/schema-version", "object/hash-algorithm",
                      "object/hash"]


def _stamp_object(obj: dict) -> dict:
    """Add mailbox object metadata fields.
    These are part of the canonical hash — the hash commits to the algorithm."""
    stamped = dict(obj)
    stamped.setdefault("object/schema-version", OBJECT_SCHEMA_VERSION)
    stamped.setdefault("object/hash-algorithm", OBJECT_HASH_ALGORITHM)
    stamped.setdefault("object/hash", None)
    return stamped


def write_object(mailbox_dir: Path, obj: dict,
                 exclude_keys: list[str] | None = None) -> str:
    """Store a content-addressed object.  Returns the object hash.
    Injects object/schema-version and object/hash-algorithm metadata before
    hashing so the hash commits to the algorithm.

    Mailbox hashes use sha256-json-canonical-v1 (transport integrity).
    They are NOT PRF domain-separated semantic hashes.

    If the dict has a self-referential hash field, list it in exclude_keys
    so it is stripped before hashing."""
    stamped = _stamp_object(obj)
    ex = set(exclude_keys or []) | {"object/hash"}
    h = self_hash(stamped, list(ex))
    obj_path = _object_path(mailbox_dir, h)
    obj_path.parent.mkdir(parents=True, exist_ok=True)
    stamped["object/hash"] = h
    obj_path.write_text(json.dumps(stamped, indent=2, default=str,
                                    sort_keys=True))
    return h


def read_object(mailbox_dir: Path, object_hash: str) -> dict | None:
    """Read a content-addressed object by its object hash.
    Returns None if the hash algorithm is not recognized."""
    path = _object_path(mailbox_dir, object_hash)
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text())
        algo = data.get("object/hash-algorithm")
        if algo and algo != OBJECT_HASH_ALGORITHM:
            return None  # Unknown hash algorithm — refuse
        return data
    except Exception:
        return None


def write_self_addressed(mailbox_dir: Path, prefix: str,
                         obj: dict, exclude: list[str]) -> str:
    """Write a self-addressed object, returning its hash.
    Stores the object both at the canonical path and in the object store."""
    h = self_hash(obj, exclude)
    stamped = dict(obj)
    for k in exclude:
        if k in stamped:
            stamped[k] = h
    # Write to object store
    write_object(mailbox_dir, stamped, exclude_keys=exclude)
    return h


# ── Message Helpers ────────────────────────────────────────────────────────


def _make_message(msg_type: str, runner_id: str,
                  extra: dict | None = None) -> dict:
    """Build a runner-message skeleton with timestamp and placeholder hash."""
    msg = {
        "runner-message/schema-version": MESSAGE_SCHEMA_VERSION,
        "runner-message/type": msg_type,
        "runner-message/timestamp": datetime.now(timezone.utc).isoformat(),
        "runner-message/hash": None,
        "runner-message/runner-id": runner_id,
        "runner-message/signature": None,
    }
    if extra:
        msg.update(extra)
    h = self_hash(msg, ["runner-message/hash", "runner-message/signature"])
    msg["runner-message/hash"] = h
    return msg


def _safe_filename(parts: list[str]) -> str:
    """Join path-safe filename parts, replacing '/' with '-'."""
    return "-".join(p.replace("/", "-") for p in parts)


def _write_message(mailbox_dir: Path, rel_dir: str,
                   filename: str, msg: dict) -> dict:
    """Write a message JSON file under mailbox_dir/rel_dir/filename."""
    full_dir = mailbox_dir / rel_dir
    full_dir.mkdir(parents=True, exist_ok=True)
    path = full_dir / filename
    path.write_text(json.dumps(msg, indent=2, default=str, sort_keys=True))
    return msg


# ── Mailbox Init ───────────────────────────────────────────────────────────


def init_mailbox(mailbox_dir: str | Path) -> Path:
    """Initialize a new mailbox directory. Returns the mailbox dir Path."""
    root = Path(mailbox_dir).expanduser().resolve()
    if root.exists():
        if (root / "mailbox.json").exists():
            raise FileExistsError(f"Mailbox already exists at {root}")
    root.mkdir(parents=True, exist_ok=True)
    # Create directory structure
    (root / "runners").mkdir()
    (root / "objects" / "sha256").mkdir(parents=True)
    metadata = {
        "mailbox/schema-version": MAILBOX_SCHEMA_VERSION,
        "mailbox/created-at": datetime.now(timezone.utc).isoformat(),
        "mailbox/spec-version": "RUNNER_MAILBOX_SPEC_V1",
        "mailbox/transport": "filesystem",
        "mailbox/runner-count": 0,
        "mailbox/run-count": 0,
    }
    (root / "mailbox.json").write_text(
        json.dumps(metadata, indent=2, default=str, sort_keys=True))
    return root


# ── Runner Identity ────────────────────────────────────────────────────────


def write_runner_announcement(mailbox_dir: Path, runner_id: str,
                               capabilities: list[str] | None = None,
                               note: str | None = None) -> dict:
    """Write a runner announcement message."""
    extra = {"runner-message/capabilities": capabilities or []}
    if note:
        extra["runner-message/note"] = note
    msg = _make_message("announcement", runner_id, extra)
    return _write_message(mailbox_dir, f"runners/{runner_id}",
                          "runner.json", msg)


# ── Run Requests ───────────────────────────────────────────────────────────


def _run_request_hash(run_id_or_hash: str) -> str:
    """Normalize a run identifier: if it looks like a hash, use it;
    otherwise hash it."""
    if len(run_id_or_hash) == 64 and all(c in "0123456789abcdef"
                                          for c in run_id_or_hash):
        return run_id_or_hash
    return sha256_hex(run_id_or_hash.encode("utf-8"))


def write_run_request(mailbox_dir: Path, request_data: dict,
                       run_id: str | None = None) -> str:
    """Write a run request record. Returns the run request hash."""
    rid = run_id or str(uuid.uuid4())
    req_hash = _run_request_hash(rid)
    req = {
        "run-request/schema-version": "run-request.v1",
        "run-request/hash": req_hash,
        "run-request/run-id": rid,
        "run-request/timestamp": datetime.now(timezone.utc).isoformat(),
        "run-request/data": request_data,
    }
    rrd = mailbox_dir / "runs" / req_hash
    rrd.mkdir(parents=True, exist_ok=True)
    (rrd / "request.json").write_text(
        json.dumps(req, indent=2, default=str, sort_keys=True))
    return req_hash


# ── Commitments ────────────────────────────────────────────────────────────


def write_commitment(mailbox_dir: Path, run_request_hash: str,
                     runner_id: str,
                     extra: dict | None = None) -> dict:
    """Write a runner commitment to participate."""
    msg = _make_message("commitment", runner_id, {
        "runner-message/run-request-hash": run_request_hash,
        **(extra or {}),
    })
    h = msg["runner-message/hash"]
    return _write_message(
        mailbox_dir, f"runs/{run_request_hash}/commitments",
        f"{_safe_filename([runner_id, h[:16]])}.json", msg)


# ── Result Submissions ─────────────────────────────────────────────────────


def write_result_submission(mailbox_dir: Path, run_request_hash: str,
                             runner_id: str,
                             summary: dict,
                             exit_code: int = 0,
                             status: str = "pass",
                             bundle_path: str | None = None,
                             elapsed_ms: int | None = None,
                             volatile: dict | None = None,
                             failures: list[dict] | None = None,
                             artifacts: dict | None = None) -> dict:
    """Write a result-submission message for a completed run."""
    extra = {
        "runner-message/run-request-hash": run_request_hash,
        "runner-message/summary": summary,
        "runner-message/status": status,
        "runner-message/exit-code": exit_code,
    }
    if elapsed_ms is not None:
        extra["runner-message/elapsed-ms"] = elapsed_ms
    if bundle_path:
        extra["runner-message/bundle-path"] = bundle_path
    if volatile:
        extra["runner-message/volatile"] = volatile
    if failures:
        extra["runner-message/failures"] = failures
    if artifacts:
        extra["runner-message/artifacts"] = artifacts
    msg = _make_message("result-submission", runner_id, extra)
    h = msg["runner-message/hash"]
    return _write_message(
        mailbox_dir, f"runs/{run_request_hash}/submissions",
        f"{_safe_filename([runner_id, h[:16]])}.json", msg)


def write_error_report(mailbox_dir: Path, run_request_hash: str,
                        runner_id: str, error_kind: str,
                        error_message: str, exit_code: int = -1) -> dict:
    """Write an error-report message."""
    extra = {
        "runner-message/run-request-hash": run_request_hash,
        "runner-message/error-kind": error_kind,
        "runner-message/error-message": error_message,
        "runner-message/exit-code": exit_code,
    }
    msg = _make_message("error-report", runner_id, extra)
    h = msg["runner-message/hash"]
    return _write_message(
        mailbox_dir, f"runs/{run_request_hash}/errors",
        f"{_safe_filename([runner_id, h[:16]])}.json", msg)


# ── Reading Submissions ────────────────────────────────────────────────────


def list_result_submissions(mailbox_dir: Path,
                             run_request_hash: str) -> list[dict]:
    """List all result-submission messages for a run request."""
    sub_dir = mailbox_dir / "runs" / run_request_hash / "submissions"
    if not sub_dir.exists():
        return []
    messages = []
    for f in sorted(sub_dir.iterdir()):
        if f.is_file() and f.suffix == ".json":
            try:
                messages.append(json.loads(f.read_text()))
            except Exception:
                pass
    return messages


def deduplicate_submissions(messages: list[dict]) -> tuple[list[dict], list[dict]]:
    """Deduplicate runner submissions with equivocation detection.

    Rules:
      - Same runner, same result hash → idempotent (keep one, silent)
      - Same runner, different result hash → equivocation (excluded, warning emitted)
      - Different runners, same result hash → normal agreement (kept)
      - Malformed runner-id (missing, None, empty) → rejected with warning

    Returns (valid_submissions, warning_messages)."""
    by_runner: dict[str, list[dict]] = {}
    warnings: list[dict] = []

    for msg in messages:
        rid = msg.get("runner-message/runner-id")
        if not rid or not isinstance(rid, str) or not rid.strip():
            warnings.append({
                "warning/code": "malformed-runner-id",
                "warning/message": f"Submission with malformed runner-id: {rid!r}",
                "warning/submission-hash": msg.get("runner-message/hash"),
            })
            continue
        by_runner.setdefault(rid, []).append(msg)

    valid: list[dict] = []
    for rid, msgs in by_runner.items():
        seen_hashes: set[str] = set()
        unique: list[dict] = []
        for m in msgs:
            h = m.get("runner-message/hash", "")
            if h in seen_hashes:
                continue  # Idempotent duplicate — skip silently
            seen_hashes.add(h)
            unique.append(m)
        if len(unique) > 1:
            # Same runner, different hashes → equivocation
            warnings.append({
                "warning/code": "runner-equivocation",
                "warning/message": (f"Runner {rid} submitted {len(unique)} different "
                                    f"result messages ({len(msgs)} files, "
                                    f"{len(unique)} unique hashes). "
                                    f"Only the latest is accepted."),
                "warning/runner-id": rid,
                "warning/submission-count": len(msgs),
                "warning/unique-count": len(unique),
            })
            # Keep only the latest by timestamp
            unique.sort(key=lambda m: m.get("runner-message/timestamp", ""), reverse=True)
            valid.append(unique[0])
        else:
            valid.extend(unique)

    # Normalize to consensus format
    normalized = []
    for msg in valid:
        summary = msg.get("runner-message/summary", {})
        normalized.append({
            "runner-message/schema-version": MESSAGE_SCHEMA_VERSION,
            "runner-message/type": "result-submission",
            "runner-message/timestamp": msg.get("runner-message/timestamp"),
            "runner-message/runner-id": msg.get("runner-message/runner-id", "?"),
            "runner-message/run-id": msg.get("runner-message/run-request-hash", "?"),
            "runner-message/status": msg.get("runner-message/status", "fail"),
            "runner-message/exit-code": msg.get("runner-message/exit-code", -1),
            "runner-message/summary": summary,
            "runner-message/bundle-path": msg.get("runner-message/bundle-path"),
        })
    return normalized, warnings


def load_result_submissions(mailbox_dir: Path,
                             run_request_hash: str) -> tuple[list[dict], list[dict]]:
    """Load result submissions, deduplicate, and convert to consensus format.

    Returns (submissions, warnings) where submissions is the same shape as
    consensus.collect_submissions() and warnings contains deduplication and
    equivocation diagnostics."""
    raw = list_result_submissions(mailbox_dir, run_request_hash)
    return deduplicate_submissions(raw)


# ── Consensus Writeback ────────────────────────────────────────────────────


def write_consensus_outputs(mailbox_dir: Path, run_request_hash: str,
                             certificate: dict | None = None,
                             disagreement: dict | None = None,
                             evidence_node: dict | None = None) -> None:
    """Write consensus outputs into the mailbox for a run request."""
    cons_dir = mailbox_dir / "runs" / run_request_hash / "consensus"
    cons_dir.mkdir(parents=True, exist_ok=True)
    if certificate:
        (cons_dir / "consensus-certificate.json").write_text(
            json.dumps(certificate, indent=2, default=str, sort_keys=True))
    if disagreement:
        (cons_dir / "disagreement-report.json").write_text(
            json.dumps(disagreement, indent=2, default=str, sort_keys=True))
    if evidence_node:
        (cons_dir / "evidence-node.json").write_text(
            json.dumps(evidence_node, indent=2, default=str, sort_keys=True))
    # Also store as content-addressed objects
    for name, obj in [("consensus-certificate.json", certificate),
                       ("disagreement-report.json", disagreement),
                       ("evidence-node.json", evidence_node)]:
        if obj:
            exclude = []
            if name == "consensus-certificate.json":
                exclude = ["consensus-certificate/hash",
                           "consensus-certificate/signature"]
            elif name == "disagreement-report.json":
                exclude = ["disagreement/hash"]
            write_object(mailbox_dir, obj, exclude_keys=exclude)


# ── Artifact Storage ───────────────────────────────────────────────────────


def write_bundle_artifact(mailbox_dir: Path, run_request_hash: str,
                           bundle_dir: Path) -> str:
    """Copy a bundle root and store its manifest as a content-addressed object.
    Returns the manifest hash."""
    run_dir = mailbox_dir / "runs" / run_request_hash / "artifacts"
    run_dir.mkdir(parents=True, exist_ok=True)
    bundle_path = Path(bundle_dir).expanduser().resolve()
    # Read bundle root to capture manifest
    bundle_root = None
    for candidate in ["clojure-bundle-root.json", "run-bundle-root.json"]:
        p = bundle_path / candidate
        if p.exists():
            bundle_root = json.loads(p.read_text())
            break
    manifest = {
        "artifact/schema-version": "bundle-manifest.v1",
        "artifact/bundle-path": str(bundle_path),
        "artifact/bundle-root": bundle_root,
    }
    mh = write_object(mailbox_dir, manifest)
    # Write as <hash>.json in artifacts dir for convenience
    (run_dir / f"{mh}.json").write_text(
        json.dumps(manifest, indent=2, default=str, sort_keys=True))
    return mh


# ── Mailbox Summary ────────────────────────────────────────────────────────


def _check(status: str, key: str, msg: str) -> dict:
    return {"check/status": status, "check/key": key, "check/message": msg}


def _apply_strict(checks: list[dict], strict: bool) -> list[dict]:
    """In strict mode, upgrade warnings to failures."""
    if not strict:
        return checks
    for c in checks:
        if c.get("check/status") == "warn":
            c["check/status"] = "fail"
    return checks


def validate_mailbox(mailbox_dir: Path,
                     run_request_hash: str | None = None,
                     identity_registry: IdentityRegistry | None = None,
                     strict: bool = False,
                     require_signatures: bool = False) -> list[dict]:
    """Validate mailbox lifecycle invariants per RUNNER_MAILBOX_VALIDATION_SPEC_V1.

    Args:
      mailbox_dir: Path to the mailbox root.
      run_request_hash: If given, validate only this run request.
      identity_registry: Optional IdentityRegistry for signature verification.
      strict: Upgrade warnings to failures.
      require_signatures: Reject unsigned submissions.

    Returns a list of check results."""
    from scripts.forensic.signatures import verify_message, _canonical_json as sig_canon
    from scripts.forensic.identity import IdentityRegistry

    root = Path(mailbox_dir).expanduser().resolve()
    checks: list[dict] = []

    # ── Structural Checks ──

    mb_path = root / "mailbox.json"
    if not mb_path.exists():
        return [_check("fail", "mailbox-exists",
                       f"mailbox.json not found at {mb_path}")]
    try:
        json.loads(mb_path.read_text())
        checks.append(_check("pass", "mailbox-exists", "mailbox.json exists and is valid"))
    except Exception:
        return [_check("fail", "mailbox-exists", "mailbox.json is not valid JSON")]

    if not run_request_hash:
        runs_dir = root / "runs"
        if runs_dir.exists():
            rc = len([d for d in runs_dir.iterdir() if d.is_dir()])
            checks.append(_check("info", "run-count", f"{rc} run request(s)"))
        return _apply_strict(checks, strict)

    # ── Per-Run-Request Checks ──

    rr_dir = root / "runs" / run_request_hash
    rr_file = rr_dir / "request.json"

    if not rr_file.exists():
        checks.append(_check("fail", "run-request-exists",
                             f"Run request {run_request_hash[:16]}... not found"))
        return _apply_strict(checks, strict)
    checks.append(_check("pass", "run-request-exists",
                         f"Run request {run_request_hash[:16]}... found"))

    # Validate run request schema
    try:
        rr_data = json.loads(rr_file.read_text())
        rr_sv = rr_data.get("run-request/schema-version")
        checks.append(_check("pass" if rr_sv == "run-request.v1" else "warn",
                             "run-request-valid",
                             f"Run request schema-version: {rr_sv}"))
    except Exception:
        checks.append(_check("fail", "run-request-valid",
                             "Run request is not valid JSON"))

    # ── Submissions ──

    sub_dir = rr_dir / "submissions"
    raw_messages: list[dict] = []
    if sub_dir.exists():
        for f in sorted(sub_dir.iterdir()):
            if f.suffix == ".json":
                try:
                    raw_messages.append(json.loads(f.read_text()))
                except Exception:
                    checks.append(_check("fail", f"submission-parse-{f.name[:20]}",
                                         f"Submission {f.name} is not valid JSON"))

    # Deduplicate
    valid_subs, dedup_warnings = deduplicate_submissions(raw_messages)
    for w in dedup_warnings:
        code = w.get("warning/code", "dedup")
        msg = w.get("warning/message", "")
        checks.append(_check("warn", f"submission-{code}", msg))

    checks.append(_check(
        "pass" if valid_subs else "info",
        "submission-count",
        f"{len(valid_subs)} valid submission(s) from {len(raw_messages)} file(s)"))

    # Per-submission checks
    seen_runner_keys: dict[str, list[str]] = {}  # runner-id → [hash]
    seen_crypto_keys: dict[str, list[str]] = {}  # public-key → [hash]

    for msg in raw_messages:
        h = msg.get("runner-message/hash", "")
        mtype = msg.get("runner-message/type", "?")
        rid = msg.get("runner-message/runner-id", "?")
        short = h[:16] if h else "?"

        # Hash integrity
        if h:
            expected = self_hash(msg, ["runner-message/hash", "runner-message/signature"])
            checks.append(_check(
                "pass" if h == expected else "fail",
                f"submission-hash-{short}",
                f"Submission {rid} hash {'verifies' if h == expected else 'MISMATCH'}"))
        else:
            checks.append(_check("warn" if not require_signatures else "fail",
                                 f"submission-hash-{short}",
                                 f"Submission {rid} missing runner-message/hash"))

        # Schema version
        msg_sv = msg.get("runner-message/schema-version")
        if msg_sv and msg_sv != MESSAGE_SCHEMA_VERSION:
            checks.append(_check("warn", f"submission-schema-{short}",
                                 f"Submission {rid} schema-version: {msg_sv}"))

        # Run request hash match
        msg_rrh = msg.get("runner-message/run-request-hash")
        if msg_rrh and msg_rrh != run_request_hash:
            checks.append(_check("fail", f"submission-rrh-{short}",
                                 f"Submission {rid} run-request-hash MISMATCH"))

        # Signature verification
        sig_result = verify_message(msg, identity_registry)
        outcome = sig_result["outcome"]

        if outcome == "unsigned":
            if require_signatures:
                checks.append(_check("fail", f"submission-signature-{short}",
                                     f"Submission {rid} is unsigned (signatures required)"))
            else:
                checks.append(_check("info", f"submission-signature-{short}",
                                     f"Submission {rid} is unsigned"))
        elif outcome == "valid-trusted":
            checks.append(_check("pass", f"submission-signature-{short}",
                                 f"Submission {rid} signature valid, trusted"))
        elif outcome == "valid-unknown":
            checks.append(_check("warn", f"submission-signature-{short}",
                                 f"Submission {rid} signature valid, unknown key"))
        elif outcome in ("valid-inactive", "valid-revoked"):
            checks.append(_check("fail", f"submission-signature-{short}",
                                 f"Submission {rid} signature valid but {outcome.replace('valid-', '')}"))
        elif outcome == "invalid-signature":
            checks.append(_check("fail", f"submission-signature-{short}",
                                 f"Submission {rid} invalid signature: {sig_result.get('detail', '')}"))
        elif outcome == "malformed":
            checks.append(_check("fail", f"submission-signature-{short}",
                                 f"Submission {rid} malformed signature"))

        # Equivocation tracking
        pk = None
        sig_field = msg.get("runner-message/signature")
        if isinstance(sig_field, dict):
            pk = sig_field.get("runner-signature/public-key")

        if h:
            seen_runner_keys.setdefault(rid, []).append(h)
            if pk:
                seen_crypto_keys.setdefault(pk, []).append(h)

    # Runner-id equivocation
    for rid, hashes in seen_runner_keys.items():
        if len(set(hashes)) > 1:
            checks.append(_check("warn", f"equivocation-runner-{rid}",
                                 f"Runner {rid} submitted {len(set(hashes))} different result hashes"))

    # Cryptographic equivocation
    for pk, hashes in seen_crypto_keys.items():
        if len(set(hashes)) > 1:
            checks.append(_check("fail", f"equivocation-crypto-{pk[:16]}",
                                 f"Same key signed {len(set(hashes))} different result hashes"))

    # ── Consensus Outputs ──

    cons_dir = rr_dir / "consensus"
    if cons_dir.exists():
        cert_file = cons_dir / "consensus-certificate.json"
        if cert_file.exists():
            try:
                cert = json.loads(cert_file.read_text())
                ch = cert.get("consensus-certificate/hash")
                if ch:
                    expected = self_hash(
                        cert, ["consensus-certificate/hash",
                               "consensus-certificate/signature"])
                    checks.append(_check(
                        "pass" if ch == expected else "fail",
                        "certificate-hash",
                        "Certificate hash verifies" if ch == expected
                        else "Certificate hash MISMATCH"))
                # Certificate signature
                cert_sig = cert.get("consensus-certificate/signature")
                if cert_sig:
                    sig_result = verify_message({"runner-message/signature": cert_sig,
                                                  "runner-message/hash": ch},
                                                 identity_registry)
                    checks.append(_check(
                        "pass" if sig_result["outcome"] in ("valid-trusted", "valid-unknown")
                        else "fail",
                        "certificate-signature",
                        f"Certificate signature: {sig_result['outcome']}"))
                # No absolute paths
                cert_str = json.dumps(cert)
                has_abs = any(marker in cert_str for marker in ["/tmp/", "/var/", "/home/"])
                checks.append(_check("warn" if has_abs else "pass",
                                     "certificate-no-abs-paths",
                                     "No absolute paths in certificate" if not has_abs
                                     else "Certificate contains absolute paths"))
            except Exception:
                checks.append(_check("fail", "certificate-parse",
                                     "Certificate is not valid JSON"))

        dis_file = cons_dir / "disagreement-report.json"
        if dis_file.exists():
            checks.append(_check("pass", "disagreement-exists",
                                 "Disagreement report present"))

        ev_file = cons_dir / "evidence-node.json"
        if ev_file.exists():
            try:
                ev = json.loads(ev_file.read_text())
                eid = ev.get("execution", {}).get("execution-id")
                checks.append(_check(
                    "pass" if eid == "execution/consensus" else "warn",
                    "evidence-node-execution-id",
                    f"Evidence node execution-id: {eid}"))
            except Exception:
                checks.append(_check("fail", "evidence-node-parse",
                                     "Evidence node is not valid JSON"))
        else:
            checks.append(_check("info", "evidence-node-exists",
                                 "No evidence node present"))
    else:
        checks.append(_check("info", "consensus-dir", "No consensus outputs yet"))

    # ── Object Store Integrity (sample) ──
    obj_dir = root / "objects" / "sha256"
    if obj_dir.exists():
        obj_count = 0
        for prefix_dir in obj_dir.iterdir():
            if prefix_dir.is_dir():
                for f in prefix_dir.iterdir():
                    if f.suffix == ".json":
                        obj_count += 1
                        try:
                            odata = json.loads(f.read_text())
                            fname_hash = f.stem
                            o_algo = odata.get("object/hash-algorithm")
                            o_sv = odata.get("object/schema-version")
                            o_hash = odata.get("object/hash")
                            if o_hash and o_hash != fname_hash:
                                checks.append(_check(
                                    "fail", f"object-hash-{fname_hash[:16]}",
                                    f"Object hash mismatch: file={fname_hash[:16]}... content={o_hash[:16]}..."))
                            if o_algo and o_algo != OBJECT_HASH_ALGORITHM:
                                checks.append(_check(
                                    "warn", f"object-algorithm-{fname_hash[:16]}",
                                    f"Object algorithm: {o_algo}"))
                            if o_sv and o_sv != OBJECT_SCHEMA_VERSION:
                                checks.append(_check(
                                    "warn", f"object-schema-{fname_hash[:16]}",
                                    f"Object schema-version: {o_sv}"))
                        except Exception:
                            checks.append(_check(
                                "warn", f"object-parse-{f.stem[:16]}",
                                f"Object {f.name} is not valid JSON"))
        if obj_count == 0:
            checks.append(_check("info", "object-count", "No objects in store"))
    else:
        checks.append(_check("info", "object-store", "Object store directory not found"))

    return _apply_strict(checks, strict)


def mailbox_summary(mailbox_dir: Path) -> dict:
    """Return a summary of the mailbox contents."""
    root = Path(mailbox_dir).expanduser().resolve()
    runner_count = 0
    runner_dir = root / "runners"
    if runner_dir.exists():
        # Count leaf-level runner dirs (those containing runner.json)
        for d in runner_dir.rglob("runner.json"):
            runner_count += 1
    run_count = 0
    runs_dir = root / "runs"
    if runs_dir.exists():
        run_count = len([d for d in runs_dir.iterdir() if d.is_dir()])
    return {
        "mailbox/schema-version": MAILBOX_SCHEMA_VERSION,
        "mailbox/path": str(root),
        "mailbox/runner-count": runner_count,
        "mailbox/run-count": run_count,
    }


# ── CLI Entry Points ───────────────────────────────────────────────────────


def cmd_init(args: list[str]) -> int:
    import argparse
    p = argparse.ArgumentParser(prog="bb forensic:mailbox:init")
    p.add_argument("dir", help="Mailbox directory path")
    a = p.parse_args(args)
    path = init_mailbox(a.dir)
    print(f"Initialized mailbox at {path}", file=sys.stderr)
    return 0


def cmd_publish_run(args: list[str]) -> int:
    import argparse
    p = argparse.ArgumentParser(prog="bb forensic:mailbox:publish-run")
    p.add_argument("dir", help="Mailbox directory path")
    p.add_argument("request", help="Run request file (EDN or JSON)")
    p.add_argument("--run-id", default=None, help="Optional run identifier")
    a = p.parse_args(args)
    mb = Path(a.dir).expanduser().resolve()
    req_text = Path(a.request).expanduser().read_text()
    request_data = json.loads(req_text) if a.request.endswith(".json") else {"path": a.request}
    rrh = write_run_request(mb, request_data, run_id=a.run_id)
    print(f"Published run request: {rrh}", file=sys.stderr)
    return 0


def cmd_validate(args: list[str]) -> int:
    """Validate mailbox lifecycle invariants."""
    import argparse
    p = argparse.ArgumentParser(prog="bb forensic:mailbox:validate")
    p.add_argument("dir", help="Mailbox directory path")
    p.add_argument("--run-request-hash", default=None,
                   help="Validate a specific run request")
    p.add_argument("--identity-registry", default=None,
                   help="Path to identity registry JSON")
    p.add_argument("--strict", action="store_true",
                   help="Upgrade warnings to failures")
    p.add_argument("--require-signatures", action="store_true",
                   help="Reject unsigned submissions")
    p.add_argument("--output", default=None,
                   help="Write validation report to file")
    a = p.parse_args(args)
    mb = Path(a.dir).expanduser().resolve()
    registry = None
    if a.identity_registry:
        from scripts.forensic.identity import IdentityRegistry
        registry = IdentityRegistry.load(a.identity_registry)
    checks = validate_mailbox(mb, a.run_request_hash,
                               identity_registry=registry,
                               strict=a.strict,
                               require_signatures=a.require_signatures)
    timestamp = datetime.now(timezone.utc).isoformat()
    fails = sum(1 for c in checks if c.get("check/status") == "fail")
    report = {
        "validate/schema-version": "mailbox-validate.v1",
        "validate/mailbox-path": str(mb),
        "validate/run-request-hash": a.run_request_hash,
        "validate/timestamp": timestamp,
        "validate/checks": checks,
        "validate/summary": {
            "total": len(checks),
            "pass": sum(1 for c in checks if c.get("check/status") == "pass"),
            "info": sum(1 for c in checks if c.get("check/status") == "info"),
            "warn": sum(1 for c in checks if c.get("check/status") == "warn"),
            "fail": fails,
        },
    }
    if a.output:
        Path(a.output).expanduser().write_text(
            json.dumps(report, indent=2, default=str, sort_keys=True))
        print(f"Validation report written to {a.output}", file=sys.stderr)
    for c in checks:
        status = c.get("check/status", "?")
        msg = c.get("check/message", "")
        print(f"  [{status:>5}] {msg}", file=sys.stderr)
    s = report["validate/summary"]
    print(f"  Total: {s['total']}, Pass: {s['pass']}, Fail: {s['fail']}, Warn: {s['warn']}, Info: {s['info']}",
          file=sys.stderr)
    if fails:
        print(f"  {fails} failure(s) found", file=sys.stderr)
    else:
        print(f"  All checks passed", file=sys.stderr)
    return 1 if fails else 0


def cmd_publish_submission(args: list[str]) -> int:
    import argparse
    p = argparse.ArgumentParser(prog="bb forensic:mailbox:publish-submission")
    p.add_argument("dir", help="Mailbox directory path")
    p.add_argument("bundle-dir", help="Run bundle directory")
    p.add_argument("--runner-id", default="runner/local-forensic",
                   help="Runner identifier")
    p.add_argument("--run-request-hash", required=True,
                   help="Run request hash this submission belongs to")
    p.add_argument("--runner-key", default=None, dest="runner_key",
                   help="Path to Ed25519 runner seed key (.ed25519-key.json)")
    a = p.parse_args(args)
    mb = Path(a.dir).expanduser().resolve()
    bd = Path(vars(a)["bundle-dir"]).expanduser().resolve()
    # Read bundle root
    from scripts.forensic.consensus import _build_summary, _deep_get
    bundle_root = None
    for candidate in ["clojure-bundle-root.json", "run-bundle-root.json"]:
        p2 = bd / candidate
        if p2.exists():
            bundle_root = json.loads(p2.read_text())
            break
    if not bundle_root:
        print(f"Error: no bundle root found in {bd}", file=sys.stderr)
        return 1
    summary = _build_summary(bundle_root)
    exit_code = _deep_get(bundle_root, "run/exit-code") or 0
    status = "pass" if exit_code == 0 else "fail"
    # Store bundle manifest as artifact
    mh = write_bundle_artifact(mb, a.run_request_hash, bd)
    artifacts = {"manifest-hash": mh}
    msg = write_result_submission(mb, a.run_request_hash, a.runner_id,
                                   summary, exit_code=exit_code,
                                   status=status, bundle_path=str(bd),
                                   artifacts=artifacts)
    # Sign if runner key provided
    if a.runner_key:
        from scripts.forensic.signatures import sign_message, read_key_file
        kp = Path(a.runner_key).expanduser()
        # Reject OpenSSH/PEM/PKCS#8 keys — these are bundle signing keys, not runner keys
        raw = kp.read_text()
        if "BEGIN OPENSSH PRIVATE KEY" in raw or "BEGIN PRIVATE KEY" in raw:
            print("Error: Unsupported key format.  This looks like a bundle signing key.", file=sys.stderr)
            print("Mailbox runner signatures use .ed25519-key.json files generated by:", file=sys.stderr)
            print("  bb forensic:mailbox:keygen --runner-id <id> --out <dir>", file=sys.stderr)
            return 1
        if kp.suffix == ".json":
            key_data = read_key_file(kp)
            seed_b64 = key_data["runner-key/private-seed-b64"]
        else:
            seed_b64 = raw.strip()
        msg = sign_message(msg, seed_b64)
        # Re-write with signature
        from scripts.forensic.mailbox import _write_message as _wm
        from scripts.forensic.mailbox import _safe_filename as _sf
        h2 = msg["runner-message/hash"]
        _wm(mb, f"runs/{a.run_request_hash}/submissions",
            f"{_sf([a.runner_id, h2[:16]])}.json", msg)
        print(f"Signed submission: {h2[:16]}...", file=sys.stderr)
    else:
        h = msg["runner-message/hash"]
        print(f"Published submission: {h[:16]}...", file=sys.stderr)
    return 0


def cmd_keygen(args: list[str]) -> int:
    """Generate a new Ed25519 runner keypair (32-byte seed)."""
    import argparse
    p = argparse.ArgumentParser(prog="bb forensic:mailbox:keygen")
    p.add_argument("--runner-id", default="runner/local",
                   help="Runner identifier")
    p.add_argument("--out", default=None,
                   help="Output directory for key files")
    a = p.parse_args(args)
    from scripts.forensic.signatures import (generate_seed_keypair,
                                              seed_keypair_to_b64,
                                              write_key_file)
    seed, pk = generate_seed_keypair()
    seed_b64, pub_b64 = seed_keypair_to_b64(seed, pk)
    out_dir = Path(a.out).expanduser().resolve() if a.out else Path.cwd()
    out_dir.mkdir(parents=True, exist_ok=True)
    safe_id = a.runner_id.replace("/", "-")
    key_path = out_dir / f"{safe_id}.ed25519-key.json"
    pub_path = out_dir / f"{safe_id}.ed25519.pub"
    write_key_file(key_path, a.runner_id, seed_b64, pub_b64)
    pub_path.write_text(pub_b64)
    print(f"Generated Ed25519 keypair for {a.runner_id}", file=sys.stderr)
    print(f"  Key file (seed): {key_path}", file=sys.stderr)
    print(f"  Public key:      {pub_path}", file=sys.stderr)
    print(f"  Seed (b64, 32 bytes): {seed_b64[:20]}...", file=sys.stderr)
    print(f"  Public key (b64):     {pub_b64[:20]}...", file=sys.stderr)
    return 0


def main():
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 1
    cmd = sys.argv[1]
    rest = sys.argv[2:]
    commands = {
        "init": cmd_init,
        "publish-run": cmd_publish_run,
        "publish-submission": cmd_publish_submission,
        "validate": cmd_validate,
        "keygen": cmd_keygen,
    }
    fn = commands.get(cmd)
    if not fn:
        print(f"Unknown command: {cmd}", file=sys.stderr)
        print(f"Available: {', '.join(commands)}", file=sys.stderr)
        return 1
    return fn(rest)


if __name__ == "__main__":
    sys.exit(main())
