//! Real SP1 prover/verifier for the frozen observed-run statement.
//!
//! This command accepts the canonical witness JSON produced by the Clojure
//! `sp1-input` path. It never accepts caller-supplied public roots.

use clap::{Parser, ValueEnum};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use sp1_sdk::{
    blocking::{ProveRequest, Prover, ProverClient},
    include_elf, Elf, HashableKey, ProvingKey, SP1ProofWithPublicValues, SP1Stdin,
};
use std::{fs, path::PathBuf};

const ELF: Elf = include_elf!("observed-run-sp1-program");
const WRONG_ELF: Elf = include_elf!("allocation-sp1-program");
const PROGRAM_ID: &str = "observed-run-sp1-program.v1";
const INPUT_SCHEMA: &str = "research-observed-run-proof-statement.v1";
const PUBLIC_VALUES_SCHEMA: &str = "observed-run-roots-384.v1";
const PROOF_ENCODING: &str = "sp1-sdk-bincode.v1";

#[derive(Clone, Copy, Debug, ValueEnum)]
enum Mode {
    Prove,
    Verify,
}

#[derive(Parser, Debug)]
#[command(author, version, about)]
struct Args {
    #[arg(long, value_enum)]
    mode: Mode,
    /// Witness emitted by the authoritative Clojure sp1-input function.
    #[arg(long)]
    input: PathBuf,
    /// Content-addressed proof-envelope destination.
    #[arg(long)]
    proof: PathBuf,
    /// Metadata emitted only after successful proof generation.
    #[arg(long)]
    metadata: PathBuf,
    /// Verification outcome destination (written only after SDK verification).
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

fn load_witness(path: &PathBuf) -> Result<(Vec<u8>, Value, Vec<u8>), String> {
    let input_bytes = fs::read(path).map_err(|e| format!("read witness: {e}"))?;
    let input: Value =
        serde_json::from_slice(&input_bytes).map_err(|e| format!("parse witness: {e}"))?;
    if input.get("schema").and_then(Value::as_str) != Some(INPUT_SCHEMA) {
        return Err("unsupported observed-run proof witness schema".into());
    }
    let expected = observed_run_sp1_program::derive_public_values(&input)
        .map_err(|e| format!("source witness rejected: {e}"))?;
    Ok((input_bytes, input, expected))
}

fn reject_mock_prover() -> Result<(), String> {
    if std::env::var("SP1_PROVER").is_ok_and(|value| value.eq_ignore_ascii_case("mock")) {
        Err("SP1_PROVER=mock is forbidden for research proof artifacts".into())
    } else {
        Ok(())
    }
}

fn metadata_for(
    proof: &SP1ProofWithPublicValues,
    pk: &impl ProvingKey,
    proof_bytes: &[u8],
) -> Metadata {
    Metadata {
        schema: "research-observed-run-sp1-proof-artifact.v1".into(),
        proof_program: PROGRAM_ID.into(),
        proof_program_elf_sha256: sha256_ref(&ELF),
        proof_program_vkey: pk.verifying_key().bytes32().to_string(),
        proof_system: "sp1-v6.3.1-core".into(),
        proof_encoding: PROOF_ENCODING.into(),
        public_values_schema: PUBLIC_VALUES_SCHEMA.into(),
        public_values_hex: format!("0x{}", hex::encode(proof.public_values.as_slice())),
        public_values_sha256: sha256_ref(proof.public_values.as_slice()),
        proof_sha256: sha256_ref(proof_bytes),
    }
}

fn check_binding(
    proof: &SP1ProofWithPublicValues,
    expected: &[u8],
    metadata: &Metadata,
    proof_bytes: &[u8],
    pk: &impl ProvingKey,
) -> Result<(), String> {
    validate_artifact_binding(
        metadata,
        proof_bytes,
        proof.public_values.as_slice(),
        expected,
        &sha256_ref(&ELF),
        &pk.verifying_key().bytes32().to_string(),
    )
}

fn validate_artifact_binding(
    metadata: &Metadata,
    proof_bytes: &[u8],
    proof_public_values: &[u8],
    expected_public_values: &[u8],
    expected_elf_root: &str,
    expected_vkey: &str,
) -> Result<(), String> {
    if metadata.schema != "research-observed-run-sp1-proof-artifact.v1"
        || metadata.proof_program != PROGRAM_ID
        || metadata.proof_program_elf_sha256 != expected_elf_root
        || metadata.proof_program_vkey != expected_vkey
        || metadata.proof_system != "sp1-v6.3.1-core"
        || metadata.proof_encoding != PROOF_ENCODING
        || metadata.public_values_schema != PUBLIC_VALUES_SCHEMA
    {
        return Err("proof program, ELF, VKey, system, or schema substitution".into());
    }
    if metadata.proof_sha256 != sha256_ref(proof_bytes) {
        return Err("proof artifact digest mismatch".into());
    }
    if metadata.public_values_sha256 != sha256_ref(proof_public_values)
        || metadata.public_values_hex != format!("0x{}", hex::encode(proof_public_values))
    {
        return Err("public-values metadata mismatch".into());
    }
    if proof_public_values != expected_public_values {
        return Err("proof public values do not match this canonical observed-run witness".into());
    }
    Ok(())
}

fn require_rejection(result: Result<(), impl std::fmt::Debug>, case: &str) -> Result<(), String> {
    if result.is_ok() {
        Err(format!(
            "negative verification unexpectedly accepted: {case}"
        ))
    } else {
        Ok(())
    }
}

fn run_negative_verification_checks(
    client: &impl Prover,
    proof: &SP1ProofWithPublicValues,
    proof_bytes: &[u8],
    pk: &impl ProvingKey,
    expected: &[u8],
    metadata: &Metadata,
) -> Result<(), String> {
    let mut corrupt = proof_bytes.to_vec();
    if corrupt.is_empty() {
        return Err("empty serialized proof cannot be used for tamper test".into());
    }
    corrupt[0] ^= 1;
    match bincode::deserialize::<SP1ProofWithPublicValues>(&corrupt) {
        Err(_) => {}
        Ok(corrupted_proof) => require_rejection(
            client.verify(&corrupted_proof, pk.verifying_key(), None),
            "wrong proof bytes",
        )?,
    }

    let mut changed_values = proof.public_values.to_vec();
    if changed_values.is_empty() {
        return Err("proof public values are empty".into());
    }
    changed_values[0] ^= 1;
    let altered_values_proof = SP1ProofWithPublicValues::new(
        proof.proof.clone(),
        sp1_sdk::SP1PublicValues::from(&changed_values),
        proof.sp1_version.clone(),
    );
    require_rejection(
        client.verify(&altered_values_proof, pk.verifying_key(), None),
        "modified public values",
    )?;

    let wrong_pk = client
        .setup(WRONG_ELF)
        .map_err(|e| format!("setup wrong-program negative verifier: {e}"))?;
    require_rejection(
        client.verify(proof, wrong_pk.verifying_key(), None),
        "wrong program/VKey",
    )?;

    let mut other_run_values = expected.to_vec();
    other_run_values[0] ^= 1;
    require_rejection(
        validate_artifact_binding(
            metadata,
            proof_bytes,
            proof.public_values.as_slice(),
            &other_run_values,
            &sha256_ref(&ELF),
            &pk.verifying_key().bytes32().to_string(),
        ),
        "cross-run public-value substitution",
    )?;
    Ok(())
}

fn run() -> Result<(), String> {
    let args = Args::parse();
    let (input_bytes, _input, expected) = load_witness(&args.input)?;
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
                .map_err(|e| format!("real SP1 proof generation failed: {e}"))?;
            if proof.public_values.as_slice() != expected.as_slice() {
                return Err(
                    "SP1 guest public values differ from the guest-derived witness projection"
                        .into(),
                );
            }
            let proof_bytes = bincode::serialize(&proof)
                .map_err(|e| format!("serialize SP1 proof envelope: {e}"))?;
            let metadata = metadata_for(&proof, &pk, &proof_bytes);
            write_atomic(&args.proof, &proof_bytes)?;
            write_atomic(
                &args.metadata,
                serde_json::to_string_pretty(&metadata)
                    .map_err(|e| format!("serialize proof metadata: {e}"))?
                    .as_bytes(),
            )?;
            println!("proof generated; public values match canonical witness");
        }
        Mode::Verify => {
            let verification_path = args
                .verification
                .as_ref()
                .ok_or("--verification is required in verify mode")?;
            let proof_bytes =
                fs::read(&args.proof).map_err(|e| format!("read proof artifact: {e}"))?;
            let metadata_bytes =
                fs::read(&args.metadata).map_err(|e| format!("read proof metadata: {e}"))?;
            let metadata: Metadata = serde_json::from_slice(&metadata_bytes)
                .map_err(|e| format!("parse proof metadata: {e}"))?;
            let proof: SP1ProofWithPublicValues = bincode::deserialize(&proof_bytes)
                .map_err(|e| format!("decode SP1 proof envelope: {e}"))?;
            check_binding(&proof, &expected, &metadata, &proof_bytes, &pk)?;
            client
                .verify(&proof, pk.verifying_key(), None)
                .map_err(|e| format!("SP1 cryptographic verification failed: {e}"))?;
            run_negative_verification_checks(
                &client,
                &proof,
                &proof_bytes,
                &pk,
                &expected,
                &metadata,
            )?;
            let verification = serde_json::json!({
                "schema": "research-observed-run-sp1-verification.v1",
                "verification": "verified",
                "proof_sha256": metadata.proof_sha256,
                "public_values_sha256": metadata.public_values_sha256,
                "proof_program_elf_sha256": metadata.proof_program_elf_sha256,
                "proof_program_vkey": metadata.proof_program_vkey,
            });
            write_atomic(
                verification_path,
                serde_json::to_string_pretty(&verification)
                    .map_err(|e| format!("serialize verification outcome: {e}"))?
                    .as_bytes(),
            )?;
            println!("SP1 SDK cryptographic verification succeeded");
        }
    }
    Ok(())
}

