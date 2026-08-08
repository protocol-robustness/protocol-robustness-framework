// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

/// @title IAllocationProofVerifier
/// @notice Prover-neutral interface for verifying an allocation proof.
/// @dev The allocation coordinator depends only on this interface so the
///      underlying proof provider (SP1 first, RISC Zero later) can be swapped
///      without changing the coordinator's semantic contract.
interface IAllocationProofVerifier {
    /// @notice Verify an allocation proof against a program verification key and
    ///         the committed public values.
    /// @param proof The proof bytes (provider-specific encoding).
    /// @param programVerificationKey The program verification key of the
    ///        allocation kernel program.
    /// @param publicValues The public values committed by the program.
    /// @return True when the proof is valid for the given key and public values.
    function verifyAllocationProof(
        bytes calldata proof,
        bytes32 programVerificationKey,
        bytes calldata publicValues
    ) external view returns (bool);
}
