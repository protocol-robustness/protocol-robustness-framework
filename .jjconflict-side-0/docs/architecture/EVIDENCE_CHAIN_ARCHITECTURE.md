# Evidence Chain Architecture

## Overview

The evidence chain binds protocol-level evidence records into a self-hashed,
content-addressed artifact registry. Every critical state transition during a
scenario replay produces an evidence record with a cryptographic hash. The
registry aggregates these records, the cursor sequences them, and the final
artifact commits to the full chain.

## Three Levels of Evidence State

### 1. Scenario-Local (with-fresh-registry)

Each scenario replay runs inside a `chain/with-fresh-registry` /
`chain/with-fresh-chain-cursor` binding (`replay.clj:151-152`). This creates a
fresh Clojure atom for the evidence registry and chain cursor, scoped to that
single scenario. **Scenario isolation is preserved** — evidence from one
scenario never contaminates another.

When the scenario completes, `chain/register-scenario-snapshot!`
(`chain.clj:88`) captures a snapshot of the local registry atom
(`:artifacts`, `:evidence-hashes`) and local chain cursor (`:final-seq`,
`:final-self-hash`, `:total-captured`). The snapshot is pushed to a
run-level accumulator atom (`scenario-evidence-atom`).

```
┌─────────────────────────────────────────────┐
│ replay-with-protocol                         │
│                                              │
│  with-fresh-registry {                       │
│    (capture evidence...)                     │
│    (capture evidence...)                     │
│    register-scenario-snapshot!  ──→ atom     │
│  }  ← local atom goes out of scope           │
│                                              │
│  next scenario: fresh atom, same pattern     │
└─────────────────────────────────────────────┘
```

### 2. Run-Level Aggregation

After all scenarios complete, `chain/accumulate-scenario-evidence!`
(`chain.clj:107`) merges every scenario-local snapshot into the top-level
`evidence-registry-atom`. Deduplication uses `intent-hash=` on
`:evidence-hash` — the same content-addressed hash produced during evidence
capture. Artifacts without `:evidence-hash` (index entries) are skipped.

The top-level atom then contains:
- **Index entries**: evidence-summary, evidence-links, chain-cursor
- **Transition-evidence entries**: all scenario evidence records, merged
  and deduplicated

```
scenario-evidence-atom [
  {:registry {:artifacts [...]} :cursor {:final-seq 3 ...}}   ← scenario 1
  {:registry {:artifacts [...]} :cursor {:final-seq 5 ...}}   ← scenario 2
  ...
]

accumulate-scenario-evidence! →
  evidence-registry-atom {
    :artifacts [transition-evidence..., index-entries...]
    :evidence-hashes [h1 h2 h3 ...]
  }
```

After aggregation, `chain/finalize-and-write!` persists the registry to
`evidence-registry.json` with a self-hashed `:registry-hash` that commits
to all entries.

### 3. Persistent Registry (evidence-registry.json)

The persisted registry file contains:
- `:schema-version`, `:contract-version`, `:run-id`, `:generated-at`
- `:evidence-count` — total artifact entries
- `:evidence-hashes` — list of all evidence content hashes
- `:artifacts` — each entry has `:id`, `:kind`, `:evidence-hash`,
  `:context-hash`, `:before-hash`, `:after-hash`, `:action-hash`,
  `:result-hash`, `:artifact-kind`
- `:registry-hash` — canonical hash over all the above

## Chain Cursor

### Scenario-Local Cursor

Each scenario gets a fresh cursor via `with-fresh-chain-cursor`. Every
evidence record receives:
- `:evidence/chain-seq` — monotonically increasing sequence number
- `:evidence/chain-prev-hash` — hash of the previous evidence in sequence
- `:evidence/chain-self-hash` — self-referential content hash

These fields are injected by `chain/inject-chain-fields` (`chain.clj:82`).

### Aggregate Cursor

`chain/build-aggregate-cursor` (`chain.clj:476`) produces a run-level cursor
that commits to all scenario chain heads:

