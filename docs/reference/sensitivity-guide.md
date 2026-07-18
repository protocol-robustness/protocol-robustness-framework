# Sensitivity & Sentinel Developer Guide

## Architecture

Three classification levels per artifact:

| Level | Source |
|---|---|
| **Structural** | Heuristic inference from artifact content (`sentinel/classify-structural`) |
| **Declared** | Explicit `:scenario/sensitivity` floor in scenario metadata |
| **Effective** | `max(structural, declared)` — never below structural |

Level ordering: `:public < :internal < :private < :embargoed < :critical-private`

## Declaring Sensitivity in a Scenario

```clojure
;; scenario definition EDN
{:id "my-scenario"
 :scenario/sensitivity
 {:level :sensitivity/private
  :risk-meta {:value-at-risk "15,000,000"
              :risk-severity :risk-severity/critical
              :reason-codes [:contains-live-vulnerability
                             :contains-protocol-identifier]}}}
```

The declared level acts as a **floor** — structural classification can raise it higher, but declared level can never downgrade structural.

## Sensitivity Status Codes

Each scenario entry in the report carries a structured status:

| Code | Meaning |
|---|---|
| `evaluated` | Sensitivity metadata present and valid; declared level used |
| `no-declaration-structural-only` | No declaration block found; structural-only classification applied |
| `not-scanned` | Scenario was not scanned for sensitivity |
| `evaluation-failed` | Sensitivity evaluation encountered an error |
| `malformed-declaration` | Declaration block present but invalid/parseable |

A scenario without a declaration still has a valid effective sensitivity based on structural classification. It does **not** disappear from run-level aggregation.

## Pipeline Flow

```
1. scenario_runner   → merge-sensitivity across all scenarios
                       → attach :sensitivity/run-level to run result
                       → build bundle-root with lightweight summary

2. scan-sensitivity  → safety scan (secret scanning)
                     → merge-sensitivity (run-level max)
                     → build-sensitivity-report  ← CANONICAL PROVENANCE
                     → write to manifest/sensitivity-report.json

3. build-attestation → read persisted sensitivity-report.json
                     → reference both semantic and byte hash in bundle
```

## Provenance Ownership

`report.clj`'s `build-sensitivity-report` function is the **single canonical provenance authority**. It calls `build-canonical-report-provenance` to assemble and persist the complete provenance object that all downstream artifacts consume.

`propagation.clj`'s `build-sensitivity-derivation` function is a **pure derivation helper**. It computes fact records from inputs but does NOT bind them to report identity or policy context. Other callers must not persist its output as an equivalent authority.

(The deprecated alias `build-provenance` was removed — use `build-sensitivity-derivation` instead.)

## Provenance Dimensions

### 1. Source Provenance

The `:sentinel/structured-sources` vector in the report provenance replaces the old opaque string list (`:sentinel/sources` remains as a human-readable display summary).

Each scenario contribution is recorded as:

```clojure
{:source/type :scenario-sensitivity
 :scenario/id "s02"
 :scenario/input-hash "sha256:abc..."
 :declared-level "private"
 :structural-level "internal"
 :effective-level "private"
 :run-level-role :run-max}   ;; or :below-max
```

Implementation sources are recorded as:

```clojure
;; Classifier ruleset
{:ruleset/id "sensitivity-sentinel"
 :ruleset/schema "sentinel-rules.v1"
 :ruleset/version "sensitivity-sentinel.v1"
 :ruleset/hash "sha256:..."}

;; Structural classification implementation
{:implementation/id "structural-classifier"
 :implementation/version "v1"
 :implementation/source "resolver-sim.sensitivity.sentinel/classify-structural"
 :ruleset-ref {:ruleset/id "structural-heuristics"
               :ruleset/schema "sentinel-rules.v1"
               :ruleset/version "sensitivity-sentinel.v1"}}

;; Secret scanner
{:implementation/id "secret-scanner"
 :implementation/version "v1"
 :implementation/source "resolver-sim.commands.scenario-safety/sensitivity-findings"
 :ruleset-ref {:ruleset/id "secret-patterns"
               :ruleset/schema "regex-patterns.v1"
               :ruleset/version "v1"}}

;; Merge function
{:implementation/id "merge-sensitivity"
 :implementation/version "v2"
 :implementation/source "resolver-sim.sensitivity.propagation/merge-sensitivity"}
```

Policy binding:

```clojure
{:source/type :policy-profile
 :profile/id "internal"
 :profile/hash "sha256:..."
 :policy/id "sensitivity-disclosure-policy"
 :policy/version "v2"}
```

Run context:

```clojure
{:source/type :run-context
 :run/id "uuid..."
 :profile "internal"
 :sentinel-version "sensitivity-sentinel.v1"}
```

