# PROJECTION_DEFINITION_REGISTRY_SPEC_V1

Status: Draft V1

## 1. Purpose

The Projection Definition Registry is the authoritative catalog of projection semantics.

A projection definition specifies:

- what source state is projected
- which semantic intent applies
- which fields are included
- which fields are excluded
- which transformations are allowed
- which output shape is expected
- which invariants or claims should hold

Projection artifacts SHALL reference registered projection definitions.

Projection meaning SHALL NOT be inferred from implementation code alone.

------

## 2. Design Principles

### 2.1 Projections Are Semantic

A projection is an intentional view of state.

It is not a data-cleaning pass.

------

### 2.2 Projection Definitions Are Stable

A projection definition SHALL NOT change meaning after publication.

Semantic changes require a new version.

------

### 2.3 Projection Definitions Are Hashable

Every projection definition SHALL have a canonical hash.

Projection artifacts SHALL reference this hash.

------

### 2.4 Projection Definitions Are Replayable

Given:

- source state
- intent
- projection definition

an independent verifier SHOULD be able to reproduce the projection artifact.

------

## 3. Registry Structure

```clojure
{:registry-version 1
 :projection-definitions [...]}
```

------

## 4. Projection Definition Structure

```clojure
{:id :projection/world-structure
 :version 1
 :projection-type :world-structure

 :intent-types #{:world/structure-projection}
 :intent-purposes #{:world-structure-identity}

 :source {:type :world-state}

 :include-paths [...]
 :exclude-paths [...]

 :transforms [...]

 :output {:type :structure-view}

 :claims [...]

 :canonical-hash ...}
```

------

## 5. Required Fields

### :id

Stable projection definition identifier.

Example:

```clojure
:projection/pro-rata-slash-obligation
```

------

### :version

Monotonic version number.

Example:

```clojure
1
```

------

### :projection-type

Projection category.

Examples:

```clojure
:world-structure
:pro-rata-allocation
:accounting-view
:risk-view
:benchmark-view
```

------

### :intent-types

Allowed intent types.

Example:

```clojure
#{:pro-rata/allocation}
```

------

### :intent-purposes

Allowed intent purposes.

Example:

```clojure
#{:slash-obligation-allocation}
```

------

### :source

Source object type.

Example:

```clojure
{:type :world-state}
```

------

### :output

Expected output shape.

Example:

```clojure
{:type :allocation-frame
 :required-keys #{:participants
                  :weights
                  :constraints}}
```

------

## 6. Include and Exclude Paths

Projection definitions SHOULD explicitly define included and excluded paths.

Example:

```clojure
:include-paths
[[:balances]
 [:obligations]
 [:resolver-states]
 [:risk-config]
 [:accounting]]
```

Example:

```clojure
:exclude-paths
[[:runtime]
 [:telemetry]
 [:debug]
 [:yield-modules :* :ops]]
```

Included paths define semantic state.

Excluded paths define non-semantic or runtime infrastructure.

------

## 7. Transforms

Transforms define allowed canonical-safe conversions.

Example:

```clojure
:transforms
[{:from :instant
  :to :iso-8601-string}

 {:from :set
  :to :sorted-vector}

 {:from :function
  :to {:type :structured-marker
       :value {:type :fn}}}]
```

Transforms SHALL be deterministic.

Transforms SHALL NOT depend on runtime identity.

------

## 8. Claims

Projection definitions MAY declare required or recommended claims.

Example:

```clojure
:claims
[{:claim-id :projection-deterministic
  :required? true}

 {:claim-id :projection-canonical-safe
  :required? true}

 {:claim-id :allocation-complete
  :required? true}

 {:claim-id :non-negative
  :required? true}

 {:claim-id :conservation
  :required? true}

 {:claim-id :rounding-bounded
  :required? true}

 {:claim-id :ordering-independent
  :required? true}]
```

------

## 9. Pro-Rata Projection Definition

