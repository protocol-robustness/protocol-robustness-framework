// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

import {Test} from "forge-std/Test.sol";
import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {OnchainShortfallReserveV3} from "../src/applications/shortfall/OnchainShortfallReserveV3.sol";
import {IShortfallAllocatorV2} from "../src/interfaces/IShortfallAllocatorV2.sol";
import {IShortfallEntitlementAllocatorV1} from "../src/interfaces/IShortfallEntitlementAllocatorV1.sol";
import {IEntitlementRepresentationV1} from "../src/interfaces/IEntitlementRepresentationV1.sol";
import {IOnchainShortfallEntitlementReserveV1} from "../src/interfaces/IOnchainShortfallEntitlementReserveV1.sol";
import {ProductionProRataAllocatorV2} from "../src/applications/pro-rata/ProductionProRataAllocatorV2.sol";
import {PublicPackedEntitlementRepresentationV1} from "../src/applications/pro-rata/PublicPackedEntitlementRepresentationV1.sol";
import {MockEntitlementProofModeSource} from "./mocks/MockEntitlementProofModeSource.sol";

contract V3ReserveToken is ERC20 {
    constructor() ERC20("V3 Reserve", "V3R") {}
    function mint(address to, uint256 amount) external { _mint(to, amount); }
}

contract V3LifecycleAllocatorMock is IShortfallAllocatorV2, IShortfallEntitlementAllocatorV1 {
    mapping(bytes32 => Operation) private _operations;
    mapping(bytes32 => EntitlementOperation) private _entitlements;
    mapping(bytes32 => address) private _funders;
    mapping(bytes32 => uint256) public override outstandingLiability;
    address public override fundingAuthority;
    bool public override fundingAuthorityBound;
    function create(bytes32, bytes32, bytes32, IERC20, uint256, address) external pure override { revert(); }
    function fund(bytes32) external pure override { revert(); }
    function cancelAndRefund(bytes32) external pure override { revert(); }
    function fundingSource(bytes32 id) external view override(IShortfallAllocatorV2, IShortfallEntitlementAllocatorV1) returns(address) { return _funders[id]; }
    function operation(bytes32 id) external view override returns(Operation memory) { return _operations[id]; }
    function createEntitlement(bytes32 id, bytes32 binding, bytes32 descriptor, bytes32 table, uint256 count, uint256 total, bytes32 claims, bytes32 profile, IERC20 token, uint256 amount, address residual) external override {
        _entitlements[id] = EntitlementOperation(State.Created, binding, descriptor, table, count, total, claims, profile, token, amount, residual, address(0));
    }
    function bindFundingAuthority(address authority) external override { fundingAuthority = authority; fundingAuthorityBound = true; }
    function setFundingAuthority(address authority) external { fundingAuthority = authority; fundingAuthorityBound = true; }
    function fundEntitlement(bytes32 id) external override { _entitlements[id].state = State.Funded; _funders[id] = msg.sender; }
    function cancelAndRefundEntitlement(bytes32 id) external override { _entitlements[id].state = State.Cancelled; }
    function activateEntitlement(bytes32 id, IEntitlementRepresentationV1 representation) external override { _entitlements[id].state = State.Activated; _entitlements[id].representation = address(representation); }
    function entitlementOperation(bytes32 id) external view override returns(EntitlementOperation memory) { return _entitlements[id]; }
    function setClosed(bytes32 id, uint256 liability) external { _entitlements[id].state = State.Closed; outstandingLiability[id] = liability; }
}

