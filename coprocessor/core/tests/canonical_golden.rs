//! Golden canonical byte-preimage tests generated from the PRF reference
//! implementation (CANONICAL_HASH_SPEC_V1_BINARY_ENCODING_ABI). These lock the
//! exact bytes the Rust kernel must produce.

use allocation_kernel::canonical::CanonValue;
use num_bigint::BigInt;

fn hex_bytes(s: &str) -> Vec<u8> {
    hex::decode(s).unwrap()
}

#[test]
fn golden_claimant_map() {
    // PRF: (canonical-bytes {:claim/id "A" :economic-owner-id "owner-A" :amount 50 :weight 50})
    let value = CanonValue::map(vec![
        (
            CanonValue::keyword("amount"),
            CanonValue::int(BigInt::from(50)),
        ),
        (
            CanonValue::keyword("weight"),
            CanonValue::int(BigInt::from(50)),
        ),
        (CanonValue::keyword("claim/id"), CanonValue::str("A")),
        (
            CanonValue::keyword("economic-owner-id"),
            CanonValue::str("owner-A"),
        ),
    ]);
    let expected = hex_bytes(
        "31042206616d6f756e741064220677656967687410642208636c61696d2f6964200141221165636f6e6f6d69632d6f776e65722d696420076f776e65722d41",
    );
    assert_eq!(value.encode(), expected);
}

#[test]
fn golden_claimant_set() {
    // PRF: canonical-bytes of the canonically ordered claimant vector.
    let claimants = vec![
        CanonValue::map(vec![
            (
                CanonValue::keyword("amount"),
                CanonValue::int(BigInt::from(50)),
            ),
            (
                CanonValue::keyword("weight"),
                CanonValue::int(BigInt::from(50)),
            ),
            (CanonValue::keyword("claim/id"), CanonValue::str("A")),
            (
                CanonValue::keyword("economic-owner-id"),
                CanonValue::str("owner-A"),
            ),
        ]),
        CanonValue::map(vec![
            (
                CanonValue::keyword("amount"),
                CanonValue::int(BigInt::from(30)),
            ),
            (
                CanonValue::keyword("weight"),
                CanonValue::int(BigInt::from(30)),
            ),
            (CanonValue::keyword("claim/id"), CanonValue::str("B")),
            (
                CanonValue::keyword("economic-owner-id"),
                CanonValue::str("owner-B"),
            ),
        ]),
        CanonValue::map(vec![
            (
                CanonValue::keyword("amount"),
                CanonValue::int(BigInt::from(20)),
            ),
            (
                CanonValue::keyword("weight"),
                CanonValue::int(BigInt::from(20)),
            ),
            (CanonValue::keyword("claim/id"), CanonValue::str("C")),
            (
                CanonValue::keyword("economic-owner-id"),
                CanonValue::str("owner-C"),
            ),
        ]),
    ];
    let expected = hex_bytes(
        "300331042206616d6f756e741064220677656967687410642208636c61696d2f6964200141221165636f6e6f6d69632d6f776e65722d696420076f776e65722d4131042206616d6f756e74103c2206776569676874103c2208636c61696d2f6964200142221165636f6e6f6d69632d6f776e65722d696420076f776e65722d4231042206616d6f756e741028220677656967687410282208636c61696d2f6964200143221165636f6e6f6d69632d6f776e65722d696420076f776e65722d43",
    );
    assert_eq!(CanonValue::array(claimants).encode(), expected);
}

#[test]
fn golden_rate_entry() {
    // PRF: flattened proposed-rates root projection.
    let value = CanonValue::map(vec![
        (
            CanonValue::keyword("numerator"),
            CanonValue::int(BigInt::from(1)),
        ),
        (CanonValue::keyword("outcome/id"), CanonValue::str("O1")),
        (
            CanonValue::keyword("denominator"),
            CanonValue::int(BigInt::from(2)),
        ),
    ]);
    let expected = hex_bytes(
        "310322096e756d657261746f721002220a6f7574636f6d652f696420024f31220b64656e6f6d696e61746f721004",
    );
    assert_eq!(value.encode(), expected);
}

#[test]
fn golden_selection_candidate() {
    // PRF: {:authoritative-randomness <32 bytes as ints> :counter 0 :outcome-count 2}
    let randomness: Vec<CanonValue> = (1u8..=32)
        .map(|b| CanonValue::int(BigInt::from(b)))
        .collect();
    let value = CanonValue::map(vec![
        (
            CanonValue::keyword("authoritative-randomness"),
            CanonValue::array(randomness),
        ),
        (
            CanonValue::keyword("counter"),
            CanonValue::int(BigInt::from(0)),
        ),
        (
            CanonValue::keyword("outcome-count"),
            CanonValue::int(BigInt::from(2)),
        ),
    ]);
    let expected = hex_bytes(
        "31032207636f756e7465721000220d6f7574636f6d652d636f756e7410042218617574686f72697461746976652d72616e646f6d6e65737330201002100410061008100a100c100e10101012101410161018101a101c101e10201022102410261028102a102c102e10301032103410361038103a103c103e1040",
    );
    assert_eq!(value.encode(), expected);
}
