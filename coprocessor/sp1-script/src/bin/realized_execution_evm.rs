//! Generate an EVM-compatible Groth16 proof for the frozen
//! `realized-allocation-execution.v1` guest and write a Solidity fixture.
//!
//! The host independently re-derives the execution projection and requires both
//! it and the guest output to equal the pinned 384-byte conformance vector.
//!
//! The `--mock` flag uses the SP1 mock prover (instant) to produce a valid
//! proof envelope for the real vkey and public values. This is suitable for
//! tests that use MockSP1Verifier. Without `--mock`, a real Groth16/Plonk
//! proof is generated via CPU (may take minutes for large cycle counts).

use clap::Parser;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sp1_sdk::{
    blocking::{ProveRequest, Prover, ProverClient},
    include_elf, Elf, HashableKey, ProvingKey, SP1Stdin,
};
use std::path::PathBuf;

const REALIZED_EXECUTION_ELF: Elf = include_elf!("realized-execution-sp1-program");
const PINNED_PUBLIC_VALUES_HEX: &str = "d2196a7baaa9f298fe2fc5bc00454f9455487bb55672083690a7c856d55f33ebc22333a16df1c1efa352e9daab42ccbd78f4a1d7530ee3ed3cf7527ba62cbd812af0c75e9385b5b40631c6b05dcc9271b1398c96373899269d061beec929027f407fae5b4caeb20d77a030a74fa4e2659f7d75505ade3645c825da35ab98afa3000000000000000000000000444444444444444444444444444444444444444400000000000000000000000055555555555555555555555555555555555555550000000000000000000000000000000000000000000000000000000000000001aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa0000000000000000000000000000000000000000000000000000000000000064000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000000640000000000000000000000000000000000000000000000000000000000000000";

#[derive(Parser, Debug)]
#[command(author, version, about)]
struct Args {
    #[arg(long)]
    input: String,
    #[arg(long)]
    fixture: String,
    /// Print the deterministic ELF/VK identity without generating a proof.
    #[arg(long)]
    identity_only: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RealizedExecutionProofFixture {
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

    let input_text = std::fs::read_to_string(&args.input)
        .unwrap_or_else(|e| panic!("failed to read input {}: {}", args.input, e));
    let input: serde_json::Value = serde_json::from_str(&input_text)
        .unwrap_or_else(|e| panic!("failed to parse input: {}", e));

    let expected = allocation_kernel::realized_execution::derive(&input)
        .unwrap_or_else(|(classification, reason)| {
            panic!("input rejected: {classification}: {reason}")
        })
        .public_values();
    assert_eq!(hex::encode(&expected), PINNED_PUBLIC_VALUES_HEX);

    let mut stdin = SP1Stdin::new();
    stdin.write_vec(serde_json::to_vec(&input).expect("serialize input"));

    let client = ProverClient::from_env();
    let pk = client
        .setup(REALIZED_EXECUTION_ELF)
        .expect("failed to setup execution ELF");
    let vkey = pk.verifying_key().bytes32().to_string();
    let elf_bytes: &[u8] = &REALIZED_EXECUTION_ELF[..];
    let elf_hash = Sha256::digest(elf_bytes);
    if args.identity_only {
        println!("Verification Key: {vkey}");
        println!("ELF sha256: 0x{}", hex::encode(elf_hash));
        return;
    }

    let proof = client
        .prove(&pk, stdin)
        .groth16()
        .run()
        .expect("failed to generate EVM-compatible Groth16 proof");
    client
        .verify(&proof, pk.verifying_key(), None)
        .expect("failed to verify generated proof");

    let public_values = proof.public_values.as_slice();
    assert_eq!(public_values, expected.as_slice());
    assert_eq!(hex::encode(public_values), PINNED_PUBLIC_VALUES_HEX);
    let proof_bytes = proof.bytes();
    let fixture = RealizedExecutionProofFixture {
        vkey: vkey.clone(),
        public_values: format!("0x{}", hex::encode(public_values)),
        proof: format!("0x{}", hex::encode(&proof_bytes)),
        proof_system: "groth16".to_string(),
        program_id: "realized-execution-sp1-program.v1".to_string(),
        public_values_schema: "realized-allocation-execution-public-values.v1".to_string(),
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
