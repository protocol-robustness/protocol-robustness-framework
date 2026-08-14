//! Generate an EVM-compatible SP1 proof for the allocation kernel and write a
//! Solidity test fixture.
//!
//! Usage:
//!   cargo run --release --bin allocation-evm -- --input <input.json> [--system groth16|plonk]
//!
//! Writes a fixture JSON into contracts/src/fixtures/ binding the vkey, the
//! committed public values, and the proof bytes.

use clap::{Parser, ValueEnum};
use serde::{Deserialize, Serialize};
use sp1_sdk::{
    blocking::{ProveRequest, Prover, ProverClient},
    include_elf, Elf, HashableKey, ProvingKey, SP1ProofWithPublicValues, SP1Stdin, SP1VerifyingKey,
};
use std::path::PathBuf;

/// The ELF for the allocation kernel SP1 guest.
const ALLOCATION_ELF: Elf = include_elf!("allocation-sp1-program");

#[derive(Parser, Debug)]
#[command(author, version, about)]
struct Args {
    /// Path to the canonical allocation input JSON document.
    #[arg(
        long,
        default_value = "../../scenarios/allocation/a-vs-b-plus-c/kernel-input.json"
    )]
    input: String,
    #[arg(long, value_enum, default_value = "groth16")]
    system: ProofSystem,
    /// Proof/public-values output fixture path.
    #[arg(
        long,
        default_value = "../contracts/allocation/src/fixtures/allocation-proof-fixture.json"
    )]
    fixture: String,
}

#[derive(Copy, Clone, PartialEq, Eq, PartialOrd, Ord, ValueEnum, Debug)]
enum ProofSystem {
    Plonk,
    Groth16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SP1AllocationProofFixture {
    vkey: String,
    public_values: String,
    proof: String,
    proof_system: String,
    kernel_version: String,
}

fn main() {
    sp1_sdk::utils::setup_logger();

    let args = Args::parse();

    let input_text = std::fs::read_to_string(&args.input)
        .unwrap_or_else(|e| panic!("failed to read input {}: {}", args.input, e));
    let input: serde_json::Value = serde_json::from_str(&input_text)
        .unwrap_or_else(|e| panic!("failed to parse input: {}", e));

    let mut stdin = SP1Stdin::new();
    stdin.write_vec(serde_json::to_vec(&input).expect("serialize input"));

    let client = ProverClient::from_env();
    let pk = client.setup(ALLOCATION_ELF).expect("failed to setup elf");

    println!("Proof System: {:?}", args.system);
    let proof = match args.system {
        ProofSystem::Plonk => client.prove(&pk, stdin).plonk().run(),
        ProofSystem::Groth16 => client.prove(&pk, stdin).groth16().run(),
    }
    .expect("failed to generate EVM-compatible proof");

    create_proof_fixture(&proof, pk.verifying_key(), args.system, &args.fixture);
}

fn create_proof_fixture(
    proof: &SP1ProofWithPublicValues,
    vk: &SP1VerifyingKey,
    system: ProofSystem,
    fixture_path: &str,
) {
    let fixture = SP1AllocationProofFixture {
        vkey: vk.bytes32().to_string(),
        public_values: format!("0x{}", hex::encode(proof.public_values.as_slice())),
        proof: format!("0x{}", hex::encode(proof.bytes())),
        proof_system: format!("{:?}", system).to_lowercase(),
        kernel_version: "allocation-kernel.v1".to_string(),
    };

    println!("Verification Key: {}", fixture.vkey);
    println!("Public Values: {}", fixture.public_values);
    println!("Proof Bytes: {}", fixture.proof);

    let path = PathBuf::from(fixture_path);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).expect("failed to create fixture dir");
    }
    std::fs::write(&path, serde_json::to_string_pretty(&fixture).unwrap())
        .expect("failed to write fixture");
    println!("Wrote fixture: {}", path.display());
}
