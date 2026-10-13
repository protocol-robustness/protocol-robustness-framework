use sha2::{Digest, Sha256};

pub fn hex_bytes(s: &str) -> Vec<u8> {
    hex::decode(s).expect("valid hex")
}
pub fn sha256_hex(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

fn varuint(mut n: u64) -> Vec<u8> {
    let mut out = Vec::new();
    loop {
        let mut b = (n & 0x7f) as u8;
        n >>= 7;
        if n != 0 {
            b |= 0x80;
        }
        out.push(b);
        if n == 0 {
            return out;
        }
    }
}
fn string_bytes(tag: u8, value: &str) -> Vec<u8> {
    let b = value.as_bytes();
    let mut out = vec![tag];
    out.extend(varuint(b.len() as u64));
    out.extend(b);
    out
}
fn keyword(value: &str) -> Vec<u8> {
    string_bytes(0x22, value)
}
fn string(value: &str) -> Vec<u8> {
    string_bytes(0x20, value)
}

/// Canonical encoding for the frozen subject's semantic map. The key order is
/// the order committed by the Clojure vector and is part of this V1 fixture.
pub fn cancellation_subject_value_bytes() -> Vec<u8> {
    let entries = [
        ("schema-version", "cancellation-transition-subject.v1"),
        (
            "prior-state/root",
            "sha256:0000000000000000000000000000000000000000000000000000000000000001",
        ),
        (
            "authorised-command/root",
            "sha256:0000000000000000000000000000000000000000000000000000000000000002",
        ),
        (
            "transition-definition/root",
            "sha256:0000000000000000000000000000000000000000000000000000000000000003",
        ),
    ];
    let mut out = vec![0x31];
    out.extend(varuint(entries.len() as u64));
    for (key, value) in entries {
        out.extend(keyword(key));
        out.extend(string(value));
    }
    out
}

pub fn domain_preimage(domain: &str, value: &[u8]) -> Vec<u8> {
    let mut out = domain.as_bytes().to_vec();
    out.extend(value);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    const VALUE_HEX: &str = "3104220e736368656d612d76657273696f6e202263616e63656c6c6174696f6e2d7472616e736974696f6e2d7375626a6563742e763122107072696f722d73746174652f726f6f7420477368613235363a303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030312217617574686f72697365642d636f6d6d616e642f726f6f7420477368613235363a30303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303032221a7472616e736974696f6e2d646566696e6974696f6e2f726f6f7420477368613235363a30303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303033";
    const ROOT: &str = "51fc7e3b4e7418dd59385119ed33d53440ebfe7a9135919e043bc39a4b041d9f";
    #[test]
    fn semantic_derivation_matches_frozen_subject() {
        let value = cancellation_subject_value_bytes();
        assert_eq!(
            hex::encode(&value),
            VALUE_HEX,
            "canonical serialization divergence"
        );
        let framed = domain_preimage("cancellation-transition-subject.v1", &value);
        let digest = sha256_hex(&framed);
        assert_eq!(&digest, ROOT, "domain framing or hashing divergence");
    }
    #[test]
    fn low_level_hash_matches_frozen_subject() {
        let framed = hex_bytes(&format!(
            "{}{}",
            "63616e63656c6c6174696f6e2d7472616e736974696f6e2d7375626a6563742e7631", VALUE_HEX
        ));
        assert_eq!(sha256_hex(&framed), ROOT);
    }
}
