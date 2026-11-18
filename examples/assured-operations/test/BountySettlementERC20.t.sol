// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

import {BountySettlement, IERC20BountySettlement} from "../src/BountySettlement.sol";

interface VmBountyERC20 {
    function deal(address who, uint256 balance) external;
    function prank(address caller) external;
    function expectRevert(bytes4 selector) external;
}

contract MockBountyERC20 is IERC20BountySettlement {
    mapping(address => uint256) public override balanceOf;
    mapping(address => mapping(address => uint256)) public allowance;
    uint256 public feeBps;
    bool public returnFalse;
    address public callbackTarget;
    bytes public callbackData;
    bool public callbackAttempted;
    bool public callbackSucceeded;

    function mint(address account, uint256 amount) external {
        balanceOf[account] += amount;
    }

    function setFeeBps(uint256 feeBps_) external {
        feeBps = feeBps_;
    }

    function setReturnFalse(bool value) external {
        returnFalse = value;
    }

    function setCallback(address target, bytes calldata data) external {
        callbackTarget = target;
        callbackData = data;
    }

    function approve(address spender, uint256 amount) external returns (bool) {
        allowance[msg.sender][spender] = amount;
        return true;
    }

    function transfer(address recipient, uint256 amount) external override returns (bool) {
        if (returnFalse) return false;
        _move(msg.sender, recipient, amount);
        return true;
    }

    function transferFrom(address sender, address recipient, uint256 amount) external override returns (bool) {
        if (returnFalse) return false;
        uint256 permitted = allowance[sender][msg.sender];
        require(permitted >= amount, "allowance exceeded");
        if (permitted != type(uint256).max) allowance[sender][msg.sender] = permitted - amount;
        _move(sender, recipient, amount);
        return true;
    }

    function _move(address sender, address recipient, uint256 amount) private {
        require(balanceOf[sender] >= amount, "balance exceeded");
        if (callbackTarget != address(0)) {
            callbackAttempted = true;
            (callbackSucceeded,) = callbackTarget.call(callbackData);
        }
        uint256 fee = amount * feeBps / 10_000;
        balanceOf[sender] -= amount;
        balanceOf[recipient] += amount - fee;
    }
}

    contract BountySettlementERC20Test {
        VmBountyERC20 private constant vm = VmBountyERC20(address(uint160(uint256(keccak256("hevm cheat code")))));
        address private constant FUNDER = address(0xf00d);
        address private constant AWARD_AUTHORITY = address(0xa11ce);
        address private constant RESEARCHER = address(0xabc123);
        uint256 private constant FUNDING = 1000;

        BountySettlement private settlement;
        MockBountyERC20 private token;

        function setUp() public {
            settlement = new BountySettlement(FUNDER, AWARD_AUTHORITY);
            token = new MockBountyERC20();
            token.mint(FUNDER, FUNDING);
        }

        function _fund() private returns (bytes32 sourceRoot) {
            vm.prank(FUNDER);
            token.approve(address(settlement), FUNDING);
            vm.prank(FUNDER);
            sourceRoot = settlement.fundTokenReserve(IERC20BountySettlement(address(token)), FUNDING);
        }

        function _approve(bytes32 payableId, bytes32 sourceRoot, uint256 amount) private {
            vm.prank(AWARD_AUTHORITY);
            settlement.approveTokenBounty(
                payableId,
                keccak256("operation"),
                sourceRoot,
                IERC20BountySettlement(address(token)),
                RESEARCHER,
                amount
            );
        }

        function testFundApproveAndClaimExactTokenAmountOnce() public {
            bytes32 sourceRoot = _fund();
            bytes32 payableId = keccak256("obligation");
            uint256 amount = 725;

            _approve(payableId, sourceRoot, amount);
            (,, uint256 available,) = settlement.reserveSources(sourceRoot);
            require(available == FUNDING - amount);
            require(token.balanceOf(address(settlement)) == FUNDING);
            require(settlement.totalTokenLiabilities(address(token)) == amount);

            vm.prank(RESEARCHER);
            settlement.claim(payableId);

            require(token.balanceOf(RESEARCHER) == amount);
            require(token.balanceOf(address(settlement)) == FUNDING - amount);
            require(settlement.totalTokenLiabilities(address(token)) == 0);
            require(settlement.totalTokenPaid(address(token)) == amount);
            require(uint8(settlement.payableLifecycle(payableId)) == uint8(BountySettlement.Lifecycle.Settled));

            vm.expectRevert(BountySettlement.AlreadySettled.selector);
            vm.prank(RESEARCHER);
            settlement.claim(payableId);

            vm.expectRevert(BountySettlement.InvalidPayable.selector);
            _approve(payableId, sourceRoot, 1);
        }

        function testOnlyConfiguredAuthoritiesCanFundOrApproveTokenBounty() public {
            vm.prank(address(0xbad));
            vm.expectRevert(BountySettlement.Unauthorized.selector);
            settlement.fundTokenReserve(IERC20BountySettlement(address(token)), 1);

            bytes32 sourceRoot = _fund();
            vm.prank(address(0xbad));
            vm.expectRevert(BountySettlement.Unauthorized.selector);
            settlement.approveTokenBounty(
                keccak256("obligation"),
                keccak256("operation"),
                sourceRoot,
                IERC20BountySettlement(address(token)),
                RESEARCHER,
                1
            );
        }

        function testUnfundedOrWrongTokenSourceCannotCreatePayable() public {
            bytes32 sourceRoot = _fund();
            MockBountyERC20 otherToken = new MockBountyERC20();

            vm.prank(AWARD_AUTHORITY);
            vm.expectRevert(BountySettlement.InvalidSource.selector);
            settlement.approveTokenBounty(
                keccak256("obligation"),
                keccak256("operation"),
                keccak256("unknown"),
                IERC20BountySettlement(address(token)),
                RESEARCHER,
                1
            );

            vm.prank(AWARD_AUTHORITY);
            vm.expectRevert(BountySettlement.InvalidSource.selector);
            settlement.approveTokenBounty(
                keccak256("obligation"),
                keccak256("operation"),
                sourceRoot,
                IERC20BountySettlement(address(otherToken)),
                RESEARCHER,
                1
            );

            vm.prank(AWARD_AUTHORITY);
            vm.expectRevert(BountySettlement.InvalidSource.selector);
            settlement.approveTokenBounty(
                keccak256("obligation"),
                keccak256("operation"),
                sourceRoot,
                IERC20BountySettlement(address(token)),
                RESEARCHER,
                FUNDING + 1
            );

            require(settlement.totalTokenLiabilities(address(token)) == 0);
        }

        function testFeeOnTransferFundingRevertsWithoutRecordingReserve() public {
            token.setFeeBps(100);
            vm.prank(FUNDER);
            token.approve(address(settlement), FUNDING);

            vm.prank(FUNDER);
            vm.expectRevert(BountySettlement.BalanceDeltaMismatch.selector);
            settlement.fundTokenReserve(IERC20BountySettlement(address(token)), FUNDING);

            require(token.balanceOf(FUNDER) == FUNDING);
            require(token.balanceOf(address(settlement)) == 0);
            require(settlement.nextFundingSequence() == 0);
        }

        function testFeeOnTransferClaimRevertsAndLeavesPayableClaimable() public {
            bytes32 sourceRoot = _fund();
            bytes32 payableId = keccak256("obligation");
            uint256 amount = 500;
            _approve(payableId, sourceRoot, amount);
            token.setFeeBps(100);

            vm.prank(RESEARCHER);
            vm.expectRevert(BountySettlement.BalanceDeltaMismatch.selector);
            settlement.claim(payableId);

            require(token.balanceOf(address(settlement)) == FUNDING);
            require(token.balanceOf(RESEARCHER) == 0);
            require(settlement.totalTokenLiabilities(address(token)) == amount);
            require(uint8(settlement.payableLifecycle(payableId)) == uint8(BountySettlement.Lifecycle.Claimable));
        }

        function testFalseReturnFromTokenRevertsFundingAndClaimAtomically() public {
            token.setReturnFalse(true);
            vm.prank(FUNDER);
            token.approve(address(settlement), FUNDING);
            vm.prank(FUNDER);
            vm.expectRevert(BountySettlement.TransferFailed.selector);
            settlement.fundTokenReserve(IERC20BountySettlement(address(token)), FUNDING);
            require(settlement.nextFundingSequence() == 0);

            token.setReturnFalse(false);
            bytes32 sourceRoot = _fund();
            bytes32 payableId = keccak256("obligation");
            _approve(payableId, sourceRoot, 500);
            token.setReturnFalse(true);

            vm.prank(RESEARCHER);
            vm.expectRevert(BountySettlement.TransferFailed.selector);
            settlement.claim(payableId);
            require(settlement.totalTokenLiabilities(address(token)) == 500);
            require(uint8(settlement.payableLifecycle(payableId)) == uint8(BountySettlement.Lifecycle.Claimable));
        }

        function testNonBeneficiaryCannotClaim() public {
            bytes32 sourceRoot = _fund();
            bytes32 payableId = keccak256("obligation");
            _approve(payableId, sourceRoot, 500);

            vm.prank(address(0xbad));
            vm.expectRevert(BountySettlement.NotBeneficiary.selector);
            settlement.claim(payableId);
        }

        function testTokenCallbackCannotReenterAnotherClaim() public {
            bytes32 sourceRoot = _fund();
            bytes32 payableId = keccak256("obligation");
            _approve(payableId, sourceRoot, 500);
            token.setCallback(address(settlement), abi.encodeCall(settlement.claim, (payableId)));

            vm.prank(RESEARCHER);
            settlement.claim(payableId);

            require(token.callbackAttempted());
            require(!token.callbackSucceeded());
            require(token.balanceOf(RESEARCHER) == 500);
            require(settlement.totalTokenLiabilities(address(token)) == 0);
            require(settlement.totalTokenPaid(address(token)) == 500);
        }
    }
