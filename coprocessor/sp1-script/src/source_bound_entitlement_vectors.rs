//! Materialization helpers for the source-bound entitlement conformance bundle.
//!
//! This is test/proof-harness plumbing only. It does not define protocol
//! derivation semantics; the materialized witness is passed to the reviewed
//! `allocation_kernel::source_bound_entitlement::derive` function.
#![allow(dead_code)] // Core conformance tests only need the materialized witness.

use serde_json::{json, Value};

#[derive(Clone, Debug)]
pub struct MaterializedVector {
    pub id: String,
    pub input: Value,
    pub expected_status: String,
    pub expected_error: Option<String>,
    pub expected_public_values: Option<String>,
}

pub fn materialize_vector(bundle: &Value, id: &str) -> Result<MaterializedVector, String> {
    let vector = bundle
        .get("vectors")
        .and_then(Value::as_array)
        .and_then(|vectors| {
            vectors
                .iter()
                .find(|vector| vector.get("id").and_then(Value::as_str) == Some(id))
        })
        .ok_or_else(|| format!("unknown source-bound entitlement vector: {id}"))?;
    let transform = vector
        .get("transform")
        .and_then(Value::as_str)
        .ok_or("vector transform required")?;
    let base = bundle
        .get("base-input")
        .ok_or("conformance bundle base-input required")?;
    let input = materialize_transform(base, transform)?;
    let expected_status = vector
        .get("expected-status")
        .and_then(Value::as_str)
        .ok_or("vector expected-status required")?
        .to_owned();
    let expected_error = vector
        .get("expected-error")
        .and_then(Value::as_str)
        .map(str::to_owned);
    let expected_public_values = bundle
        .get("expected")
        .and_then(|expected| expected.get(id))
        .and_then(|expected| expected.get("public-values"))
        .and_then(Value::as_str)
        .map(str::to_owned);

    if expected_status == "passing" && expected_public_values.is_none() {
        return Err(format!(
            "passing vector {id} is missing golden public-values"
        ));
    }
    if expected_status == "rejected" && expected_error.is_none() {
        return Err(format!("rejected vector {id} is missing expected-error"));
    }

    Ok(MaterializedVector {
        id: id.to_owned(),
        input,
        expected_status,
        expected_error,
        expected_public_values,
    })
}

pub fn materialize_transform(base: &Value, transform: &str) -> Result<Value, String> {
    let mut input = base.clone();
    match transform {
        "identity" => {}
        "rounding-7-of-10" => {
            input["realized-statement-input"]["available"] = json!("7");
            input["realized-statement-input"]["requested"] =
                json!({"claim-c": "2", "claim-a": "5", "claim-b": "3"});
            input["economic-reserve-state"] = json!({
                "gross-balance": "10",
                "reserved-before": "2",
                "protected-capital": "1",
                "available-amount": "7",
                "availability-revision": "17"
            });
        }
        "reverse-all-input-rows" => {
            reverse_array(
                &mut input["realized-statement-input"]["allocation-context"]["claimants"],
                "claimants",
            )?;
            reverse_array(
                &mut input["realized-statement-input"]["allocation-context"]["outcomes"][0]
                    ["allocations"],
                "allocations",
            )?;
            reverse_array(&mut input["beneficiary-mapping"], "beneficiary-mapping")?;
            reverse_array(&mut input["openings"], "openings")?;
        }
        "replace-claim-b-salt" => replace_opening_salt(
            &mut input,
            "claim-b",
            "0xdddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
        )?,
        "replace-claim-b-beneficiary" => {
            let beneficiary = "0x4444444444444444444444444444444444444444";
            replace_mapping_field(&mut input, "claim-b", "beneficiary", beneficiary)?;
            replace_claimant_field(&mut input, "claim-b", "economic-owner-id", beneficiary)?;
        }
        "duplicate-claim-a-mapping" => input["beneficiary-mapping"]
            .as_array_mut()
            .ok_or("beneficiary-mapping must be an array")?
            .push(json!({
                "claim-id": "claim-a",
                "beneficiary": "0x5555555555555555555555555555555555555555"
            })),
        other => return Err(format!("unknown conformance transform: {other}")),
    }
    Ok(input)
}

fn reverse_array(value: &mut Value, field: &str) -> Result<(), String> {
    value
        .as_array_mut()
        .ok_or_else(|| format!("{field} must be an array"))?
        .reverse();
    Ok(())
}

fn replace_mapping_field(
    input: &mut Value,
    claim: &str,
    field: &str,
    value: &str,
) -> Result<(), String> {
    let row = input["beneficiary-mapping"]
        .as_array_mut()
        .ok_or("beneficiary-mapping must be an array")?
        .iter_mut()
        .find(|row| row["claim-id"] == claim)
        .ok_or_else(|| format!("beneficiary mapping missing claim {claim}"))?;
    row[field] = Value::String(value.to_owned());
    Ok(())
}

fn replace_claimant_field(
    input: &mut Value,
    claim: &str,
    field: &str,
    value: &str,
) -> Result<(), String> {
    let row = input["realized-statement-input"]["allocation-context"]["claimants"]
        .as_array_mut()
        .ok_or("allocation-context claimants must be an array")?
        .iter_mut()
        .find(|row| row["claim-id"] == claim)
        .ok_or_else(|| format!("allocation claimant missing claim {claim}"))?;
    row[field] = Value::String(value.to_owned());
    Ok(())
}

fn replace_opening_salt(input: &mut Value, claim: &str, salt: &str) -> Result<(), String> {
    let row = input["openings"]
        .as_array_mut()
        .ok_or("openings must be an array")?
        .iter_mut()
        .find(|row| row["claim-id"] == claim)
        .ok_or_else(|| format!("opening missing claim {claim}"))?;
    row["salt"] = Value::String(salt.to_owned());
    Ok(())
}
