// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;
import {Canonical as C} from "./Canonical.sol";

/// @dev Exact closed subset of the architecture grammar: one direct target,
/// selector route, principal caller, no validity bounds or cross-domain context.
library OperationCodec {
    struct Envelope {
        bytes32 chainInstance;
        bytes32 release;
        bytes32 candidate;
        bytes32 scope;
        address target;
        bytes32 codeHash;
        bytes32 profile;
        bytes4 selector;
        bytes32 calldataHash;
        uint256 value;
        address principal;
        bytes32 replayNamespace;
        uint256 nonce;
    }

    function operation(bytes4 selector) internal pure returns (bytes memory) {
        string[] memory k = new string[](3);
        bytes[] memory v = new bytes[](3);
        k[0] = "route";
        v[0] = C.keyword("selector");
        k[1] = "selector";
        v[1] = C.text(string.concat("0x", C.hexText(abi.encodePacked(selector))));
        k[2] = "definition-ref";
        v[2] = C.nil();
        return C.map(k, v);
    }

    function envelopeRoot(Envelope memory e) internal pure returns (bytes32) {
        string[] memory k = new string[](13);
        bytes[] memory v = new bytes[](13);
        k[0] = "artifact/schema";
        v[0] = C.text("AssuredOperationEnvelope.v1");
        k[1] = "chain-instance/root";
        v[1] = C.hashValue(e.chainInstance, false);
        k[2] = "deployment";
        {
            string[] memory dk = new string[](4);
            bytes[] memory dv = new bytes[](4);
            dk[0] = "release-ref";
            dv[0] = C.ref("prf/protocol-registry-release.v1", e.release, true);
            dk[1] = "candidate-ref";
            dv[1] = C.ref("prf/registry-binding-candidate.v1", e.candidate, true);
            dk[2] = "binding-id";
            dv[2] = C.keyword("binding/target");
            dk[3] = "scope-root";
            dv[3] = C.hashValue(e.scope, false);
            v[2] = C.map(dk, dv);
        }
        k[3] = "target/address";
        v[3] = C.addressValue(e.target);
        k[4] = "target/code-bindings";
        {
            string[] memory ck = new string[](4);
            bytes[] memory cv = new bytes[](4);
            ck[0] = "role";
            cv[0] = C.keyword("runtime/target");
            ck[1] = "address";
            cv[1] = C.addressValue(e.target);
            ck[2] = "runtime-code-hash";
            cv[2] = C.hashValue(e.codeHash, true);
            ck[3] = "deployment-ref";
            cv[3] = C.ref("prf/registry-binding-candidate.v1", e.candidate, true);
            bytes[] memory items = new bytes[](1);
            items[0] = C.map(ck, cv);
            v[4] = C.vector(items);
        }
        k[5] = "execution/profile-ref";
        v[5] = C.ref("ao-dispatcher-profile.prototype.v1", e.profile, false);
        k[6] = "operation";
        v[6] = operation(e.selector);
        k[7] = "calldata/hash";
        v[7] = C.hashValue(e.calldataHash, false);
        k[8] = "native/value";
        v[8] = C.uintValue(e.value);
        k[9] = "caller";
        {
            string[] memory ck = new string[](3);
            bytes[] memory cv = new bytes[](3);
            ck[0] = "mode";
            cv[0] = C.keyword("principal");
            ck[1] = "principal";
            cv[1] = C.addressValue(e.principal);
            ck[2] = "authority-ref";
            cv[2] = C.nil();
            v[9] = C.map(ck, cv);
        }
        k[10] = "replay";
        {
            string[] memory rk = new string[](2);
            bytes[] memory rv = new bytes[](2);
            rk[0] = "namespace-ref";
            rv[0] = C.ref("ao-replay-namespace.prototype.v1", e.replayNamespace, false);
            rk[1] = "nonce";
            rv[1] = C.uintValue(e.nonce);
            v[10] = C.map(rk, rv);
        }
        k[11] = "validity";
        {
            string[] memory vk = new string[](2);
            bytes[] memory vv = new bytes[](2);
            vk[0] = "not-before-block";
            vv[0] = C.nil();
            vk[1] = "not-after-block";
            vv[1] = C.nil();
            v[11] = C.map(vk, vv);
        }
        k[12] = "domain-context-ref";
        v[12] = C.nil();
        return C.root("prf.assured-operation-envelope.v1", C.map(k, v));
    }

    function registrationRoot(
        Envelope memory e,
        uint256 revision,
        bytes32 parent,
        bytes32 policy,
        string memory policyType,
        bytes32 control,
        bool disabled
    ) internal pure returns (bytes32) {
        string[] memory k = new string[](11);
        bytes[] memory v = new bytes[](11);
        k[0] = "artifact/schema";
        v[0] = C.text("ProtectedOperationRegistration.v1");
        k[1] = "chain-instance/root";
        v[1] = C.hashValue(e.chainInstance, false);
        k[2] = "deployment-ref";
        v[2] = C.ref("prf/registry-binding-candidate.v1", e.candidate, true);
        k[3] = "target/address";
        v[3] = C.addressValue(e.target);
        k[4] = "execution/profile-ref";
        v[4] = C.ref("ao-dispatcher-profile.prototype.v1", e.profile, false);
        k[5] = "operation";
        v[5] = operation(e.selector);
        k[6] = "revision";
        v[6] = C.uintValue(revision);
        k[7] = "parent-ref";
        v[7] = revision == 0 ? C.nil() : C.ref("ProtectedOperationRegistration.v1", parent, false);
        k[8] = "status";
        v[8] = C.keyword(disabled ? "disabled" : "protected");
        k[9] = "assurance-policy-ref";
        v[9] = C.ref(policyType, policy, false);
        k[10] = "control-ref";
        v[10] = C.ref("ao-registration-control.prototype.v1", control, false);
        return C.root("prf.protected-operation-registration.v1", C.map(k, v));
    }
}
