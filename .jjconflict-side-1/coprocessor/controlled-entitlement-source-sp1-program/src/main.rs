//! Controlled-source SP1 profile for the source-bound entitlement projection.
//!
//! This profile requires the prepared operation to commit to the independently
//! reconstructed realized-statement root. It is intended for controlled
//! conformance sources and does not authenticate a production claims authority.
#![no_main]
sp1_zkvm::entrypoint!(main);

use allocation_kernel::controlled_entitlement_source;
use serde_json::Value;

pub fn main() {
    let input = sp1_zkvm::io::read_vec();
    let parsed: Value =
        serde_json::from_slice(&input).expect("controlled entitlement input must be JSON");
    let derivation = controlled_entitlement_source::derive(&parsed)
        .unwrap_or_else(|reason| panic!("controlled entitlement witness rejected: {reason}"));
    let public_values = derivation.public_values.encode();
    assert_eq!(
        public_values.len(),
        allocation_kernel::source_bound_entitlement::PUBLIC_VALUES_LEN
    );
    sp1_zkvm::io::commit_slice(&public_values);
}
