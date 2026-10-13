# EVIDENCE_NODE_SPEC_V1

Status: Draft V1

## 1. Purpose

An Evidence Node is an immutable, hash-addressed record describing the execution of a registered workload.

Evidence Nodes provide:

- auditability
- replayability
- provenance
- benchmark history
- invariant verification
- tamper evidence

Evidence Nodes are protocol artifacts.

They are not debugging logs.

------

## 2. Design Principles

### 2.1 Immutable

Evidence Nodes SHALL NOT be modified after creation.

Corrections SHALL be represented by new nodes.

------

### 2.2 Hash Addressed

Every node SHALL possess a canonical hash.

Node identity is derived from content.

Node identity SHALL NOT be assigned externally.

------

### 2.3 Self Describing

A node SHALL contain sufficient metadata for independent interpretation.

External systems SHALL NOT be required to determine node meaning.

------

### 2.4 Reproducible

Given:

- execution registry
- execution inputs
- source revision

an auditor SHOULD be able to reproduce execution results.

------

### 2.5 Evidence Is Mandatory

All executions SHALL emit evidence nodes.

Execution failure SHALL NOT suppress node creation.

------

## 3. Node Structure

Canonical structure:

```clojure
{:schema-version 1

 :node-id ...

 :node-hash ...

 :parent-hashes [...]

 :bootstrap-roots [...]

 :timestamp ...

 :execution ...

 :provenance ...

 :result ...

 :evidence ...

 :attestations [...]

 :extensions {...}

 :policy-output {...}}
```

Hashing note:

- `:node-hash` is computed from the canonical execution-node projection.
- The projection includes `:schema-version`, parent relationships, execution provenance,
  result summary, evidence hashes, attestations, and extensions.
- The projection excludes `:node-id`, `:node-hash`, `:timestamp`, and policy-filtered
  presentation output so metadata-only and visibility-only changes do not alter node integrity.

------

## 4. Required Fields

### :schema-version

Evidence node schema version.

Example:

```clojure
1
```

------

### :node-id

Unique identifier.

Requirements:

- UUID or equivalent
- globally unique

Example:

```clojure
"77a4cfc4-7a62-4f98-8d0d-a22f2c9a1f9d"
```

------

### :node-hash

Canonical hash of the node.

Requirements:

- computed after canonicalization
- deterministic
- content-addressed

Example:

```clojure
"sha256:..."
```

------

### :timestamp

Node creation timestamp.

Requirements:

- UTC
- ISO-8601

Example:

```clojure
"2026-06-23T20:15:00Z"
```

------

## 5. Parent Relationships

### :parent-hashes

References to predecessor evidence nodes.

Example:

```clojure
["sha256:abc..."
 "sha256:def..."]
```

Nodes MAY form:

- linear chains
- trees
- DAGs

Consumers SHALL NOT assume a linear chain.

DAG structure is the preferred model.

### :bootstrap-roots

Explicit parent hashes that are allowed to exist outside the local node set.

These are used for replay roots, imported chains, or external anchors.

------

## 6. Execution Section

The execution section describes what was executed.

Structure:

```clojure
{:execution-id ...
 :execution-kind ...
 :runner ...
 :registry-hash ...
 :policy-id ...
 :policy-hash ...}
```

------

### :execution-id

Registry identifier.

Example:

```clojure
:bench/hash-throughput
```

------

### :execution-kind

Example:

```clojure
:test
:benchmark
:replay
:simulation
:fuzz
```

------

### :runner

Execution engine.

Example:

```clojure
:benchmark
```

------

### :registry-hash

Canonical hash of the execution registry used.

This field is REQUIRED.

Purpose:

- execution provenance
- replay reproducibility
- registry evolution tracking

------

### :policy-hash

Canonical hash of the evidence policy used.

This field is REQUIRED.

Purpose:

- evidence interpretation
- auditability

------

## 7. Provenance Section

Captures execution context.

Structure:

