//! Source-bound Shortfall allocation witness.
//!
//! The witness is derived only from raw reserve and realized-statement inputs.
//! It binds the existing provenance roots to the independently re-derived
//! realized statement through the frozen allocation-binding canonical hash.

use crate::{
    canonical::{domain_hash, CanonValue},
    realized_statement_io, shortfall_provenance,
};
use serde_json::Value;
use sha3::{Digest, Keccak256};

pub const SCHEMA_VERSION: &str = "prf/source-bound-shortfall-allocation.v1";
pub const PUBLIC_VALUES_LEN: usize = 160;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Witness {
    pub allocation_binding: [u8; 32],
    pub realized_statement: [u8; 32],
    pub operation_id: [u8; 32],
    pub reserve_snapshot_hash: [u8; 32],
}

pub fn derive(input: &Value) -> Result<Witness, String> {
    // The fixture is semantic, not a bag of independently supplied derived
    // documents.  Reconstruct both consumers from the same Shortfall fields.
    shortfall_provenance::validate_raw_reserve(input)
        .map_err(|e| format!("invalid reserve provenance: {e}"))?;
    let statement = realized_statement_input(input)?;
    let details = realized_statement_io::run_realized_statement_with_details(&statement, true);
    if details["result/status"] != "passing" {
        return Err(format!(
            "realized statement rejected: {}",
            details["rejection/classification"]
        ));
    }
    let execution = execution_input(input, &statement);
    let execution_projection = crate::realized_execution::run_realized_execution(&execution);
    if execution_projection["result/status"] != "passing"
        || execution_projection["statement-root"] != details["statement-root"]
    {
        return Err("realized statement/execution statement-root mismatch".to_owned());
    }
    let provenance = provenance_input(input)?;
    let operation = provenance["operation-id"]
        .as_str()
        .ok_or("operation-id required")?;
    let operation_id = bytes32(operation)?;
    let chain_id = provenance["chain-id"]
        .as_str()
        .ok_or("chain-id required")?
        .parse::<u64>()
        .map_err(|_| "invalid chain-id".to_owned())?;
    let asset = shortfall_provenance::evm_asset_root(
        chain_id,
        provenance["token-address"]
            .as_str()
            .ok_or("token-address required")?,
    );
    let snapshot = shortfall_provenance::solidity_snapshot_hash(&provenance);
    let observation = shortfall_provenance::observation_root(&provenance, &asset, &snapshot);
    let context = shortfall_provenance::context_root(&provenance, &asset, &observation);
    let reserve = shortfall_provenance::binding_root(&observation, &context, operation);
    let statement_root = details["statement-root"]
        .as_str()
        .ok_or("statement-root missing")?;
    let binding_body = CanonValue::map(vec![
        (
            CanonValue::keyword("operation-id"),
            CanonValue::str(operation),
        ),
        (
            CanonValue::keyword("realized-statement/root"),
            CanonValue::str(statement_root),
        ),
        (
            CanonValue::keyword("reserve-binding/root"),
            CanonValue::str(&reserve),
        ),
        (
            CanonValue::keyword("schema-version"),
            CanonValue::str("prf/onchain-shortfall-allocation-binding.v1"),
        ),
    ]);
    let allocation = domain_hash("prf.onchain-shortfall-allocation-binding.v1", &binding_body);
    Ok(Witness {
        allocation_binding: bytes32(&allocation)?,
        realized_statement: bytes32(statement_root)?,
        operation_id,
        reserve_snapshot_hash: bytes32(&snapshot)?,
    })
}

impl Witness {
    pub fn public_values(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(PUBLIC_VALUES_LEN);
        out.extend(Keccak256::digest(
            b"prf/onchain-shortfall-allocation-binding-public-values.v1",
        ));
        out.extend(self.allocation_binding);
        out.extend(self.realized_statement);
        out.extend(self.operation_id);
        out.extend(self.reserve_snapshot_hash);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emits_exactly_five_words_from_raw_inputs() {
        let input: Value = serde_json::from_str(include_str!(
            "../tests/fixtures/onchain-shortfall-end-to-end-v1.json"
        ))
        .unwrap();
        let witness = derive(&input).unwrap();
        assert_eq!(
            hex::encode(witness.realized_statement),
            "1779ead0c2eff0239a3de700918fca7849e33cbca0a8ebdad363f3ec38f226e9"
        );
        assert_eq!(witness.public_values().len(), PUBLIC_VALUES_LEN);
        assert_ne!(&witness.public_values()[32..64], &[0u8; 32]);
    }

    #[test]
    fn source_mutations_change_bound_output() {
        let input: Value = serde_json::from_str(include_str!(
            "../tests/fixtures/onchain-shortfall-end-to-end-v1.json"
        ))
        .unwrap();
        let first = derive(&input).unwrap().public_values();
        let mut changed = input.clone();
        changed["available-amount"] = Value::String("69".into());
        assert!(derive(&changed).is_err());
        let mut operation_changed = input.clone();
        operation_changed["operation-id"] = Value::String(
            "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".into(),
        );
        assert!(derive(&operation_changed).is_err());
        assert_eq!(first.len(), 160);
    }
}

fn provenance_input(input: &Value) -> Result<Value, String> {
    let sf = input.get("shortfall").ok_or("shortfall required")?;
    let mut v = input.clone();
    v["allocation-context-root"] = sf["allocation-context-root"].clone();
    v["candidates"] = sf["candidates"].clone();
    Ok(v)
}

fn realized_statement_input(input: &Value) -> Result<Value, String> {
    let sf = input.get("shortfall").ok_or("shortfall required")?;
    let context = sf
        .get("allocation-context")
        .ok_or("allocation-context required")?
        .clone();
    let requested = sf.get("requested").ok_or("requested required")?.clone();
    Ok(serde_json::json!({
        "allocation-context": context,
        "available": input["available-amount"].clone(),
        "requested": requested,
        "policy": sf["policy"].clone(),
        "fail-action-policy": sf["fail-action-policy"].clone(),
        "round-state": sf["round-state"].clone()
    }))
}

fn execution_input(input: &Value, statement: &Value) -> Value {
    serde_json::json!({
        "realized-statement-input": statement,
        "execution-companion": {
            "schema-version": crate::realized_execution::SCHEMA_VERSION,
            "claim-recipient-mapping": input["execution"]["claim-recipient-mapping"].clone(),
            "token-address": input["token-address"].clone(),
            "execution-target": {
                "allocator-address": input["allocator"].clone(),
                "chain-id": input["chain-id"].clone(),
                "operation-id": input["operation-id"].clone(),
                "available-amount": input["available-amount"].clone()
            }
        }
    })
}

fn bytes32(value: &str) -> Result<[u8; 32], String> {
    let raw = value.trim_start_matches("sha256:").trim_start_matches("0x");
    let bytes = hex::decode(raw).map_err(|_| format!("invalid bytes32: {value}"))?;
    bytes
        .try_into()
        .map_err(|_| format!("expected 32 bytes: {value}"))
}
