//! Isolated SP1 guest for the pro-rata exact-verification proof profile.
//!
//! This program is separate from the frozen allocation-kernel guest. It only
//! proves request-authoritative verification of a caller-supplied allocation
//! claim; it does not execute or commit allocator public values.

#![no_main]
sp1_zkvm::entrypoint!(main);

use num_bigint::BigInt;
use prf_canonical_codec::CanonValue;
use prf_pro_rata_allocation::exact_verifier::{
    exact_verification_input_root, verify, CapTreatment, ClaimedAllocation, OrderingPolicy,
    RoundingPolicy, VerificationClaim, VerificationItem, VerificationRequest,
    CONFORMANCE_CORPUS_ROOT, FAIRNESS_SPEC_ROOT,
};
use serde_json::Value;

fn required<'a>(value: &'a Value, field: &str) -> &'a Value {
    value
        .get(field)
        .unwrap_or_else(|| panic!("missing field {field}"))
}

fn decimal(value: &Value, field: &str) -> BigInt {
    value
        .as_str()
        .unwrap_or_else(|| panic!("{field} must be a decimal string"))
        .parse()
        .unwrap_or_else(|_| panic!("{field} must be a valid integer"))
}

/// Parse the explicit typed-value JSON encoding used for canonical IDs.
/// Objects have `kind` and `value` members; map values are arrays of pairs.
fn canonical_value(value: &Value) -> CanonValue {
    let kind = required(value, "kind")
        .as_str()
        .unwrap_or_else(|| panic!("canonical ID kind must be a string"));
    let payload = value.get("value");
    match kind {
        "keyword" => CanonValue::keyword(
            payload
                .and_then(Value::as_str)
                .unwrap_or_else(|| panic!("keyword ID value must be a string")),
        ),
        "string" => CanonValue::str(
            payload
                .and_then(Value::as_str)
                .unwrap_or_else(|| panic!("string ID value must be a string")),
        ),
        "integer" => CanonValue::int(decimal(
            payload.unwrap_or_else(|| panic!("integer ID requires value")),
            "integer ID",
        )),
        "boolean" => CanonValue::bool(
            payload
                .and_then(Value::as_bool)
                .unwrap_or_else(|| panic!("boolean ID value must be boolean")),
        ),
        "null" => CanonValue::Null,
        "array" => CanonValue::array(
            payload
                .and_then(Value::as_array)
                .unwrap_or_else(|| panic!("array ID value must be an array"))
                .iter()
                .map(canonical_value)
                .collect(),
        ),
        "map" => {
            let pairs = payload
                .and_then(Value::as_array)
                .unwrap_or_else(|| panic!("map ID value must be an array of pairs"));
            CanonValue::map(
                pairs
                    .iter()
                    .map(|pair| {
                        let pair = pair
                            .as_array()
                            .unwrap_or_else(|| panic!("map ID entry must be a pair"));
                        if pair.len() != 2 {
                            panic!("map ID entry must contain two values");
                        }
                        (canonical_value(&pair[0]), canonical_value(&pair[1]))
                    })
                    .collect(),
            )
        }
        _ => panic!("unsupported canonical ID kind {kind}"),
    }
}

fn rounding(value: &Value) -> RoundingPolicy {
    match value.as_str() {
        Some("floor") => RoundingPolicy::Floor,
        Some("floor-with-largest-remainder") => RoundingPolicy::LargestRemainder,
        _ => panic!("unsupported rounding policy"),
    }
}

fn ordering(value: &Value) -> OrderingPolicy {
    match value.as_str() {
        Some("input-order") => OrderingPolicy::InputOrder,
        Some("canonical-id") => OrderingPolicy::CanonicalId,
        _ => panic!("unsupported ordering policy"),
    }
}

fn cap_treatment(value: &Value) -> CapTreatment {
    match value.as_str() {
        Some("unallocated") => CapTreatment::Unallocated,
        Some("redistribute") => CapTreatment::Redistribute,
        _ => panic!("unsupported cap treatment"),
    }
}

fn parse_request(value: &Value) -> VerificationRequest {
    let items = required(value, "items")
        .as_array()
        .unwrap_or_else(|| panic!("items must be an array"))
        .iter()
        .map(|item| {
            let cap = match item.get("cap") {
                None | Some(Value::Null) => None,
                Some(value) => Some(decimal(value, "cap")),
            };
            VerificationItem {
                id: canonical_value(required(item, "id")),
                weight: decimal(required(item, "weight"), "weight"),
                cap,
            }
        })
        .collect();
    VerificationRequest {
        amount: decimal(required(value, "amount"), "amount"),
        items,
        rounding: rounding(required(value, "rounding")),
        ordering: ordering(required(value, "ordering-policy")),
        cap_treatment: cap_treatment(required(value, "cap-treatment")),
    }
}

fn parse_claim(value: &Value) -> VerificationClaim {
    let allocations = required(value, "allocations")
        .as_array()
        .unwrap_or_else(|| panic!("allocations must be an array"))
        .iter()
        .map(|row| ClaimedAllocation {
            id: canonical_value(required(row, "id")),
            allocated: decimal(required(row, "allocated"), "allocated"),
        })
        .collect();
    VerificationClaim {
        allocations,
        total_allocated: decimal(required(value, "total-allocated"), "total-allocated"),
        total_unmet: decimal(required(value, "total-unmet"), "total-unmet"),
        remainder: decimal(required(value, "remainder"), "remainder"),
    }
}

fn append_hash(output: &mut Vec<u8>, value: &str) {
    let bytes = hex::decode(value).expect("canonical root must be hex");
    assert_eq!(bytes.len(), 32, "canonical root must be bytes32");
    output.extend(bytes);
}

pub fn main() {
    let input: Value = serde_json::from_slice(&sp1_zkvm::io::read_vec())
        .expect("exact-verifier input must be valid JSON");
    let request = parse_request(required(&input, "request"));
    let claim = parse_claim(required(&input, "claim"));

    assert!(
        verify(&request, &claim).expect("request must satisfy the supported verifier domain"),
        "allocation claim does not match exact reconstruction"
    );

    let mut public_values = Vec::with_capacity(96);
    append_hash(&mut public_values, FAIRNESS_SPEC_ROOT);
    append_hash(&mut public_values, CONFORMANCE_CORPUS_ROOT);
    append_hash(
        &mut public_values,
        &exact_verification_input_root(&request, &claim),
    );
    sp1_zkvm::io::commit_slice(&public_values);
}
