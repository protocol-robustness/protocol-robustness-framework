//! SP1 guest for the realized-allocation-statement core (thin wrapper).
//!
//! Reads the canonical realized-statement input document (the same JSON the
//! native `realized_statement_io` path consumes), runs the shared
//! `allocation-kernel::realized_statement_io` core, and commits the statement
//! public-value projection as the program's public values.
//!
//! Public values are the exact same JSON projection the native Rust core
//! emits, so `SP1 guest public values == native Rust public values` holds
//! byte-for-byte. This is the thin wrapper required by acceptance condition B:
//! the guest executes deterministic Rust domain logic; it does not reimplement
//! the realization or the statement encoding.

#![no_main]
sp1_zkvm::entrypoint!(main);

use allocation_kernel::realized_statement_io;
use serde_json::Value;

pub fn main() {
    // Read the canonical realized-statement input document (raw bytes).
    let input = sp1_zkvm::io::read_vec();

    let parsed: Value = match serde_json::from_slice(&input) {
        Ok(v) => v,
        Err(e) => {
            // Commit a minimal rejected projection so the proof still carries a
            // stable outcome rather than failing the whole program.
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

    // Thin wrapper: delegate all semantics + encoding to the shared core.
    let result = realized_statement_io::run_realized_statement(&parsed);
    let bytes = serde_json::to_vec(&result).expect("serialize public values");
    sp1_zkvm::io::commit_slice(&bytes);
}
