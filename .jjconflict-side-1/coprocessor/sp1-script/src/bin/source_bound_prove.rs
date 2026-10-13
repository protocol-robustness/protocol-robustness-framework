//! Generate an SP1 Groth16 proof for the source-bound Shortfall witness.
use clap::Parser;
use serde::Serialize;
use sha2::{Digest, Sha256};
use sp1_sdk::{
    blocking::{ProveRequest, Prover, ProverClient},
    include_elf, HashableKey, ProvingKey, SP1Stdin,
};

const ELF: sp1_sdk::Elf = include_elf!("source-bound-sp1-program");

#[derive(Parser)]
struct Args {
    #[arg(long)]
    input: String,
    #[arg(long)]
    fixture: String,
    #[arg(long)]
    identity_only: bool,
}
#[derive(Serialize)]
struct Fixture {
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
    let bytes = std::fs::read(&args.input).expect("read input");
    let input: serde_json::Value = serde_json::from_slice(&bytes).expect("parse input");
    let expected = allocation_kernel::source_bound::derive(&input)
        .expect("input rejected")
        .public_values();
    assert_eq!(expected.len(), 160);
    let client = ProverClient::from_env();
    let pk = client.setup(ELF).expect("setup ELF");
    let vkey = pk.verifying_key().bytes32().to_string();
    let elf_hash = Sha256::digest(&ELF[..]);
    if args.identity_only {
        println!("Verification Key: {vkey}");
        println!("ELF sha256: 0x{}", hex::encode(elf_hash));
        return;
    }
    let mut stdin = SP1Stdin::new();
    stdin.write_vec(bytes);
    let proof = client
        .prove(&pk, stdin)
        .groth16()
        .run()
        .expect("generate proof");
    client
        .verify(&proof, pk.verifying_key(), None)
        .expect("verify proof");
    assert_eq!(proof.public_values.as_slice(), expected.as_slice());
    let fixture = Fixture {
        vkey,
        public_values: format!("0x{}", hex::encode(&expected)),
        proof: format!("0x{}", hex::encode(proof.bytes())),
        proof_system: "groth16".into(),
        program_id: "source-bound-sp1-program.v1".into(),
        public_values_schema: "prf/onchain-shortfall-allocation-binding-public-values.v1".into(),
        elf_sha256: format!("0x{}", hex::encode(elf_hash)),
    };
    let path = std::path::PathBuf::from(args.fixture);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).unwrap();
    }
    std::fs::write(&path, serde_json::to_string_pretty(&fixture).unwrap()).unwrap();
    println!("Wrote fixture: {}", path.display());
}
