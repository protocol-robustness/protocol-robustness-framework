//! Exact ratio and proportionality arithmetic, ported from the PRF proposal
//! namespace. All arithmetic is arbitrary-precision integer arithmetic; no
//! floating point is permitted.

use crate::canonical::CanonValue;
use crate::kernel::{Context, RateEntry};
use num_bigint::BigInt;
use num_integer::Integer;
use num_traits::{One, Signed, Zero};

/// Greatest common divisor (positive result).
pub fn bigint_gcd(a: &BigInt, b: &BigInt) -> BigInt {
    let g = a.gcd(b);
    if g.is_negative() {
        -g
    } else {
        g
    }
}

/// Least common multiple.
pub fn lcm(a: &BigInt, b: &BigInt) -> BigInt {
    if a.is_zero() || b.is_zero() {
        BigInt::zero()
    } else {
        (a / &bigint_gcd(a, b)) * b
    }
}

/// Derive the common denominator, scaled numerators, and sum for rates in
/// outcome canonical order.
pub fn ratio_sum_common(rates: &[RateEntry]) -> (BigInt, Vec<BigInt>, BigInt) {
    let mut common = BigInt::one();
    for r in rates {
        common = lcm(&common, &r.denominator);
    }
    let scaled: Vec<BigInt> = rates
        .iter()
        .map(|r| (&r.numerator * &common) / &r.denominator)
        .collect();
    let sum: BigInt = scaled.iter().sum();
    (common, scaled, sum)
}

/// True when every rate is a reduced exact ratio.
pub fn rates_canonical_exact(rates: &[RateEntry]) -> bool {
    rates.iter().all(|r| {
        (!r.numerator.is_negative())
            && !r.denominator.is_zero()
            && r.denominator.is_positive()
            && (r.numerator.is_zero()
                || (bigint_gcd(&r.numerator, &r.denominator) == BigInt::one()))
    })
}

/// True when the proposed rates sum exactly to one under the common denominator.
pub fn rates_sum_to_one(rates: &[RateEntry]) -> bool {
    let (common, _scaled, sum) = ratio_sum_common(rates);
    sum == common
}

/// Expected-allocation numerators for each claimant in canonical order.
///   E-num_i = sum_j (scaled numerator of outcome j) * allocation_i_j
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gcd_and_lcm() {
        assert_eq!(
            bigint_gcd(&BigInt::from(4), &BigInt::from(6)),
            BigInt::from(2)
        );
        assert_eq!(lcm(&BigInt::from(2), &BigInt::from(3)), BigInt::from(6));
        assert_eq!(lcm(&BigInt::from(4), &BigInt::from(6)), BigInt::from(12));
    }
}
