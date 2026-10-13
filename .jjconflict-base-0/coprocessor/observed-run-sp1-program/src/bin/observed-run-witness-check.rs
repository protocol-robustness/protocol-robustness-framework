use std::io::{self, Read};

fn main() {
    let mut input = String::new();
    io::stdin()
        .read_to_string(&mut input)
        .expect("read witness");
    let value: serde_json::Value = serde_json::from_str(&input).expect("parse witness JSON");
    match observed_run_sp1_program::derive_public_values(&value) {
        Ok(bytes) => println!("{}", observed_run_sp1_program::public_values_hex(&bytes)),
        Err(error) => {
            eprintln!("{error}");
            std::process::exit(2);
        }
    }
}
