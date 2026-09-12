# CLAIMS_SPEC_V1

Status: Draft V1

## 1. Purpose

Claims represent conclusions derived from evidence.

Claims are not observations.

Claims are assertions whose truth value is supported by evidence.

Examples:

- Accounting consistency holds
- Conservation of value holds
- Deterministic replay succeeded
- Benchmark regression detected
- Protocol invariant violated

Claims transform raw evidence into machine-verifiable conclusions.

------

## 2. Design Principles

### 2.1 Claims Are Derived

Claims SHALL be derived from evidence.

Claims SHALL NOT exist without supporting evidence.

------

### 2.2 Claims Are Reproducible

Given:

- evidence node
- registry version
- claim definition

an independent verifier SHOULD be able to recompute the claim.

------

### 2.3 Claims Are Immutable

Claims SHALL NOT be modified after publication.

Updated conclusions SHALL be represented as new claims.

------

### 2.4 Claims Are Explicit

Claims SHALL identify:

- what is being asserted
- how it was evaluated
- which evidence supports it

------

## 3. Claim Structure

Canonical structure:

```clojure
{:claim-id ...
 :claim-type ...
 :value ...
 :confidence ...
 :evidence-refs [...]
 :claim-definition-hash ...
 :metadata {...}}
```

------

## 4. Required Fields

### :claim-id

Unique claim identifier.

Example:

```clojure
:accounting-consistency
```

------

### :claim-type

Semantic category.

Examples:

```clojure
:invariant
:benchmark
:replay
:safety
:economic
:structural
:audit
```

------

### :value

Claim outcome.

Examples:

```clojure
true
false
42
:warning
:critical
```

Claims are not restricted to booleans.

------

### :evidence-refs

Supporting evidence references.

Example:

```clojure
["sha256:node1"
 "sha256:node2"]
```

At least one supporting evidence reference SHALL exist.

------

### :claim-definition-hash

Hash of claim definition used.

Purpose:

- reproducibility
- semantic stability
- version tracking

------

## 5. Optional Fields

### :confidence

Confidence level.

Examples:

```clojure
1.0
0.95
0.80
```

Deterministic claims SHOULD use:

```clojure
1.0
```

------

### :metadata

Claim-specific information.

Example:

```clojure
{:expected-value 100
 :observed-value 99}
```

------

## 6. Claim Categories

### Invariant Claims

Examples:

```clojure
:accounting-consistency
:conservation-of-value
:non-negative-balances
```

------

### Replay Claims

Examples:

```clojure
:deterministic-replay
:replay-output-match
```

------

### Benchmark Claims

Examples:

```clojure
:no-regression
:performance-improved
```

------

### Economic Claims

Examples:

```clojure
:supply-conserved
:redistribution-correct
```

------

### Audit Claims

Examples:

```clojure
:evidence-complete
:policy-compliant
```

------

## 7. Composite Claims

Claims MAY depend on other claims.

Example:

```clojure
{:claim-id :protocol-safe

 :depends-on
 [:accounting-consistency
  :conservation-of-value
  :deterministic-replay]}
```

Dependency graphs SHALL be acyclic.

------

## 8. Claim Evaluation

Claims SHOULD be evaluated using registered claim definitions.

Example:

```clojure
{:id :accounting-consistency

 :definition-hash "sha256:..."
}
```

Claim definitions are versioned protocol artifacts.

------

## 9. Claim Validation

Validation SHALL fail if:

- claim id missing
- evidence references missing
- claim definition hash missing
- unsupported claim type
- dependency cycle detected

------

## 10. Evidence Relationship

Evidence answers:

"What happened?"

Claims answer:

"What does it mean?"

Example:

Evidence:

```clojure
{:balance-delta 0}
```

Claim:

```clojure
{:claim-id :conservation-of-value
 :value true}
```

------

## 11. Long-Term Compatibility

Claim semantics SHALL be determined by:

- claim id
- claim definition hash

Claim meaning SHALL NOT depend on implementation details.

------

## 12. Audit Requirement

Given:

- claim
- claim definition
- supporting evidence

an auditor SHALL be able to independently verify claim correctness.