// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

/// @title ProviderBond (skeleton)
/// @notice Bonding for allocation proving operators.
///
/// LATER-PHASE RESPONSIBILITY (not implemented in this phase):
///   - require proving operators to post a bond;
///   - slash or refund the bond based on proof submission, withholding, liveness,
///     and timeout outcomes;
///   - narrow `assume-punishment-credible` to non-cooperation/withholding rather
///     than correctness (correctness is enforced by the validity proof).
///
/// This file is a compiling skeleton only.
contract ProviderBond {
    uint256 public constant BOND_DENOMINATOR = 1_000_000;

    /// @dev Skeleton: bond unit constant reserved for the later provider
    ///      punishment lifecycle.
}
