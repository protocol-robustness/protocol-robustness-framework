# Evidence Registry

## Status: Proposed

## 1. Current State

The framework already produces:

- **Generic transition traces** — `emit-evidence!` in `dispatcher.clj` captures every event with before/after world hashes, attribution context, action/result maps
- **Targeted protocol evidence** — `capture-event-evidence!` at 25+ call sites with mechanism-specific fields, chain-seq, group-id, world hashes
- **Artifact registry** — `chain.clj` builds `test-artifacts.json` indexing all run artifacts with SHA-256 hashes
- **Validation roots** — `artifact-registry-validation.json` with checks for artifact presence, schema version
- **Links index** — `build-evidence-links-index` groups artifacts by `:evidence/group-id`
- **Mechanism index** — `build-mechanism-index` groups by evidence type -> mechanism mapping
- **Coverage report** — `build-evidence-coverage-report` summarizes generic vs targeted counts
- **Research helpers** — `find-evidence`, `linked-evidence-group`, `verify-chain-integrity`, `diff-evidence-directories`

**What's missing:** A single researcher-accessible registry tying generic traces, targeted evidence, validation checks, and artifact metadata together. Currently a researcher must call multiple functions or read multiple files to understand a run's evidence.

## 2. Recommended Design

A read-only, post-run evidence registry built by scanning existing outputs. Not a new capture path — a new aggregation layer.

```
results/<run-id>/
├── test-artifacts.json                     (existing)
├── artifact-registry-validation.json        (existing)
├── evidence-registry.json                  ** NEW **
├── evidence-registry-validation.json       ** NEW **
└── event-evidence/                         (existing)
```

**Key principle:** The registry is **derived** from existing files. It never modifies them. It is safe to delete and rebuild at any time.

### What it is responsible for

- Indexing evidence artifacts by event, group-id, subject, type, and layer
- Providing cross-links between generic traces and targeted evidence
- Validating evidence completeness and attribution quality
- Giving researchers a single file to start their analysis

### What it is NOT responsible for

- Capturing new evidence
- Modifying existing artifacts
- Executing protocol logic
- Replacing `test-artifacts.json`
- Being a general-purpose database

## 3. Registry Schema

```clojure
{:schema/version "evidence-registry.v1"
 :run/id "S19-run-20260618-abc123"
 :scenario/id "S19"
 :generated/at "2026-06-18T12:00:00Z"
 :generated/by "evidence-registry-builder.v1"

 :entries [<EvidenceEntry> ...]

 :indexes {:by-event-index   {0 ["ev-0..." "ev-1..."], ...}
           :by-group-id      {"S19-run:0:create_escrow" ["ev-0"...], ...}
           :by-subject       {[:escrow 0] ["ev-0"...], ...}
           :by-evidence-type {:escrow-created ["ev-0"...], ...}
           :by-layer         {:generic-trace [...], :targeted-protocol [...]}}

 :source {:scenario-file-id "scenarios/S19"
          :golden-hash "abc..."
          :suite-id :suites/baseline}}
```

### EvidenceEntry

| Field | Classification | Source |
|-------|---------------|--------|
| `:evidence/id` | required | content hash prefix |
| `:evidence/type` | required | artifact `:evidence/type` |
| `:evidence/layer` | required | artifact `:evidence/layer` |
| `:hash/content` | required | artifact `:evidence/hash` |
| `:file/path` | required | relative path from run dir |
| `:evidence/role` | recommended | :core/:diagnostic/:trace |
| `:evidence/chain-seq` | recommended | artifact chain-seq |
| `:evidence/group-id` | recommended | artifact group-id |
| `:scenario/id` | recommended | from attribution |
| `:run/id` | recommended | from attribution |
| `:event/index` | recommended | from attribution |
| `:event/type` | recommended | from attribution |
| `:subject/type` | recommended | from attribution |
| `:subject/id` | recommended | from attribution |
| `:action/type` | recommended | from attribution |
| `:evidence/reason` | recommended | from attribution |
| `:hash/world-before` | diagnostic | artifact |
| `:hash/world-after` | diagnostic | artifact |
| `:file/bytes` | diagnostic | file system |
| `:links` | diagnostic | derived by builder |

### Classification meaning

| Classification | Behavior |
|---------------|----------|
| `:required` | Validation fails if missing or invalid |
| `:recommended` | Warning emitted, validation passes |
| `:diagnostic` | Logged only, no validation impact |
| `:future` | Schema reserved, not yet populated |

