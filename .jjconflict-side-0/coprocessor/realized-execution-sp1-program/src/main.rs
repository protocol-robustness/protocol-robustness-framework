//! SP1 guest for `realized-allocation-execution.v1`.
//!
//! This is deliberately separate from `realized-statement-sp1-program`: that
//! guest commits the stable 32-byte `evm-bytes32-v1` statement-root ABI. This
//! guest parses the raw statement input plus execution companion and commits
//! only the derived, fixed 384-byte execution public-value projection.

#![no_main]
sp1_zkvm::entrypoint!(main);

use allocation_kernel::realized_execution;
use serde_json::Value;

pub fn main() {
    let input = sp1_zkvm::io::read_vec();
    let parsed: Value =
        serde_json::from_slice(&input).expect("execution projection input must be JSON");
    let projection =
        realized_execution::derive(&parsed).unwrap_or_else(|(classification, reason)| {
            panic!("execution projection rejected: {classification}: {reason}")
        });
    let public_values = projection.public_values();
    assert_eq!(public_values.len(), realized_execution::PUBLIC_VALUES_LEN);
    sp1_zkvm::io::commit_slice(&public_values);
}
