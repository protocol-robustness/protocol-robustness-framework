//! Structural semantic checks for parsed allocation context members.

use crate::exact_ratio::RateEntry;
use crate::kernel_types::{Claimant, Outcome, Policy};
use num_bigint::BigInt;
use num_traits::Zero;
use std::collections::{HashMap, HashSet};

/// Stable validation error; classifications match the allocation-kernel contract.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ContextValidationError {
    pub classification: &'static str,
    pub reason: &'static str,
}

/// Align parsed rates to the canonical outcome order and require exact outcome coverage.
pub fn rates_for_outcomes(
    outcomes: &[Outcome],
    rates_by_outcome: &HashMap<String, (BigInt, BigInt)>,
) -> Result<Vec<RateEntry>, String> {
    let outcome_ids: HashSet<&str> = outcomes
        .iter()
        .map(|outcome| outcome.outcome_id.as_str())
        .collect();
    if let Some(extra_id) = rates_by_outcome
        .keys()
        .find(|outcome_id| !outcome_ids.contains(outcome_id.as_str()))
    {
        return Err(format!("unexpected rate for outcome {}", extra_id));
    }

    outcomes
        .iter()
        .map(|outcome| {
            let (numerator, denominator) = rates_by_outcome
                .get(&outcome.outcome_id)
                .cloned()
                .ok_or_else(|| format!("missing rate for outcome {}", outcome.outcome_id))?;
            Ok(RateEntry {
                outcome_id: outcome.outcome_id.clone(),
                numerator,
                denominator,
            })
        })
        .collect()
}

/// Check claimant-set semantics in the legacy validation order.
pub fn validate_claimants(
    claimants: &[Claimant],
    policy: &Policy,
) -> Result<(), ContextValidationError> {
    let mut ids: Vec<&str> = claimants
        .iter()
        .map(|claim| claim.claim_id.as_str())
        .collect();
    ids.sort_unstable();
    if ids.windows(2).any(|pair| pair[0] == pair[1]) {
        return Err(ContextValidationError {
            classification: "duplicate-claim-id",
            reason: "duplicate claim id",
        });
    }

    if policy.forbid_duplicate_owners {
        let mut owners: Vec<&str> = claimants
            .iter()
            .map(|claim| claim.economic_owner_id.as_str())
            .collect();
        owners.sort_unstable();
        if owners.windows(2).any(|pair| pair[0] == pair[1]) {
            return Err(ContextValidationError {
                classification: "duplicate-economic-owner",
                reason: "duplicate economic owner",
            });
        }
    }

    if claimants.is_empty() {
        return Err(ContextValidationError {
            classification: "empty-claimant-set",
            reason: "empty claimant set",
        });
    }
    Ok(())
}

/// Check outcome-set semantics in the legacy validation order.
pub fn validate_outcomes(outcomes: &[Outcome]) -> Result<(), ContextValidationError> {
    if outcomes.is_empty() {
        return Err(ContextValidationError {
            classification: "empty-outcome-set",
            reason: "empty outcome set",
        });
    }

    let mut ids: Vec<&str> = outcomes
        .iter()
        .map(|outcome| outcome.outcome_id.as_str())
        .collect();
    ids.sort_unstable();
    if ids.windows(2).any(|pair| pair[0] == pair[1]) {
        return Err(ContextValidationError {
            classification: "duplicate-outcome-id",
            reason: "duplicate outcome id",
        });
    }
    Ok(())
}

/// Check claimant weights against the committed allocation-context totals.
pub fn validate_weight_totals(
    claimants: &[Claimant],
    total_eligible_weight: &BigInt,
    exact_pro_rata_denominator: &BigInt,
) -> Result<(), ContextValidationError> {
    let sum_weights: BigInt = claimants.iter().map(|claim| claim.weight.clone()).sum();
    if sum_weights.is_zero() {
        return Err(ContextValidationError {
            classification: "zero-total-weight",
            reason: "zero total weight",
        });
    }
    if &sum_weights != total_eligible_weight {
        return Err(ContextValidationError {
            classification: "inconsistent-total-weight",
            reason: "inconsistent total weight",
        });
    }
    if &sum_weights != exact_pro_rata_denominator {
        return Err(ContextValidationError {
            classification: "inconsistent-pro-rata-denominator",
            reason: "inconsistent pro-rata denominator",
        });
    }
    Ok(())
}

/// Validate the domain constraints on an exact allocation rate.
pub fn validate_rate(
    numerator: &BigInt,
    denominator: &BigInt,
) -> Result<(), ContextValidationError> {
    if numerator < &BigInt::from(0) {
        return Err(ContextValidationError {
            classification: "negative-amount",
            reason: "numerator must be non-negative",
        });
    }
    if denominator <= &BigInt::from(0) {
        return Err(ContextValidationError {
            classification: "non-positive-rate-denominator",
            reason: "non-positive rate denominator",
        });
    }
    Ok(())
}

