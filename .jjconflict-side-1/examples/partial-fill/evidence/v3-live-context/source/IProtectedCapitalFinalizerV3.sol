// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

/// @notice Terminal refund route exposed only by a policy-bound V3 reserve.
interface IProtectedCapitalFinalizerV3 {
    function scopedOperationId() external view returns (bytes32);
    function finalizeProtectedCapital() external;
}