## 4. Validation Model

```clojure
{:schema/version "evidence-registry-validation.v1"
 :registry-path "evidence-registry.json"
 :generated/at "2026-06-18T12:00:00Z"
 :checks [{:id "every-entry-has-id" :status :passed}]
 :status :passed
 :metrics {:total 14, :passed 10, :warnings 3, :failed 1}}
```

| Category | Checks |
|----------|--------|
| required | Every entry has id, type, content-hash, path. Every path exists. Content hash matches file. No duplicate IDs. |
| recommended | Every entry has scenario-id, run-id, event-index. Targeted entries have subject/type, subject/id, action/type. Every group-id resolves. |
| diagnostic | World hashes present. Chain-seq monotonic. No gaps. File metadata populated. |
| future | Invariant-linked IDs exist. Artifact paths in test-artifacts.json. |

## 5. Builder Flow

```
Entry point: (build-evidence-registry! dir)

  1. Read scenario/run metadata from world params or run context
  2. Scan event-evidence/ directory with read-evidence-json
  3. Build entries vector from extracted artifact fields
  4. Build indexes (by-event-index, by-group-id, by-subject, etc.)
  5. Write evidence-registry.json
  6. Run validation checks
  7. Write evidence-registry-validation.json
  8. Register both files in chain registry via index-artifact-entry
```

**CLI:** `bb evidence:registry --dir results/test-artifacts`

## 6. Integration Points

| Component | Phase 1 Change | Rationale |
|-----------|---------------|-----------|
| `io/event_evidence.clj` | None | Registry reads, never writes |
| `evidence/chain.clj` | None | Registry reads test-artifacts.json, doesn't modify it |
| `sim/fixtures.clj` | Optional post-run hook | Can invoke builder after scenario finalization |
| `replay.clj` | None | Registry is post-processing only |
| `invariants.clj` | Deferred to Phase 3 | Invariant linking needs separate design |

## 7. Minimal First PR

**Phase 1 scope:** ~200 lines of new code, zero changes to existing capture paths.

| File | What |
|------|------|
| `src/resolver_sim/evidence/registry.clj` | `build-evidence-registry!` — scanner, builder, writer |
| `src/resolver_sim/evidence/registry_validation.clj` | `validate-evidence-registry!` — check runner |
| `bb.edn` or `scripts/evidence.clj` | Task alias |
| `test/resolver_sim/evidence/registry_test.clj` | Tests for builder and validator |

### Excluded from Phase 1

- Changes to `capture-event-evidence!` or `emit-evidence!`
- Changes to `chain.clj` or `test-artifacts.json`
- Invariant result linking
- Scenario runner integration
- Strict-mode enforcement

### Public API (Phase 1)

```clojure
(build-evidence-registry! dir)
;; => {:registry-path "evidence-registry.json"
;;     :validation-path "evidence-registry-validation.json"
;;     :entry-count 42
;;     :validation-status :passed}

(evidence-for-event registry event-index)
;; Entries with :event/index matching

(evidence-for-group registry group-id)
;; Entries with :evidence/group-id matching

(evidence-for-subject registry subject-type subject-id)
;; Entries with matching :subject/type and :subject/id
```

## 8. Risks and Tradeoffs

| Risk | Mitigation |
|------|------------|
| Registry stale if built before artifacts finalize | Build as last step; document ordering |
| Scanning slow for large runs | Single-pass directory read |
| Content hashes drift with serialization | Use artifact's internal `:evidence/hash`, not file SHA |
| Indexes duplicate entry data | Derived convenience; entries are source of truth |
| Researcher expects live queries | Document as post-run snapshot |

## 9. Future Phases

| Phase | What | Depends on |
|-------|------|------------|
| 1 | Read-only registry builder + validation | Nothing |
| 2 | Integration into scenario runner + bb task | Phase 1 |
| 3 | Invariant result linking | Phase 2 |
| 4 | Strict-mode CI gates | Phase 3 |
| 5 | Capture metadata hardening | Phase 4 |

## 10. Diff Evidence

A read-only diagnostic artifact generated post-run by comparing before/after world state from replay traces.

### Schema