```clojure
{:cursor/scope :aggregate-run
 :cursor/scenario-count 43
 :cursor/scenario-heads [
   {:scenario/seq 3 :scenario/last-hash "..." :scenario/total-captured 3}
   {:scenario/seq 5 :scenario/last-hash "..." :scenario/total-captured 5}
   ...]
 :cursor/total-evidence 623
 :cursor/registry-root-hash "abc123..."
 :cursor/reconciled? true}
```

Written as `aggregate-cursor.json` in the artifact directory.

### Legacy Cursor (chain-cursor-final.json)

The existing `chain-cursor-final.json` is produced by each scenario's
`finalize-and-attest!` call — it reflects only the last scenario's chain.
The aggregate cursor supersedes it for run-level queries.

## Evidence Reconciliation

`chain/reconcile-evidence!` (`chain.clj:392`) validates that the persisted
evidence matches the disk state. It compares:

| Source | Field |
|--------|-------|
| Evidence files on disk (event-evidence/) | File count, max chain-seq |
| evidence-registry.json | Registry entry count |
| chain-cursor-final.json | Cursor final-seq |

If files exist on disk that aren't registered, or the cursor seq is behind
the max disk seq, the reconciliation throws (or logs, depending on
`:throw-on-error`). This catches evidence leaks from scenarios where
`register-scenario-snapshot!` was never called (e.g., early exits).

```
reconcile-evidence! →
  {:reconciled? true/false
   :disk-count 678
   :registry-count 625
   :cursor-seq 0
   :max-disk-seq 10
   :errors ["Cursor behind disk: cursor seq 0 but disk has seq up to 10"]}
```

## Data Flow Summary

```
Scenario 1          Scenario 2          Scenario N
    │                    │                    │
    ▼                    ▼                    ▼
with-fresh-registry   with-fresh-registry   with-fresh-registry
    │                    │                    │
    ▼                    ▼                    ▼
capture evidence      capture evidence      capture evidence
  (write to disk)       (write to disk)       (write to disk)
    │                    │                    │
    ▼                    ▼                    ▼
register-scenario-snapshot! (× N scenarios)
    │
    ▼
scenario-evidence-atom [snapshot1, snapshot2, ...]
    │
    ▼
accumulate-scenario-evidence!
    │
    ▼
evidence-registry-atom (merged: transition + index entries)
    │
    ├──→ finalize-and-write! → evidence-registry.json
    │
    ├──→ build-aggregate-cursor → aggregate-cursor.json
    │
    └──→ reconcile-evidence! → PASS/FAIL
```

## Key Files

| File | Role |
|------|------|
| `src/resolver_sim/evidence/chain.clj` | Registry atom, cursor, aggregation, reconciliation |
| `src/resolver_sim/io/event_evidence.clj` | Evidence capture, disk persistence, normalization |
| `src/resolver_sim/contract_model/replay.clj` | `replay-with-protocol` — scenario replay + snapshot capture |
| `src/resolver_sim/evidence/capture.clj` | Evidence finalization, hash computation |
| `protocols_src/test/.../dispute_resolution_coverage_test.clj` | Coverage test with aggregation, reconciliation, aggregate cursor |

## Evidence Types

Evidence records produced by the Sew protocol (from protocol replay):

| evidence/type | Count (typical) |
|---|---|
| escrow-created | 185 |
| dispute-raised | 168 |
| escrow-released | 125 |
| escrow-refunded | 41 |
| stake-registered | 33 |
| bond-posted | 27 |
| slashing | 19 |
| dispute-escalated | 19 |
| fraud-slash-proposed | 10 |
| evidence-submitted | 9 |
| resolver-unavailability-changed | 8 |
| resolution-challenged | 8 |
| fraud-slash-appealed | 6 |
| incentive-payout | 5 |
| escrow-withdrawn | 4 |
| reversed | 3 |
| fraud-slash-rejected | 2 |
| prorata-allocation | 1 |
| guard-rejected | 1 |
| rejected | 1 |
| stake-withdrawn | 1 |