### 2. Declaration Provenance

Each scenario entry in `:scenarios` includes a `:declaration-provenance` record (when a declaration exists):

```clojure
{:declaration/source-artifact-id   "my-scenario"
 :declaration/source-path          "scenarios/edn/my-scenario.edn"
 :declaration/source-hash          "sha256:..."
 :declaration/schema               "scenario-sensitivity.v1"
 :declaration/value                "private"
 :declaration/risk-meta-hash       "sha256:..."}
```

The source hash covers the exact canonical scenario definition, not only the execution input. This ties the declaration back to the authoritative scenario file.

### 3. Derivation / Aggregation Provenance

The report includes an `:aggregation-derivation` record showing how the run-level was computed:

```clojure
{:aggregation/function           :max-effective-sensitivity
 :aggregation/version            "merge-sensitivity.v2"
 :aggregation/merge-function-ref {:implementation/id "merge-sensitivity" ...}
 :aggregation/input-count        12
 :aggregation/included-count     10
 :aggregation/missing-count      2
 :aggregation/scenario-id-set-hash "sha256:..."
 :aggregation/winners            ["s02" "s09"]
 :aggregation/result             "private"
 :risk-aggregation/function      :max-risk-severity
 :risk-aggregation/winner-scenario-id "s09"
 :risk-aggregation/result        "critical"}
```

Multiple "winners" are explicitly listed when scenarios tie for the run-level max.

### 4. Scenario-Set Reconciliation

The provenance's `:source/type :scenario-set` record proves completeness:

```clojure
{:source/type :scenario-set
 :scenario-ids ["s01" "s02" ...]
 :scenario-id-set-hash "sha256:..."
 :count 12
 :missing-scenario-ids []
 :unexpected-scenario-ids []}
```

The scenario ID set hash should reconcile against the canonical execution inventory or package index.

### 5. Policy / Decision Provenance

The report separates **classification provenance** (what level was determined) from **decision provenance** (what action was taken based on profile):

- `:profile` — applied safety profile (public vs internal)
- `:decision` — resulting decision (allowed, blocked, internal-retention)
- Policy identity is embedded in `:provenance :sentinel/structured-sources` via the `:policy-profile` record
- `sentinel/policy-hash` provides a deterministic hash of the full sentinel policy configuration (version, levels, sinks, disclosure rules)

### 6. Semantic Hash vs Persisted-Byte Hash

The report distinguishes two hashes:

| Field | Purpose |
|---|---|
| `:report/semantic-hash` | Computed over canonical semantic projection, **excluding** volatile fields (`:evaluated-at`, `:report-hash`, `:report-byte-hash`). Same inputs = same hash regardless of when generated. Intent: `:evidence-record`. |
| `:report-byte-hash` | SHA-256 of the exact persisted file bytes (after JSON write). Includes `:evaluated-at` and any serialization artifacts. Computed during `write-sensitivity-report!`. |
| `:report-byte-length` | File size in bytes. |

The attestation bundle binds both:

```clojure
{:sensitivity-report/ref
 {:schema "sensitivity-report.v2"
  :semantic-hash "sha256:..."
  :sha256 "sha256:..."
  :byte-length 1234
  :path "manifest/sensitivity-report.json"}}
```

The semantic hash proves equivalent meaning. The byte hash proves the exact file being packaged.

### 7. Verification Chain

A verifier consuming the attestation bundle should:

1. Read report path from `:sensitivity-report/ref :path`
2. Verify `:sha256` and `:byte-length` match the actual file
3. Parse JSON, validate `:schema-version`
4. Recompute `:report/semantic-hash` from the parsed data and compare
5. Verify scenario-set reconciliation (`:scenario-id-set-hash` against expected inventory)
6. Extract `:decision` and apply policy handling

Sensitivity decision controls bundle handling but does not determine general bundle integrity:
- `:allowed` → bundle is valid for release
- `:blocked` → bundle integrity may still be valid; release is prohibited
- `:internal-retention` → bundle integrity valid; distribution constrained
- Missing/malformed sensitivity evidence is distinct from an explicit `:blocked` decision

### 8. Derived-Artifact Sensitivity Propagation

For all derived artifacts (evidence DAG nodes, notebooks, exported summaries), sensitivity should propagate from the **maximum source level**:

```clojure
{:sensitivity/derivation
 {:source-artifacts [{:artifact/ref "..." :effective-level "private"}
                     {:artifact/ref "..." :effective-level "internal"}]
  :default-rule :max-source-level
  :structural-level "private"
  :declared-floor nil
  :effective-level "private"
  :reason-codes [:derived-from-sensitive-source]}}
```

