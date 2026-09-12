# RUNNER_IDENTITY_SPEC_V1

Status: Draft V1

## 1. Purpose

Define runner identity records, public key registry, and the trust taxonomy
for the forensic evidence runner system.  This spec answers:

- Who produced this result?
- Can I verify the result after copying the mailbox to another machine?
- Can I distinguish unknown, inactive, revoked, and trusted runners?

## 2. Design Principles

### 2.1 Identity Is a Public Key

A runner's identity is its Ed25519 public key.  Human-readable names and
runner-id strings are aliases bound to keys via the registry.  The key is
the ground truth.

### 2.2 Portable Verification

A signed submission can be verified anywhere — the mailbox path, the
verifier's machine, the archive — because the signature is over the
message content, not the transport envelope.  Verification needs only the
signer's public key.

### 2.3 Trust Is a Registry Decision

Trust is not a property of the key.  Trust is a registry entry binding a
key to a scope, with an expiration or revocation window.  The same key may
be trusted for one suite and untrusted for another.

### 2.4 No Central Authority

Phase 3 uses a local registry file (edn or json).  Future phases may
add distributed or on-chain registries.

## 3. Runner Identity Record

Each known runner has an identity record stored in the registry:

```json
{
  "identity/schema-version": "runner-identity.v1",
  "identity/runner-id": "runner/ci-validation",
  "identity/public-key": "base64url-encoded-ed25519-public-key",
  "identity/status": "trusted",
  "identity/registered-at": "2026-06-28T12:00:00Z",
  "identity/updated-at": "2026-06-28T12:00:00Z",
  "identity/expires-at": null,
  "identity/scopes": ["prf-sew", "prf-yield"],
  "identity/note": "CI validation runner — automated",
  "identity/revocation-reason": null
}
```

### 3.1 Runner Status Taxonomy

| Status | Meaning | Can sign? | Can participate in quorum? |
|---|---|---|---|
| `trusted` | Known key, active, authorized | Yes | Yes |
| `unknown` | No registry entry | No (signature rejected) | No |
| `inactive` | Known but temporarily disabled | No | No |
| `revoked` | Key compromised or decommissioned | No | No (evidence flagged) |

### 3.2 Identity Record Fields

| Field | Required | Type | Description |
|---|---|---|---|
| `identity/schema-version` | YES | string | `"runner-identity.v1"` |
| `identity/runner-id` | YES | string | Human-readable runner identifier |
| `identity/public-key` | YES | string | Base64url-encoded Ed25519 public key |
| `identity/status` | YES | enum | `trusted`, `unknown`, `inactive`, `revoked` |
| `identity/registered-at` | YES | ISO-8601 | When this identity was registered |
| `identity/updated-at` | No | ISO-8601 | Last status change |
| `identity/expires-at` | No | ISO-8601 | Optional expiration (null = no expiry) |
| `identity/scopes` | No | string array | Authorized scenario suites |
| `identity/note` | No | string | Free-form description |
| `identity/revocation-reason` | No | string | Required if status is `revoked` |

## 4. Public Key Registry

The registry is a JSON file (`identity-registry.json`) stored alongside the
mailbox or in a well-known path:

```
forensic-mailbox/
├── mailbox.json
├── identity-registry.json        <-- runner identity records
├── runners/
│   └── <runner-id>/
│       ├── runner.json
│       └── heartbeats/
├── runs/
│   └── ...
└── objects/
    └── ...
```

### 4.1 Registry Schema

```json
{
  "registry/schema-version": "identity-registry.v1",
  "registry/updated-at": "2026-06-28T12:00:00Z",
  "registry/identities": [
    { "identity/runner-id": "runner/ci-validation", ... },
    { "identity/runner-id": "runner/researcher-a", ... }
  ],
  "registry/trusted-keys": [
    "base64url-encoded-key-1",
    "base64url-encoded-key-2"
  ]
}
```

### 4.2 Lookup Rules

1. Given a runner-id, find the identity record → get public key and status
2. Given a public key (from a signature), find the identity record(s) → get runner-id and status
3. If no record found → status is `unknown`
4. If record found with `revoked` status → verification fails, evidence flagged
5. If record found with `inactive` status → verification fails with reason

## 5. Runner Lifecycle

```
        +-----------+
        | unknown   |  (no registry entry — can announce, cannot sign)
        +-----+-----+
              |
        registration (key + runner-id added to registry)
              |
        +-----v-----+
        | trusted    |  (active, can sign, participate in quorum)
        +-----+-----+
              |
      +-------+-------+
      |               |
  deactivation    compromise/rotation
      |               |
  +---v----+     +---v----+
  | inactive|    | revoked |
  +---------+    +---------+
```

## 6. Identity in Runner Announcements

When a runner announces itself (per RUNNER_MESSAGE_SPEC_V1), it SHOULD
include its public key:

```json
{
  "runner-message/schema-version": "runner-message.v1",
  "runner-message/type": "announcement",
  "runner-message/runner-id": "runner/ci-validation",
  "runner-message/public-key": "base64url...",
  "runner-message/capabilities": ["sew-invariants"],
  "runner-message/note": "CI validation runner"
}
```

The announcement is NOT a proof of identity — anyone can claim any
runner-id.  The registry is the source of truth.  The announcement's
public-key field is a hint for registry lookup.

## 7. Identity in Consensus Artifacts

The consensus certificate (RUNNER_CONSENSUS_SPEC_V1) identifies
participants by runner-id.  With Phase 3, each participant entry
SHOULD also include the runner's public key hash:

```json
{
  "consensus-certificate/participant-list": [
    {
      "runner-id": "runner/ci-validation",
      "public-key-hash": "sha256:base64url...",
      "bundle/hash": "a1b2...",
      "overview/hash": "c3d4...",
      "agree": true
    }
  ]
}
```

This binds the participant entry to a verifiable identity without
embedding the full public key (which may be large).

## 8. References

- `RUNNER_SIGNATURE_SPEC_V1.md` — message signing and verification
- `RUNNER_MESSAGE_SPEC_V1.md` — message format
- `RUNNER_CONSENSUS_SPEC_V1.md` — consensus protocol
- `RUNNER_MAILBOX_SPEC_V1.md` — mailbox transport
- Ed25519 (RFC 8032) — signature algorithm
