//! SP1 guest for the IEE-PRF allocation kernel.
//!
//! The guest reads the canonical allocation input document (the same JSON that
//! the PRF `allocation verify-proposal` command and the native Rust CLI
//! consume), runs the shared `allocation-kernel` core, and commits the
//! public-value projection as the program's public values.
//!
//! Public values are the exact same JSON projection that the native kernel
//! emits (`json_projection::public_result_to_json`). This makes
//! `SP1 guest public values == native Rust public result == PRF public result`
//! hold byte-for-byte, which the host script verifies.

#![no_main]
sp1_zkvm::entrypoint!(main);

use allocation_kernel::{json_projection, kernel};
use serde_json::Value;

pub fn main() {
    // Read the canonical allocation input document (raw bytes).
    let input = sp1_zkvm::io::read_vec();

    let parsed: Value = match serde_json::from_slice(&input) {
        Ok(v) => v,
        Err(e) => {
            // Commit a minimal rejected projection so the proof still carries
            // a stable outcome rather than failing the whole program.
            let envelope = serde_json::json!({
                "result/status": "rejected",
                "rejection/classification": "malformed-input",
                "rejection/reason": format!("malformed JSON: {}", e),
            });
            let bytes = serde_json::to_vec(&envelope).expect("serialize rejection");
            sp1_zkvm::io::commit_slice(&bytes);
            return;
        }
    };

    let (ctx, committed) = match (
        kernel::parse_context(&parsed),
        kernel::parse_committed_for_proving(&parsed),
    ) {
        (Ok(ctx), Ok(committed)) => (ctx, committed),
        (Err(e), _) | (_, Err(e)) => {
            let envelope = serde_json::json!({
                "result/status": "rejected",
                "rejection/classification": e.classification,
                "rejection/reason": e.reason,
            });
            let bytes = serde_json::to_vec(&envelope).expect("serialize rejection");
            sp1_zkvm::io::commit_slice(&bytes);
            return;
        }
    };

    let round_state = kernel::parse_round_state(&parsed);
    let result = kernel::run_kernel(&ctx, &committed, round_state.as_ref());
    let json = json_projection::public_result_to_json(&result);
    let bytes = serde_json::to_vec(&json).expect("serialize public values");
    sp1_zkvm::io::commit_slice(&bytes);
}
