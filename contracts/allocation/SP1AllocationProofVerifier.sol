// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

import {IAllocationProofVerifier} from "./IAllocationProofVerifier.sol";

/// @title SP1AllocationProofVerifier (skeleton)
/// @notice SP1-specific allocation proof verifier.
///
/// LATER-PHASE RESPONSIBILITY (not implemented in this phase):
///   - wrap the SP1 Groth16/PLONK EVM verifier for the allocation kernel program;
///   - decode the program verification key and public values per the SP1
///     encoding;
///   - satisfy IAllocationProofVerifier so the coordinator can select this
///     provider without changing its semantic contract.
///
/// This file is a compiling skeleton only. Real SP1 verifier generation and
/// on-chain proof verification are deferred.
contract SP1AllocationProofVerifier is IAllocationProofVerifier {
    /// @dev Skeleton: implementing IAllocationProofVerifier is the integration
    ///      contract; a later phase supplies the actual SP1 verifier logic.
    function verifyAllocationProof(
        bytes calldata,
        bytes32,
        bytes calldata
    ) external view returns (bool) {
        // Skeleton: no real SP1 verification is implemented in this phase.
        return false;
    }
}
