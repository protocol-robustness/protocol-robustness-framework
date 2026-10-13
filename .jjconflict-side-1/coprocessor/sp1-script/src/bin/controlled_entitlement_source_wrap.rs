//! Retain SP1 compressed and BN254 wrapper checkpoints before native Groth16.
//! Uses the same public SP1 node/witness APIs as SDK 6.3.1.
use clap::Parser;
use slop_algebra::{AbstractField, PrimeField32};
use slop_bn254::Bn254Fr;
use sp1_hypercube::koalabears_to_bn254;
use sp1_primitives::SP1Field;
use sp1_prover::worker::{cpu_worker_builder, SP1LocalNodeBuilder};
use sp1_prover_types::network_base_types::ProofMode;
use sp1_recursion_circuit::{
    machine::SP1ShapedWitnessValues,
    utils::{koalabear_bytes_to_bn254, koalabears_proof_nonce_to_bn254, words_to_bytes},
    witness::{OuterWitness, Witnessable},
};
use sp1_recursion_executor::RecursionPublicValues;
use sp1_recursion_gnark_ffi::GnarkWitness;
use sp1_sdk::{include_elf, Elf, SP1Context, SP1ProofWithPublicValues, SP1Stdin};
use std::{borrow::Borrow, path::PathBuf};
const ELF: Elf = include_elf!("controlled-entitlement-source-sp1-program");
#[derive(Parser)]
struct Args {
    #[arg(long)]
    input: PathBuf,
    #[arg(long)]
    wrapper_witness: PathBuf,
    #[arg(long)]
    compressed_proof: PathBuf,
}
#[tokio::main]
async fn main() {
    sp1_sdk::utils::setup_logger();
    let args = Args::parse();
    let bytes = std::fs::read(&args.input).unwrap();
    let input = serde_json::from_slice(&bytes).unwrap();
    let expected = allocation_kernel::controlled_entitlement_source::derive(&input)
        .expect("controlled source rejected")
        .public_values
        .encode();
    let node = SP1LocalNodeBuilder::from_worker_client_builder(cpu_worker_builder())
        .build()
        .await
        .unwrap();
    let vk = node.setup(&ELF).await.unwrap();
    let proof = if args.compressed_proof.exists() {
        SP1ProofWithPublicValues::load(&args.compressed_proof)
            .expect("load compressed SDK checkpoint")
    } else {
        let mut stdin = SP1Stdin::new();
        stdin.write_vec(bytes);
        let proof = SP1ProofWithPublicValues::from(
            node.prove_with_mode(&ELF, stdin, SP1Context::default(), ProofMode::Compressed)
                .await
                .expect("actual SP1 compressed proof"),
        );
        assert_eq!(proof.public_values.as_slice(), expected.as_slice());
        node.verify(&vk, &proof.proof)
            .expect("verify actual compressed proof");
        proof
            .save(&args.compressed_proof)
            .expect("retain compressed checkpoint");
        proof
    };
    assert_eq!(proof.public_values.as_slice(), expected.as_slice());
    node.verify(&vk, &proof.proof)
        .expect("verify compressed checkpoint");
    println!("Actual compressed proof verified; 288 public bytes match controlled source");
    let wrapped = node
        .shrink_wrap(&proof.proof)
        .await
        .expect("actual SDK shrink/wrap");
    // SDK 6.3.1's run_groth16 prepares its Gnark witness through these APIs.
    let pv: &RecursionPublicValues<SP1Field> = wrapped.proof.public_values.as_slice().borrow();
    let vkey_hash = koalabears_to_bn254(&pv.sp1_vk_digest);
    let digest_bytes: [SP1Field; 32] = words_to_bytes(&pv.committed_value_digest)
        .try_into()
        .unwrap();
    let mut witness = OuterWitness::default();
    SP1ShapedWitnessValues {
        vks_and_proofs: vec![(wrapped.vk, wrapped.proof.clone())],
        is_complete: true,
    }
    .write(&mut witness);
    witness.write_committed_values_digest(koalabear_bytes_to_bn254(&digest_bytes));
    witness.write_vkey_hash(vkey_hash);
    witness.write_exit_code(Bn254Fr::from_canonical_u32(pv.exit_code.as_canonical_u32()));
    witness.write_vk_root(koalabears_to_bn254(&pv.vk_root));
    witness.write_proof_nonce(koalabears_proof_nonce_to_bn254(&pv.proof_nonce));
    GnarkWitness::new(witness).save(args.wrapper_witness.to_str().unwrap());
    println!(
        "Actual SDK wrapper checkpoint retained: {}",
        args.wrapper_witness.display()
    );
}
