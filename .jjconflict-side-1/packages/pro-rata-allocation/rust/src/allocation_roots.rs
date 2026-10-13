//! Canonical root projections for the legacy allocation-kernel artifacts.
//!
//! The canonical codec is shared; these artifact domain tags and envelopes
//! belong to the allocation package.

use prf_canonical_codec::{domain_hash, domain_hash_raw, CanonValue};
use sha2::{Digest, Sha256};

const CLAIMANT_SET: &str = "CLAIMANT_SET_V1";
const OUTCOME_SET: &str = "OUTCOME_SET_V1";
const PROPOSED_RATES: &str = "PROPOSED_RATES_V1";
const RATE_DERIVED_SUMMARY: &str = "RATE_DERIVED_SUMMARY_V1";
const SELECTED_OUTCOME: &str = "SELECTED_OUTCOME_V1";
const RESULT_ROOT: &str = "RESULT_ROOT_V1";
const MERKLE_NODE: &str = "EVIDENCE_MERKLE_NODE_V1";

/// Claimant-set root: domain hash of the canonically ordered claimant set.
pub fn claimant_set_root(claimants: &CanonValue) -> String {
    domain_hash(CLAIMANT_SET, claimants)
}

/// Outcome-set root: domain hash of the canonically ordered outcome set.
pub fn outcome_set_root(outcomes: &CanonValue) -> String {
    domain_hash(OUTCOME_SET, outcomes)
}

/// Proposed-rates root.
pub fn proposed_rates_root(rates: &CanonValue) -> String {
    domain_hash(PROPOSED_RATES, rates)
}

/// Rate-derived summary hash (wraps the summary in its schema envelope).
pub fn rate_derived_summary_hash(summary: &CanonValue) -> String {
    let envelope = CanonValue::map(vec![
        (
            CanonValue::keyword("schema-version"),
            CanonValue::str("rate-derived-summary.v1"),
        ),
        (CanonValue::keyword("summary"), summary.clone()),
    ]);
    domain_hash(RATE_DERIVED_SUMMARY, &envelope)
}

/// Selected-outcome hash.
pub fn selected_outcome_hash(outcome: &CanonValue) -> String {
    domain_hash(SELECTED_OUTCOME, outcome)
}

/// Merkle root for allocation result leaves in canonical claimant order.
pub fn result_merkle_root(leaves: &[CanonValue]) -> Result<String, String> {
    let digests: Vec<Vec<u8>> = leaves
        .iter()
        .map(|leaf| domain_hash_raw(RESULT_ROOT, leaf))
        .collect();
    if digests.is_empty() {
        return Err("empty Merkle tree".to_string());
    }

    let mut level = digests;
    while level.len() > 1 {
        if level.len() % 2 == 1 {
            level.push(level.last().expect("non-empty level").clone());
        }
        level = level
            .chunks(2)
            .map(|pair| {
                let mut hasher = Sha256::new();
                hasher.update(MERKLE_NODE.as_bytes());
                hasher.update(&pair[0]);
                hasher.update(&pair[1]);
                hasher.finalize().to_vec()
            })
            .collect();
    }
    Ok(hex::encode(&level[0]))
}
