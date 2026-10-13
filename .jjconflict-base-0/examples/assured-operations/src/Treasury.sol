// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;
import {Canonical as C} from "./Canonical.sol";
import {OperationCodec as O} from "./OperationCodec.sol";
import {IAssurancePolicy} from "./AssuredExecutor.sol";

/// @notice Native-asset test treasury: one consequential route; deposits are public.
contract Treasury {
    address public immutable executor;
    uint256 public revision;
    bool private _entered;
    bytes32 public immutable projectionDefinitionRoot;
    event WithdrawalObserved(
        address indexed source,
        address indexed destination,
        uint256 amount,
        uint256 preRevision,
        uint256 postRevision,
        uint256 sourceBefore,
        uint256 sourceAfter,
        uint256 destinationBefore,
        uint256 destinationAfter
    );

    constructor(address gate) {
        require(gate != address(0), "zero executor");
        executor = gate;
        string[] memory k = new string[](3);
        bytes[] memory v = new bytes[](3);
        k[0] = "artifact/schema";
        v[0] = C.text("treasury-projection-definition.prototype.v1");
        k[1] = "target/address";
        v[1] = C.addressValue(address(this));
        k[2] = "fields";
        bytes[] memory fields = new bytes[](3);
        fields[0] = C.keyword("target/address");
        fields[1] = C.keyword("balance");
        fields[2] = C.keyword("revision");
        v[2] = C.vector(fields);
        projectionDefinitionRoot = C.root("ao.prototype.treasury-projection-definition.v1", C.map(k, v));
    }

    receive() external payable {
        require(!_entered, "deposit during withdrawal");
        revision++;
    }

    function currentResultRoot() external view returns (bytes32) {
        string[] memory k = new string[](3);
        bytes[] memory v = new bytes[](3);
        k[0] = "target/address";
        v[0] = C.addressValue(address(this));
        k[1] = "balance";
        v[1] = C.uintValue(address(this).balance);
        k[2] = "revision";
        v[2] = C.uintValue(revision);
        // Existing registry observation result-root convention; not a new
        // universal observation artifact. Frame context is composed externally.
        return C.root("prf.registry-chain-observation-result.v1", C.map(k, v));
    }

    function withdraw(address payable destination, uint256 amount) external {
        require(
            msg.sender == executor && !_entered && destination != address(0) && destination != address(this),
            "invalid withdrawal"
        );
        uint256 beforeSource = address(this).balance;
        uint256 beforeDestination = destination.balance;
        require(amount > 0 && amount <= beforeSource, "amount");
        uint256 beforeRevision = revision;
        _entered = true;
        revision++;
        (bool ok,) = destination.call{value: amount}("");
        require(ok, "destination reverted");
        // Narrow native asset profile: reject onward movement/incoming callbacks.
        require(
            address(this).balance == beforeSource - amount && destination.balance == beforeDestination + amount,
            "delta mismatch"
        );
        _entered = false;
        emit WithdrawalObserved(
            address(this),
            destination,
            amount,
            beforeRevision,
            revision,
            beforeSource,
            address(this).balance,
            beforeDestination,
            destination.balance
        );
    }
}

/// @notice Application-specific ECDSA-attested bounded permission. No SP1 proof,
/// solvency claim, or researcher authority is inferred from this test profile.
contract TreasuryPolicy is IAssurancePolicy {
    address public immutable signer;
    uint256 public immutable limit;
    uint256 public immutable keepBalance;

    constructor(address attestor, uint256 cap, uint256 floor) {
        require(attestor != address(0), "zero signer");
        signer = attestor;
        limit = cap;
        keepBalance = floor;
    }

    function artifactType() external pure returns (string memory) {
        return "treasury-withdrawal-policy.prototype.v1";
    }

    function policyRoot() public view returns (bytes32) {
        string[] memory k = new string[](4);
        bytes[] memory v = new bytes[](4);
        k[0] = "artifact/schema";
        v[0] = C.text("treasury-withdrawal-policy.prototype.v1");
        k[1] = "signer/address";
        v[1] = C.addressValue(signer);
        k[2] = "maximum-amount";
        v[2] = C.uintValue(limit);
        k[3] = "minimum-post-balance";
        v[3] = C.uintValue(keepBalance);
        return C.root("ao.prototype.treasury-policy.v1", C.map(k, v));
    }

    /// @dev Reusable predicate at one exact pre-state: nonce is intentionally
    /// absent. It binds instance, deployment, route, caller, destination and cap.
    function statementRoot(
        O.Envelope memory e,
        address destination,
        bytes32 projection,
        bytes32 result,
        uint256 deadline
    ) public view returns (bytes32) {
        string[] memory k = new string[](10);
        bytes[] memory v = new bytes[](10);
        k[0] = "artifact/schema";
        v[0] = C.text("treasury-withdrawal-permission.prototype.v1");
        k[1] = "policy-ref";
        v[1] = C.ref("treasury-withdrawal-policy.prototype.v1", policyRoot(), false);
        k[2] = "chain-instance/root";
        v[2] = C.hashValue(e.chainInstance, false);
        k[3] = "candidate-ref";
        v[3] = C.ref("prf/registry-binding-candidate.v1", e.candidate, true);
        k[4] = "target/address";
        v[4] = C.addressValue(e.target);
        k[5] = "operation";
        v[5] = O.operation(e.selector);
        k[6] = "principal";
        v[6] = C.addressValue(e.principal);
        k[7] = "destination";
        v[7] = C.addressValue(destination);
        k[8] = "state";
        string[] memory sk = new string[](2);
        bytes[] memory sv = new bytes[](2);
        sk[0] = "definition-root";
        sv[0] = C.hashValue(projection, false);
        sk[1] = "result-root";
        sv[1] = C.hashValue(result, false);
        v[8] = C.map(sk, sv);
        k[9] = "not-after-block";
        v[9] = C.uintValue(deadline);
        return C.root("ao.prototype.treasury-withdrawal-permission.v1", C.map(k, v));
    }

    function admit(
        O.Envelope calldata e,
        bytes calldata data,
        bytes32 projection,
        bytes32 result,
        bytes calldata evidence
    ) external view returns (bytes32) {
        // Exact ABI length rejects aliases/trailing bytes accepted by some decoders.
        require(data.length == 68 && e.value == 0, "call shape");
        (address destination, uint256 amount) = abi.decode(data[4:], (address, uint256));
        require(
            amount > 0 && amount <= limit && e.target.balance >= amount && e.target.balance - amount >= keepBalance,
            "policy amount"
        );
        require(evidence.length == 128, "evidence shape");
        (uint256 deadline, uint8 v, bytes32 r, bytes32 s) = abi.decode(evidence, (uint256, uint8, bytes32, bytes32));
        require(
            block.number <= deadline && (v == 27 || v == 28)
                && uint256(s) <= 0x7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0,
            "signature bounds"
        );
        bytes32 statement = statementRoot(e, destination, projection, result, deadline);
        require(ecrecover(statement, v, r, s) == signer, "invalid assurance");
        return statement;
    }
}
