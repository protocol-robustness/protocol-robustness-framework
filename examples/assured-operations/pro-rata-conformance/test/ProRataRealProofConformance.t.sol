// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

import {OperationCodec as O} from "ao-prototype/src/OperationCodec.sol";
import {AssuredExecutor} from "ao-prototype/src/AssuredExecutor.sol";
import {ProRataDispatchTarget, ProRataAssurancePolicy} from "./ProRataConformance.t.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {IPrfStatementAdmission} from "prf-evm/src/interfaces/IPrfStatementAdmission.sol";
import {IShortfallAllocatorV2} from "prf-evm/src/interfaces/IShortfallAllocatorV2.sol";
import {OnchainShortfallReserveV2} from "prf-evm/src/applications/shortfall/OnchainShortfallReserveV2.sol";
import {SourceBoundProRataControllerV2} from "prf-evm/src/applications/shortfall/SourceBoundProRataControllerV2.sol";
import {SourceBoundProRataAssuranceV2} from "prf-evm/src/applications/shortfall/SourceBoundProRataAssuranceV2.sol";
import {
    SourceBoundProRataRatificationControllerV2
} from "prf-evm/src/applications/shortfall/SourceBoundProRataRatificationControllerV2.sol";
import {ProductionProRataAllocatorV2} from "prf-evm/src/applications/pro-rata/ProductionProRataAllocatorV2.sol";
import {
    PrfSourceBoundStatementVerifierAdapterV2
} from "prf-evm/src/verification/adapters/PrfSourceBoundStatementVerifierAdapterV2.sol";
import {PrfStatementAdmission} from "prf-evm/src/core/PrfStatementAdmission.sol";
import {ShortfallV2Commitments} from "prf-evm/src/verification/ShortfallV2Commitments.sol";

interface VmRealProof {
    function envString(string calldata name) external returns (string memory);
    function readFile(string calldata path) external view returns (string memory);
    function parseJson(string calldata json, string calldata key) external returns (bytes memory);
    function prank(address caller) external;
    function writeJson(string calldata json, string calldata path) external;
    function toString(address value) external returns (string memory);
    function toString(bytes calldata value) external returns (string memory);
    function toString(bytes32 value) external returns (string memory);
    function toString(uint256 value) external returns (string memory);
}

