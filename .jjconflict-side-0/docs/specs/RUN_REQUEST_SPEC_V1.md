# RUN_REQUEST_SPEC_V1

Status: Draft V1

## 1. Purpose

The Run Request is the canonical input to `run-and-report`. It describes
**how** a suite is executed — which runner, which protocol, which evidence
and output policies — but not **which** individual scenarios (scenario
selection is the suite's responsibility).

A valid Run Request is required for a bundle root to be **runnable**
(reproducible by another runner).

## 2. Design Principles

### 2.1 Execution Shape, Not Scenario Selection

The Run Request describes the execution environment. Scenario selection
is the suite's responsibility. A Run Request without a `:suite/key` is
valid but describes a registry-default run.

### 2.2 Canonical Runs Require Pinned Runner

For canonical (bundle-marked) runs, `:runner-selection` MUST specify
`:mode :pinned` with a known `:runner-id`. Non-pinned modes
(`:capability-match`, `:quorum`) produce non-canonical bundles.

### 2.3 Registry-Backed

Runner identities and protocol ids SHALL be registered in passive
registries (`execution-runner-definitions`, `orchestrator-definitions`, `protocol-symbol-registry`).
The Run Request references known registry entries by key.

## 3. Fields

### 3.1 Required

| Field | Type | Description |
|---|---|---|
| `:registry-key` | keyword | Registry configuration key (default `:default`) |
| `:workspace` | keyword | Execution workspace (default `:current`) |
| `:runner-selection` | map | Runner identity and selection mode (see §4) |

### 3.2 Optional

| Field | Type | Default | Description |
|---|---|---|---|
| `:suite/key` | keyword | nil | Named suite key (e.g. `:sew-invariants`, `:yield-provider-scenarios`) |
| `:protocol/default-id` | string | `"sew-v1"` | Protocol identifier from `protocol-symbol-registry` |
| `:evidence/profile` | keyword | nil | Evidence collection profile (`:standard`, `:minimal`, `:full`) |
| `:output/profile` | keyword | `:full` | Output verbosity profile (`:full`, `:summary`, `:minimal`) |
| `:mode` | keyword | `:production` | Execution mode (`:production` or `:dev`) |

## 4. Runner Selection

### 4.1 Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `:mode` | keyword | yes | `:pinned`, `:capability-match`, or `:quorum` |
| `:runner-id` | keyword | yes (pinned) | Known execution runner id from `execution-runner-definitions` |
| `:capabilities` | set | yes (capability-match) | Required capabilities for runner selection |

### 4.2 Modes

#### `:pinned` (canonical)

A specific runner is named. The runner id MUST be in
`known-execution-runner-ids`. Used for canonical bundle-root runs.

#### `:capability-match`

The system selects a runner satisfying the declared `:capabilities`.
The selected runner identity is recorded in the bundle root.

#### `:quorum`

Multiple independent runners execute the same suite. Consensus is
computed over the resulting DAG or normalized overview hashes.
Not yet implemented.

### 4.3 Known Runner IDs

| ID | Description | Capabilities |
|---|---|---|
| `:runner/local-bb` | Local Babashka execution runner | clojure, bb, filesystem, evidence-dag |
| `:runner/local-clojure` | Local Clojure JVM execution runner | clojure, jvm, filesystem, evidence-dag, full-classpath |

### 4.4 Orchestrator

Each execution runner references an orchestrator via `:orchestrator-id`.
The orchestrator dispatches the suite-level run-and-report function:

| ID | Description |
|---|---|
| `:orchestrator/run-and-report-v1` | Primary orchestrator dispatching suite-level execution |

## 5. Validation

A Run Request is valid iff:

1. All required fields are present
2. `:runner-selection` contains required sub-fields per mode
3. `:runner-selection` mode is one of `#{:pinned :capability-match :quorum}`
4. In `:pinned` mode, `:runner-id` is a known execution runner

```clojure
(validate-run-request request)
;; => {:valid? true}
;; => {:valid? false :errors [{:code :missing-request-fields ...}]}
```

## 6. Example

```clojure
{:registry-key       :default
 :workspace          :current
 :runner-selection   {:mode     :pinned
                      :runner-id :runner/local-bb}
 :suite/key          :sew-invariants
 :protocol/default-id "sew-v1"
 :evidence/profile   :standard
 :output/profile     :full
 :mode               :production}
```
