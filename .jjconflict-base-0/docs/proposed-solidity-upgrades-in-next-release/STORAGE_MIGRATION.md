# Solidity V2 Upgrade: Storage Migration Strategy

## 1. Objective
Transition the Sew protocol from a sequential `uint256` workflow ID model to a semantic `bytes32` identifier model. This change is required to achieve identity immutability and prevent cross-interaction state contamination.

## 2. Storage Layout Delta

### V1 Layout (Current)
```solidity
uint256 public nextWorkflowId;
mapping(uint256 => EscrowTransfer) public escrowTransfers;
mapping(uint256 => EscrowSettings) public escrowSettings;
```

### V2 Layout (Proposed)
```solidity
// Primary index is now the semantic TransferId
mapping(bytes32 => EscrowTransfer) public escrowTransfers;
mapping(bytes32 => EscrowSettings) public escrowSettings;

// Mapping to resolve legacy integer IDs during migration
mapping(uint256 => bytes32) public legacyToSemanticId;
```

## 3. Live Migration Procedure

### Step 1: Identification
Query the V1 contract for `nextWorkflowId`. All IDs from `0` to `nextWorkflowId - 1` are "Legacy IDs".

### Step 2: Hashing
For each Legacy ID, compute the V2 `ProtectedTransferId` using a migration salt:
```solidity
bytes32 v2Id = keccak256(abi.encodePacked(legacyId, "SEW_V1_MIGRATION_SALT"));
```

### Step 3: Registration
The V2 migration script (run by governance) populates the `legacyToSemanticId` mapping. This ensures that any off-chain system or pending transaction referencing an integer ID can still resolve to the correct V2 record.

### Step 4: Logic Cut-over
Update the contract logic to check `legacyToSemanticId` for incoming integer lookups. New escrows bypass this mapping and use the native V2 `bytes32` keys directly.

---

## 4. Rollback Plan
In the event of a migration failure, the V2 contract retains the ability to resolve Legacy IDs via the original array-access logic, provided the original `escrowTransfers` array data was preserved in the migration snapshot.
