//! Canonical ordering for allocation context members.

use crate::kernel_types::{Claimant, Outcome};
use prf_canonical_codec::CanonValue;

fn canonical_id_key(id: &str) -> Vec<u8> {
    CanonValue::str(id).encode()
}

/// Sort claimants by the canonical binary encoding of their claim IDs.
pub fn sort_claimants(claimants: &mut [Claimant]) {
    claimants.sort_by_key(|claimant| canonical_id_key(&claimant.claim_id));
}

/// Sort outcomes and each outcome's allocation entries by canonical ID encoding.
pub fn sort_outcomes(outcomes: &mut [Outcome]) {
    outcomes.sort_by_key(|outcome| canonical_id_key(&outcome.outcome_id));
    for outcome in outcomes {
        outcome
            .allocations
            .sort_by_key(|entry| canonical_id_key(&entry.claim_id));
    }
}
