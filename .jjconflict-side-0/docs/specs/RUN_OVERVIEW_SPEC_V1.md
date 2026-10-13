# RUN_OVERVIEW_SPEC_V1

Status: Draft V1

## 1. Purpose

The Run Overview is a normalized, stable-field summary of a suite
execution. It strips volatile fields (timestamps, paths, timing,
raw execution data) so that two runners executing the same suite
produce byte-for-byte identical overview hashes.

This makes the overview the **canonical comparison surface** for:

- Comparing local vs remote runner results
- Quorum / multi-runner consensus
- Regression checks across git commits
- Externally verifiable reports
- Deterministic bundle root hashes

## 2. Design Principles

### 2.1 Stable Fields Only

The overview MUST contain only fields that are deterministic given
the same suite, protocol, and runner. Fields that vary across runs
(runtime, paths, host info) MUST be excluded.

### 2.2 Runner-Agnostic

Two different runner implementations executing the same suite with
the same protocol MUST produce the same overview hash.

### 2.3 Diagnostic Isolation

Diagnostic fields (elapsed time, performance counters, debug output)
are intentionally excluded. They belong in the run `:diagnostics`
section, not the overview.

## 3. Fields

### 3.1 Top-Level

| Field | Type | Description |
|---|---|---|
| `:overview/schema-version` | string | `"run-overview.v1"` |
| `:suite` | map | Suite identification (see §3.2) |
| `:results` | vector | Per-scenario stable results (see §3.3) |
| `:totals` | map | Aggregate counts (see §3.4) |
| `:consensus` | map | Consensus eligibility (see §3.5) |

### 3.2 Suite

```clojure
{:suite/key       :sew-invariants
 :scenario-count  120}
```

Runner identity is intentionally excluded from the overview for runner-agnostic
comparison. It belongs in the bundle root run request, diagnostics, or runner
attestation instead.

### 3.3 Per-Scenario Result

Each entry in `:results` contains only stable fields:

| Field | Type | Description |
|---|---|---|
| `:scenario-id` | string | Scenario identifier |
| `:scenario-hash` | string | Stable content hash of the scenario definition |
| `:pass?` | boolean | Whether the scenario passed |
| `:outcome` | keyword | Raw outcome (`:pass`, `:fail`, `:error`) |
| `:halt-reason` | string or nil | Reason for halt if failed |
| `:checks` | vector | Check results (stable check maps) |
| `:violations` | map | Invariant violation details |
| `:dispatcher-id` | keyword | Dispatcher used for this scenario |
| `:expected-fail?` | boolean | Whether failure was expected |

Volatile fields excluded from each entry:

| Excluded Field | Reason |
|---|---|
| `:replay-result` | Raw execution data, differs across runners |
| `:execution/raw` | Raw execution data |
| `:runner` | Runner metadata (timing, host info) |
| `:scenario` | Full scenario map (may contain volatile metadata) |
| `:scenario-path` | Non-authoritative file path, differs across runners |

### 3.4 Totals

```clojure
{:passed            118
 :failed            2
 :total             120
 :expected-failed   2
 :unexpected-failed 0}
```

### 3.5 Consensus

```clojure
{:eligible? true
 :basis     :normalized-result-hash}
```

## 4. Volatile Fields

The following fields are stripped when building the overview:

### Per-Scenario Volatile Fields

- `:replay-result` — full raw replay output (runner-specific)
- `:execution/raw` — alias for replay-result
- `:runner` — runner metadata (timing, host, implementation)
- `:scenario` — full scenario map (may contain volatile metadata)
- `:name` — display name (may differ across runners)
- `:source` — source tag (`:inline`, `:file`, etc.)

### Run-Level Volatile Fields

- `:diagnostics` — timing, performance metrics
- `:summary` — aggregate summary with timing
- `:elapsed-ms` — wall clock time
- `:runner-selection` — runner identity (moved to bundle root / diagnostics)

## 5. Hash Projection

The overview hash is computed over the entire overview map:

```clojure
(hash-with-intent {:hash/intent :run-overview} overview)
```

Two runners produce the same hash iff they execute the same suite
and produce the same stable result for every scenario.

## 6. Build

```clojure
(require '[resolver-sim.run.overview :as overview])

(overview/build-overview scenario-run-result)
;; => {:overview/schema-version "run-overview.v1"
;;     :suite {...}
;;     :results [...]
;;     :totals {...}
;;     :consensus {:eligible? true :basis :normalized-result-hash}}

(overview/overview-hash overview)
;; => "abc123..."  (deterministic, runner-agnostic)
```

## 7. Example

```clojure
{:overview/schema-version "run-overview.v1"
 :suite {:suite/key       :sew-invariants
         :scenario-count  120}
 :results [{:scenario-id    "S01"
            :scenario-hash  "abc123def..."
            :pass?          true
            :outcome        :pass
            :halt-reason    nil
            :checks         [{:check/id :some-check :check/status :pass}]
            :violations     {}
            :dispatcher-id  :protocol/sew-v1
            :expected-fail? false}
           {:scenario-id    "S02"
            :scenario-hash  "456ghi..."
            :pass?          false
            :outcome        :fail
            :halt-reason    "theory violation"
            :checks         [{:check/id :theory-check :check/status :fail}]
            :violations     {:theory-mismatch {:expected :pass :actual :fail}}
            :dispatcher-id  :protocol/sew-v1
            :expected-fail? true}]
 :totals {:passed 1 :failed 1 :total 2
          :expected-failed 1 :unexpected-failed 0}
 :consensus {:eligible? true :basis :normalized-result-hash}}
```
