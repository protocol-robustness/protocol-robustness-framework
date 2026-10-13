# INTENT_DSL_SPEC_V1

Status: Draft V1

## 1. Purpose

Intent DSL V1 defines the structured semantic intent object used when deriving hashes, projections, claims, evidence, and proportional allocations.

Intent answers:

> “What semantic operation is this artifact meant to represent?”

Intent is part of identity.

Two operations over the same world state MAY produce different hashes if their intents differ.

------

## 2. Design Principles

### 2.1 Intent Is Structured

Intent SHALL NOT be an arbitrary free-form map.

The core schema is strict and validated.

------

### 2.2 Intent Is Hashable

Intent SHALL be canonicalized and hashable.

Intent hashes MAY be embedded in evidence nodes, projection artifacts, claims, and attestations.

------

### 2.3 Intent Is Semantic

Intent describes the meaning of an operation, not runtime implementation details.

------

### 2.4 Intent Is Extensible

Intent supports controlled extension through `:intent/extensions`.

Extensions SHALL NOT alter the meaning of required core fields.

------

## 3. Canonical Structure

```clojure
{:intent/type ...
 :intent/version 1
 :intent/purpose ...
 :intent/scope ...
 :intent/inputs ...
 :intent/constraints ...
 :intent/output ...
 :intent/extensions {...}}
```

------

## 4. Required Fields

### :intent/type

Semantic operation type.

Examples:

```clojure
:pro-rata/allocation
:world/structure-projection
:evidence/node
:claim/evaluation
:attestation/signing
:benchmark/execution
:replay/verification
```

------

### :intent/version

Intent schema version.

For this spec:

```clojure
1
```

------

### :intent/purpose

Specific purpose of the operation.

Examples:

```clojure
:slash-obligation-allocation
:liquidity-shortfall-allocation
:yield-fulfillment-allocation
:world-structure-identity
:evidence-chain-node
:deterministic-replay-check
```

------

### :intent/scope

Defines the semantic boundary.

Example:

```clojure
{:protocol :sew
 :module :slashing
 :domain :economic-allocation}
```

Required keys:

```clojure
:protocol
:domain
```

Optional keys:

```clojure
:module
:scenario
:execution-id
:run-id
```

------

### :intent/inputs

Declared semantic input classes.

Example:

```clojure
#{:obligations
  :weights
  :caps
  :balances
  :eligible-participants}
```

This field does not contain raw input data.

It declares which input categories are semantically relevant.

------

### :intent/constraints

Declared constraints the operation must preserve.

Example:

```clojure
#{:conservation
  :non-negative
  :allocation-completeness
  :rounding-bounded
  :ordering-independent}
```

------

### :intent/output

Expected semantic output class.

Example:

```clojure
{:type :allocation-vector
 :unit :wei
 :rounding :largest-remainder}
```

------

## 5. Optional Field: :intent/extensions

Extensions provide controlled flexibility.

Example:

```clojure
{:intent/extensions
 {:sew/slash-kind :fraud
  :sew/epoch 42}}
```

Rules:

- MUST be a map
- keys SHOULD be namespaced
- values MUST be canonical-safe
- extensions MUST NOT override core fields
- extensions MUST NOT change interpretation of required fields
- extensions MAY affect hashes

------

## 6. Pro-Rata Intent Example

```clojure
{:intent/type :pro-rata/allocation
 :intent/version 1
 :intent/purpose :slash-obligation-allocation
 :intent/scope {:protocol :sew
                :module :slashing
                :domain :economic-allocation}
 :intent/inputs #{:obligations
                  :weights
                  :caps
                  :balances
                  :eligible-participants}
 :intent/constraints #{:conservation
                       :non-negative
                       :allocation-completeness
                       :rounding-bounded
                       :ordering-independent}
 :intent/output {:type :allocation-vector
                 :unit :wei
                 :rounding :largest-remainder}
 :intent/extensions {:sew/slash-kind :fraud}}
```

------

## 7. World Structure Projection Intent Example

```clojure
{:intent/type :world/structure-projection
 :intent/version 1
 :intent/purpose :world-structure-identity
 :intent/scope {:protocol :sew
                :domain :world-identity}
 :intent/inputs #{:world-state}
 :intent/constraints #{:deterministic
                       :canonical-safe
                       :ordering-independent}
 :intent/output {:type :structure-view}}
```

------

## 8. Validation Rules

Intent validation SHALL fail if:

- required fields are missing
- `:intent/version` is not `1`
- `:intent/type` is not a keyword
- `:intent/purpose` is not a keyword
- `:intent/scope` is missing `:protocol`
- `:intent/scope` is missing `:domain`
- `:intent/inputs` is not a set of keywords
- `:intent/constraints` is not a set of keywords
- `:intent/output` is not a map
- `:intent/extensions` is present but not a map
- extension keys are unqualified where qualification is required
- any value is not canonical-safe

------

## 9. Hashing Rules

Intent hash SHALL be computed over the canonicalized intent object.

Hashing SHALL include:

```clojure
:intent/type
:intent/version
:intent/purpose
:intent/scope
:intent/inputs
:intent/constraints
:intent/output
:intent/extensions
```

Hashing SHALL exclude:

- runtime metadata
- timestamps
- generated ids
- cached hashes

The intent hash domain tag SHALL be:

```text
INTENT_DSL_V1
```

------

## 10. Relationship to `hash-with-intent`

`hash-with-intent` SHALL include the canonical intent object, not merely an intent keyword.

Preferred structure:

```clojure
(hash-with-intent data intent)
```

Where:

```clojure
intent = valid INTENT_DSL_V1 object
```

The resulting hash commits to both:

- data
- semantic intent

------

## 11. Relationship to Projection-Based Pro-Rata

Projection-based pro-rata allocation SHALL use intent to define:

- eligible input classes
- constraints
- output shape
- rounding semantics
- allocation purpose

A pro-rata projection without intent is invalid.

------

## 12. Compatibility Rules

Changing any core field changes semantic identity.

Changing `:intent/extensions` MAY change semantic identity.

Changing runtime implementation without changing intent is permitted only if semantics remain identical.

Semantic changes require either:

- new `:intent/purpose`
- new `:intent/type`
- new `:intent/version`
- explicit extension change

------

## 13. Audit Requirement

Given an intent object, an auditor SHALL be able to determine:

- what operation was intended
- what semantic inputs were relevant
- what constraints were expected
- what output class was expected
- whether the intent was valid under this spec