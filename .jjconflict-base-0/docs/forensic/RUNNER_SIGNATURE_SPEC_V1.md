# RUNNER_SIGNATURE_SPEC_V1

Status: Draft V1

## 1. Purpose

Define how forensic runner messages are cryptographically signed and
verified.  Signing proves which runner produced a message and guarantees
integrity — a signed submission cannot be tampered with after the runner
signs it.

Key question: Can I verify this runner result after copying the mailbox
somewhere else?

Answer: Yes, if the submission includes the runner's signature over the
message content.  The signature is portable — it verifies anywhere,
independent of the mailbox path, filesystem metadata, or transport.

## 2. Design Principles

### 2.1 Sign the Canonical Message, Not the File

The signature is computed over the canonical JSON bytes of the message
(excluding the signature field itself).  This ensures the signature is
independent of filename, directory path, file modification time, and
transport envelope.

### 2.2 Ed25519

All signatures use Ed25519 (RFC 8032).  Ed25519 provides fast verification,
small signatures (64 bytes), and deterministic signing (no random nonce).

### 2.3 Portable

A signed message can be copied to any mailbox, any filesystem, any
machine, and the signature still verifies.  No transport metadata is
included in the signed payload.

### 2.4 Signing Is Optional

Phase 3 does not require all messages to be signed.  Unsigned messages
are accepted but flagged as `unverified` in validation.  Signatures are
verified when the signer's public key is available.

## 3. Signature Placement

Runner messages (RUNNER_MESSAGE_SPEC_V1) have a reserved signature field:

```json
{
  "runner-message/schema-version": "runner-message.v1",
  "runner-message/type": "result-submission",
  "runner-message/hash": "sha256:...",
  "runner-message/runner-id": "runner/ci-validation",
  "runner-message/signature": {
    "runner-signature/schema-version": "runner-signature.v1",
    "runner-signature/algorithm": "ed25519",
    "runner-signature/public-key": "base64url-encoded-key",
    "runner-signature/value": "base64url-encoded-signature",
    "runner-signature/signed-at": "2026-06-28T12:05:00Z"
  },
  "runner-message/summary": { ... }
}
```

### 3.1 Signature Object Fields

| Field | Required | Description |
|---|---|---|
| `runner-signature/schema-version` | YES | `"runner-signature.v1"` |
| `runner-signature/algorithm` | YES | `"ed25519"` |
| `runner-signature/public-key` | YES | Base64url-encoded Ed25519 public key |
| `runner-signature/value` | YES | Base64url-encoded Ed25519 signature |
| `runner-signature/signed-at` | YES | ISO-8601 UTC timestamp |

## 4. Signing Procedure

1. Build the runner message (all fields except `runner-message/signature`)
2. Compute the canonical JSON bytes of the message with `sort_keys=True`,
   `indent=2`, UTF-8 encoding, excluding `runner-message/hash` and
   `runner-message/signature` (these are self-referential / signature fields)
3. Compute SHA-256 of those bytes → set `runner-message/hash`
4. Sign the canonical JSON bytes (same bytes used for the hash, but
   including `runner-message/hash` this time — the hash is part of the
   signed payload so the signature commits to the message content)
5. Set `runner-message/signature/value` to the Ed25519 signature
6. Set `runner-message/signature/public-key` to the signer's public key

### 4.1 Signing Payload Definition

The bytes fed to Ed25519 are the canonical JSON bytes of the message
excluding the `runner-message/signature` field:

```
canonical_json(message_without_signature_field)
```

Where `canonical_json` means `json.dumps(data, indent=2, sort_keys=True,
default=str).encode("utf-8")`.

The full canonical message bytes are signed, not a pre-hash.  The
`runner-message/hash` field IS included in the signed payload — the
signature commits to the hash, and the hash commits to the message
content.  This means tampering with any signed field changes both the
hash and the signature.

## 5. Verification Procedure

1. Extract `runner-message/signature` from the message
2. Extract `runner-message/hash` from the message
3. Recompute the canonical JSON bytes excluding `runner-message/signature`
   and `runner-message/hash` → compute SHA-256
4. Assert the computed hash matches `runner-message/hash` (message integrity)
5. Look up the public key in the identity registry (RUNNER_IDENTITY_SPEC_V1)
6. If key not found → status is `unknown` — verification advisory only
7. If key found and status is `revoked` or `inactive` → verification FAILS
8. Verify Ed25519 signature: `ed25519_verify(public_key, hash, signature)`
9. If signature valid and key trusted → verification PASSES

### 5.1 Verification Outcomes

| Outcome | Conditions | Severity |
|---|---|---|
| `verified` | Hash matches, signature valid, key trusted | Pass |
| `verified-unknown-key` | Hash matches, signature valid, key not in registry | Warning |
| `unverified` | No signature field present | Info |
| `hash-mismatch` | Message content changed after signing | Fail |
| `signature-invalid` | Signature does not verify against public key | Fail |
| `key-revoked` | Key exists but status is revoked | Fail |
| `key-inactive` | Key exists but status is inactive | Fail |

## 6. Equivocation Detection

A runner equivocates when it submits two different result messages for the
same run request with the same runner-id (or same public key).  Signature
verification enables cryptographic equivocation detection:

1. Two submissions from the same public key with different hashes →
   cryptographic proof of equivocation
2. Two submissions from the same runner-id with different public keys →
   identity theft or key rotation (detected via registry)
3. Two submissions from the same runner-id, one signed one unsigned →
   unsigned may be a replay; signed is the authoritative version

Equivocation is recorded in the submission warnings (Phase 2 already
implements this for runner-id based detection).  Phase 3 adds
cryptographic binding by checking the public key, not just the runner-id.

## 7. Consensus Certificate Signing

The consensus certificate (RUNNER_CONSENSUS_SPEC_V1) MAY also be signed,
either by the coordinator or by each participant.  When signed:

```json
{
  "consensus-certificate/schema-version": "consensus-certificate.v1",
  "consensus-certificate/signature": {
    "runner-signature/schema-version": "runner-signature.v1",
    "runner-signature/algorithm": "ed25519",
    "runner-signature/public-key": "base64url...",
    "runner-signature/value": "base64url...",
    "runner-signature/signed-at": "2026-06-28T12:06:00Z"
  },
  "consensus-certificate/hash": "sha256:..."
}
```

## 8. Key Format Boundary

Phase 3 mailbox runner-message signatures use Ed25519 seed keys generated
by the forensic runner keygen command.  These keys are stored as structured
JSON files ending in `.ed25519-key.json`.

These keys are distinct from PRF bundle-signing keys.  Bundle signing and
bundle verification continue to use the existing Clojure/OpenSSH/PKCS#8
path.  Mailbox runner-message signing must NOT accept OpenSSH, PEM, or
PKCS#8 private keys.

The identity registry stores public keys only:

```json
{
  "runner/id": "runner/ci-validation",
  "runner/key-type": "ed25519",
  "runner/public-key-b64": "base64url-encoded-public-key",
  "runner/status": "trusted"
}
```

Private seed material must never be stored in identity registries, mailbox
metadata, consensus certificates, or evidence nodes.

## 9. References

- `RUNNER_IDENTITY_SPEC_V1.md` — identity records and key registry
- `RUNNER_MESSAGE_SPEC_V1.md` — message format
- `RUNNER_CONSENSUS_SPEC_V1.md` — consensus protocol
- `RUNNER_MAILBOX_SPEC_V1.md` — mailbox transport
- Ed25519 (RFC 8032) — signature algorithm
- Base64url (RFC 4648 §5) — key and signature encoding
