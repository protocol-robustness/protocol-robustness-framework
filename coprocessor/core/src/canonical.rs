//! Allocation-kernel compatibility facade for the standalone PRF canonical codec.
//!
//! Generic canonical value encoding and SHA-256 domain hashing live in
//! `prf-canonical-codec`. Allocation-specific domain tags and helpers remain
//! here so callers keep their existing `crate::canonical` paths.

pub use prf_canonical_codec::{
    bytes_to_hex, digest_to_bigint, domain_hash, domain_hash_raw, encode_varuint, CanonValue,
};

pub mod tags {
    //! Domain tag strings (must match PRF `domain-tags` map).
    pub use prf_pro_rata_allocation::allocation_evaluation::ALLOCATION_CONTEXT_DOMAIN as ALLOCATION_CONTEXT;
    pub const CLAIMANT_SET: &str = "CLAIMANT_SET_V1";
    pub const OUTCOME_SET: &str = "OUTCOME_SET_V1";
    pub const PROPOSED_RATES: &str = "PROPOSED_RATES_V1";
    pub const RATE_DERIVED_SUMMARY: &str = "RATE_DERIVED_SUMMARY_V1";
    pub const SELECTED_OUTCOME: &str = "SELECTED_OUTCOME_V1";
    pub const RESULT_ROOT: &str = "RESULT_ROOT_V1";
    pub const CERTIFICATE_ASSERTIONS: &str = "CERTIFICATE_ASSERTIONS_V1";
    pub const CERTIFICATE_ASSERTIONS_V2: &str = "CERTIFICATE_ASSERTIONS_V2";
    pub const MERKLE_LEAF: &str = "EVIDENCE_MERKLE_LEAF_V1";
    pub const MERKLE_NODE: &str = "EVIDENCE_MERKLE_NODE_V1";
}

/// Interpret a 64-char lowercase hex string as a 32-byte raw digest.
pub fn hex32_to_bytes(s: &str) -> Option<Vec<u8>> {
    if s.len() != 64 {
        return None;
    }
    if !s
        .bytes()
        .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
    {
        return None;
    }
    hex::decode(s).ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use num_bigint::BigInt;

    #[test]
    fn varuint_minimal() {
        assert_eq!(encode_varuint(BigInt::from(0)), vec![0x00]);
        assert_eq!(encode_varuint(BigInt::from(1)), vec![0x01]);
        assert_eq!(encode_varuint(BigInt::from(127)), vec![0x7f]);
        assert_eq!(encode_varuint(BigInt::from(128)), vec![0x80, 0x01]);
    }

    #[test]
    fn string_encoding() {
        // "hi" -> 0x20 0x02 0x68 0x69
        assert_eq!(CanonValue::str("hi").encode(), vec![0x20, 0x02, 0x68, 0x69]);
    }

    #[test]
    fn int_zigzag() {
        // -5 -> 0x10 zigzag(-5)=9 -> 0x10 0x09
        assert_eq!(CanonValue::int(BigInt::from(-5)).encode(), vec![0x10, 0x09]);
        // 1 -> 0x10 0x02
        assert_eq!(CanonValue::int(BigInt::from(1)).encode(), vec![0x10, 0x02]);
    }

    #[test]
    fn map_sorts_keys() {
        let m = CanonValue::map(vec![
            (CanonValue::str("b"), CanonValue::int(BigInt::from(2))),
            (CanonValue::str("a"), CanonValue::int(BigInt::from(1))),
        ]);
        // key "a" encodes 0x20 0x01 0x61; key "b" 0x20 0x01 0x62
        let expected = vec![
            0x31, 0x02, 0x20, 0x01, 0x61, 0x10, 0x02, 0x20, 0x01, 0x62, 0x10, 0x04,
        ];
        assert_eq!(m.encode(), expected);
    }

    #[test]
    fn keyword_encoding() {
        assert_eq!(
            CanonValue::keyword("active").encode(),
            vec![0x22, 0x06, b'a', b'c', b't', b'i', b'v', b'e']
        );
        assert_eq!(
            CanonValue::keyword("claim/id").encode(),
            vec![0x22, 0x08, b'c', b'l', b'a', b'i', b'm', b'/', b'i', b'd']
        );
    }
}
