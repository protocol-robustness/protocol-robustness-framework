// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {IOnchainShortfallReserveV2} from "../../interfaces/IOnchainShortfallReserveV2.sol";
import {IShortfallAllocatorV2} from "../../interfaces/IShortfallAllocatorV2.sol";
import {IShortfallEntitlementAllocatorV1} from "../../interfaces/IShortfallEntitlementAllocatorV1.sol";
import {IOnchainShortfallEntitlementReserveV1} from "../../interfaces/IOnchainShortfallEntitlementReserveV1.sol";
import {IEntitlementRepresentationV1} from "../../interfaces/IEntitlementRepresentationV1.sol";

/// @notice V3 single-operation reserve with policy-bound restricted capital. This contract exposes deterministic
/// economic-state commitments and atomic Shortfall funding/refund routes.
contract OnchainShortfallReserveV3 is IOnchainShortfallReserveV2, IOnchainShortfallEntitlementReserveV1 {
    bytes32 public constant ECONOMIC_STATE_DOMAIN = keccak256("prf.economic-reserve-state.v3");
    bytes32 public constant OBSERVATION_DOMAIN = keccak256("prf.authoritative-reserve-observation.v3");
    bytes32 public constant TERMINAL_CONDITIONS = keccak256(
        "allocator-closed;zero-outstanding-liability;no-active-reserve-holds;exact-protected-balance"
    );

    IERC20 public immutable override token;
    address public immutable applicationAuthority;
    address public ratificationAuthority;
    bool public ratificationAuthorityBound;
    uint256 public immutable protectedCapital;
    bytes32 public immutable scopedOperationId;
    bytes32 public policyRoot;
    address public immutable sponsor;
    address public immutable refundBeneficiary;
    bytes32 public immutable fundingAttributionRoot;
    bytes32 public immutable purposeRoot;
    bool public finalized;
    IShortfallAllocatorV2 public immutable override allocator;

    uint64 public availabilityRevision;
    uint64 public nextSnapshotId = 1;
    uint256 public totalReserved;

    enum ReservationState {
        None,
        Active,
        Released,
        Expired
    }

    struct Reservation {
        ReservationState state;
        uint256 amount;
        uint256 expiresAt;
    }

    struct EconomicState {
        bytes32 preparedOperationRoot;
        bytes32 reserve;
        bytes32 token;
        uint256 grossBalance;
        uint256 reservedBefore;
        uint256 protectedAmount;
        uint256 availableAmount;
        uint64 revision;
        bytes32 commitment;
    }

    struct AuthoritativeObservation {
        bytes32 preparedOperationRoot;
        bytes32 reserveStateCommitment;
        uint64 snapshotId;
        uint256 createdAtBlock;
        bytes32 observationRoot;
    }

    mapping(bytes32 => Reservation) public reservations;
    bool public ratifying;
    bool private _entered;

    error ZeroValue();
    error InvalidReservation();
    error ReservationNotExpired();
    error AccountingInconsistent();
    error InsufficientAvailability();
    error TokenDeltaMismatch();
    error OnlyApplicationAuthority(address caller);
    error OnlySponsor(address caller);
    error OnlyRatificationAuthority(address caller);
    error RatificationAuthorityAlreadyBound();
    error InvalidRatificationAuthority();
    error CommitmentMismatch(bytes32 expected, bytes32 actual);
    error AllocatorMismatch();
    error Reentrant();
    error AllowanceMismatch();
    error WrongOperation();
    error Terminal();
    error OperationNotClosed();
    error ProtectedBalanceMismatch();
    error RefundTransferMismatch();

    event Deposited(address indexed from, uint256 amount);
    event ReservationCreated(bytes32 indexed reservationId, uint256 amount, uint256 expiresAt, uint64 revision);
    event ReservationReleased(bytes32 indexed reservationId, uint256 amount, uint64 revision);
    event ReservationExpired(bytes32 indexed reservationId, uint256 amount, uint64 revision);
    event RatificationAuthorityBound(address indexed controller);
    event FundingCancelledAndRefunded(bytes32 indexed operationId, uint256 amount, uint64 revision);
    event EntitlementFundingCancelledAndRefunded(bytes32 indexed operationId, uint256 amount, uint64 revision);
    event RatifiedAndFunded(
        bytes32 indexed operationId, bytes32 indexed observationRoot, uint256 amount, uint64 revision
    );
    event ProtectedCapitalReleased(bytes32 indexed policyRoot, bytes32 indexed operationId, address indexed beneficiary, uint256 amount);

    modifier nonReentrant() {
        if (_entered) revert Reentrant();
        _entered = true;
        _;
        _entered = false;
    }

    modifier onlyApplicationAuthority() {
        if (msg.sender != applicationAuthority) revert OnlyApplicationAuthority(msg.sender);
        _;
    }

    modifier onlyRatificationAuthority() {
        if (msg.sender != ratificationAuthority) revert OnlyRatificationAuthority(msg.sender);
        _;
    }

    /// @notice Permanently binds the controller used for atomic V2 ratification.
    /// Ordinary reserve administration remains owned by applicationAuthority.
    function bindRatificationAuthority(address controller_) external onlyApplicationAuthority {
        if (ratificationAuthorityBound) revert RatificationAuthorityAlreadyBound();
        if (controller_ == address(0)) revert InvalidRatificationAuthority();
        IShortfallEntitlementAllocatorV1 entitlementAllocator = IShortfallEntitlementAllocatorV1(address(allocator));
        if (
            !entitlementAllocator.fundingAuthorityBound()
                || entitlementAllocator.fundingAuthority() != address(this)
        ) revert InvalidRatificationAuthority();
        ratificationAuthority = controller_;
        ratificationAuthorityBound = true;
        policyRoot = keccak256(abi.encode(
            keccak256("prf.restricted-refundable-sponsor-capital-policy.v1"),
            block.chainid, address(this), address(token), address(allocator), scopedOperationId,
            protectedCapital, sponsor, refundBeneficiary, fundingAttributionRoot, purposeRoot, controller_, TERMINAL_CONDITIONS
        ));
        emit RatificationAuthorityBound(controller_);
    }

    constructor(
        IERC20 token_,
        address applicationAuthority_,
        uint256 protectedCapital_,
        IShortfallAllocatorV2 allocator_,
        bytes32 scopedOperationId_,
        address sponsor_,
        address refundBeneficiary_,
        bytes32 fundingAttributionRoot_,
        bytes32 purposeRoot_
    ) {
        if (address(token_) == address(0) || applicationAuthority_ == address(0) || address(allocator_) == address(0)) revert ZeroValue();
        token = token_;
        applicationAuthority = applicationAuthority_;
        if (scopedOperationId_ == bytes32(0) || sponsor_ == address(0) || refundBeneficiary_ == address(0) || fundingAttributionRoot_ == bytes32(0) || purposeRoot_ == bytes32(0)) revert ZeroValue();
        protectedCapital = protectedCapital_;
        scopedOperationId = scopedOperationId_;
        sponsor = sponsor_;
        refundBeneficiary = refundBeneficiary_;
        fundingAttributionRoot = fundingAttributionRoot_;
        purposeRoot = purposeRoot_;
        allocator = allocator_;
    }

    /// @dev Supported assets are exact-transfer, non-rebasing ERC-20s with conventional balanceOf semantics.
    function deposit(uint256 amount) external {
        if (finalized) revert Terminal();
        if (msg.sender != sponsor) revert OnlySponsor(msg.sender);
        if (amount == 0) revert ZeroValue();
        uint256 sourceBefore = token.balanceOf(msg.sender);
        uint256 reserveBefore = token.balanceOf(address(this));
        if (!token.transferFrom(msg.sender, address(this), amount)) revert TokenDeltaMismatch();
        if (sourceBefore < token.balanceOf(msg.sender) || sourceBefore - token.balanceOf(msg.sender) != amount) {
            revert TokenDeltaMismatch();
        }
        if (token.balanceOf(address(this)) < reserveBefore || token.balanceOf(address(this)) - reserveBefore != amount) revert TokenDeltaMismatch();
        emit Deposited(msg.sender, amount);
    }

    /// @notice Creates an exclusion hold, not a fundable operation. Its deadline cannot be extended.
    function reserveForOtherObligation(bytes32 reservationId, uint256 amount, uint256 expiresAt)
        external
        onlyApplicationAuthority
    {
        if (finalized) revert Terminal();
        if (
            reservationId == bytes32(0) || amount == 0 || expiresAt <= block.timestamp
                || reservations[reservationId].state != ReservationState.None
        ) {
            revert InvalidReservation();
        }
        uint256 gross = token.balanceOf(address(this));
        uint256 available = _available(gross);
        if (amount > available) revert InsufficientAvailability();
        reservations[reservationId] = Reservation(ReservationState.Active, amount, expiresAt);
        totalReserved += amount;
        ++availabilityRevision;
        emit ReservationCreated(reservationId, amount, expiresAt, availabilityRevision);
    }

    function releaseOtherObligationReservation(bytes32 reservationId) external onlyApplicationAuthority {
        if (finalized) revert Terminal();
        Reservation storage reservation = reservations[reservationId];
        if (reservation.state != ReservationState.Active) revert InvalidReservation();
        uint256 amount = reservation.amount;
        reservation.state = ReservationState.Released;
        totalReserved -= amount;
        ++availabilityRevision;
        emit ReservationReleased(reservationId, amount, availabilityRevision);
    }

    /// @notice Permissionlessly frees a hold at or after its immutable deadline.
    /// Time alone does not change accounting. Both terminal states permanently consume the ID.
    function expireOtherObligationReservation(bytes32 reservationId) external {
        if (finalized) revert Terminal();
        Reservation storage reservation = reservations[reservationId];
        if (reservation.state != ReservationState.Active) revert InvalidReservation();
        if (block.timestamp < reservation.expiresAt) revert ReservationNotExpired();
        uint256 amount = reservation.amount;
        reservation.state = ReservationState.Expired;
        totalReserved -= amount;
        ++availabilityRevision;
        emit ReservationExpired(reservationId, amount, availabilityRevision);
    }

    function ratifyAndFund(RatificationRequest calldata request)
        external
        pure
        override
        returns (RatificationResult memory)
    {
        request;
        revert WrongOperation();
    }

    /// @notice Additive successor funding handoff for the beneficiary-entitlement
    ///         proof profile. The existing V2 ratification ABI is unchanged.
    function ratifyAndFundEntitlement(
        IOnchainShortfallEntitlementReserveV1.EntitlementRatificationRequest calldata request
    )
        external
        override
        onlyRatificationAuthority
        nonReentrant
        returns (IOnchainShortfallEntitlementReserveV1.EntitlementRatificationResult memory result)
    {
        if (finalized) revert Terminal();
        if (!ratificationAuthorityBound) revert InvalidRatificationAuthority();
        if (request.operationId != scopedOperationId) revert WrongOperation();
        if (ratifying) revert Reentrant();
        if (
            request.operationId == bytes32(0) || request.preparedOperationRoot == bytes32(0)
                || request.allocationBindingRoot == bytes32(0) || request.descriptorRoot == bytes32(0)
                || request.evmEntitlementTableRoot == bytes32(0) || request.entitlementCount == 0
                || request.entitlementTotal == 0 || request.allocationClaimRoot == bytes32(0)
                || request.residualRecipient == address(0)
        ) revert ZeroValue();

        uint256 gross = token.balanceOf(address(this));
        uint256 available = _available(gross);
        bytes32 actual = _commitment(request.preparedOperationRoot, gross, totalReserved, protectedCapital, available);
        if (actual != request.expectedReserveStateCommitment) {
            revert CommitmentMismatch(request.expectedReserveStateCommitment, actual);
        }
        if (available == 0 || request.entitlementTotal != available) {
            revert InsufficientAvailability();
        }

        ratifying = true;
        ++availabilityRevision;
        totalReserved += available;
        uint64 snapshotId = nextSnapshotId++;
        bytes32 observationRoot = keccak256(
            abi.encode(
                OBSERVATION_DOMAIN,
                request.preparedOperationRoot,
                request.expectedReserveStateCommitment,
                snapshotId,
                block.number
            )
        );

        IShortfallEntitlementAllocatorV1 entitlementAllocator = IShortfallEntitlementAllocatorV1(address(allocator));
        entitlementAllocator.createEntitlement(
            request.operationId,
            request.allocationBindingRoot,
            request.descriptorRoot,
            request.evmEntitlementTableRoot,
            request.entitlementCount,
            request.entitlementTotal,
            request.allocationClaimRoot,
            request.beneficiaryProfileWord,
            token,
            available,
            request.residualRecipient
        );
        IShortfallEntitlementAllocatorV1.EntitlementOperation memory created =
            entitlementAllocator.entitlementOperation(request.operationId);
        if (
            created.state != IShortfallAllocatorV2.State.Created || address(created.token) != address(token)
                || created.amount != available || created.allocationBindingRoot != request.allocationBindingRoot
                || created.descriptorRoot != request.descriptorRoot
                || created.evmEntitlementTableRoot != request.evmEntitlementTableRoot
                || created.entitlementCount != request.entitlementCount
                || created.entitlementTotal != request.entitlementTotal
                || created.allocationClaimRoot != request.allocationClaimRoot
                || created.beneficiaryProfileWord != request.beneficiaryProfileWord
        ) revert AllocatorMismatch();

        uint256 reserveBefore = token.balanceOf(address(this));
        uint256 allocatorBefore = token.balanceOf(address(allocator));
        if (token.allowance(address(this), address(allocator)) != 0) revert AllowanceMismatch();
        if (!token.approve(address(allocator), available)) revert AllowanceMismatch();
        entitlementAllocator.fundEntitlement(request.operationId);
        if (
            reserveBefore < token.balanceOf(address(this))
                || reserveBefore - token.balanceOf(address(this)) != available
                || token.balanceOf(address(allocator)) < allocatorBefore
                || token.balanceOf(address(allocator)) - allocatorBefore != available
                || token.allowance(address(this), address(allocator)) != 0
        ) revert TokenDeltaMismatch();
        IShortfallEntitlementAllocatorV1.EntitlementOperation memory funded =
            entitlementAllocator.entitlementOperation(request.operationId);
        if (funded.state != IShortfallAllocatorV2.State.Funded) revert AllocatorMismatch();

        totalReserved -= available;
        ratifying = false;
        result = IOnchainShortfallEntitlementReserveV1.EntitlementRatificationResult(
            observationRoot, snapshotId, available, availabilityRevision
        );
        emit RatifiedAndFunded(request.operationId, observationRoot, available, availabilityRevision);
    }

    /// @notice Forwards activation after comparing the deployed logical table
    ///         with the roots admitted and funded for this operation.
    function activateEntitlement(bytes32 operationId, IEntitlementRepresentationV1 representation)
        external
        onlyRatificationAuthority
    {
        if (finalized) revert Terminal();
        if (!ratificationAuthorityBound) revert InvalidRatificationAuthority();
        if (operationId != scopedOperationId) revert WrongOperation();
        IShortfallEntitlementAllocatorV1(address(allocator)).activateEntitlement(operationId, representation);
    }

    /// @notice Atomically refunds a funded, not-yet-activated entitlement operation to this reserve.
    /// @dev The allocator's recorded funder and the exact token deltas are checked before returning.
    function cancelAndRefundEntitlement(bytes32 operationId) external onlyRatificationAuthority nonReentrant {
        if (finalized) revert Terminal();
        if (!ratificationAuthorityBound) revert InvalidRatificationAuthority();
        if (operationId != scopedOperationId) revert WrongOperation();
        IShortfallEntitlementAllocatorV1 entitlementAllocator = IShortfallEntitlementAllocatorV1(address(allocator));
        IShortfallEntitlementAllocatorV1.EntitlementOperation memory funded =
            entitlementAllocator.entitlementOperation(operationId);
        if (
            funded.state != IShortfallAllocatorV2.State.Funded || address(funded.token) != address(token)
                || funded.amount == 0 || entitlementAllocator.fundingSource(operationId) != address(this)
        ) revert AllocatorMismatch();

        uint256 allocatorBefore = token.balanceOf(address(allocator));
        uint256 reserveBefore = token.balanceOf(address(this));
        if (token.allowance(address(this), address(allocator)) != 0) revert AllowanceMismatch();
        entitlementAllocator.cancelAndRefundEntitlement(operationId);
        if (
            allocatorBefore < token.balanceOf(address(allocator))
                || allocatorBefore - token.balanceOf(address(allocator)) != funded.amount
                || token.balanceOf(address(this)) < reserveBefore
                || token.balanceOf(address(this)) - reserveBefore != funded.amount
        ) revert TokenDeltaMismatch();
        IShortfallEntitlementAllocatorV1.EntitlementOperation memory refunded =
            entitlementAllocator.entitlementOperation(operationId);
        if (refunded.state != IShortfallAllocatorV2.State.Cancelled) revert AllocatorMismatch();

        ++availabilityRevision;
        emit EntitlementFundingCancelledAndRefunded(operationId, funded.amount, availabilityRevision);
    }

    /// @notice Returns funded legacy operation capital to this reserve atomically.
    /// @dev Custody recovery remains downstream of the reservation boundary.
    function cancelAndRefund(bytes32 operationId) external pure override {
        operationId;
        revert WrongOperation();
    }

    /// @notice Releases only the policy-bound protected amount after the scoped claimant operation is closed.
    function finalizeProtectedCapital() external onlyRatificationAuthority nonReentrant {
        if (finalized) revert Terminal();
        if (!ratificationAuthorityBound || policyRoot == bytes32(0)) revert InvalidRatificationAuthority();
        if (totalReserved != 0) revert AccountingInconsistent();
        IShortfallEntitlementAllocatorV1 entitlementAllocator = IShortfallEntitlementAllocatorV1(address(allocator));
        IShortfallEntitlementAllocatorV1.EntitlementOperation memory operation =
            entitlementAllocator.entitlementOperation(scopedOperationId);
        if (operation.state != IShortfallAllocatorV2.State.Closed) revert OperationNotClosed();
        // Production allocator's liability is an independently readable state variable.
        if (entitlementAllocator.outstandingLiability(scopedOperationId) != 0) revert OperationNotClosed();
        uint256 reserveBefore = token.balanceOf(address(this));
        uint256 beneficiaryBefore = token.balanceOf(refundBeneficiary);
        if (reserveBefore != protectedCapital) revert ProtectedBalanceMismatch();
        ++availabilityRevision;
        finalized = true;
        if (!token.transfer(refundBeneficiary, protectedCapital)) revert RefundTransferMismatch();
        uint256 reserveAfter = token.balanceOf(address(this));
        uint256 beneficiaryAfter = token.balanceOf(refundBeneficiary);
        if (reserveBefore - reserveAfter != protectedCapital || beneficiaryAfter - beneficiaryBefore != protectedCapital) {
            revert RefundTransferMismatch();
        }
        emit ProtectedCapitalReleased(policyRoot, scopedOperationId, refundBeneficiary, protectedCapital);
    }

    function economicState(bytes32 preparedOperationRoot) public view returns (EconomicState memory state) {
        if (!ratificationAuthorityBound || policyRoot == bytes32(0)) revert InvalidRatificationAuthority();
        uint256 gross = token.balanceOf(address(this));
        uint256 protectedAmount = finalized ? 0 : protectedCapital;
        if (totalReserved > gross || totalReserved + protectedAmount > gross) revert AccountingInconsistent();
        uint256 available = gross - totalReserved - protectedAmount;
        state = EconomicState(
            preparedOperationRoot,
            bytes32(uint256(uint160(address(this)))),
            bytes32(uint256(uint160(address(token)))),
            gross,
            totalReserved,
            protectedAmount,
            available,
            availabilityRevision,
            _commitment(preparedOperationRoot, gross, totalReserved, protectedAmount, available)
        );
    }

    function currentCommitment(bytes32 preparedOperationRoot) external view override returns (bytes32) {
        return economicState(preparedOperationRoot).commitment;
    }

    function authoritativeObservation(
        bytes32 preparedOperationRoot,
        bytes32 reserveStateCommitment,
        uint64 snapshotId,
        uint256 createdAtBlock
    ) external pure returns (AuthoritativeObservation memory observation) {
        observation = AuthoritativeObservation(
            preparedOperationRoot,
            reserveStateCommitment,
            snapshotId,
            createdAtBlock,
            keccak256(
                abi.encode(
                    OBSERVATION_DOMAIN, preparedOperationRoot, reserveStateCommitment, snapshotId, createdAtBlock
                )
            )
        );
    }

    function _available(uint256 gross) private view returns (uint256) {
        if (totalReserved > gross || totalReserved + protectedCapital > gross) revert AccountingInconsistent();
        return gross - totalReserved - protectedCapital;
    }

    function _commitment(
        bytes32 preparedOperationRoot,
        uint256 gross,
        uint256 reservedBefore,
        uint256 protectedAmount,
        uint256 available
    )
        private
        view
        returns (bytes32)
    {
        return keccak256(
            abi.encode(
                ECONOMIC_STATE_DOMAIN,
                preparedOperationRoot,
                bytes32(uint256(uint160(address(this)))),
                bytes32(uint256(uint160(address(token)))),
                gross,
                reservedBefore,
                protectedAmount,
                available,
                availabilityRevision,
                policyRoot
            )
        );
    }
}
