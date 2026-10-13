//! SP1 guest for the V2 source-bound Shortfall assurance statement.
#![no_main]
sp1_zkvm::entrypoint!(main);

use allocation_kernel::source_bound_v2;
use serde_json::Value;

pub fn main() {
    let input = sp1_zkvm::io::read_vec();
    let parsed: Value = serde_json::from_slice(&input).expect("source-bound-v2 input must be JSON");
    let (_, _, values) = source_bound_v2::derive_fixture(&parsed)
        .unwrap_or_else(|reason| panic!("source-bound-v2 witness rejected: {reason}"));
    let public_values = values.encode();
    assert_eq!(public_values.len(), source_bound_v2::PUBLIC_VALUES_LEN);
    sp1_zkvm::io::commit_slice(&public_values);
}
