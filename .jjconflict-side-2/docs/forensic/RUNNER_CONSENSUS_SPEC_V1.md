# RUNNER_CONSENSUS_SPEC_V1

Status: Draft V1

## 1. Purpose

Define the consensus protocol for the forensic evidence runner: how multiple
runners coordinate, submit results, reach agreement, and produce verifiable
consensus artifacts. Phase 1 covers local repeated-execution consensus
(transport-agnostic, file-based messages). Future phases add remote runners.

## 2. Design Principles

### 2.1 Comparison Surface

Consensus is computed over deterministic fields only. The comparison surface
is the `runner-message/summary` map from RUNNER_MESSAGE_SPEC_V1 — stable
hashes (bundle/hash, overview/hash), execution totals, source provenance,
and registry snapshots. Volatile fields (timestamps, node hashes) are
explicitly excluded.

### 2.2 Threshold-Based Agreement

Consensus is reached when at least T out of N runners agree on all
comparison fields. T defaults to `max(2, N - 1)` — at least 2, and no
more than one dissenter allowed.

### 2.3 Artifact-Based Output

Consensus produces two artifacts:
1. **Consensus certificate** — signed or self-certifying record of agreement
2. **Disagreement report** — field-level divergence details when consensus
   is not reached

Both artifacts are JSON files with self-referential hashes, suitable for
inclusion in a forensic evidence bundle.

### 2.4 Transport Agnostic

Phase 1 uses a shared filesystem staging directory. The coordinator and
runners communicate via JSON files. No network protocols, no shared
memory, no IPC. This makes the protocol testable and verifiable by
inspection.

### 2.5 No Leader Election

In Phase 1, the coordinator is externally provided (a script or human
operator). Runners do not elect a leader. Future phases may add leader
election for decentralized coordination.

## 3. Protocol Lifecycle

```
Coordinator                          Runner(s)
    |                                    |
    |--- [announcement request] -------->| (coordinator creates staging dir)
    |                                    |
    |<--- [RunnerAnnouncement] ----------| (runner announces readiness)
    |                                    |
    |--- [execution instruction] ------->| (coordinator signals start)
    |                                    |
    |   (runner executes scenarios)      |
    |                                    |
    |<--- [Heartbeat] (optional) --------| (periodic progress updates)
    |                                    |
    |<--- [ResultSubmission] ------------| (runner submits result summary)
    |                                    |
    |   (coordinator compares all        |
    |    submissions against threshold)  |
    |                                    |
    |--- [ConsensusResult] ------------->| (coordinator sends verdict)
    |                                    |
    |   (coordinator writes certificate  |
    |    and disagreement report)        |
```

Phase 1 may skip the announcement and instruction steps by having the
coordinator directly invoke runners as subprocesses.

## 4. Rounds

A consensus round is one complete cycle:
1. **Init** — Coordinator creates a staging directory with unique round ID
2. **Collect** — Coordinator gathers result submissions from N runners
3. **Compare** — Coordinator compares fields across submissions
4. **Verdict** — Coordinator determines agreement level
5. **Finalize** — Coordinator writes certificate and disagreement artifacts

Each round has a unique `round-id` (UUID or timestamp-based).

```
consensus-round-001/
  coordinator/
    consensus-certificate.json
    disagreement-report.json
    round-manifest.json
  runners/
    runner-a/
      announcement.json
      result-submission.json
      heartbeat-1.json
    runner-b/
      announcement.json
      result-submission.json
```

### 4.1 Round Manifest

```json
{
  "consensus/schema-version": "consensus-round.v1",
  "consensus/round-id": "round-2026-06-28T12-00-00Z",
  "consensus/status": "confirmed",
  "consensus/type": "local-repeated-execution",
  "consensus/coordinator-id": "coordinator/local-filesystem",
  "consensus/started-at": "2026-06-28T12:00:00Z",
  "consensus/completed-at": "2026-06-28T12:06:00Z",
  "consensus/runner-count": 3,
  "consensus/threshold": 2,
  "consensus/participants": [
    {"runner-id": "runner/local-clojure", "status": "pass"},
    {"runner-id": "runner/local-bb", "status": "pass"},
    {"runner-id": "runner/local-clojure-b", "status": "pass"}
  ],
  "consensus/submission-count": 3,
  "consensus/fields-compared": 14,
  "consensus/fields-agreed": 14,
  "consensus/volatile-fields-excluded": [
    "execution/node-hash",
    "execution/content-hash",
    "execution/record-hash"
  ]
}
```

## 5. Comparison Algorithm

### 5.1 Input

N result submissions, each containing a `runner-message/summary` map of
stable fields.

### 5.2 Per-Field Agreement

For each field `f` in the comparison surface:

1. Collect values `v_i` from each runner `i` where `v_i` is non-null
2. Count occurrences via majority: `count(v == winning-value)`
3. If `count >= threshold` → field is **confirmed**
4. If no value reaches threshold → field has **insufficient agreement**

Fields where all runners have null values are skipped.

### 5.3 Overall Verdict

| Condition | Verdict |
|---|---|
| All fields confirmed, all runners succeeded | `"confirmed"` |
| All fields confirmed, some runners failed | `"confirmed-with-failures"` |
| One or more fields have insufficient agreement | `"diverged"` |
| No submissions received | `"inconclusive"` |
| Coordinator error | `"failed"` |