/// Require a positive allocation capacity.
pub fn validate_capacity(capacity: &BigInt) -> Result<(), ContextValidationError> {
    if capacity <= &BigInt::from(0) {
        return Err(ContextValidationError {
            classification: "non-positive-capacity",
            reason: "non-positive capacity",
        });
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use num_bigint::BigInt;

    fn claimant(id: &str, owner: &str) -> Claimant {
        Claimant {
            claim_id: id.to_string(),
            economic_owner_id: owner.to_string(),
            amount: BigInt::from(1),
            weight: BigInt::from(1),
        }
    }

    #[test]
    fn rates_follow_outcome_order_and_report_missing_outcomes() {
        let outcomes = [
            Outcome {
                outcome_id: "b".into(),
                allocations: vec![],
            },
            Outcome {
                outcome_id: "a".into(),
                allocations: vec![],
            },
        ];
        let rates = HashMap::from([
            ("a".into(), (BigInt::from(1), BigInt::from(2))),
            ("b".into(), (BigInt::from(3), BigInt::from(4))),
        ]);
        let aligned = rates_for_outcomes(&outcomes, &rates).unwrap();
        assert_eq!(aligned[0].outcome_id, "b");
        assert_eq!(aligned[1].outcome_id, "a");
        assert!(aligned[0].numerator == BigInt::from(3));

        let with_extra = HashMap::from([
            ("a".into(), (BigInt::from(1), BigInt::from(2))),
            ("b".into(), (BigInt::from(3), BigInt::from(4))),
            ("extra".into(), (BigInt::from(5), BigInt::from(6))),
        ]);
        assert_eq!(
            rates_for_outcomes(&outcomes, &with_extra).unwrap_err(),
            "unexpected rate for outcome extra"
        );

        let missing = HashMap::from([("a".into(), (BigInt::from(1), BigInt::from(2)))]);
        assert_eq!(
            rates_for_outcomes(&outcomes, &missing).unwrap_err(),
            "missing rate for outcome b"
        );
    }

    #[test]
    fn rate_validation_preserves_legacy_classifications() {
        assert_eq!(
            validate_rate(&BigInt::from(-1), &BigInt::from(1))
                .unwrap_err()
                .classification,
            "negative-amount"
        );
        assert_eq!(
            validate_rate(&BigInt::from(1), &BigInt::from(0))
                .unwrap_err()
                .classification,
            "non-positive-rate-denominator"
        );
        assert!(validate_rate(&BigInt::from(0), &BigInt::from(1)).is_ok());
    }

    #[test]
    fn capacity_validation_requires_positive_value() {
        for capacity in [BigInt::from(0), BigInt::from(-1)] {
            let error = validate_capacity(&capacity).unwrap_err();
            assert_eq!(error.classification, "non-positive-capacity");
            assert_eq!(error.reason, "non-positive capacity");
        }
        assert!(validate_capacity(&BigInt::from(1)).is_ok());
    }

    #[test]
    fn duplicate_claim_ids_keep_legacy_classification() {
        let policy = Policy {
            policy_id: "p".into(),
            policy_hash: "h".into(),
            forbid_duplicate_owners: false,
        };
        let error =
            validate_claimants(&[claimant("c", "a"), claimant("c", "b")], &policy).unwrap_err();
        assert_eq!(error.classification, "duplicate-claim-id");
    }

    #[test]
    fn duplicate_owners_are_rejected_only_by_policy() {
        let claimants = [claimant("c1", "owner"), claimant("c2", "owner")];
        let policy = Policy {
            policy_id: "p".into(),
            policy_hash: "h".into(),
            forbid_duplicate_owners: true,
        };
        let error = validate_claimants(&claimants, &policy).unwrap_err();
        assert_eq!(error.classification, "duplicate-economic-owner");
    }

    #[test]
    fn empty_and_duplicate_outcomes_keep_legacy_classifications() {
        assert_eq!(
            validate_outcomes(&[]).unwrap_err().classification,
            "empty-outcome-set"
        );
        let duplicate_ids = [
            Outcome {
                outcome_id: "same".into(),
                allocations: vec![],
            },
            Outcome {
                outcome_id: "same".into(),
                allocations: vec![],
            },
        ];
        assert_eq!(
            validate_outcomes(&duplicate_ids)
                .unwrap_err()
                .classification,
            "duplicate-outcome-id"
        );
    }

    #[test]
    fn weight_total_errors_keep_legacy_order_and_classifications() {
        let claimants = [claimant("c", "owner")];
        let zero = BigInt::from(0);
        let one = BigInt::from(1);
        assert_eq!(
            validate_weight_totals(&[], &zero, &zero)
                .unwrap_err()
                .classification,
            "zero-total-weight"
        );
        assert_eq!(
            validate_weight_totals(&claimants, &BigInt::from(2), &BigInt::from(3))
                .unwrap_err()
                .classification,
            "inconsistent-total-weight"
        );
        assert_eq!(
            validate_weight_totals(&claimants, &one, &BigInt::from(2))
                .unwrap_err()
                .classification,
            "inconsistent-pro-rata-denominator"
        );
        assert!(validate_weight_totals(&claimants, &one, &one).is_ok());
    }
}
