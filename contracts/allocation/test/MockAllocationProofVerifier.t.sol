// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

import {Test} from "forge-std/Test.sol";
import {MockAllocationProofVerifier} from "../MockAllocationProofVerifier.sol";

contract MockAllocationProofVerifierTest is Test {
    MockAllocationProofVerifier verifier;

    bytes32 constant PROGRAM_KEY = keccak256("allocation-kernel.v1");
    bytes32 constant MOCK_COMMITMENT = keccak256("mock-proof");

    function setUp() public {
        verifier = new MockAllocationProofVerifier(MOCK_COMMITMENT, PROGRAM_KEY);
    }

    function test_acceptsExactCommitment() public {
        bytes memory proof = hex"deadbeef";
        bytes memory publicValues = hex"c0ffee";
        bytes32 commitment = keccak256(abi.encodePacked(proof, PROGRAM_KEY, publicValues));
        // Deploy a fresh verifier with this exact commitment to accept it.
        MockAllocationProofVerifier accepting =
            new MockAllocationProofVerifier(commitment, PROGRAM_KEY);
        assertTrue(accepting.verifyAllocationProof(proof, PROGRAM_KEY, publicValues));
    }

    function test_rejectsArbitraryProofData() public view {
        bytes memory proof = hex"deadbeef";
        bytes memory publicValues = hex"c0ffee";
        // MOCK_COMMITMENT does not match keccak256(proof || key || values), so
        // verification must fail.
        assertFalse(verifier.verifyAllocationProof(proof, PROGRAM_KEY, publicValues));
    }

    function test_rejectsWrongProgramKey() public view {
        bytes memory proof = hex"deadbeef";
        bytes memory publicValues = hex"c0ffee";
        assertFalse(verifier.verifyAllocationProof(proof, bytes32(0), publicValues));
    }

    function test_rejectsEmptyProof() public view {
        assertFalse(verifier.verifyAllocationProof(bytes(""), PROGRAM_KEY, bytes("")));
    }
}