fn main() {
    sp1_sdk::utils::setup_logger();
    if let Err(error) = run() {
        eprintln!("observed-run SP1 host rejected: {error}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> (Metadata, Vec<u8>, Vec<u8>) {
        let proof = b"actual-proof-envelope".to_vec();
        let public_values = vec![7u8; 384];
        let metadata = Metadata {
            schema: "research-observed-run-sp1-proof-artifact.v1".into(),
            proof_program: PROGRAM_ID.into(),
            proof_program_elf_sha256: "sha256:elf".into(),
            proof_program_vkey: "vkey-1".into(),
            proof_system: "sp1-v6.3.1-core".into(),
            proof_encoding: PROOF_ENCODING.into(),
            public_values_schema: PUBLIC_VALUES_SCHEMA.into(),
            public_values_hex: format!("0x{}", hex::encode(&public_values)),
            public_values_sha256: sha256_ref(&public_values),
            proof_sha256: sha256_ref(&proof),
        };
        (metadata, proof, public_values)
    }

    #[test]
    fn rejects_tampered_proof_public_values_program_vkey_and_cross_run() {
        let (metadata, proof, public_values) = sample();
        let valid = |metadata: &Metadata, proof: &[u8], actual: &[u8], expected: &[u8]| {
            validate_artifact_binding(metadata, proof, actual, expected, "sha256:elf", "vkey-1")
                .is_ok()
        };
        assert!(valid(&metadata, &proof, &public_values, &public_values));

        let mut wrong_proof = proof.clone();
        wrong_proof[0] ^= 1;
        assert!(!valid(
            &metadata,
            &wrong_proof,
            &public_values,
            &public_values
        ));

        let mut wrong_public_values = public_values.clone();
        wrong_public_values[0] ^= 1;
        assert!(!valid(
            &metadata,
            &proof,
            &wrong_public_values,
            &public_values
        ));

        let mut wrong_program = metadata.clone();
        wrong_program.proof_program_elf_sha256 = "sha256:other-elf".into();
        assert!(!valid(
            &wrong_program,
            &proof,
            &public_values,
            &public_values
        ));

        let mut wrong_vkey = metadata.clone();
        wrong_vkey.proof_program_vkey = "vkey-2".into();
        assert!(!valid(&wrong_vkey, &proof, &public_values, &public_values));

        let mut another_run_values = public_values.clone();
        another_run_values[31] ^= 1;
        assert!(!valid(
            &metadata,
            &proof,
            &public_values,
            &another_run_values
        ));
    }
}
