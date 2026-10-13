// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;
import {Canonical as C} from "../src/Canonical.sol";
import {OperationCodec as O} from "../src/OperationCodec.sol";
import {AssuredExecutor, IAssurancePolicy} from "../src/AssuredExecutor.sol";
import {Treasury, TreasuryPolicy} from "../src/Treasury.sol";

interface Vm {
    struct Log {
        bytes32[] topics;
        bytes data;
        address emitter;
    }
    function addr(uint256 privateKey) external returns (address);
    function sign(uint256 privateKey, bytes32 digest) external returns (uint8, bytes32, bytes32);
    function deal(address who, uint256 balance) external;
    function prank(address caller) external;
    function roll(uint256 blockNumber) external;
    function chainId(uint256 chainId) external;
    function etch(address who, bytes calldata code) external;
    function expectRevert() external;
    function expectRevert(bytes4 selector) external;
    function expectRevert(bytes calldata reason) external;
    function recordLogs() external;
    function getRecordedLogs() external returns (Log[] memory);
    function readFile(string calldata path) external view returns (string memory);
    function projectRoot() external view returns (string memory);
    function parseJsonBytes32(string calldata json, string calldata key) external pure returns (bytes32);
    function parseJsonBytes(string calldata json, string calldata key) external pure returns (bytes memory);
}

contract RejectingDestination {
    receive() external payable {
        revert("reject");
    }
}

contract ReentrantDestination {
    AssuredExecutor gate;
    bytes payload;
    bool public blocked;

    constructor(AssuredExecutor g, bytes memory p) {
        gate = g;
        payload = p;
    }

    receive() external payable {
        (bool ok, bytes memory why) = address(gate).call(payload);
        require(!ok && bytes4(why) == AssuredExecutor.Reentrant.selector, "gate not serialized");
        blocked = true;
    }
}

