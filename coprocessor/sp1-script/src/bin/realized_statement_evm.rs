//! Generate an EVM-compatible SP1 proof for the realized-allocation-statement guest
//! and write a Solidity test fixture.
//!
//! Usage:
//!   cargo run --release --bin realized-statement-evm -- \
//!     --input <realized-statement-input.json> [--system groth16|plonk] [--mock] \
//!     --fixture <output-fixture.json>
//!
//! Writes a fixture JSON binding the vkey, committed public values (UTF-8 JSON
//! from the SP1 guest), and the EVM-compatible proof bytes.
//!
//! The `--mock` flag uses the SP1 mock prover (instant) to produce a valid
//! proof envelope for the real vkey and public values. This is suitable for
//! tests that use MockSP1Verifier. Without `--mock`, a real Groth16/Plonk
//! proof is generated via CPU (may take minutes for large cycle counts).

use clap::{Parser, ValueEnum};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sp1_sdk::{
    blocking::{Prover, ProverClient, ProveRequest},
    include_elf, Elf, HashableKey, ProvingKey, SP1ProofWithPublicValues, SP1Stdin,
};
use std::path::PathBuf;

const REALIZED_ELF: Elf = include_elf!("realized-statement-sp1-program");

fn sha256_ref(bytes: &[u8]) -> String {
    format!("sha256:{:x}", Sha256::digest(bytes))
}

#[derive(Parser, Debug)]
#[command(author, version, about)]
struct Args {
    #[arg(
        long,
        default_value = "../../scenarios/allocation/a-vs-b-plus-c/realized-statement-input.json"
    )]
    input: String,
    #[arg(long, value_enum, default_value = "groth16")]
    system: ProofSystem,
    #[arg(long, default_value = "../../sew-prf-genesis-solidity/src/fixtures/realized-statement-proof-fixture.json")]
    fixture: String,
    #[arg(long)]
    mock: bool,
}

#[derive(Copy, Clone, PartialEq, Eq, PartialOrd, Ord, ValueEnum, Debug)]
enum ProofSystem {
    Plonk,
    Groth16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RealizedStatementProofFixture {
    vkey: String,
    public_values: String,
    proof: String,
    proof_system: String,
    program_id: String,
    public_values_schema: String,
    elf_sha256: String,
}

fn main() {
    sp1_sdk::utils::setup_logger();

    let args = Args::parse();

    if args.mock {
        std::env::set_var("SP1_PROVER", "mock");
    }

    let input_text = std::fs::read_to_string(&args.input)
        .unwrap_or_else(|e| panic!("failed to read input {}: {}", args.input, e));
    let input: serde_json::Value = serde_json::from_str(&input_text)
        .unwrap_or_else(|e| panic!("failed to parse input: {}", e));

    let mut stdin = SP1Stdin::new();
    stdin.write_vec(serde_json::to_vec(&input).expect("serialize input"));

    let client = ProverClient::from_env();
    let pk = client.setup(REALIZED_ELF).expect("failed to setup elf");
    let vkey = pk.verifying_key().bytes32().to_string();

    println!("Proof System: {:?} (mock={})", args.system, args.mock);
    let proof = match args.system {
        ProofSystem::Plonk => client.prove(&pk, stdin).plonk().run(),
        ProofSystem::Groth16 => client.prove(&pk, stdin).groth16().run(),
    }
    .expect("failed to generate EVM-compatible proof");

    let public_values = proof.public_values.as_slice();
    let proof_bytes = proof.bytes();
    let elf_bytes: &[u8] = &REALIZED_ELF[..];
    let elf_hash = Sha256::digest(elf_bytes);

    let fixture = RealizedStatementProofFixture {
        vkey: vkey.clone(),
        public_values: format!("0x{}", hex::encode(public_values)),
        proof: format!("0x{}", hex::encode(&proof_bytes)),
        proof_system: if args.mock {
            format!("mock-{:?}", args.system).to_lowercase()
        } else {
            format!("{:?}", args.system).to_lowercase()
        },
        program_id: "realized-statement-sp1-program.v1".to_string(),
        public_values_schema: "evm-bytes32-v1".to_string(),
        elf_sha256: format!("0x{}", hex::encode(elf_hash)),
    };

    println!("Verification Key: {}", fixture.vkey);
    println!("Public Values: {}", fixture.public_values);
    println!("Proof Bytes: {}", fixture.proof);
    println!("ELF sha256: {}", fixture.elf_sha256);

    let path = PathBuf::from(&args.fixture);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).expect("failed to create fixture dir");
    }
    std::fs::write(&path, serde_json::to_string_pretty(&fixture).unwrap())
        .expect("failed to write fixture");
    println!("Wrote fixture: {}", path.display());
}
