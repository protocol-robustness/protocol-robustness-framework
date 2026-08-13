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
use serde::Serialize;
use serde_json::Value;
use sha2::{Digest, Sha256};
use sp1_sdk::{
    blocking::{ProveRequest, Prover, ProverClient},
    include_elf, Elf, HashableKey, ProvingKey, SP1Stdin,
};
use std::path::PathBuf;

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
    /// Persist the locally verified proof/public-values artifact. Required in
    /// --prove mode so proof evidence is never silently discarded.
    #[arg(long)]
    artifact: Option<PathBuf>,
}

#[derive(Serialize)]
struct RealizedStatementProofArtifact {
    schema_version: String,
    proof_profile: String,
    statement_schema_version: String,
    statement_root: String,
    program_id: String,
    program_elf_sha256: String,
    program_vkey: String,
    public_values_schema: String,
    public_values_utf8_json: String,
    public_values_sha256: String,
    proof_bytes_hex: String,
    proof_sha256: String,
    rustc_version: String,
    cargo_lock_sha256: String,
}

fn sha256_ref(bytes: &[u8]) -> String {
    format!("sha256:{:x}", Sha256::digest(bytes))
}

fn command_stdout(program: &str, args: &[&str]) -> String {
    std::process::Command::new(program)
        .args(args)
        .output()
        .ok()
        .filter(|output| output.status.success())
        .and_then(|output| String::from_utf8(output.stdout).ok())
        .map(|value| value.trim().to_owned())
        .unwrap_or_else(|| "unavailable".to_owned())
}

fn write_artifact(
    path: &PathBuf,
    proof: &sp1_sdk::SP1ProofWithPublicValues,
    pk: &impl ProvingKey,
    native: &[u8],
) {
    let public: Value = serde_json::from_slice(native).expect("native public JSON");
    let artifact = RealizedStatementProofArtifact {
        schema_version: "realized-allocation-proof.v1".to_owned(),
        proof_profile: "allocation-proof/largest-remainder-deferred-pro-rata.v1".to_owned(),
        statement_schema_version: public["schema-version"]
            .as_str()
            .expect("statement schema")
            .to_owned(),
        statement_root: public["statement-root"]
            .as_str()
            .expect("statement root")
            .to_owned(),
        program_id: "realized-statement-sp1-program.v1".to_owned(),
        program_elf_sha256: sha256_ref(&REALIZED_ELF),
        program_vkey: pk.verifying_key().bytes32().to_string(),
        public_values_schema: "utf8-json-v1".to_owned(),
        public_values_utf8_json: String::from_utf8(native.to_vec()).expect("UTF-8 public values"),
        public_values_sha256: sha256_ref(native),
        proof_bytes_hex: format!("0x{}", hex::encode(proof.bytes())),
        proof_sha256: sha256_ref(&proof.bytes()),
        rustc_version: command_stdout("rustc", &["--version"]),
        cargo_lock_sha256: sha256_ref(
            &std::fs::read("../Cargo.lock").expect("read coprocessor Cargo.lock"),
        ),
    };
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).expect("create artifact directory");
    }
    std::fs::write(
        path,
        serde_json::to_string_pretty(&artifact).expect("serialize artifact"),
    )
    .expect("write proof artifact");
    println!("Wrote locally verified proof artifact: {}", path.display());
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
    if args.prove && args.artifact.is_none() {
        eprintln!("Error: --artifact is required with --prove");
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
        write_artifact(
            args.artifact.as_ref().expect("artifact required"),
            &proof,
            &pk,
            &native,
        );
    }
}
