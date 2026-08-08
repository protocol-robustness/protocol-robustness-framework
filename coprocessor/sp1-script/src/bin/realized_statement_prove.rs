//! SP1 host script for the realized-allocation-statement guest (thin wrapper).
//!
//! Generates and verifies a proof of the realized-statement SP1 program, and
//! checks the equality:
//!
//!     native Rust realized-statement output == SP1 guest public values
//!
//! Usage:
//!   cargo run --release --bin realized-statement-prove -- \
//!     --input <input.json> [--prove|--execute]
//!
//! The input document is the canonical realized-statement input (allocation
//! context + available + requested + policy + fail-action-policy + round-state).

use clap::Parser;
use serde_json::Value;
use sp1_sdk::{
    blocking::{ProveRequest, Prover, ProverClient},
    include_elf, Elf, HashableKey, ProvingKey, SP1Stdin,
};

/// The ELF for the realized-statement SP1 guest.
const REALIZED_ELF: Elf = include_elf!("realized-statement-sp1-program");

#[derive(Parser, Debug)]
#[command(author, version, about)]
struct Args {
    /// Path to the canonical realized-statement input JSON document.
    #[arg(
        long,
        default_value = "../../scenarios/allocation/a-vs-b-plus-c/realized-statement-input.json"
    )]
    input: String,
    /// Execute the program without generating a proof.
    #[arg(long)]
    execute: bool,
    /// Generate a proof (default when --execute is not given).
    #[arg(long)]
    prove: bool,
}

fn load_input(path: &str) -> Value {
    let text = std::fs::read_to_string(path)
        .unwrap_or_else(|e| panic!("failed to read input {}: {}", path, e));
    serde_json::from_str(&text).unwrap_or_else(|e| panic!("failed to parse input {}: {}", path, e))
}

fn main() {
    sp1_sdk::utils::setup_logger();

    let args = Args::parse();

    if args.execute == args.prove {
        eprintln!("Error: specify exactly one of --execute or --prove");
        std::process::exit(1);
    }

    let input = load_input(&args.input);
    let stdin_input = serde_json::to_vec(&input).expect("serialize input");
    let mut stdin = SP1Stdin::new();
    stdin.write_vec(stdin_input);

    // Reference native computation: run the shared realized-statement core on
    // the host to compare against the committed guest public values.
    let native = {
        let result = allocation_kernel::realized_statement_io::run_realized_statement(&input);
        serde_json::to_vec(&result).expect("serialize native public values")
    };

    let client = ProverClient::from_env();

    if args.execute {
        let (public_values, report) = client.execute(REALIZED_ELF, stdin).run().unwrap();
        println!("Program executed successfully.");
        println!("Cycles: {}", report.total_instruction_count());
        assert_eq!(public_values.as_slice(), native.as_slice());
        println!("Guest public values == native public values (execute).");
    } else {
        let pk = client.setup(REALIZED_ELF).expect("failed to setup elf");
        let proof = client.prove(&pk, stdin).run().expect("failed to prove");
        client
            .verify(&proof, pk.verifying_key(), None)
            .expect("failed to verify proof");
        println!("Successfully generated and verified proof!");

        assert_eq!(proof.public_values.as_slice(), native.as_slice());
        println!("Guest public values == native public values (prove).");
        println!("Program vkey: {}", pk.verifying_key().bytes32());
    }
}
