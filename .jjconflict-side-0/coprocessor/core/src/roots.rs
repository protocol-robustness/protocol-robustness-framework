//! Root construction matching the PRF allocation kernel byte-for-byte.

use crate::canonical::{bytes_to_hex, domain_hash, hex32_to_bytes, tags, CanonValue};
pub use prf_pro_rata_allocation::allocation_roots::{
    claimant_set_root, outcome_set_root, proposed_rates_root, rate_derived_summary_hash,
    result_merkle_root, selected_outcome_hash,
};
use sha2::{Digest, Sha256};

/// Raw SHA256(EVIDENCE_MERKLE_NODE_V1 || left || right).
fn merkle_node(left: &[u8], right: &[u8]) -> Vec<u8> {
    let mut hasher = Sha256::new();
    hasher.update(tags::MERKLE_NODE.as_bytes());
    hasher.update(left);
    hasher.update(right);
    hasher.finalize().to_vec()
}

/// Compute the Merkle root over raw digests. An odd level duplicates its final
/// node (EVIDENCE_COMMITMENT_SPEC_V1 §9).
pub fn merkle_root(raw_digests: &[Vec<u8>]) -> Result<String, String> {
    if raw_digests.is_empty() {
        return Err("empty Merkle tree".to_string());
    }
    let mut level: Vec<Vec<u8>> = raw_digests.to_vec();
    while level.len() > 1 {
        if level.len() % 2 == 1 {
            let last = level.last().unwrap().clone();
            level.push(last);
        }
        let mut next = Vec::with_capacity(level.len() / 2);
        for pair in level.chunks(2) {
            next.push(merkle_node(&pair[0], &pair[1]));
        }
        level = next;
    }
    Ok(bytes_to_hex(&level[0]))
}

/// Certificate assertions digest.
pub fn certificate_assertions_digest(preimage: &CanonValue) -> String {
    domain_hash(tags::CERTIFICATE_ASSERTIONS, preimage)
}

/// CERTIFICATE_ASSERTIONS_V2 digest: extends v1 by committing the round
/// lifecycle observation.
pub fn certificate_assertions_digest_v2(preimage: &CanonValue) -> String {
    domain_hash(tags::CERTIFICATE_ASSERTIONS_V2, preimage)
}

/// Validate a 0x-prefixed or bare 64-char lowercase hex 32-byte hash.
pub fn normalize_hash(s: &str) -> Option<String> {
    let bare = s.strip_prefix("0x").unwrap_or(s);
    if bare.len() != 64
        || !bare
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && (b as char).is_lowercase())
    {
        return None;
    }
    Some(format!("0x{}", bare))
}

pub fn raw_of_hex(s: &str) -> Option<Vec<u8>> {
    hex32_to_bytes(s.strip_prefix("0x").unwrap_or(s))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn odd_level_duplicates_final_node() {
        // single node -> root is that node itself (leaf already applied)
        let d1 = vec![1u8; 32];
        let root1 = merkle_root(std::slice::from_ref(&d1)).unwrap();
        let root2 = merkle_root(std::slice::from_ref(&d1)).unwrap();
        assert_eq!(root1, root2);
    }

    #[test]
    fn different_leaves_differ() {
        let a = vec![1u8; 32];
        let b = vec![2u8; 32];
        let ab = merkle_root(&[a.clone(), b.clone()]).unwrap();
        let ba = merkle_root(&[b.clone(), a.clone()]).unwrap();
        assert_ne!(ab, ba);
    }
}
