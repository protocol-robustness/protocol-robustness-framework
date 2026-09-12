# Solidity Upgrade Plan: Transitioning to Semantic IDs (V2)

## 1. Storage Layout Audit & Migration Strategy

### 1.1 Current Layout (V1)
The V1 system uses a monotonic `uint256` counter to index the `escrows` mapping.
```solidity
uint256 public nextWorkflowId;
mapping(uint256 => Escrow) public escrows;
```

### 1.2 Proposed Layout (V2)
The V2 system uses a semantic `bytes32` hash derived from provenance data.
```solidity
mapping(bytes32 => Escrow) public escrows;
// For backward compatibility during migration:
mapping(uint256 => bytes32) public v1ToV2MigrationMap;
```

### 1.3 Live Migration Script (Logic)
For currently deployed escrows, we must preserve identity integrity.
1. **Snapshoting**: Export all V1 `workflowId` values and their current states.
2. **Canonicalization**: For each V1 ID, generate a V2 `ProtectedTransferId` using a constant salt: `keccak256(v1_id, "V1_MIGRATION_SALT")`.
3. **Registration**: Write these mappings into the `v1ToV2MigrationMap`.
4. **Execution**: Update the contract logic to resolve V1 IDs through this map, effectively treating them as "Legacy Aliases" while all new escrows use the native V2 hashing.

---

## 2. Identity Guard Implementation (create-blocked)

To prevent the "Identity Confusion" class of vulnerabilities identified in simulation (`S55`), the Solidity `createEscrow` function must implement a strict `create-blocked` guard.

### 2.1 Proposed Implementation
Instead of the contract assigning a sequential integer, the ID is derived from the sender's nonce.
```solidity
function createEscrow(address token, address to, uint256 amount, uint256 nonce) public returns (bytes32) {
    // 1. Derive Semantic ID
    bytes32 transferId = keccak256(abi.encodePacked(msg.sender, nonce));

    // 2. Identity Guard (create-blocked)
    // CRITICAL: Ensure this ID has never been used.
    require(escrows[transferId].state == EscrowState.None, "SewIdentity: TransferId already bound");

    // 3. Commitment
    escrows[transferId] = Escrow({
        token: token,
        from: msg.sender,
        to: to,
        amount: amount,
        state: EscrowState.Pending
    });

    emit EscrowCreated(transferId, msg.sender, to, amount);
    return transferId;
}
```

---

## 3. Cross-Contract Verification (Simulation Modeling)

Before deploying the above changes to Solidity, we use the Clojure `InvariantSuite` as a **Formal Specification Tool**.

### 3.1 Verification Steps
1. **Model the Guard**: Update the Clojure `create-escrow` function to use the `keccak256` logic and the `workflow-id-already-exists` check.
2. **Adversarial Search**: Run the `S55_alias-rebinding-after-create-blocked` scenario against the new model.
3. **Invariant Assertion**: The simulator must prove that there is **zero probability** of an attacker successfully rebinding an identity, as the `require` check in step 2.1 is mathematically guaranteed by the hash uniqueness.

By passing this simulation, we provide the **formal proof of credibility** required for a production V2 release.
