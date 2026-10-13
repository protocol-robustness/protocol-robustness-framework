use allocation_kernel::realized_execution::{self, PUBLIC_VALUES_LEN, PUBLIC_VALUES_SCHEMA};
use serde_json::json;

fn input() -> serde_json::Value {
    serde_json::from_str(include_str!("fixtures/realized-execution-all-active.json")).unwrap()
}

#[test]
fn execution_projection_has_frozen_vector_and_abi_public_values() {
    let projection = realized_execution::derive(&input()).unwrap();
    assert_eq!(
        hex::encode(projection.statement_root),
        "c22333a16df1c1efa352e9daab42ccbd78f4a1d7530ee3ed3cf7527ba62cbd81"
    );
    assert_eq!(projection.allocation_count, 3);
    assert_eq!(projection.total_allocated, 100);
    assert_eq!(projection.residual_amount, 0);
    assert_eq!(projection.public_values().len(), PUBLIC_VALUES_LEN);
    assert_eq!(
        &projection.public_values()[0..32],
        &keccak256(PUBLIC_VALUES_SCHEMA)
    );
    assert_eq!(
        hex::encode(projection.operation_binding),
        "2af0c75e9385b5b40631c6b05dcc9271b1398c96373899269d061beec929027f"
    );
    assert_eq!(
        hex::encode(projection.claim_root),
        "407fae5b4caeb20d77a030a74fa4e2659f7d75505ade3645c825da35ab98afa3"
    );
    assert_eq!(
        projection
            .claims
            .iter()
            .map(|claim| hex::encode(claim.leaf))
            .collect::<Vec<_>>(),
        vec![
            "f80d653aa6c98215e9d3a574dccb08c7b0772dddfff2d4404becf5f08bd4f30a",
            "23472ad42041455ea0d2ae5aaf7b6cb8f440622e2b53ad010bd0bdf8c566d227",
            "f08fd8ef2d0ea21606cc29d5ccfb387800f0fa09d79858ee4432e903ed98e612",
        ]
    );
    assert_eq!(hex::encode(projection.public_values()), "d2196a7baaa9f298fe2fc5bc00454f9455487bb55672083690a7c856d55f33ebc22333a16df1c1efa352e9daab42ccbd78f4a1d7530ee3ed3cf7527ba62cbd812af0c75e9385b5b40631c6b05dcc9271b1398c96373899269d061beec929027f407fae5b4caeb20d77a030a74fa4e2659f7d75505ade3645c825da35ab98afa3000000000000000000000000444444444444444444444444444444444444444400000000000000000000000055555555555555555555555555555555555555550000000000000000000000000000000000000000000000000000000000000001aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa0000000000000000000000000000000000000000000000000000000000000064000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000000640000000000000000000000000000000000000000000000000000000000000000");
}

#[test]
fn operation_binding_commits_available_amount() {
    let full = realized_execution::derive(&input()).unwrap();
    let mut short = input();
    short["realized-statement-input"]["available"] = json!("99");
    short["execution-companion"]["execution-target"]["available-amount"] = json!("99");
    let partial = realized_execution::derive(&short).unwrap();
    assert_ne!(full.operation_binding, partial.operation_binding);
    assert_eq!(partial.available_amount, 99);
    assert_eq!(partial.total_allocated, 99);
}

#[test]
fn rejects_host_computed_projection_and_mapping_drift() {
    let mut hostile = input();
    hostile["execution-companion"]["statement-root"] = json!("00");
    assert_eq!(
        realized_execution::run_realized_execution(&hostile)["rejection/classification"],
        "noncanonical-execution-input"
    );

    let mut missing = input();
    missing["execution-companion"]["claim-recipient-mapping"]
        .as_array_mut()
        .unwrap()
        .pop();
    assert_eq!(
        realized_execution::run_realized_execution(&missing)["rejection/classification"],
        "claim-recipient-mapping-not-exact"
    );

    let mut wrong_amount = input();
    wrong_amount["execution-companion"]["execution-target"]["available-amount"] = json!("99");
    assert_eq!(
        realized_execution::run_realized_execution(&wrong_amount)["rejection/classification"],
        "available-amount-mismatch"
    );
}

#[test]
fn mapping_must_be_canonical_and_addresses_nonzero() {
    let mut unordered = input();
    unordered["execution-companion"]["claim-recipient-mapping"]
        .as_array_mut()
        .unwrap()
        .swap(0, 1);
    assert_eq!(
        realized_execution::run_realized_execution(&unordered)["rejection/classification"],
        "noncanonical-claim-recipient-mapping"
    );

    let mut zero_address = input();
    zero_address["execution-companion"]["token-address"] =
        json!("0x0000000000000000000000000000000000000000");
    assert_eq!(
        realized_execution::run_realized_execution(&zero_address)["rejection/classification"],
        "malformed-token-address"
    );
}

fn keccak256(text: &str) -> [u8; 32] {
    use sha3::{Digest, Keccak256};
    Keccak256::digest(text.as_bytes()).into()
}