contract OnchainShortfallReserveV3Test is Test {
    V3ReserveToken token;
    V3LifecycleAllocatorMock allocator;
    OnchainShortfallReserveV3 reserve;
    address constant BENEFICIARY = address(0xCA701);
    address constant CONTROLLER = address(0xC0117);
    bytes32 constant OPERATION = keccak256("controlled-partial-fill");

    function setUp() public {
        token = new V3ReserveToken();
        allocator = new V3LifecycleAllocatorMock();
        reserve = new OnchainShortfallReserveV3(
            token, address(this), 10, allocator, OPERATION, address(this), BENEFICIARY,
            keccak256("funding-attribution"), keccak256("restricted-refundable-sponsor-capital")
        );
        allocator.setFundingAuthority(address(reserve));
        reserve.bindRatificationAuthority(CONTROLLER);
        token.mint(address(this), 10);
        token.approve(address(reserve), 10);
        reserve.deposit(10);
    }

    function test_ReleaseRequiresClosedOperationAndZeroLiability() public {
        vm.prank(CONTROLLER);
        vm.expectRevert(OnchainShortfallReserveV3.OperationNotClosed.selector);
        reserve.finalizeProtectedCapital();
        allocator.setClosed(OPERATION, 1);
        vm.prank(CONTROLLER);
        vm.expectRevert(OnchainShortfallReserveV3.OperationNotClosed.selector);
        reserve.finalizeProtectedCapital();
        assertEq(token.balanceOf(address(reserve)), 10);
        assertFalse(reserve.finalized());
    }

    function test_ExactRefundToImmutableBeneficiaryAndReplayBlocked() public {
        allocator.setClosed(OPERATION, 0);
        vm.prank(CONTROLLER);
        reserve.finalizeProtectedCapital();
        assertEq(token.balanceOf(BENEFICIARY), 10);
        assertEq(token.balanceOf(address(reserve)), 0);
        assertTrue(reserve.finalized());
        OnchainShortfallReserveV3.EconomicState memory terminal = reserve.economicState(keccak256("prepared"));
        assertEq(terminal.grossBalance, 0);
        assertEq(terminal.protectedAmount, 0);
        assertEq(terminal.availableAmount, 0);
        vm.prank(CONTROLLER);
        vm.expectRevert(OnchainShortfallReserveV3.Terminal.selector);
        reserve.finalizeProtectedCapital();
        vm.expectRevert(OnchainShortfallReserveV3.Terminal.selector);
        reserve.deposit(1);
        (bool ok,) = address(reserve).call(abi.encodeWithSignature("finalizeProtectedCapital(address)", address(0xBAD)));
        assertFalse(ok);
    }

    function test_OnlyBoundControllerCanFinalize() public {
        allocator.setClosed(OPERATION, 0);
        vm.expectRevert(abi.encodeWithSelector(OnchainShortfallReserveV3.OnlyRatificationAuthority.selector, address(this)));
        reserve.finalizeProtectedCapital();
        assertEq(token.balanceOf(BENEFICIARY), 0);
    }

    function test_UnrelatedOperationCannotBeAdmitted() public {
        IOnchainShortfallEntitlementReserveV1.EntitlementRatificationRequest memory request;
        request.operationId = keccak256("other-operation");
        vm.prank(CONTROLLER);
        vm.expectRevert(OnchainShortfallReserveV3.WrongOperation.selector);
        reserve.ratifyAndFundEntitlement(request);
        vm.prank(CONTROLLER);
        vm.expectRevert(OnchainShortfallReserveV3.WrongOperation.selector);
        reserve.activateEntitlement(request.operationId, IEntitlementRepresentationV1(address(0)));
        assertEq(token.balanceOf(address(reserve)), 10);
        assertFalse(reserve.finalized());
    }

    function test_ActiveReserveHoldBlocksRelease() public {
        token.mint(address(this), 1);
        token.approve(address(reserve), 1);
        reserve.deposit(1);
        reserve.reserveForOtherObligation(keccak256("hold"), 1, block.timestamp + 1 days);
        allocator.setClosed(OPERATION, 0);
        vm.prank(CONTROLLER);
        vm.expectRevert(OnchainShortfallReserveV3.AccountingInconsistent.selector);
        reserve.finalizeProtectedCapital();
        assertEq(token.balanceOf(address(reserve)), 11);
    }

    function test_OnlyRecordedSponsorCanFundReserve() public {
        token.mint(address(0xBAD), 1);
        vm.prank(address(0xBAD));
        vm.expectRevert(abi.encodeWithSelector(OnchainShortfallReserveV3.OnlySponsor.selector, address(0xBAD)));
        reserve.deposit(1);
        assertEq(token.balanceOf(address(reserve)), 10);
    }

    function test_PolicyBoundCommitmentIncludesBeneficiaryAndOperation() public view {
        assertTrue(reserve.policyRoot() != bytes32(0));
        OnchainShortfallReserveV3.EconomicState memory state = reserve.economicState(keccak256("prepared"));
        assertTrue(state.commitment != bytes32(0));
        assertEq(reserve.refundBeneficiary(), BENEFICIARY);
        assertEq(reserve.scopedOperationId(), OPERATION);
    }
}

