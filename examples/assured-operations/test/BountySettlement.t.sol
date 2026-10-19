// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

import {BountySettlement} from "../src/BountySettlement.sol";

interface VmBounty {
    function deal(address who, uint256 balance) external;
    function prank(address caller) external;
    function expectRevert(bytes4 selector) external;
    function recordLogs() external;
    function getRecordedLogs() external returns (Log[] memory);

    struct Log {
        bytes32[] topics;
        bytes data;
        address emitter;
    }
}

contract RejectingBountyBeneficiary {
    BountySettlement private immutable settlement;

    constructor(BountySettlement settlement_) {
        settlement = settlement_;
    }

    function claim(bytes32 payableId) external {
        settlement.claim(payableId);
    }

    receive() external payable {
        revert("reject payout");
    }
}

contract BountySettlementTest {
    VmBounty private constant vm = VmBounty(address(uint160(uint256(keccak256("hevm cheat code")))));
    address private constant FUNDER = address(0xf00d);
    address private constant AWARD_AUTHORITY = address(0xa11ce);
    address private constant RESEARCHER = address(0xabc123);

    BountySettlement private settlement;

    function setUp() public {
        settlement = new BountySettlement(FUNDER, AWARD_AUTHORITY);
    }

    function fundedSource(uint256 amount) private returns (bytes32 sourceRoot) {
        vm.deal(FUNDER, amount);
        vm.prank(FUNDER);
        sourceRoot = settlement.fundReserve{value: amount}();
    }

    function testAuthenticatedReserveBacksAndPaysExactAnonymousBeneficiary() public {
        bytes32 sourceRoot = fundedSource(100);
        bytes32 payableId = keccak256("award-1");
        bytes32 operationRoot = keccak256("operation-1");
        uint256 preClaimBalance = RESEARCHER.balance;

        vm.prank(AWARD_AUTHORITY);
        settlement.approveBounty(payableId, operationRoot, sourceRoot, RESEARCHER, 70);
        (,, uint256 availableBefore,) = settlement.reserveSources(sourceRoot);
        require(availableBefore == 30 && settlement.totalLiabilities() == 70);
        require(uint8(settlement.payableLifecycle(payableId)) == uint8(BountySettlement.Lifecycle.Claimable));

        vm.recordLogs();
        vm.prank(RESEARCHER);
        settlement.claim(payableId);
        VmBounty.Log[] memory logs = vm.getRecordedLogs();

        require(logs.length == 1 && logs[0].emitter == address(settlement));
        require(
            logs[0].topics[0]
                == keccak256(
                    "BountyPaid(bytes32,bytes32,bytes32,address,uint256,uint256,bytes32,bytes32,uint256,uint256,uint256,uint256)"
                )
        );
        (
            address beneficiary,
            uint256 amount,
            uint256 sequence,
            bytes32 predecessorRoot,
            bytes32 successorRoot,
            uint256 reserveBefore,
            uint256 reserveAfter,
            uint256 eventBeneficiaryBefore,
            uint256 beneficiaryAfter
        ) = abi.decode(logs[0].data, (address, uint256, uint256, bytes32, bytes32, uint256, uint256, uint256, uint256));
        require(beneficiary == RESEARCHER && amount == 70 && sequence == 1);
        require(predecessorRoot != bytes32(0) && successorRoot != predecessorRoot);
        require(reserveBefore == 100 && reserveAfter == 30);
        require(eventBeneficiaryBefore == preClaimBalance && beneficiaryAfter == preClaimBalance + 70);
        require(RESEARCHER.balance == preClaimBalance + 70);
        require(settlement.totalLiabilities() == 0 && settlement.totalPaid() == 70);
        require(uint8(settlement.payableLifecycle(payableId)) == uint8(BountySettlement.Lifecycle.Settled));

        vm.expectRevert(BountySettlement.AlreadySettled.selector);
        vm.prank(RESEARCHER);
        settlement.claim(payableId);
    }

    function testUnauthenticatedOrUnfundedBountyCannotBecomeClaimable() public {
        bytes32 sourceRoot = fundedSource(50);
        bytes32 payableId = keccak256("award-2");
        vm.prank(address(0xbad));
        vm.expectRevert(BountySettlement.Unauthorized.selector);
        settlement.approveBounty(payableId, keccak256("op"), sourceRoot, RESEARCHER, 20);

        vm.prank(AWARD_AUTHORITY);
        vm.expectRevert(BountySettlement.InvalidSource.selector);
        settlement.approveBounty(payableId, keccak256("op"), keccak256("unknown"), RESEARCHER, 20);

        vm.prank(AWARD_AUTHORITY);
        vm.expectRevert(BountySettlement.InvalidSource.selector);
        settlement.approveBounty(payableId, keccak256("op"), sourceRoot, RESEARCHER, 51);
        require(settlement.totalLiabilities() == 0);
        require(uint8(settlement.payableLifecycle(payableId)) == uint8(BountySettlement.Lifecycle.None));
    }

    function testRejectedDirectTransferLeavesLiabilityClaimable() public {
        bytes32 sourceRoot = fundedSource(40);
        bytes32 payableId = keccak256("award-3");
        RejectingBountyBeneficiary recipient = new RejectingBountyBeneficiary(settlement);
        vm.prank(AWARD_AUTHORITY);
        settlement.approveBounty(payableId, keccak256("op-3"), sourceRoot, address(recipient), 40);

        vm.expectRevert(BountySettlement.TransferFailed.selector);
        recipient.claim(payableId);

        require(settlement.totalLiabilities() == 40 && settlement.totalPaid() == 0);
        require(uint8(settlement.payableLifecycle(payableId)) == uint8(BountySettlement.Lifecycle.Claimable));
    }
}
