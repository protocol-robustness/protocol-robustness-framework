//! Canonical binary encoding port of CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI.
//!
//! This is a byte-for-byte port of the PRF `resolver-sim.hash.canonical`
//! encoding for the value subset used by the allocation kernel. It must match
//! the Clojure implementation exactly; no Rust-specific encoding is used.
//!
//! Type tags:
//!   NULL         0x00
//!   BOOL_FALSE   0x01
//!   BOOL_TRUE    0x02
//!   INT          0x10  (zigzag varuint)
//!   STRING       0x20  (length-prefixed UTF-8)
//!   KEYWORD      0x22  (length-prefixed "ns/name" or "name")
//!   ARRAY        0x30  (length-prefixed concatenation)
//!   MAP          0x31  (length-prefixed key/value pairs sorted by key bytes)
//!
//! varuint is LEB128 little-endian base-128, minimal representation.
//! Integers are encoded via the zigzag transform: n >= 0 -> 2n, n < 0 -> -2n-1.

use num_bigint::BigInt;
use num_traits::{One, Signed, Zero};
use sha2::{Digest, Sha256};

/// A canonical value tree matching the PRF canonical encoding.
#[derive(Clone, Debug, PartialEq)]
pub enum CanonValue {
    Null,
    Bool(bool),
    Int(BigInt),
    Str(String),
    /// A keyword; the string is already in "ns/name" or "name" form.
    Keyword(String),
    Array(Vec<CanonValue>),
    /// Pairs are kept in insertion order by callers that construct them;
    /// `encode` sorts them by encoded key bytes as required by the ABI.
    Map(Vec<(CanonValue, CanonValue)>),
}

impl CanonValue {
    pub fn null() -> Self {
        CanonValue::Null
    }
    pub fn bool(b: bool) -> Self {
        CanonValue::Bool(b)
    }
    pub fn int(n: impl Into<BigInt>) -> Self {
        CanonValue::Int(n.into())
    }
    pub fn str(s: impl Into<String>) -> Self {
        CanonValue::Str(s.into())
    }
    pub fn keyword(s: impl Into<String>) -> Self {
        CanonValue::Keyword(s.into())
    }
    pub fn array(items: Vec<CanonValue>) -> Self {
        CanonValue::Array(items)
    }
    pub fn map(pairs: Vec<(CanonValue, CanonValue)>) -> Self {
        CanonValue::Map(pairs)
    }
}

/// Encode a non-negative integer as LEB128 varuint (minimal representation).
pub fn encode_varuint(mut n: BigInt) -> Vec<u8> {
    let mut out = Vec::new();
    let seven: BigInt = BigInt::from(0x7f);
    loop {
        // low byte of n & 0x7f
        let b = (&n & &seven).to_bytes_le().1;
        let byte = b[0];
        n >>= 7;
        if n.is_zero() {
            out.push(byte);
            return out;
        } else {
            out.push(byte | 0x80);
        }
    }
}

/// ZigZag transform: n >= 0 -> 2n, n < 0 -> -2n-1.
fn zigzag(n: &BigInt) -> BigInt {
    if n.is_negative() {
        (-BigInt::from(2) * n) - BigInt::one()
    } else {
        BigInt::from(2) * n
    }
}

impl CanonValue {
    /// Encode this value to canonical bytes.
    pub fn encode(&self) -> Vec<u8> {
        match self {
            CanonValue::Null => vec![0x00],
            CanonValue::Bool(false) => vec![0x01],
            CanonValue::Bool(true) => vec![0x02],
            CanonValue::Int(n) => {
                let mut out = vec![0x10];
                out.extend(encode_varuint(zigzag(n)));
                out
            }
            CanonValue::Str(s) => {
                let bytes = s.as_bytes();
                let mut out = vec![0x20];
                out.extend(encode_varuint(BigInt::from(bytes.len())));
                out.extend(bytes);
                out
            }
            CanonValue::Keyword(s) => {
                let bytes = s.as_bytes();
                let mut out = vec![0x22];
                out.extend(encode_varuint(BigInt::from(bytes.len())));
                out.extend(bytes);
                out
            }
            CanonValue::Array(items) => {
                let mut out = vec![0x30];
                out.extend(encode_varuint(BigInt::from(items.len())));
                for item in items {
                    out.extend(item.encode());
                }
                out
            }
            CanonValue::Map(pairs) => {
                // Sort pairs by encoded key bytes (unsigned lexicographic).
                let mut encoded: Vec<(Vec<u8>, Vec<u8>)> = pairs
                    .iter()
                    .map(|(k, v)| (k.encode(), v.encode()))
                    .collect();
                encoded.sort_by(|a, b| a.0.cmp(&b.0));
                let mut out = vec![0x31];
                out.extend(encode_varuint(BigInt::from(encoded.len())));
                for (k, v) in encoded {
                    out.extend(k);
                    out.extend(v);
                }
                out
            }
        }
    }

    /// Raw SHA-256 digest of the canonical bytes.
    pub fn sha256(&self) -> Vec<u8> {
        let mut hasher = Sha256::new();
        hasher.update(self.encode());
        hasher.finalize().to_vec()
    }
}

/// Compute SHA256(domain_tag_utf8 || canonical_bytes(value)).
pub fn domain_hash(domain_tag: &str, value: &CanonValue) -> String {
    let mut hasher = Sha256::new();
    hasher.update(domain_tag.as_bytes());
    hasher.update(value.encode());
    hex::encode(hasher.finalize())
}

/// Raw SHA256(domain_tag_utf8 || canonical_bytes(value)).
pub fn domain_hash_raw(domain_tag: &str, value: &CanonValue) -> Vec<u8> {
    let mut hasher = Sha256::new();
    hasher.update(domain_tag.as_bytes());
    hasher.update(value.encode());
    hasher.finalize().to_vec()
}

/// Interpret a raw 32-byte digest as an unsigned big-endian integer.
pub fn digest_to_bigint(digest: &[u8]) -> BigInt {
    BigInt::from_bytes_be(num_bigint::Sign::Plus, digest)
}

/// Compute a hex string from raw bytes.
pub fn bytes_to_hex(bytes: &[u8]) -> String {
    hex::encode(bytes)
}

pub mod tags {
    //! Domain tag strings (must match PRF `domain-tags` map).
    pub const ALLOCATION_CONTEXT: &str = "ALLOCATION_CONTEXT_V1";
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
