# Solidity V2 Specification: Identity Guard (create-blocked)

## 1. Governance Rule
Every interaction identity in the Sew protocol must be cryptographically bound to its provenance data. The protocol MUST revert any attempt to re-bind or reuse an interaction identifier.

## 2. Implementation: `createEscrow` Guard

The `create-blocked` logic is enforced at the entry point of the protocol.

```solidity
/**
 * @notice Create a new protected transfer with a semantic ID.
 * @param nonce A user-supplied salt to ensure uniqueness if multiple transfers 
 *              are created in the same block.
 */
function createEscrow(
    address token,
    address to,
    uint256 amount,
    uint256 nonce,
    EscrowSettings memory settings
) public returns (bytes32) {
    // 1. Compute Semantic ID (TransferId)
    // lineage: creator + nonce
    bytes32 transferId = keccak256(abi.encodePacked(msg.sender, nonce));

    // 2. Identity Guard (create-blocked)
    // Ensures that this lineage has never been committed before.
    require(escrowTransfers[transferId].state == EscrowState.NONE, "SewIdentity: ID already bound");

    // 3. Execution
    // ... logic to pull tokens and record state ...
}
```

## 3. Implementation: `raiseDispute` ID Derivation

Dispute identities are derived from the `TransferId` to ensure strict isolation.

```solidity
/**
 * @notice Raise a dispute on an existing protected transfer.
 * @return disputeId The semantic ID for this specific dispute process.
 */
function raiseDispute(bytes32 transferId) public returns (bytes32) {
    // 1. Provenance Check
    require(escrowTransfers[transferId].state == EscrowState.PENDING, "SewDispute: Invalid transfer state");

    // 2. Compute Dispute ID
    // lineage: transferId + disputeNonce (derived from transfer state)
    uint256 disputeNonce = escrowTransfers[transferId].disputeCount;
    bytes32 disputeId = keccak256(abi.encodePacked(transferId, disputeNonce));

    // 3. Update State
    // ... initialize dispute process indexed by disputeId ...
}
```

---

## 4. Architectural Impact
By moving to derived IDs, the protocol eliminates the `workflowId` as a shared mutable state. Each phase (Transfer, Dispute, Claim) has a globally unique, immutable identifier, preventing all classes of identity-confusion attacks.
