# INTENT_REGISTRY_SPEC_V1

Status: Draft V1

## 0. Scope

This spec defines the **passive intent definitions registry** in `resolver-sim.definitions.passive-registries`. This registry catalogs semantic intent types, purposes, scopes, inputs, constraints, and output shapes. It is the governance surface for what intents exist and what they mean.

**This spec does NOT define the executable hash intent registry.** Executable hash semantics — domain tags, projection functions, includes/excludes, and runtime `hash-with-intent` resolution — are defined by [`HASH_INTENT_REGISTRY_SPEC_V1`](./HASH_INTENT_REGISTRY_SPEC_V1.md).

Passive intent definitions may reference, classify, or describe runtime hash intents, but the validation rules and entry shapes are different. See §14 for the relationship between the two registries.

## 1. Purpose

The passive Intent Registry is the authoritative catalog of allowed semantic intents for the system.

It defines:

- valid intent types
- valid intent purposes
- required input classes
- required constraints
- allowed output shapes
- compatible scopes
- validation rules

Intent objects SHALL be validated against this registry at startup and before use in projection, evidence generation, claim evaluation, or attestation signing.

------

## 2. Design Principles

### 2.1 Intent Is Registered

Intent meaning SHALL NOT be inferred from arbitrary keywords.

All valid `:intent/type` and `:intent/purpose` pairs SHALL be registered.

------

### 2.2 Intent Is Stable

A registered intent definition SHALL NOT change meaning.

Semantic changes require a new intent definition version.

------

### 2.3 Intent Is Hashable

Intent registry entries SHALL have canonical hashes.

------

### 2.4 Intent Is Enforced

Invalid intent objects SHALL NOT be accepted by:

- projection functions
- evidence node generation
- claim evaluation
- attestation signing

------

## 3. Registry Structure

```clojure
{:registry-version 1
 :intents [...]}
```

------

## 4. Intent Definition Structure

```clojure
{:id :pro-rata/slash-obligation-allocation
 :version 1

 :intent/type :pro-rata/allocation
 :intent/purpose :slash-obligation-allocation

 :scope {:protocols #{:sew}
         :domains #{:economic-allocation}
         :modules #{:slashing}}

 :inputs #{:obligations
           :weights
           :caps
           :balances
           :eligible-participants}

 :constraints #{:conservation
                :non-negative
                :allocation-completeness
                :rounding-bounded
                :ordering-independent}

 :output {:type :allocation-vector
          :unit :wei
          :rounding :largest-remainder}

 :extensions-policy {:allowed? true
                     :require-namespaced-keys? true}

 :description "Allocates slash obligations proportionally across eligible participants."}
```

------

## 5. Required Fields

### :id

Stable registry identifier.

Example:

```clojure
:pro-rata/slash-obligation-allocation
```

------

### :version

Monotonic version number.

Example:

```clojure
1
```

------

### :intent/type

Registered semantic operation type.

Example:

```clojure
:pro-rata/allocation
```

------

### :intent/purpose

Registered purpose.

Example:

```clojure
:slash-obligation-allocation
```

------

### :scope

Allowed semantic boundaries.

Required keys:

```clojure
:protocols
:domains
```

Optional keys:

```clojure
:modules
:scenarios
:execution-kinds
```

------

### :inputs

Required semantic input classes.

Example:

```clojure
#{:obligations :weights :caps :balances}
```

------

### :constraints

Required semantic constraints.

Example:

```clojure
#{:conservation :non-negative}
```

------

### :output

Expected output schema.

Example:

```clojure
{:type :allocation-vector
 :unit :wei
 :rounding :largest-remainder}
```

------

## 6. Optional Fields

### :extensions-policy

Controls `:intent/extensions`.

Example:

```clojure
{:allowed? true
 :require-namespaced-keys? true}
```

If omitted:

```clojure
{:allowed? false}
```

------

### :description

Human-readable description.

------

### :deprecated?

Marks a registered intent as deprecated.

Deprecated intents MAY validate for historical artifacts but SHOULD NOT be used for new execution.

------

### :replaced-by

Replacement intent id.

Example:

```clojure
:replaced-by :pro-rata/slash-obligation-allocation-v2
```

------

## 7. Validation Rules

Intent registry validation SHALL fail if:

- duplicate `:id` exists
- duplicate `[:intent/type :intent/purpose :version]` exists
- required fields are missing
- `:inputs` is empty
- `:constraints` is empty
- `:output` is missing `:type`
- extension policy is malformed
- deprecated intent has invalid replacement reference

------

## 8. Intent Object Validation

An INTENT_DSL_V1 object validates against the registry if:

- its `:intent/type` matches a registered definition
- its `:intent/purpose` matches the same definition
- its `:intent/version` is supported
- its `:intent/scope` is compatible with the registered scope
- its `:intent/inputs` is equal to or a permitted subset/superset as defined by policy
- its `:intent/constraints` includes all required constraints
- its `:intent/output` conforms to registered output definition
- its `:intent/extensions` conforms to extension policy

