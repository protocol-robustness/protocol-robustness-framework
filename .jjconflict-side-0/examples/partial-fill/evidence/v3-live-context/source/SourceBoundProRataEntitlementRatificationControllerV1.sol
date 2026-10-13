// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

import {IOnchainShortfallReserveV2} from "../../interfaces/IOnchainShortfallReserveV2.sol";
import {IOnchainShortfallEntitlementReserveV1} from "../../interfaces/IOnchainShortfallEntitlementReserveV1.sol";
import {SourceBoundProRataControllerV2} from "./SourceBoundProRataControllerV2.sol";
import {SourceBoundProRataEntitlementAssuranceV1} from "./SourceBoundProRataEntitlementAssuranceV1.sol";
import {IEntitlementRepresentationV1} from "../../interfaces/IEntitlementRepresentationV1.sol";
import {IProtectedCapitalFinalizerV3} from "../../interfaces/IProtectedCapitalFinalizerV3.sol";

/// @notice Atomic ratification/funding handoff for the successor entitlement profile.
/// @dev Additive to the frozen V2 controller, whose ABI carries realizedStatementRoot.
contract SourceBoundProRataEntitlementRatificationControllerV1 {
    enum State {
        None,
        Prepared,
        Ratifying,
        Funded,
        Activated,
        CancelledRefunded,
        Finalized
    }

    SourceBoundProRataControllerV2 public immutable preparedController;
    SourceBoundProRataEntitlementAssuranceV1 public immutable assurance;
    IOnchainShortfallReserveV2 public immutable reserve;
    address public executionCaller;
    bool public executionCallerBound;

    mapping(bytes32 => State) public state;
    mapping(bytes32 => IOnchainShortfallEntitlementReserveV1.EntitlementRatificationResult) public result;

    error InvalidState(State actual);
    error InvalidPreparedOperation(SourceBoundProRataControllerV2.State actual);
    error PreparedContextMismatch();
    error AssuranceMismatch();
    error OnlyGovernance(address caller);
    error OnlyExecutionCaller(address caller);
    error InvalidExecutionCaller();
    error ExecutionCallerAlreadyBound();

    event ExecutionCallerBound(address indexed caller);
    event EntitlementRatificationFunded(bytes32 indexed operationId, bytes32 indexed receiptId, uint256 amount);
    event EntitlementOperationActivated(bytes32 indexed operationId, address indexed representation);
    event EntitlementOperationCancelledAndRefunded(bytes32 indexed operationId);
    event ProtectedCapitalFinalized(bytes32 indexed operationId, address indexed reserve);
    event StaleEntitlementAssuranceReplaced(
        bytes32 indexed operationId,
        bytes32 indexed oldAssuranceCommitment,
        bytes32 indexed newAssuranceCommitment,
        uint64 oldGeneration,
        uint64 newGeneration,
        address authorizingPrincipal
    );

    constructor(
        SourceBoundProRataControllerV2 preparedController_,
        SourceBoundProRataEntitlementAssuranceV1 assurance_,
        IOnchainShortfallReserveV2 reserve_
    ) {
        require(
            address(preparedController_) != address(0) && address(assurance_) != address(0)
                && address(reserve_) != address(0),
            "SBERC1: zero value"
        );
        preparedController = preparedController_;
        assurance = assurance_;
        reserve = reserve_;
    }

    function bindExecutionCaller(address caller_) external {
        if (msg.sender != preparedController.governance()) revert OnlyGovernance(msg.sender);
        if (executionCallerBound) revert ExecutionCallerAlreadyBound();
        if (caller_ == address(0) || caller_.code.length == 0) revert InvalidExecutionCaller();
        executionCaller = caller_;
        executionCallerBound = true;
        emit ExecutionCallerBound(caller_);
    }

    function registerPrepared(bytes32 operationId) external {
        if (preparedController.state(operationId) != SourceBoundProRataControllerV2.State.Prepared) {
            revert InvalidState(state[operationId]);
        }
        if (!preparedController.successorEntitlementProfile(operationId) || state[operationId] != State.None) {
            revert InvalidState(state[operationId]);
        }
        state[operationId] = State.Prepared;
    }

    function ratifyAndFund(bytes32 operationId, bytes32 expectedReserveStateCommitment, address residualRecipient)
        external
    {
        if (!executionCallerBound || msg.sender != executionCaller) revert OnlyExecutionCaller(msg.sender);
        if (state[operationId] != State.Prepared) revert InvalidState(state[operationId]);
        if (preparedController.state(operationId) != SourceBoundProRataControllerV2.State.Prepared) {
            revert InvalidPreparedOperation(preparedController.state(operationId));
        }
        if (
            preparedController.preparedChainId(operationId) != block.chainid
                || preparedController.ratificationController() != address(this)
                || preparedController.preparedReserve(operationId) != address(reserve)
                || preparedController.preparedToken(operationId) != address(reserve.token())
                || preparedController.preparedAllocator(operationId) != address(reserve.allocator())
                || bytes32(uint256(uint160(residualRecipient)))
                    != preparedController.preparedResidualRecipient(operationId)
        ) revert PreparedContextMismatch();

        state[operationId] = State.Ratifying;
        bytes32 preparedRoot = preparedController.preparedOperationRoot(operationId);
        SourceBoundProRataEntitlementAssuranceV1.RatificationPayload memory payload =
            assurance.consumeForRatification(operationId, preparedRoot, expectedReserveStateCommitment);
        if (
            payload.operationId != operationId || payload.preparedOperationRoot != preparedRoot
                || payload.reserveStateCommitment != expectedReserveStateCommitment
        ) revert AssuranceMismatch();

        // This write and assurance consumption roll back if any reserve or token
        // postcondition fails, preserving the atomic Ratified -> Funded boundary.
        preparedController.finalizePreparedOperation(operationId, preparedRoot);
        IOnchainShortfallEntitlementReserveV1.EntitlementRatificationResult memory funded = IOnchainShortfallEntitlementReserveV1(
                address(reserve)
            )
            .ratifyAndFundEntitlement(
                IOnchainShortfallEntitlementReserveV1.EntitlementRatificationRequest({
                operationId: operationId,
                preparedOperationRoot: preparedRoot,
                expectedReserveStateCommitment: expectedReserveStateCommitment,
                allocationBindingRoot: payload.allocationBindingRoot,
                descriptorRoot: payload.descriptorRoot,
                evmEntitlementTableRoot: payload.evmEntitlementTableRoot,
                entitlementCount: payload.entitlementCount,
                entitlementTotal: payload.entitlementTotal,
                allocationClaimRoot: payload.allocationClaimRoot,
                beneficiaryProfileWord: preparedController.preparedBeneficiaryProfileWord(operationId),
                residualRecipient: residualRecipient
            })
            );
        result[operationId] = funded;
        state[operationId] = State.Funded;
        emit EntitlementRatificationFunded(operationId, payload.receiptId, funded.fundedAmount);
    }

    /// @notice Replaces an admitted assurance only after its reserve snapshot becomes stale.
    function replaceStaleAssurance(
        bytes32 operationId,
        bytes32 expectedOldAssuranceCommitment,
        bytes32 expectedReplacementAssuranceCommitment,
        uint64 expectedGeneration,
        bytes32 replacementReceiptId,
        bytes calldata replacementPublicValues
    ) external {
        if (!executionCallerBound || msg.sender != executionCaller) {
            revert OnlyExecutionCaller(msg.sender);
        }
        if (state[operationId] != State.Prepared) revert InvalidState(state[operationId]);
        if (preparedController.state(operationId) != SourceBoundProRataControllerV2.State.Prepared) {
            revert InvalidPreparedOperation(preparedController.state(operationId));
        }
        bytes32 preparedRoot = preparedController.preparedOperationRoot(operationId);
        if (
            preparedController.preparedChainId(operationId) != block.chainid
                || preparedController.ratificationController() != address(this)
                || preparedController.preparedReserve(operationId) != address(reserve)
                || preparedController.preparedToken(operationId) != address(reserve.token())
                || preparedController.preparedAllocator(operationId) != address(reserve.allocator())
        ) revert PreparedContextMismatch();
        bytes32 liveCommitment = reserve.currentCommitment(preparedRoot);
        (bytes32 oldCommitment, bytes32 newCommitment, uint64 oldGeneration, uint64 newGeneration) = assurance.replaceStaleAssurance(
            operationId,
            expectedOldAssuranceCommitment,
            expectedReplacementAssuranceCommitment,
            expectedGeneration,
            replacementReceiptId,
            replacementPublicValues,
            liveCommitment
        );
        emit StaleEntitlementAssuranceReplaced(
            operationId, oldCommitment, newCommitment, oldGeneration, newGeneration, msg.sender
        );
    }

    /// @notice Returns funded capital before activation; activated liabilities cannot be cancelled.
    function cancelAndRefundEntitlement(bytes32 operationId) external {
        if (!executionCallerBound || msg.sender != executionCaller) revert OnlyExecutionCaller(msg.sender);
        if (state[operationId] != State.Funded) revert InvalidState(state[operationId]);
        IOnchainShortfallEntitlementReserveV1(address(reserve)).cancelAndRefundEntitlement(operationId);
        state[operationId] = State.CancelledRefunded;
        emit EntitlementOperationCancelledAndRefunded(operationId);
    }

    function activateEntitlement(bytes32 operationId, IEntitlementRepresentationV1 representation) external {
        if (!executionCallerBound || msg.sender != executionCaller) revert OnlyExecutionCaller(msg.sender);
        if (state[operationId] != State.Funded) revert InvalidState(state[operationId]);
        IOnchainShortfallEntitlementReserveV1(address(reserve)).activateEntitlement(operationId, representation);
        state[operationId] = State.Activated;
        emit EntitlementOperationActivated(operationId, address(representation));
    }

    /// @notice Finalizes a V3 reserve after its scoped claimant operation has closed.
    /// @dev The reserve verifies zero liability, no active holds and exact protected balance.
    function finalizeProtectedCapital() external {
        if (!executionCallerBound || msg.sender != executionCaller) revert OnlyExecutionCaller(msg.sender);
        bytes32 operationId = IProtectedCapitalFinalizerV3(address(reserve)).scopedOperationId();
        if (state[operationId] != State.Activated) revert InvalidState(state[operationId]);
        IProtectedCapitalFinalizerV3(address(reserve)).finalizeProtectedCapital();
        state[operationId] = State.Finalized;
        emit ProtectedCapitalFinalized(operationId, address(reserve));
    }
}
