//! SP1 guest for the realized-allocation-statement core (thin wrapper).
//!
//! Reads the canonical realized-statement input document (the same JSON the
//! native `realized_statement_io` path consumes), runs the shared
//! `allocation-kernel::realized_statement_io` core, and commits the statement
//! root as a fixed-width EVM projection: a single `bytes32`.
//!
//! Public values are a 32-byte big-endian hash (the SHA-256 statement root),
//! NOT UTF-8 JSON. This is a deliberate EVM boundary decision:
//!   canonical statement
//!     → SHA-256
//!     → 32-byte root
//!     → SP1 public value (bytes32)
//!
//! The canonical JSON statement remains available off-chain via the native
//! projection path; only the on-chain-exposed SP1 public values change.

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
            // Commit a zero root for rejection so the proof envelope is stable.
            // (Off-chain consumers read the canonical statement JSON via the
            // native path; on-chain admission only sees the 32-byte root.)
            sp1_zkvm::io::commit_slice(&[0u8; 32]);
            eprintln!("malformed JSON: {}", e);
            return;
        }
    };

    // Thin wrapper: delegate all semantics to the shared core.
    let result = realized_statement_io::run_realized_statement(&parsed);

    // Extract and commit the statement root as a fixed bytes32.
    let root_hex = result["statement-root"]
        .as_str()
        .expect("statement-root must be present in passing result");
    let root_bytes = hex::decode(root_hex).expect("statement-root must be valid hex");
    assert_eq!(root_bytes.len(), 32, "statement root must be 32 bytes");
    sp1_zkvm::io::commit_slice(&root_bytes);
}