------

## 9. Scope Compatibility

Example valid object:

```clojure
{:intent/scope {:protocol :sew
                :module :slashing
                :domain :economic-allocation}}
```

against registry entry:

```clojure
{:scope {:protocols #{:sew}
         :modules #{:slashing}
         :domains #{:economic-allocation}}}
```

Validation SHALL fail if protocol, module, or domain is outside registered scope.

------

## 10. Input Compatibility

Default rule:

```clojure
:intent/inputs == registered :inputs
```

Registry entries MAY specify:

```clojure
:input-policy :exact
```

or:

```clojure
:input-policy :allow-superset
```

or:

```clojure
:input-policy :allow-subset
```

Default:

```clojure
:exact
```

------

## 11. Constraint Compatibility

Default rule:

```clojure
registered constraints ⊆ intent constraints
```

Intent objects MAY include additional constraints only if:

```clojure
:constraint-policy :allow-superset
```

Default:

```clojure
:require-all
```

------

## 12. Hashing Rules

Intent definition hash SHALL include:

```clojure
:id
:version
:intent/type
:intent/purpose
:scope
:inputs
:constraints
:output
:extensions-policy
:input-policy
:constraint-policy
```

Intent definition hash SHALL exclude:

```clojure
:description
:deprecated?
:replaced-by
:canonical-hash
```

Domain tag:

```text
INTENT_REGISTRY_ENTRY_V1
```

Registry hash SHALL be computed over all intent definition hashes.

Domain tag:

```text
INTENT_REGISTRY_V1
```

------

## 13. Startup Validation

At startup, the system SHALL validate:

- registry schema
- duplicate definitions
- intent hash correctness
- all registered intent definitions canonicalize successfully
- all intent definitions satisfy Identity Algebra invariants
- all domain tags are registered
- all hash projections are available

Startup SHOULD fail hard on invalid registry state.

Development warning-only mode MAY exist but MUST be explicit.

------

## 14. Relationship to `HASH_INTENT_REGISTRY_SPEC_V1`

The passive intent definitions registry (this spec) and the executable hash intent registry ([`HASH_INTENT_REGISTRY_SPEC_V1`](./HASH_INTENT_REGISTRY_SPEC_V1.md)) serve different purposes:

| Aspect | This spec | HASH_INTENT_REGISTRY_SPEC_V1 |
|---|---|---|
| Location | `passive_registries.clj` (intent-definitions) | `canonical.clj` (hash-intents) |
| Entry shape | `:id`, `:version`, `:intent/type`, `:intent/purpose`, `:scope`, `:inputs`, `:constraints`, `:output` | `:intent/name`, `:intent/domain-tag`, `:intent/description`, `:intent/includes`, `:intent/excludes`, `:intent/projection-fn`, `:intent/version` |
| Purpose | Semantic governance — what intents exist and what they mean | Runtime hash execution — how to project and hash data for a given intent |
| Validated by | `validate-passive-registries!` (startup hard-fail) | `validate-registry!` (namespace-load hard-fail) |
| Used by | Projection definitions, claim evaluations, evidence policy | `hash-with-intent` callsites |

Both registries MUST pass validation at startup. A failure in either prevents system start.

`hash-with-intent` is defined by `HASH_INTENT_REGISTRY_SPEC_V1`. It resolves against the executable hash intent registry, not the passive intent definitions registry. The passive registry wraps the executable registry entries (`hash-projection-registry-definitions` in `passive_registries.clj`) so they appear in the passive validation surface.

------

## 15. Relationship to Projection-Based Pro-Rata

Projection-based pro-rata allocation SHALL use registered intents.

A pro-rata allocation without a registered intent is invalid.

The registry entry SHOULD define:

- required allocation inputs
- required conservation constraints
- rounding semantics
- output shape
- eligibility semantics

------

## 16. Example: Pro-Rata Slash Obligation Allocation

```clojure
{:id :pro-rata/slash-obligation-allocation
 :version 1
 :intent/type :pro-rata/allocation
 :intent/purpose :slash-obligation-allocation
 :scope {:protocols #{:sew}
         :domains #{:economic-allocation}
         :modules #{:slashing}}
 :inputs #{:obligations
           :weights
           :caps
           :balances
           :eligible-participants}
 :constraints #{:conservation
                :non-negative
                :allocation-completeness
                :rounding-bounded
                :ordering-independent}
 :output {:type :allocation-vector
          :unit :wei
          :rounding :largest-remainder}
 :input-policy :exact
 :constraint-policy :require-all
 :extensions-policy {:allowed? true
                     :require-namespaced-keys? true}}
```

------

## 17. Audit Requirement

Given an intent object and intent registry, an auditor SHALL be able to determine:

- whether intent was registered
- whether intent was valid at execution time
- which semantic operation was intended
- which inputs were relevant
- which constraints were required
- which output shape was expected
- whether extensions were permitted