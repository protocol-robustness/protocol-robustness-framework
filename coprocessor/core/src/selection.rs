//! Deterministic outcome selection under :domain-hash-rejection-v1, matching
//! the PRF selection namespace exactly.
//!
//! Algorithm:
//!   1. n = number of canonically ordered outcomes; require n > 0.
//!   2. For counter values beginning at zero, derive candidate-bytes =
//!      `domain-hash(:selected-outcome, {:authoritative-randomness <32 bytes>
//!      :counter <non-negative integer> :outcome-count n})`.
//!   3. Interpret the 32-byte digest as an unsigned big-endian integer.
//!   4. M = 2^256; limit = M - (M mod n).
//!   5. If candidate < limit, selected-index = candidate mod n.
//!   6. Otherwise increment counter and derive another candidate.
//!   7. Return the outcome at selected-index in canonical outcome order.

use crate::canonical::{digest_to_bigint, domain_hash_raw, tags, CanonValue};
use num_bigint::BigInt;
use num_traits::{One, Zero};

/// Selection receipt matching the PRF structure.
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

fn two_to_256() -> BigInt {
    BigInt::from(1) << 256
}

/// Candidate digest hex for a given counter (domain-separated).
pub fn candidate_digest_hex(randomness: &[u8], counter: &BigInt, outcome_count: &BigInt) -> String {
    let preimage = CanonValue::map(vec![
        (
            CanonValue::keyword("authoritative-randomness"),
            CanonValue::array(
                randomness
                    .iter()
                    .map(|b| CanonValue::int(BigInt::from(*b)))
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
    let raw = domain_hash_raw(tags::SELECTED_OUTCOME, &preimage);
    hex::encode(raw)
}

/// M - (M mod n) where M = 2^256.
pub fn rejection_limit(outcome_count: &BigInt) -> BigInt {
    let m = two_to_256();
    let rem = &m % outcome_count;
    m - rem
}
/// Run rejection sampling; returns the accepted counter, candidate digest hex,
/// candidate value, and selected index.
pub fn select_index(randomness: &[u8], outcome_count: usize) -> Result<SelectionIndex, String> {
    if outcome_count == 0 {
        return Err("empty outcome set".to_string());
    }
    let n = BigInt::from(outcome_count);
    let mut counter = BigInt::zero();
    loop {
        let digest_hex = candidate_digest_hex(randomness, &counter, &n);
        let raw = crate::canonical::hex32_to_bytes(&digest_hex)
            .ok_or_else(|| "invalid candidate digest".to_string())?;
        let value = digest_to_bigint(&raw);
        let limit = rejection_limit(&n);
        if value < limit {
            let selected_index = &value % &n;
            return Ok(SelectionIndex {
                accepted_counter: counter,
                candidate_digest: digest_hex,
                candidate_value: value,
                selected_index,
            });
        }
        counter += BigInt::one();
    }
}

/// Intermediate selection result before outcome id/hash are attached.
#[derive(Clone, Debug)]
pub struct SelectionIndex {
    pub accepted_counter: BigInt,
    pub candidate_digest: String,
    pub candidate_value: BigInt,
    pub selected_index: BigInt,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn index_in_range() {
        for n in 1..20usize {
            let sel = select_index(&[7u8; 32], n).unwrap();
            assert!(sel.selected_index >= BigInt::zero());
            assert!(sel.selected_index < BigInt::from(n));
        }
    }

    #[test]
    fn empty_outcome_set_rejected() {
        assert!(select_index(&[0u8; 32], 0).is_err());
    }
}