/// @notice Runs the exact Gate E V2 deployment and proof through the frozen AO
/// dispatcher. The Gate E direct-ratification test is not this conformance test.
contract ProRataRealProofConformanceTest {
    VmRealProof private constant vm = VmRealProof(address(uint160(uint256(keccak256("hevm cheat code")))));
    uint256 private constant AO_NONCE = 0xA011;
    bytes32 private constant WRONG_OPERATION_ID = keccak256("different-prepared-operation");

    AssuredExecutor private gate;
    ProRataDispatchTarget private target;
    ProRataAssurancePolicy private policy;
    IERC20 private token;
    OnchainShortfallReserveV2 private reserve;
    ProductionProRataAllocatorV2 private allocator;
    SourceBoundProRataControllerV2 private preparedController;
    SourceBoundProRataAssuranceV2 private assurance;
    SourceBoundProRataRatificationControllerV2 private ratification;
    PrfSourceBoundStatementVerifierAdapterV2 private sourceAdapter;
    PrfStatementAdmission private admission;

    bytes32 private operationId;
    bytes32 private preparedRoot;
    bytes32 private reserveCommitment;
    bytes32 private allocationBindingRoot;
    bytes32 private realizedStatementRoot;
    bytes32 private configurationRoot;
    bytes32 private sourceVerifierId;
    uint64 private configurationEpoch;
    address private residualRecipient;
    address private initiator;
    bytes private proof;
    bytes private publicValues;
    bytes32 private receiptId;
    uint256 private reserveBalanceBefore;
    uint256 private allocatorBalanceBefore;

    event RealV2AOContext(
        bytes32 indexed chainInstanceGenesisRoot,
        bytes32 indexed preparedOperationRoot,
        bytes32 indexed admissionReceiptId,
        bytes32 allocationBindingRoot,
        bytes32 realizedStatementRoot,
        bytes32 configurationRoot,
        uint64 configurationEpoch,
        bytes32 programVKey,
        bytes32 programElfSha256,
        bytes32 proofDigest,
        bytes32 publicValuesDigest,
        address assuredExecutor,
        address dispatchTarget
    );

    function setUp() public {
        string memory manifestPath = vm.envString("GATE_E_MANIFEST");
        string memory fixturePath = vm.envString("GATE_E_FIXTURE");
        string memory manifest = vm.readFile(manifestPath);
        string memory fixture = vm.readFile(fixturePath);

        uint256 manifestChainId = abi.decode(vm.parseJson(manifest, ".chainId"), (uint256));
        require(manifestChainId == block.chainid, "manifest chain mismatch");

        token = IERC20(_address(manifest, ".contracts.token"));
        reserve = OnchainShortfallReserveV2(_address(manifest, ".contracts.reserve"));
        allocator = ProductionProRataAllocatorV2(_address(manifest, ".contracts.allocator"));
        preparedController = SourceBoundProRataControllerV2(_address(manifest, ".contracts.preparedController"));
        assurance = SourceBoundProRataAssuranceV2(_address(manifest, ".contracts.assurance"));
        ratification =
            SourceBoundProRataRatificationControllerV2(_address(manifest, ".contracts.ratificationController"));
        admission = PrfStatementAdmission(_address(manifest, ".contracts.admission"));
        sourceAdapter = PrfSourceBoundStatementVerifierAdapterV2(_address(manifest, ".contracts.sourceAdapter"));

        operationId = _bytes32(manifest, ".operationId");
        preparedRoot = _bytes32(manifest, ".preparedOperationRoot");
        reserveCommitment = _bytes32(manifest, ".reserveStateCommitment");
        allocationBindingRoot = _bytes32(manifest, ".allocationBindingRoot");
        realizedStatementRoot = _bytes32(manifest, ".realizedStatementRoot");
        configurationRoot = _bytes32(manifest, ".configurationRoot");
        configurationEpoch = uint64(abi.decode(vm.parseJson(manifest, ".configurationEpoch"), (uint256)));
        sourceVerifierId = _bytes32(manifest, ".localOnly.sourceVerifierId");
        residualRecipient = _address(manifest, ".localOnly.residualRecipient");
        initiator = _address(manifest, ".deployer");

        require(preparedController.preparedOperationRoot(operationId) == preparedRoot, "prepared root mismatch");
        require(admission.expectedConfigurationRoot() == configurationRoot, "configuration root mismatch");
        require(admission.expectedConfigurationEpoch() == configurationEpoch, "configuration epoch mismatch");
        require(!ratification.executionCallerBound(), "deployment route already bound");

        proof = abi.decode(vm.parseJson(fixture, ".proof"), (bytes));
        publicValues = abi.decode(vm.parseJson(fixture, ".public_values"), (bytes));
        require(_stringEq(fixture, ".proof_system", "groth16"), "proof system mismatch");
        require(_stringEq(fixture, ".program_id", "source-bound-v2-sp1-program.v2"), "program mismatch");
        require(_bytes32(fixture, ".vkey") == sourceAdapter.programVKey(), "VKey mismatch");
        require(_bytes32(fixture, ".elf_sha256") == sourceAdapter.programElfSha256(), "ELF mismatch");
        require(
            _stringEq(fixture, ".public_values_schema", "prf/onchain-shortfall-allocation-binding-public-values.v2"),
            "public-values schema mismatch"
        );
        bytes memory expectedPublicValues = abi.encode(
            sourceAdapter.PUBLIC_VALUES_DOMAIN(),
            preparedRoot,
            reserveCommitment,
            allocationBindingRoot,
            realizedStatementRoot,
            operationId
        );
        require(keccak256(publicValues) == keccak256(expectedPublicValues), "fixture does not bind manifest operation");
        require(admission.verifierRegistry().isSealed(admission.configurationExecutor().currentVerifierRegistryRoot()));

        reserveBalanceBefore = token.balanceOf(address(reserve));
        allocatorBalanceBefore = token.balanceOf(address(allocator));
        require(
            reserve.economicState(preparedRoot).commitment == reserveCommitment, "live reserve differs from proof input"
        );

        gate = new AssuredExecutor(admission.chainInstanceGenesisRoot(), address(this), 0);
        target = new ProRataDispatchTarget(
            gate, reserve, allocator, preparedController, ratification, assurance, operationId
        );
        vm.prank(preparedController.governance());
        ratification.bindExecutionCaller(address(target));
        policy = new ProRataAssurancePolicy(gate, target, assurance, preparedController, reserve, operationId);
        gate.sealTarget(
            address(target),
            target.ratifyAndFund.selector,
            keccak256("gate-e-deployment-candidate"),
            keccak256("gate-e-source-bound-v2-release"),
            keccak256("gate-e-pro-rata-deployment-scope"),
            policy
        );
        _assertDeploymentRouteBinding();
    }

    function testRealProofAndExactOperationExecuteThroughFrozenAOPath() public {
        _admitAndPrepare();
        ratification.registerPrepared(operationId);

        (bool directOk, bytes memory directError) = address(ratification)
            .call(
                abi.encodeCall(
                    SourceBoundProRataRatificationControllerV2.ratifyAndFund,
                    (operationId, reserveCommitment, configurationRoot, configurationEpoch, residualRecipient)
                )
            );
        require(!directOk, "direct ratifyAndFund bypassed bound AO route");
        require(
            bytes4(directError) == SourceBoundProRataRatificationControllerV2.OnlyExecutionCaller.selector,
            "direct route rejected for wrong reason"
        );
        require(!assurance.consumedAssurance(receiptId), "direct route consumed V2 assurance");
        require(!gate.consumed(AO_NONCE), "direct route consumed AO nonce");
        _assertNoCapitalTransition();
        require(
            ratification.state(operationId) == SourceBoundProRataRatificationControllerV2.State.Prepared,
            "direct route changed ratification state"
        );

        (O.Envelope memory envelope, bytes memory data, AssuredExecutor.Admission memory witness) = _dispatch();
        vm.prank(initiator);
        gate.execute(envelope, witness, data, abi.encode(receiptId));

        IShortfallAllocatorV2.Operation memory funded = allocator.operation(operationId);
        require(funded.state == IShortfallAllocatorV2.State.Funded, "allocator not funded");
        require(funded.amount == reserveBalanceBefore - reserve.protectedCapital(), "funded amount mismatch");
        require(address(funded.token) == address(token), "funded token mismatch");
        require(funded.allocationBindingRoot == allocationBindingRoot, "allocation root mismatch");
        require(funded.realizedStatementRoot == realizedStatementRoot, "realized root mismatch");
        require(
            token.balanceOf(address(allocator)) - allocatorBalanceBefore == funded.amount, "allocator delta mismatch"
        );
        require(token.balanceOf(address(reserve)) == reserve.protectedCapital(), "protected reserve mismatch");
        require(assurance.consumedAssurance(receiptId), "V2 assurance not consumed");
        require(gate.consumed(AO_NONCE), "AO nonce not consumed");
        (bytes32 observationRoot,,,) = ratification.result(operationId);
        require(observationRoot != bytes32(0), "V2 reserve observation missing");
        _writeAoRouteManifest(observationRoot, envelope, data, witness);
    }

    function testWrongProofRejectsWithoutNonceOrCapitalTransition() public {
        bytes memory wrongProof = proof;
        wrongProof[0] = bytes1(uint8(wrongProof[0]) ^ 1);
        (bool ok,) = address(admission)
            .call(
                abi.encodeCall(
                    IPrfStatementAdmission.admitStatement,
                    (sourceVerifierId, allocationBindingRoot, wrongProof, publicValues)
                )
            );
        require(!ok, "tampered SP1 proof admitted");
        require(!gate.consumed(AO_NONCE), "failed proof consumed AO nonce");
        require(assurance.preparedAssurance(operationId).state == SourceBoundProRataAssuranceV2.AssuranceState.None);
        _assertNoCapitalTransition();
    }

    function testProofCannotBeReboundToAnotherPreparedOperation() public {
        bytes memory wrongOperationValues = abi.encode(
            sourceAdapter.PUBLIC_VALUES_DOMAIN(),
            preparedRoot,
            reserveCommitment,
            allocationBindingRoot,
            realizedStatementRoot,
            WRONG_OPERATION_ID
        );
        (bool admitted,) = address(admission)
            .call(
                abi.encodeCall(
                    IPrfStatementAdmission.admitStatement,
                    (sourceVerifierId, allocationBindingRoot, proof, wrongOperationValues)
                )
            );
        require(!admitted, "proof admitted with a different prepared operation id");

        ShortfallV2Commitments.PreparedOperation memory otherOperation = _operation(WRONG_OPERATION_ID);
        vm.prank(initiator);
        preparedController.prepareOperation(otherOperation);

        receiptId = admission.admitStatement(sourceVerifierId, allocationBindingRoot, proof, publicValues);
        (bool rebound,) = address(assurance)
            .call(
                abi.encodeCall(
                    SourceBoundProRataAssuranceV2.prepareSourceAssurance, (WRONG_OPERATION_ID, receiptId, publicValues)
                )
            );
        require(!rebound, "V2 proof rebound to a different prepared operation");
        require(!gate.consumed(AO_NONCE), "wrong operation consumed AO nonce");
        _assertNoCapitalTransition();
    }

    function testProofCannotBeSubstitutedForAnotherPreparedDeploymentRoot() public {
        bytes memory wrongDeploymentValues = abi.encode(
            sourceAdapter.PUBLIC_VALUES_DOMAIN(),
            keccak256("different-deployed-prepared-operation"),
            reserveCommitment,
            allocationBindingRoot,
            realizedStatementRoot,
            operationId
        );
        (bool admitted,) = address(admission)
            .call(
                abi.encodeCall(
                    IPrfStatementAdmission.admitStatement,
                    (sourceVerifierId, allocationBindingRoot, proof, wrongDeploymentValues)
                )
            );
        require(!admitted, "proof admitted for another prepared deployment root");
        require(!gate.consumed(AO_NONCE), "deployment substitution consumed AO nonce");
        _assertNoCapitalTransition();
    }

    function testConfigurationMismatchRejectsAndConsumesNothing() public {
        PrfStatementAdmission deployedAdmission = admission;
        PrfStatementAdmission wrongConfigurationAdmission = new PrfStatementAdmission(
            deployedAdmission.genesis(),
            deployedAdmission.configurationExecutor(),
            deployedAdmission.verifierRegistry(),
            keccak256("wrong-configuration"),
            configurationEpoch
        );
        (bool ok,) = address(wrongConfigurationAdmission)
            .call(
                abi.encodeCall(
                    IPrfStatementAdmission.admitStatement,
                    (sourceVerifierId, allocationBindingRoot, proof, publicValues)
                )
            );
        require(!ok, "proof admitted against wrong configuration");
        require(!gate.consumed(AO_NONCE), "configuration mismatch consumed AO nonce");
        _assertNoCapitalTransition();
    }

    function testStaleProjectionRejectsWithoutV2OrAOConsumption() public {
        _admitAndPrepare();
        ratification.registerPrepared(operationId);
        (O.Envelope memory envelope, bytes memory data, AssuredExecutor.Admission memory staleWitness) = _dispatch();

        vm.prank(reserve.applicationAuthority());
        reserve.reserveForOtherObligation(keccak256("projection-change"), 1);

        (bool ok,) = address(gate)
            .call(abi.encodeCall(AssuredExecutor.execute, (envelope, staleWitness, data, abi.encode(receiptId))));
        require(!ok, "stale projection admitted");
        require(!gate.consumed(AO_NONCE), "stale projection consumed AO nonce");
        require(!assurance.consumedAssurance(receiptId), "stale projection consumed V2 assurance");
        require(
            ratification.state(operationId) == SourceBoundProRataRatificationControllerV2.State.Prepared,
            "stale projection changed ratification state"
        );
        _assertNoCapitalTransition();
    }

    function _admitAndPrepare() private {
        receiptId = admission.admitStatement(sourceVerifierId, allocationBindingRoot, proof, publicValues);
        IPrfStatementAdmission.AdmissionReceipt memory receipt = admission.admissionReceipt(receiptId);
        require(receipt.exists, "V2 receipt missing");
        require(receipt.chainInstanceGenesisRoot == admission.chainInstanceGenesisRoot(), "chain instance mismatch");
        require(receipt.configurationRoot == configurationRoot, "receipt configuration mismatch");
        require(receipt.configurationEpoch == configurationEpoch, "receipt epoch mismatch");
        require(receipt.verifierId == sourceVerifierId, "receipt verifier mismatch");
        require(receipt.programVKey == sourceAdapter.programVKey(), "receipt VKey mismatch");
        require(receipt.programElfSha256 == sourceAdapter.programElfSha256(), "receipt ELF mismatch");
        require(receipt.publicValuesDigest == sha256(publicValues), "receipt public-values mismatch");
        assurance.prepareSourceAssurance(operationId, receiptId, publicValues);
        emit RealV2AOContext(
            admission.chainInstanceGenesisRoot(),
            preparedRoot,
            receiptId,
            allocationBindingRoot,
            realizedStatementRoot,
            configurationRoot,
            configurationEpoch,
            sourceAdapter.programVKey(),
            sourceAdapter.programElfSha256(),
            sha256(proof),
            sha256(publicValues),
            address(gate),
            address(target)
        );
    }

    function _dispatch()
        private
        view
        returns (O.Envelope memory envelope, bytes memory data, AssuredExecutor.Admission memory witness)
    {
        envelope = gate.template();
        data = abi.encodeCall(
            ProRataDispatchTarget.ratifyAndFund,
            (operationId, reserveCommitment, configurationRoot, configurationEpoch, residualRecipient)
        );
        envelope.principal = initiator;
        envelope.nonce = AO_NONCE;
        envelope.calldataHash = sha256(data);
        (uint256 revision, bytes32 registrationRoot,,,,) = gate.active();
        witness = AssuredExecutor.Admission(
            registrationRoot, revision, target.projectionDefinitionRoot(), target.currentResultRoot()
        );
    }

    function _operation(bytes32 operation) private returns (ShortfallV2Commitments.PreparedOperation memory) {
        return ShortfallV2Commitments.PreparedOperation({
            operationId: operation,
            initiator: bytes32(uint256(uint160(initiator))),
            chainId: uint64(block.chainid),
            reserve: bytes32(uint256(uint160(address(reserve)))),
            token: bytes32(uint256(uint160(address(token)))),
            allocator: bytes32(uint256(uint160(address(allocator)))),
            configurationRoot: configurationRoot,
            configurationEpoch: configurationEpoch,
            sourceVerifierId: sourceVerifierId,
            executionVerifierId: _bytes32FromManifestLocal(".executionVerifierId"),
            sourceProgramVKey: sourceAdapter.programVKey(),
            sourceProgramElfSha256: sourceAdapter.programElfSha256(),
            sourceStatementSchema: sourceAdapter.statementSchemaVersion(),
            sourcePublicValuesSchema: sourceAdapter.publicValuesSchema(),
            executionStatementSchema: _bytes32FromManifestLocal(".executionStatementSchema"),
            executionPublicValuesSchema: _bytes32FromManifestLocal(".executionPublicValuesSchema"),
            policyRoot: _bytes32FromManifest(".policyRoot"),
            residualRecipient: bytes32(uint256(uint160(residualRecipient)))
        });
    }

    function _assertDeploymentRouteBinding() private view {
        require(ratification.executionCallerBound(), "ratification route not bound");
        require(ratification.executionCaller() == address(target), "wrong ratification adapter");
        require(address(target).code.length != 0, "adapter has no runtime code");
        require(address(target.executor()) == address(gate), "adapter reports wrong executor");
    }

    function _writeAoRouteManifest(
        bytes32 observationRoot,
        O.Envelope memory envelope,
        bytes memory data,
        AssuredExecutor.Admission memory witness
    ) private {
        string memory manifest = "{";
        manifest = _addString(manifest, "schema", "ao-pro-rata-real-v2-route-manifest.v1");
        manifest = _addString(
            manifest, "gateEManifestDigest", _string(vm.readFile(vm.envString("GATE_E_MANIFEST")), ".manifestDigest")
        );
        manifest = _addNumber(manifest, "chainId", vm.toString(block.chainid));
        manifest = _addNumber(manifest, "forkBlock", vm.toString(block.number));
        manifest = _addString(manifest, "chainInstanceGenesisRoot", vm.toString(admission.chainInstanceGenesisRoot()));
        manifest = _addString(manifest, "operationId", vm.toString(operationId));
        manifest = _addString(manifest, "preparedOperationRoot", vm.toString(preparedRoot));
        manifest = _addString(manifest, "reserveStateCommitment", vm.toString(reserveCommitment));
        manifest = _addString(manifest, "allocationBindingRoot", vm.toString(allocationBindingRoot));
        manifest = _addString(manifest, "realizedStatementRoot", vm.toString(realizedStatementRoot));
        manifest = _addString(manifest, "configurationRoot", vm.toString(configurationRoot));
        manifest = _addNumber(manifest, "configurationEpoch", vm.toString(configurationEpoch));
        manifest = _addString(manifest, "sourceVerifierId", vm.toString(sourceVerifierId));
        manifest = _addString(manifest, "programVKey", vm.toString(sourceAdapter.programVKey()));
        manifest = _addString(manifest, "programElfSha256", vm.toString(sourceAdapter.programElfSha256()));
        manifest = _addString(manifest, "proofSha256", vm.toString(sha256(proof)));
        manifest = _addString(manifest, "publicValuesSha256", vm.toString(sha256(publicValues)));
        manifest = _addString(manifest, "admissionReceiptId", vm.toString(receiptId));
        manifest = _addString(manifest, "assurancePolicyRoot", vm.toString(policy.policyRoot()));
        manifest = _addString(manifest, "operationEnvelopeRoot", vm.toString(O.envelopeRoot(envelope)));
        manifest = _addString(manifest, "operationTarget", vm.toString(envelope.target));
        manifest = _addString(manifest, "operationSelector", vm.toString(abi.encodePacked(envelope.selector)));
        manifest = _addString(manifest, "operationCalldata", vm.toString(data));
        manifest = _addString(manifest, "operationCalldataHash", vm.toString(sha256(data)));
        manifest = _addString(manifest, "operationPrincipal", vm.toString(envelope.principal));
        manifest = _addNumber(manifest, "operationNonce", vm.toString(envelope.nonce));
        manifest = _addNumber(manifest, "operationNativeValue", vm.toString(envelope.value));
        manifest = _addString(manifest, "consumedRegistrationRoot", vm.toString(witness.expectedRegistrationRoot));
        manifest =
            _addNumber(manifest, "consumedRegistrationRevision", vm.toString(witness.expectedRegistrationRevision));
        manifest =
            _addString(manifest, "consumedProjectionDefinitionRoot", vm.toString(witness.projectionDefinitionRoot));
        manifest = _addString(manifest, "consumedProjectionResultRoot", vm.toString(witness.consumedResultRoot));
        manifest = _addString(manifest, "executor", vm.toString(address(gate)));
        manifest = _addString(manifest, "executorCodeHash", vm.toString(address(gate).codehash));
        manifest = _addString(manifest, "dispatchTarget", vm.toString(address(target)));
        manifest = _addString(manifest, "dispatchTargetCodeHash", vm.toString(address(target).codehash));
        manifest = _addString(manifest, "ratificationController", vm.toString(address(ratification)));
        manifest = _addString(manifest, "permanentlyBoundExecutionCaller", vm.toString(ratification.executionCaller()));
        manifest = _addString(manifest, "registrationRoot", vm.toString(witness.expectedRegistrationRoot));
        manifest = _addNumber(manifest, "registrationRevision", vm.toString(witness.expectedRegistrationRevision));
        manifest = _addString(manifest, "reserveObservationRoot", vm.toString(observationRoot));
        manifest = _addNumber(manifest, "allocatorTokenBalance", vm.toString(token.balanceOf(address(allocator))));
        manifest = _addNumber(manifest, "reserveTokenBalance", vm.toString(token.balanceOf(address(reserve))));
        vm.writeJson(string.concat(manifest, "}"), vm.envString("GATE_E_AO_ROUTE_MANIFEST"));
    }

    function _addString(string memory json, string memory key, string memory value)
        private
        pure
        returns (string memory)
    {
        string memory field = string.concat('"', key, '":"', value, '"');
        return bytes(json).length == 1 ? string.concat(json, field) : string.concat(json, ",", field);
    }

    function _addNumber(string memory json, string memory key, string memory value)
        private
        pure
        returns (string memory)
    {
        string memory field = string.concat('"', key, '":', value);
        return bytes(json).length == 1 ? string.concat(json, field) : string.concat(json, ",", field);
    }

    function _assertNoCapitalTransition() private view {
        require(token.balanceOf(address(reserve)) == reserveBalanceBefore, "reserve capital changed");
        require(token.balanceOf(address(allocator)) == allocatorBalanceBefore, "allocator capital changed");
    }

    function _address(string memory json, string memory key) private returns (address) {
        return abi.decode(vm.parseJson(json, key), (address));
    }

    function _bytes32(string memory json, string memory key) private returns (bytes32) {
        return abi.decode(vm.parseJson(json, key), (bytes32));
    }

    function _bytes32FromManifest(string memory key) private returns (bytes32) {
        return _bytes32(vm.readFile(vm.envString("GATE_E_MANIFEST")), key);
    }

    function _bytes32FromManifestLocal(string memory key) private returns (bytes32) {
        return _bytes32(vm.readFile(vm.envString("GATE_E_MANIFEST")), string.concat(".localOnly", key));
    }

    function _string(string memory json, string memory key) private returns (string memory) {
        return abi.decode(vm.parseJson(json, key), (string));
    }

    function _stringEq(string memory json, string memory key, string memory expected) private returns (bool) {
        return keccak256(bytes(abi.decode(vm.parseJson(json, key), (string)))) == keccak256(bytes(expected));
    }
}
