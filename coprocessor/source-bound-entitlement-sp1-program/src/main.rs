//! Thin SP1 guest for `source-bound-entitlement-public-values.v1`.
//!
//! All semantic derivation lives in the reviewed Rust core module. The guest
//! accepts only the raw successor witness and commits its exact 288-byte
//! public projection.
#![no_main]
sp1_zkvm::entrypoint!(main);

use allocation_kernel::source_bound_entitlement;
use serde_json::Value;

pub fn main() {
    let input = sp1_zkvm::io::read_vec();
    let parsed: Value =
        serde_json::from_slice(&input).expect("source-bound-entitlement input must be JSON");
    let derivation = source_bound_entitlement::derive(&parsed)
        .unwrap_or_else(|reason| panic!("source-bound-entitlement witness rejected: {reason}"));
    let public_values = derivation.public_values.encode();
    assert_eq!(
        public_values.len(),
        source_bound_entitlement::PUBLIC_VALUES_LEN
    );
    sp1_zkvm::io::commit_slice(&public_values);
}
