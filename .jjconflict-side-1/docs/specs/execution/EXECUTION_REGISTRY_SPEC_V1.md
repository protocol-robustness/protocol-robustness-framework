# EXECUTION_REGISTRY_SPEC_V1

Status: Draft V1

## 1. Purpose

The Execution Registry defines all executable workloads known to the system.

An execution is any deterministic or semi-deterministic workload that may produce evidence.

Examples include:

- Unit tests
- Integration tests
- Benchmarks
- Invariant sweeps
- Replay validation
- Differential testing
- Fuzzing
- Simulation
- Future distributed execution jobs

The registry is the authoritative catalog of executable protocol artifacts.

------

## 2. Design Goals

### 2.1 Declarative

Execution definitions SHALL be represented as data.

Execution behavior SHALL NOT be encoded in shell scripts.

### 2.2 Auditable

Every execution SHALL produce an evidence node.

### 2.3 Extensible

New execution types SHALL be introducible without modifying existing registry entries.

### 2.4 Deterministic Registration

Registry loading SHALL produce identical results given identical inputs.

------

## 3. Registry Structure

```clojure
{:registry-version 1

 :executions [...]}
```

------

## 4. Execution Definition

Minimum definition:

```clojure
{:id :unit/hash-canonical

 :kind :test

 :runner :clojure-test

 :entry hash.canonical-test/run-all}
```

------

## 5. Required Fields

### :id

Unique execution identifier.

Requirements:

- keyword
- globally unique within registry
- immutable once published

Example:

```clojure
:id :bench/hash-throughput
```

------

### :kind

Semantic execution category.

Examples:

```clojure
:test
:benchmark
:replay
:simulation
:fuzz
:invariant-check
:differential
```

------

### :runner

Execution engine implementation.

Examples:

```clojure
:clojure-test
:benchmark
:replay
:fuzz
```

------

### :entry

Runnable target.

Examples:

```clojure
hash.test/run-all

benchmark.hash/run

replay.validation/run
```

------

## 6. Optional Fields

### :tags

Execution classification.

Example:

```clojure
#{:fast
  :core
  :critical}
```

------

### :description

Human-readable description.

------

### :execution-target

Execution environment.

Example:

```clojure
{:type :local}
```

Future examples:

```clojure
{:type :ci}
{:type :cluster
 :pool :benchmarks}
```

------

### :timeout-ms

Maximum permitted runtime.

Example:

```clojure
:timeout-ms 60000
```

------

### :depends-on

Execution dependencies.

Example:

```clojure
:depends-on
[:unit/hash-canonical
 :audit/invariant-sweep]
```

Dependency graph SHALL be acyclic.

------

### :evidence-policy

Reference to evidence policy.

Example:

```clojure
:evidence-policy :default
```

------

## 7. Registry Validation Rules

Startup SHALL fail if:

- duplicate execution ids exist
- dependency cycles exist
- unknown runners are referenced
- missing entry functions are referenced
- invalid evidence policies are referenced
- invalid execution targets are referenced

------

## 8. Execution Selection

Executions MAY be selected by:

### Explicit Id

```bash
run --id :bench/hash-throughput
```

### Tag

```bash
run --tag :core
```

### Kind

```bash
run --kind :benchmark
```

------

## 9. Scheduling

Scheduling SHALL be external to execution definitions.

Schedules select executions.

Example:

```clojure
{:schedule-id :nightly

 :select-tags #{:performance}

 :cron "0 2 * * *"}
```

This avoids coupling execution semantics to scheduling policy.

------

## 10. Evidence Requirement

All executions SHALL emit an evidence node.

Evidence content is governed by EVIDENCE_POLICY_SPEC_V1.

Execution success or failure SHALL NOT affect evidence node creation.

------

## 11. Future Compatibility

Future execution kinds SHALL NOT require schema migration provided they conform to:

```clojure
{:id ...
 :kind ...
 :runner ...
 :entry ...}
```

All additional semantics SHALL be runner-defined.