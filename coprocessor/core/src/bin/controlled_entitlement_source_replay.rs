//! Thin replay entrypoint over the existing controlled-source derivation.
use std::io::{self, Read};
fn main() {
    let mut raw = String::new();
    io::stdin().read_to_string(&mut raw).expect("read witness");
    let result = serde_json::from_str(&raw)
        .map_err(|e| e.to_string())
        .and_then(|input| allocation_kernel::controlled_entitlement_source::derive(&input));
    match result {
        Ok(derived) => println!(
            "{}",
            allocation_kernel::source_bound_entitlement::projection_json(&derived)
        ),
        Err(reason) => {
            eprintln!("controlled source rejected: {reason}");
            std::process::exit(1);
        }
    }
}
