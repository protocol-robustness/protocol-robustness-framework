// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

import {Test} from "forge-std/Test.sol";
import {SP1Verifier} from "@sp1-contracts/v6.1.0/SP1VerifierGroth16.sol";
import {ISP1Verifier} from "../src/verification/interfaces/ISP1Verifier.sol";
import {
    PrfSourceBoundEntitlementVerifierAdapterV1 as Adapter
} from "../src/verification/adapters/PrfSourceBoundEntitlementVerifierAdapterV1.sol";

/// @notice Checks the actual V3-context proof against the deployed EVM Groth16 verifier.
/// This does not perform registry admission or execute the V3 reserve lifecycle.
contract PartialFillV3LiveProofVerificationTest is Test {
    string private constant FIXTURE = "test/fixtures/partial-fill-v3/proof.json";
    bytes32 private constant VKEY = 0x00691e1cff8e4252731c8fe2bc326e8725ed5f9b92a298cc30a2c5fc40962dd1;
    bytes32 private constant ELF_SHA256 = 0x705efd1c5b997ae1f2c19eddcf6bb66a147e94b6b3b036393d6dab041c00542d;
    bytes32 private constant RESERVE_STATE_COMMITMENT = 0xa40e4db35601330b194a1e4b03965f0804f613eab979f1b850b313e09f1dfd0c;
    bytes32 private constant PREPARED_OPERATION_ROOT = 0xc5897bf4d7d707dd04143c307cab6a62c790273e4d1592297c94cdd32261f799;

    function test_ActualV3ContextProofPassesEvmVerifierAndAlterationFails() public {
        string memory fixture = vm.readFile(FIXTURE);
        assertEq(abi.decode(vm.parseJson(fixture, ".program_id"), (string)), "source-bound-entitlement-controlled-sp1-program.v1");
        assertEq(abi.decode(vm.parseJson(fixture, ".proof_system"), (string)), "groth16");
        assertEq(abi.decode(vm.parseJson(fixture, ".vkey"), (bytes32)), VKEY);
        assertEq(abi.decode(vm.parseJson(fixture, ".elf_sha256"), (bytes32)), ELF_SHA256);

        bytes memory proof = abi.decode(vm.parseJson(fixture, ".proof"), (bytes));
        bytes memory publicValues = abi.decode(vm.parseJson(fixture, ".public_values"), (bytes));
        assertEq(publicValues.length, 288);

        SP1Verifier verifier = new SP1Verifier();
        Adapter adapter = new Adapter(ISP1Verifier(address(verifier)), VKEY, ELF_SHA256);
        Adapter.SourceBoundEntitlementPublicValuesV1 memory values = adapter.decodePublicValues(publicValues);
        assertEq(values.preparedOperationRoot, PREPARED_OPERATION_ROOT);
        assertEq(values.reserveStateCommitment, RESERVE_STATE_COMMITMENT);
        assertEq(values.entitlementCount, 3);
        assertEq(values.entitlementTotal, 70);
        assertEq(adapter.verifyStatementProof(values.allocationBindingRoot, proof, publicValues), values.allocationBindingRoot);

        proof[4] = bytes1(uint8(proof[4]) ^ 1);
        vm.expectRevert();
        adapter.verifyStatementProof(values.allocationBindingRoot, proof, publicValues);
    }
}
