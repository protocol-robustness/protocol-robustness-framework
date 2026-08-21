//! SP1 host script for the realized-allocation-statement guest (thin wrapper).
//!
//! Generates and verifies a proof of the realized-statement SP1 program, and
//! checks the equality:
//!
//!     native Rust statement root == SP1 guest public values (bytes32)
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
    public_values_bytes32: String,
    public_values_sha256: String,
    proof_encoding: String,
    proof_file: String,
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
    statement_root_hex: &str,
) {
    let proof_path = path.with_extension("sp1-proof.bin");
    if let Some(parent) = proof_path.parent() {
        std::fs::create_dir_all(parent).expect("create proof directory");
    }
    // `SP1ProofWithPublicValues::bytes()` is deliberately restricted to
    // Groth16/Plonk EVM encodings. Core proofs are persisted as the SDK's
    // versioned bincode envelope, which an off-chain verifier loads and
    // verifies through the SP1 SDK.
    let proof_bytes = bincode::serialize(proof).expect("serialize SP1 core proof envelope");
    std::fs::write(&proof_path, &proof_bytes).expect("write SP1 proof envelope");
    let artifact = RealizedStatementProofArtifact {
        schema_version: "realized-allocation-proof.v1".to_owned(),
        proof_profile: "allocation-proof/largest-remainder-deferred-pro-rata.v1".to_owned(),
        statement_schema_version: "realized-allocation-statement.v1".to_owned(),
        statement_root: statement_root_hex.to_owned(),
        program_id: "realized-statement-sp1-program.v1".to_owned(),
        program_elf_sha256: sha256_ref(&REALIZED_ELF),
        program_vkey: pk.verifying_key().bytes32().to_string(),
        public_values_schema: "evm-bytes32-v1".to_owned(),
        public_values_bytes32: format!("0x{}", hex::encode(&proof.public_values)),
        public_values_sha256: sha256_ref(proof.public_values.as_slice()),
        proof_encoding: "sp1-bincode.v1".to_owned(),
        proof_file: proof_path
            .file_name()
            .expect("proof filename")
            .to_string_lossy()
            .into_owned(),
        proof_sha256: sha256_ref(&proof_bytes),
        rustc_version: command_stdout("rustc", &["--version"]),
        cargo_lock_sha256: sha256_ref(
            &std::fs::read(
                PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                    .parent()
                    .expect("sp1-script has coprocessor parent")
                    .join("Cargo.lock"),
            )
            .expect("read coprocessor Cargo.lock"),
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
    // the host to compute the expected statement root.
    let native_statement_root = {
        let result = allocation_kernel::realized_statement_io::run_realized_statement(&input);
        result["statement-root"]
            .as_str()
            .expect("statement root from native computation")
            .to_owned()
    };
    let native_root_bytes =
        hex::decode(&native_statement_root).expect("statement root must be valid hex");
    let native_root_bytes32: [u8; 32] = native_root_bytes
        .as_slice()
        .try_into()
        .expect("statement root must be 32 bytes");

    let client = ProverClient::from_env();

    if args.execute {
        let (public_values, report) = client.execute(REALIZED_ELF, stdin).run().unwrap();
        println!("Program executed successfully.");
        println!("Cycles: {}", report.total_instruction_count());
        assert_eq!(public_values.as_slice(), &native_root_bytes32[..]);
        println!("Guest public values == native statement root (execute).");
    } else {
        let pk = client.setup(REALIZED_ELF).expect("failed to setup elf");
        let proof = client.prove(&pk, stdin).run().expect("failed to prove");
        client
            .verify(&proof, pk.verifying_key(), None)
            .expect("failed to verify proof");
        println!("Successfully generated and verified proof!");

        assert_eq!(proof.public_values.as_slice(), &native_root_bytes32[..]);
        println!("Guest public values == native statement root (prove).");
        println!("Program vkey: {}", pk.verifying_key().bytes32());
        write_artifact(
            args.artifact.as_ref().expect("artifact required"),
            &proof,
            &pk,
            &native_statement_root,
        );
    }
}
