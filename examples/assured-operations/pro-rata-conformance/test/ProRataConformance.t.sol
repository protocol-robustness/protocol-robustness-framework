// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

import {Canonical as C} from "ao-prototype/src/Canonical.sol";
import {OperationCodec as O} from "ao-prototype/src/OperationCodec.sol";
import {AssuredExecutor, IAssurancePolicy} from "ao-prototype/src/AssuredExecutor.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {IPrfStatementAdmission} from "prf-evm/src/interfaces/IPrfStatementAdmission.sol";
import {ISP1Verifier} from "prf-evm/src/verification/interfaces/ISP1Verifier.sol";
import {
    PrfSourceBoundStatementVerifierAdapterV2
} from "prf-evm/src/verification/adapters/PrfSourceBoundStatementVerifierAdapterV2.sol";
import {ShortfallV2Commitments} from "prf-evm/src/verification/ShortfallV2Commitments.sol";
import {IShortfallAllocatorV2} from "prf-evm/src/interfaces/IShortfallAllocatorV2.sol";
import {OnchainShortfallReserveV2} from "prf-evm/src/applications/shortfall/OnchainShortfallReserveV2.sol";
import {SourceBoundProRataControllerV2} from "prf-evm/src/applications/shortfall/SourceBoundProRataControllerV2.sol";
import {SourceBoundProRataAssuranceV2} from "prf-evm/src/applications/shortfall/SourceBoundProRataAssuranceV2.sol";
import {
    SourceBoundProRataRatificationControllerV2
} from "prf-evm/src/applications/shortfall/SourceBoundProRataRatificationControllerV2.sol";
import {ProductionProRataAllocatorV2} from "prf-evm/src/applications/pro-rata/ProductionProRataAllocatorV2.sol";

interface Vm {
    function prank(address caller) external;
}

contract TestToken is IERC20 {
    mapping(address => uint256) public balanceOf;
    mapping(address => mapping(address => uint256)) public allowance;
    uint256 public totalSupply;

    function mint(address to, uint256 amount) external {
        balanceOf[to] += amount;
        totalSupply += amount;
    }

    function approve(address spender, uint256 amount) external returns (bool) {
        allowance[msg.sender][spender] = amount;
        emit Approval(msg.sender, spender, amount);
        return true;
    }

    function transfer(address to, uint256 amount) external returns (bool) {
        _move(msg.sender, to, amount);
        return true;
    }

    function transferFrom(address from, address to, uint256 amount) external returns (bool) {
        uint256 approved = allowance[from][msg.sender];
        require(approved >= amount);
        allowance[from][msg.sender] = approved - amount;
        _move(from, to, amount);
        return true;
    }

    function _move(address from, address to, uint256 amount) private {
        require(balanceOf[from] >= amount);
        balanceOf[from] -= amount;
        balanceOf[to] += amount;
        emit Transfer(from, to, amount);
    }
}

/// @dev Only the verifier call is mocked. The real V2 adapter, admission receipt
/// checks, assurance lifecycle, ratification controller, reserve and allocator
/// are exercised below. This does not claim SP1 proof verification.
contract AcceptingTestVerifier is ISP1Verifier {
    function verifyProof(bytes32, bytes calldata, bytes calldata) external pure {}
}

