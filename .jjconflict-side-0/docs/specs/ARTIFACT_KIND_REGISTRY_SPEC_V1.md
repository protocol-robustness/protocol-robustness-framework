# ARTIFACT_KIND_REGISTRY_SPEC_V1

Status: Draft V1

## 1. Purpose

Defines the mapping from content-addressed artifact kinds to their runtime hash intents. Every artifact kind that is content-addressed SHALL map to exactly one registered hash intent. This registry is the single source of truth for verifying that every content-addressed artifact has a canonical hash intent backing it.

Without this mapping, artifact kinds could be hashed under inconsistent or undocumented intents, making cross-system verification ambiguous and audit trails unreliable.

## 2. Design Principles

### 2.1 Every Content-Addressed Artifact Kind Has a Hash Intent

Every artifact kind whose identity is determined by content-addressing SHALL map to a registered hash intent in `resolver-sim.hash.canonical/hash-intents`.

### 2.2 One Intent Per Kind

Each artifact kind SHALL map to exactly one hash intent. No two artifact kinds SHALL share a hash intent.

### 2.3 Intent Is Stable

An artifact kind's hash intent SHALL NOT change meaning. Semantic changes require a new intent version.

### 2.4 Intent Is Enforced at Startup

The artifact-kind-to-intent mapping SHALL be verifiable at startup. The passive artifact-kind registry SHALL be validated by `validate-passive-registries!`.

## 3. Registry: `artifact-kind-mapping`

The mapping is defined as a map from artifact-kind keywords to hash intent keywords:

```clojure
{:evidence-node            :evidence-node
 :claim-definition          :claim-definition
 :claim-result              :claim-result
 :attestation               :attestation
 :artifact-registry         :registry
 :execution-definition      :execution-definition
 ;; Transition evidence kinds (hashed under :evidence-record)
 :transition                :evidence-record
 :transition-error          :evidence-record
 ;; Layered evidence kinds (independent intents)
 :decision-evidence         :decision-evidence
 :invariant-attestation     :invariant-attestation
 :projection-evidence       :projection-evidence
 :invariant-failure         :invariant-failure
 :checkpoint-evidence       :checkpoint-evidence
 :startup-validation        :startup-validation}
```

### 3.1 Full Mapping Table

| Artifact kind | Hash intent | Domain tag | Description |
|---|---|---|---|
| `:evidence-node` | `:evidence-node` | `EVIDENCE_NODE_V1` | Canonical identity of an execution evidence node |
| `:claim-definition` | `:claim-definition` | `CLAIM_DEFINITION` | Canonical identity of one claim definition |
| `:claim-result` | `:claim-result` | `CLAIM_RESULT_V1` | Canonical identity of a claim evaluation result |
| `:attestation` | `:attestation` | `ATTESTATION_V1` | Canonical identity of an attestation record |
| `:artifact-registry` | `:registry` | `REGISTRY_V1` | Evidence registry commitment for artifact catalog |
| `:execution-definition` | `:execution-definition` | `EXECUTION_DEFINITION_V1` | Canonical identity of an execution registry definition entry |
| `:transition` | `:evidence-record` | `EVIDENCE_RECORD_V1` | Content identity of an individual evidence record |
| `:transition-error` | `:evidence-record` | `EVIDENCE_RECORD_V1` | Content identity of an individual evidence record |
| `:decision-evidence` | `:decision-evidence` | `DECISION_EVIDENCE_V1` | Structured record of a decision with alternatives and selection |
| `:invariant-attestation` | `:invariant-attestation` | `INVARIANT_ATTESTATION_V1` | Per-step invariant attestation |
| `:projection-evidence` | `:projection-evidence` | `PROJECTION_EVIDENCE_V1` | Projection hash paired with world hash |
| `:invariant-failure` | `:invariant-failure` | `INVARIANT_FAILURE_V1` | Evidence recorded when an invariant check fails |
| `:checkpoint-evidence` | `:checkpoint-evidence` | `CHECKPOINT_EVIDENCE_V1` | Attestable checkpoint with world hash and chain position |
| `:startup-validation` | `:startup-validation` | `STARTUP_VALIDATION_V1` | Startup registry validation evidence |
| `:action` | `:action` | `ACTION_V1` | Canonical identity of a normalized action payload |
| `:action-at` | `:action-at` | `ACTION_AT_V1` | Canonical identity of an action occurrence at a specific execution point |
| `:projection-artifact` | `:projection-artifact` | `PROJECTION_ARTIFACT_V1` | Canonical identity of a projection artifact (ex-ante frame) |
| `:pro-rata-allocation-result` | `:pro-rata-allocation-result` | `PRO_RATA_ALLOCATION_RESULT_V1` | Canonical identity of a pro-rata allocation result artifact (ex-post outcome) |

