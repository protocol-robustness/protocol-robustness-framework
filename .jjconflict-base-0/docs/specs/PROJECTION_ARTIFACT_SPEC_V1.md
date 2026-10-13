# PROJECTION_ARTIFACT_SPEC_V1

Status: Draft V1

## 1. Purpose

A Projection Artifact is a canonical, deterministic representation of a semantic view of a larger state space.

A projection is not the world state itself.

A projection is an intentionally selected identity view derived from world state under a specific intent.

Examples:

- World structure projection
- Pro-rata allocation projection
- Resolver exposure projection
- Accounting projection
- Risk-state projection
- Benchmark comparison projection

Projection artifacts are protocol artifacts.

They are independently hashable, evidence-backed, and replayable.

------

## 2. Design Principles

### 2.1 Intent-Bound

Every projection SHALL be associated with a valid registered intent.

A projection without intent is invalid.

------

### 2.2 Deterministic

Given:

- world state
- projection definition
- intent

the resulting projection SHALL be identical.

------

### 2.3 Canonical

Projection artifacts SHALL contain only canonical-safe values.

Projection artifacts SHALL pass canonical validation.

------

### 2.4 Replayable

An independent verifier SHOULD be able to regenerate the projection from source inputs.

------

### 2.5 Semantic

Projection artifacts represent semantic structure.

They are not serialization artifacts.

------

## 3. Projection Artifact Structure

Canonical structure:

```clojure
{:schema-version 1

 :projection-id ...

 :projection-type ...

 :projection-version ...

 :intent ...

 :projection-definition-hash ...

 :source ...

 :projection ...

 :summary ...

 :claims [...]

 :metadata {...}

 :projection-hash ...}
```

------

## 4. Required Fields

### :schema-version

Projection artifact schema version.

Example:

```clojure
1
```

------

### :projection-id

Unique identifier.

Example:

```clojure
"proj-5e0d4d"
```

------

### :projection-type

Registered projection category.

Examples:

```clojure
:world-structure
:pro-rata-allocation
:accounting-view
:risk-view
:benchmark-view
```

------

### :projection-version

Projection definition version.

Example:

```clojure
1
```

------

## 5. Intent

Every projection SHALL embed a valid intent object.

Example:

```clojure
{:intent/type :pro-rata/allocation
 :intent/purpose :slash-obligation-allocation
 ...}
```

Intent SHALL validate against:

- INTENT_DSL_SPEC_V1
- INTENT_REGISTRY_SPEC_V1

------

## 6. Projection Definition

### :projection-definition-hash

Hash of the projection definition used.

Required.

Purpose:

- reproducibility
- semantic stability
- replay verification

Example:

```clojure
"sha256:abc123..."
```

Projection meaning SHALL be derived from:

```clojure
projection-definition-hash
```

not implementation code location.

------

## 7. Source Section

Describes origin of projection.

Structure:

```clojure
{:world-hash ...
 :input-hashes [...]
 :execution-id ...
 :evidence-node-hash ...}
```

------

### :world-hash

Hash of source world state.

Required when projection originates from a world.

------

### :input-hashes

Hashes of additional source artifacts.

Examples:

```clojure
allocation policies
constraint definitions
benchmark inputs
```

------

## 8. Projection Payload

### :projection

The canonical projected structure.

Example:

```clojure
{:participants [...]
 :weights [...]
 :caps [...]
 :total-obligation ...}
```

Projection contents depend on projection type.

Projection SHALL be canonical-safe.

Projection SHALL NOT contain:

- functions
- runtime handles
- mutable references

------

## 9. Summary Section

Human-readable summary.

Purpose:

- debugging
- evidence navigation
- dashboards

Example:

```clojure
{:participant-count 12
 :total-weight "1000000"
 :constraint-count 4}
```

Summary SHALL NOT affect projection semantics.

------

## 10. Claims

Projection artifacts MAY contain derived claims.

Example:

```clojure
[{:claim-id :allocation-complete
  :value true}

 {:claim-id :conservation
  :value true}]
```

Claims SHALL reference CLAIMS_SPEC_V1.

------

## 11. Metadata

Optional metadata.

Examples:

```clojure
{:generated-at ...
 :generator-version ...}
```

Metadata SHALL NOT affect projection identity.

------

## 12. Projection Hash

### :projection-hash

Canonical hash of projection artifact.

Required.

Projection hash SHALL exclude:

```clojure
:projection-hash
:summary
:metadata
```

Projection hash SHALL include:

```clojure
:schema-version
:projection-type
:projection-version
:intent
:projection-definition-hash
:source
:projection
```

------

## 13. Projection Types

### World Structure Projection

Purpose:

```text
World identity and structural comparison.
```

Output:

```clojure
world structure view
```

------

### Pro-Rata Allocation Projection

Purpose:

```text
Allocation reasoning.
```

Output:

```clojure
{:participants ...
 :weights ...
 :constraints ...
}
```

This projection SHALL precede allocation execution.

------

### Accounting Projection

Purpose:

```text
Financial consistency verification.
```

Output:

```clojure
balances
liabilities
obligations
```

------

### Risk Projection

Purpose:

```text
Exposure and solvency analysis.
```

------

## 14. Pro-Rata Requirements

For projection type:

```clojure
:pro-rata-allocation
```

projection SHALL include:

```clojure
:participants
:weights
:constraints
```

and SHOULD include:

```clojure
:caps
:eligibility-rules
```

Claims SHOULD include:

```clojure
:allocation-complete
:non-negative
:conservation
```

------

## 15. Replay Verification

Replay verification SHALL compare:

- intent
- projection definition hash
- source hashes
- projection hash

before comparing downstream allocations.

Projection equivalence is stronger than output equivalence.

------

## 16. Evidence Relationship

Evidence Node:

```text
What happened?
```

Projection Artifact:

```text
What structure was used?
```

Claim:

```text
What conclusions follow?
```

Attestation:

```text
Who verified it?
```

------

## 17. Startup Validation

Projection artifact validation SHALL fail if:

- intent invalid
- projection hash mismatch
- unsupported projection type
- missing projection definition hash
- non-canonical values present
- required source hashes missing

------

## 18. Audit Requirement

Given a projection artifact, an auditor SHALL be able to determine:

- which intent produced it
- which world state it derives from
- which projection definition was used
- which structure was extracted
- which claims were derived
- whether the projection hash verifies

without requiring execution logs.

------

## 19. Long-Term Compatibility

Projection artifacts are protocol artifacts.

Future schema versions SHALL preserve the ability to verify:

- projection hashes
- source provenance
- intent validity
- projection semantics

for historical artifacts.