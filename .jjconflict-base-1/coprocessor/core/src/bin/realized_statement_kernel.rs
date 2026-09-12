//! Native CLI for the realized-allocation-statement core.
//!
//! Reads the canonical realized-statement input document from stdin, runs the
//! shared `realized_statement_io` core, and prints the canonical JSON
//! statement projection to stdout. Returns nonzero on malformed or rejected
//! input.

use allocation_kernel::realized_statement_io;
use std::io::{self, Read};

fn main() {
    let mut input = String::new();
    if let Err(e) = io::stdin().read_to_string(&mut input) {
        eprintln!("realized-statement-kernel: failed to read stdin: {}", e);
        std::process::exit(2);
    }

    let parsed: serde_json::Value = match serde_json::from_str(&input) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("realized-statement-kernel: malformed JSON: {}", e);
            std::process::exit(2);
        }
    };

    let result = realized_statement_io::run_realized_statement(&parsed);
    println!(
        "{}",
        serde_json::to_string(&result).expect("serialize result")
    );
    if result["result/status"] == "rejected" {
        eprintln!(
            "realized-statement-kernel: rejected: {}",
            result["rejection/reason"]
        );
        std::process::exit(1);
    }
}
