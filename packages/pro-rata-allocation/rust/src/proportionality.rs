//! Exact rate-derived allocation summaries and proportionality checks.
//!
//! These legacy allocation semantics use package-owned context types and the
//! shared canonical codec. Host execution and proof assertions stay in the
//! adapter crate.

use crate::exact_ratio::ratio_sum_common;
use crate::kernel_types::Context;
use num_bigint::BigInt;
use num_traits::Zero;
use prf_canonical_codec::CanonValue;

/// Expected-allocation numerators for each claimant in canonical order.
/// E-num_i = sum_j (scaled numerator of outcome j) * allocation_i_j.
pub fn expected_allocation_numerators(ctx: &Context) -> (BigInt, Vec<BigInt>) {
    let (common, scaled, _sum) = ratio_sum_common(&ctx.rates);
    let numerators: Vec<BigInt> = ctx
        .claimants
        .iter()
        .map(|claim| {
            let cid = &claim.claim_id;
            let mut acc = BigInt::zero();
            for (outcome, s) in ctx.outcomes.iter().zip(scaled.iter()) {
                let alloc = outcome
                    .allocations
                    .iter()
                    .find(|a| &a.claim_id == cid)
                    .map(|a| a.allocated.clone())
                    .unwrap_or_else(BigInt::zero);
                acc += s * &alloc;
            }
            acc
        })
        .collect();
    (common, numerators)
}

/// The rate-derived summary as a canonical value tree (matches PRF
/// `build-rate-derived-summary`).
pub fn build_rate_derived_summary(ctx: &Context) -> CanonValue {
    let (common, scaled, sum) = ratio_sum_common(&ctx.rates);
    let (expected_common, numerators) = expected_allocation_numerators(ctx);

    let scaled_entries: Vec<CanonValue> = ctx
        .rates
        .iter()
        .zip(scaled.iter())
        .map(|(r, s)| {
            CanonValue::map(vec![
                (
                    CanonValue::keyword("outcome/id"),
                    CanonValue::str(&r.outcome_id),
                ),
                (CanonValue::keyword("numerator"), CanonValue::int(s.clone())),
            ])
        })
        .collect();

    let expected_allocations: Vec<CanonValue> = ctx
        .claimants
        .iter()
        .zip(numerators.iter())
        .map(|(c, e)| {
            CanonValue::map(vec![
                (
                    CanonValue::keyword("claim/id"),
                    CanonValue::str(&c.claim_id),
                ),
                (
                    CanonValue::keyword("expected-allocation-numerator"),
                    CanonValue::int(e.clone()),
                ),
                (
                    CanonValue::keyword("expected-allocation-denominator"),
                    CanonValue::int(expected_common.clone()),
                ),
                (
                    CanonValue::keyword("exact-pro-rata-numerator"),
                    CanonValue::int(&ctx.capacity * &c.weight),
                ),
                (
                    CanonValue::keyword("exact-pro-rata-denominator"),
                    CanonValue::int(ctx.total_eligible_weight.clone()),
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
pub fn proportional_proposed(ctx: &Context) -> bool {
    let w = &ctx.total_eligible_weight;
    let c = &ctx.capacity;
    let (d, _scaled, _sum) = ratio_sum_common(&ctx.rates);
    let (_expected_common, expected_numerators) = expected_allocation_numerators(ctx);
    ctx.claimants
        .iter()
        .zip(expected_numerators.iter())
        .all(|(claim, e_num)| (e_num * w) == (c * &claim.weight * &d))
}

/// Rate arithmetic retained as a compatibility export for the core crate.
pub use crate::exact_ratio::{bigint_gcd, lcm, rates_canonical_exact, rates_sum_to_one};
