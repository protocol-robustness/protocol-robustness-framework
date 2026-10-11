//! Standalone PRF canonical value encoding and domain hashing.
//!
//! This crate owns the generic canonical value codec. Artifact-specific domain
//! tags and semantic preimages remain with their owning modules.

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

#[cfg(test)]
mod tests {
    use super::*;

    fn conformance_vectors() -> std::collections::HashMap<&'static str, Vec<&'static str>> {
        include_str!("../../clj/test/resources/canonical-v1-vectors.tsv")
            .lines()
            .filter(|line| !line.starts_with('#') && !line.is_empty())
            .map(|line| {
                let fields: Vec<_> = line.split('\t').collect();
                (fields[0], fields[1..].to_vec())
            })
            .collect()
    }

    #[test]
    fn canonical_v1_shared_clojure_vectors() {
        let vectors = [
            ("null", CanonValue::Null),
            ("false", CanonValue::Bool(false)),
            ("true", CanonValue::Bool(true)),
            ("negative-one", CanonValue::int(-1)),
            ("utf8-e-acute", CanonValue::str("é")),
            ("keyword-x", CanonValue::keyword("x")),
            (
                "array-int-one-string-x",
                CanonValue::array(vec![CanonValue::int(1), CanonValue::str("x")]),
            ),
            (
                "sorted-keyword-map",
                CanonValue::map(vec![
                    (CanonValue::keyword("b"), CanonValue::Bool(false)),
                    (CanonValue::keyword("a"), CanonValue::int(1)),
                ]),
            ),
        ];
        let fixtures = conformance_vectors();
        for (id, value) in vectors {
            assert_eq!(bytes_to_hex(&value.encode()), fixtures[id][0]);
        }
    }

    #[test]
    fn canonical_v1_domain_hash_matches_clojure_vector() {
        let value = CanonValue::map(vec![
            (CanonValue::keyword("a"), CanonValue::int(1)),
            (CanonValue::keyword("b"), CanonValue::Bool(false)),
        ]);
        let fixture = &conformance_vectors()["sorted-keyword-map"];
        assert_eq!(domain_hash(fixture[1], &value), fixture[2]);
    }
}