```clojure
{:schema/version "diff-evidence.v1"

 ;; Identity
 :evidence/id "ev-000017-state-diff-91fd2231"
 :evidence/type :state-diff
 :evidence/layer :diff
 :evidence/role :diagnostic

 ;; Event linkage
 :scenario/id "S19"
 :run/id "S19-run-20260618-abc123"
 :event/index 17
 :event/type :slash_resolver
 :evidence/group-id "S19-run:17:slash_resolver"

 ;; Content hashes
 :hash/world-before "sha256-..."
 :hash/world-after "sha256-..."
 :hash/diff "sha256-..."

 ;; Summary
 :diff/summary
 {:changed-paths 4
  :added-paths 1
  :removed-paths 0
  :unexpected-paths 0
  :financial-changes 1}

 ;; Individual changes
 :diff/changes
 [{:path [:resolvers "0xResolver" :stake]
   :op :changed
   :before 1000
   :after 900
   :delta -100
   :classification :unclassified}

  {:path [:slash-obligations "slash-42"]
   :op :added
   :before nil
   :after {:resolver "0xResolver" :amount 100}
   :classification :unclassified}]

 ;; Validation
 :validation/status :passed
 :validation/warnings []}
```

### Builder

```
replay output → post-run diff builder → diff-evidence artifacts → evidence registry
```

The builder works from canonical before/after world state already emitted by replay. It does NOT add new hooks inside protocol logic.

**Implementation:**

```clojure
(build-diff-evidence! before-world after-world event-context)
;; 1. Compute structural diff (walk both worlds, find add/change/remove at top-level paths)
;; 2. Build diff-evidence.v1 artifact
;; 3. Write to diff-evidence/<id>.json
;; 4. Return the evidence-id for registry linking
```

The structural diff algorithm:
- Walk top-level keys of both worlds
- For each key present in before only → `:removed`
- For each key present in after only → `:added`
- For each key in both → if values differ → `:changed` (with before/after values)
- Compute hash/diff from the changes vector (stable JSON)

### Integration with Evidence Registry

```clojure
{:indexes
 {:by-event-index
  {17 {:generic  ["ev-000017-transition-a81f92cc"]
       :targeted ["ev-000017-slash-resolver-b21c55aa"]
       :diff     ["ev-000017-state-diff-91fd2231"]
       :invariants ["inv-slashing-conservation-77cc91"]}}

  :by-subject
  {[:resolver "0xResolver"]
   ["ev-000017-slash-resolver-b21c55aa"
    "ev-000017-state-diff-91fd2231"]}}}
```

The registry builder computes diff links during the same post-run pass: for each event with a generic trace entry, check if a corresponding diff-evidence artifact exists in `diff-evidence/` and link it.

### Validation Rules

| Classification | Rule |
|---------------|------|
| required | Diff evidence has scenario-id, run-id, event-index, event-type, group-id |
| required | Diff evidence path exists |
| required | Diff hash is stable (deterministic from same inputs) |
| recommended | Referenced before/after world hashes are present |
| recommended | Diff evidence belongs to an evidence group with ≥1 entry |
| diagnostic | Diff evidence links to a generic transition |
| diagnostic | Large diffs (≥50 changed paths) produce warnings |
| warning only | Missing before/after world snapshots (v1) |
| optional v1 | Unexpected-path classification is descriptive, not blocking |

### File Location

```
results/<run-id>/
├── diff-evidence/
│   ├── ev-000017-state-diff-91fd2231.json
│   └── ...
```

### Phased Rollout

| Phase | What | Depends on |
|-------|------|------------|
| 1 | Generate structural diffs post-run, store as diagnostic evidence, link in registry, basic validation | Evidence registry Phase 1 |
| 2 | Path filters to suppress noisy/internal paths, summaries for domains (balances, claims, obligations, stake, yield) | Phase 1 |
| 3 | Semantic classification (`:expected`, `:unexpected`, `:invariant-relevant`, `:financial-boundary`, `:diagnostic-only`) | Phase 2 |
| 4 | Researcher-facing summaries in notebooks | Phase 3 |

### What v1 Does NOT Do

- Perfect semantic classification
- Invariant-aware diff interpretation
- Every path indexed globally in :by-diff-path (deferred to Phase 2)
- Bidirectional graph links
- Mandatory before/after snapshots for every event
- Replay-level diff instrumentation
- Protocol-specific diff code
- CI failure on noisy diffs

### Design Constraints

