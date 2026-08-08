//! Native CLI: `allocation-kernel < vector.json`
//!
//! Reads a canonical vector input document from stdin, runs the independent
//! kernel, and prints the canonical JSON public-value projection to stdout.
//! Diagnostics go to stderr. Returns nonzero on malformed or non-passing input.

use allocation_kernel::{json_projection, kernel};
use std::io::{self, Read};

fn main() {
    let mut input = String::new();
    if let Err(e) = io::stdin().read_to_string(&mut input) {
        eprintln!("allocation-kernel: failed to read stdin: {}", e);
        std::process::exit(2);
    }

    let parsed: serde_json::Value = match serde_json::from_str(&input) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("allocation-kernel: malformed JSON: {}", e);
            std::process::exit(2);
        }
    };

    match kernel::parse_context(&parsed) {
        Err(e) => {
            eprintln!("allocation-kernel: rejected: {}", e);
            // Emit the minimal rejection envelope on stdout, matching the PRF
            // rejection projection (status + classification only).
            println!(
                "{}",
                serde_json::json!({
                    "result/status": "rejected",
                    "rejection/classification": e.classification,
                })
            );
            std::process::exit(1);
        }
        Ok(ctx) => {
            let committed = match kernel::parse_committed(&parsed) {
                Ok(c) => c,
                Err(e) => {
                    eprintln!("allocation-kernel: rejected: {}", e);
                    println!(
                        "{}",
                        serde_json::json!({
                            "result/status": "rejected",
                            "rejection/classification": e.classification,
                        })
                    );
                    std::process::exit(1);
                }
            };
            let round_state = kernel::parse_round_state(&parsed);
            let result = kernel::run_kernel(&ctx, &committed, round_state.as_ref());
            let json = json_projection::public_result_to_json(&result);
            println!(
                "{}",
                serde_json::to_string(&json).expect("serialize result")
            );
            if result.status == "rejected" {
                if let Some(reason) = &result.rejection_reason {
                    eprintln!("allocation-kernel: rejected: {}", reason);
                }
                std::process::exit(1);
            }
        }
    }
}
