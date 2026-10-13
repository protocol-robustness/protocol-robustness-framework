//! Finish the existing SP1 Groth16 path from its retained BN254 wrapper witness.
//! Uses SDK FFI and SDK cryptographic verification; no alternative circuit or encoder.
use clap::Parser;
use sha2::{Digest, Sha256};
use sp1_recursion_gnark_ffi::{ffi::prove_groth16_bn254, Groth16Bn254Prover};
use sp1_sdk::{
    blocking::{Prover, ProverClient},
    include_elf, Elf, HashableKey, ProvingKey, SP1Proof, SP1ProofWithPublicValues, SP1PublicValues,
    SP1_CIRCUIT_VERSION,
};
use std::path::PathBuf;
const ELF: Elf = include_elf!("controlled-entitlement-source-sp1-program");
#[derive(Parser)]
struct Args {
    #[arg(long)]
    input: PathBuf,
    #[arg(long)]
    wrapper_witness: PathBuf,
    #[arg(long)]
    groth16_artifacts: PathBuf,
    #[arg(long)]
    fixture: PathBuf,
}
fn main() {
    sp1_sdk::utils::setup_logger();
    let args = Args::parse();
    let input_bytes = std::fs::read(&args.input).expect("read controlled source");
    let input = serde_json::from_slice(&input_bytes).expect("parse controlled source");
    let expected = allocation_kernel::controlled_entitlement_source::derive(&input)
        .expect("controlled source rejected")
        .public_values
        .encode();
    assert_eq!(expected.len(), 288);
    let values = SP1PublicValues::from(expected.as_slice());
    let client = ProverClient::builder().light().build();
    let pk = client.setup(ELF).expect("derive actual guest identity");
    let wrapper_bytes = std::fs::read(&args.wrapper_witness).expect("read SDK wrapper witness");
    let wrapper: serde_json::Value =
        serde_json::from_slice(&wrapper_bytes).expect("parse SDK wrapper witness");
    assert_eq!(
        wrapper["vkey_hash"].as_str(),
        Some(
            num_bigint::BigUint::parse_bytes(
                pk.verifying_key()
                    .bytes32()
                    .to_string()
                    .trim_start_matches("0x")
                    .as_bytes(),
                16
            )
            .unwrap()
            .to_string()
            .as_str()
        ),
        "wrapper program mismatch"
    );
    assert_eq!(
        wrapper["committed_values_digest"].as_str(),
        Some(values.hash_bn254().to_string().as_str()),
        "wrapper public values mismatch"
    );
    assert_eq!(
        wrapper["exit_code"].as_str(),
        Some("0"),
        "unsuccessful guest"
    );
    assert_eq!(
        wrapper["proof_nonce"].as_str(),
        Some("0"),
        "unexpected proof nonce for this controlled fixture"
    );
    println!("SDK wrapper matches actual guest VKey and all 288 public bytes; exit/nonce zero");
    // The same FFI entrypoint called by Groth16Bn254Prover::prove in SDK 6.3.1.
    // Its temporary witness is already retained, so no SP1 trace caches are needed here.
    let mut raw = prove_groth16_bn254(
        args.groth16_artifacts.to_str().unwrap(),
        args.wrapper_witness.to_str().unwrap(),
    );
    raw.groth16_vkey_hash = Groth16Bn254Prover::get_vkey_hash(&args.groth16_artifacts);
    let proof = SP1ProofWithPublicValues::new(
        SP1Proof::Groth16(raw),
        values,
        SP1_CIRCUIT_VERSION.to_owned(),
    );
    client
        .verify(&proof, pk.verifying_key(), None)
        .expect("real SDK cryptographic verification of completed Groth16 proof");
    let fixture = serde_json::json!({
        "program_id": allocation_kernel::controlled_entitlement_source::PROGRAM_ID,
        "proof_system": "groth16", "vkey": pk.verifying_key().bytes32().to_string(),
        "elf_sha256": format!("0x{}",hex::encode(Sha256::digest(&ELF[..]))),
        "public_values_schema": allocation_kernel::source_bound_entitlement::PUBLIC_VALUES_SCHEMA,
        "public_values_length": expected.len(), "public_values": format!("0x{}",hex::encode(&expected)),
        "source": {"kind":"raw-witness", "witness_sha256":format!("0x{}",hex::encode(Sha256::digest(&input_bytes))),
            "wrapper_witness_sha256":format!("0x{}",hex::encode(Sha256::digest(&wrapper_bytes))),
            "continuation":"SDK 6.3.1 native Groth16 FFI; actual SDK cryptographic verification"},
        "proof": format!("0x{}",hex::encode(proof.bytes()))
    });
    if let Some(parent) = args.fixture.parent() {
        std::fs::create_dir_all(parent).unwrap();
    }
    std::fs::write(&args.fixture, serde_json::to_vec_pretty(&fixture).unwrap())
        .expect("write verified fixture");
    println!(
        "Real Groth16 proof completed and SDK verified: {}",
        args.fixture.display()
    );
}