contract AssuredOperationsTest {
    Vm private constant vm = Vm(address(uint160(uint256(keccak256("hevm cheat code")))));
    uint256 private constant ATTESTOR_KEY = 0xa11ce;
    AssuredExecutor gate;
    Treasury treasury;
    TreasuryPolicy policy;
    address destination = address(0xd357);
    bool activationCallback;
    bool governanceCallbackBlocked;
    bytes32 callbackSuccessor;

    receive() external payable {
        if (activationCallback) {
            (bool ok, bytes memory why) =
                address(gate).call(abi.encodeCall(AssuredExecutor.activate, (callbackSuccessor)));
            require(!ok && bytes4(why) == AssuredExecutor.Reentrant.selector, "governance callback escaped fence");
            governanceCallbackBlocked = true;
        }
    }

    function setUp() public {
        gate = new AssuredExecutor(bytes32(uint256(1)), address(this), 1);
        treasury = new Treasury(address(gate));
        policy = new TreasuryPolicy(vm.addr(ATTESTOR_KEY), 100, 10);
        gate.sealTarget(
            address(treasury),
            treasury.withdraw.selector,
            bytes32(uint256(2)),
            bytes32(uint256(3)),
            bytes32(uint256(4)),
            policy
        );
        vm.deal(address(this), 2000);
        (bool ok,) = address(treasury).call{value: 1000}("");
        require(ok);
    }

    function intent(uint256 nonce, address recipient, uint256 amount)
        private
        view
        returns (O.Envelope memory e, bytes memory data)
    {
        e = gate.template();
        data = abi.encodeCall(Treasury.withdraw, (payable(recipient), amount));
        e.principal = address(this);
        e.nonce = nonce;
        e.calldataHash = sha256(data);
    }

    function witness() private view returns (AssuredExecutor.Admission memory w) {
        (uint256 revision, bytes32 root,,,,) = gate.active();
        w = AssuredExecutor.Admission(root, revision, treasury.projectionDefinitionRoot(), treasury.currentResultRoot());
    }

    function proof(TreasuryPolicy p, O.Envelope memory e, address recipient, AssuredExecutor.Admission memory w)
        private
        returns (bytes memory)
    {
        uint256 deadline = block.number + 100;
        bytes32 statement = p.statementRoot(e, recipient, w.projectionDefinitionRoot, w.consumedResultRoot, deadline);
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(ATTESTOR_KEY, statement);
        return abi.encode(deadline, v, r, s);
    }

    function testWithdrawalAndForensicComposition() public {
        (O.Envelope memory e, bytes memory data) = intent(7, destination, 100);
        AssuredExecutor.Admission memory w = witness();
        bytes memory evidence = proof(policy, e, destination, w);
        bytes32 expectedAssurance =
            policy.statementRoot(e, destination, w.projectionDefinitionRoot, w.consumedResultRoot, block.number + 100);
        uint256 destinationBefore = destination.balance;
        vm.recordLogs();
        gate.execute(e, w, data, evidence);
        Vm.Log[] memory logs = vm.getRecordedLogs();
        require(logs.length == 2 && logs[0].emitter == address(treasury) && logs[1].emitter == address(gate));
        require(
            logs[0].topics[0]
                == keccak256(
                    "WithdrawalObserved(address,address,uint256,uint256,uint256,uint256,uint256,uint256,uint256)"
                )
        );
        require(address(uint160(uint256(logs[0].topics[1]))) == address(treasury));
        require(address(uint160(uint256(logs[0].topics[2]))) == destination);
        (
            uint256 amount,
            uint256 preRevision,
            uint256 postRevision,
            uint256 sourceBefore,
            uint256 sourceAfter,
            uint256 destBefore,
            uint256 destAfter
        ) = abi.decode(logs[0].data, (uint256, uint256, uint256, uint256, uint256, uint256, uint256));
        require(amount == 100 && preRevision == 1 && postRevision == 2);
        require(
            sourceBefore == 1000 && sourceAfter == 900 && destBefore == destinationBefore
                && destAfter == destinationBefore + 100
        );
        require(logs[1].topics[1] == gate.envelopeRoot(e) && logs[1].topics[2] == w.expectedRegistrationRoot);
        (
            uint256 regRevision,
            bytes32 appliedPolicy,
            address verifier,
            bytes32 verifierCode,
            bytes32 assurance,
            bytes32 evidenceHash,
            bytes32 projection,
            bytes32 consumedObservation,
            address principal,
            uint256 nonce,
            bytes32 postObservation
        ) = abi.decode(
            logs[1].data,
            (uint256, bytes32, address, bytes32, bytes32, bytes32, bytes32, bytes32, address, uint256, bytes32)
        );
        require(
            regRevision == 0 && appliedPolicy == policy.policyRoot() && verifier == address(policy)
                && verifierCode == address(policy).codehash && assurance == expectedAssurance
                && evidenceHash == sha256(evidence) && projection == w.projectionDefinitionRoot
                && consumedObservation == w.consumedResultRoot && principal == e.principal && nonce == 7
                && postObservation == treasury.currentResultRoot()
        );
        require(gate.consumed(7) && address(treasury).balance == 900 && destination.balance == destAfter);
    }

    function testDirectBypassRejected() public {
        vm.expectRevert();
        treasury.withdraw(payable(destination), 100);
        require(address(treasury).balance == 1000);
    }

    function testNoAlternativeSelectorOrFallback() public {
        (O.Envelope memory e,) = intent(1, destination, 100);
        e.selector = bytes4(0xdeadbeef);
        bytes memory data = abi.encodePacked(e.selector);
        e.calldataHash = sha256(data);
        AssuredExecutor.Admission memory w = witness();
        vm.expectRevert(AssuredExecutor.InvalidIdentity.selector);
        gate.execute(e, w, data, "");
        (bool ok,) = address(treasury).call(hex"deadbeef");
        require(!ok);
    }

    function testReplayWithFreshObservationStillRejected() public {
        (O.Envelope memory e, bytes memory data) = intent(1, destination, 100);
        AssuredExecutor.Admission memory w = witness();
        bytes memory evidence = proof(policy, e, destination, w);
        gate.execute(e, w, data, evidence);
        w = witness();
        vm.expectRevert(AssuredExecutor.Replay.selector);
        gate.execute(e, w, data, evidence);
    }

    function testReusableStatementDoesNotCommitNonceOrExactAmount() public {
        (O.Envelope memory first, bytes memory data) = intent(1, destination, 50);
        (O.Envelope memory second, bytes memory secondData) = intent(2, destination, 100);
        AssuredExecutor.Admission memory w = witness();
        bytes memory evidence = proof(policy, first, destination, w);
        bytes32 a = policy.admit(first, data, w.projectionDefinitionRoot, w.consumedResultRoot, evidence);
        bytes32 b = policy.admit(second, secondData, w.projectionDefinitionRoot, w.consumedResultRoot, evidence);
        require(a == b && gate.envelopeRoot(first) != gate.envelopeRoot(second));
        gate.execute(first, w, data, evidence);
        vm.expectRevert(AssuredExecutor.StaleObservation.selector);
        gate.execute(second, w, secondData, evidence);
    }

    function testDepositMakesAssuranceStale() public {
        (O.Envelope memory e, bytes memory data) = intent(1, destination, 100);
        AssuredExecutor.Admission memory w = witness();
        bytes memory evidence = proof(policy, e, destination, w);
        (bool ok,) = address(treasury).call{value: 1}("");
        require(ok);
        vm.expectRevert(AssuredExecutor.StaleObservation.selector);
        gate.execute(e, w, data, evidence);
        require(!gate.consumed(1));
    }

    function testChangedObservationCannotRetargetOldSignature() public {
        (O.Envelope memory e, bytes memory data) = intent(1, destination, 100);
        AssuredExecutor.Admission memory w = witness();
        bytes memory evidence = proof(policy, e, destination, w);
        (bool ok,) = address(treasury).call{value: 1}("");
        require(ok);
        w = witness();
        vm.expectRevert();
        gate.execute(e, w, data, evidence);
    }

    function testTargetRevertRollsBackNonceRevisionAndBalances() public {
        RejectingDestination recipient = new RejectingDestination();
        (O.Envelope memory e, bytes memory data) = intent(1, address(recipient), 100);
        AssuredExecutor.Admission memory w = witness();
        bytes memory evidence = proof(policy, e, address(recipient), w);
        vm.expectRevert(
            abi.encodeWithSelector(
                AssuredExecutor.TargetReverted.selector,
                abi.encodeWithSignature("Error(string)", "destination reverted")
            )
        );
        gate.execute(e, w, data, evidence);
        require(!gate.consumed(1) && treasury.revision() == 1 && address(treasury).balance == 1000);
        (e, data) = intent(1, destination, 100);
        evidence = proof(policy, e, destination, w);
        gate.execute(e, w, data, evidence);
        require(gate.consumed(1));
    }

    function testReentrantDispatchBlocked() public {
        (O.Envelope memory e, bytes memory data) = intent(1, destination, 100);
        AssuredExecutor.Admission memory w = witness();
        bytes memory evidence = proof(policy, e, destination, w);
        ReentrantDestination recipient =
            new ReentrantDestination(gate, abi.encodeCall(AssuredExecutor.execute, (e, w, data, evidence)));
        (e, data) = intent(1, address(recipient), 100);
        evidence = proof(policy, e, address(recipient), w);
        gate.execute(e, w, data, evidence);
        require(recipient.blocked());
    }

    function testUnauthorizedGovernanceAndResealRejected() public {
        AssuredExecutor.Admission memory w = witness();
        vm.prank(address(0xbad));
        vm.expectRevert(AssuredExecutor.Unauthorized.selector);
        gate.propose(w.expectedRegistrationRoot, policy, true);
        vm.expectRevert();
        gate.sealTarget(
            address(treasury),
            treasury.withdraw.selector,
            bytes32(uint256(5)),
            bytes32(uint256(6)),
            bytes32(uint256(7)),
            policy
        );
    }

    function testActivationSameBlockRejectsObservedOldRevision() public {
        (O.Envelope memory e, bytes memory data) = intent(1, destination, 100);
        AssuredExecutor.Admission memory old = witness();
        bytes memory evidence = proof(policy, e, destination, old);
        TreasuryPolicy successor = new TreasuryPolicy(vm.addr(ATTESTOR_KEY), 90, 10);
        bytes32 next = gate.propose(old.expectedRegistrationRoot, successor, false);
        vm.expectRevert(AssuredExecutor.ActivationNotReady.selector);
        gate.activate(next);
        vm.roll(block.number + 1);
        gate.activate(next);
        vm.expectRevert(AssuredExecutor.RegistrationChanged.selector);
        gate.execute(e, old, data, evidence);
        AssuredExecutor.Admission memory fresh = witness();
        require(fresh.expectedRegistrationRevision == 1 && fresh.expectedRegistrationRoot == next);
        // Updated witness cannot quietly preserve old policy authority.
        vm.expectRevert();
        gate.execute(e, fresh, data, evidence);
        (e, data) = intent(1, destination, 90);
        evidence = proof(successor, e, destination, fresh);
        gate.execute(e, fresh, data, evidence);
    }

    function testExecutionBeforeActivationKeepsExplicitOldRevision() public {
        (O.Envelope memory e, bytes memory data) = intent(1, destination, 100);
        AssuredExecutor.Admission memory old = witness();
        bytes memory evidence = proof(policy, e, destination, old);
        bytes32 next = gate.propose(old.expectedRegistrationRoot, policy, true);
        vm.roll(block.number + 1);
        gate.execute(e, old, data, evidence); // Same transaction/block as activation, but precedes it.
        gate.activate(next);
        (e, data) = intent(2, destination, 100);
        AssuredExecutor.Admission memory fresh = witness();
        vm.expectRevert(AssuredExecutor.Disabled.selector);
        gate.execute(e, fresh, data, "");
    }

    function testReplacedPendingProposalAndStalePredecessorRejected() public {
        AssuredExecutor.Admission memory w = witness();
        bytes32 first = gate.propose(w.expectedRegistrationRoot, policy, true);
        bytes32 second = gate.propose(w.expectedRegistrationRoot, policy, false);
        vm.roll(block.number + 1);
        vm.expectRevert(AssuredExecutor.RegistrationChanged.selector);
        gate.activate(first);
        gate.activate(second);
        vm.expectRevert(AssuredExecutor.RegistrationChanged.selector);
        gate.propose(w.expectedRegistrationRoot, policy, false);
    }

    function testDisableEnableNeverResetsReplay() public {
        uint256 firstBlock = block.number;
        (O.Envelope memory e, bytes memory data) = intent(1, destination, 100);
        AssuredExecutor.Admission memory w = witness();
        gate.execute(e, w, data, proof(policy, e, destination, w));
        w = witness();
        bytes32 next = gate.propose(w.expectedRegistrationRoot, policy, true);
        vm.roll(firstBlock + 1);
        gate.activate(next);
        w = witness();
        next = gate.propose(w.expectedRegistrationRoot, policy, false);
        vm.roll(firstBlock + 2);
        gate.activate(next);
        w = witness();
        vm.expectRevert(AssuredExecutor.Replay.selector);
        gate.execute(e, w, data, "");
    }

    function activateThenExecute(
        bytes32 next,
        O.Envelope calldata e,
        AssuredExecutor.Admission calldata w,
        bytes calldata data,
        bytes calldata evidence
    ) external {
        require(msg.sender == address(this), "test harness only");
        gate.activate(next);
        gate.execute(e, w, data, evidence);
    }

    function testAtomicBatchRevertsActivationWhenFollowingOldRevisionExecutionFails() public {
        (O.Envelope memory e, bytes memory data) = intent(1, destination, 100);
        AssuredExecutor.Admission memory w = witness();
        bytes memory evidence = proof(policy, e, destination, w);
        bytes32 next = gate.propose(w.expectedRegistrationRoot, policy, true);
        vm.roll(block.number + 1);
        (bool ok, bytes memory why) =
            address(this).call(abi.encodeCall(this.activateThenExecute, (next, e, w, data, evidence)));
        require(!ok && bytes4(why) == AssuredExecutor.RegistrationChanged.selector);
        AssuredExecutor.Admission memory restored = witness();
        require(
            restored.expectedRegistrationRoot == w.expectedRegistrationRoot
                && restored.expectedRegistrationRevision == 0 && gate.pendingRoot() == next
        );
        gate.execute(e, w, data, evidence);
    }

    function testAuthorizedGovernanceCallbackCannotActivateDuringDispatch() public {
        (O.Envelope memory e, bytes memory data) = intent(1, address(this), 100);
        AssuredExecutor.Admission memory w = witness();
        bytes memory evidence = proof(policy, e, address(this), w);
        callbackSuccessor = gate.propose(w.expectedRegistrationRoot, policy, true);
        vm.roll(block.number + 1);
        activationCallback = true;
        gate.execute(e, w, data, evidence);
        require(governanceCallbackBlocked && witness().expectedRegistrationRoot == w.expectedRegistrationRoot);
        gate.activate(callbackSuccessor);
    }

    function testVerifierSignerChangeRequiresNewAssuranceDespiteSameLimitsAndState() public {
        (O.Envelope memory e, bytes memory data) = intent(1, destination, 100);
        AssuredExecutor.Admission memory w = witness();
        bytes memory oldEvidence = proof(policy, e, destination, w);
        uint256 newKey = 0xb0b;
        TreasuryPolicy successor = new TreasuryPolicy(vm.addr(newKey), 100, 10);
        bytes32 next = gate.propose(w.expectedRegistrationRoot, successor, false);
        vm.roll(block.number + 1);
        gate.activate(next);
        w = witness();
        vm.expectRevert();
        gate.execute(e, w, data, oldEvidence);
        uint256 deadline = block.number + 100;
        bytes32 statement =
            successor.statementRoot(e, destination, w.projectionDefinitionRoot, w.consumedResultRoot, deadline);
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(newKey, statement);
        gate.execute(e, w, data, abi.encode(deadline, v, r, s));
    }

    function testFuzzWithdrawalPreservesExactBalancesAndConsumesNonce(uint8 seed, uint256 nonce) public {
        uint256 amount = uint256(seed) % 100 + 1;
        uint256 beforeDestination = destination.balance;
        (O.Envelope memory e, bytes memory data) = intent(nonce, destination, amount);
        AssuredExecutor.Admission memory w = witness();
        gate.execute(e, w, data, proof(policy, e, destination, w));
        require(
            gate.consumed(nonce) && treasury.revision() == 2 && address(treasury).balance == 1000 - amount
                && destination.balance == beforeDestination + amount
        );
    }

    function testWrongChainCallerTargetReleaseCandidateCodeProfileAndCalldata() public {
        (O.Envelope memory e, bytes memory data) = intent(1, destination, 100);
        AssuredExecutor.Admission memory w = witness();
        bytes memory evidence = proof(policy, e, destination, w);
        O.Envelope memory bad = e;
        bad.chainInstance = bytes32(uint256(99));
        vm.expectRevert(AssuredExecutor.InvalidIdentity.selector);
        gate.execute(bad, w, data, evidence);
        (e, data) = intent(1, destination, 100);
        e.target = address(0x123);
        vm.expectRevert(AssuredExecutor.InvalidIdentity.selector);
        gate.execute(e, w, data, evidence);
        (e, data) = intent(1, destination, 100);
        e.release = bytes32(uint256(99));
        vm.expectRevert(AssuredExecutor.InvalidIdentity.selector);
        gate.execute(e, w, data, evidence);
        (e, data) = intent(1, destination, 100);
        e.candidate = bytes32(uint256(99));
        vm.expectRevert(AssuredExecutor.InvalidIdentity.selector);
        gate.execute(e, w, data, evidence);
        (e, data) = intent(1, destination, 100);
        e.codeHash = bytes32(uint256(99));
        vm.expectRevert(AssuredExecutor.InvalidIdentity.selector);
        gate.execute(e, w, data, evidence);
        (e, data) = intent(1, destination, 100);
        e.profile = bytes32(uint256(99));
        vm.expectRevert(AssuredExecutor.InvalidIdentity.selector);
        gate.execute(e, w, data, evidence);
        (e, data) = intent(1, destination, 100);
        e.calldataHash = bytes32(uint256(99));
        vm.expectRevert(AssuredExecutor.InvalidIdentity.selector);
        gate.execute(e, w, data, evidence);
        (e, data) = intent(1, destination, 100);
        vm.prank(address(0xbad));
        vm.expectRevert(AssuredExecutor.InvalidIdentity.selector);
        gate.execute(e, w, data, evidence);
        vm.chainId(block.chainid + 1);
        vm.expectRevert(AssuredExecutor.InvalidIdentity.selector);
        gate.execute(e, w, data, evidence);
    }

    function testVerifierAndLiveRuntimeSubstitutionRejected() public {
        (O.Envelope memory e, bytes memory data) = intent(1, destination, 100);
        AssuredExecutor.Admission memory w = witness();
        bytes memory evidence = proof(policy, e, destination, w);
        bytes memory oldCode = address(policy).code;
        vm.etch(address(policy), hex"00");
        vm.expectRevert(AssuredExecutor.InvalidIdentity.selector);
        gate.execute(e, w, data, evidence);
        vm.etch(address(policy), oldCode);
        vm.etch(address(treasury), hex"00");
        vm.expectRevert(AssuredExecutor.InvalidIdentity.selector);
        gate.execute(e, w, data, evidence);
    }

    function testInvalidSignatureDestinationAmountValueExpiryAndProjectionRejected() public {
        (O.Envelope memory e, bytes memory data) = intent(1, destination, 100);
        AssuredExecutor.Admission memory w = witness();
        bytes memory evidence = proof(policy, e, destination, w);
        (e, data) = intent(1, address(0x456), 100);
        vm.expectRevert();
        gate.execute(e, w, data, evidence);
        (e, data) = intent(1, destination, 101);
        vm.expectRevert();
        gate.execute(e, w, data, evidence);
        (e, data) = intent(1, destination, 100);
        e.value = 1;
        vm.expectRevert();
        gate.execute{value: 1}(e, w, data, evidence);
        e.value = 0;
        w.projectionDefinitionRoot = bytes32(uint256(99));
        vm.expectRevert(AssuredExecutor.StaleObservation.selector);
        gate.execute(e, w, data, evidence);
        w = witness();
        vm.roll(block.number + 101);
        vm.expectRevert();
        gate.execute(e, w, data, evidence);
    }

    function testCanonicalGoldenVectorsAndUint256Maximum() public {
        string memory fixture = vm.readFile(string.concat(vm.projectRoot(), "/fixtures/canonical-golden.json"));
        O.Envelope memory e = O.Envelope(
            bytes32(uint256(0x1111111111111111111111111111111111111111111111111111111111111111)),
            bytes32(uint256(0x2222222222222222222222222222222222222222222222222222222222222222)),
            bytes32(uint256(0x3333333333333333333333333333333333333333333333333333333333333333)),
            bytes32(uint256(0x4444444444444444444444444444444444444444444444444444444444444444)),
            address(0x1001),
            bytes32(uint256(0x5555555555555555555555555555555555555555555555555555555555555555)),
            bytes32(uint256(0x6666666666666666666666666666666666666666666666666666666666666666)),
            bytes4(0x12345678),
            bytes32(uint256(0x7777777777777777777777777777777777777777777777777777777777777777)),
            0,
            address(0x3003),
            bytes32(uint256(0x8888888888888888888888888888888888888888888888888888888888888888)),
            123
        );
        require(O.envelopeRoot(e) == vm.parseJsonBytes32(fixture, ".envelope"), "envelope golden");
        require(
            O.registrationRoot(
                e,
                0,
                bytes32(0),
                bytes32(uint256(0x9999999999999999999999999999999999999999999999999999999999999999)),
                "treasury-withdrawal-policy.prototype.v1",
                bytes32(uint256(0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa)),
                false
            ) == vm.parseJsonBytes32(fixture, ".registration"),
            "registration golden"
        );
        require(
            keccak256(C.uintValue(type(uint256).max)) == keccak256(vm.parseJsonBytes(fixture, ".uint256MaximumBytes")),
            "uint256 golden"
        );
        TreasuryPolicy goldenPolicy = new TreasuryPolicy(address(0x3003), 100, 10);
        require(goldenPolicy.policyRoot() == vm.parseJsonBytes32(fixture, ".policy"), "policy golden");
        // Same existing result root convention, no synthetic universal observation.
        string[] memory k = new string[](3);
        bytes[] memory v = new bytes[](3);
        k[0] = "target/address";
        v[0] = C.addressValue(address(0x1001));
        k[1] = "balance";
        v[1] = C.uintValue(1000);
        k[2] = "revision";
        v[2] = C.uintValue(1);
        require(
            C.root("prf.registry-chain-observation-result.v1", C.map(k, v)) == vm.parseJsonBytes32(fixture, ".result"),
            "observation result golden"
        );
    }
}
