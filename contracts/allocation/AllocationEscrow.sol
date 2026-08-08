// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

/// @title AllocationEscrow (skeleton)
/// @notice Escrow contract that releases value to claimants against an accepted
///         result root.
///
/// LATER-PHASE RESPONSIBILITY (not implemented in this phase):
///   - hold custody of funds after the coordinator activates a result root;
///   - allow claimants to withdraw by submitting Merkle proofs against the
///     accepted result root;
///   - enforce that withdrawals are asynchronous while allocation authority is
///     atomic.
///
/// This file is a compiling skeleton only.
contract AllocationEscrow {
    bytes32 public acceptedResultRoot;

    /// @dev Skeleton: records an accepted result root. A later phase binds this
    ///      to the coordinator's atomic activation and implements Merkle-proof
    ///      withdrawals.
    function _setAcceptedResultRoot(bytes32 resultRoot_) internal {
        acceptedResultRoot = resultRoot_;
    }
}
