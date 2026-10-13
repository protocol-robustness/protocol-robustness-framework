// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

import {Canonical as C} from "../src/Canonical.sol";
import {OperationCodec as O} from "../src/OperationCodec.sol";
import {AssuredExecutor, IAssurancePolicy, IObservedTarget} from "../src/AssuredExecutor.sol";

interface VmGovernance {
    function addr(uint256 privateKey) external returns (address);
    function sign(uint256 privateKey, bytes32 digest) external returns (uint8, bytes32, bytes32);
    function prank(address caller) external;
    function recordLogs() external;
    function getRecordedLogs() external returns (Log[] memory);

    struct Log {
        bytes32[] topics;
        bytes data;
        address emitter;
    }
}

/// @notice A candidate build publishes the release, source and configuration
/// identities that governance approved, plus its claimed security-assessment
/// profile. Runtime bytecode identity is read directly from the deployed code.
contract GovernedImplementation {
    bytes32 public immutable releaseRoot;
    bytes32 public immutable configurationRoot;
    bytes32 public immutable sourceArtifactRoot;
    bytes32 public immutable securityPolicyRoot;

    constructor(bytes32 release_, bytes32 configuration_, bytes32 source_, bytes32 securityPolicy_) {
        releaseRoot = release_;
        configurationRoot = configuration_;
        sourceArtifactRoot = source_;
        securityPolicyRoot = securityPolicy_;
    }

    function implementationMarker() external pure returns (bytes32) {
        return keccak256("governance-upgrade-conformance-v1");
    }
}

