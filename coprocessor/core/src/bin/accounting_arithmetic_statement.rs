//! Guest-compatible arithmetic validator/conformance tool, not a prover.
use std::io::{self, Read};
fn main() {
    let mut input = String::new();
    io::stdin().read_to_string(&mut input).unwrap();
    let result = serde_json::from_str(&input)
        .map_err(|e| e.to_string())
        .and_then(|v| allocation_kernel::accounting_arithmetic::derive_public_values(&v));
    match result {
        Ok(bytes) => println!("{}", hex::encode(bytes)),
        Err(reason) => {
            eprintln!("arithmetic witness rejected: {reason}");
            std::process::exit(1);
        }
    }
}
