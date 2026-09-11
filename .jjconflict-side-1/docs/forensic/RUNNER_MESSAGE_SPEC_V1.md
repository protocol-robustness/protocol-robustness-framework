# RUNNER_MESSAGE_SPEC_V1

Status: Draft V1

## 1. Purpose

Define the message format that forensic runners use to announce themselves,
submit results, and report status to a coordinator. Phase 1 is transport-
agnostic — messages are JSON files on a shared filesystem. Future phases
may add libp2p, gRPC, or Unix socket transports.

## 2. Design Principles

### 2.1 Transport-Agnostic

Messages are self-describing JSON objects. No transport-level metadata
(IP address, socket FD, connection ID) is embedded in the message body.
The coordinator determines the transport.

### 2.2 Stateless Per Message

Every message is self-contained. The coordinator does not maintain per-
runner session state beyond the current consensus round.

### 2.3 Runner Identity

Each runner has a stable identity string (`runner-id`) that SHOULD be
unique within a consensus round. Runners without persistent identity
use a coordinator-assigned ephemeral ID.

### 2.4 Content-Addressed Results

Each result submission includes the submitting runner's `bundle/hash` and
`overview/hash` (self-referential content hashes). The coordinator uses
these for comparison, not the runner's claimed identity.

## 3. Message Types

### 3.1 RunnerAnnouncement

Sent by a runner to the coordinator to indicate readiness.

```json
{
  "runner-message/schema-version": "runner-message.v1",
  "runner-message/type": "announcement",
  "runner-message/timestamp": "2026-06-28T12:00:00Z",
  "runner-message/runner-id": "runner/local-clojure",
  "runner-message/runner-version": "0.1.0",
  "runner-message/capabilities": ["sew-invariants", "yield-scenarios"],
  "runner-message/note": "Optional human-readable description"
}
```

**Fields:**

| Field | Required | Description |
|---|---|---|
| `runner-message/schema-version` | YES | `"runner-message.v1"` |
| `runner-message/type` | YES | `"announcement"` |
| `runner-message/timestamp` | YES | ISO-8601 UTC |
| `runner-message/runner-id` | YES | Stable or ephemeral runner identifier |
| `runner-message/runner-version` | No | Runner software version |
| `runner-message/capabilities` | No | Array of capability strings |
| `runner-message/note` | No | Free-form note |

### 3.2 ResultSubmission

Sent by a runner after completing its execution. Contains the runner's
result summary for consensus comparison.

```json
{
  "runner-message/schema-version": "runner-message.v1",
  "runner-message/type": "result-submission",
  "runner-message/timestamp": "2026-06-28T12:05:00Z",
  "runner-message/runner-id": "runner/local-clojure",
  "runner-message/run-id": "2026-06-28T12-00-00Z-consensus-a",
  "runner-message/status": "pass",
  "runner-message/exit-code": 0,
  "runner-message/elapsed-ms": 45200,
  "runner-message/summary": {
    "bundle/hash": "a1b2c3d4e5f6...",
    "bundle/schema-version": "bundle-root.v1",
    "overview/hash": "f6e5d4c3b2a1...",
    "execution/summary/status": "pass",
    "execution/summary/totals/total": 125,
    "execution/summary/totals/passed": 125,
    "execution/summary/totals/failed": 0,
    "execution/summary/totals/expected-failed": 0,
    "execution/summary/totals/unexpected-failed": 0,
    "source/tree-hash": "deadbeef...",
    "source/tree-hash-algorithm": "source-tree-hash.v1.path-content-sha256",
    "source/commit": "abc123def456",
    "source/dirty?": "false",
    "registry/snapshot/hash": "1234..."
  },
  "runner-message/bundle-path": "<PRF_RUNS_DIR>/2026-06-28T12-00-00Z-consensus-a",
  "runner-message/evidence": {
    "dag-inventory-hash": "abcd...",
    "claim-count": 3,
    "attestation-count": 3
  },
  "runner-message/failures": [
    {"scenario-id": "s81-appeal-deadline-boundary-before",
     "outcome": "xfail",
     "expected": true}
  ],
  "runner-message/volatile": {
    "execution/node-hash": "xyz789...",
    "execution/content-hash": "def012...",
    "execution/record-hash": "ghi345..."
  },
  "runner-message/note": "Optional additional context"
}
```

**Fields:**

