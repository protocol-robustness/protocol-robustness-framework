# Sew Protocol V2: Identity & Provenance Architecture

## 1. Motivation
The V1 identity model relies on overloaded, monotonic counters (`workflowId`). This creates state-machine fragility (ID reuse), auditability issues (ID overloads multiple phases), and lacks type safety across protocol layers. The V2 architecture transitions to **Semantic, Derived Identifiers** to achieve identity immutability and provenance-based auditing.

## 2. Semantic ID Hierarchy
Each identity is derived from the canonical `ProtectedTransferId` (Escrow/Workflow), ensuring provenance lineage.

| Semantic ID Type | Derivation Path | Purpose |
| :--- | :--- | :--- |
| **`ProtectedTransferId`** | `CanonicalSource` | Source of truth for all workflow/escrow operations. |
| **`DisputeProcessId`** | `hash(TransferId, nonce)` | Unique identifier for a dispute process on a transfer. |
| **`ResolverDecisionId`** | `hash(DisputeProcessId, resolverId)` | Unique ID for a specific resolver action/decision. |
| **`WatchdogChallengeId`** | `hash(DisputeProcessId, watchdogId, nonce)` | Unique ID for a challenge against a resolution. |
| **`ClaimableWithdrawalId`** | `hash(TransferId, claimType, sequence)` | Deterministic identifier for withdrawal entitlement. |

## 3. Transition Strategy (V1 to V2)

### Phase 1: Phantom Types (Simulation Only)
*   **Goal**: Enforce type-safety and ID separation in the simulator without breaking Solidity V1 storage.
*   **Implementation**: Wrap integers in Clojure Records (e.g., `(defrecord TransferId [id])`). 
*   **Benefits**: Catches bugs at compile-time/invariant-check time where a `DisputeId` might be accidentally used in place of a `TransferId`.

### Phase 2: Canonicalization Layer
*   **Goal**: Establish a backward-compatible mapping between V1 monotonic IDs and V2 semantic IDs.
*   **Implementation**: A `canonicalize-id` utility that maps a V1 counter to a deterministic V2 hash (using a genesis-timestamp-derived seed) for auditing.

### Phase 3: Solidity Storage Upgrade (Hard-Fork)
*   **Goal**: Immutable storage layout.
*   **Implementation**: Migrate `mapping(uint256 => Escrow)` to `mapping(bytes32 => Escrow)`. Use the derived semantic IDs as the primary keys in the storage layout.
