//! Compatibility facade for package-owned pro-rata proportionality semantics.

pub use prf_pro_rata_allocation::exact_ratio::{
    bigint_gcd, lcm, rates_canonical_exact, rates_sum_to_one,
};
pub use prf_pro_rata_allocation::proportionality::{
    build_rate_derived_summary, expected_allocation_numerators, proportional_proposed,
};
