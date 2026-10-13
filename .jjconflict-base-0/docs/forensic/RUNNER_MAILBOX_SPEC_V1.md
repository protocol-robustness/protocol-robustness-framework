# RUNNER_MAILBOX_SPEC_V1

Status: Draft V1

## 1. Purpose

Define the shared artifact mailbox for runner consensus. The mailbox is a
filesystem-backed, content-addressed message store that lets runners and
coordinators communicate through durable files rather than direct process
invocation or networking.

Phase 2 builds on Phase 1 (local consensus) by adding a transport layer
that can later map to HTTP, Nostr, IPFS, or libp2p without changing the
message or consensus semantics.

## 2. Non-Goals

- Networking, IPC, or remote process orchestration
- Encryption or access control (Phase 2 is plain filesystem)
- Cryptographic message signing (placeholder fields reserved)
- Runner identity governance or discovery
- Replacing the Phase 1 message or consensus specs

## 3. Mailbox Directory Layout

```
forensic-mailbox/
├── mailbox.json                    # mailbox metadata (version, created-at)
├── runners/
│   └── <runner-id>/
│       ├── runner.json             # runner announcement
│       └── heartbeats/
│           └── <seq>-<hash>.json   # periodic heartbeats
├── runs/
│   └── <run-request-hash>/
│       ├── request.json            # run request record
│       ├── commitments/
│       │   └── <runner-id>-<hash>.json   # runner commitment to participate
│       ├── submissions/
│       │   └── <runner-id>-<result-hash>.json  # result-submission messages
│       ├── errors/
│       │   └── <runner-id>-<hash>.json       # error-report messages
│       ├── consensus/
│       │   ├── consensus-certificate.json    # from RUNNER_CONSENSUS_SPEC_V1
│       │   ├── disagreement-report.json      # from RUNNER_CONSENSUS_SPEC_V1
│       │   └── evidence-node.json            # DAG evidence node
│       └── artifacts/
│           └── <hash>.json          # content-addressed bundle manifests
└── objects/
    └── sha256/
        └── <hash-prefix>/
            └── <full-hash>.json     # content-addressed objects
```

### 3.1 `mailbox.json`

```json
{
  "mailbox/schema-version": "forensic-mailbox.v1",
  "mailbox/created-at": "2026-06-28T12:00:00Z",
  "mailbox/spec-version": "RUNNER_MAILBOX_SPEC_V1",
  "mailbox/transport": "filesystem",
  "mailbox/runner-count": 0,
  "mailbox/run-count": 0
}
```

### 3.2 Runner Identity (`runners/<id>/runner.json`)

Stored as a `RunnerAnnouncement` message per RUNNER_MESSAGE_SPEC_V1.

### 3.3 Run Request (`runs/<hash>/request.json`)

The run request that launched the consensus round. Contains `run-request/hash`
(self-referential), suite key, runner selection parameters.

### 3.4 Submissions (`runs/<hash>/submissions/<runner-id>-<hash>.json`)

Result-submission messages per RUNNER_MESSAGE_SPEC_V1. Each includes
`runner-message/summary` (stable comparison fields) and `runner-message/artifacts`
(bundle root references).

### 3.5 Consensus Outputs (`runs/<hash>/consensus/`)

| File | Schema | Source |
|---|---|---|
| `consensus-certificate.json` | `consensus-certificate.v1` | `build_certificate` from consensus.py |
| `disagreement-report.json` | `disagreement-report.v1` | `build_disagreement` from consensus.py |
| `evidence-node.json` | Evidence DAG node | `write_evidence_node` from consensus.py |

### 3.6 Content-Addressed Objects (`objects/sha256/<prefix>/<hash>.json`)

Stores arbitrary JSON objects by their SHA-256 hash. The hash is computed
from the canonical JSON encoding of the object (excluding a `object/hash`
field if present, to allow self-referential addressing).

## 4. Object Hashing Rules

All mailbox objects stored in `objects/` or `artifacts/` use deterministic
canonical JSON encoding:

```python
canonical = json.dumps(data, indent=2, default=str, sort_keys=True)
object_hash = sha256(canonical.encode("utf-8")).hexdigest()
```

If the object contains a self-referential hash field (e.g., `object/hash`
or `consensus-certificate/hash`), it MUST be excluded from the canonical
bytes used to compute the hash. The hash field is then set to the computed
value before the object is written to storage.

