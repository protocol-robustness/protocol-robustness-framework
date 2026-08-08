// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

import {IAllocationProofVerifier} from "./IAllocationProofVerifier.sol";

/// @title AllocationCoordinator (skeleton)
/// @notice Lifecycle coordinator for an allocation round.
///
/// LATER-PHASE RESPONSIBILITY (not implemented in this phase):
///   - freeze the allocation context (claimant-set root, outcome-set root,
///     proposed-rates root, capacity, policy hash, proof program key, deadlines);
///   - request authoritative randomness and forbid cancellation after the
///     randomness request;
///   - verify the allocation proof via IAllocationProofVerifier and activate
///     exactly one accepted result root atomically;
///   - prevent double consumption of a round;
///   - control custody handoff to the escrow contract.
///
/// This file is a compiling skeleton only. Do not use it for real rounds.
contract AllocationCoordinator {
    /// @dev Skeleton: the prover-neutral verifier dependency is declared so the
    ///      interface contract is explicit. A later phase wires it into the
    ///      acceptProvedAllocation path.
    IAllocationProofVerifier public immutable proofVerifier;

    constructor(IAllocationProofVerifier proofVerifier_) {
        proofVerifier = proofVerifier_;
    }
}
