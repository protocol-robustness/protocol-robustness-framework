//! SP1 guest for `research-observed-run-proof-statement.v1`.
//!
//! Input is the canonical observed-run and run-assurance witness emitted by
//! Clojure. The guest validates source identities and required lineage, then
//! derives and commits the twelve public roots in their frozen canonical order.
#![no_main]
sp1_zkvm::entrypoint!(main);

use serde_json::Value;

pub fn main() {
    let input = sp1_zkvm::io::read_vec();
    let parsed: Value =
        serde_json::from_slice(&input).expect("observed-run source witness must be JSON");
    let public_values = observed_run_sp1_program::derive_public_values(&parsed)
        .unwrap_or_else(|reason| panic!("observed-run lineage rejected: {reason}"));
    assert_eq!(public_values.len(), 384);
    sp1_zkvm::io::commit_slice(&public_values);
}
