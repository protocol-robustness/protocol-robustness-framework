//! Deterministic outcome selection under `domain-hash-rejection-v1`.
//!
//! The selection contract belongs to pro-rata allocation. It uses the shared
//! canonical codec; the existing selected-outcome domain is intentionally
//! preserved.

use num_bigint::BigInt;
use num_traits::{One, Zero};
use prf_canonical_codec::{digest_to_bigint, domain_hash_raw, CanonValue};

const SELECTED_OUTCOME_DOMAIN: &str = "SELECTED_OUTCOME_V1";

/// Selection receipt matching the existing PRF structure.
#[derive(Clone, Debug)]
pub struct SelectionReceipt {
    pub algorithm: String,
    pub outcome_count: BigInt,
    pub accepted_counter: BigInt,
    pub candidate_digest: String,
    pub selected_index: BigInt,
    pub selected_outcome_id: String,
    pub selected_outcome_hash: String,
}

/// Intermediate selection result before outcome id/hash are attached.
#[derive(Clone, Debug)]
pub struct SelectionIndex {
    pub accepted_counter: BigInt,
    pub candidate_digest: String,
    pub candidate_value: BigInt,
    pub selected_index: BigInt,
}

fn two_to_256() -> BigInt {
    BigInt::from(1) << 256
}

/// Candidate digest for a counter, using the frozen selected-outcome domain.
pub fn candidate_digest_hex(randomness: &[u8], counter: &BigInt, outcome_count: &BigInt) -> String {
    let preimage = CanonValue::map(vec![
        (
            CanonValue::keyword("authoritative-randomness"),
            CanonValue::array(
                randomness
                    .iter()
                    .map(|byte| CanonValue::int(BigInt::from(*byte)))
                    .collect(),
            ),
        ),
        (
            CanonValue::keyword("counter"),
            CanonValue::int(counter.clone()),
        ),
        (
            CanonValue::keyword("outcome-count"),
            CanonValue::int(outcome_count.clone()),
        ),
    ]);
    hex::encode(domain_hash_raw(SELECTED_OUTCOME_DOMAIN, &preimage))
}

/// M - (M mod n), where M = 2^256.
pub fn rejection_limit(outcome_count: &BigInt) -> BigInt {
    let m = two_to_256();
    let remainder = &m % outcome_count;
    m - remainder
}

/// Run rejection sampling; return the accepted counter, candidate digest,
/// candidate value, and selected index.
pub fn select_index(randomness: &[u8], outcome_count: usize) -> Result<SelectionIndex, String> {
    if outcome_count == 0 {
        return Err("empty outcome set".to_string());
    }
    let count = BigInt::from(outcome_count);
    let mut counter = BigInt::zero();
    loop {
        let digest = candidate_digest_hex(randomness, &counter, &count);
        let raw = hex::decode(&digest).map_err(|_| "invalid candidate digest".to_string())?;
        let value = digest_to_bigint(&raw);
        let limit = rejection_limit(&count);
        if value < limit {
            let selected_index = &value % &count;
            return Ok(SelectionIndex {
                accepted_counter: counter,
                candidate_digest: digest,
                candidate_value: value,
                selected_index,
            });
        }
        counter += BigInt::one();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn index_in_range() {
        for count in 1..20usize {
            let selected = select_index(&[7u8; 32], count).unwrap();
            assert!(selected.selected_index >= BigInt::zero());
            assert!(selected.selected_index < BigInt::from(count));
        }
    }

    #[test]
    fn empty_outcome_set_rejected() {
        assert!(select_index(&[0u8; 32], 0).is_err());
    }
}
