# EVIDENCE_POLICY_SPEC_V1

Status: Draft V1

## 1. Purpose

Evidence policies define what information is captured in evidence nodes.

Evidence policies do not determine execution behavior.

Evidence policies determine evidence visibility and retention.

------

## 2. Design Principles

### 2.1 Evidence Nodes Are Mandatory

All executions SHALL produce evidence nodes.

Evidence policies SHALL NOT suppress node creation.

### 2.2 Policy Controls Content

Policies determine:

- what is included
- what is omitted
- what is retained
- what is summarized

### 2.3 Policies Are Versioned

Evidence policies SHALL be versioned and hashable artifacts.

Evidence interpretation depends on policy version.

------

## 3. Registry Structure

```clojure
{:policy-version 1

 :policies [...]}
```

------

## 4. Policy Definition

Example:

```clojure
{:id :default

 :classes
 #{:result
   :metrics
   :invariants
   :failures
   :environment}}
```

------

## 5. Evidence Classes

### :result

Execution outcome.

Includes:

```clojure
{:status ...
 :started-at ...
 :finished-at ...}
```

------

### :inputs

Execution inputs.

Includes:

```clojure
{:input-hash ...}
```

------

### :outputs

Execution outputs.

Includes:

```clojure
{:output-hash ...}
```

------

### :metrics

Runtime measurements.

Examples:

```clojure
{:runtime-ms ...
 :memory-bytes ...
}
```

------

### :invariants

Invariant evaluation results.

Examples:

```clojure
[{:id :conservation-of-value
  :status :pass}]
```

------

### :failures

Failure details.

Examples:

```clojure
{:type ...
 :message ...
 :stacktrace ...}
```

------

### :environment

Execution environment metadata.

Examples:

```clojure
{:os ...
 :jvm ...
}
```

------

### :git

Repository state.

Examples:

```clojure
{:commit ...
 :dirty? ...}
```

------

### :stdout

Captured standard output.

------

### :debug

Implementation-specific diagnostics.

------

## 6. Failure Filtering

Policies MAY filter failure categories.

Example:

```clojure
{:failure-policy

 {:include-expected-failures? false
  :exclude-classes #{:environment :debug}}}
```

------

Expected failures SHALL remain counted in execution summaries even when details are excluded.

Filtered or hidden failure details SHALL NOT change the canonical node hash.

Node integrity MUST be computed from the unfiltered canonical execution projection,
not from policy-filtered presentation output.

------

## 7. Failure Classification

Failures SHALL be classified.

Example:

```clojure
{:failure-type :known-regression}
```

Examples:

```clojure
:known-regression
:experimental
:environment
:flaky
:unexpected
```

------

## 8. Retention Policy

Policies MAY define retention requirements.

Example:

```clojure
{:retention

 {:full-evidence-days 90

  :debug-days 30

  :summary-years 5}}
```

Retention policy SHALL NOT alter evidence hashes already emitted.

------

## 9. Evidence Node Model

Evidence node structure:

```clojure
{:execution-id ...

 :execution-kind ...

 :timestamp ...

 :inputs-hash ...

 :outputs-hash ...

 :evidence {...}

 :parent-hash ...

 :self-hash ...}
```

------

## 10. Invariant Evidence

Invariant evaluation SHOULD be captured separately from test outcomes.

Example:

```clojure
{:invariants

 [{:id :accounting-consistency
   :status :pass}

  {:id :non-negative-balances
   :status :pass}]}
```

Invariant evidence is preferred over binary pass/fail reporting.

------

## 11. Benchmark Evidence

Benchmark evidence SHALL support both deterministic and statistical execution modes.

Deterministic mode example:

```clojure
{:runtime-ms 42}
```

Statistical mode example:

```clojure
{:samples 100

 :p50 42

 :p95 58

 :mean 45}
```

Statistical benchmark support MAY be introduced after policy adoption.

------

## 12. Audit Requirement

Given an evidence node and policy version, an auditor SHALL be able to determine:

- what evidence was captured
- what evidence was omitted
- why omission occurred
- how evidence should be interpreted

This requirement exists to preserve long-term evidentiary integrity.