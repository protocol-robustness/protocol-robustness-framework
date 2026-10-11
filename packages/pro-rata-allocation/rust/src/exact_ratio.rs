//! Exact rational arithmetic used by deterministic allocation policies.
//!
//! This module owns rate representation and ratio validation. Kernel-context
//! projections remain in the host crate because they combine these mechanics
//! with that kernel's input and result semantics.

use num_bigint::BigInt;
use num_integer::Integer;
use num_traits::{One, Signed, Zero};

#[derive(Clone, Debug)]
pub struct RateEntry {
    pub outcome_id: String,
    pub numerator: BigInt,
    pub denominator: BigInt,
}

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
    for rate in rates {
        common = lcm(&common, &rate.denominator);
    }
    let scaled: Vec<BigInt> = rates
        .iter()
        .map(|rate| (&rate.numerator * &common) / &rate.denominator)
        .collect();
    let sum: BigInt = scaled.iter().sum();
    (common, scaled, sum)
}

/// True when every rate is a reduced exact ratio.
pub fn rates_canonical_exact(rates: &[RateEntry]) -> bool {
    rates.iter().all(|rate| {
        !rate.numerator.is_negative()
            && !rate.denominator.is_zero()
            && rate.denominator.is_positive()
            && (rate.numerator.is_zero()
                || bigint_gcd(&rate.numerator, &rate.denominator) == BigInt::one())
    })
}

/// True when proposed rates sum exactly to one under the common denominator.
pub fn rates_sum_to_one(rates: &[RateEntry]) -> bool {
    let (common, _, sum) = ratio_sum_common(rates);
    sum == common
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
