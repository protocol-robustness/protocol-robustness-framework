# Intent Registry

The Intent Registry defines the canonical hash intents used across the framework.

It is not a documentation-only list. It is integrated into runtime startup validation, artifact generation, evidence construction, claim evaluation, attestation verification, and protocol integration packages.

The registry exists to make every hash answer the question:

> “What identity domain is this hash committing to?”

Without an explicit intent, two structurally similar values from different semantic domains could accidentally share hash semantics. That would weaken evidence integrity, replay verification, registry validation, and forensic review.

## Goals

The intent registry provides:

- a stable list of supported hash intents
- a domain tag for each intent
- validation rules for each intent
- projection requirements before hashing
- startup checks against the Identity Algebra invariants
- a common contract for framework core and protocol integrations

## Non-goals

The intent registry does not define protocol-specific business logic.

It should not know about Sew escrows, resolver slashing, yield modules, dispute flows, stablecoin ratings, or any other protocol-specific concept except through generic, registered intent domains.

Protocol-specific integrations may register additional intents, but they must do so through the same validation contract as framework intents.

## Integration requirement

The intent registry is integrated into:

1. canonical hashing (`hash-with-intent` in `resolver-sim.hash.canonical`)
2. evidence node generation
3. artifact registry construction
4. claim definition registry validation
5. attestor registry validation
6. execution registry validation
7. replay verification
8. protocol integration startup
9. project artifact validation
10. CI validation gates

A hash intent is not considered valid merely because it appears in code. It must be registered, validated, and accepted at startup.

## Core rule

Every framework hash must declare an intent.

Invalid:

```clojure
(hash-value data)
```

Valid:

```clojure
(hash-with-intent {:hash/intent :evidence-node} data)
```

## Source of truth

The intent registry is defined in `resolver_sim.hash.canonical/hash-intents` and validated at namespace load by `validate-registry!`. Startup hard-fails if any intent contract is invalid.

The passive intent registry (`resolver-sim.definitions.passive-registries/intent-registry`) wraps `hash-intents` into the `INTENT_REGISTRY_SPEC_V1` format for cross-registry validation.

## Registry entry shape

Each entry in `hash-intents` is a contract map with the following required fields:

```clojure
{:intent/name          :evidence-node          ;; keyword, must match the map key
 :intent/domain-tag    "EVIDENCE_NODE_V1"      ;; string, must be in domain-tags
 :intent/description   "Canonical identity of an execution evidence node"  ;; string
 :intent/includes      #{:schema-version ...}  ;; set of keys included in the projection
 :intent/excludes      #{:node-id ...}         ;; set of keys excluded from the projection
 :intent/projection-fn project-evidence-node   ;; fn that produces canonical-safe data
 :intent/version       1}                      ;; positive integer
```

Every field is validated by `validate-registry!`:

| Field | Type | Validation |
|---|---|---|
| `:intent/name` | keyword | Must match the key in the `hash-intents` map |
| `:intent/domain-tag` | string | Must be registered in `domain-tags` (globally unique across all intents) |
| `:intent/description` | string | Free-text description of the intent's semantic domain |
| `:intent/includes` | set | Keys included in the hash projection |
| `:intent/excludes` | set | Keys excluded from the hash projection |
| `:intent/projection-fn` | fn | Must produce deterministic, canonical-safe values (validated at startup with a sample input) |
| `:intent/version` | pos? int | Monotonic version for the intent contract |

## Registered intents

As of the current codebase (22 intents):

| Intent key | Domain tag | Purpose |
|---|---|---|
| `:world-structure` | `WORLD_STATE_V1` | Structural identity of system state |
| `:evidence-record` | `EVIDENCE_RECORD_V1` | Content identity of an evidence record |
| `:evidence-content` | `EVIDENCE_CONTENT_V1` | JSON-round-trippable content hash |
| `:evidence-chain` | `EVIDENCE_CHAIN_V1` | Chain linking structure for audit trails |
| `:manifest` | `MANIFEST_V1` | Bundle manifest identity |
| `:bundle-root` | `BUNDLE_ROOT_V1` | Top-level benchmark commitment |
| `:registry` | `REGISTRY_V1` | Evidence registry commitment |
| `:provenance` | `PROVENANCE_V1` | Provenance lineage and verification |
| `:evm-projection` | `EVM_PROJECTION_V1` | EVM-compatible world subset |
| `:state-diff` | `STATE_DIFF_V1` | Structural diff state hash |
| `:params-manifest` | `PARAMS_MANIFEST_V1` | Parameter manifest for reproducibility |
| `:invariant-attestation` | `INVARIANT_ATTESTATION_V1` | Per-step invariant attestation |
| `:projection-evidence` | `PROJECTION_EVIDENCE_V1` | Projection hash for cross-system comparison |
| `:checkpoint-evidence` | `CHECKPOINT_EVIDENCE_V1` | Attestable checkpoint |
| `:benchmark-certification` | `BENCHMARK_CERTIFICATION_V1` | Benchmark run certification |
| `:intent-dsl` | `INTENT_DSL_V1` | INTENT_DSL_SPEC_V1 intent object |
| `:intent-registry-entry` | `INTENT_REGISTRY_ENTRY_V1` | One registered intent contract |
| `:intent-registry` | `INTENT_REGISTRY_V1` | Intent registry artifact |
| `:projection-definition` | `PROJECTION_DEFINITION_V1` | One projection definition |
| `:projection-definition-registry` | `PROJECTION_DEFINITION_REGISTRY_V1` | Projection definition registry |
| `:projection-artifact` | `PROJECTION_ARTIFACT_V1` | One projection artifact |
| `:claim-definition` | `CLAIM_DEFINITION` | One claim definition |
| `:attestor` | `ATTESTOR` | One attestor registry entry |
| `:evidence-node` | `EVIDENCE_NODE_V1` | Execution evidence node |
| `:decision-evidence` | `DECISION_EVIDENCE_V1` | Decision with alternatives |
| `:invariant-failure` | `INVARIANT_FAILURE_V1` | Invariant check failure |
| `:startup-validation` | `STARTUP_VALIDATION_V1` | Startup registry validation evidence |

## Adding a new intent

1. Add the entry to `def hash-intents` in `resolver_sim.hash.canonical`
2. Add the domain tag string to `def domain-tags`
3. Add a corresponding entry to `intent-definitions` in `resolver-sim.definitions.passive-registries`
4. Add a projection function (or use `project-identity` if no transformation is needed)
5. Startup validation (`validate-registry!`) will enforce the contract automatically

## Direct `domain-hash` vs registered intents

Subsystem code may call `(domain-hash <domain-tag> <projected-body>)` directly for
internal composition and content-addressing. This is supported and cheap, but it
bypasses the intent layer: no exclusion/constraint enforcement, no registry
validation of the projection, and no `intent-hash=` comparison.

The governed path is a registered intent:

- every domain tag SHOULD have a corresponding entry in `hash-intents`;
- `validate-registry!` enforces the reverse direction (every intent's domain tag
  must exist in `domain-tags`) but does NOT require every domain tag to have an
  intent — orphan tags are a governance smell;
- when a projection is shared between a direct `domain-hash` call site and an
  intent, the projection MUST live in `resolver-sim.hash.canonical` (single
  source of truth) so the two paths cannot drift.

The bounty / with-bounty domains follow this pattern: the projections live in
`resolver-sim.hash.canonical` (`project-bounty-payable`,
`project-with-bounty-policy`, etc.), the economics namespaces delegate to them,
and all eleven bounty domain tags are registered as intents.
