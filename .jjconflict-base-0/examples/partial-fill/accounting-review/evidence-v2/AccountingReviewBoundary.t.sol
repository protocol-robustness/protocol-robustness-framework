// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

import {PartialFillV3ControlledTest} from "./PartialFillV3Controlled.t.sol";
import {SP1Verifier} from "@sp1-contracts/v6.1.0/SP1VerifierGroth16.sol";
import {PrfStatementAdmission} from "../src/core/PrfStatementAdmission.sol";
import {IPrfVerifierRegistry} from "../src/interfaces/IPrfVerifierRegistry.sol";
import {PrfSourceBoundEntitlementVerifierAdapterV1 as Adapter} from "../src/verification/adapters/PrfSourceBoundEntitlementVerifierAdapterV1.sol";
import {SourceBoundProRataEntitlementAssuranceV1 as Assurance} from "../src/applications/shortfall/SourceBoundProRataEntitlementAssuranceV1.sol";
import {SourceBoundProRataEntitlementRatificationControllerV1 as Ratification} from "../src/applications/shortfall/SourceBoundProRataEntitlementRatificationControllerV1.sol";
import {console2} from "forge-std/console2.sol";

/// Accounting signatures and adoption roots are not operation assurance.
///
/// These tests exercise each authorization gate independently, with exact
/// revert selectors (never a bare `expectRevert`):
///   * SP1 proof verification (the real Groth16 verifier),
///   * the admission gate,
///   * the assurance layer,
///   * execution-caller authorization.
/// Each negative asserts the full authorization/balance/liability digest is
/// unchanged. A positive control proves the same prerequisite path succeeds
/// under valid capital authority, so the negatives are not passing vacuously.
contract AccountingReviewBoundaryTest is PartialFillV3ControlledTest {
    string constant AFIX = "test/fixtures/accounting-review/";

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

    function _realProof() private view returns (bytes memory proof, bytes memory values) {
        string memory fixture = vm.readFile(string.concat(DIR, "proof.json"));
        return (vm.parseJsonBytes(fixture, ".proof"), vm.parseJsonBytes(fixture, ".public_values"));
    }

    function _accountingRecord(string memory file, string memory rootField)
        private
        view
        returns (bytes memory record, bytes32 root)
    {
        string memory identity = vm.readFile(string.concat(AFIX, "evm-input.json"));
        record = bytes(vm.readFile(string.concat(AFIX, file)));
        root = vm.parseJsonBytes32(identity, rootField);
    }

    /// Positive control: the legitimate capital-authorization path succeeds, so
    /// the negative tests below are not passing for lack of a working route.
    function test_PositiveControl_ValidCapitalAuthority() public {
        require(stage == 2, "requires PARTIAL_FILL_STAGE=2");
        (bytes memory proof, bytes memory values) = _realProof();
        Adapter.SourceBoundEntitlementPublicValuesV1 memory pv = adapter.decodePublicValues(values);
        bytes32 before = _boundaryDigest();
        bytes32 receipt = admission.admitStatement(verifierId, pv.allocationBindingRoot, proof, values);
        assurance.prepareSourceAssurance(OP, receipt, values);
        ratification.registerPrepared(OP);
        operator.fund(reserveRoot);
        assertEq(token.balanceOf(address(reserve)), 10);
        assertEq(token.balanceOf(address(allocator)), 70);
        assertTrue(_boundaryDigest() != before, "valid authority must change state");
    }

    /// Each gate is exercised in isolation for one authenticated accounting
    /// record. The record reaches the real SP1 verifier directly, the admission
    /// wrapper, the assurance layer, and finally execution-caller authorization.
    function _assertRecordRejected(bytes32 root, bytes memory record, bytes memory values) private {
        Adapter.SourceBoundEntitlementPublicValuesV1 memory pv = adapter.decodePublicValues(values);
        bytes32 before = _boundaryDigest();

        // Gate 1 (SP1 proof verification): the record's leading bytes are not the
        // verifier selector. Exact revert data, not a generic revert.
        bytes4 received;
        assembly ("memory-safe") { received := mload(add(record, 32)) }
        vm.expectRevert(abi.encodeWithSelector(
            SP1Verifier.WrongVerifierSelector.selector, received, bytes4(verifier.VERIFIER_HASH())));
        adapter.verifyStatementProof(pv.allocationBindingRoot, record, values);

        // Gate 2 (admission): the adapter failure is surfaced as ProofVerificationFailed.
        vm.expectRevert(abi.encodeWithSelector(PrfStatementAdmission.ProofVerificationFailed.selector));
        admission.admitStatement(verifierId, pv.allocationBindingRoot, record, values);

        // Gate 3 (assurance): the accounting root is not an admitted receipt.
        vm.expectRevert(abi.encodeWithSelector(Assurance.InvalidReceipt.selector));
        assurance.prepareSourceAssurance(OP, root, values);

        assertEq(_boundaryDigest(), before, "no state change on rejected records");
    }

    function test_AccountingRatificationCannotAuthorizeCapital() public {
        require(stage == 2, "requires PARTIAL_FILL_STAGE=2");
        (bytes memory allocRecord, bytes32 allocRoot) = _accountingRecord("accounting-ratification.edn", ".accountingRatificationRoot");
        (bytes memory realizedRecord, bytes32 realizedRoot) = _accountingRecord("accounting-ratification-realized.edn", ".realizedAccountingRatificationRoot");
        (, bytes memory values) = _realProof();
        assertTrue(allocRoot != bytes32(0) && realizedRoot != bytes32(0) && allocRoot != realizedRoot);

        _assertRecordRejected(allocRoot, allocRecord, values);
        _assertRecordRejected(realizedRoot, realizedRecord, values);

        // Gate 4 (execution-caller authorization): a non-caller cannot fund even
        // though the caller check precedes every other stateful condition.
        bytes32 before = _boundaryDigest();
        vm.expectRevert(abi.encodeWithSelector(Ratification.OnlyExecutionCaller.selector, address(this)));
        ratification.ratifyAndFund(OP, reserveRoot, address(this));
        assertEq(_boundaryDigest(), before);

        console2.log("ACCOUNTING_RATIFICATION_ROOT");
        console2.logBytes32(allocRoot);
        console2.log("REALIZED_ACCOUNTING_RATIFICATION_ROOT");
        console2.logBytes32(realizedRoot);
        console2.log("ACCOUNTING_RECORD_FILE_SHA256");
        console2.logBytes32(sha256(allocRecord));
        console2.log("REALIZED_ACCOUNTING_RECORD_FILE_SHA256");
        console2.logBytes32(sha256(realizedRecord));
        console2.log("Both authenticated records rejected at proof/admission/assurance/caller gates with exact selectors; checked capital and operation state unchanged; local EVM only");
    }

    /// Invalid verification payload: exact adapter error with the actual length.
    function test_RejectInvalidVerificationPayload() public {
        require(stage == 2, "requires PARTIAL_FILL_STAGE=2");
        (bytes memory proof, bytes memory values) = _realProof();
        Adapter.SourceBoundEntitlementPublicValuesV1 memory pv = adapter.decodePublicValues(values);
        bytes memory malformed = new bytes(287);
        bytes32 before = _boundaryDigest();
        vm.expectRevert(abi.encodeWithSelector(Adapter.InvalidPublicValuesLength.selector, uint256(287)));
        adapter.decodePublicValues(malformed);
        vm.expectRevert(abi.encodeWithSelector(PrfStatementAdmission.ProofVerificationFailed.selector));
        admission.admitStatement(verifierId, pv.allocationBindingRoot, proof, malformed);
        assertEq(_boundaryDigest(), before);
    }

    /// Admission-gate rejection under otherwise valid prerequisites: a real proof
    /// with a mismatched expected statement root fails at the adapter root check.
    function test_RejectValidProofWithMismatchedStatementRoot() public {
        require(stage == 2, "requires PARTIAL_FILL_STAGE=2");
        (bytes memory proof, bytes memory values) = _realProof();
        bytes32 actual = adapter.decodePublicValues(values).allocationBindingRoot;
        bytes32 wrong = keccak256("not-the-committed-binding-root");
        bytes32 before = _boundaryDigest();
        vm.expectRevert(abi.encodeWithSelector(Adapter.StatementRootMismatch.selector, wrong, actual));
        adapter.verifyStatementProof(wrong, proof, values);
        vm.expectRevert(abi.encodeWithSelector(PrfStatementAdmission.ProofVerificationFailed.selector));
        admission.admitStatement(verifierId, wrong, proof, values);
        assertEq(_boundaryDigest(), before);
    }

    /// Admission gate rejects an unknown verifier registry entry.
    function test_RejectUnregisteredVerifierId() public {
        require(stage == 2, "requires PARTIAL_FILL_STAGE=2");
        (bytes memory proof, bytes memory values) = _realProof();
        bytes32 actual = adapter.decodePublicValues(values).allocationBindingRoot;
        bytes32 unknownId = keccak256("unknown-verifier");
        bytes32 activeRoot = executor.currentVerifierRegistryRoot();
        bytes32 before = _boundaryDigest();
        vm.expectRevert(abi.encodeWithSelector(IPrfVerifierRegistry.VerifierNotFound.selector, activeRoot, unknownId));
        admission.admitStatement(unknownId, actual, proof, values);
        assertEq(_boundaryDigest(), before);
    }

    /// Execution-caller authorization under otherwise valid prerequisites: with a
    /// real proof admitted and assurance prepared, a non-caller cannot fund, but
    /// the bound operator can.
    function test_ExecutionCallerAuthorizationUnderValidPrerequisites() public {
        require(stage == 2, "requires PARTIAL_FILL_STAGE=2");
        (bytes memory proof, bytes memory values) = _realProof();
        bytes32 actual = adapter.decodePublicValues(values).allocationBindingRoot;
        bytes32 receipt = admission.admitStatement(verifierId, actual, proof, values);
        assurance.prepareSourceAssurance(OP, receipt, values);
        ratification.registerPrepared(OP);

        bytes32 preparedDigest = _boundaryDigest();
        vm.expectRevert(abi.encodeWithSelector(Ratification.OnlyExecutionCaller.selector, address(this)));
        ratification.ratifyAndFund(OP, reserveRoot, address(this));
        assertEq(_boundaryDigest(), preparedDigest, "rejected caller must not change state");

        operator.fund(reserveRoot);
        assertEq(token.balanceOf(address(reserve)), 10);
        assertEq(token.balanceOf(address(allocator)), 70);
    }
}
