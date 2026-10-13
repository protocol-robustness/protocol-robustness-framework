# Proposal: Abstracting Identity and Liveness in the Replay Engine

## 1. Problem Statement
The current simulation kernel (`replay.clj`) and the `SimulationAdapter` protocol assume a 1:1 relationship between a creation event and a single identifier (`workflow-id`). This manifests in three key areas:

1.  **`created-id`**: The protocol returns only one ID per successful creation action.
2.  **`open-entities`**: The engine checks for scenario completeness by expecting a flat list of unresolved IDs.
3.  **Aliasing**: The `save-id-as` mechanism assumes it is capturing the one-and-only ID produced by an action.

As the protocol evolves to include shared resources (e.g., Aave Vaults shared across multiple escrows), this model becomes "lossy." An action might create multiple resources, and "scenario completeness" might depend on different resource types being finalized.

## 2. Proposed Solution: Resource-based Identity

We propose transitioning from an ID-centric model to a **Resource-based Handle** model.

### 2.1 Protocol Changes (`SimulationAdapter`)

We propose deprecating the ID-specific methods in favor of structured resource maps.

| Deprecated Method | Replacement | Description |
| :--- | :--- | :--- |
| `(created-id [adapter action extra])` | `(created-resources [adapter action extra])` | Returns a map of resources created (e.g., `{:workflow-id 0, :vault-id "v1"}`). |
| `(open-entities [adapter world])` | `(active-resources [adapter world])` | Returns a map of sets categorizing active resources (e.g., `{:escrows #{0}, :vaults #{"v1"}}`). |

### 2.2 Replay Engine Enhancements (`replay.clj`)

#### Multi-Resource Aliasing
The `save-id-as` feature in scenarios should be enhanced to allow targeted aliasing.

**Legacy (Backward Compatible):**
```json
{ "action": "create_escrow", "save-id-as": "wf1" }
// Engine saves the first (or only) resource found in created-resources.
```

**Proposed (Targeted):**
```json
{ 
  "action": "create_escrow", 
  "save-resource-as": { "workflow-id": "wf1", "vault-id": "v1" } 
}
```

#### Structured Liveness Checks
The replay engine currently halts if `open-entities` is non-empty. In the new model, it will halt if any set in the `active-resources` map is non-empty.

**New Halt Reason Detail:**
```clojure
{:halt-reason :active-resources-at-end
 :detail {:active-resources {:escrows [] :vaults ["v1"]}}}
```
This clarifies that while all escrows are closed, a vault is still "leaking" or unresolved.

## 3. Implementation Plan

1.  **Phase 1: Protocol Update.** Update `SimulationAdapter` to include the new methods. Implement them in `SewProtocol` as pass-throughs to the existing logic.
2.  **Phase 2: Engine Update.** Update `replay.clj` to prefer the new methods if implemented by the protocol, falling back to legacy methods for compatibility.
3.  **Phase 3: Migration.** Gradually update Sew and Yield modules to return rich resource maps. Update `open-gates` logic to align with these resource types.

## 4. Benefits
- **Shared Resource Support:** Naturally handles 1:N and N:M relationships (e.g., many escrows to one vault).
- **Auditability:** Explicitly identifies *what* is still active at the end of a scenario (Is it a dispute? A bond? A yield position?).
- **Flexibility:** Allows the protocol to introduce new entity types without changing the simulation kernel.

## Status Update

Deferred after yield-v1 investigation.

The immediate workflow-id leakage concern was resolved by introducing first-class vault-centric testing through `yield-v1`, rather than by generalizing replay identity.

Current decision:

- Keep `workflow-id` as the Sew lifecycle identity.
- Use `owner-id`, `module-id`, and `token` for yield-v1 vault tests.
- Do not implement `created-resources`, `active-resources`, or `save-resource-as` until a concrete non-yield scenario proves the replay kernel cannot express the required test subject.

See: `docs/architecture/YIELD_V1_VAULT_CENTRIC_TESTING.md`.
