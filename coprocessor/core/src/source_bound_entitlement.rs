//! Pure `source-bound-entitlement` successor derivation.
//!
//! Canonical artifacts use the PRF canonical SHA-256 codec. EVM commitments
//! use explicit 32-byte ABI words and keccak256. This module does not contain
//! an SP1 entrypoint, deployment representation, or withdrawal behavior.

use crate::canonical::{domain_hash, CanonValue};
use crate::realized_statement_io;
use serde_json::{json, Value};
use sha3::{Digest, Keccak256};
use std::collections::{HashMap, HashSet};

pub const PUBLIC_VALUES_SCHEMA: &str = "prf/source-bound-entitlement-public-values.v1";
pub const PUBLIC_VALUES_LEN: usize = 288;
pub const ENTITLEMENT_SCHEMA: &str = "beneficiary-entitlement.v1";
pub const ENTITLEMENT_SET_SCHEMA: &str = "beneficiary-entitlement-set.v1";
pub const DESCRIPTOR_SCHEMA: &str = "entitlement-representation-descriptor.v1";
pub const DESCRIPTOR_PROFILE: &str = "committed-immutable-table-v1";
pub const SALT_BYTES: usize = 32;

const ENTITLEMENT_DOMAIN: &str = "PRF_BENEFICIARY_ENTITLEMENT_V1";
const ENTITLEMENT_SET_DOMAIN: &str = "PRF_BENEFICIARY_ENTITLEMENT_SET_V1";
const DESCRIPTOR_DOMAIN: &str = "PRF_ENTITLEMENT_REPRESENTATION_DESCRIPTOR_V1";
const ALLOCATION_BINDING_DOMAIN: &str = "prf.onchain-shortfall-allocation-binding.v1";
const PREPARED_OPERATION_DOMAIN: &str = "prf.prepared-operation.v2";
const RESERVE_STATE_DOMAIN: &str = "prf.economic-reserve-state.v2";
const RESERVE_STATE_V3_DOMAIN: &str = "prf.economic-reserve-state.v3";
const CLAIM_LEAF_DOMAIN: &str = "prf.source-bound-entitlement.claim-leaf.v1";
const CLAIM_NODE_DOMAIN: &str = "prf.source-bound-entitlement.claim-node.v1";
const CLAIM_EMPTY_DOMAIN: &str = "prf.source-bound-entitlement.claim-empty.v1";
const COMMITMENT_DOMAIN: &str = "prf.source-bound-entitlement.commitment.v1";
const TABLE_NODE_DOMAIN: &str = "prf.source-bound-entitlement.table-node.v1";
const TABLE_EMPTY_DOMAIN: &str = "prf.source-bound-entitlement.table-empty.v1";
const TABLE_ROOT_DOMAIN: &str = "prf.source-bound-entitlement.table-root.v1";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EntitlementMember {
    pub allocation_binding_root: String,
    pub realized_statement_root: String,
    pub operation_id: String,
    pub claim_id: String,
    pub beneficiary: String,
    pub beneficiary_profile: String,
    pub amount: u128,
    pub ordinal: u128,
    pub entitlement_root: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SourceBoundEntitlementPublicValuesV1 {
    pub prepared_operation_root: [u8; 32],
    pub reserve_state_commitment: [u8; 32],
    pub allocation_binding_root: [u8; 32],
    pub entitlement_representation_descriptor_root: [u8; 32],
    pub evm_entitlement_table_root: [u8; 32],
    pub entitlement_count: u128,
    pub entitlement_total: u128,
    pub allocation_claim_root: [u8; 32],
}

impl SourceBoundEntitlementPublicValuesV1 {
    pub fn schema_word() -> [u8; 32] {
        keccak(&[PUBLIC_VALUES_SCHEMA.as_bytes()])
    }

    pub fn words(&self) -> [[u8; 32]; 9] {
        [
            Self::schema_word(),
            self.prepared_operation_root,
            self.reserve_state_commitment,
            self.allocation_binding_root,
            self.entitlement_representation_descriptor_root,
            self.evm_entitlement_table_root,
            uint_word(self.entitlement_count),
            uint_word(self.entitlement_total),
            self.allocation_claim_root,
        ]
    }

    pub fn encode(&self) -> Vec<u8> {
        let words = self.words();
        let mut out = Vec::with_capacity(PUBLIC_VALUES_LEN);
        for word in words {
            out.extend_from_slice(&word);
        }
        debug_assert_eq!(out.len(), PUBLIC_VALUES_LEN);
        out
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Derivation {
    pub realized_statement_root: String,
    pub allocation_binding_root: String,
    pub operation_id: String,
    pub entitlements: Vec<EntitlementMember>,
    pub canonical_entitlement_set_root: String,
    pub descriptor_root: String,
    pub allocation_claim_leaves: Vec<[u8; 32]>,
    pub allocation_claim_root: [u8; 32],
    pub evm_entitlement_commitments: Vec<[u8; 32]>,
    pub evm_entitlement_table_root: [u8; 32],
    pub entitlement_count: u128,
    pub entitlement_total: u128,
    pub prepared_operation_root: [u8; 32],
    pub reserve_state_commitment: [u8; 32],
    pub public_values: SourceBoundEntitlementPublicValuesV1,
}

fn kv(key: &str, value: CanonValue) -> (CanonValue, CanonValue) {
    (CanonValue::keyword(key), value)
}

fn descriptor_value() -> CanonValue {
    CanonValue::map(vec![
        kv("schema-version", CanonValue::str(DESCRIPTOR_SCHEMA)),
        kv("profile", CanonValue::str(DESCRIPTOR_PROFILE)),
        kv("commitment-domain", CanonValue::str(COMMITMENT_DOMAIN)),
        kv(
            "commitment-fields",
            CanonValue::array(
                [
                    "domain",
                    "allocation-binding-root",
                    "operation-id",
                    "ordinal",
                    "claim-identity-hash",
                    "beneficiary-address",
                    "amount",
                    "beneficiary-profile",
                    "salt",
                ]
                .iter()
                .map(|s| CanonValue::str(*s))
                .collect(),
            ),
        ),
        kv(
            "commitment-field-types",
            CanonValue::array(
                [
                    "bytes32", "bytes32", "bytes32", "uint256", "bytes32", "address", "uint256",
                    "bytes32", "bytes32",
                ]
                .iter()
                .map(|s| CanonValue::str(*s))
                .collect(),
            ),
        ),
        kv(
            "abi-encoding",
            CanonValue::str("abi.encode-static-32-byte-words.v1"),
        ),
        kv(
            "uint-word-scheme",
            CanonValue::str("uint256-big-endian-zero-padded-word.v1"),
        ),
        kv(
            "address-word-scheme",
            CanonValue::str("address-left-zero-padded-32-byte-word.v1"),
        ),
        kv(
            "claim-identity-word-scheme",
            CanonValue::str("keccak256-prf-canonical-claim-string-bytes.v1"),
        ),
        kv(
            "domain-word-scheme",
            CanonValue::str("keccak256-utf8-domain-string-word.v1"),
        ),
        kv(
            "abi-word-scheme",
            CanonValue::str("ordered-static-32-byte-word-concatenation.v1"),
        ),
        kv(
            "canonical-index-mapping",
            CanonValue::str("claim-identity-canonical-bytes-ascending.v1"),
        ),
        kv(
            "beneficiary-profile-encoding",
            CanonValue::str("keccak256-utf8-profile-word.v1"),
        ),
        kv(
            "opening-schema",
            CanonValue::str("entitlement-opening.bytes32-salt.v1"),
        ),
        kv("salt-width-bytes", CanonValue::int(SALT_BYTES)),
        kv("salt-placement", CanonValue::str("final-abi-word")),
        kv(
            "entitlement-commitment",
            CanonValue::str("keccak256-static-abi-v1"),
        ),
        kv(
            "claim-leaf-fields",
            CanonValue::array(
                [
                    "claim-leaf-domain",
                    "allocation-binding-root",
                    "operation-id",
                    "ordinal",
                    "claim-identity-hash",
                    "beneficiary-address",
                    "amount",
                    "beneficiary-profile-word",
                ]
                .iter()
                .map(|s| CanonValue::str(*s))
                .collect(),
            ),
        ),
        kv("claim-leaf-domain", CanonValue::str(CLAIM_LEAF_DOMAIN)),
        kv(
            "claim-leaf-field-types",
            CanonValue::array(
                [
                    "bytes32", "bytes32", "bytes32", "uint256", "bytes32", "address", "uint256",
                    "bytes32",
                ]
                .iter()
                .map(|s| CanonValue::str(*s))
                .collect(),
            ),
        ),
        kv(
            "claim-leaf-scheme",
            CanonValue::str("domain-word-plus-ordered-static-abi-words.v1"),
        ),
        kv(
            "claim-merkle-scheme",
            CanonValue::str("positional-ordered-duplicate-last-merkle.v1"),
        ),
        kv(
            "claim-merkle-node-domain",
            CanonValue::str(CLAIM_NODE_DOMAIN),
        ),
        kv(
            "claim-merkle-empty-domain",
            CanonValue::str(CLAIM_EMPTY_DOMAIN),
        ),
        kv(
            "table-commitment-scheme",
            CanonValue::str("domain-word-plus-ordered-static-abi-words.v1"),
        ),
        kv(
            "logical-table-aggregation",
            CanonValue::str("ordered-duplicate-last-merkle-then-summary.v1"),
        ),
        kv(
            "table-merkle-scheme",
            CanonValue::str("positional-ordered-duplicate-last-merkle.v1"),
        ),
        kv(
            "table-leaf-placement",
            CanonValue::str("commitment-is-leaf-value-no-prehash.v1"),
        ),
        kv(
            "table-summary-fields",
            CanonValue::array(
                [
                    "table-merkle-root",
                    "entitlement-count",
                    "entitlement-total",
                ]
                .iter()
                .map(|s| CanonValue::str(*s))
                .collect(),
            ),
        ),
        kv(
            "table-summary-scheme",
            CanonValue::str("domain-word-plus-three-ordered-static-abi-words.v1"),
        ),
        kv("table-node-domain", CanonValue::str(TABLE_NODE_DOMAIN)),
        kv("table-empty-domain", CanonValue::str(TABLE_EMPTY_DOMAIN)),
        kv("table-root-domain", CanonValue::str(TABLE_ROOT_DOMAIN)),
        kv("consumption-index", CanonValue::str("ordinal-div-256")),
        kv(
            "consumption-bit-order",
            CanonValue::str("lsb0-ordinal-mod-256"),
        ),
    ])
}

pub fn descriptor_root() -> String {
    domain_hash(DESCRIPTOR_DOMAIN, &descriptor_value())
}

fn member_value(member: &EntitlementMember, include_root: bool) -> CanonValue {
    let mut pairs = vec![
        kv("schema-version", CanonValue::str(ENTITLEMENT_SCHEMA)),
        kv(
            "allocation-binding/root",
            CanonValue::str(member.allocation_binding_root.clone()),
        ),
        kv(
            "realized-statement/root",
            CanonValue::str(member.realized_statement_root.clone()),
        ),
        kv("operation-id", CanonValue::str(member.operation_id.clone())),
        kv("claim/id", CanonValue::str(member.claim_id.clone())),
        kv("beneficiary", CanonValue::str(member.beneficiary.clone())),
        kv(
            "beneficiary-profile",
            CanonValue::str(member.beneficiary_profile.clone()),
        ),
        kv("amount", CanonValue::int(member.amount)),
        kv("ordinal", CanonValue::int(member.ordinal)),
    ];
    if include_root {
        pairs.push(kv(
            "entitlement/root",
            CanonValue::str(member.entitlement_root.clone()),
        ));
    }
    CanonValue::map(pairs)
}

fn allocation_binding_root(
    reserve_binding_root: &str,
    realized_statement_root: &str,
    operation_id: &str,
) -> String {
    domain_hash(
        ALLOCATION_BINDING_DOMAIN,
        &CanonValue::map(vec![
            kv(
                "schema-version",
                CanonValue::str("prf/onchain-shortfall-allocation-binding.v1"),
            ),
            kv(
                "reserve-binding/root",
                CanonValue::str(reserve_binding_root),
            ),
            kv(
                "realized-statement/root",
                CanonValue::str(realized_statement_root),
            ),
            kv("operation-id", CanonValue::str(operation_id)),
        ]),
    )
}

fn entitlement_set_root(
    binding_root: &str,
    statement_root: &str,
    operation_id: &str,
    profile: &str,
    members: &[EntitlementMember],
    total: u128,
) -> String {
    domain_hash(
        ENTITLEMENT_SET_DOMAIN,
        &CanonValue::map(vec![
            kv("schema-version", CanonValue::str(ENTITLEMENT_SET_SCHEMA)),
            kv(
                "allocation-binding/root",
                CanonValue::str(format!("sha256:{binding_root}")),
            ),
            kv("realized-statement/root", CanonValue::str(statement_root)),
            kv("operation-id", CanonValue::str(operation_id)),
            kv("beneficiary-profile", CanonValue::str(profile)),
            kv(
                "entitlements",
                CanonValue::array(members.iter().map(|m| member_value(m, true)).collect()),
            ),
            kv("entitlement-count", CanonValue::int(members.len())),
            kv("entitlement-total", CanonValue::int(total)),
        ]),
    )
}

fn parse_hex<const N: usize>(value: &str, field: &str) -> Result<[u8; N], String> {
    let raw = value
        .strip_prefix("0x")
        .ok_or_else(|| format!("{field} must have 0x prefix"))?;
    if raw.len() != N * 2
        || !raw
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
    {
        return Err(format!("invalid {field}"));
    }
    let bytes = hex::decode(raw).map_err(|_| format!("invalid {field}"))?;
    bytes.try_into().map_err(|_| format!("invalid {field}"))
}

fn parse_root(value: &str, field: &str) -> Result<[u8; 32], String> {
    let raw = value.strip_prefix("sha256:").unwrap_or(value);
    if raw.len() != 64
        || !raw
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
    {
        return Err(format!("invalid {field}"));
    }
    hex::decode(raw)
        .map_err(|_| format!("invalid {field}"))?
        .try_into()
        .map_err(|_| format!("invalid {field}"))
}

fn parse_u128(value: &Value, field: &str) -> Result<u128, String> {
    let text = value
        .as_str()
        .ok_or_else(|| format!("{field} must be a canonical decimal string"))?;
    if text.is_empty()
        || (text.len() > 1 && text.starts_with('0'))
        || !text.bytes().all(|b| b.is_ascii_digit())
    {
        return Err(format!("invalid {field}"));
    }
    text.parse::<u128>().map_err(|_| format!("invalid {field}"))
}

fn address_word(value: &str, field: &str) -> Result<[u8; 32], String> {
    let address = parse_hex::<20>(value, field)?;
    if address == [0u8; 20] {
        return Err(format!("zero {field}"));
    }
    let mut word = [0u8; 32];
    word[12..].copy_from_slice(&address);
    Ok(word)
}

fn flexible_word(value: &str, field: &str) -> Result<[u8; 32], String> {
    match value.len() {
        42 => address_word(value, field),
        66 => parse_hex::<32>(value, field),
        _ => Err(format!("invalid {field}")),
    }
}

fn uint_word(value: u128) -> [u8; 32] {
    let mut word = [0u8; 32];
    word[16..].copy_from_slice(&value.to_be_bytes());
    word
}

fn keccak(parts: &[&[u8]]) -> [u8; 32] {
    let mut hasher = Keccak256::new();
    for part in parts {
        hasher.update(part);
    }
    hasher.finalize().into()
}

fn domain_word(domain: &str) -> [u8; 32] {
    keccak(&[domain.as_bytes()])
}

fn claim_canonical_bytes(claim_id: &str) -> Vec<u8> {
    CanonValue::str(claim_id).encode()
}

fn merkle_root(leaves: &[[u8; 32]], node_domain: &str, empty_domain: &str) -> [u8; 32] {
    if leaves.is_empty() {
        return keccak(&[&domain_word(empty_domain)]);
    }
    let mut level = leaves.to_vec();
    while level.len() > 1 {
        if level.len() % 2 == 1 {
            level.push(*level.last().expect("nonempty"));
        }
        level = level
            .chunks(2)
            .map(|pair| keccak(&[&domain_word(node_domain), &pair[0], &pair[1]]))
            .collect();
    }
    level[0]
}

fn claim_leaf(member: &EntitlementMember) -> Result<[u8; 32], String> {
    let binding = parse_root(&member.allocation_binding_root, "allocation binding root")?;
    let operation = parse_hex::<32>(&member.operation_id, "operation id")?;
    let claim_hash = keccak(&[&claim_canonical_bytes(&member.claim_id)]);
    let beneficiary = address_word(&member.beneficiary, "beneficiary")?;
    Ok(keccak(&[
        &domain_word(CLAIM_LEAF_DOMAIN),
        &binding,
        &operation,
        &uint_word(member.ordinal),
        &claim_hash,
        &beneficiary,
        &uint_word(member.amount),
        &domain_word(&member.beneficiary_profile),
    ]))
}

fn entitlement_commitment(
    member: &EntitlementMember,
    salt: [u8; SALT_BYTES],
) -> Result<[u8; 32], String> {
    let binding = parse_root(&member.allocation_binding_root, "allocation binding root")?;
    let operation = parse_hex::<32>(&member.operation_id, "operation id")?;
    let claim_hash = keccak(&[&claim_canonical_bytes(&member.claim_id)]);
    let beneficiary = address_word(&member.beneficiary, "beneficiary")?;
    Ok(keccak(&[
        &domain_word(COMMITMENT_DOMAIN),
        &binding,
        &operation,
        &uint_word(member.ordinal),
        &claim_hash,
        &beneficiary,
        &uint_word(member.amount),
        &domain_word(&member.beneficiary_profile),
        &salt,
    ]))
}

fn table_root(commitments: &[[u8; 32]], count: u128, total: u128) -> [u8; 32] {
    let members = merkle_root(commitments, TABLE_NODE_DOMAIN, TABLE_EMPTY_DOMAIN);
    keccak(&[
        &domain_word(TABLE_ROOT_DOMAIN),
        &members,
        &uint_word(count),
        &uint_word(total),
    ])
}

fn prepared_operation_root(prepared: &Value) -> Result<[u8; 32], String> {
    let get = |key: &str| {
        prepared[key]
            .as_str()
            .ok_or_else(|| format!("prepared-operation {key} required"))
    };
    let words = vec![
        parse_hex::<32>(get("operation-id")?, "operation-id")?,
        flexible_word(get("initiator")?, "initiator")?,
        uint_word(parse_u128(&prepared["chain-id"], "chain-id")?),
        flexible_word(get("reserve")?, "reserve")?,
        flexible_word(get("token")?, "token")?,
        flexible_word(get("allocator")?, "allocator")?,
        parse_hex::<32>(get("configuration-root")?, "configuration-root")?,
        uint_word(parse_u128(
            &prepared["configuration-epoch"],
            "configuration-epoch",
        )?),
        parse_hex::<32>(get("source-verifier-id")?, "source-verifier-id")?,
        parse_hex::<32>(get("execution-verifier-id")?, "execution-verifier-id")?,
        parse_hex::<32>(get("source-program-vkey")?, "source-program-vkey")?,
        parse_hex::<32>(
            get("source-program-elf-sha256")?,
            "source-program-elf-sha256",
        )?,
        parse_hex::<32>(get("source-statement-schema")?, "source-statement-schema")?,
        parse_hex::<32>(
            get("source-public-values-schema")?,
            "source-public-values-schema",
        )?,
        parse_hex::<32>(
            get("execution-statement-schema")?,
            "execution-statement-schema",
        )?,
        parse_hex::<32>(
            get("execution-public-values-schema")?,
            "execution-public-values-schema",
        )?,
        parse_hex::<32>(get("policy-root")?, "policy-root")?,
        domain_word(get("beneficiary-profile")?),
        flexible_word(get("residual-recipient")?, "residual-recipient")?,
    ];
    let domain = domain_word(PREPARED_OPERATION_DOMAIN);
    let mut parts: Vec<&[u8]> = Vec::with_capacity(words.len() + 1);
    parts.push(&domain);
    parts.extend(words.iter().map(|w| w.as_slice()));
    Ok(keccak(&parts))
}

fn reserve_state_commitment(
    prepared: &Value,
    state: &Value,
    prepared_root: [u8; 32],
) -> Result<[u8; 32], String> {
    let gross = parse_u128(&state["gross-balance"], "gross-balance")?;
    let reserved = parse_u128(&state["reserved-before"], "reserved-before")?;
    let protected = parse_u128(&state["protected-capital"], "protected-capital")?;
    let available = parse_u128(&state["available-amount"], "available-amount")?;
    if reserved.checked_add(protected).ok_or("reserve overflow")? > gross
        || available != gross - reserved - protected
    {
        return Err("invalid reserve accounting".into());
    }
    let domain = match state.get("commitment-version").and_then(Value::as_str) {
        None | Some("v2") => RESERVE_STATE_DOMAIN,
        Some("v3") => RESERVE_STATE_V3_DOMAIN,
        Some(_) => return Err("unsupported reserve commitment version".into()),
    };
    let mut fields = vec![
        domain_word(domain),
        prepared_root,
        flexible_word(
            prepared["reserve"].as_str().ok_or("reserve required")?,
            "reserve",
        )?,
        flexible_word(prepared["token"].as_str().ok_or("token required")?, "token")?,
        uint_word(gross),
        uint_word(reserved),
        uint_word(protected),
        uint_word(available),
        uint_word(parse_u128(
            &state["availability-revision"],
            "availability-revision",
        )?),
    ];
    if domain == RESERVE_STATE_V3_DOMAIN {
        let policy_root = parse_root(
            state["policy-root"]
                .as_str()
                .ok_or("v3 policy-root required")?,
            "policy-root",
        )?;
        fields.push(policy_root);
    } else if state.get("policy-root").is_some() {
        return Err("policy-root requires reserve commitment version v3".into());
    }
    Ok(keccak(
        &fields
            .iter()
            .map(|word| word.as_slice())
            .collect::<Vec<_>>(),
    ))
}

pub fn derive(input: &Value) -> Result<Derivation, String> {
    let object = input
        .as_object()
        .ok_or("successor witness must be an object")?;
    const ALLOWED_FIELDS: &[&str] = &[
        "realized-statement-input",
        "reserve-binding-root",
        "prepared-operation",
        "economic-reserve-state",
        "beneficiary-mapping",
        "openings",
    ];
    if object
        .keys()
        .any(|key| !ALLOWED_FIELDS.contains(&key.as_str()))
    {
        return Err("unknown successor witness field".into());
    }

    let detailed = realized_statement_io::run_realized_statement_with_details(
        input
            .get("realized-statement-input")
            .ok_or("realized-statement-input required")?,
        true,
    );
    if detailed["result/status"] != "passing" {
        return Err(format!(
            "realized statement rejected: {}",
            detailed["rejection/reason"].as_str().unwrap_or("unknown")
        ));
    }
    let statement_root = detailed["statement-root"]
        .as_str()
        .ok_or("statement root missing")?
        .to_owned();
    let prepared = input
        .get("prepared-operation")
        .ok_or("prepared-operation required")?;
    let operation_id = prepared["operation-id"]
        .as_str()
        .ok_or("operation-id required")?
        .to_owned();
    let profile = prepared["beneficiary-profile"]
        .as_str()
        .ok_or("prepared-operation beneficiary-profile required")?;
    if profile != "smart-account-v1" && profile != "immutable-address-v1" {
        return Err("unsupported beneficiary profile".into());
    }
    let prepared_root = prepared_operation_root(prepared)?;
    let reserve_state = input
        .get("economic-reserve-state")
        .ok_or("economic-reserve-state required")?;
    let reserve_commitment = reserve_state_commitment(prepared, reserve_state, prepared_root)?;
    let reserve_binding_root = input["reserve-binding-root"]
        .as_str()
        .ok_or("reserve-binding-root required")?;
    parse_root(reserve_binding_root, "reserve-binding-root")?;
    let binding_root =
        allocation_binding_root(reserve_binding_root, &statement_root, &operation_id);

    let mappings = input["beneficiary-mapping"]
        .as_array()
        .ok_or("beneficiary-mapping must be an array")?;
    let mut beneficiaries: HashMap<&str, &str> = HashMap::new();
    for mapping in mappings {
        let claim = mapping["claim-id"].as_str().ok_or("claim-id required")?;
        let beneficiary = mapping["beneficiary"]
            .as_str()
            .ok_or("beneficiary required")?;
        address_word(beneficiary, "beneficiary")?;
        if beneficiaries.insert(claim, beneficiary).is_some() {
            return Err("duplicate claim identity".into());
        }
    }
    let claimants = input["realized-statement-input"]["allocation-context"]["claimants"]
        .as_array()
        .ok_or("allocation-context claimants must be an array")?;
    let mut owners: HashMap<&str, &str> = HashMap::new();
    for claimant in claimants {
        let claim = claimant["claim-id"]
            .as_str()
            .ok_or("claimant claim-id required")?;
        let owner = claimant["economic-owner-id"]
            .as_str()
            .ok_or("claimant economic-owner-id required")?;
        if owners.insert(claim, owner).is_some() {
            return Err("duplicate claim identity".into());
        }
    }
    for (claim, beneficiary) in &beneficiaries {
        if owners.get(claim).copied() != Some(*beneficiary) {
            return Err("beneficiary must match allocation-context economic owner".into());
        }
    }

    let rows = detailed["details"]["realized-results"]
        .as_array()
        .ok_or("realized rows missing")?;
    let mut positive: Vec<(String, u128)> = rows
        .iter()
        .filter_map(|row| {
            let amount = row["filled"].as_i64()?;
            if amount > 0 {
                Some((row["claim/id"].as_str()?.to_owned(), amount as u128))
            } else {
                None
            }
        })
        .collect();
    positive.sort_by_cached_key(|row| claim_canonical_bytes(&row.0));
    if positive.windows(2).any(|pair| pair[0].0 == pair[1].0) {
        return Err("duplicate claim identity".into());
    }
    let expected: HashSet<&str> = positive.iter().map(|(claim, _)| claim.as_str()).collect();
    if expected.len() != beneficiaries.len()
        || beneficiaries.keys().any(|claim| !expected.contains(claim))
    {
        return Err("beneficiary mapping must exactly cover positive rows".into());
    }

    let mut members = Vec::with_capacity(positive.len());
    for (ordinal, (claim_id, amount)) in positive.into_iter().enumerate() {
        let mut member = EntitlementMember {
            allocation_binding_root: format!("sha256:{binding_root}"),
            realized_statement_root: statement_root.clone(),
            operation_id: operation_id.clone(),
            beneficiary: beneficiaries[claim_id.as_str()].to_owned(),
            beneficiary_profile: profile.to_owned(),
            claim_id,
            amount,
            ordinal: ordinal as u128,
            entitlement_root: String::new(),
        };
        member.entitlement_root = domain_hash(ENTITLEMENT_DOMAIN, &member_value(&member, false));
        members.push(member);
    }
    if members.is_empty() {
        return Err("empty entitlement set".into());
    }
    let total = members.iter().try_fold(0u128, |sum, m| {
        sum.checked_add(m.amount)
            .ok_or("entitlement total overflow")
    })?;
    let available = parse_u128(&reserve_state["available-amount"], "available-amount")?;
    if total != available {
        return Err("Shortfall entitlement conservation failed".into());
    }

    let set_root = entitlement_set_root(
        &binding_root,
        &statement_root,
        &operation_id,
        profile,
        &members,
        total,
    );
    let claim_leaves: Vec<[u8; 32]> = members.iter().map(claim_leaf).collect::<Result<_, _>>()?;
    let claim_root = merkle_root(&claim_leaves, CLAIM_NODE_DOMAIN, CLAIM_EMPTY_DOMAIN);

    let openings = input["openings"]
        .as_array()
        .ok_or("openings must be an array")?;
    let mut salts = HashMap::new();
    for opening in openings {
        let claim = opening["claim-id"]
            .as_str()
            .ok_or("opening claim-id required")?;
        let salt =
            parse_hex::<SALT_BYTES>(opening["salt"].as_str().ok_or("salt required")?, "salt")?;
        if salts.insert(claim, salt).is_some() {
            return Err("duplicate opening claim identity".into());
        }
    }
    if salts.len() != members.len()
        || members
            .iter()
            .any(|m| !salts.contains_key(m.claim_id.as_str()))
    {
        return Err("openings must exactly cover entitlements".into());
    }
    let commitments: Vec<[u8; 32]> = members
        .iter()
        .map(|member| entitlement_commitment(member, salts[member.claim_id.as_str()]))
        .collect::<Result<_, _>>()?;
    let count = members.len() as u128;
    let evm_table_root = table_root(&commitments, count, total);
    let descriptor = descriptor_root();
    let public_values = SourceBoundEntitlementPublicValuesV1 {
        prepared_operation_root: prepared_root,
        reserve_state_commitment: reserve_commitment,
        allocation_binding_root: parse_root(&binding_root, "allocation-binding-root")?,
        entitlement_representation_descriptor_root: parse_root(&descriptor, "descriptor-root")?,
        evm_entitlement_table_root: evm_table_root,
        entitlement_count: count,
        entitlement_total: total,
        allocation_claim_root: claim_root,
    };

    Ok(Derivation {
        realized_statement_root: statement_root,
        allocation_binding_root: binding_root,
        operation_id,
        entitlements: members,
        canonical_entitlement_set_root: set_root,
        descriptor_root: descriptor,
        allocation_claim_leaves: claim_leaves,
        allocation_claim_root: claim_root,
        evm_entitlement_commitments: commitments,
        evm_entitlement_table_root: evm_table_root,
        entitlement_count: count,
        entitlement_total: total,
        prepared_operation_root: prepared_root,
        reserve_state_commitment: reserve_commitment,
        public_values,
    })
}

pub fn projection_json(derived: &Derivation) -> Value {
    json!({
        "realized-statement-root": derived.realized_statement_root,
        "allocation-binding-root": derived.allocation_binding_root,
        "operation-id": derived.operation_id,
        "ordered-entitlements": derived.entitlements.iter().map(|m| json!({
            "allocation-binding-root": m.allocation_binding_root,
            "realized-statement-root": m.realized_statement_root,
            "operation-id": m.operation_id,
            "claim-id": m.claim_id,
            "beneficiary": m.beneficiary,
            "beneficiary-profile": m.beneficiary_profile,
            "amount": m.amount.to_string(),
            "ordinal": m.ordinal.to_string(),
            "entitlement-root": m.entitlement_root,
        })).collect::<Vec<_>>(),
        "canonical-entitlement-set-root": derived.canonical_entitlement_set_root,
        "entitlement-representation-descriptor-root": derived.descriptor_root,
        "allocation-claim-leaves": derived.allocation_claim_leaves.iter().map(hex::encode).collect::<Vec<_>>(),
        "allocation-claim-root": hex::encode(derived.allocation_claim_root),
        "evm-entitlement-commitments": derived.evm_entitlement_commitments.iter().map(hex::encode).collect::<Vec<_>>(),
        "evm-entitlement-table-root": hex::encode(derived.evm_entitlement_table_root),
        "entitlement-count": derived.entitlement_count.to_string(),
        "entitlement-total": derived.entitlement_total.to_string(),
        "prepared-operation-root": hex::encode(derived.prepared_operation_root),
        "reserve-state-commitment": hex::encode(derived.reserve_state_commitment),
        "schema-word": hex::encode(SourceBoundEntitlementPublicValuesV1::schema_word()),
        "public-words": derived.public_values.words().iter().map(hex::encode).collect::<Vec<_>>(),
        "public-values": hex::encode(derived.public_values.encode()),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn public_values_are_nine_words() {
        let value = SourceBoundEntitlementPublicValuesV1 {
            prepared_operation_root: [1; 32],
            reserve_state_commitment: [2; 32],
            allocation_binding_root: [3; 32],
            entitlement_representation_descriptor_root: [4; 32],
            evm_entitlement_table_root: [5; 32],
            entitlement_count: 6,
            entitlement_total: 7,
            allocation_claim_root: [8; 32],
        };
        assert_eq!(value.words().len(), 9);
        assert_eq!(value.encode().len(), PUBLIC_VALUES_LEN);
        assert_eq!(&value.encode()[6 * 32 + 16..7 * 32], &6u128.to_be_bytes());
        assert_eq!(&value.encode()[7 * 32 + 16..8 * 32], &7u128.to_be_bytes());
    }

    #[test]
    fn every_execution_member_field_is_commitment_bound() {
        let base = EntitlementMember {
            allocation_binding_root: format!("sha256:{}", "11".repeat(32)),
            realized_statement_root: "22".repeat(32),
            operation_id: format!("0x{}", "33".repeat(32)),
            claim_id: "claim-a".to_owned(),
            beneficiary: format!("0x{}", "44".repeat(20)),
            beneficiary_profile: "smart-account-v1".to_owned(),
            amount: 7,
            ordinal: 0,
            entitlement_root: String::new(),
        };
        let salt = [0x55; SALT_BYTES];
        let claim = claim_leaf(&base).unwrap();
        let commitment = entitlement_commitment(&base, salt).unwrap();
        let variants = [
            EntitlementMember {
                allocation_binding_root: format!("sha256:{}", "66".repeat(32)),
                ..base.clone()
            },
            EntitlementMember {
                operation_id: format!("0x{}", "77".repeat(32)),
                ..base.clone()
            },
            EntitlementMember {
                claim_id: "claim-b".to_owned(),
                ..base.clone()
            },
            EntitlementMember {
                beneficiary: format!("0x{}", "88".repeat(20)),
                ..base.clone()
            },
            EntitlementMember {
                beneficiary_profile: "immutable-address-v1".to_owned(),
                ..base.clone()
            },
            EntitlementMember {
                amount: 8,
                ..base.clone()
            },
            EntitlementMember {
                ordinal: 1,
                ..base.clone()
            },
        ];
        for variant in variants {
            assert_ne!(claim, claim_leaf(&variant).unwrap());
            assert_ne!(commitment, entitlement_commitment(&variant, salt).unwrap());
        }
        assert_ne!(
            commitment,
            entitlement_commitment(&base, [0x99; SALT_BYTES]).unwrap()
        );
        assert_eq!(claim, claim_leaf(&base).unwrap());
    }
}
