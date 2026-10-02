//! Execute or prove the source-bound beneficiary entitlement successor.

use clap::Parser;
use sha2::{Digest, Sha256};
use sp1_sdk::{
    blocking::{ProveRequest, Prover, ProverClient},
    include_elf, Elf, HashableKey, ProvingKey, SP1Proof, SP1ProofMode, SP1ProofWithPublicValues,
    SP1Stdin,
};
use std::time::Instant;

#[path = "../source_bound_entitlement_vectors.rs"]
mod source_bound_entitlement_vectors;

const ELF: Elf = include_elf!("source-bound-entitlement-sp1-program");
const PROGRAM_ID: &str = "source-bound-entitlement-sp1-program.v1";

#[derive(Parser, Debug)]
struct Args {
    /// Path to one raw successor witness JSON object.
    #[arg(long, conflicts_with = "vectors")]
    input: Option<String>,
    /// Path to the source-bound-entitlement conformance bundle.
    #[arg(long, requires = "vector")]
    vectors: Option<String>,
    /// Vector id to materialize from --vectors.
    #[arg(long, requires = "vectors")]
    vector: Option<String>,
    #[arg(long)]
    execute: bool,
    #[arg(long)]
    prove: bool,
    #[arg(long)]
    identity_only: bool,
    #[arg(long)]
    fixture: Option<String>,
}

fn main() {
    let host_started = Instant::now();
    sp1_sdk::utils::setup_logger();
    let args = Args::parse();
    let phase_started = Instant::now();
    let (input, vector_case, source) = match (&args.input, &args.vectors, &args.vector) {
        (Some(path), None, None) => {
            let input = std::fs::read(path).expect("read successor witness");
            let source = serde_json::json!({
                "kind": "raw-witness",
                "witness_sha256": format!("0x{}", hex::encode(Sha256::digest(&input)))
            });
            (input, None, source)
        }
        (None, Some(path), Some(vector_id)) => {
            let bundle_bytes = std::fs::read(path).expect("read conformance vector bundle");
            let bundle: serde_json::Value =
                serde_json::from_slice(&bundle_bytes).expect("parse conformance vector bundle");
            let vector = source_bound_entitlement_vectors::materialize_vector(&bundle, vector_id)
                .unwrap_or_else(|reason| panic!("materialize vector {vector_id}: {reason}"));
            let input = serde_json::to_vec(&vector.input).expect("encode materialized witness");
            let source = serde_json::json!({
                "kind": "conformance-vector",
                "bundle_sha256": format!("0x{}", hex::encode(Sha256::digest(&bundle_bytes))),
                "vector_id": vector.id,
                "witness_sha256": format!("0x{}", hex::encode(Sha256::digest(&input)))
            });
            (input, Some(vector), source)
        }
        _ => panic!(
            "specify either --input <raw-witness.json> or --vectors <bundle.json> --vector <id>"
        ),
    };
    eprintln!(
        "b3 timing: input read/materialized {:?}, {} bytes",
        phase_started.elapsed(),
        input.len()
    );
    let phase_started = Instant::now();
    let parsed: serde_json::Value = serde_json::from_slice(&input).expect("parse successor input");
    eprintln!("b3 timing: fixture parse {:?}", phase_started.elapsed());
    let phase_started = Instant::now();
    let expected = allocation_kernel::source_bound_entitlement::derive(&parsed)
        .map(|derivation| derivation.public_values.encode());
    if let Some(vector) = &vector_case {
        match vector.expected_status.as_str() {
            "passing" => assert!(
                expected.is_ok(),
                "vector {} is marked passing but core derivation rejected it: {:?}",
                vector.id,
                expected.err()
            ),
            "rejected" => {
                let reason = expected
                    .as_ref()
                    .expect_err("vector is marked rejected but core derivation passed");
                assert!(
                    reason.contains(vector.expected_error.as_deref().unwrap()),
                    "vector {} rejection mismatch: {reason}",
                    vector.id
                );
            }
            status => panic!("unsupported vector status: {status}"),
        }
    }
    eprintln!(
        "b3 timing: native Rust derivation {:?}",
        phase_started.elapsed()
    );
    if !args.identity_only && args.execute == args.prove {
        panic!("specify exactly one of --execute or --prove");
    }
    let mut stdin = SP1Stdin::new();
    let phase_started = Instant::now();
    stdin.write_vec(input);
    eprintln!(
        "b3 timing: SP1 stdin construction {:?}; serialized size {} bytes",
        phase_started.elapsed(),
        bincode::serialized_size(&stdin).expect("size SP1 stdin")
    );
    if args.execute {
        eprintln!("b3 timing: executor initialization starting");
        let phase_started = Instant::now();
        let client = ProverClient::builder().light().build();
        eprintln!(
            "b3 timing: light executor initialization {:?}",
            phase_started.elapsed()
        );
        eprintln!(
            "b3 timing: guest execution starting; host elapsed {:?}",
            host_started.elapsed()
        );
        let phase_started = Instant::now();
        let execution = client.execute(ELF, stdin).run();
        eprintln!(
            "b3 timing: guest execution returned {:?}",
            phase_started.elapsed()
        );
        let expected = match expected {
            Ok(expected) => expected,
            Err(reason) => {
                let (_, report) = execution.expect("execute invalid successor witness");
                assert_ne!(
                    report.exit_code, 0,
                    "guest accepted a witness rejected by Rust core: {reason}"
                );
                println!("guest rejected invalid witness as expected: {reason}");
                if let Some(vector) = &vector_case {
                    println!("vector_id: {}", vector.id);
                }
                return;
            }
        };
        let (public_values, report) = execution.expect("execute successor ELF");
        assert_eq!(
            report.exit_code, 0,
            "successor guest did not halt successfully"
        );
        let phase_started = Instant::now();
        assert_eq!(public_values.as_slice(), expected.as_slice());
        assert_matches_vector_golden(&vector_case, public_values.as_slice());
        eprintln!(
            "b3 timing: public-values comparison {:?}",
            phase_started.elapsed()
        );
        println!("guest public values match Rust core (288 bytes)");
        print_source_identity(&source);
        println!("public_values: 0x{}", hex::encode(public_values.as_slice()));
        println!("cycles: {}", report.total_instruction_count());
    } else {
        if let Err(reason) = &expected {
            panic!("cannot prove rejected successor witness: {reason}");
        }
        let client = ProverClient::from_env();
        let pk = client.setup(ELF).expect("setup successor ELF");
        let vkey = pk.verifying_key().bytes32().to_string();
        let elf_sha256 = format!("0x{}", hex::encode(Sha256::digest(&ELF[..])));
        if args.identity_only {
            println!("program_id: {PROGRAM_ID}");
            println!("vkey: {vkey}");
            println!("elf_sha256: {elf_sha256}");
            return;
        }
        let expected = expected.expect("successor input rejected");
        // `--prove` is the EVM proof path. The SDK defaults to Core proofs,
        // whose `bytes()` method panics because they are not on-chain proofs.
        let proof = client
            .prove(&pk, stdin)
            .groth16()
            .run()
            .expect("generate successor Groth16 proof");
        client
            .verify(&proof, pk.verifying_key(), None)
            .expect("verify successor proof");
        assert_eq!(proof.public_values.as_slice(), expected.as_slice());
        assert_matches_vector_golden(&vector_case, proof.public_values.as_slice());
        let proof_system = require_groth16(&proof).unwrap_or_else(|reason| panic!("{reason}"));
        let onchain_proof_bytes = proof.bytes();
        println!("proof public values match Rust core (288 bytes)");
        println!("proof system: {proof_system}");
        print_source_identity(&source);
        println!("program_id: {PROGRAM_ID}");
        println!("vkey: {vkey}");
        println!("elf_sha256: {elf_sha256}");
        if let Some(path) = args.fixture {
            let fixture = serde_json::json!({
                "program_id": PROGRAM_ID,
                "vkey": vkey,
                "elf_sha256": elf_sha256,
                "public_values_schema": allocation_kernel::source_bound_entitlement::PUBLIC_VALUES_SCHEMA,
                "public_values_length": allocation_kernel::source_bound_entitlement::PUBLIC_VALUES_LEN,
                "public_values": format!("0x{}", hex::encode(&expected)),
                "source": source,
                "proof_system": proof_system,
                "proof": format!("0x{}", hex::encode(&onchain_proof_bytes)),
            });
            std::fs::write(path, serde_json::to_string_pretty(&fixture).unwrap()).unwrap();
            println!("on-chain proof bytes: {}", onchain_proof_bytes.len());
            println!(
                "on-chain proof sha256: 0x{}",
                hex::encode(Sha256::digest(&onchain_proof_bytes))
            );
        }
    }
}

