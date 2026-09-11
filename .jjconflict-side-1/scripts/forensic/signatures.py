"""Ed25519 runner message signing and verification per RUNNER_SIGNATURE_SPEC_V1.

Key material:
  - Private keys are 32-byte Ed25519 seeds (not 64-byte expanded secret keys).
  - Seeds are persisted as base64url strings (no padding).
  - Public keys are 32-byte Ed25519 verify keys.

Signing rules:
  - Sign canonical JSON bytes of the message (sort_keys, indent=2)
  - Exclude self-referential fields: runner-message/hash, runner-message/signature
  - Exclude diagnostic/transport-local fields
  - Signature is portable — verifies after mailbox copy or relocation

Verification outcomes:
  valid-trusted      — signature valid, key in registry, status=trusted
  valid-unknown      — signature valid, key not in registry
  valid-inactive     — signature valid, key in registry, status=inactive
  valid-revoked      — signature valid, key in registry, status=revoked
  invalid-signature  — signature does not verify against embedded key
  unsigned           — no signature field present
  malformed          — signature field present but unparseable
"""

from __future__ import annotations

import base64
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from nacl.signing import SigningKey, VerifyKey
from nacl.encoding import Base64Encoder
from nacl.exceptions import BadSignatureError

from scripts.forensic.identity import IdentityRegistry

SIGNATURE_SCHEMA_VERSION = "runner-signature.v1"
SIGNATURE_ALGORITHM = "ed25519"

HASH_EXCLUDE_KEYS = ["runner-message/hash", "runner-message/signature"]
SIGN_EXCLUDE_KEYS = ["runner-message/signature"]

# Key file schema
KEY_FILE_SCHEMA_VERSION = "runner-key.v1"


def _canonical_json(data: dict, exclude: list[str] | None = None) -> bytes:
    if exclude:
        data = {k: v for k, v in data.items() if k not in exclude}
    return json.dumps(data, indent=2, default=str, sort_keys=True).encode("utf-8")


def generate_seed_keypair() -> tuple[bytes, bytes]:
    """Generate a new Ed25519 keypair from a random 32-byte seed.

    Returns (seed, public_key) where:
      seed       — 32-byte Ed25519 seed (private key material)
      public_key — 32-byte Ed25519 verify key
    """
    sk = SigningKey.generate()
    return bytes(sk), bytes(sk.verify_key)


def seed_keypair_to_b64(seed: bytes, public_key: bytes) -> tuple[str, str]:
    """Encode a seed keypair as base64url strings.

    Returns (seed_b64, pub_b64)."""
    return (Base64Encoder.encode(seed).decode("ascii"),
            Base64Encoder.encode(public_key).decode("ascii"))


def write_key_file(path: str | Path, runner_id: str,
                   seed_b64: str, pub_b64: str) -> None:
    """Write a structured key file with schema version."""
    import json
    payload = {
        "runner-key/schema-version": KEY_FILE_SCHEMA_VERSION,
        "runner-key/type": "ed25519-seed",
        "runner-key/runner-id": runner_id,
        "runner-key/private-seed-b64": seed_b64,
        "runner-key/public-key-b64": pub_b64,
    }
    p = Path(path).expanduser()
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(payload, indent=2))


def read_key_file(path: str | Path) -> dict:
    """Read a structured key file, validating schema version.
    Returns the parsed dict or raises on schema mismatch."""
    import json
    data = json.loads(Path(path).expanduser().read_text())
    sv = data.get("runner-key/schema-version")
    if sv != KEY_FILE_SCHEMA_VERSION:
        raise ValueError(f"Unknown key file schema version: {sv}")
    kt = data.get("runner-key/type")
    if kt != "ed25519-seed":
        raise ValueError(f"Unknown key type: {kt}")
    return data


def _message_hash(msg: dict) -> str:
    """Compute the self-referential hash of a message (helper)."""
    import hashlib
    can = _canonical_json(msg, HASH_EXCLUDE_KEYS)
    return hashlib.sha256(can).hexdigest()


def sign_message(msg: dict, private_seed_b64: str) -> dict:
    """Sign a runner message with an Ed25519 32-byte seed.

    The message is modified: runner-message/hash is set (if not already),
    and runner-message/signature is populated.

    Args:
      msg: The runner message dict.  Must have at least a type.
      private_seed_b64: Base64url-encoded 32-byte Ed25519 seed.

    Returns the signed message dict.
    """
    msg = dict(msg)
    msg.setdefault("runner-message/hash", _message_hash(msg))

    # Sign canonical message bytes excluding self-referential signature fields.
    # SigningKey.sign() accepts a seed via Base64Encoder.
    payload = _canonical_json(msg, SIGN_EXCLUDE_KEYS)

    signing_key = SigningKey(private_seed_b64, encoder=Base64Encoder)
    verify_key_b64 = signing_key.verify_key.encode(encoder=Base64Encoder).decode("ascii")
    signed = signing_key.sign(payload)
    sig_b64 = Base64Encoder.encode(signed.signature).decode("ascii")

    msg["runner-message/signature"] = {
        "runner-signature/schema-version": SIGNATURE_SCHEMA_VERSION,
        "runner-signature/algorithm": SIGNATURE_ALGORITHM,
        "runner-signature/public-key": verify_key_b64,
        "runner-signature/value": sig_b64,
        "runner-signature/signed-at": datetime.now(timezone.utc).isoformat(),
    }
    return msg


