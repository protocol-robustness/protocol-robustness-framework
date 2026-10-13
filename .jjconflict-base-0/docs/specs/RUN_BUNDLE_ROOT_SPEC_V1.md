# RUN_BUNDLE_ROOT_SPEC_V1

Status: Draft V1

## 1. Purpose

A Bundle Root is a self-contained, hash-addressed record of a suite
execution. It captures the run request, registry state, execution
environment, execution summary, and normalized overview — enough
information to:

- Verify that a specific suite was executed
- Re-run the same suite with identical inputs
- Compare execution results across different runners
- Serve as the root of an evidence DAG for the run

## 2. Design Principles

### 2.1 Runnable by Default

A bundle root is **runnable** iff it contains a complete run request +
registry snapshot. Runnable means a runner can take the bundle root
and re-execute the same suite deterministically.

### 2.2 Self-Referential Hash

The bundle root has a self-referential hash (`:bundle/hash`) computed
from all fields except `:bundle/id` and `:bundle/hash` itself. The
`:bundle/id` is set equal to `:bundle/hash`.

### 2.3 Canonical vs Non-Canonical

A canonical bundle root was produced by a pinned runner executing the
full registered suite with no scenario filtering. Non-canonical bundles
carry a structured `:non-canonical-reason` and are not suitable for
comparison or attestation.

### Non-Canonical Reason Codes

| Code | Description |
|---|---|
| `:single-scenario-selected` | Single scenario selected; not a full suite run |
| `:fixture-suite-selected` | Fixture suite selected; not a registered suite run |
| `:dev-mode` | Development mode; bundle not suitable for comparison |
| `:capability-match-runner` | Capability-matched runner; non-deterministic selection |
| `:quorum-not-yet-canonical` | Quorum mode selected; not yet canonical |
| `:scenario-filtering` | Scenario filtering applied (reserved for future use) |

## 3. Fields

### 3.1 Top-Level

| Field | Type | Required | Description |
|---|---|---|---|
| `:bundle/schema-version` | string | yes | `"bundle-root.v1"` |
| `:bundle/id` | string | yes | Hex hash (equals `:bundle/hash`) |
| `:bundle/hash` | string | yes | Self-referential hash of all other fields |
| `:run/request` | map | yes | The RUN_REQUEST_SPEC_V1 map (see §4) |
| `:registry/snapshot` | map | yes | Registry state hashes at execution time (see §5) |
| `:run/environment` | map | yes | Execution environment metadata (see §6) |
| `:execution/summary` | map | yes | Aggregate results (see §7) |
| `:dag/root-node-hash` | string | yes | Hash of the DAG root evidence node |
| `:overview/hash` | string | yes | Hash of the normalized overview |
| `:overview` | map | yes | Full RUN_OVERVIEW_SPEC_V1 map |

### 3.2 Canonical Status Fields

| Field | Type | Description |
|---|---|---|
| `:bundle/canonical?` | boolean | True if full registered suite, production mode, pinned runner |
| `:bundle/non-canonical-reason` | map or nil | Structured reason if non-canonical: `{:code <keyword> :details <string>}` |

## 4. Run Request

The `:run/request` map conforms to RUN_REQUEST_SPEC_V1. It MUST contain:

```clojur
{:registry-key       :default
 :workspace          :current
 :runner-selection   {:mode :pinned :runner-id :runner/local-bb}
 :suite/key          :sew-invariants
 :protocol/default-id "sew-v1"
 :evidence/profile   :standard
 :output/profile     :full}
```

## 5. Registry Snapshot

The `:registry/snapshot` captures hashes of all active registries at
execution time. Each hash is computed via `hash-with-intent {:hash/intent :registry}`.

| Field | Source | Description |
|---|---|---|
| `:attestor-registry-hash` | `attestor-registry` | Attestor registry snapshot |
| `:scenario-suite-hash` | `suites` | Scenario suite definitions |
| `:dispatcher-registry-hash` | `known-protocol-ids` | Active protocol dispatchers |
| `:execution-registry-hash` | `execution-registry` | Registered execution modes |
| `:evidence-policy-hash` | `evidence-policy-registry` | Registered evidence policies |
| `:claim-definition-registry-hash` | `claim-definition-registry` | Registered claim definitions |

## 6. Environment

The `:run/environment` captures the execution context:

```clojure
{:git/commit       "abc123..."      ;; git rev-parse HEAD
 :git/dirty?       false             ;; git status --porcelain
 :clojure/version  "1.12.0"
 :java/version     "17.0.9"
 :os/name          "Linux"}
```

## 7. Execution Summary

The `:execution/summary` contains aggregate results:

```clojure
{:totals {:passed 118 :failed 2 :total 120}
 :status :pass}
```

## 8. Runnable Bundle Criteria

A bundle root is runnable iff ALL of the following hold:

| Check | Condition |
|---|---|
| `:run/request` present | Request map exists |
| `:run/request` valid | All required fields present, runner selection valid |
| `:registry/snapshot` present | Snapshot map exists |
| `:attestor-registry-hash` present | Attestor registry hash captured |
| `:scenario-suite-hash` present | Scenario suite hash captured |
| `:dispatcher-registry-hash` present | Dispatcher hash captured |
| `:evidence-policy-hash` present | Evidence policy hash captured |
| `:dag/root-node-hash` present | DAG root evidence node hash captured |
| `:bundle/id == :bundle/hash` | Self-referential hash consistency |
| `:overview/hash` present | Normalized overview hash captured |
| `:bundle/hash` present | Self-referential bundle hash captured |

```clojure
(runnable-bundle-root? bundle-root registries)
;; => {:runnable? true}
;; => {:runnable? false :errors [{:code :missing-run-request} ...]}
```

## 9. Hash Projection

The bundle hash is computed from all fields EXCEPT `:bundle/id` and
`:bundle/hash`:

```clojure
(let [base (dissoc bundle-root :bundle/id :bundle/hash)]
  (hash-with-intent {:hash/intent :bundle-root} base))
```

This is the self-referential hash pattern: the bundle commits to its
own content, and the hash is what you use to verify integrity.

## 10. Example

```clojure
{:bundle/schema-version "bundle-root.v1"
 :bundle/id             "abc123def456..."
 :bundle/hash           "abc123def456..."
 :run/request           {...}
 :registry/snapshot     {:attestor-registry-hash "r1"
                          :scenario-suite-hash "s1"
                          :dispatcher-registry-hash "d1"
                          :evidence-policy-hash "e1"
                          :execution-registry-hash "x1"
                          :claim-definition-registry-hash "c1"}
  :run/environment       {:git/commit "abc" :git/dirty? false ...}
  :execution/summary     {:totals {:passed 3 :failed 0 :total 3}
                          :status :pass}
  :dag/root-node-hash    "dag-root-hash"
  :overview/hash         "ov-hash"
  :overview              {...}
  :bundle/canonical?     true}
```