/// @notice Immutable routing target with one gated upgrade operation and no
/// alternate implementation setter or fallback route in this profile.
contract GovernanceRouter is IObservedTarget {
    address public immutable override executor;
    address public implementation;
    bytes32 public activeReleaseRoot;
    bytes32 public activeConfigurationRoot;
    bytes32 public activeSourceArtifactRoot;
    bytes32 public activeSecurityPolicyRoot;
    bytes32 public immutable projectionDefinitionRoot;

    event RouterUpgraded(
        address indexed priorImplementation,
        address indexed newImplementation,
        bytes32 releaseRoot,
        bytes32 configurationRoot,
        bytes32 sourceArtifactRoot,
        bytes32 securityPolicyRoot
    );

    constructor(address executor_, GovernedImplementation initial) {
        require(executor_ != address(0) && address(initial).code.length != 0, "bad inception");
        executor = executor_;
        implementation = address(initial);
        activeReleaseRoot = initial.releaseRoot();
        activeConfigurationRoot = initial.configurationRoot();
        activeSourceArtifactRoot = initial.sourceArtifactRoot();
        activeSecurityPolicyRoot = initial.securityPolicyRoot();

        string[] memory keys = new string[](3);
        bytes[] memory values = new bytes[](3);
        keys[0] = "artifact/schema";
        values[0] = C.text("governance-router-projection-definition.prototype.v1");
        keys[1] = "executor/address";
        values[1] = C.addressValue(executor_);
        keys[2] = "target/address";
        values[2] = C.addressValue(address(this));
        projectionDefinitionRoot =
            C.root("ao.prototype.governance-router-projection-definition.v1", C.map(keys, values));
    }

    function currentResultRoot() external view override returns (bytes32) {
        string[] memory keys = new string[](8);
        bytes[] memory values = new bytes[](8);
        keys[0] = "artifact/schema";
        values[0] = C.text("governance-router-projection-result.prototype.v1");
        keys[1] = "configuration/root";
        values[1] = C.hashValue(activeConfigurationRoot, false);
        keys[2] = "implementation/address";
        values[2] = C.addressValue(implementation);
        keys[3] = "implementation/code-hash";
        values[3] = C.hashValue(implementation.codehash, true);
        keys[4] = "release/root";
        values[4] = C.hashValue(activeReleaseRoot, false);
        keys[5] = "security-policy/root";
        values[5] = C.hashValue(activeSecurityPolicyRoot, false);
        keys[6] = "source-artifact/root";
        values[6] = C.hashValue(activeSourceArtifactRoot, false);
        keys[7] = "target/address";
        values[7] = C.addressValue(address(this));
        return C.root("ao.prototype.governance-router-projection-result.v1", C.map(keys, values));
    }

    function applyUpgrade(address nextAddress, bytes32 nextReleaseRoot, bytes32 nextConfigurationRoot) external {
        require(msg.sender == executor, "dispatcher only");
        require(nextAddress != implementation && nextAddress.code.length != 0, "invalid implementation");
        GovernedImplementation next = GovernedImplementation(nextAddress);
        require(
            next.releaseRoot() == nextReleaseRoot && next.configurationRoot() == nextConfigurationRoot,
            "candidate identity mismatch"
        );
        address previous = implementation;
        implementation = nextAddress;
        activeReleaseRoot = nextReleaseRoot;
        activeConfigurationRoot = nextConfigurationRoot;
        activeSourceArtifactRoot = next.sourceArtifactRoot();
        activeSecurityPolicyRoot = next.securityPolicyRoot();
        emit RouterUpgraded(
            previous,
            nextAddress,
            nextReleaseRoot,
            nextConfigurationRoot,
            activeSourceArtifactRoot,
            activeSecurityPolicyRoot
        );
    }
}

    /// @notice Governance consumer policy: a timelock is the submitting principal;
    /// a separate governance key attests a candidate release, source, configuration,
    /// security profile and exact current router state.
    contract GovernanceUpgradePolicy is IAssurancePolicy {
        AssuredExecutor public immutable executor;
        GovernanceRouter public immutable target;
        address public immutable timelock;
        address public immutable signer;
        bytes32 public immutable requiredSecurityPolicyRoot;
        bytes32 private immutable _policyRoot;

        constructor(
            AssuredExecutor executor_,
            GovernanceRouter target_,
            address timelock_,
            address signer_,
            bytes32 requiredSecurityPolicyRoot_
        ) {
            require(
                address(executor_) != address(0) && address(target_) != address(0) && timelock_ != address(0)
                    && signer_ != address(0) && requiredSecurityPolicyRoot_ != bytes32(0),
                "invalid policy"
            );
            executor = executor_;
            target = target_;
            timelock = timelock_;
            signer = signer_;
            requiredSecurityPolicyRoot = requiredSecurityPolicyRoot_;
            string[] memory keys = new string[](4);
            bytes[] memory values = new bytes[](4);
            keys[0] = "artifact/schema";
            values[0] = C.text("governance-upgrade-policy.prototype.v1");
            keys[1] = "required-security-policy/root";
            values[1] = C.hashValue(requiredSecurityPolicyRoot_, false);
            keys[2] = "signer/address";
            values[2] = C.addressValue(signer_);
            keys[3] = "timelock/address";
            values[3] = C.addressValue(timelock_);
            _policyRoot = C.root("ao.prototype.governance-upgrade-policy.v1", C.map(keys, values));
        }

        function artifactType() external pure returns (string memory) {
            return "governance-upgrade-policy.prototype.v1";
        }

        function policyRoot() external view returns (bytes32) {
            return _policyRoot;
        }

        function statementRoot(
            O.Envelope memory envelope,
            GovernedImplementation candidate,
            bytes32 projection,
            bytes32 observed,
            uint256 deadline
        ) public view returns (bytes32) {
            string[] memory keys = new string[](12);
            bytes[] memory values = new bytes[](12);
            keys[0] = "artifact/schema";
            values[0] = C.text("governance-upgrade-approval.prototype.v1");
            keys[1] = "candidate/address";
            values[1] = C.addressValue(address(candidate));
            keys[2] = "candidate/code-hash";
            values[2] = C.hashValue(address(candidate).codehash, true);
            keys[3] = "candidate/configuration-root";
            values[3] = C.hashValue(candidate.configurationRoot(), false);
            keys[4] = "candidate/release-root";
            values[4] = C.hashValue(candidate.releaseRoot(), false);
            keys[5] = "candidate/security-policy-root";
            values[5] = C.hashValue(candidate.securityPolicyRoot(), false);
            keys[6] = "candidate/source-artifact-root";
            values[6] = C.hashValue(candidate.sourceArtifactRoot(), false);
            keys[7] = "chain-instance/root";
            values[7] = C.hashValue(envelope.chainInstance, false);
            keys[8] = "envelope/root";
            values[8] = C.hashValue(O.envelopeRoot(envelope), false);
            keys[9] = "policy/root";
            values[9] = C.ref("governance-upgrade-policy.prototype.v1", _policyRoot, false);
            keys[10] = "prior-state";
            string[] memory stateKeys = new string[](2);
            bytes[] memory stateValues = new bytes[](2);
            stateKeys[0] = "definition-root";
            stateValues[0] = C.hashValue(projection, false);
            stateKeys[1] = "result-root";
            stateValues[1] = C.hashValue(observed, false);
            values[10] = C.map(stateKeys, stateValues);
            keys[11] = "validity/not-after-block";
            values[11] = C.uintValue(deadline);
            return C.root("ao.prototype.governance-upgrade-approval.v1", C.map(keys, values));
        }

        function admit(
            O.Envelope calldata envelope,
            bytes calldata data,
            bytes32 projection,
            bytes32 observed,
            bytes calldata evidence
        ) external view returns (bytes32 assuranceRoot) {
            require(msg.sender == address(executor) && envelope.target == address(target), "wrong gate/target");
            require(
                envelope.selector == target.applyUpgrade.selector && envelope.principal == timelock, "wrong authority"
            );
            require(envelope.value == 0 && data.length == 100 && evidence.length == 128, "wrong call shape");
            (address candidateAddress, bytes32 releaseRoot, bytes32 configurationRoot) =
                abi.decode(data[4:], (address, bytes32, bytes32));
            require(candidateAddress.code.length != 0, "candidate has no code");
            GovernedImplementation candidate = GovernedImplementation(candidateAddress);
            require(
                candidate.releaseRoot() == releaseRoot && candidate.configurationRoot() == configurationRoot
                    && candidate.securityPolicyRoot() == requiredSecurityPolicyRoot
                    && candidate.sourceArtifactRoot() != bytes32(0),
                "candidate does not satisfy policy"
            );
            require(
                target.projectionDefinitionRoot() == projection && target.currentResultRoot() == observed, "stale state"
            );
            (uint256 deadline, uint8 v, bytes32 r, bytes32 s) = abi.decode(evidence, (uint256, uint8, bytes32, bytes32));
            require(block.number <= deadline && (v == 27 || v == 28), "expired or invalid signature");
            require(
                uint256(s) <= 0x7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0,
                "non-canonical signature"
            );
            assuranceRoot = statementRoot(envelope, candidate, projection, observed, deadline);
            require(ecrecover(assuranceRoot, v, r, s) == signer, "governance approval mismatch");
        }
    }

    contract GovernanceConformanceTest {
        VmGovernance private constant vm = VmGovernance(address(uint160(uint256(keccak256("hevm cheat code")))));
        uint256 private constant GOVERNANCE_KEY = 0x600d;
        bytes32 private constant CHAIN_INSTANCE = keccak256("governance-chain-instance");
        bytes32 private constant REQUIRED_SECURITY_POLICY = keccak256("upgrade-security-profile-v1");
        uint256 private constant DEADLINE_SPAN = 100;

        AssuredExecutor private gate;
        GovernanceRouter private target;
        GovernanceUpgradePolicy private policy;
        GovernedImplementation private initial;
        GovernedImplementation private candidate;
        address private timelock;

        function setUp() public {
            timelock = address(0x71_1e_10c);
            gate = new AssuredExecutor(CHAIN_INSTANCE, address(this), 0);
            initial = new GovernedImplementation(
                keccak256("release-v1"), keccak256("configuration-v1"), keccak256("source-v1"), REQUIRED_SECURITY_POLICY
            );
            candidate = new GovernedImplementation(
                keccak256("release-v2"), keccak256("configuration-v2"), keccak256("source-v2"), REQUIRED_SECURITY_POLICY
            );
            target = new GovernanceRouter(address(gate), initial);
            policy = new GovernanceUpgradePolicy(
                gate, target, timelock, vm.addr(GOVERNANCE_KEY), REQUIRED_SECURITY_POLICY
            );
            gate.sealTarget(
                address(target),
                target.applyUpgrade.selector,
                keccak256("canonical-target-candidate"),
                keccak256("router-release"),
                keccak256("router-deployment-scope"),
                policy
            );
        }

        function testGovernanceUpgradeBindsArtifactAuthorityAndStateAndReconstructsTransition() public {
            (O.Envelope memory envelope, bytes memory data, AssuredExecutor.Admission memory witness) = _intent(1);
            bytes32 preState = witness.consumedResultRoot;
            bytes memory evidence = _approval(envelope, candidate, witness);
            bytes32 expectedAssurance = policy.statementRoot(
                envelope, candidate, witness.projectionDefinitionRoot, preState, block.number + DEADLINE_SPAN
            );

            vm.recordLogs();
            vm.prank(timelock);
            gate.execute(envelope, witness, data, evidence);
            VmGovernance.Log[] memory logs = vm.getRecordedLogs();

            require(target.implementation() == address(candidate), "implementation not installed");
            require(target.activeReleaseRoot() == candidate.releaseRoot(), "release not installed");
            require(target.activeConfigurationRoot() == candidate.configurationRoot(), "configuration not installed");
            require(
                target.activeSourceArtifactRoot() == candidate.sourceArtifactRoot(), "source artifact not installed"
            );
            require(target.activeSecurityPolicyRoot() == REQUIRED_SECURITY_POLICY, "security profile not installed");
            require(target.currentResultRoot() != preState, "projection did not advance");
            require(gate.consumed(1), "operation nonce not consumed");

            bytes32 upgradeTopic = keccak256("RouterUpgraded(address,address,bytes32,bytes32,bytes32,bytes32)");
            bool foundUpgrade;
            for (uint256 i; i < logs.length; ++i) {
                if (logs[i].emitter == address(target) && logs[i].topics[0] == upgradeTopic) {
                    foundUpgrade = true;
                    require(
                        address(uint160(uint256(logs[i].topics[1]))) == address(initial), "wrong prior implementation"
                    );
                    require(
                        address(uint160(uint256(logs[i].topics[2]))) == address(candidate), "wrong new implementation"
                    );
                    (bytes32 releaseRoot, bytes32 configurationRoot, bytes32 sourceRoot, bytes32 securityRoot) =
                        abi.decode(logs[i].data, (bytes32, bytes32, bytes32, bytes32));
                    require(
                        releaseRoot == candidate.releaseRoot() && configurationRoot == candidate.configurationRoot()
                            && sourceRoot == candidate.sourceArtifactRoot() && securityRoot == REQUIRED_SECURITY_POLICY,
                        "upgrade event does not reconstruct installed state"
                    );
                }
                if (
                    logs[i].emitter == address(gate)
                        && logs[i].topics[0]
                            == keccak256(
                                "OperationExecuted(bytes32,bytes32,uint256,bytes32,address,bytes32,bytes32,bytes32,bytes32,bytes32,address,uint256,bytes32)"
                            )
                ) {
                    require(logs[i].topics[1] == gate.envelopeRoot(envelope), "wrong authorized envelope");
                    require(logs[i].topics[2] == witness.expectedRegistrationRoot, "wrong registration revision");
                    (
                        uint256 registrationRevision,
                        bytes32 activePolicy,
                        address verifier,
                        bytes32 verifierCodeHash,
                        bytes32 assuranceRoot,
                        bytes32 evidenceHash,
                        bytes32 projectionRoot,
                        bytes32 consumedRoot,
                        address principal,
                        uint256 nonce,
                        bytes32 postRoot
                    ) = abi.decode(
                        logs[i].data,
                        (
                            uint256,
                            bytes32,
                            address,
                            bytes32,
                            bytes32,
                            bytes32,
                            bytes32,
                            bytes32,
                            address,
                            uint256,
                            bytes32
                        )
                    );
                    require(
                        registrationRevision == witness.expectedRegistrationRevision
                            && activePolicy == policy.policyRoot() && verifier == address(policy)
                            && verifierCodeHash == address(policy).codehash && assuranceRoot == expectedAssurance
                            && evidenceHash == sha256(evidence) && projectionRoot == witness.projectionDefinitionRoot
                            && consumedRoot == preState && principal == timelock && nonce == 1
                            && postRoot == target.currentResultRoot(),
                        "authorization evidence mismatch"
                    );
                }
            }
            require(foundUpgrade, "upgrade event missing");
        }

        function testCandidateOutsidePolicyCannotConsumeNonce() public {
            GovernedImplementation unsafeCandidate = new GovernedImplementation(
                keccak256("release-unsafe"),
                keccak256("configuration-unsafe"),
                keccak256("source-unsafe"),
                keccak256("unapproved-security-profile")
            );
            (O.Envelope memory envelope, bytes memory data, AssuredExecutor.Admission memory witness) =
                _intentFor(2, unsafeCandidate);
            bytes memory evidence = _approval(envelope, unsafeCandidate, witness);
            vm.prank(timelock);
            (bool ok,) =
                address(gate).call(abi.encodeCall(AssuredExecutor.execute, (envelope, witness, data, evidence)));
            require(!ok && !gate.consumed(2), "policy-invalid candidate was admitted");
            require(target.implementation() == address(initial), "invalid candidate changed target");
        }

        function testWrongSubmittingAuthorityCannotUseGovernanceApproval() public {
            (O.Envelope memory envelope, bytes memory data, AssuredExecutor.Admission memory witness) = _intent(3);
            bytes memory evidence = _approval(envelope, candidate, witness);
            vm.prank(address(0xBAD));
            (bool ok,) =
                address(gate).call(abi.encodeCall(AssuredExecutor.execute, (envelope, witness, data, evidence)));
            require(!ok && !gate.consumed(3), "wrong governance principal admitted");
        }

        function testSuccessfulUpgradeCannotReplaySameEnvelope() public {
            (O.Envelope memory envelope, bytes memory data, AssuredExecutor.Admission memory witness) = _intent(4);
            bytes memory evidence = _approval(envelope, candidate, witness);
            vm.prank(timelock);
            gate.execute(envelope, witness, data, evidence);
            AssuredExecutor.Admission memory updatedWitness = _witness();
            vm.prank(timelock);
            (bool ok,) =
                address(gate).call(abi.encodeCall(AssuredExecutor.execute, (envelope, updatedWitness, data, evidence)));
            require(!ok && gate.consumed(4), "envelope replayed");
        }

        function _intent(uint256 nonce)
            private
            view
            returns (O.Envelope memory envelope, bytes memory data, AssuredExecutor.Admission memory witness)
        {
            return _intentFor(nonce, candidate);
        }

        function _intentFor(uint256 nonce, GovernedImplementation implementation)
            private
            view
            returns (O.Envelope memory envelope, bytes memory data, AssuredExecutor.Admission memory witness)
        {
            envelope = gate.template();
            data = abi.encodeCall(
                GovernanceRouter.applyUpgrade,
                (address(implementation), implementation.releaseRoot(), implementation.configurationRoot())
            );
            envelope.principal = timelock;
            envelope.nonce = nonce;
            envelope.calldataHash = sha256(data);
            witness = _witness();
        }

        function _witness() private view returns (AssuredExecutor.Admission memory witness) {
            (uint256 revision, bytes32 registrationRoot,,,,) = gate.active();
            witness = AssuredExecutor.Admission(
                registrationRoot, revision, target.projectionDefinitionRoot(), target.currentResultRoot()
            );
        }

        function _approval(
            O.Envelope memory envelope,
            GovernedImplementation implementation,
            AssuredExecutor.Admission memory witness
        ) private returns (bytes memory) {
            uint256 deadline = block.number + DEADLINE_SPAN;
            bytes32 statement = policy.statementRoot(
                envelope, implementation, witness.projectionDefinitionRoot, witness.consumedResultRoot, deadline
            );
            (uint8 v, bytes32 r, bytes32 s) = vm.sign(GOVERNANCE_KEY, statement);
            return abi.encode(deadline, v, r, s);
        }
    }
