# CLAIM_DEFINITION_REGISTRY_SPEC_V1

Status: Draft V1

## 1. Purpose

The Claim Definition Registry is the authoritative catalog of claim semantics.

A claim definition specifies:

- what a claim means
- how it is evaluated
- which evidence it consumes
- which outputs it produces

Claims SHALL reference registered claim definitions.

Claim meaning SHALL NOT be inferred from claim identifiers alone.

------

## 2. Design Principles

### 2.1 Claims Have Stable Semantics

A claim identifier MUST NOT change meaning.

Semantic changes require a new claim definition.

------

### 2.2 Definitions Are Versioned

Claim definitions are immutable protocol artifacts.

Changes create new versions.

------

### 2.3 Definitions Are Hashable

Every claim definition SHALL possess a canonical hash.

Evidence nodes reference definition hashes rather than implementation code.

------

### 2.4 Evaluation Is Reproducible

Given:

- evidence
- claim definition
- registry version

claim evaluation SHOULD be reproducible.

------

## 3. Registry Structure

```clojure
{:registry-version 1

 :claim-definitions [...]}
```

------

## 4. Claim Definition Structure

```clojure
{:id :accounting-consistency

 :version 1

 :category :invariant

 :description "Accounting balances sum correctly"

 :inputs [...]

 :evaluation ...

 :outputs [...]

 :canonical-hash ...}
```

------

## 5. Required Fields

### :id

Stable claim identifier.

Example:

```clojure
:accounting-consistency
```

------

### :version

Monotonic version number.

Example:

```clojure
1
```

------

### :category

Examples:

```clojure
:invariant
:benchmark
:replay
:economic
:audit
:safety
```

------

### :inputs

Required evidence inputs.

Example:

```clojure
[:world-state
 :invariant-results]
```

------

### :evaluation

Evaluation definition.

V1 supports:

```clojure
{:type :code-reference
 :entry claims.accounting/evaluate}
```

Future versions MAY support:

```clojure
{:type :algebraic}
```

or

```clojure
{:type :declarative}
```

without registry redesign.

------

### :outputs

Expected outputs.

Example:

```clojure
{:type :boolean}
```

------

## 6. Dependency Support

Claims MAY depend on other claims.

Example:

```clojure
:depends-on
[:conservation-of-value
 :non-negative-balances]
```

Dependency graph SHALL be acyclic.

------

## 7. Registry Validation

Startup SHALL fail if:

- duplicate ids exist
- duplicate version numbers exist
- dependency cycles exist
- referenced claims missing
- invalid evaluation type
- missing evaluation entry

------

## 8. Hashing

Canonical hash SHALL exclude:

```clojure
:canonical-hash
```

Canonical hash SHALL include:

```clojure
:id
:version
:category
:inputs
:evaluation
:outputs
```

------

## 9. Compatibility Rules

Claim meaning is defined by:

```clojure
[id version canonical-hash]
```

Changing semantics without incrementing version is prohibited.

------

## 10. Audit Requirement

Given a claim and definition hash, an auditor SHALL be able to determine:

- intended meaning
- evaluation logic
- expected inputs
- expected outputs
- version history