```clojure
{:git ...
 :environment ...
 :inputs ...}
```

------

### :git

Repository state.

Example:

```clojure
{:commit "abc123"
 :dirty? false}
```

------

### :environment

Execution environment.

Example:

```clojure
{:os "linux"
 :jvm-version "21"
 :architecture "amd64"}
```

------

### :inputs

Input provenance.

Example:

```clojure
{:input-hash "sha256:..."
 :input-count 42}
```

Raw inputs SHOULD NOT be embedded unless explicitly configured.

Input hashes are preferred.

------

## 8. Result Section

Execution outcome.

Structure:

```clojure
{:status ...
 :started-at ...
 :finished-at ...
 :runtime-ms ...}
```

------

### :status

Allowed values:

```clojure
:pass
:fail
:error
:timeout
:cancelled
```

------

### :runtime-ms

Execution duration.

Required for completed executions.

------

## 9. Evidence Section

Contains evidence governed by evidence policy.

Structure:

```clojure
{:outputs ...
 :metrics ...
 :invariants ...
 :failures ...}
```

Subsections MAY be omitted according to policy.

------

### :outputs

Output provenance.

Example:

```clojure
{:output-hash "sha256:..."
 :artifact-count 7}
```

------

### :metrics

Runtime measurements.

Example:

```clojure
{:memory-bytes ...
 :cpu-time-ms ...}
```

------

### :invariants

Invariant evaluations.

Example:

```clojure
[{:id :accounting-consistency
  :status :pass}

 {:id :non-negative-balances
  :status :pass}]
```

Invariant evidence is preferred over binary success indicators.

------

### :failures

Failure evidence.

Example:

```clojure
{:type :unexpected
 :message "..."
 :stacktrace "..."}
```

Failure details MAY be filtered by evidence policy.

------

## 10. Benchmark Evidence

Benchmark executions MAY provide statistical evidence.

Example:

```clojure
{:samples 100
 :mean 42
 :median 41
 :p95 55
 :p99 62
 :stddev 4.2}
```

Statistical benchmark evidence SHALL NOT replace deterministic benchmark evidence.

Both MAY coexist.

------

## 11. Attestations

Optional third-party claims.

Structure:

```clojure
[{:attestor ...
  :claim ...
  :signature ...}]
```

Examples:

- CI attestation
- benchmark certification
- replay verification
- external audit confirmation

Attestations SHALL NOT affect node hash validity.

Attestations describe claims about the node.

------

## 12. Extensions

Reserved area for future expansion.

Structure:

```clojure
{:extensions {...}}
```

Consumers SHALL ignore unknown extension fields.

------

## 13. Canonical Hashing

Node hashes SHALL be generated from canonicalized node content.

Hash inputs SHALL exclude:

```clojure
:node-hash
```

Hash inputs SHALL include:

```clojure
:schema-version
:node-id
:parent-hashes
:timestamp
:execution
:provenance
:result
:evidence
```

Canonicalization rules SHALL be defined by CANONICAL_HASH_SPEC_V1.

------

## 14. Validation Rules

Node validation SHALL fail if:

- required fields are missing
- registry hash is absent
- policy hash is absent
- node hash mismatch occurs
- schema version is unsupported
- execution id is missing
- status is invalid

------

## 15. Replay Verification

Replay verification SHALL compare:

- registry hash
- policy hash
- input hash
- execution identifier

before execution comparison occurs.

Replay validity SHALL NOT be determined solely by matching outputs.

------

## 16. Audit Requirements

Given an evidence node, an auditor SHALL be able to determine:

- what executed
- which registry entry executed
- which evidence policy was used
- which inputs were consumed
- which outputs were produced
- which invariants were evaluated
- which failures occurred
- which code revision executed

without requiring access to execution logs.

------

## 17. Long-Term Compatibility

Evidence nodes are protocol artifacts.

Future schema versions SHALL preserve the ability to verify:

- node hashes
- registry provenance
- policy provenance
- execution provenance

for previously emitted nodes.