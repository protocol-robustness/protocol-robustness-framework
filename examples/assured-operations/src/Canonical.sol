// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

/// @dev Narrow mirror of canonical-hash-scheme.v1, checked against the PRF encoder.
/// No ABI/keccak replacement for canonical artifact roots. Already encoded values
/// are internal inputs only; this is not an arbitrary-byte canonical validator.
library Canonical {
    function varuint(uint256 n) internal pure returns (bytes memory b) {
        do {
            uint8 next = uint8(n & 127);
            n >>= 7;
            b = bytes.concat(b, bytes1(next | (n == 0 ? 0 : 128)));
        } while (n != 0);
    }

    function uintValue(uint256 n) internal pure returns (bytes memory) {
        // ZigZag(n) = 2*n may need 257 bits. Emit its first seven bits first.
        bytes memory b = bytes.concat(hex"10", bytes1(uint8((n & 63) << 1) | (n >> 6 == 0 ? 0 : 128)));
        n >>= 6;
        if (n != 0) b = bytes.concat(b, varuint(n));
        return b;
    }

    function text(string memory s) internal pure returns (bytes memory) {
        return bytes.concat(hex"20", varuint(bytes(s).length), bytes(s));
    }

    function keyword(string memory s) internal pure returns (bytes memory) {
        return bytes.concat(hex"22", varuint(bytes(s).length), bytes(s));
    }

    function nil() internal pure returns (bytes memory) {
        return hex"00";
    }

    function less(bytes memory a, bytes memory b) private pure returns (bool) {
        uint256 count = a.length < b.length ? a.length : b.length;
        for (uint256 i; i < count; i++) {
            if (a[i] != b[i]) return uint8(a[i]) < uint8(b[i]);
        }
        return a.length < b.length;
    }

    function map(string[] memory names, bytes[] memory values) internal pure returns (bytes memory b) {
        require(names.length == values.length, "map lengths");
        bytes[] memory keys = new bytes[](names.length);
        for (uint256 i; i < names.length; i++) {
            keys[i] = keyword(names[i]);
        }
        for (uint256 i = 1; i < keys.length; i++) {
            uint256 j = i;
            while (j > 0 && less(keys[j], keys[j - 1])) {
                (keys[j], keys[j - 1]) = (keys[j - 1], keys[j]);
                (values[j], values[j - 1]) = (values[j - 1], values[j]);
                j--;
            }
        }
        b = bytes.concat(hex"31", varuint(keys.length));
        for (uint256 i; i < keys.length; i++) {
            if (i > 0) require(keccak256(keys[i]) != keccak256(keys[i - 1]), "duplicate key");
            b = bytes.concat(b, keys[i], values[i]);
        }
    }

    function vector(bytes[] memory values) internal pure returns (bytes memory b) {
        b = bytes.concat(hex"30", varuint(values.length));
        for (uint256 i; i < values.length; i++) {
            b = bytes.concat(b, values[i]);
        }
    }

    function hexText(bytes memory data) internal pure returns (string memory) {
        bytes memory alphabet = "0123456789abcdef";
        bytes memory output = new bytes(data.length * 2);
        for (uint256 i; i < data.length; i++) {
            output[2 * i] = alphabet[uint8(data[i]) >> 4];
            output[2 * i + 1] = alphabet[uint8(data[i]) & 15];
        }
        return string(output);
    }

    function addressValue(address a) internal pure returns (bytes memory) {
        require(a != address(0), "zero address");
        return text(string.concat("0x", hexText(abi.encodePacked(a))));
    }

    function hashValue(bytes32 h, bool code) internal pure returns (bytes memory) {
        return text(string.concat(code ? "keccak256:" : "sha256:", hexText(abi.encodePacked(h))));
    }

    function ref(string memory kind, bytes32 h, bool keywordType) internal pure returns (bytes memory) {
        string[] memory k = new string[](2);
        bytes[] memory v = new bytes[](2);
        k[0] = "artifact/type";
        v[0] = keywordType ? keyword(kind) : text(kind);
        k[1] = "artifact/root";
        v[1] = hashValue(h, false);
        return map(k, v);
    }

    function root(string memory domain, bytes memory body) internal pure returns (bytes32) {
        return sha256(bytes.concat(bytes(domain), body));
    }
}
