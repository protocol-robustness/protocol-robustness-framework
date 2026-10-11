//! Compatibility facade for package-owned pro-rata allocation validation.

pub use prf_pro_rata_allocation::validation::{
    run_assertions, AssertionResult, Committed, RecomputedRoots, ASSERTION_IDS,
};