## 4. Artifact Kind Entry Shape

Each artifact kind entry in the registry SHALL have these required fields:

```clojure
{:kind              :evidence-node
 :intent            :evidence-node
 :domain-tag        "EVIDENCE_NODE_V1"
 :content-addressed? true
 :description       "Canonical identity of an execution evidence node"}
```

### 4.1 `:kind`

Keyword identifying the artifact kind.

- Type: `keyword?`
- Required: yes

### 4.2 `:intent`

Keyword of the registered hash intent in `resolver-sim.hash.canonical/hash-intents`.

- Type: `keyword?`
- Required: yes
- Constraint: SHALL be a key in `hash-intents`

### 4.3 `:domain-tag`

Domain tag string of the target hash intent.

- Type: `string?`
- Required: yes
- Constraint: SHALL equal the target intent's `:intent/domain-tag`

### 4.4 `:content-addressed?`

Whether this artifact kind is content-addressed (identity derived from hash of content).

- Type: `boolean?`
- Required: yes

### 4.5 `:description`

Human-readable description of the artifact kind.

- Type: `string?`
- Required: yes

## 5. Validation Rules

### 5.1 Required Fields

Every artifact-kind registry entry SHALL contain all 5 required fields.

### 5.2 Intent Exists

`:intent` SHALL be a key in `resolver-sim.hash.canonical/hash-intents`. An unknown intent causes a validation hard-fail.

### 5.3 Domain Tag Matches

`:domain-tag` SHALL equal the target hash intent's `:intent/domain-tag`. A mismatch causes a validation hard-fail.

### 5.4 No Duplicate Domains

No two artifact kinds SHALL share the same `:domain-tag`. This enforces the one-intent-per-kind rule.

### 5.5 One Intent Per Kind

No two artifact kinds SHALL share the same `:intent`. Transition evidence kinds (`:transition`, `:transition-error`) that share `:evidence-record` are an explicit exception documented in §6.

## 6. Shared Intent Exceptions

### 6.1 Transition Evidence

`:transition` and `:transition-error` artifact kinds share the `:evidence-record` hash intent. This is permitted because both are evidence records that differ only in outcome status but share the same content identity semantics.

Exception documented-at: `ARTIFACT_KIND_REGISTRY_SPEC_V1.md §6.1`

## 7. Adding a New Artifact Kind

1. Determine whether the new kind needs its own hash intent or can reuse an existing one
2. If new intent is needed:
   a. Register a domain tag string in `domain-tags`
   b. Add the intent contract to `hash-intents` with a projection function that selects identity-critical fields
   c. Ensure the projection function is deterministic and produces canonical-safe output
3. Add a registry entry to the artifact-kind mapping
4. If the kind appears in the passive registry surface, add it to `artifact-kind-mapping` in `resolver-sim.definitions.passive-registries` with full validation entry

## 8. Relationship to `HASH_INTENT_REGISTRY_SPEC_V1`

`HASH_INTENT_REGISTRY_SPEC_V1` defines the executable hash intent contracts — domain tags, projection functions, includes/excludes, and runtime resolution via `resolve-intent`.

`ARTIFACT_KIND_REGISTRY_SPEC_V1` (this document) defines the mapping from artifact kinds to those intents, ensuring every content-addressed artifact has a verifiable hash intent.

Both registries SHALL pass validation at startup. A failure in either prevents system start.

## 9. Relationship to `INTENT_REGISTRY_SPEC_V1`

`INTENT_REGISTRY_SPEC_V1` defines the passive intent definitions registry — semantic intent types, purposes, scopes, inputs, constraints, and output shapes.

The artifact-kind mapping does not duplicate passive intent definitions. It cross-references them via `:intent`. An artifact kind's hash intent SHALL have a corresponding passive intent definition if the runtime intent projects identity-critical fields.

## 10. Example: Complete Entry

```clojure
{:kind              :claim-result
 :intent            :claim-result
 :domain-tag        "CLAIM_RESULT_V1"
 :content-addressed? true
 :description       "Canonical identity of a claim evaluation result"}
```

## 11. Audit Requirement

Given an artifact kind and its hash, an auditor SHALL be able to determine:

- which artifact kind produced the hash
- which hash intent was used
- which domain tag was prepended
- which projection function selected the identity-critical fields
- whether the mapping was registered at the time of hashing
- whether the hash intent was version-stable at the time of hashing
