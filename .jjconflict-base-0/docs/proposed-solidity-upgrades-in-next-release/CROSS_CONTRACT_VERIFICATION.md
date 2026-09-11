# V2 Verification: Cross-Contract Integrity (Simulation Modeling)

## 1. The Simulation as a Formal Specification
In V2, the Clojure simulation acts as the **Canonical Specification** for the Solidity implementation. No change is made to the Solidity contracts without first being modeled and verified in the simulation.

## 2. Modeling Identity Immutability (S55)

The `S55_alias-rebinding-after-create-blocked` scenario is the primary verification tool for the new identity model.

### 2.1 Simulation Proof
1. **Adversarial Setup**: The attacker attempts to call `create_escrow` with a previously used identity (provenance tuple).
2. **State Constraint**: The simulator enforces the `TransferId` derived from the tuple.
3. **Guard Enforcement**: The simulator invokes the `workflow-id-already-exists` check (mirroring the Solidity `require`).
4. **Verification**: The invariant suite confirms that the state machine **always reverts** this attempt, providing mathematical certainty that the protocol is immune to rebinding.

## 3. Continuous Integration: InvariantSuite

The `InvariantSuite` is updated to include the following checks for V2:

| Invariant ID | Name | Description |
| :--- | :--- | :--- |
| **`INV-IDENTITY-01`** | `Uniqueness` | No two world-state entries share the same `TransferId`. |
| **`INV-IDENTITY-02`** | `Isolation` | A dispute process indexed by `DisputeId` cannot modify state in an unrelated `TransferId`. |
| **`INV-IDENTITY-03`** | `Provenance` | Every `ClaimableWithdrawalId` must resolve to a valid, historically verified `TransferId`. |

By passing these invariants, we prove that the V2 identity architecture is logically sound before a single line of Solidity code is deployed.
