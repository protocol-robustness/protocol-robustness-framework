//! Execute the isolated exact-verifier SP1 guest against a JSON request/claim.
//! This qualification runner reports execution output; it does not create or
//! qualify a proof artifact, VKey, or production verifier identity.

use clap::Parser;
use prf_pro_rata_allocation::exact_verifier::{CONFORMANCE_CORPUS_ROOT, FAIRNESS_SPEC_ROOT};
use sp1_sdk::{
    blocking::{Prover, ProverClient},
    include_elf, Elf, SP1Stdin,
};
use std::{fs, path::PathBuf};

const ELF: Elf = include_elf!("pro-rata-exact-verifier-sp1-program");

#[derive(Parser)]
struct Args {
    #[arg(long)]
    input: PathBuf,
    #[arg(long)]
    expected_input_root: Option<String>,
}

fn main() {
    sp1_sdk::utils::setup_logger();
    let args = Args::parse();
    let input = fs::read(&args.input).expect("read exact-verifier input");
    let client = ProverClient::from_env();
    let mut stdin = SP1Stdin::new();
    stdin.write_vec(input);
    let (public_values, _) = client
        .execute(ELF, stdin)
        .run()
        .expect("exact-verifier SP1 guest execution");
    assert_eq!(
        public_values.as_slice().len(),
        96,
        "public values are spec root || corpus root || exact input root"
    );
    let public_values = public_values.as_slice();
    assert_eq!(
        &public_values[..32],
        hex::decode(FAIRNESS_SPEC_ROOT)
            .expect("valid spec root")
            .as_slice(),
        "guest fairness-spec root differs from the Clojure/Rust golden root"
    );
    assert_eq!(
        &public_values[32..64],
        hex::decode(CONFORMANCE_CORPUS_ROOT)
            .expect("valid corpus root")
            .as_slice(),
        "guest conformance-corpus root differs from the Clojure/Rust golden root"
    );
    if let Some(expected) = args.expected_input_root {
        let expected = expected.strip_prefix("0x").unwrap_or(&expected);
        assert_eq!(
            &public_values[64..96],
            hex::decode(expected)
                .expect("expected input root must be hex")
                .as_slice(),
            "guest input root differs from the expected cross-language vector"
        );
    }
    println!("0x{}", hex::encode(public_values));
}
