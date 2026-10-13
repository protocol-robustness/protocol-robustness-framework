//! Print the allocation kernel program verification key.
//!
//! Usage: cargo run --release --bin allocation-vkey

use sp1_sdk::{blocking::MockProver, blocking::Prover, include_elf, Elf, HashableKey, ProvingKey};

/// The ELF for the allocation kernel SP1 guest.
const ALLOCATION_ELF: Elf = include_elf!("allocation-sp1-program");

fn main() {
    let prover = MockProver::new();
    let pk = prover.setup(ALLOCATION_ELF).expect("failed to setup elf");
    println!("{}", pk.verifying_key().bytes32());
}