contract TestStatementAdmission is IPrfStatementAdmission {
    mapping(bytes32 => AdmissionReceipt) private _receipts;
    address public sourceAdapter;
    uint256 public override admittedCount;
    bytes32 public constant CONFIGURATION_ROOT = keccak256("test-configuration");
    bytes32 public constant VERIFIER_ID = keccak256("test-source-verifier");

    function setSourceAdapter(address adapter) external {
        sourceAdapter = adapter;
    }

    function supportsInterface(bytes4 interfaceId) external pure override returns (bool) {
        return interfaceId == type(IPrfStatementAdmission).interfaceId || interfaceId == 0x01ffc9a7;
    }

    function protocolGenesisRoot() external pure override returns (bytes32) {
        return keccak256("test-protocol");
    }

    function chainInstanceGenesisRoot() external pure override returns (bytes32) {
        return keccak256("test-chain-instance");
    }

    function expectedConfigurationRoot() external pure override returns (bytes32) {
        return CONFIGURATION_ROOT;
    }

    function expectedConfigurationEpoch() external pure override returns (uint64) {
        return 1;
    }

    function admissionReceipt(bytes32 receiptId) external view override returns (AdmissionReceipt memory) {
        return _receipts[receiptId];
    }

    function admitStatement(
        bytes32 verifierId,
        bytes32 statementRoot,
        bytes calldata proof,
        bytes calldata publicValues
    ) external override returns (bytes32 receiptId) {
        require(verifierId == VERIFIER_ID);
        PrfSourceBoundStatementVerifierAdapterV2 adapter = PrfSourceBoundStatementVerifierAdapterV2(sourceAdapter);
        require(adapter.verifyStatementProof(statementRoot, proof, publicValues) == statementRoot);
        receiptId = keccak256(abi.encode(verifierId, address(adapter), statementRoot, sha256(publicValues)));
        _receipts[receiptId] = AdmissionReceipt({
            exists: true,
            protocolGenesisRoot: keccak256("test-protocol"),
            chainInstanceGenesisRoot: keccak256("test-chain-instance"),
            configurationRoot: CONFIGURATION_ROOT,
            configurationEpoch: 1,
            verifierRegistryRoot: keccak256("test-verifier-registry"),
            verifierId: verifierId,
            verifierAdapter: address(adapter),
            programVKey: adapter.programVKey(),
            programElfSha256: adapter.programElfSha256(),
            statementSchemaVersionHash: adapter.statementSchemaVersion(),
            publicValuesSchema: adapter.publicValuesSchema(),
            statementRoot: statementRoot,
            publicValuesDigest: sha256(publicValues)
        });
        ++admittedCount;
    }
}

    /// @dev Thin application adapter. It adds no proof or capital semantics to the
    /// generic gate; it exposes the existing ratification call and live projection.
    contract ProRataDispatchTarget {
        AssuredExecutor public immutable executor;
        OnchainShortfallReserveV2 public immutable reserve;
        ProductionProRataAllocatorV2 public immutable allocator;
        SourceBoundProRataControllerV2 public immutable preparedController;
        SourceBoundProRataRatificationControllerV2 public immutable ratification;
        SourceBoundProRataAssuranceV2 public immutable assurance;
        bytes32 public immutable operationId;
        bytes32 public immutable projectionDefinitionRoot;

        constructor(
            AssuredExecutor executor_,
            OnchainShortfallReserveV2 reserve_,
            ProductionProRataAllocatorV2 allocator_,
            SourceBoundProRataControllerV2 preparedController_,
            SourceBoundProRataRatificationControllerV2 ratification_,
            SourceBoundProRataAssuranceV2 assurance_,
            bytes32 operationId_
        ) {
            executor = executor_;
            reserve = reserve_;
            allocator = allocator_;
            preparedController = preparedController_;
            ratification = ratification_;
            assurance = assurance_;
            operationId = operationId_;

            string[] memory keys = new string[](5);
            bytes[] memory values = new bytes[](5);
            keys[0] = "artifact/schema";
            values[0] = C.text("pro-rata-ratification-projection.prototype.v1");
            keys[1] = "target/address";
            values[1] = C.addressValue(address(this));
            keys[2] = "reserve/address";
            values[2] = C.addressValue(address(reserve_));
            keys[3] = "allocator/address";
            values[3] = C.addressValue(address(allocator_));
            keys[4] = "operation/id";
            values[4] = C.hashValue(operationId_, false);
            projectionDefinitionRoot = C.root("ao.prototype.pro-rata-projection-definition.v1", C.map(keys, values));
        }

        function currentResultRoot() public view returns (bytes32) {
            bytes32 preparedRoot = preparedController.preparedOperationRoot(operationId);
            OnchainShortfallReserveV2.EconomicState memory state = reserve.economicState(preparedRoot);
            SourceBoundProRataAssuranceV2.PreparedSourceAssurance memory proofState =
                assurance.preparedAssurance(operationId);
            string[] memory keys = new string[](9);
            bytes[] memory values = new bytes[](9);
            keys[0] = "artifact/schema";
            values[0] = C.text("pro-rata-ratification-projection-result.prototype.v1");
            keys[1] = "operation/id";
            values[1] = C.hashValue(operationId, false);
            keys[2] = "prepared-operation/root";
            values[2] = C.hashValue(preparedRoot, false);
            keys[3] = "reserve-state/commitment";
            values[3] = C.hashValue(state.commitment, true);
            keys[4] = "reserve/token-balance";
            values[4] = C.uintValue(reserve.token().balanceOf(address(reserve)));
            keys[5] = "allocator/token-balance";
            values[5] = C.uintValue(reserve.token().balanceOf(address(allocator)));
            keys[6] = "availability/revision";
            values[6] = C.uintValue(state.revision);
            keys[7] = "assurance/state";
            values[7] = C.keyword(_assuranceState(proofState.state));
            keys[8] = "ratification/state";
            values[8] = C.keyword(_ratificationState(ratification.state(operationId)));
            return C.root("ao.prototype.pro-rata-projection-result.v1", C.map(keys, values));
        }

        function ratifyAndFund(
            bytes32 opId,
            bytes32 expectedReserveStateCommitment,
            bytes32 configurationRoot,
            uint64 configurationEpoch,
            address residualRecipient
        ) external {
            require(msg.sender == address(executor), "dispatcher only");
            ratification.ratifyAndFund(
                opId, expectedReserveStateCommitment, configurationRoot, configurationEpoch, residualRecipient
            );
        }

        function _assuranceState(SourceBoundProRataAssuranceV2.AssuranceState state)
            private
            pure
            returns (string memory)
        {
            if (state == SourceBoundProRataAssuranceV2.AssuranceState.None) return "none";
            if (state == SourceBoundProRataAssuranceV2.AssuranceState.Prepared) return "prepared";
            return "consumed";
        }

        function _ratificationState(SourceBoundProRataRatificationControllerV2.State state)
            private
            pure
            returns (string memory)
        {
            if (state == SourceBoundProRataRatificationControllerV2.State.None) return "none";
            if (state == SourceBoundProRataRatificationControllerV2.State.Prepared) return "prepared";
            if (state == SourceBoundProRataRatificationControllerV2.State.Ratifying) return "ratifying";
            return "funded";
        }
    }

    /// @dev Application-owned assurance admission. It binds the source assurance
    /// to the exact prepared operation, live reserve state and prepared initiator.
    contract ProRataAssurancePolicy is IAssurancePolicy {
        AssuredExecutor public immutable executor;
        ProRataDispatchTarget public immutable target;
        SourceBoundProRataAssuranceV2 public immutable assurance;
        SourceBoundProRataControllerV2 public immutable preparedController;
        OnchainShortfallReserveV2 public immutable reserve;
        bytes32 public immutable operationId;
        bytes32 public immutable root;

        constructor(
            AssuredExecutor executor_,
            ProRataDispatchTarget target_,
            SourceBoundProRataAssuranceV2 assurance_,
            SourceBoundProRataControllerV2 preparedController_,
            OnchainShortfallReserveV2 reserve_,
            bytes32 operationId_
        ) {
            executor = executor_;
            target = target_;
            assurance = assurance_;
            preparedController = preparedController_;
            reserve = reserve_;
            operationId = operationId_;
            string[] memory keys = new string[](4);
            bytes[] memory values = new bytes[](4);
            keys[0] = "artifact/schema";
            values[0] = C.text("pro-rata-source-assurance-policy.prototype.v1");
            keys[1] = "assurance-contract";
            values[1] = C.addressValue(address(assurance_));
            keys[2] = "operation/id";
            values[2] = C.hashValue(operationId_, false);
            keys[3] = "source-statement/schema";
            values[3] = C.hashValue(assurance_.sourceAdapter().statementSchemaVersion(), false);
            root = C.root("ao.prototype.pro-rata-source-assurance-policy.v1", C.map(keys, values));
        }

        function artifactType() external pure returns (string memory) {
            return "pro-rata-source-assurance-policy.prototype.v1";
        }

        function policyRoot() external view returns (bytes32) {
            return root;
        }

        function admit(
            O.Envelope calldata envelope,
            bytes calldata data,
            bytes32 projection,
            bytes32 observed,
            bytes calldata evidence
        ) external view returns (bytes32) {
            require(msg.sender == address(executor), "gate only");
            require(envelope.target == address(target) && envelope.value == 0, "wrong target/value");
            require(envelope.principal == preparedController.preparedInitiator(operationId), "wrong initiator");
            require(data.length == 164 && evidence.length == 32, "call/evidence shape");
            (
                bytes32 suppliedOperationId,
                bytes32 reserveCommitment,
                bytes32 configurationRoot,
                uint64 configurationEpoch,
                address residualRecipient
            ) = abi.decode(data[4:], (bytes32, bytes32, bytes32, uint64, address));
            require(suppliedOperationId == operationId, "wrong operation");
            SourceBoundProRataAssuranceV2.PreparedSourceAssurance memory prepared =
                assurance.preparedAssurance(operationId);
            require(prepared.state == SourceBoundProRataAssuranceV2.AssuranceState.Prepared, "assurance not prepared");
            require(abi.decode(evidence, (bytes32)) == prepared.receiptId, "wrong admission receipt");
            require(
                prepared.preparedOperationRoot == preparedController.preparedOperationRoot(operationId), "wrong source"
            );
            require(prepared.reserveStateCommitment == reserveCommitment, "assurance state mismatch");
            require(
                configurationRoot == preparedController.preparedConfigurationRoot(operationId), "configuration mismatch"
            );
            require(configurationEpoch == preparedController.preparedConfigurationEpoch(operationId), "epoch mismatch");
            require(
                bytes32(uint256(uint160(residualRecipient)))
                    == preparedController.preparedResidualRecipient(operationId),
                "recipient mismatch"
            );
            OnchainShortfallReserveV2.EconomicState memory live = reserve.economicState(prepared.preparedOperationRoot);
            require(live.commitment == reserveCommitment, "stale reserve state");
            require(
                target.projectionDefinitionRoot() == projection && target.currentResultRoot() == observed,
                "stale projection"
            );
            return prepared.receiptId;
        }
    }

    contract ProRataConformanceTest {
        Vm private constant vm = Vm(address(uint160(uint256(keccak256("hevm cheat code")))));
        bytes32 private constant OPERATION_ID = keccak256("pro-rata-operation");
        bytes32 private constant CONFIGURATION_ROOT = keccak256("application-configuration");
        bytes32 private constant ALLOCATION_ROOT = keccak256("allocation-binding");
        bytes32 private constant REALIZED_ROOT = keccak256("realized-statement");
        bytes32 private constant VKEY = keccak256("test-vkey");
        bytes32 private constant ELF = keccak256("test-elf");
        address private constant RESIDUAL_RECIPIENT = address(0xBEEF);

        AssuredExecutor private gate;
        TestToken private token;
        ProductionProRataAllocatorV2 private allocator;
        OnchainShortfallReserveV2 private reserve;
        SourceBoundProRataControllerV2 private preparedController;
        SourceBoundProRataAssuranceV2 private assurance;
        SourceBoundProRataRatificationControllerV2 private ratification;
        ProRataDispatchTarget private target;
        ProRataAssurancePolicy private policy;
        TestStatementAdmission private admission;
        PrfSourceBoundStatementVerifierAdapterV2 private sourceAdapter;
        bytes32 private preparedRoot;
        bytes32 private reserveCommitment;
        bytes32 private receiptId;
        bytes private publicValues;

        function setUp() public {
            gate = new AssuredExecutor(keccak256("chain-instance-genesis"), address(this), 0);
            token = new TestToken();
            allocator = new ProductionProRataAllocatorV2(address(this));
            reserve = new OnchainShortfallReserveV2(IERC20(address(token)), address(this), 10, allocator);
            preparedController = new SourceBoundProRataControllerV2();
            AcceptingTestVerifier testVerifier = new AcceptingTestVerifier();
            sourceAdapter = new PrfSourceBoundStatementVerifierAdapterV2(testVerifier, VKEY, ELF);
            admission = new TestStatementAdmission();
            admission.setSourceAdapter(address(sourceAdapter));
            assurance = new SourceBoundProRataAssuranceV2(admission, sourceAdapter, preparedController, address(this));
            ratification = new SourceBoundProRataRatificationControllerV2(preparedController, assurance, reserve);

            preparedController.bindRatificationController(address(ratification));
            assurance.bindRatificationController(address(ratification));
            reserve.bindRatificationAuthority(address(ratification));
            allocator.bindFundingAuthority(address(reserve));

            token.mint(address(this), 100);
            token.approve(address(reserve), 100);
            reserve.deposit(100);

            ShortfallV2Commitments.PreparedOperation memory operation = _operation();
            preparedRoot = preparedController.prepareOperation(operation);
            reserveCommitment = reserve.economicState(preparedRoot).commitment;
            publicValues = abi.encode(
                sourceAdapter.PUBLIC_VALUES_DOMAIN(),
                preparedRoot,
                reserveCommitment,
                ALLOCATION_ROOT,
                REALIZED_ROOT,
                OPERATION_ID
            );
            bytes32 receipt = admission.admitStatement(admission.VERIFIER_ID(), ALLOCATION_ROOT, hex"01", publicValues);
            // Admission is permissionless in this fixture; source preparation joins
            // the retained admission receipt to the existing V2 operation.
            receiptId = receipt;
            assurance.prepareSourceAssurance(OPERATION_ID, receiptId, publicValues);
            ratification.registerPrepared(OPERATION_ID);

            target = new ProRataDispatchTarget(
                gate, reserve, allocator, preparedController, ratification, assurance, OPERATION_ID
            );
            ratification.bindExecutionCaller(address(target));
            _assertDeploymentRouteBinding();
            policy = new ProRataAssurancePolicy(gate, target, assurance, preparedController, reserve, OPERATION_ID);
            gate.sealTarget(
                address(target),
                target.ratifyAndFund.selector,
                keccak256("deployment-candidate"),
                keccak256("release"),
                keccak256("deployment-scope"),
                policy
            );
        }

        function testExistingV2RatificationThroughUnchangedAssuredExecutor() public {
            (O.Envelope memory envelope, bytes memory data, AssuredExecutor.Admission memory admissionWitness) =
                _dispatch(41);
            gate.execute(envelope, admissionWitness, data, abi.encode(receiptId));

            IShortfallAllocatorV2.Operation memory funded = allocator.operation(OPERATION_ID);
            require(uint8(funded.state) == uint8(IShortfallAllocatorV2.State.Funded), "allocator not funded");
            require(funded.allocationBindingRoot == ALLOCATION_ROOT, "allocation root lost");
            require(funded.realizedStatementRoot == REALIZED_ROOT, "realized root lost");
            require(funded.amount == 90 && address(funded.token) == address(token), "funding mismatch");
            require(token.balanceOf(address(reserve)) == 10, "protected reserve mismatch");
            require(token.balanceOf(address(allocator)) == 90, "allocator delta mismatch");
            require(assurance.consumedAssurance(receiptId), "V2 assurance not consumed");
            require(gate.consumed(41), "generic nonce not consumed");
            (bytes32 observationRoot,,,) = ratification.result(OPERATION_ID);
            require(observationRoot != bytes32(0), "reserve observation missing");
        }

        function testDeploymentRouteBindingIsOperationalInvariant() public view {
            _assertDeploymentRouteBinding();
        }

        function testPreparedInitiatorIsRequiredByApplicationPolicy() public {
            (O.Envelope memory envelope, bytes memory data, AssuredExecutor.Admission memory admissionWitness) =
                _dispatch(42);
            envelope.principal = address(0xBAD);
            vm.prank(address(0xBAD));
            (bool ok,) = address(gate)
                .call(
                    abi.encodeCall(AssuredExecutor.execute, (envelope, admissionWitness, data, abi.encode(receiptId)))
                );
            require(!ok && !gate.consumed(42), "unprepared caller admitted");
        }

        function testDirectRatificationRejectsThenDispatcherPathSucceeds() public {
            (bool directOk, bytes memory directError) = address(ratification)
                .call(
                    abi.encodeCall(
                        SourceBoundProRataRatificationControllerV2.ratifyAndFund,
                        (OPERATION_ID, reserveCommitment, CONFIGURATION_ROOT, uint64(7), RESIDUAL_RECIPIENT)
                    )
                );
            require(!directOk, "direct ratification bypassed Assured Operations");
            require(
                bytes4(directError) == SourceBoundProRataRatificationControllerV2.OnlyExecutionCaller.selector,
                "wrong direct-call rejection"
            );
            require(!assurance.consumedAssurance(receiptId), "direct rejection consumed V2 assurance");
            require(
                uint8(ratification.state(OPERATION_ID))
                    == uint8(SourceBoundProRataRatificationControllerV2.State.Prepared),
                "direct rejection changed V2 state"
            );
            require(
                token.balanceOf(address(reserve)) == 100 && token.balanceOf(address(allocator)) == 0,
                "direct rejection moved assets"
            );

            (O.Envelope memory envelope, bytes memory data, AssuredExecutor.Admission memory admissionWitness) =
                _dispatch(43);
            gate.execute(envelope, admissionWitness, data, abi.encode(receiptId));
            require(assurance.consumedAssurance(receiptId) && gate.consumed(43), "dispatched operation not consumed");
            require(
                token.balanceOf(address(reserve)) == 10 && token.balanceOf(address(allocator)) == 90,
                "dispatched operation failed"
            );
        }

        function _dispatch(uint256 nonce)
            private
            view
            returns (O.Envelope memory envelope, bytes memory data, AssuredExecutor.Admission memory admissionWitness)
        {
            envelope = gate.template();
            data = abi.encodeCall(
                ProRataDispatchTarget.ratifyAndFund,
                (OPERATION_ID, reserveCommitment, CONFIGURATION_ROOT, uint64(7), RESIDUAL_RECIPIENT)
            );
            envelope.principal = address(this);
            envelope.nonce = nonce;
            envelope.calldataHash = sha256(data);
            (uint256 revision, bytes32 registrationRoot,,,,) = gate.active();
            admissionWitness = AssuredExecutor.Admission(
                registrationRoot, revision, target.projectionDefinitionRoot(), target.currentResultRoot()
            );
        }

        function _operation() private view returns (ShortfallV2Commitments.PreparedOperation memory operation) {
            operation = ShortfallV2Commitments.PreparedOperation({
                operationId: OPERATION_ID,
                initiator: bytes32(uint256(uint160(address(this)))),
                chainId: uint64(block.chainid),
                reserve: bytes32(uint256(uint160(address(reserve)))),
                token: bytes32(uint256(uint160(address(token)))),
                allocator: bytes32(uint256(uint160(address(allocator)))),
                configurationRoot: CONFIGURATION_ROOT,
                configurationEpoch: 7,
                sourceVerifierId: admission.VERIFIER_ID(),
                executionVerifierId: keccak256("execution-verifier"),
                sourceProgramVKey: VKEY,
                sourceProgramElfSha256: ELF,
                sourceStatementSchema: sourceAdapter.statementSchemaVersion(),
                sourcePublicValuesSchema: sourceAdapter.publicValuesSchema(),
                executionStatementSchema: keccak256("execution-statement-schema"),
                executionPublicValuesSchema: keccak256("execution-public-values-schema"),
                policyRoot: keccak256("application-pro-rata-policy"),
                residualRecipient: bytes32(uint256(uint160(RESIDUAL_RECIPIENT)))
            });
        }

        function _assertDeploymentRouteBinding() private view {
            require(ratification.executionCallerBound(), "ratification route not bound");
            require(ratification.executionCaller() == address(target), "wrong ratification route");
            require(address(target).code.length != 0, "ratification adapter not deployed");
            require(address(target.executor()) == address(gate), "ratification adapter uses wrong executor");
        }
    }
