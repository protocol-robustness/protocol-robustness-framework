//! Context-derived exact proportionality checks and summary projection.

use crate::exact_ratio::ratio_sum_common;
use crate::kernel_types::Context;
use num_bigint::BigInt;
use num_traits::Zero;
use prf_canonical_codec::CanonValue;

/// Expected-allocation numerators for each claimant in canonical order.
pub fn expected_allocation_numerators(context: &Context) -> (BigInt, Vec<BigInt>) {
    let (common, scaled, _) = ratio_sum_common(&context.rates);
    let numerators: Vec<BigInt> = context
        .claimants
        .iter()
        .map(|claim| {
            let mut acc = BigInt::zero();
            for (outcome, rate_numerator) in context.outcomes.iter().zip(scaled.iter()) {
                let allocated = outcome
                    .allocations
                    .iter()
                    .find(|entry| entry.claim_id == claim.claim_id)
                    .map(|entry| entry.allocated.clone())
                    .unwrap_or_else(BigInt::zero);
                acc += rate_numerator * allocated;
            }
            acc
        })
        .collect();
    (common, numerators)
}

/// The rate-derived summary as a canonical value tree.
pub fn build_rate_derived_summary(context: &Context) -> CanonValue {
    let (common, scaled, sum) = ratio_sum_common(&context.rates);
    let (expected_common, numerators) = expected_allocation_numerators(context);

    let scaled_entries = context
        .rates
        .iter()
        .zip(scaled.iter())
        .map(|(rate, numerator)| {
            CanonValue::map(vec![
                (
                    CanonValue::keyword("outcome/id"),
                    CanonValue::str(&rate.outcome_id),
                ),
                (
                    CanonValue::keyword("numerator"),
                    CanonValue::int(numerator.clone()),
                ),
            ])
        })
        .collect();

    let expected_allocations = context
        .claimants
        .iter()
        .zip(numerators.iter())
        .map(|(claim, expected)| {
            CanonValue::map(vec![
                (
                    CanonValue::keyword("claim/id"),
                    CanonValue::str(&claim.claim_id),
                ),
                (
                    CanonValue::keyword("expected-allocation-numerator"),
                    CanonValue::int(expected.clone()),
                ),
                (
                    CanonValue::keyword("expected-allocation-denominator"),
                    CanonValue::int(expected_common.clone()),
                ),
                (
                    CanonValue::keyword("exact-pro-rata-numerator"),
                    CanonValue::int(&context.capacity * &claim.weight),
                ),
                (
                    CanonValue::keyword("exact-pro-rata-denominator"),
                    CanonValue::int(context.total_eligible_weight.clone()),
                ),
            ])
        })
        .collect();

    CanonValue::map(vec![
        (
            CanonValue::keyword("common-rate-denominator"),
            CanonValue::int(common.clone()),
        ),
        (
            CanonValue::keyword("scaled-rate-numerators"),
            CanonValue::array(scaled_entries),
        ),
        (
            CanonValue::keyword("expected-allocations"),
            CanonValue::array(expected_allocations),
        ),
        (
            CanonValue::keyword("rates-sum"),
            CanonValue::int(sum.clone()),
        ),
        (
            CanonValue::keyword("rates-sum-to-one?"),
            CanonValue::bool(sum == common),
        ),
    ])
}

/// Exact cross-product proportionality: E-num_i * W == C * w_i * D.
pub fn proportional_proposed(context: &Context) -> bool {
    let weight_total = &context.total_eligible_weight;
    let capacity = &context.capacity;
    let (denominator, _, _) = ratio_sum_common(&context.rates);
    let (_, expected_numerators) = expected_allocation_numerators(context);
    context
        .claimants
        .iter()
        .zip(expected_numerators.iter())
        .all(|(claim, expected)| {
            (expected * weight_total) == (capacity * &claim.weight * &denominator)
        })
}
