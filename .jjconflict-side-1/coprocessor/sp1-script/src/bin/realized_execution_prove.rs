//! Execute or prove the `realized-allocation-execution.v1` SP1 projection.
//!
//! The host supplies only the raw statement input and canonical execution
//! companion. Expected public values are independently re-derived for an
//! equality check; they are never guest inputs.

use clap::Parser;
use sp1_sdk::{
    blocking::{ProveRequest, Prover, ProverClient},
    include_elf, Elf, HashableKey, ProvingKey, SP1Stdin,
};

const REALIZED_EXECUTION_ELF: Elf = include_elf!("realized-execution-sp1-program");

#[derive(Parser, Debug)]
#[command(author, version, about)]
struct Args {
    /// Path to a realized-allocation-execution.v1 raw input document.
    #[arg(long)]
    input: String,
    /// Execute without generating a proof.
    #[arg(long)]
    execute: bool,
    /// Generate and locally verify an SP1 proof.
    #[arg(long)]
    prove: bool,
}

fn main() {
    sp1_sdk::utils::setup_logger();
    let args = Args::parse();
    if args.execute == args.prove {
        eprintln!("Error: specify exactly one of --execute or --prove");
        std::process::exit(1);
    }

    let input = std::fs::read(&args.input)
        .unwrap_or_else(|error| panic!("failed to read {}: {error}", args.input));
    let parsed: serde_json::Value = serde_json::from_slice(&input)
        .unwrap_or_else(|error| panic!("failed to parse {}: {error}", args.input));
    let expected = allocation_kernel::realized_execution::derive(&parsed)
        .unwrap_or_else(|(classification, reason)| {
            panic!("input rejected: {classification}: {reason}")
        })
        .public_values();

    let mut stdin = SP1Stdin::new();
    stdin.write_vec(input);
    let client = ProverClient::from_env();
    if args.execute {
        let (public_values, report) = client.execute(REALIZED_EXECUTION_ELF, stdin).run().unwrap();
        assert_eq!(public_values.as_slice(), expected.as_slice());
        println!("Guest public values == independently derived execution projection.");
        println!("Cycles: {}", report.total_instruction_count());
    } else {
        let pk = client
            .setup(REALIZED_EXECUTION_ELF)
            .expect("failed to setup ELF");
        let proof = client.prove(&pk, stdin).run().expect("failed to prove");
        client
            .verify(&proof, pk.verifying_key(), None)
            .expect("failed to verify proof");
        assert_eq!(proof.public_values.as_slice(), expected.as_slice());
        println!("Proof public values == independently derived execution projection.");
        println!("Program vkey: {}", pk.verifying_key().bytes32());
    }
}