fn require_groth16(proof: &SP1ProofWithPublicValues) -> Result<&'static str, String> {
    let mode = match &proof.proof {
        SP1Proof::Core(_) => SP1ProofMode::Core,
        SP1Proof::Compressed(_) => SP1ProofMode::Compressed,
        SP1Proof::Plonk(_) => SP1ProofMode::Plonk,
        SP1Proof::Groth16(_) => SP1ProofMode::Groth16,
    };
    fixture_proof_system(mode)
}

fn fixture_proof_system(mode: SP1ProofMode) -> Result<&'static str, String> {
    match mode {
        SP1ProofMode::Groth16 => Ok("groth16"),
        other => Err(format!("expected Groth16 EVM proof, got {other:?}")),
    }
}

#[cfg(test)]
mod tests {
    use super::fixture_proof_system;
    use sp1_sdk::SP1ProofMode;

    #[test]
    fn evm_fixture_accepts_only_groth16_proofs() {
        assert_eq!(fixture_proof_system(SP1ProofMode::Groth16), Ok("groth16"));
    }

    #[test]
    fn evm_fixture_rejects_core_and_compressed_proofs() {
        assert_eq!(
            fixture_proof_system(SP1ProofMode::Core),
            Err("expected Groth16 EVM proof, got Core".to_owned())
        );
        assert_eq!(
            fixture_proof_system(SP1ProofMode::Compressed),
            Err("expected Groth16 EVM proof, got Compressed".to_owned())
        );
    }
}

fn assert_matches_vector_golden(
    vector_case: &Option<source_bound_entitlement_vectors::MaterializedVector>,
    public_values: &[u8],
) {
    if let Some(vector) = vector_case {
        let expected = vector
            .expected_public_values
            .as_deref()
            .expect("passing vector must have golden public-values")
            .trim_start_matches("0x");
        let expected = hex::decode(expected).unwrap_or_else(|error| {
            panic!("vector {} has malformed golden bytes: {error}", vector.id)
        });
        assert_eq!(
            public_values, expected,
            "vector {} golden mismatch",
            vector.id
        );
        println!("public values match conformance vector {}", vector.id);
    }
}

fn print_source_identity(source: &serde_json::Value) {
    println!(
        "source: {}",
        serde_json::to_string(source).expect("serialize source identity")
    );
}
