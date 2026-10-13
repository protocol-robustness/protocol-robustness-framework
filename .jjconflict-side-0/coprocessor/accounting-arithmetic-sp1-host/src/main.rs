//! Real SP1 proving and verification for the checked-i64 accounting statement.
//! The witness must come from PRF's validated native accounting execution path.

use clap::{Parser, ValueEnum};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sp1_sdk::{
    blocking::{ProveRequest, Prover, ProverClient},
    include_elf, Elf, HashableKey, ProvingKey, SP1ProofWithPublicValues, SP1Stdin,
};
use std::{fs, path::PathBuf};

const ELF: Elf = include_elf!("accounting-arithmetic-sp1-program");
const INPUT_SCHEMA: &str = "research-accounting-arithmetic-statement.v1";
const PUBLIC_VALUES_SCHEMA: &str = "accounting-arithmetic-roots-160.v1";
const PROGRAM_ID: &str = "accounting-arithmetic-sp1-program.v1";
const PROOF_ENCODING: &str = "sp1-sdk-bincode.v1";

#[derive(Clone, Copy, Debug, ValueEnum)]
enum Mode {
    Prove,
    Verify,
}

#[derive(Parser, Debug)]
struct Args {
    #[arg(long, value_enum)]
    mode: Mode,
    #[arg(long)]
    input: PathBuf,
    #[arg(long)]
    proof: PathBuf,
    #[arg(long)]
    metadata: PathBuf,
    #[arg(long)]
    verification: Option<PathBuf>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Metadata {
    schema: String,
    proof_program: String,
    proof_program_elf_sha256: String,
    proof_program_vkey: String,
    proof_system: String,
    proof_encoding: String,
    public_values_schema: String,
    public_values_hex: String,
    public_values_sha256: String,
    proof_sha256: String,
}

fn sha256_ref(bytes: &[u8]) -> String {
    format!("sha256:{:x}", Sha256::digest(bytes))
}

fn write_atomic(path: &PathBuf, bytes: &[u8]) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("create output directory: {e}"))?;
    }
    let temp = path.with_extension(format!("{}.tmp", std::process::id()));
    fs::write(&temp, bytes).map_err(|e| format!("write temporary artifact: {e}"))?;
    fs::rename(&temp, path).map_err(|e| format!("publish artifact: {e}"))
}

fn load_witness(path: &PathBuf, check_arithmetic: bool) -> Result<(Vec<u8>, Vec<u8>), String> {
    let bytes = fs::read(path).map_err(|e| format!("read arithmetic witness: {e}"))?;
    let value: serde_json::Value =
        serde_json::from_slice(&bytes).map_err(|e| format!("parse arithmetic witness: {e}"))?;
    if value.get("schema").and_then(serde_json::Value::as_str) != Some(INPUT_SCHEMA) {
        return Err("unsupported accounting arithmetic witness schema".into());
    }
    let public = (if check_arithmetic {
        allocation_kernel::accounting_arithmetic::derive_public_values(&value)
    } else {
        allocation_kernel::accounting_arithmetic::derive_public_commitments(&value)
    })
    .map_err(|e| format!("accounting witness rejected: {e}"))?;
    if public.len() != 160 {
        return Err("accounting arithmetic public values must be 160 bytes".into());
    }
    Ok((bytes, public))
}

fn reject_mock_prover() -> Result<(), String> {
    if std::env::var("SP1_PROVER").is_ok_and(|value| value.eq_ignore_ascii_case("mock")) {
        Err("SP1_PROVER=mock is forbidden for accounting proof artifacts".into())
    } else {
        Ok(())
    }
}

fn metadata_for(proof: &SP1ProofWithPublicValues, pk: &impl ProvingKey, bytes: &[u8]) -> Metadata {
    Metadata {
        schema: "research-accounting-arithmetic-sp1-proof-artifact.v1".into(),
        proof_program: PROGRAM_ID.into(),
        proof_program_elf_sha256: sha256_ref(&ELF),
        proof_program_vkey: pk.verifying_key().bytes32().to_string(),
        proof_system: "sp1-v6.3.1-core".into(),
        proof_encoding: PROOF_ENCODING.into(),
        public_values_schema: PUBLIC_VALUES_SCHEMA.into(),
        public_values_hex: format!("0x{}", hex::encode(proof.public_values.as_slice())),
        public_values_sha256: sha256_ref(proof.public_values.as_slice()),
        proof_sha256: sha256_ref(bytes),
    }
}