A separately constructed summary can be less sensitive only through an **explicit release-projection path** with its own provenance.

### 9. Declassification / Projection Model

There is no automatic declassification. A declassified projection is a **new artifact** with explicit lineage:

```clojure
{:projection/id "pro-rata-public-summary.v1"
 :projection/implementation-hash "sha256:..."
 :source-artifact-commitments [...]
 :included-fields [...]
 :excluded-fields [...]
 :aggregation-thresholds [...]
 :residual-disclosure-risk ...
 :policy-decision ...
 :approval-attestation-ref ...}
```

Until such a policy exists, the conservative rule applies: **all derived artifacts inherit the maximum source sensitivity**.

### 10. Integrity Binding

All provenance references use structured artifact references containing:

```clojure
{:schema         "sensitivity-report.v2"
 :path           "manifest/sensitivity-report.json"
 :semantic-hash  "sha256:..."
 :sha256         "sha256:..."
 :byte-length    1234}
```

This applies to bundle root references, attestation bundle references, and all downstream consumers.

## Report Schema (v2)

```clojure
{:schema-version         "sensitivity-report.v2"
 :run-id                 <uuid>
 :profile                "public" | "internal"
 :decision               "allowed" | "blocked" | "internal-retention"
 :findings               [{:path <path> :pattern <regex>} ...]
 :scenario-count         <int>
 :sensitive-scenario-count <int>
 :scenarios              [{:id               <str>
                           :input-hash       <hex>
                           :structural-level <str>
                           :effective-level  <str>
                           :declared-level   <str>          ;; optional
                           :sensitivity/status <str>
                           :declaration-provenance <map>    ;; optional
                           :risk-meta        <map>           ;; optional
                           :result-level     <str>}          ;; optional
                          ...]
 :run-level              <str>               ;; optional
 :structural-level       <str>               ;; optional
 :risk-meta              <map>               ;; optional
 :provenance             {:sentinel/effective-level <str>
                          :sentinel/structural-level <str>
                          :sentinel/declared-level <str>    ;; optional
                          :sentinel/reasons [<str> ...]
                          :sentinel/risk-meta <map>
                          :sentinel/sources [<str> ...]     ;; display summary
                          :sentinel/structured-sources
                          [{:source/type :scenario-sensitivity ...}
                           {:ruleset/id ...}
                           {:implementation/id ...}
                           {:source/type :policy-profile ...}
                           {:source/type :scenario-set ...}
                           {:source/type :run-context ...}]}
 :aggregation-derivation {:aggregation/function ...
                          :aggregation/version ...
                          :aggregation/winners [...]
                          ...}
 :evaluated-at           <ISO-8601>
 :report-hash            <sha256-hex>         ;; semantic hash
 :report/semantic-hash   <sha256-hex>         ;; same as report-hash
 :report-byte-hash       <sha256-hex>         ;; set by write! — exact file bytes
 :report-byte-length     <int>}               ;; set by write! — file size
```

## Merge-Sensitivity Algorithm

1. Collect all scenario sensitivity declarations
2. Filter nil entries (scenarios with no sensitivity metadata get structural-only, not removed)
3. Pick the highest effective level as run-level
4. Among risk-meta entries, keep the one with highest `:risk-severity`
5. Multiple scenarios at the same max level are all "winners"
6. If no scenarios have any classification, run-level is nil

## Verification

The attestation bundle verifier checks `:sentinel/decision` from the sensitivity report:
- Verify report file: byte hash, length, schema, semantic hash
- Reconcile scenario ID set against inventory
- `:allowed` → bundle valid
- `:blocked` → bundle status `:blocked-by-sensitivity-policy` (integrity may still be valid)
- `:internal-retention` → bundle valid, distribution constrained

## Key Source Files

| File | Role |
|---|---|
| `sensitivity/sentinel.clj` | Classification engine, disclosure matrix, policy-hash, enforcement assertions |
| `sensitivity/propagation.clj` | Effective sensitivity, merge, attach, `build-sensitivity-derivation` (pure helper), downgrade prevention |
| `sensitivity/report.clj` | v2 report builder — `build-sensitivity-report` calls `build-canonical-report-provenance` as the single provenance authority |
| `commands/scenario_safety.clj` | Secret scanning (private keys, tokens, JWTs) |
| `commands/scenario_orchestration.clj` | Pipeline phases: scan-sensitivity, build-attestation-bundle |
| `run/bundle_root.clj` | Bundle root with lightweight sensitivity summary and report-reference |
| `evidence/attestation_bundle.clj` | Attestation bundle with sensitivity-report/ref (semantic + byte hash) |
| `commands/scenario_inventory.clj` | Artifact registry declaring report as CORE |
