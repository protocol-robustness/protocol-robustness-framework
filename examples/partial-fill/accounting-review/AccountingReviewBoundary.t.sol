// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

import {PartialFillV3ControlledTest} from "./PartialFillV3Controlled.t.sol";
import {PrfSourceBoundEntitlementVerifierAdapterV1 as Adapter} from "../src/verification/adapters/PrfSourceBoundEntitlementVerifierAdapterV1.sol";
import {console2} from "forge-std/console2.sol";

/// Accounting signatures and adoption roots are not operation assurance.
/// The fixture is an actually signed PRF accounting record, verified offline.
contract AccountingReviewBoundaryTest is PartialFillV3ControlledTest {
    function _boundaryDigest() private view returns (bytes32) {
        return keccak256(abi.encode(
            token.balanceOf(address(reserve)), token.balanceOf(address(allocator)),
            token.balanceOf(ALICE), token.balanceOf(BOB), token.balanceOf(CAROL),
            prepared.state(OP), ratification.state(OP),
            allocator.entitlementOperation(OP).state, allocator.claimedAmount(OP),
            allocator.claimedCount(OP), allocator.outstandingLiability(OP),
            admission.admittedCount(), reserve.totalReserved(),
            reserve.availabilityRevision(), reserve.nextSnapshotId(),
            token.allowance(address(reserve), address(allocator))
        ));
    }

    function test_AccountingRatificationCannotAuthorizeCapital() public {
        require(stage == 2, "Accounting boundary qualification requires PARTIAL_FILL_STAGE=2");
        string memory fixture = "test/fixtures/accounting-review/";
        string memory identity = vm.readFile(string.concat(fixture, "evm-input.json"));
        bytes memory record = bytes(vm.readFile(string.concat(fixture, "accounting-ratification.edn")));
        bytes32 accountingRoot = vm.parseJsonBytes32(identity, ".accountingRatificationRoot");
        assertEq(sha256(record), vm.parseJsonBytes32(identity, ".recordFileSha256"));
        assertTrue(accountingRoot != bytes32(0));
        bytes memory values = vm.parseJsonBytes(vm.readFile(string.concat(DIR, "proof.json")), ".public_values");
        Adapter.SourceBoundEntitlementPublicValuesV1 memory pv = adapter.decodePublicValues(values);
        bytes32 before = _boundaryDigest();

        // A genuine accounting-signed record is not an SP1 proof.
        vm.expectRevert();
        admission.admitStatement(verifierId, pv.allocationBindingRoot, record, values);
        assertEq(_boundaryDigest(), before);

        // The accounting adoption root is not an admitted operation receipt.
        vm.expectRevert();
        assurance.prepareSourceAssurance(OP, accountingRoot, values);
        assertEq(_boundaryDigest(), before);

        // Accounting adoption does not appoint an execution caller.
        vm.expectRevert();
        ratification.ratifyAndFund(OP, reserveRoot, address(this));
        assertEq(_boundaryDigest(), before);
        assertEq(token.balanceOf(address(reserve)), 80);
        assertEq(token.balanceOf(address(allocator)), 0);
        assertEq(allocator.outstandingLiability(OP), 0);
        console2.log("ACCOUNTING_RATIFICATION_ROOT");
        console2.logBytes32(accountingRoot);
        console2.log("ACCOUNTING_RECORD_FILE_SHA256");
        console2.logBytes32(sha256(record));
        console2.log("Three capital-boundary attempts rejected; checked capital and operation state unchanged; local EVM only");
    }
}
