//! `realized-allocation-execution.v1` derivation.
//!
//! This module parses the raw realized-statement input and an explicit EVM
//! execution companion, recomputes the realized rows, and derives the complete
//! EVM execution projection. It never accepts a host-computed statement root,
//! operation binding, claim leaf/root, or aggregate as input.

use crate::realized_statement_io;
use serde_json::{json, Value};
use sha3::{Digest, Keccak256};
use std::collections::{HashMap, HashSet};

pub const SCHEMA_VERSION: &str = "realized-allocation-execution.v1";
pub const PUBLIC_VALUES_SCHEMA: &str = "realized-allocation-execution-public-values.v1";
pub const PUBLIC_VALUES_LEN: usize = 384;

const OPERATION_BINDING_DOMAIN: &str = "prf.realized-allocation-execution.operation-binding.v1";
const CLAIM_LEAF_DOMAIN: &str = "prf.realized-allocation-execution.claim-leaf.v1";
const CLAIM_NODE_DOMAIN: &str = "prf.realized-allocation-execution.claim-node.v1";
const CLAIM_ROOT_EMPTY_DOMAIN: &str = "prf.realized-allocation-execution.claim-root-empty.v1";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ExecutionClaim {
    pub claim_id: String,
    pub recipient: [u8; 20],
    pub filled: u64,
    pub leaf: [u8; 32],
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ExecutionProjection {
    pub statement_root: [u8; 32],
    pub operation_binding: [u8; 32],
    pub claim_root: [u8; 32],
    pub token: [u8; 20],
    pub allocator: [u8; 20],
    pub chain_id: u64,
    pub operation_id: [u8; 32],
    pub available_amount: u64,
    pub allocation_count: u64,
    pub total_allocated: u64,
    pub residual_amount: u64,
    pub claims: Vec<ExecutionClaim>,
}

/// Parse and derive an execution projection from this exact input shape:
///
/// `{ "realized-statement-input": { ... }, "execution-companion": { ... } }`.
///
/// Rejections use the same stable envelope as the statement kernel. Inputs
/// that can produce a passing result are fully rederived from raw data.
pub fn run_realized_execution(input: &Value) -> Value {
    match derive(input) {
        Ok(projection) => json!({
            "result/status": "passing",
            "schema-version": SCHEMA_VERSION,
            "statement-root": hex::encode(projection.statement_root),
            "operation-binding": hex::encode(projection.operation_binding),
            "claim-root": hex::encode(projection.claim_root),
            "token-address": address_hex(&projection.token),
            "allocator-address": address_hex(&projection.allocator),
            "chain-id": projection.chain_id.to_string(),
            "operation-id": format!("0x{}", hex::encode(projection.operation_id)),
            "available-amount": projection.available_amount.to_string(),
            "allocation-count": projection.allocation_count.to_string(),
            "total-allocated": projection.total_allocated.to_string(),
            "residual-amount": projection.residual_amount.to_string(),
            "public-values-schema": PUBLIC_VALUES_SCHEMA,
            "public-values": format!("0x{}", hex::encode(projection.public_values())),
        }),
        Err((classification, reason)) => json!({
            "result/status": "rejected",
            "rejection/classification": classification,
            "rejection/reason": reason,
        }),
    }
}

/// Derive the projection and preserve typed bytes for SP1 public values.
pub fn derive(input: &Value) -> Result<ExecutionProjection, (String, String)> {
    let statement_input = input.get("realized-statement-input").ok_or_else(|| {
        reject(
            "missing-realized-statement-input",
            "realized-statement-input required",
        )
    })?;
    let detailed =
        realized_statement_io::run_realized_statement_with_details(statement_input, true);
    if detailed["result/status"] != "passing" {
        return Err((
            "invalid-realized-statement-input".to_owned(),
            detailed["rejection/classification"]
                .as_str()
                .unwrap_or("statement input rejected")
                .to_owned(),
        ));
    }

    let companion = input
        .get("execution-companion")
        .and_then(Value::as_object)
        .ok_or_else(|| {
            reject(
                "malformed-execution-companion",
                "execution-companion must be an object",
            )
        })?;
    require_only_keys(
        companion,
        &[
            "schema-version",
            "claim-recipient-mapping",
            "token-address",
            "execution-target",
        ],
        "execution-companion",
    )?;
    if companion.get("schema-version").and_then(Value::as_str) != Some(SCHEMA_VERSION) {
        return Err(reject(
            "unsupported-execution-schema",
            "execution-companion schema-version must be realized-allocation-execution.v1",
        ));
    }

    let token = parse_address(companion.get("token-address"), "token-address")?;
    let target = companion
        .get("execution-target")
        .and_then(Value::as_object)
        .ok_or_else(|| {
            reject(
                "malformed-execution-target",
                "execution-target must be an object",
            )
        })?;
    require_only_keys(
        target,
        &[
            "allocator-address",
            "chain-id",
            "operation-id",
            "available-amount",
        ],
        "execution-target",
    )?;
    let allocator = parse_address(target.get("allocator-address"), "allocator-address")?;
    let chain_id = parse_u64(target.get("chain-id"), "chain-id")?;
    if chain_id == 0 {
        return Err(reject(
            "malformed-chain-id",
            "chain-id must be greater than zero",
        ));
    }
    let operation_id = parse_bytes32(target.get("operation-id"), "operation-id")?;
    let available_amount = parse_u64(target.get("available-amount"), "available-amount")?;

    let statement_root = parse_bare_hash32(detailed.get("statement-root"), "statement-root")?;
    let rows = detailed["details"]["realized-results"]
        .as_array()
        .ok_or_else(|| {
            reject(
                "invalid-realized-statement-input",
                "statement details missing realized-results",
            )
        })?;
    let executable: Vec<(&str, u64)> = rows
        .iter()
        .filter_map(|row| {
            let filled = row.get("filled")?.as_i64()?;
            if filled > 0 {
                Some((row.get("claim/id")?.as_str()?, filled as u64))
            } else {
                None
            }
        })
        .collect();
    let total_allocated = executable.iter().try_fold(0_u64, |sum, (_, amount)| {
        sum.checked_add(*amount)
            .ok_or_else(|| reject("total-allocated-overflow", "total allocated exceeds uint64"))
    })?;
    if available_amount != total_allocated {
        return Err(reject(
            "available-amount-mismatch",
            "execution target available-amount must equal the supported-profile realized total",
        ));
    }

    let mappings = parse_mappings(companion.get("claim-recipient-mapping"))?;
    let executable_ids: HashSet<&str> = executable.iter().map(|(id, _)| *id).collect();
    if mappings.len() != executable_ids.len()
        || mappings
            .keys()
            .any(|id| !executable_ids.contains(id.as_str()))
    {
        return Err(reject(
            "claim-recipient-mapping-not-exact",
            "mapping must contain exactly one recipient for each executable filled claim",
        ));
    }

    let operation_binding = operation_binding(
        chain_id,
        &allocator,
        &token,
        &operation_id,
        available_amount,
    );
    let mut claims: Vec<ExecutionClaim> = executable
        .iter()
        .map(|(claim_id, filled)| {
            let recipient = mappings[*claim_id];
            ExecutionClaim {
                claim_id: (*claim_id).to_owned(),
                recipient,
                filled: *filled,
                leaf: claim_leaf(&operation_binding, claim_id, &recipient, *filled),
            }
        })
        .collect();
    claims.sort_by(|a, b| a.claim_id.as_bytes().cmp(b.claim_id.as_bytes()));
    let claim_root = claim_root(&claims.iter().map(|claim| claim.leaf).collect::<Vec<_>>());

    Ok(ExecutionProjection {
        statement_root,
        operation_binding,
        claim_root,
        token,
        allocator,
        chain_id,
        operation_id,
        available_amount,
        allocation_count: claims.len() as u64,
        total_allocated,
        residual_amount: 0,
        claims,
    })
}

impl ExecutionProjection {
    /// Exact 384-byte EVM ABI static tuple encoding, detailed in the V1 spec.
    pub fn public_values(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(PUBLIC_VALUES_LEN);
        out.extend(domain_word(PUBLIC_VALUES_SCHEMA));
        out.extend(self.statement_root);
        out.extend(self.operation_binding);
        out.extend(self.claim_root);
        out.extend(address_word(&self.token));
        out.extend(address_word(&self.allocator));
        out.extend(uint_word(self.chain_id));
        out.extend(self.operation_id);
        out.extend(uint_word(self.available_amount));
        out.extend(uint_word(self.allocation_count));
        out.extend(uint_word(self.total_allocated));
        out.extend(uint_word(self.residual_amount));
        debug_assert_eq!(out.len(), PUBLIC_VALUES_LEN);
        out
    }
}

fn parse_mappings(value: Option<&Value>) -> Result<HashMap<String, [u8; 20]>, (String, String)> {
    let rows = value.and_then(Value::as_array).ok_or_else(|| {
        reject(
            "malformed-claim-recipient-mapping",
            "claim-recipient-mapping must be an array",
        )
    })?;
    let mut mappings = HashMap::with_capacity(rows.len());
    let mut previous: Option<&str> = None;
    for row in rows {
        let row = row.as_object().ok_or_else(|| {
            reject(
                "malformed-claim-recipient-mapping",
                "each mapping must be an object",
            )
        })?;
        require_only_keys(
            row,
            &["claim/id", "recipient"],
            "claim-recipient-mapping row",
        )?;
        let claim_id = row
            .get("claim/id")
            .and_then(Value::as_str)
            .filter(|id| !id.is_empty())
            .ok_or_else(|| {
                reject(
                    "malformed-claim-recipient-mapping",
                    "claim/id must be a non-empty string",
                )
            })?;
        if previous.map_or(false, |prior| prior.as_bytes() >= claim_id.as_bytes()) {
            return Err(reject(
                "noncanonical-claim-recipient-mapping",
                "mappings must be strictly ordered by UTF-8 claim/id bytes",
            ));
        }
        previous = Some(claim_id);
        let recipient = parse_address(row.get("recipient"), "recipient")?;
        if mappings.insert(claim_id.to_owned(), recipient).is_some() {
            return Err(reject(
                "duplicate-claim-recipient-mapping",
                "claim/id must be unique",
            ));
        }
    }
    Ok(mappings)
}

fn require_only_keys(
    map: &serde_json::Map<String, Value>,
    allowed: &[&str],
    name: &str,
) -> Result<(), (String, String)> {
    if map.keys().any(|key| !allowed.contains(&key.as_str())) {
        Err(reject(
            "noncanonical-execution-input",
            &format!("{} contains an unknown field", name),
        ))
    } else if map.len() != allowed.len() {
        Err(reject(
            "malformed-execution-input",
            &format!("{} is missing a required field", name),
        ))
    } else {
        Ok(())
    }
}

fn parse_u64(value: Option<&Value>, field: &str) -> Result<u64, (String, String)> {
    let text = value.and_then(Value::as_str).ok_or_else(|| {
        reject(
            &format!("malformed-{}", field),
            &format!("{} must be a decimal string", field),
        )
    })?;
    if text.is_empty()
        || (text.len() > 1 && text.starts_with('0'))
        || !text.bytes().all(|b| b.is_ascii_digit())
    {
        return Err(reject(
            &format!("malformed-{}", field),
            &format!("{} must be a canonical unsigned decimal string", field),
        ));
    }
    text.parse::<u64>().map_err(|_| {
        reject(
            &format!("{}-exceeds-supported-range", field),
            &format!("{} exceeds the current uint64 execution profile", field),
        )
    })
}

fn parse_address(value: Option<&Value>, field: &str) -> Result<[u8; 20], (String, String)> {
    let text = value.and_then(Value::as_str).ok_or_else(|| {
        reject(
            &format!("malformed-{}", field),
            &format!("{} must be a 0x-prefixed lowercase EVM address", field),
        )
    })?;
    let raw = text
        .strip_prefix("0x")
        .filter(|raw| {
            raw.len() == 40
                && raw
                    .bytes()
                    .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        })
        .ok_or_else(|| {
            reject(
                &format!("malformed-{}", field),
                &format!("{} must be a 0x-prefixed lowercase EVM address", field),
            )
        })?;
    let bytes = hex::decode(raw).expect("validated hexadecimal address");
    let address: [u8; 20] = bytes.try_into().expect("20-byte address");
    if address == [0; 20] {
        return Err(reject(
            &format!("malformed-{}", field),
            &format!("{} must be non-zero", field),
        ));
    }
    Ok(address)
}

fn parse_bytes32(value: Option<&Value>, field: &str) -> Result<[u8; 32], (String, String)> {
    let text = value.and_then(Value::as_str).ok_or_else(|| {
        reject(
            &format!("malformed-{}", field),
            &format!("{} must be a 0x-prefixed lowercase bytes32", field),
        )
    })?;
    let raw = text
        .strip_prefix("0x")
        .filter(|raw| {
            raw.len() == 64
                && raw
                    .bytes()
                    .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        })
        .ok_or_else(|| {
            reject(
                &format!("malformed-{}", field),
                &format!("{} must be a 0x-prefixed lowercase bytes32", field),
            )
        })?;
    Ok(hex::decode(raw)
        .expect("validated hexadecimal bytes32")
        .try_into()
        .expect("32-byte hash"))
}

fn parse_bare_hash32(value: Option<&Value>, field: &str) -> Result<[u8; 32], (String, String)> {
    let text = value.and_then(Value::as_str).ok_or_else(|| {
        reject(
            "invalid-realized-statement-input",
            &format!("{} missing", field),
        )
    })?;
    if text.len() != 64
        || !text
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
    {
        return Err(reject(
            "invalid-realized-statement-input",
            &format!("{} is not lowercase hex", field),
        ));
    }
    Ok(hex::decode(text)
        .expect("validated hash")
        .try_into()
        .expect("32-byte hash"))
}

fn operation_binding(
    chain_id: u64,
    allocator: &[u8; 20],
    token: &[u8; 20],
    operation_id: &[u8; 32],
    available_amount: u64,
) -> [u8; 32] {
    keccak(&[
        &domain_word(OPERATION_BINDING_DOMAIN),
        &uint_word(chain_id),
        &address_word(allocator),
        &address_word(token),
        operation_id,
        &uint_word(available_amount),
    ])
}

fn claim_leaf(binding: &[u8; 32], claim_id: &str, recipient: &[u8; 20], filled: u64) -> [u8; 32] {
    keccak(&[
        &domain_word(CLAIM_LEAF_DOMAIN),
        binding,
        &keccak(&[claim_id.as_bytes()]),
        &address_word(recipient),
        &uint_word(filled),
    ])
}

fn claim_root(leaves: &[[u8; 32]]) -> [u8; 32] {
    if leaves.is_empty() {
        return keccak(&[&domain_word(CLAIM_ROOT_EMPTY_DOMAIN)]);
    }
    let mut level = leaves.to_vec();
    while level.len() > 1 {
        if level.len() % 2 == 1 {
            level.push(*level.last().expect("non-empty level"));
        }
        level = level
            .chunks_exact(2)
            .map(|pair| keccak(&[&domain_word(CLAIM_NODE_DOMAIN), &pair[0], &pair[1]]))
            .collect();
    }
    level[0]
}

fn domain_word(domain: &str) -> [u8; 32] {
    keccak(&[domain.as_bytes()])
}
fn keccak(parts: &[&[u8]]) -> [u8; 32] {
    let mut hasher = Keccak256::new();
    for part in parts {
        hasher.update(part);
    }
    hasher.finalize().into()
}
fn uint_word(value: u64) -> [u8; 32] {
    let mut word = [0; 32];
    word[24..].copy_from_slice(&value.to_be_bytes());
    word
}
fn address_word(address: &[u8; 20]) -> [u8; 32] {
    let mut word = [0; 32];
    word[12..].copy_from_slice(address);
    word
}
fn address_hex(address: &[u8; 20]) -> String {
    format!("0x{}", hex::encode(address))
}
fn reject(classification: &str, reason: &str) -> (String, String) {
    (classification.to_owned(), reason.to_owned())
}
