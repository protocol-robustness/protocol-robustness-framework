//! Separate arithmetic statement: does not change observed-run-roots-384.v1.
//! It proves canonical plan coverage and partition arithmetic, not execution
//! provenance, confidentiality, semantic claim acceptance or payment authority.
#![no_main]
sp1_zkvm::entrypoint!(main);

pub fn main() {
    let bytes = sp1_zkvm::io::read_vec();
    let witness = serde_json::from_slice(&bytes).expect("canonical accounting witness JSON");
    let public = allocation_kernel::accounting_arithmetic::derive_public_values(&witness)
        .unwrap_or_else(|reason| panic!("accounting arithmetic rejected: {reason}"));
    assert_eq!(public.len(), 160);
    sp1_zkvm::io::commit_slice(&public);
}