| Field | Required | Description |
|---|---|---|
| `runner-message/schema-version` | YES | `"runner-message.v1"` |
| `runner-message/type` | YES | `"result-submission"` |
| `runner-message/timestamp` | YES | ISO-8601 UTC |
| `runner-message/runner-id` | YES | Runner identifier |
| `runner-message/run-id` | YES | The run's bundle directory name |
| `runner-message/status` | YES | `"pass"` or `"fail"` |
| `runner-message/exit-code` | YES | Exit code from the execution |
| `runner-message/elapsed-ms` | No | Wall-clock execution time |
| `runner-message/summary` | YES | Map of stable comparison fields (see 3.2.1) |
| `runner-message/bundle-path` | No | Path to the full run bundle directory |
| `runner-message/evidence` | No | Evidence summary (DAG hash, claim count, attestation count) |
| `runner-message/failures` | No | Array of failure details |
| `runner-message/volatile` | No | Volatile fields (timestamps, execution hashes) — informational only |
| `runner-message/note` | No | Free-form note |

#### 3.2.1 Summary Field Rules

The `runner-message/summary` map MUST contain only deterministic,
reproducible fields that are expected to be identical across runners
executing the same suite on the same source tree.

| Field | Stability | Description |
|---|---|---|
| `bundle/hash` | Stable (same source + config → same hash) | Self-referential bundle root hash |
| `bundle/schema-version` | Constant | Always `"bundle-root.v1"` |
| `overview/hash` | Stable (same results → same hash) | Normalized overview hash |
| `execution/summary/status` | Stable | Pass/fail status |
| `execution/summary/totals/*` | Stable | Pass/fail/expected/unexpected counts |
| `source/tree-hash` | Stable (same source → same hash) | Deterministic source hash |
| `source/tree-hash-algorithm` | Constant | Hash algorithm name |
| `source/commit` | Stable | Git commit SHA |
| `source/dirty?` | Stable | Git dirty flag |
| `registry/snapshot/*` | Stable (staged before run) | Registry hash snapshots |

### 3.3 Heartbeat

Sent periodically by a runner during long executions.

```json
{
  "runner-message/schema-version": "runner-message.v1",
  "runner-message/type": "heartbeat",
  "runner-message/timestamp": "2026-06-28T12:02:00Z",
  "runner-message/runner-id": "runner/local-clojure",
  "runner-message/run-id": "2026-06-28T12-00-00Z-consensus-a",
  "runner-message/progress": "scenario-42-of-125",
  "runner-message/note": null
}
```

### 3.4 ErrorReport

Sent by a runner when it encounters a fatal error.

```json
{
  "runner-message/schema-version": "runner-message.v1",
  "runner-message/type": "error-report",
  "runner-message/timestamp": "2026-06-28T12:03:00Z",
  "runner-message/runner-id": "runner/local-clojure",
  "runner-message/run-id": "2026-06-28T12-00-00Z-consensus-b",
  "runner-message/error-kind": "pipeline-crash",
  "runner-message/error-message": "Clojure process exited with signal 9 (SIGKILL)",
  "runner-message/error-detail": null,
  "runner-message/exit-code": -9
}
```

### 3.5 ConsensusResult

Sent by the coordinator to each runner after consensus is computed.

```json
{
  "runner-message/schema-version": "runner-message.v1",
  "runner-message/type": "consensus-result",
  "runner-message/timestamp": "2026-06-28T12:06:00Z",
  "runner-message/runner-id": "coordinator",
  "runner-message/consensus/status": "confirmed",
  "runner-message/consensus/agreed-hash": "a1b2c3d4e5f6...",
  "runner-message/consensus/participants": 3,
  "runner-message/consensus/threshold": 2,
  "runner-message/consensus/agreement-count": 3,
  "runner-message/consensus/certificate-path": "consensus-certificate.json",
  "runner-message/consensus/disagreements": []
}
```

## 4. Serialization

Messages are serialized as JSON with the following rules:
- Keys use `/` as namespace separator (e.g., `runner-message/type`)
- Timestamps are ISO-8601 UTC strings with seconds precision
- All fields are optional unless marked Required
- Unknown fields MUST be ignored (forward compatibility)
- The message may be wrapped in a transport envelope (see section 5)

## 5. Transport Envelope (Phase 1: Filesystem)

Phase 1 uses a shared staging directory. Each message is written as a JSON
file named `<runner-id>-<type>-<sequence>.json`. The coordinator polls the
directory for new files.

```json
{
  "runner-message/schema-version": "runner-message.v1",
  "runner-message/type": "envelope",
  "runner-message/envelope/transport": "filesystem",
  "runner-message/envelope/staging-dir": "/tmp/consensus-round-001",
  "runner-message/envelope/message": { ... actual message ... }
}
```

For Phase 1, the envelope is optional. Messages may be written directly
to the staging directory without an envelope wrapper.

## 6. References

- `scripts/forensic/quorum.py` — existing local repeated-execution quorum
- `scripts/forensic/self_test.py` — existing two-run determinism check
- `RUNNER_CONSENSUS_SPEC_V1.md` — consensus protocol and lifecycle
- `docs/specs/RUN_REQUEST_SPEC_V1.md` — runner selection modes
- `src/resolver_sim/run/criteria.clj` — runner identity and capability matching