/// @notice Local integration of V3 custody with the production allocator's
///         table checks, withdrawals and terminal liability state. This does
///         not exercise proof admission or a source-bound ratification service.
contract OnchainShortfallReserveV3ProductionAllocatorTest is Test {
    bytes32 private constant OP = keccak256("v3-production-allocator-shortfall");
    bytes32 private constant BINDING = keccak256("local-smoke-binding");
    bytes32 private constant PROFILE = keccak256("immutable-address-v1");
    bytes32 private constant COMMITMENT_DOMAIN = keccak256("prf.source-bound-entitlement.commitment.v1");
    bytes32 private constant TABLE_NODE_DOMAIN = keccak256("prf.source-bound-entitlement.table-node.v1");
    bytes32 private constant CLAIM_LEAF_DOMAIN = keccak256("prf.source-bound-entitlement.claim-leaf.v1");
    bytes32 private constant CLAIM_NODE_DOMAIN = keccak256("prf.source-bound-entitlement.claim-node.v1");
    bytes32 private constant SALT_A = keccak256("v3-salt-a");
    bytes32 private constant SALT_B = keccak256("v3-salt-b");
    bytes32 private constant SALT_C = keccak256("v3-salt-c");
    address private constant ALICE = address(0xA11CE);
    address private constant BOB = address(0xB0B);
    address private constant CAROL = address(0xCA701);

    V3ReserveToken private token;
    MockEntitlementProofModeSource private proofMode;
    ProductionProRataAllocatorV2 private allocator;
    OnchainShortfallReserveV3 private reserve;
    PublicPackedEntitlementRepresentationV1 private representation;
    bytes32[] private commitments;
    bytes32[] private claimLeaves;

    function setUp() public {
        token = new V3ReserveToken();
        proofMode = new MockEntitlementProofModeSource();
        allocator = new ProductionProRataAllocatorV2(address(this), proofMode);
        reserve = new OnchainShortfallReserveV3(
            token, address(this), 10, allocator, OP, address(this), CAROL,
            keccak256("local-attribution-placeholder"), keccak256("restricted-refundable-sponsor-capital")
        );
        allocator.bindFundingAuthority(address(reserve));
        reserve.bindRatificationAuthority(address(this));
        commitments = new bytes32[](3);
        claimLeaves = new bytes32[](3);
        commitments[0] = _commitment(0, "claim-a", ALICE, 35, SALT_A);
        commitments[1] = _commitment(1, "claim-b", BOB, 21, SALT_B);
        commitments[2] = _commitment(2, "claim-c", CAROL, 14, SALT_C);
        claimLeaves[0] = _claimLeaf(0, "claim-a", ALICE, 35);
        claimLeaves[1] = _claimLeaf(1, "claim-b", BOB, 21);
        claimLeaves[2] = _claimLeaf(2, "claim-c", CAROL, 14);
        representation = new PublicPackedEntitlementRepresentationV1(commitments, 70);
        token.mint(address(this), 80);
        token.approve(address(reserve), 80);
        reserve.deposit(80);
    }

    function test_ProductionAllocatorWithdrawalAndV3TerminalRefund() public {
        bytes32 preparedRoot = keccak256("local-prepared-operation-root");
        IOnchainShortfallEntitlementReserveV1.EntitlementRatificationRequest memory request =
            IOnchainShortfallEntitlementReserveV1.EntitlementRatificationRequest({
                operationId: OP,
                preparedOperationRoot: preparedRoot,
                expectedReserveStateCommitment: reserve.currentCommitment(preparedRoot),
                allocationBindingRoot: BINDING,
                descriptorRoot: keccak256("local-descriptor-root"),
                evmEntitlementTableRoot: representation.logicalRoot(),
                entitlementCount: 3,
                entitlementTotal: 70,
                allocationClaimRoot: _node(CLAIM_NODE_DOMAIN, _node(CLAIM_NODE_DOMAIN, claimLeaves[0], claimLeaves[1]), claimLeaves[2]),
                beneficiaryProfileWord: PROFILE,
                residualRecipient: address(this)
            });

        reserve.ratifyAndFundEntitlement(request);
        reserve.activateEntitlement(OP, representation);
        assertEq(token.balanceOf(address(allocator)), 70);
        assertEq(allocator.outstandingLiability(OP), 70);
        assertEq(token.balanceOf(address(reserve)), 10);

        _withdraw(0, "claim-a", ALICE, 35, SALT_A);
        assertEq(allocator.outstandingLiability(OP), 35);
        _withdraw(1, "claim-b", BOB, 21, SALT_B);
        assertEq(allocator.outstandingLiability(OP), 14);
        _withdraw(2, "claim-c", CAROL, 14, SALT_C);
        assertEq(allocator.outstandingLiability(OP), 0);
        assertEq(uint8(allocator.entitlementOperation(OP).state), uint8(IShortfallAllocatorV2.State.Closed));
        assertEq(token.balanceOf(ALICE), 35);
        assertEq(token.balanceOf(BOB), 21);
        assertEq(token.balanceOf(CAROL), 14);
        assertEq(token.balanceOf(address(allocator)), 0);
        assertEq(token.balanceOf(address(reserve)), 10);

        reserve.finalizeProtectedCapital();
        assertEq(token.balanceOf(CAROL), 24);
        assertEq(token.balanceOf(address(reserve)), 0);
        assertTrue(reserve.finalized());
    }

    function _withdraw(uint256 index, string memory claim, address beneficiary, uint256 amount, bytes32 salt) private {
        vm.prank(beneficiary);
        allocator.withdraw(
            OP,
            ProductionProRataAllocatorV2.EntitlementOpening(index, bytes(claim), beneficiary, amount, salt),
            _tablePath(index),
            false,
            new bytes32[](0),
            ProductionProRataAllocatorV2.ProofMode.Requested
        );
    }

    function _tablePath(uint256 index) private view returns (bytes32[] memory path) {
        path = new bytes32[](2);
        path[0] = commitments[index == 0 ? 1 : (index == 1 ? 0 : 2)];
        path[1] = index < 2
            ? _node(TABLE_NODE_DOMAIN, commitments[2], commitments[2])
            : _node(TABLE_NODE_DOMAIN, commitments[0], commitments[1]);
    }

    function _commitment(uint256 index, string memory claim, address beneficiary, uint256 amount, bytes32 salt)
        private
        pure
        returns (bytes32)
    {
        return keccak256(abi.encode(
            COMMITMENT_DOMAIN,
            BINDING,
            OP,
            index,
            _claimHash(claim),
            beneficiary,
            amount,
            PROFILE,
            salt
        ));
    }

    function _claimLeaf(uint256 index, string memory claim, address beneficiary, uint256 amount)
        private
        pure
        returns (bytes32)
    {
        return keccak256(abi.encode(
            CLAIM_LEAF_DOMAIN, BINDING, OP, index, _claimHash(claim), beneficiary, amount, PROFILE
        ));
    }

    function _claimHash(string memory claim) private pure returns (bytes32) {
        bytes memory encoded = bytes(claim);
        return keccak256(abi.encodePacked(bytes1(0x20), bytes1(uint8(encoded.length)), encoded));
    }

    function _node(bytes32 domain, bytes32 left, bytes32 right) private pure returns (bytes32) {
        return keccak256(abi.encodePacked(domain, left, right));
    }
}
