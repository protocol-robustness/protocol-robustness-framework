//! Compatibility facade for the standalone pro-rata realized-fill package.
//!
//! Existing kernel, statement, and SP1 callers keep their module path while the
//! allocation semantics and result types are owned by the package crate.

pub use prf_pro_rata_allocation::realized_fill::*;
