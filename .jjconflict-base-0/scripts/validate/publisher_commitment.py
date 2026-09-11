"""Publisher commitment verification for the test-artifacts acceptance bar.

The publisher gate is stage 3 of acceptance:

    schema-valid -> content-integrity-valid -> publisher-authentic
    -> capability-exercised -> claim-permitted

This module verifies that an accepted artifact registry is cryptographically
committed by an authorised publisher under a declared publisher policy.  It
does **not** assert that the publisher generated the artifacts, reproduced the
run, or that any model/economic claim is correct.

The commitment is reconstructed **from already-validated data**: the verifier
re-hashes the run manifest and every artifact from disk and rebuilds the
preimage itself.  The signature envelope never supplies artifact lists, run
ids, or digests; a copied digest or a transplanted signature cannot satisfy
this gate.

Domain-separated commitment:

    preimage = {
      "domain": "prf.test-artifacts.publisher-manifest.v1",
      "publisher_manifest_schema": "publisher-manifest.v1",
      "schema_version":   <registry schema_version>
      "contract_version": <registry contract_version>
      "run_id":           <registry run_id>
      "run_manifest":     {"path": <run manifest path>, "sha256": <recomputed>}
      "artifacts":        [{"id","path","sha256","importance"} ...]  # sorted
      "required_chain_ids": ["test-run","test-summary","claimable-classification"]
      "policy":           {"id": <policy_id>, "hash": <policy hash>}
    }

    digest = sha256( canonical_json(preimage) )
    signature = ed25519 over digest

canonical_json uses sorted keys and compact separators, matching the
deterministic-hash convention used elsewhere in this repository
(``json.dumps(sort_keys=True)``).  The golden commitment-preimage fixture
locks the serialization so future Clojure or external verifiers cannot drift.

Result reasons (fail-closed; any non-``publisher-signature-valid`` is a
rejection):

    signature-invalid                 bad hex, wrong length, or verify failed
    unknown-signature-algorithm       algorithm != "ed25519"
    unknown-publisher-key             key_id not present in the policy
    publisher-not-authorised          key revoked or publisher_id mismatch
    publisher-policy-mismatch         envelope policy id/hash != applied policy
    publisher-commitment-mismatch     claimed digest != reconstructed digest
    publisher-signature-valid         accepted

Additional diagnosable rejections:

    envelope-malformed                     unreadable / not a JSON object
    unsupported-publisher-manifest-version schema_version != publisher-manifest.v1
    domain-mismatch                        envelope domain != commitment domain
    unknown-envelope-field                 envelope carries unrecognized keys
    artifact-unreadable                    a required file cannot be re-hashed
"""

from __future__ import annotations

import hashlib
import json
import pathlib
from typing import Any

import nacl.signing
import nacl.exceptions

# ── Contract constants ────────────────────────────────────────────────────────

PUBLISHER_DOMAIN = "prf.test-artifacts.publisher-manifest.v1"
PUBLISHER_MANIFEST_SCHEMA = "publisher-manifest.v1"
PUBLISHER_POLICY_SCHEMA = "publisher-policy.v1"
DEFAULT_ALGORITHM = "ed25519"
ED25519_SEED_HEX_LEN = 64
ED25519_SIG_HEX_LEN = 128

REQUIRED_CHAIN_IDS = (
    "test-run",
    "test-summary",
    "claimable-classification",
    "results-artifact",
)

# Envelope fields the publisher manifest may carry.  Anything else is rejected.
_ENVELOPE_FIELDS = frozenset({
    "schema_version",
    "domain",
    "algorithm",
    "publisher_id",
    "key_id",
    "policy",
    "signature_hex",
    "claimed_digest_hex",
    "created_at",
})

# Reason taxonomy (mirrors the Clojure keyword names without the leading ':').
REASON_SIGNATURE_INVALID = "signature-invalid"
REASON_UNKNOWN_ALGORITHM = "unknown-signature-algorithm"
REASON_UNKNOWN_KEY = "unknown-publisher-key"
REASON_NOT_AUTHORISED = "publisher-not-authorised"
REASON_POLICY_MISMATCH = "publisher-policy-mismatch"
REASON_COMMITMENT_MISMATCH = "publisher-commitment-mismatch"
REASON_VALID = "publisher-signature-valid"
REASON_MALFORMED = "envelope-malformed"
REASON_UNSUPPORTED_VERSION = "unsupported-publisher-manifest-version"
REASON_DOMAIN_MISMATCH = "domain-mismatch"
REASON_UNKNOWN_FIELD = "unknown-envelope-field"
REASON_UNREADABLE = "artifact-unreadable"


# ── Deterministic hashing helpers ─────────────────────────────────────────────