1. **Post-run only.** Diff evidence is generated from canonical before/after state already emitted by replay. No new capture-event-evidence!-style hooks inside protocol code.
2. **Least-replay-surprise.** Replay output should not change when diff-evidence is enabled.
3. **Read-only.** Diff evidence does not modify replay outputs.
4. **Derived.** Always rebuildable from replay traces. Safe to delete.

## 11. Updated Future Phases

| Phase | What | Depends on |
|-------|------|------------|
| 1 | Read-only registry builder + validation | Nothing |
| 2 | Integration into scenario runner + bb task | Phase 1 |
| 3 | Structural diff-evidence generation | Phase 2 |
| 4 | Diff path filters + domain summaries | Phase 3 |
| 5 | Invariant result linking in registry | Phase 3 |
| 6 | Semantic diff classification | Phase 4 |
| 7 | Strict-mode CI gates | Phase 5 |
| 8 | Capture metadata hardening | Phase 6 |

## 12. Chain-Cursor Integration

### Current State

The chain-cursor in `io/event_evidence.clj` is capture-time machinery that sequences targeted evidence artifacts. It injects:

- `:evidence/chain-seq`
- `:evidence/chain-prev-hash`
- `:evidence/chain-self-hash`

This is useful, but currently under-exposed. The cursor mutates evidence metadata during capture, but no run-level artifact explains the integrity, coverage, or navigability of the resulting chain.

The cursor itself should remain internal. The registry should make the emitted chain evidence-backed by reconstructing and validating it from the artifacts written to disk.

### What the Registry Adds

The evidence registry should add a run-level `:evidence-chain` summary derived during the registry builder’s scan of `event-evidence/`.

Example:

```clojure
{:evidence-chain
 {:schema/version "evidence-chain-summary.v1"
  :scope :targeted-evidence
  :status :passed

  :summary
  {:first-seq 1
   :last-seq 42
   :total-chained 42
   :total-unchained 3
   :coverage-ratio 0.933}

  :integrity
  {:status :passed
   :gaps []
   :duplicate-seqs []
   :prev-hash-mismatches []
   :self-hash-mismatches []}

  :coverage
  {:targeted-total 45
   :targeted-chained 42
   :targeted-unchained ["ev-000011-legacy-abc"
                        "ev-000012-legacy-def"
                        "ev-000013-legacy-ghi"]
   :generic-total 5
   :generic-chained 0
   :generic-policy :not-required}

  :warnings []}}

### Classification

| Field | Classification | Rationale |
|-------|---------------|-----------|
| `:evidence-chain/status` | `:diagnostic` initially | Useful integrity signal, should not break older runs |
| `:evidence-chain/integrity` | `:diagnostic` initially | Reports gaps, duplicates, and hash mismatches |
| `:evidence-chain/coverage` | `:diagnostic` initially | Makes unchained evidence visible without requiring full coverage |
| `:indexes/by-chain-seq` | `:diagnostic` | Derived navigation aid |
| `:chain/self-hash-mismatch` | `:recommended` or `:strict-fail` later | Strong evidence of corruption or schema/hash mismatch |
| `:chain/prev-hash-mismatch` | `:recommended` or `:strict-fail` later | Strong evidence of broken chain continuity |

### Chain-Seq Uniqueness

The chain-cursor assigns a **single, monotonically incrementing seq number** per `capture-event-evidence!` call. Each targeted artifact has a unique `:evidence/chain-seq`. There is never more than one artifact per chain-seq.

The `:by-chain-seq` index is a flat map of seq → single ID:

```clojure
{:indexes
 {:by-chain-seq
  {1 "ev-000001-escrow-created-a1b2c3"
   2 "ev-000002-dispute-raised-d4e5f6"
   3 "ev-000003-slash-proposed-a7b8c9"}}}
```

The `:by-group-id` index is the correct way to find all artifacts (generic + targeted) for the same event:

```clojure
{:indexes
 {:by-group-id
  {"S19-run:0:create_escrow"
   ["ev-000001-transition-..."         ;; generic, no chain-seq
    "ev-000001-escrow-created-..."]}}} ;; targeted, chain-seq 1
```

Generic traces emitted by `emit-evidence!` (dispatcher) bypass the chain-cursor and have no `:evidence/chain-seq`.

### What This Does NOT Do

- Change chain-cursor capture behavior
- Make the chain-cursor emit its own evidence artifacts
- Block validation on chain gaps
- Require every artifact to have chain fields
