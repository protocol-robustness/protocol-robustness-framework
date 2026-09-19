//! SP1 guest for the source-bound Shortfall allocation witness v1.
#![no_main]
sp1_zkvm::entrypoint!(main);

use allocation_kernel::source_bound;
use serde_json::Value;

pub fn main() {
    let input = sp1_zkvm::io::read_vec();
    let parsed: Value = serde_json::from_slice(&input).expect("source-bound input must be JSON");
    let witness = source_bound::derive(&parsed)
        .unwrap_or_else(|reason| panic!("source-bound witness rejected: {reason}"));
    let public_values = witness.public_values();
    assert_eq!(public_values.len(), source_bound::PUBLIC_VALUES_LEN);
    sp1_zkvm::io::commit_slice(&public_values);
}