def canonical_json(obj: Any) -> str:
    """Deterministic JSON: sorted keys, compact separators."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":"))


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: pathlib.Path) -> str | None:
    """Lowercase sha256 hex of a file's bytes, or None if unreadable."""
    try:
        h = hashlib.sha256()
        with path.open("rb") as f:
            for chunk in iter(lambda: f.read(8192), b""):
                h.update(chunk)
        return h.hexdigest()
    except (OSError, IOError):
        return None


def policy_hash(policy: dict) -> str:
    """Deterministic hash of a publisher policy's identity-bearing content.

    Excludes the policy's own ``policy_hash`` field so the value is not
    self-referential.  The envelope must commit to this recomputed hash.
    """
    body = {
        "schema_version": policy["schema_version"],
        "policy_id": policy["policy_id"],
        "trusted_keys": policy["trusted_keys"],
    }
    return sha256_hex(canonical_json(body).encode("utf-8"))


# ── Commitment reconstruction (verifier-derived, never envelope-supplied) ─────

def _resolve(base_dir: pathlib.Path, path: str) -> pathlib.Path:
    p = pathlib.Path(path)
    if p.is_absolute():
        return p
    return base_dir / p


def commitment_preimage(
    registry: dict,
    run_manifest_path: str,
    run_manifest_sha256: str,
    policy_id: str,
    policy_hash_value: str,
    recomputed_artifact_shas: dict[str, str],
) -> dict:
    """Build the commitment preimage from already-validated registry data.

    ``run_manifest_sha256`` and ``recomputed_artifact_shas`` are the verifier's
    own recomputed hashes of the on-disk files (never the registry-declared
    values, never envelope-supplied values).  The ``artifacts`` projection is
    deterministic: sorted by ``id`` then ``path``, each entry carrying only
    id / path / recomputed sha256 / importance.  ``required_chain_ids`` is the
    fixed required chain.
    """
    artifacts = sorted(
        (
            {
                "id": a.get("id"),
                "path": a.get("path"),
                "sha256": recomputed_artifact_shas.get(a.get("id"), a.get("sha256")),
                "importance": a.get("importance"),
            }
            for a in registry.get("artifacts") or []
        ),
        key=lambda a: (a["id"], a["path"]),
    )
    return {
        "domain": PUBLISHER_DOMAIN,
        "publisher_manifest_schema": PUBLISHER_MANIFEST_SCHEMA,
        "schema_version": registry.get("schema_version"),
        "contract_version": registry.get("contract_version"),
        "run_id": registry.get("run_id"),
        "run_manifest": {
            "path": run_manifest_path,
            "sha256": run_manifest_sha256,
        },
        "artifacts": artifacts,
        "required_chain_ids": sorted(REQUIRED_CHAIN_IDS),
        "policy": {"id": policy_id, "hash": policy_hash_value},
    }


def commitment_digest(preimage: dict) -> str:
    """SHA-256 of the canonical JSON encoding of the commitment preimage."""
    return sha256_hex(canonical_json(preimage).encode("utf-8"))


# ── Publisher key lookup ──────────────────────────────────────────────────────

def find_trusted_key(policy: dict, key_id: str):
    """Return the trusted key entry for ``key_id``, or None."""
    for entry in policy.get("trusted_keys") or []:
        if entry.get("key_id") == key_id:
            return entry
    return None


# ── Verification ──────────────────────────────────────────────────────────────

def _fail(reason: str, detail: str = "") -> dict:
    return {"accepted": False, "reason": reason, "detail": detail}


def _ok(detail: str = "") -> dict:
    return {"accepted": True, "reason": REASON_VALID, "detail": detail}