fn check_binding(
    proof: &SP1ProofWithPublicValues,
    expected: &[u8],
    metadata: &Metadata,
    proof_bytes: &[u8],
    pk: &impl ProvingKey,
) -> Result<(), String> {
    let mut mismatches = Vec::new();
    if metadata.schema != "research-accounting-arithmetic-sp1-proof-artifact.v1" {
        mismatches.push("artifact schema");
    }
    if metadata.proof_program != PROGRAM_ID {
        mismatches.push("program id");
    }
    if metadata.proof_program_elf_sha256 != sha256_ref(&ELF) {
        mismatches.push("ELF root");
    }
    if metadata.proof_program_vkey != pk.verifying_key().bytes32().to_string() {
        mismatches.push("VKey");
    }
    if metadata.proof_system != "sp1-v6.3.1-core" {
        mismatches.push("proof system");
    }
    if metadata.proof_encoding != PROOF_ENCODING {
        mismatches.push("proof encoding");
    }
    if metadata.public_values_schema != PUBLIC_VALUES_SCHEMA {
        mismatches.push("public-values schema");
    }
    if !mismatches.is_empty() {
        return Err(format!("accounting proof metadata mismatch: {}", mismatches.join(", ")));
    }
    if metadata.proof_sha256 != sha256_ref(proof_bytes)
        || metadata.public_values_sha256 != sha256_ref(proof.public_values.as_slice())
        || metadata.public_values_hex
            != format!("0x{}", hex::encode(proof.public_values.as_slice()))
        || proof.public_values.as_slice() != expected
    {
        return Err("accounting proof bytes or witness public values do not match".into());
    }
    Ok(())
}

fn run() -> Result<(), String> {
    let args = Args::parse();
    // Proving checks arithmetic in the host as defense in depth. Verification
    // only derives the expected commitments structurally; the SP1 proof is the
    // authority for the arithmetic relation and no serial fold is repeated.
    let (input_bytes, expected) = load_witness(&args.input, matches!(args.mode, Mode::Prove))?;
    reject_mock_prover()?;
    let client = ProverClient::from_env();
    let pk = client
        .setup(ELF)
        .map_err(|e| format!("SP1 setup failed: {e}"))?;
    match args.mode {
        Mode::Prove => {
            if args.verification.is_some() {
                return Err("--verification is only valid in verify mode".into());
            }
            let mut stdin = SP1Stdin::new();
            stdin.write_vec(input_bytes);
            let proof = client
                .prove(&pk, stdin)
                .run()
                .map_err(|e| format!("real accounting SP1 proof generation failed: {e}"))?;
            if proof.public_values.as_slice() != expected {
                return Err("SP1 guest public values differ from validated witness".into());
            }
            let bytes = bincode::serialize(&proof)
                .map_err(|e| format!("serialize SP1 proof envelope: {e}"))?;
            let metadata = metadata_for(&proof, &pk, &bytes);
            write_atomic(&args.proof, &bytes)?;
            write_atomic(
                &args.metadata,
                serde_json::to_string_pretty(&metadata)
                    .map_err(|e| format!("serialize proof metadata: {e}"))?
                    .as_bytes(),
            )?;
            println!("real accounting SP1 proof generated");
        }
        Mode::Verify => {
            let output = args
                .verification
                .as_ref()
                .ok_or("--verification is required in verify mode")?;
            let bytes = fs::read(&args.proof).map_err(|e| format!("read proof: {e}"))?;
            let metadata: Metadata = serde_json::from_slice(
                &fs::read(&args.metadata).map_err(|e| format!("read proof metadata: {e}"))?,
            )
            .map_err(|e| format!("parse proof metadata: {e}"))?;
            let proof: SP1ProofWithPublicValues =
                bincode::deserialize(&bytes).map_err(|e| format!("decode proof envelope: {e}"))?;
            check_binding(&proof, &expected, &metadata, &bytes, &pk)?;
            client
                .verify(&proof, pk.verifying_key(), None)
                .map_err(|e| format!("SP1 cryptographic verification failed: {e}"))?;
            let result = serde_json::json!({
                "schema": "research-accounting-arithmetic-sp1-verification.v1",
                "verification": "verified",
                "proof_sha256": metadata.proof_sha256,
                "public_values_sha256": metadata.public_values_sha256,
                "proof_program_elf_sha256": metadata.proof_program_elf_sha256,
                "proof_program_vkey": metadata.proof_program_vkey,
            });
            write_atomic(
                output,
                serde_json::to_string_pretty(&result)
                    .map_err(|e| format!("serialize verification result: {e}"))?
                    .as_bytes(),
            )?;
            println!("SP1 accounting proof verification succeeded");
        }
    }
    Ok(())
}

fn main() {
    sp1_sdk::utils::setup_logger();
    if let Err(error) = run() {
        eprintln!("accounting SP1 host rejected: {error}");
        std::process::exit(1);
    }
}