def verify_message(msg: dict,
                   registry: IdentityRegistry | None = None
                   ) -> dict:
    """Verify a runner message's signature.

    Returns a dict with:
      outcome: str — one of the outcome constants
      runner-id: str | None
      public-key: str | None
      detail: str — human-readable explanation
    """
    sig_field = msg.get("runner-message/signature")
    runner_id = msg.get("runner-message/runner-id")
    pk_b64: str | None = None

    if not sig_field:
        return _outcome("unsigned", runner_id, detail="No signature field")

    if not isinstance(sig_field, dict):
        return _outcome("malformed", runner_id, detail="Signature is not a dict")

    sv = sig_field.get("runner-signature/schema-version")
    algo = sig_field.get("runner-signature/algorithm")
    pk_b64 = sig_field.get("runner-signature/public-key")
    sig_b64 = sig_field.get("runner-signature/value")

    if sv != SIGNATURE_SCHEMA_VERSION:
        return _outcome("malformed", runner_id,
                        detail=f"Unexpected signature schema version: {sv}")
    if algo != SIGNATURE_ALGORITHM:
        return _outcome("malformed", runner_id,
                        detail=f"Unexpected algorithm: {algo}")
    if not pk_b64:
        return _outcome("malformed", runner_id,
                        detail="Missing public-key in signature")
    if not sig_b64:
        return _outcome("malformed", runner_id,
                        detail="Missing signature value")

    # Recompute the message hash for integrity
    expected_hash = _message_hash(msg)
    msg_hash = msg.get("runner-message/hash")
    if msg_hash and msg_hash != expected_hash:
        return _outcome("hash-mismatch", runner_id,
                        detail="Message hash changed after signing",
                        public_key=pk_b64)

    # Verify the signature via high-level VerifyKey API
    try:
        payload = _canonical_json(msg, SIGN_EXCLUDE_KEYS)
        sig_bytes = Base64Encoder.decode(sig_b64)
        verify_key = VerifyKey(pk_b64, encoder=Base64Encoder)
        verify_key.verify(payload, sig_bytes)
    except BadSignatureError:
        return _outcome("invalid-signature", runner_id,
                        detail="Ed25519 signature does not verify against embedded public key",
                        public_key=pk_b64)
    except Exception as e:
        return _outcome("malformed", runner_id,
                        detail=f"Signature verification error: {e}",
                        public_key=pk_b64)

    # Check identity registry
    if registry is None:
        return _outcome("valid-unknown", runner_id,
                        detail="Signature valid, no identity registry provided",
                        public_key=pk_b64)

    # Registry present: check runner-id ↔ public-key binding
    reg_pk = registry.public_key(runner_id) if runner_id else None
    if runner_id and reg_pk:
        if reg_pk != pk_b64:
            return _outcome("registry-key-mismatch", runner_id,
                            detail=f"Runner-id {runner_id} maps to different key in registry",
                            public_key=pk_b64)

    # Reverse check: if the embedded public key is in the registry, the
    # claimed runner-id must match the registered runner for that key.
    # This catches the case where a message claims runner-id "alice" but
    # uses "bob"'s registered key without "alice" being in the registry.
    if pk_b64:
        reg_entry_by_key = registry.lookup_by_key(pk_b64)
        if reg_entry_by_key:
            reg_id = reg_entry_by_key.get("runner/id")
            if reg_id and runner_id and reg_id != runner_id:
                return _outcome("registry-key-mismatch", runner_id,
                                detail=f"Public key belongs to runner {reg_id}, not {runner_id}",
                                public_key=pk_b64)

    status = registry.status(runner_id, pk_b64)

    if status == "trusted":
        return _outcome("valid-trusted", runner_id,
                        detail="Signature valid, runner is trusted",
                        public_key=pk_b64)
    elif status == "unknown":
        return _outcome("valid-unknown", runner_id,
                        detail="Signature valid, runner is unknown (not in registry)",
                        public_key=pk_b64)
    elif status == "inactive":
        return _outcome("valid-inactive", runner_id,
                        detail="Signature valid but runner status is inactive",
                        public_key=pk_b64)
    elif status == "revoked":
        return _outcome("valid-revoked", runner_id,
                        detail="Signature valid but runner key is revoked",
                        public_key=pk_b64)
    else:
        return _outcome("valid-unknown", runner_id,
                        detail=f"Signature valid, runner status '{status}'",
                        public_key=pk_b64)


def _outcome(outcome: str, runner_id: str | None = None,
             detail: str = "", public_key: str | None = None) -> dict:
    return {
        "outcome": outcome,
        "runner-id": runner_id,
        "public-key": public_key,
        "detail": detail,
    }