A pro-rata projection definition SHALL define:

- participant extraction
- eligibility extraction
- weight extraction
- cap extraction, if applicable
- total obligation extraction
- rounding policy
- constraint set

Example:

```clojure
{:id :projection/pro-rata-slash-obligation
 :version 1
 :projection-type :pro-rata-allocation

 :intent-types #{:pro-rata/allocation}
 :intent-purposes #{:slash-obligation-allocation}

 :source {:type :world-state}

 :include-paths
 [[:slash-obligations]
  [:resolver-states]
  [:balances]
  [:staking]
  [:risk-config]
  [:accounting]]

 :exclude-paths
 [[:runtime]
  [:debug]
  [:telemetry]
  [:yield-modules :* :ops]]

 :transforms
 [{:from :set :to :sorted-vector}
  {:from :instant :to :iso-8601-string}
  {:from :function :to {:type :structured-marker
                        :value {:type :fn}}}]

 :output {:type :allocation-frame
          :unit :wei
          :rounding :largest-remainder
          :required-keys #{:participants
                           :eligible-participants
                           :weights
                           :caps
                           :total-obligation
                           :constraints}}

 :claims
 [{:claim-id :projection-deterministic
   :required? true}
  {:claim-id :projection-canonical-safe
   :required? true}
  {:claim-id :allocation-complete
   :required? true}
  {:claim-id :non-negative
   :required? true}
  {:claim-id :conservation
   :required? true}
  {:claim-id :rounding-bounded
   :required? true}
  {:claim-id :ordering-independent
   :required? true}]}
```

------

## 10. Validation Rules

Projection definition registry validation SHALL fail if:

- duplicate `:id` exists
- duplicate `[:id :version]` exists
- required fields are missing
- projection type is unsupported
- intent types are not registered
- intent purposes are not registered
- include/exclude paths conflict
- transforms are unsupported
- output schema is malformed
- required claims are not registered
- canonical hash mismatch occurs

------

## 11. Hashing Rules

Projection definition hash SHALL include:

```clojure
:id
:version
:projection-type
:intent-types
:intent-purposes
:source
:include-paths
:exclude-paths
:transforms
:output
:claims
```

Projection definition hash SHALL exclude:

```clojure
:description
:deprecated?
:replaced-by
:canonical-hash
```

Domain tag:

```text
PROJECTION_DEFINITION_V1
```

Registry hash SHALL be computed over all projection definition hashes.

Domain tag:

```text
PROJECTION_DEFINITION_REGISTRY_V1
```

------

## 12. Relationship to Projection Artifacts

A projection artifact SHALL reference:

```clojure
:projection-definition-hash
```

Replay verification SHALL validate that the artifact was produced using a registered projection definition.

------

## 13. Relationship to `project-world-to-structure-view`

`project-world-to-structure-view` SHOULD become an implementation of a registered projection definition.

It SHALL NOT define projection semantics implicitly.

The projection definition defines semantics.

The function implements them.

------

## 14. Relationship to Pro-Rata

Projection-based pro-rata allocation SHALL consume a projection artifact, not raw world state.

Correct flow:

```text
world state
  → registered projection definition
  → projection artifact
  → allocation
  → claims
  → evidence node
```

Invalid flow:

```text
world state
  → direct procedural allocation
```

------

## 15. Startup Validation

At startup, the system SHALL validate:

- projection definition registry schema
- projection definition hashes
- referenced intents
- referenced claims
- supported transforms
- output schemas
- hash projections
- domain tags

Invalid projection definitions SHOULD hard-fail startup.

------

## 16. Audit Requirement

Given a projection artifact and projection definition registry, an auditor SHALL be able to determine:

- which projection definition was used
- whether that definition was registered
- whether the intent was compatible
- which source paths were included
- which source paths were excluded
- which transforms were permitted
- which claims were expected
- whether the projection hash verifies