## 5. Message Storage Rules

Messages are stored as JSON files. Each message file includes:

| Field | Required | Description |
|---|---|---|
| `runner-message/schema-version` | YES | `"runner-message.v1"` |
| `runner-message/type` | YES | Message type from RUNNER_MESSAGE_SPEC_V1 |
| `runner-message/timestamp` | YES | ISO-8601 UTC |
| `runner-message/hash` | YES | Content hash of the message (self-referential) |
| `runner-message/runner-id` | YES | Runner identifier |
| `runner-message/run-request-hash` | For submissions | Links to the run request |
| `runner-message/signature` | No | Placeholder for future signing |

## 6. Run Lifecycle Through Mailbox

```
Coordinator                         Mailbox                        Runner
    |                                 |                              |
    |-- init_mailbox() -------------->|                              |
    |-- write_run_request() --------->| (runs/<hash>/request.json)  |
    |                                 |                              |
    |                              (runner polls or is notified)     |
    |                                 |<---- write_commitment() -----|
    |                                 |<---- write_heartbeat() ------|
    |                                 |                              |
    |                                 |<---- write_result_submission |
    |                                 |       (submissions/*.json)  |
    |                                 |                              |
    |-- load_result_submissions() --->|                              |
    |-- compute_agreement()           |                              |
    |-- write_consensus_outputs() --->| (consensus/*.json)          |
```

Phase 2 is pull-based: the coordinator reads submissions from the mailbox.
Future phases may add push notifications.

## 7. Result-Submission Flow

1. Runner completes execution
2. Runner computes `runner-message/hash` from canonical message body
3. Runner writes to `submissions/<runner-id>-<hash-prefix>.json`
4. Runner stores bundle root or manifest in `artifacts/<manifest-hash>.json`
5. Coordinator calls `load_result_submissions()` to collect all submissions
6. Each submission is converted to the normalized format from Phase 1
7. Consensus algorithm runs unchanged

## 8. Consensus Writeback Flow

1. Coordinator computes agreement, verdict, certificate, disagreement
2. Coordinator writes to `consensus/consensus-certificate.json`
3. If diverged, writes `consensus/disagreement-report.json`
4. Writes `consensus/evidence-node.json` with DAG node
5. Also stores objects in `objects/sha256/...` for content addressing

## 9. Artifact References

Result submissions reference bundle artifacts via a `runner-message/artifacts`
map:

```json
{
  "runner-message/artifacts": {
    "bundle-root-hash": "sha256:abc...",
    "bundle-root-path": "relative/path/or/opaque-id",
    "manifest-hash": "sha256:def..."
  }
}
```

Absolute paths MUST NOT appear in hashed message fields. They may appear
only in unhashed diagnostic metadata.

## 10. Privacy Considerations

- Mailbox directories may contain runner IDs and run request details
- No encryption in Phase 2 — filesystem permissions are the access control
- Future phases should encrypt artifact content while keeping message
  routing fields transparent
- Runner IDs should be opaque where possible in sensitive workflows

## 11. Future Transport Mapping

### HTTP

Mailbox layout maps to RESTful API paths:
- `GET /runs/<hash>/submissions` — list submissions
- `POST /runs/<hash>/submissions` — publish submission
- `GET /objects/sha256/<hash>` — retrieve object
- `POST /objects/sha256/<hash>` — store object

### Nostr

Mailbox messages map to signed Nostr events:
- `runner-message/type` → `kind`
- `runner-message/runner-id` → `pubkey`
- Content → event tags + content field
- `objects/sha256/` → IPFS CID or blob pointer

### IPFS

`objects/sha256/` content-addressable store maps directly to IPFS:
- SHA-256 hash → CID (raw or DAG-JSON)
- `artifacts/` → IPLD blocks
- No directory structure needed — content is self-addressing

### libp2p

Mailbox operations map to request-response or pubsub protocols:
- `load_result_submissions` → request for a run's submission list
- `write_result_submission` → publish to run-specific topic
- Object store → bitswap or DHT

## 12. References

- `RUNNER_MESSAGE_SPEC_V1.md` — message types and fields
- `RUNNER_CONSENSUS_SPEC_V1.md` — consensus protocol and artifacts
- `scripts/forensic/mailbox.py` — reference implementation
- `scripts/forensic/consensus.py` — consensus coordinator
