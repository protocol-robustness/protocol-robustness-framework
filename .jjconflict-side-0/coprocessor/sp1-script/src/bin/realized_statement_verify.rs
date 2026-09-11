//! Independent verifier for a persisted realized-statement Core proof.
//!
//! This binary deliberately trusts neither proof-profile nor program identity
//! from the artifact.  It resolves the only currently supported profile to the
//! ELF compiled into this verifier, derives its verification key locally, loads
//! the persisted SDK envelope, and verifies the exact public-value bytes.
//!
//! It emits an *unsigned* verification decision. A deployment must combine
//! that decision with the strictly ingested artifact, wrap it in
//! `realized-allocation-proof-verification.v1`, and sign it with an independently
//! configured `:allocation-proof-verifier` key; this program never accepts or
//! stores signing keys.

use clap::Parser;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sp1_sdk::{
    blocking::{Prover, ProverClient},
    include_elf, Elf, HashableKey, ProvingKey, SP1ProofWithPublicValues,
};
use std::path::{Path, PathBuf};

const REALIZED_ELF: Elf = include_elf!("realized-statement-sp1-program");
const PROFILE: &str = "allocation-proof/largest-remainder-deferred-pro-rata.v1";
const PROGRAM_ID: &str = "realized-statement-sp1-program.v1";
const STATEMENT_SCHEMA: &str = "realized-allocation-statement.v1";
const PUBLIC_VALUES_SCHEMA: &str = "evm-bytes32-v1";
const PROOF_ENCODING: &str = "sp1-bincode.v1";

#[derive(Parser, Debug)]
#[command(author, version, about)]
struct Args {
    /// Persisted realized-allocation-proof.v1 JSON artifact.
    #[arg(long)]
    artifact: PathBuf,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Artifact {
    schema_version: String,
    proof_profile: String,
    statement_schema_version: String,
    statement_root: String,
    program_id: String,
    program_elf_sha256: String,
    program_vkey: String,
    public_values_schema: String,
    #[serde(rename = "public_values_bytes32")]
    _public_values_bytes32: Option<String>,
    public_values_sha256: String,
    proof_encoding: String,
    proof_file: String,
    proof_sha256: String,
    // Build provenance is not verification identity, but artifacts produced by
    // the reproducible prover carry these fields.
    #[serde(default, rename = "rustc_version")]
    _rustc_version: Option<String>,
    #[serde(default, rename = "cargo_lock_sha256")]
    _cargo_lock_sha256: Option<String>,
    #[serde(default, rename = "proof_artifact_hash")]
    _proof_artifact_hash: Option<String>,
}

#[derive(Serialize)]
struct VerificationDecision {
    verification_schema_version: String,
    verification_verdict: String,

    proof_profile: String,
    statement_root: String,
    program_id: String,
    program_elf_sha256: String,
    program_vkey: String,
    public_values_sha256: String,
    proof_sha256: String,
    verifier_id: String,
    verifier_version: String,
}

fn sha256_ref(bytes: &[u8]) -> String {
    format!("sha256:{:x}", Sha256::digest(bytes))
}

fn fail(message: &str) -> ! {
    eprintln!("verification rejected: {message}");
    std::process::exit(1)
}

fn single_sibling_path(artifact_path: &Path, name: &str) -> PathBuf {
    let candidate = Path::new(name);
    if candidate.file_name().and_then(|n| n.to_str()) != Some(name)
        || candidate.components().count() != 1
    {
        fail("proof_file must be a single sibling filename");
    }
    artifact_path
        .parent()
        .unwrap_or_else(|| fail("artifact has no parent"))
        .join(candidate)
}

fn expected_public_values(artifact: &Artifact) -> Vec<u8> {
    // With evm-bytes32-v1 schema, the committed public values are the raw
    // 32-byte statement root. Validate that the artifact's statement root
    // is a 32-byte hex value.
    let root_hex = if artifact.statement_root.starts_with("sha256:") {
        &artifact.statement_root[7..]
    } else {
        &artifact.statement_root
    };
    let root_bytes =
        hex::decode(root_hex).unwrap_or_else(|_| fail("statement_root is not valid hex"));
    if root_bytes.len() != 32 {
        fail("statement_root must be 32 bytes");
    }
    root_bytes
}

fn main() {
    sp1_sdk::utils::setup_logger();
    let args = Args::parse();
    let raw = std::fs::read(&args.artifact).unwrap_or_else(|_| fail("cannot read artifact"));
    let artifact: Artifact = serde_json::from_slice(&raw)
        .unwrap_or_else(|_| fail("malformed or unsupported artifact JSON"));

    // Registry resolution is local and fixed by this verifier binary. Never
    // derive expected profile/ELF/VK from caller-controlled artifact fields.
    let client = ProverClient::from_env();
    let pk = client
        .setup(REALIZED_ELF)
        .unwrap_or_else(|_| fail("cannot setup local approved ELF"));
    let expected_elf = sha256_ref(&REALIZED_ELF);
    let expected_vk = pk.verifying_key().bytes32().to_string();
    if artifact.schema_version != "realized-allocation-proof.v1"
        || artifact.proof_profile != PROFILE
        || artifact.statement_schema_version != STATEMENT_SCHEMA
        || artifact.program_id != PROGRAM_ID
        || artifact.program_elf_sha256 != expected_elf
        || artifact.program_vkey != expected_vk
        || artifact.public_values_schema != PUBLIC_VALUES_SCHEMA
        || artifact.proof_encoding != PROOF_ENCODING
    {
        fail("artifact identity is not approved for this verifier profile");
    }

    let expected_public = expected_public_values(&artifact);
    if sha256_ref(&expected_public) != artifact.public_values_sha256 {
        fail("public-values hash mismatch");
    }
    let proof_path = single_sibling_path(&args.artifact, &artifact.proof_file);
    let proof_bytes =
        std::fs::read(&proof_path).unwrap_or_else(|_| fail("cannot read sibling proof envelope"));
    if sha256_ref(&proof_bytes) != artifact.proof_sha256 {
        fail("persisted proof hash mismatch");
    }
    let proof = SP1ProofWithPublicValues::load(&proof_path)
        .unwrap_or_else(|_| fail("cannot decode persisted SP1 proof envelope"));
    if proof.public_values.as_slice() != expected_public.as_slice() {
        fail("persisted proof public values differ from artifact bytes");
    }
    client
        .verify(&proof, pk.verifying_key(), None)
        .unwrap_or_else(|_| fail("SP1 SDK verification failed"));

    let decision = VerificationDecision {
        verification_schema_version: "realized-allocation-proof-verification.v1".to_owned(),
        verification_verdict: "verified".to_owned(),
        proof_profile: PROFILE.to_owned(),
        statement_root: artifact.statement_root,
        program_id: PROGRAM_ID.to_owned(),
        program_elf_sha256: expected_elf,
        program_vkey: expected_vk,
        public_values_sha256: artifact.public_values_sha256,
        proof_sha256: artifact.proof_sha256,
        verifier_id: "realized-statement-sp1-sdk".to_owned(),
        verifier_version: env!("CARGO_PKG_VERSION").to_owned(),
    };
    println!(
        "{}",
        serde_json::to_string(&decision).expect("serialize decision")
    );
}
