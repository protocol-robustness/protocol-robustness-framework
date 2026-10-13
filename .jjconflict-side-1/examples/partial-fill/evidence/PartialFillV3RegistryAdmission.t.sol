// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

import {Test, console2} from "forge-std/Test.sol";
import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {SP1Verifier} from "@sp1-contracts/v6.1.0/SP1VerifierGroth16.sol";
import {ISP1Verifier} from "../src/verification/interfaces/ISP1Verifier.sol";
import {PrfVerifierRegistry} from "../src/core/PrfVerifierRegistry.sol";
import {PrfChainInstanceGenesis} from "../src/core/PrfChainInstanceGenesis.sol";
import {PrfStatementAdmission} from "../src/core/PrfStatementAdmission.sol";
import {SewGovernanceAuthorityAdapter} from "../src/integrations/sew/SewGovernanceAuthorityAdapter.sol";
import {SewConfigurationExecutor} from "../src/integrations/sew/SewConfigurationExecutor.sol";
import {
    PrfSourceBoundEntitlementVerifierAdapterV1 as Adapter
} from "../src/verification/adapters/PrfSourceBoundEntitlementVerifierAdapterV1.sol";

contract PartialFillV3AdmissionToken is ERC20 {
    constructor() ERC20("Controlled local units", "LOCAL") {}
}

/// @notice Reconstructs the exact verifier/configuration/genesis admission environment
/// for the actual V3-context proof, without relying on a V3 reserve contract source file.
contract PartialFillV3RegistryAdmissionTest is Test {
    string private constant DIR = "test/fixtures/partial-fill-v3/";
    string private constant PROGRAM = "source-bound-entitlement-controlled-sp1-program.v1";
    bytes32 private constant VKEY = 0x00691e1cff8e4252731c8fe2bc326e8725ed5f9b92a298cc30a2c5fc40962dd1;
    bytes32 private constant ELF_SHA256 = 0x705efd1c5b997ae1f2c19eddcf6bb66a147e94b6b3b036393d6dab041c00542d;

    PartialFillV3AdmissionToken private token;
    SP1Verifier private verifier;
    Adapter private adapter;
    PrfVerifierRegistry private registry;
    PrfChainInstanceGenesis private genesis;
    SewGovernanceAuthorityAdapter private authority;
    SewConfigurationExecutor private executor;
    PrfStatementAdmission private admission;
    bytes32 private registryRoot;
    bytes32 private verifierId;
    bytes32 private configurationRoot;

    function setUp() public {
        vm.chainId(31337);
        string memory identity = vm.readFile(string.concat(DIR, "identity.json"));
        assertEq(vm.parseJsonBytes32(identity, ".vkey"), VKEY);
        assertEq(vm.parseJsonBytes32(identity, ".elf_sha256"), ELF_SHA256);

        // Keep this deployment order aligned with the staged V3 context fixture:
        // token, verifier, adapter, registry, authority, genesis.
        token = new PartialFillV3AdmissionToken();
        verifier = new SP1Verifier();
        adapter = new Adapter(ISP1Verifier(address(verifier)), VKEY, ELF_SHA256);
        registry = new PrfVerifierRegistry(address(this));
        verifierId = keccak256(bytes(PROGRAM));
        _registerVerifier();

        string memory config = vm.readFile(string.concat(DIR, "configuration.json"));
        configurationRoot = vm.parseJsonBytes32(config, ".configurationRoot");
        bytes32 protocolRoot = vm.parseJsonBytes32(config, ".protocolRoot");
        bytes32 authorityRoot = vm.parseJsonBytes32(config, ".authorityRoot");
        authority = new SewGovernanceAuthorityAdapter(protocolRoot, address(this), authorityRoot);
        genesis = new PrfChainInstanceGenesis(
            protocolRoot, 31337, 31337, address(authority), authorityRoot, configurationRoot, address(this)
        );
        string memory chain = vm.readFile(string.concat(DIR, "genesis.json"));
        genesis.sealGenesis(
            vm.parseJsonBytes32(chain, ".chainRoot"), address(genesis).codehash, address(authority).codehash
        );
        executor = new SewConfigurationExecutor(genesis, authority, registry, registryRoot);
        admission = new PrfStatementAdmission(genesis, executor, registry, configurationRoot, 0);
    }

    function _registerVerifier() private {
        bytes32[] memory ids = new bytes32[](1);
        ids[0] = verifierId;
        address[] memory adapters = new address[](1);
        adapters[0] = address(adapter);
        bytes32[] memory keys = new bytes32[](1);
        keys[0] = VKEY;
        bytes32[] memory elfs = new bytes32[](1);
        elfs[0] = ELF_SHA256;
        string[] memory programs = new string[](1);
        programs[0] = PROGRAM;
        bytes32[] memory schemas = new bytes32[](1);
        schemas[0] = adapter.statementSchemaVersion();
        bytes32[] memory publicSchemas = new bytes32[](1);
        publicSchemas[0] = adapter.publicValuesSchema();
        bool[] memory active = new bool[](1);
        active[0] = true;
        registryRoot = registry.computeCanonicalRoot(ids, adapters, keys, elfs, programs, schemas, publicSchemas, active);
        registry.registerVerifier(
            registryRoot, verifierId, address(adapter), VKEY, ELF_SHA256, PROGRAM, schemas[0], publicSchemas[0]
        );
        registry.sealRegistry(registryRoot);
    }

    function test_ActualV3ContextProofAdmitsUnderRetainedRegistryConfigurationAndGenesis() public {
        string memory fixture = vm.readFile(string.concat(DIR, "proof.json"));
        bytes memory proof = vm.parseJsonBytes(fixture, ".proof");
        bytes memory values = vm.parseJsonBytes(fixture, ".public_values");
        Adapter.SourceBoundEntitlementPublicValuesV1 memory publicValues = adapter.decodePublicValues(values);
        assertEq(publicValues.entitlementCount, 3);
        assertEq(publicValues.entitlementTotal, 70);
        bytes32 receipt = admission.admitStatement(verifierId, publicValues.allocationBindingRoot, proof, values);
        assertTrue(receipt != bytes32(0));
        PrfStatementAdmission.AdmissionReceipt memory admitted = admission.admissionReceipt(receipt);
        assertEq(admitted.configurationRoot, configurationRoot);
        assertEq(admitted.verifierRegistryRoot, registryRoot);
        assertEq(admission.admittedCount(), 1);
        console2.log("V3_ADMISSION_RECEIPT", vm.toString(receipt));
        console2.log("V3_CONFIGURATION_ROOT", vm.toString(configurationRoot));
        console2.log("V3_VERIFIER_REGISTRY_ROOT", vm.toString(registryRoot));
        console2.log("V3_ADMITTED_RESERVE_STATE_COMMITMENT", vm.toString(publicValues.reserveStateCommitment));
    }
}
