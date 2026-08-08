// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

import {IAllocationProofVerifier} from "./IAllocationProofVerifier.sol";

/// @title MockAllocationProofVerifier
/// @notice Deterministic mock verifier for tests and development only.
/// @dev This contract implements a deterministic hash contract that binds the
///      mock proof, the program verification key, and the public values. It does
///      NOT simply return true: the commitment must match exactly.
///
///      The mock contract is suitable only for tests. It is NOT a real
///      cryptographic verifier and must never be used in production or
///      misrepresented as a real proof.
contract MockAllocationProofVerifier is IAllocationProofVerifier {
    /// @notice Hash of the mock commitment. This is the only accepted mock proof.
    bytes32 public immutable mockProofCommitment;

    /// @notice The program verification key accepted by this mock.
    bytes32 public immutable programVerificationKey;

    constructor(bytes32 mockProofCommitment_, bytes32 programVerificationKey_) {
        mockProofCommitment = mockProofCommitment_;
        programVerificationKey = programVerificationKey_;
    }

    /// @notice Deterministic mock verification.
    /// @dev Computes keccak256(proof || programVerificationKey || publicValues)
    ///      and accepts only when it equals the deployed mockProofCommitment.
    function verifyAllocationProof(
        bytes calldata proof,
        bytes32 key,
        bytes calldata publicValues
    ) external view returns (bool) {
        if (key != programVerificationKey) {
            return false;
        }
        bytes32 commitment = keccak256(abi.encodePacked(proof, key, publicValues));
        return commitment == mockProofCommitment;
    }
}
