// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;
import {Canonical as C} from "./Canonical.sol";
import {OperationCodec as O} from "./OperationCodec.sol";

interface IObservedTarget {
    function executor() external view returns (address);
    function projectionDefinitionRoot() external view returns (bytes32);
    function currentResultRoot() external view returns (bytes32);
}

interface IAssurancePolicy {
    function artifactType() external pure returns (string memory);
    function policyRoot() external view returns (bytes32);
    function admit(
        O.Envelope calldata intent,
        bytes calldata data,
        bytes32 projection,
        bytes32 observed,
        bytes calldata evidence
    ) external view returns (bytes32 assuranceRoot);
}

/// @notice Experimental single-target gate; constructor governance is a local
/// prototype control lane, not a substitute for PRF governed-authority artifacts.
contract AssuredExecutor {
    address public immutable governance;
    uint256 public immutable executionChainId;
    bytes32 public immutable chainInstance;
    uint256 public immutable activationDelayBlocks;
    bytes32 public immutable controlRoot;
    bytes32 public immutable replayNamespace;
    bytes32 public profileRoot;
    O.Envelope private _template;
    bool public sealedTarget;
    bool private _entered;
    mapping(uint256 => bool) public consumed;

    struct Registration {
        uint256 revision;
        bytes32 root;
        bytes32 policyRoot;
        address policy;
        bytes32 policyCodeHash;
        bool disabled;
    }
    Registration public active;

    struct Pending {
        Registration next;
        bytes32 predecessor;
        uint256 activateAtBlock;
    }
    Pending private _pending;

    struct Admission {
        bytes32 expectedRegistrationRoot;
        uint256 expectedRegistrationRevision;
        bytes32 projectionDefinitionRoot;
        bytes32 consumedResultRoot;
    }

    error Unauthorized();
    error InvalidIdentity();
    error RegistrationChanged();
    error Disabled();
    error StaleObservation();
    error Replay();
    error Reentrant();
    error ActivationNotReady();
    error TargetReverted(bytes reason);

    event TargetSealed(
        address indexed target,
        bytes32 candidate,
        bytes32 releaseRoot,
        bytes32 scope,
        bytes32 runtimeHash,
        bytes32 profile,
        bytes32 registration
    );
    event RegistrationProposed(
        bytes32 indexed predecessor,
        bytes32 indexed successor,
        uint256 revision,
        address policy,
        bytes32 policyRoot,
        bytes32 policyCodeHash,
        bool disabled,
        uint256 activateAtBlock
    );
    event RegistrationActivated(
        bytes32 indexed predecessor,
        bytes32 indexed successor,
        uint256 revision,
        address policy,
        bytes32 policyRoot,
        bool disabled
    );
    event OperationExecuted(
        bytes32 indexed envelopeRoot,
        bytes32 indexed registrationRoot,
        uint256 registrationRevision,
        bytes32 policyRoot,
        address policy,
        bytes32 policyCodeHash,
        bytes32 assuranceRoot,
        bytes32 evidenceHash,
        bytes32 projectionDefinitionRoot,
        bytes32 consumedResultRoot,
        address principal,
        uint256 nonce,
        bytes32 postResultRoot
    );

    modifier onlyGovernance() {
        if (msg.sender != governance) revert Unauthorized();
        _;
    }
    modifier serialized() {
        if (_entered) revert Reentrant();
        _entered = true;
        _;
        _entered = false;
    }

    constructor(bytes32 genesisRoot, address authority, uint256 delayBlocks) {
        require(genesisRoot != bytes32(0) && authority != address(0), "invalid inception");
        chainInstance = genesisRoot;
        executionChainId = block.chainid;
        governance = authority;
        activationDelayBlocks = delayBlocks;
        string[] memory k = new string[](4);
        bytes[] memory v = new bytes[](4);
        k[0] = "artifact/schema";
        v[0] = C.text("ao-registration-control.prototype.v1");
        k[1] = "governance/address";
        v[1] = C.addressValue(authority);
        k[2] = "activation-delay-blocks";
        v[2] = C.uintValue(delayBlocks);
        k[3] = "removal";
        v[3] = C.keyword("forbidden");
        controlRoot = C.root("ao.prototype.registration-control.v1", C.map(k, v));
        k = new string[](3);
        v = new bytes[](3);
        k[0] = "artifact/schema";
        v[0] = C.text("ao-replay-namespace.prototype.v1");
        k[1] = "chain-instance/root";
        v[1] = C.hashValue(genesisRoot, false);
        k[2] = "executor/address";
        v[2] = C.addressValue(address(this));
        replayNamespace = C.root("ao.prototype.replay-namespace.v1", C.map(k, v));
    }

    /// @dev Governance seals fixture admission references once. Production must
    /// authenticate the existing release/candidate/manifest joins independently.
    function sealTarget(
        address target,
        bytes4 selector,
        bytes32 candidate,
        bytes32 releaseRoot,
        bytes32 scope,
        IAssurancePolicy policy
    ) external onlyGovernance serialized {
        require(
            !sealedTarget && target.code.length != 0 && candidate != bytes32(0) && releaseRoot != bytes32(0)
                && scope != bytes32(0),
            "bad seal"
        );
        require(IObservedTarget(target).executor() == address(this), "wrong target gate");
        string[] memory k = new string[](4);
        bytes[] memory v = new bytes[](4);
        k[0] = "artifact/schema";
        v[0] = C.text("ao-dispatcher-profile.prototype.v1");
        k[1] = "executor/address";
        v[1] = C.addressValue(address(this));
        k[2] = "executor/code-hash";
        v[2] = C.hashValue(address(this).codehash, true);
        k[3] = "caller-mode";
        v[3] = C.keyword("outer-msg-sender");
        profileRoot = C.root("ao.prototype.dispatcher-profile.v1", C.map(k, v));
        _template = O.Envelope(
            chainInstance,
            releaseRoot,
            candidate,
            scope,
            target,
            target.codehash,
            profileRoot,
            selector,
            bytes32(0),
            0,
            governance,
            replayNamespace,
            0
        );
        active = _registration(policy, false, 0, bytes32(0));
        sealedTarget = true;
        emit TargetSealed(target, candidate, releaseRoot, scope, target.codehash, profileRoot, active.root);
        emit RegistrationActivated(bytes32(0), active.root, 0, active.policy, active.policyRoot, false);
    }

    function template() external view returns (O.Envelope memory) {
        return _template;
    }

    function envelopeRoot(O.Envelope calldata e) external pure returns (bytes32) {
        return O.envelopeRoot(e);
    }

    function pendingRoot() external view returns (bytes32) {
        return _pending.next.root;
    }

    function _registration(IAssurancePolicy policy, bool disabled, uint256 revision, bytes32 parent)
        private
        view
        returns (Registration memory next)
    {
        require(address(policy).code.length != 0, "policy has no code");
        bytes32 p = policy.policyRoot();
        require(p != bytes32(0), "empty policy");
        bytes32 root = O.registrationRoot(_template, revision, parent, p, policy.artifactType(), controlRoot, disabled);
        next = Registration(revision, root, p, address(policy), address(policy).codehash, disabled);
    }

    function propose(bytes32 expectedPredecessor, IAssurancePolicy policy, bool disabled)
        external
        onlyGovernance
        serialized
        returns (bytes32)
    {
        if (!sealedTarget || active.root != expectedPredecessor) revert RegistrationChanged();
        Registration memory next = _registration(policy, disabled, active.revision + 1, active.root);
        uint256 activateAt = block.number + activationDelayBlocks;
        _pending = Pending(next, active.root, activateAt);
        emit RegistrationProposed(
            active.root,
            next.root,
            next.revision,
            next.policy,
            next.policyRoot,
            next.policyCodeHash,
            disabled,
            activateAt
        );
        return next.root;
    }

    function activate(bytes32 expectedSuccessor) external onlyGovernance serialized {
        Pending memory proposal = _pending;
        if (
            proposal.next.root == bytes32(0) || proposal.next.root != expectedSuccessor
                || proposal.predecessor != active.root
        ) revert RegistrationChanged();
        if (block.number < proposal.activateAtBlock) revert ActivationNotReady();
        _checkPolicy(proposal.next);
        active = proposal.next;
        delete _pending;
        emit RegistrationActivated(
            proposal.predecessor, active.root, active.revision, active.policy, active.policyRoot, active.disabled
        );
    }

    function _checkPolicy(Registration memory r) private view {
        if (r.policy.codehash != r.policyCodeHash || IAssurancePolicy(r.policy).policyRoot() != r.policyRoot) {
            revert InvalidIdentity();
        }
    }

    function execute(
        O.Envelope calldata e,
        Admission calldata witness,
        bytes calldata targetData,
        bytes calldata evidence
    ) external payable serialized returns (bytes memory output) {
        Registration memory observed = active;
        if (!sealedTarget || block.chainid != executionChainId) revert InvalidIdentity();
        if (
            witness.expectedRegistrationRoot != observed.root
                || witness.expectedRegistrationRevision != observed.revision
        ) revert RegistrationChanged();
        if (observed.disabled) revert Disabled();
        _checkPolicy(observed);
        O.Envelope memory t = _template;
        if (
            e.chainInstance != chainInstance || e.target != t.target || e.target.codehash != t.codeHash
                || e.codeHash != t.codeHash || e.release != t.release || e.candidate != t.candidate
                || e.scope != t.scope || e.profile != profileRoot || e.replayNamespace != replayNamespace
                || e.selector != t.selector || e.principal != msg.sender || e.value != msg.value
                || targetData.length < 4 || bytes4(targetData[:4]) != e.selector || sha256(targetData) != e.calldataHash
        ) revert InvalidIdentity();
        IObservedTarget target = IObservedTarget(e.target);
        bytes32 projection = target.projectionDefinitionRoot();
        bytes32 result = target.currentResultRoot();
        if (projection != witness.projectionDefinitionRoot || result != witness.consumedResultRoot) {
            revert StaleObservation();
        }
        if (consumed[e.nonce]) revert Replay();
        bytes32 assuranceRoot = IAssurancePolicy(observed.policy).admit(e, targetData, projection, result, evidence);
        require(assuranceRoot != bytes32(0), "missing assurance");
        // Policies/observations are STATICCALLs. Mutation attempts fail; no
        // external mutable callback occurs between these checks and reservation.
        consumed[e.nonce] = true;
        bool ok;
        (ok, output) = e.target.call{value: e.value}(targetData);
        if (!ok) revert TargetReverted(output);
        emit OperationExecuted(
            O.envelopeRoot(e),
            observed.root,
            observed.revision,
            observed.policyRoot,
            observed.policy,
            observed.policyCodeHash,
            assuranceRoot,
            sha256(evidence),
            projection,
            result,
            msg.sender,
            e.nonce,
            target.currentResultRoot()
        );
    }
}