def verify_publisher_commitment(
    registry: dict,
    run_manifest_file: pathlib.Path,
    envelope: Any,
    policy: dict,
    base_dir: pathlib.Path,
) -> dict:
    """Verify an artifact registry's publisher commitment.

    Args:
        registry            the already schema-validated test-artifacts.json dict
        run_manifest_file   the run manifest file (re-hashed from disk)
        envelope            the publisher manifest (publication.json) dict
        policy              the applied publisher policy dict
        base_dir            directory against which relative artifact paths resolve

    Returns ``{"accepted": bool, "reason": str, "detail": str}``.  Fail-closed.
    """
    if not isinstance(envelope, dict):
        return _fail(REASON_MALFORMED, "publisher manifest is not a JSON object")

    unknown = sorted(set(envelope) - _ENVELOPE_FIELDS)
    if unknown:
        return _fail(
            REASON_UNKNOWN_FIELD,
            f"unexpected publisher-manifest fields: {unknown}",
        )

    if envelope.get("schema_version") != PUBLISHER_MANIFEST_SCHEMA:
        return _fail(
            REASON_UNSUPPORTED_VERSION,
            f"expected {PUBLISHER_MANIFEST_SCHEMA!r}, "
            f"got {envelope.get('schema_version')!r}",
        )

    if envelope.get("domain") != PUBLISHER_DOMAIN:
        return _fail(
            REASON_DOMAIN_MISMATCH,
            f"expected domain {PUBLISHER_DOMAIN!r}, got {envelope.get('domain')!r}",
        )

    if envelope.get("algorithm") != DEFAULT_ALGORITHM:
        return _fail(
            REASON_UNKNOWN_ALGORITHM,
            f"unsupported signature algorithm: {envelope.get('algorithm')!r}",
        )

    # Envelope must commit to the applied policy; a mismatched policy id or a
    # stale/edited policy hash is rejected before any signature work.
    env_policy = envelope.get("policy")
    if not isinstance(env_policy, dict):
        return _fail(REASON_POLICY_MISMATCH, "envelope policy is not an object")
    recomputed_policy_hash = policy_hash(policy)
    if env_policy.get("id") != policy.get("policy_id"):
        return _fail(
            REASON_POLICY_MISMATCH,
            f"envelope policy id {env_policy.get('id')!r} != applied {policy.get('policy_id')!r}",
        )
    if env_policy.get("hash") != recomputed_policy_hash:
        return _fail(
            REASON_POLICY_MISMATCH,
            f"envelope policy hash {env_policy.get('hash')!r} != recomputed {recomputed_policy_hash!r}",
        )

    # Key authorisation is separate from cryptographic validity.
    key_id = envelope.get("key_id")
    trusted = find_trusted_key(policy, key_id)
    if trusted is None:
        return _fail(REASON_UNKNOWN_KEY, f"key_id {key_id!r} not present in publisher policy")
    if trusted.get("publisher_id") != envelope.get("publisher_id"):
        return _fail(
            REASON_NOT_AUTHORISED,
            f"key {key_id!r} is bound to publisher {trusted.get('publisher_id')!r}, "
            f"not {envelope.get('publisher_id')!r}",
        )
    if trusted.get("status") != "active":
        return _fail(REASON_NOT_AUTHORISED, f"key {key_id!r} status is {trusted.get('status')!r}")

    # Reconstruct the commitment from validated data; never from the envelope.
    # Both the run manifest and every artifact are re-hashed from disk so the
    # commitment binds actual bytes, not declared or envelope-supplied values.
    run_manifest_sha = sha256_file(run_manifest_file)
    if run_manifest_sha is None:
        return _fail(REASON_UNREADABLE, f"cannot re-hash run manifest {run_manifest_file}")
    recomputed_artifacts: dict[str, str] = {}
    for art in registry.get("artifacts") or []:
        art_path = _resolve(base_dir, art.get("path"))
        art_sha = sha256_file(art_path)
        if art_sha is None:
            return _fail(
                REASON_UNREADABLE,
                f"cannot re-hash artifact {art.get('id')!r} at {art_path}",
            )
        recomputed_artifacts[art["id"]] = art_sha
    preimage = commitment_preimage(
        registry,
        str(registry.get("run_manifest", {}).get("path") or run_manifest_file),
        run_manifest_sha,
        policy.get("policy_id"),
        recomputed_policy_hash,
        recomputed_artifacts,
    )
    digest = commitment_digest(preimage)

    # The envelope's claimed digest, when present, must equal the reconstructed
    # one.  A digest copied from elsewhere cannot be substituted.
    claimed = envelope.get("claimed_digest_hex")
    if claimed is not None:
        if claimed != digest:
            return _fail(
                REASON_COMMITMENT_MISMATCH,
                f"envelope digest {claimed!r} != reconstructed {digest!r}",
            )

    # Verify the signature over the reconstructed digest.
    sig_hex = envelope.get("signature_hex")
    if not isinstance(sig_hex, str) or len(sig_hex) != ED25519_SIG_HEX_LEN:
        return _fail(REASON_SIGNATURE_INVALID, "signature_hex is not 128 hex chars")
    try:
        sig = bytes.fromhex(sig_hex)
    except ValueError:
        return _fail(REASON_SIGNATURE_INVALID, "signature_hex is not valid hex")

    pub_hex = trusted.get("public_key_hex")
    if not isinstance(pub_hex, str) or len(pub_hex) != 64:
        return _fail(REASON_NOT_AUTHORISED, f"key {key_id!r} has no 64-char public_key_hex")
    try:
        verify_key = nacl.signing.VerifyKey(bytes.fromhex(pub_hex))
        verify_key.verify(digest.encode("utf-8"), sig)
    except (ValueError, nacl.exceptions.BadSignatureError):
        return _fail(REASON_SIGNATURE_INVALID, "ed25519 signature verification failed")

    return _ok(f"committed by key {key_id!r} under policy {policy.get('policy_id')!r}")
