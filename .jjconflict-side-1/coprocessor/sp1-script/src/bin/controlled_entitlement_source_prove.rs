//! Execute or generate an EVM-compatible proof for the controlled-source
//! entitlement profile. Input must already commit the derived source root in
//! `prepared-operation.policy-root`; this CLI never edits witness authority.

use clap::Parser;
use sha2::{Digest, Sha256};
use sp1_sdk::{
    blocking::{ProveRequest, Prover, ProverClient},
    include_elf, Elf, HashableKey, ProvingKey, SP1Proof, SP1ProofMode, SP1ProofWithPublicValues,
    SP1Stdin,
};

const ELF: Elf = include_elf!("controlled-entitlement-source-sp1-program");
const PROGRAM_ID: &str = allocation_kernel::controlled_entitlement_source::PROGRAM_ID;

#[derive(Parser, Debug)]
struct Args {
    /// Path to a raw witness whose prepared operation commits to its source root.
    #[arg(long)]
    input: String,
    /// Write an EVM proof fixture after Groth16 generation and SDK verification.
    #[arg(long, requires = "prove")]
    fixture: Option<String>,
    /// Execute only; does not set up proving keys.
    #[arg(long, conflicts_with = "prove")]
    execute: bool,
    /// Generate, verify, and serialize a Groth16 EVM proof.
    #[arg(long, conflicts_with = "execute")]
    prove: bool,
    /// Print candidate program identity (requires SP1 setup, no proof).
    #[arg(long, conflicts_with_all = ["execute", "prove"])]
    identity_only: bool,
}

fn main() {
    sp1_sdk::utils::setup_logger();
    let args = Args::parse();
    if !args.identity_only && !args.execute && !args.prove {
        panic!("specify exactly one of --execute, --prove, or --identity-only");
    }

    let input_bytes = std::fs::read(&args.input).expect("read controlled entitlement witness");
    let input: serde_json::Value =
        serde_json::from_slice(&input_bytes).expect("parse controlled entitlement witness");
    let expected = allocation_kernel::controlled_entitlement_source::derive(&input)
        .unwrap_or_else(|reason| panic!("controlled witness rejected: {reason}"))
        .public_values
        .encode();
    let mut stdin = SP1Stdin::new();
    stdin.write_vec(input_bytes.clone());

    if args.execute {
        let client = ProverClient::builder().light().build();
        let (public_values, report) = client
            .execute(ELF, stdin)
            .run()
            .expect("execute controlled entitlement guest");
        assert_eq!(public_values.as_slice(), expected.as_slice());
        assert_eq!(expected.len(), 288);
        println!("controlled guest output matches core (288 bytes)");
        println!("cycles: {}", report.total_instruction_count());
        return;
    }

    let client = ProverClient::from_env();
    let pk = client.setup(ELF).expect("setup controlled entitlement ELF");
    let vkey = pk.verifying_key().bytes32().to_string();
    let elf_sha256 = format!("0x{}", hex::encode(Sha256::digest(&ELF[..])));
    if args.identity_only {
        println!("program_id: {PROGRAM_ID}");
        println!("vkey: {vkey}");
        println!("elf_sha256: {elf_sha256}");
        return;
    }

    let proof = client
        .prove(&pk, stdin)
        .groth16()
        .run()
        .expect("generate controlled entitlement Groth16 proof");
    client
        .verify(&proof, pk.verifying_key(), None)
        .expect("verify controlled entitlement proof");
    assert_eq!(proof.public_values.as_slice(), expected.as_slice());
    assert_eq!(expected.len(), 288);
    let proof_system = require_groth16(&proof).unwrap_or_else(|reason| panic!("{reason}"));
    let proof_bytes = proof.bytes();
    println!("proof system: {proof_system}");
    println!("public values: 288 bytes; match controlled Rust derivation");
    println!("program_id: {PROGRAM_ID}");
    println!("vkey: {vkey}");
    println!("elf_sha256: {elf_sha256}");
    if let Some(path) = args.fixture {
        let fixture = serde_json::json!({
            "program_id": PROGRAM_ID,
            "proof_system": proof_system,
            "vkey": vkey,
            "elf_sha256": elf_sha256,
            "public_values_schema": allocation_kernel::source_bound_entitlement::PUBLIC_VALUES_SCHEMA,
            "public_values_length": expected.len(),
            "public_values": format!("0x{}", hex::encode(&expected)),
            "source": {
                "kind": "raw-witness",
                "witness_sha256": format!("0x{}", hex::encode(Sha256::digest(&input_bytes)))
            },
            "proof": format!("0x{}", hex::encode(&proof_bytes))
        });
        if let Some(parent) = std::path::Path::new(&path).parent() {
            std::fs::create_dir_all(parent).expect("create proof fixture directory");
        }
        std::fs::write(&path, serde_json::to_vec_pretty(&fixture).unwrap())
            .expect("write controlled entitlement fixture");
        println!("proof_bytes: {}", proof_bytes.len());
        println!(
            "proof_sha256: 0x{}",
            hex::encode(Sha256::digest(&proof_bytes))
        );
        println!("fixture: {path}");
    }
}

fn require_groth16(proof: &SP1ProofWithPublicValues) -> Result<&'static str, String> {
    let mode = match &proof.proof {
        SP1Proof::Core(_) => SP1ProofMode::Core,
        SP1Proof::Compressed(_) => SP1ProofMode::Compressed,
        SP1Proof::Plonk(_) => SP1ProofMode::Plonk,
        SP1Proof::Groth16(_) => SP1ProofMode::Groth16,
    };
    proof_system_for_mode(mode)
}

fn proof_system_for_mode(mode: SP1ProofMode) -> Result<&'static str, String> {
    match mode {
        SP1ProofMode::Groth16 => Ok("groth16"),
        other => Err(format!("expected Groth16 EVM proof, got {other:?}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use sp1_sdk::{blocking::ProverClient, SP1ProofMode};

    #[test]
    fn controlled_evm_profile_accepts_only_groth16() {
        assert_eq!(proof_system_for_mode(SP1ProofMode::Groth16), Ok("groth16"));
    }

    #[test]
    fn proof_mode_diagnostic_is_explicit() {
        assert_eq!(
            proof_system_for_mode(SP1ProofMode::Core),
            Err("expected Groth16 EVM proof, got Core".to_owned())
        );
        assert_eq!(
            proof_system_for_mode(SP1ProofMode::Compressed),
            Err("expected Groth16 EVM proof, got Compressed".to_owned())
        );
    }

    #[test]
    fn controlled_guest_executes_bound_witness_and_matches_core() {
        let bundle: serde_json::Value = serde_json::from_str(include_str!(
            "../../../../etc/conformance/vectors/source-bound-entitlement-v1.json"
        ))
        .unwrap();
        let mut input = bundle["base-input"].clone();
        let unbound = allocation_kernel::source_bound_entitlement::derive(&input).unwrap();
        input["prepared-operation"]["policy-root"] =
            json!(format!("0x{}", unbound.realized_statement_root));
        let expected = allocation_kernel::controlled_entitlement_source::derive(&input)
            .unwrap()
            .public_values
            .encode();
        let mut stdin = SP1Stdin::new();
        stdin.write_vec(serde_json::to_vec(&input).unwrap());

        let client = ProverClient::builder().light().build();
        let (public_values, report) = client.execute(ELF, stdin).run().unwrap();
        assert_eq!(report.exit_code, 0);
        assert_eq!(public_values.as_slice(), expected.as_slice());
        assert_eq!(public_values.as_slice().len(), 288);
    }
}