### 5.4 Comparison Surface (Phase 1)

The default comparison surface matches the existing quorum fields:

```
bundle/hash
overview/hash
execution/summary/status
execution/summary/totals/total
execution/summary/totals/passed
execution/summary/totals/failed
execution/summary/totals/expected-failed
execution/summary/totals/unexpected-failed
source/tree-hash
source/tree-hash-algorithm
```

Plus all `registry/snapshot/*` keys dynamically discovered from any
submission.

## 6. Consensus Certificate Artifact

Written when the verdict is `"confirmed"` or `"confirmed-with-failures"`.

```json
{
  "consensus-certificate/schema-version": "consensus-certificate.v1",
  "consensus-certificate/round-id": "round-2026-06-28T12-00-00Z",
  "consensus-certificate/status": "confirmed",
  "consensus-certificate/agreed-hash": "a1b2c3d4e5f6...",
  "consensus-certificate/agreed-hash-type": "overview/hash",
  "consensus-certificate/participants": 3,
  "consensus-certificate/threshold": 2,
  "consensus-certificate/agreement-count": 3,
  "consensus-certificate/timestamp": "2026-06-28T12:06:00Z",
  "consensus-certificate/participants": [
    {"runner-id": "runner/a", "bundle/hash": "a1b2...", "agree": true},
    {"runner-id": "runner/b", "bundle/hash": "a1b2...", "agree": true},
    {"runner-id": "runner/c", "bundle/hash": "a1b2...", "agree": true}
  ],
  "consensus-certificate/hash": "self-referential-sha256",
  "consensus-certificate/signature": null
}
```

The agreed hash type indicates which hash was used for the primary agreement
test. For Phase 1, this is `"overview/hash"` (the normalized deterministic
hash designed for cross-runner comparison).

## 7. Disagreement Artifact

Written when the verdict is `"diverged"`.

```json
{
  "disagreement/schema-version": "disagreement-report.v1",
  "disagreement/round-id": "round-2026-06-28T12-00-00Z",
  "disagreement/status": "diverged",
  "disagreement/timestamp": "2026-06-28T12:06:00Z",
  "disagreement/summary": {
    "fields-compared": 14,
    "fields-agreed": 12,
    "fields-diverged": 2
  },
  "disagreement/fields": [
    {
      "field": "bundle/hash",
      "status": "diverged",
      "values": {
        "runner/a": "a1b2c3d4e5f6...",
        "runner/b": "deadbeef1234..."
      },
      "agreement-count": 1,
      "total-runs": 2,
      "threshold": 2,
      "reason": "Insufficient agreement: 1/2 runners agree"
    }
  ],
  "disagreement/runners": [
    {"runner-id": "runner/a", "status": "pass", "divergent-fields": []},
    {"runner-id": "runner/b", "status": "fail", "divergent-fields": ["bundle/hash"]}
  ],
  "disagreement/hash": "self-referential-sha256"
}
```

## 8. Evidence Node Integration

The consensus certificate and disagreement report are eligible for inclusion
as evidence nodes in the forensic DAG. When integrated:

1. The coordinator computes a SHA-256 of the certificate/report
2. An evidence node is created with:
   - `:node-hash` = SHA-256 of the certificate/report content
   - `:execution-id` = `:execution/consensus`
   - `:execution-kind` = `:consensus-verification`
   - `:runner` = coordinator ID
   - `:result/status` = `:pass` if confirmed, `:fail` if diverged
   - `:parent-hashes` = hashes of participant run bundle roots

A dedicated `consensus/` subdirectory in the bundle holds the certificate
and report, analogous to `claims/` and `attestations/`.

```
~/prf-runs/<run-id>/
    ├── evidence-dag/
    ├── claims/
    ├── attestations/
    ├── consensus/
    │   ├── consensus-certificate.json
    │   └── disagreement-report.json   (only if diverged)
    ├── anchors/
    ├── run-overview.json
    └── run-bundle-root.json
```

## 9. Runner Identity Registry

Phase 1 uses a static identity registry:

| Runner ID | Description | Source |
|---|---|---|
| `runner/local-clojure` | Clojure CLI with `:with-sew` alias | `criteria.clj` |
| `runner/local-bb` | Babashka runner | `criteria.clj` |
| `runner/local-forensic` | Forensic Python runner | `run.py` (PRF_ORCHESTRATION_RUNNER_ID) |
| `coordinator/local-filesystem` | Phase 1 filesystem coordinator | This spec |

## 10. Relation to Existing Tools

| Tool | Relationship |
|---|---|
| `quorum.py` | Precursor — Phase 1 replaces with formal coordinator |
| `self_test.py` | Special case (2-run quorum) — subsumed by N-run coordinator |
| `reproduce.py` | Reproduce from bundle — orthogonal to consensus |
| Attestation quorum | Separate system (attestor agreement on claims) — NOT replaced |

## 11. References

- `RUNNER_MESSAGE_SPEC_V1.md` — message format
- `scripts/forensic/quorum.py` — existing local quorum implementation
- `scripts/forensic/self_test.py` — existing two-run determinism check
- `docs/specs/RUN_REQUEST_SPEC_V1.md` — runner selection modes
- `src/resolver_sim/run/criteria.clj` — `:quorum` selection mode (reserved)
- `src/resolver_sim/run/overview.clj` — consensus eligibility marker
