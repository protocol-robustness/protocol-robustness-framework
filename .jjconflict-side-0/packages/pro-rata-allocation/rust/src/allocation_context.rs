//! Canonical context and result-leaf projections for the allocation kernel.

use crate::kernel_types::{Claimant, Context, Outcome};
use num_bigint::BigInt;
use num_traits::Zero;
use prf_canonical_codec::CanonValue;

pub const SELECTION_ALGORITHM: &str = "domain-hash-rejection-v1";

fn claimant_value(claim: &Claimant) -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("claim/id"),
            CanonValue::str(&claim.claim_id),
        ),
        (
            CanonValue::keyword("economic-owner-id"),
            CanonValue::str(&claim.economic_owner_id),
        ),
        (
            CanonValue::keyword("amount"),
            CanonValue::int(claim.amount.clone()),
        ),
        (
            CanonValue::keyword("weight"),
            CanonValue::int(claim.weight.clone()),
        ),
    ])
}

fn allocation_entry_value(entry: &crate::kernel_types::AllocationEntry) -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("claim/id"),
            CanonValue::str(&entry.claim_id),
        ),
        (
            CanonValue::keyword("allocated"),
            CanonValue::int(entry.allocated.clone()),
        ),
    ])
}

fn outcome_value(outcome: &Outcome) -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("outcome/id"),
            CanonValue::str(&outcome.outcome_id),
        ),
        (
            CanonValue::keyword("allocations"),
            CanonValue::array(
                outcome
                    .allocations
                    .iter()
                    .map(allocation_entry_value)
                    .collect(),
            ),
        ),
    ])
}

fn context_rate_value(rate: &crate::exact_ratio::RateEntry) -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("outcome/id"),
            CanonValue::str(&rate.outcome_id),
        ),
        (
            CanonValue::keyword("rate"),
            CanonValue::map(vec![
                (
                    CanonValue::keyword("numerator"),
                    CanonValue::int(rate.numerator.clone()),
                ),
                (
                    CanonValue::keyword("denominator"),
                    CanonValue::int(rate.denominator.clone()),
                ),
            ]),
        ),
    ])
}

fn policy_value(policy: &crate::kernel_types::Policy) -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("policy/id"),
            CanonValue::str(&policy.policy_id),
        ),
        (
            CanonValue::keyword("policy/hash"),
            CanonValue::str(&policy.policy_hash),
        ),
        (
            CanonValue::keyword("forbid-duplicate-owners"),
            CanonValue::bool(policy.forbid_duplicate_owners),
        ),
    ])
}

/// Canonical value tree committed by the legacy allocation-context hash.
pub fn context_preimage(context: &Context) -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("schema-version"),
            CanonValue::str("allocation-context.v1"),
        ),
        (
            CanonValue::keyword("artifact-kind"),
            CanonValue::keyword("allocation-context"),
        ),
        (
            CanonValue::keyword("allocation/id"),
            CanonValue::str(&context.allocation_id),
        ),
        (
            CanonValue::keyword("allocation-kernel-version"),
            CanonValue::str(&context.kernel_version),
        ),
        (
            CanonValue::keyword("selection-algorithm"),
            CanonValue::keyword(SELECTION_ALGORITHM),
        ),
        (CanonValue::keyword("policy"), policy_value(&context.policy)),
        (
            CanonValue::keyword("claimants"),
            CanonValue::array(context.claimants.iter().map(claimant_value).collect()),
        ),
        (
            CanonValue::keyword("outcomes"),
            CanonValue::array(context.outcomes.iter().map(outcome_value).collect()),
        ),
        (
            CanonValue::keyword("proposed-rates"),
            CanonValue::array(context.rates.iter().map(context_rate_value).collect()),
        ),
        (
            CanonValue::keyword("capacity"),
            CanonValue::int(context.capacity.clone()),
        ),
        (
            CanonValue::keyword("total-eligible-weight"),
            CanonValue::int(context.total_eligible_weight.clone()),
        ),
        (
            CanonValue::keyword("exact-pro-rata-denominator"),
            CanonValue::int(context.exact_pro_rata_denominator.clone()),
        ),
        (
            CanonValue::keyword("authoritative-randomness"),
            CanonValue::array(
                context
                    .randomness
                    .iter()
                    .map(|byte| CanonValue::int(BigInt::from(*byte)))
                    .collect(),
            ),
        ),
    ])
}

pub fn claimants_value(context: &Context) -> CanonValue {
    CanonValue::array(context.claimants.iter().map(claimant_value).collect())
}

pub fn outcomes_value(context: &Context) -> CanonValue {
    CanonValue::array(context.outcomes.iter().map(outcome_value).collect())
}

pub fn rates_value(context: &Context) -> CanonValue {
    CanonValue::array(
        context
            .rates
            .iter()
            .map(|rate| {
                CanonValue::map(vec![
                    (
                        CanonValue::keyword("outcome/id"),
                        CanonValue::str(&rate.outcome_id),
                    ),
                    (
                        CanonValue::keyword("numerator"),
                        CanonValue::int(rate.numerator.clone()),
                    ),
                    (
                        CanonValue::keyword("denominator"),
                        CanonValue::int(rate.denominator.clone()),
                    ),
                ])
            })
            .collect(),
    )
}

pub fn selected_outcome_value(outcome: &Outcome) -> CanonValue {
    outcome_value(outcome)
}

/// One result Merkle leaf value tree from the legacy allocation kernel.
pub fn result_leaf(
    claim: &Claimant,
    allocated: BigInt,
    selected_outcome_id: &str,
    context_hash: &str,
) -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("claim/id"),
            CanonValue::str(&claim.claim_id),
        ),
        (
            CanonValue::keyword("beneficiary"),
            CanonValue::str(&claim.economic_owner_id),
        ),
        (
            CanonValue::keyword("context-hash"),
            CanonValue::str(context_hash),
        ),
        (
            CanonValue::keyword("input-amount"),
            CanonValue::int(claim.amount.clone()),
        ),
        (
            CanonValue::keyword("input-weight"),
            CanonValue::int(claim.weight.clone()),
        ),
        (
            CanonValue::keyword("result-status"),
            CanonValue::str(if allocated.is_zero() {
                "not-allocated"
            } else {
                "allocated"
            }),
        ),
        (
            CanonValue::keyword("final-allocation"),
            CanonValue::int(allocated),
        ),
        (
            CanonValue::keyword("selected-outcome-id"),
            CanonValue::str(selected_outcome_id),
        ),
    ])
}
