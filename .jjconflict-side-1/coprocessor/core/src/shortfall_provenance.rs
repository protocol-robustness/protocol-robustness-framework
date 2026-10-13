//! Independent reconstruction helpers for the production Shortfall provenance boundary.
use crate::canonical::{domain_hash, CanonValue};
use serde_json::Value;
use sha3::{Digest, Keccak256};

pub const ASSET_DOMAIN: &str = "prf.evm-erc20-asset.v1";
pub const BINDING_DOMAIN: &str = "prf.onchain-shortfall-reserve-binding.v1";

fn kw(s: &str) -> CanonValue {
    CanonValue::keyword(s.trim_start_matches(':'))
}
fn st(s: &str) -> CanonValue {
    CanonValue::str(s)
}

pub fn evm_asset_root(chain_id: u64, token_address: &str) -> String {
    let body = CanonValue::map(vec![
        (kw("chain-id"), CanonValue::int(chain_id)),
        (kw("schema-version"), st("prf/evm-erc20-asset.v1")),
        (kw("token-address"), st(token_address)),
        (kw("unit"), kw("erc20-base-unit")),
    ]);
    format!("sha256:{}", domain_hash(ASSET_DOMAIN, &body))
}

pub fn solidity_snapshot_hash(input: &Value) -> String {
    let word = |n: &str| format!("{:064x}", n.parse::<u128>().unwrap());
    let address = |s: &str| format!("{:0>64}", s.trim_start_matches("0x"));
    let mut encoded = Vec::new();
    let words = [
        format!(
            "0x{}",
            hex::encode(keccak256(b"prf.onchain-shortfall-reserve.snapshot.v1"))
        ),
        word(input["chain-id"].as_str().unwrap()),
        address(input["reserve"].as_str().unwrap()),
        address(input["token-address"].as_str().unwrap()),
        word(input["snapshot-id"].as_str().unwrap()),
        input["operation-id"]
            .as_str()
            .unwrap()
            .trim_start_matches("0x")
            .to_owned(),
        address(input["allocator"].as_str().unwrap()),
        word(input["created-at-block"].as_str().unwrap()),
        word(input["gross-amount"].as_str().unwrap()),
        word(input["reserved-amount-before"].as_str().unwrap()),
        word(input["protected-amount"].as_str().unwrap()),
        word(input["available-amount"].as_str().unwrap()),
    ];
    for w in words {
        encoded.extend(hex::decode(w.trim_start_matches("0x")).unwrap());
    }
    format!("0x{}", hex::encode(keccak256(&encoded)))
}

pub fn validate_raw_reserve(input: &Value) -> Result<(), &'static str> {
    let chain = input["chain-id"]
        .as_str()
        .ok_or("chain-id")?
        .parse::<u64>()
        .map_err(|_| "chain-id")?;
    if chain > u64::MAX {
        return Err("chain-id");
    }
    for key in ["reserve", "token-address", "allocator"] {
        let address = input[key].as_str().ok_or("address")?;
        if address.len() != 42
            || !address.starts_with("0x")
            || !address[2..].bytes().all(|b| b.is_ascii_hexdigit())
        {
            return Err("address");
        }
    }
    let gross = input["gross-amount"]
        .as_str()
        .ok_or("gross-amount")?
        .parse::<u128>()
        .map_err(|_| "gross-amount")?;
    let reserved = input["reserved-amount-before"]
        .as_str()
        .ok_or("reserved-amount-before")?
        .parse::<u128>()
        .map_err(|_| "reserved-amount-before")?;
    let protected = input["protected-amount"]
        .as_str()
        .ok_or("protected-amount")?
        .parse::<u128>()
        .map_err(|_| "protected-amount")?;
    let available = input["available-amount"]
        .as_str()
        .ok_or("available-amount")?
        .parse::<u128>()
        .map_err(|_| "available-amount")?;
    if reserved.checked_add(protected).ok_or("accounting")? > gross {
        return Err("accounting");
    }
    if gross - reserved - protected != available {
        return Err("available-amount");
    }
    Ok(())
}

fn keccak256(bytes: &[u8]) -> Vec<u8> {
    Keccak256::digest(bytes).to_vec()
}
fn s(v: &Value, key: &str) -> CanonValue {
    CanonValue::str(v[key].as_str().unwrap())
}
fn kwv(v: &Value, key: &str) -> CanonValue {
    CanonValue::keyword(v[key].as_str().unwrap().trim_start_matches(':'))
}
fn u(v: &Value, key: &str) -> CanonValue {
    CanonValue::int(v[key].as_str().unwrap().parse::<u64>().unwrap())
}
fn policy() -> CanonValue {
    CanonValue::map(vec![
        (kw(":mode"), kw("pro-rata")),
        (kw(":rounding-policy"), kw("largest-remainder")),
        (
            kw(":fail-action-policy"),
            CanonValue::map(vec![
                (kw(":deferred-policy"), kw("same-ratio")),
                (kw(":haircut-policy"), kw("same-ratio")),
                (kw(":mode"), kw("pro-rata-treatment")),
            ]),
        ),
    ])
}
fn candidates(v: &Value) -> CanonValue {
    CanonValue::array(
        v["candidates"]
            .as_array()
            .unwrap()
            .iter()
            .map(|c| {
                let mut p = vec![
                    (kw(":claim/id"), s(c, "claim-id")),
                    (kw(":requested"), u(c, "requested")),
                    (kw(":eligibility"), kwv(c, "eligibility")),
                ];
                if c.get("ineligibility-reason").is_some() {
                    p.push((kw(":ineligibility/reason"), kwv(c, "ineligibility-reason")));
                }
                CanonValue::map(p)
            })
            .collect(),
    )
}

pub fn observation_root(v: &Value, asset_root: &str, snapshot_hash: &str) -> String {
    let body = CanonValue::map(vec![
        (kw(":allocator"), s(v, "allocator")),
        (kw(":asset/root"), st(asset_root)),
        (kw(":available-amount"), u(v, "available-amount")),
        (kw(":chain-id"), u(v, "chain-id")),
        (kw(":created-at-block"), u(v, "created-at-block")),
        (kw(":gross-amount"), u(v, "gross-amount")),
        (kw(":operation-id"), s(v, "operation-id")),
        (kw(":protected-amount"), u(v, "protected-amount")),
        (kw(":reserve"), s(v, "reserve")),
        (
            kw(":reserved-amount-before"),
            u(v, "reserved-amount-before"),
        ),
        (
            kw(":schema-version"),
            st("prf/onchain-shortfall-reserve-observation.v1"),
        ),
        (kw(":snapshot-hash"), st(snapshot_hash)),
        (kw(":snapshot-id"), u(v, "snapshot-id")),
        (
            kw(":source"),
            kw("onchain-shortfall-reserve-observation.v1"),
        ),
        (kw(":token-address"), s(v, "token-address")),
    ]);
    format!(
        "sha256:{}",
        domain_hash("prf.onchain-shortfall-reserve-observation.v1", &body)
    )
}

pub fn context_root(v: &Value, asset_root: &str, observation: &str) -> String {
    let body = CanonValue::map(vec![
        (
            kw(":allocation-context-root"),
            s(v, "allocation-context-root"),
        ),
        (kw(":allocation-policy"), policy()),
        (kw(":asset/root"), st(asset_root)),
        (kw(":available-amount"), u(v, "available-amount")),
        (kw(":availability/root"), st(observation)),
        (
            kw(":availability/source"),
            kw("onchain-shortfall-reserve-observation.v1"),
        ),
        (kw(":candidates"), candidates(v)),
        (kw(":schema-version"), st("prf/shortfall-context.v2")),
        (kw(":shortfall/profile"), kw("shortfall-pro-rata.v1")),
    ]);
    format!("sha256:{}", domain_hash("PRF_SHORTFALL_CONTEXT_V2", &body))
}

pub fn binding_root(observation_root: &str, context_root: &str, operation_id: &str) -> String {
    let body = CanonValue::map(vec![
        (kw("operation-id"), st(operation_id)),
        (kw("reserve-observation/root"), st(observation_root)),
        (
            kw("schema-version"),
            st("prf/onchain-shortfall-reserve-binding.v1"),
        ),
        (kw("shortfall/context-root"), st(context_root)),
    ]);
    format!("sha256:{}", domain_hash(BINDING_DOMAIN, &body))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn different_contracts_do_not_share_asset_identity() {
        assert_ne!(
            evm_asset_root(31337, "0x1111111111111111111111111111111111111111"),
            evm_asset_root(31337, "0x2222222222222222222222222222222222222222")
        );
    }
    #[test]
    fn shared_fixture_reconstructs_all_roots_and_mutations_fail() {
        let raw: Value = serde_json::from_str(include_str!(
            "../tests/fixtures/onchain-shortfall-reserve-provenance.json"
        ))
        .unwrap();
        validate_raw_reserve(&raw).unwrap();
        let asset = evm_asset_root(
            raw["chain-id"].as_str().unwrap().parse().unwrap(),
            raw["token-address"].as_str().unwrap(),
        );
        let snapshot = solidity_snapshot_hash(&raw);
        let observation = observation_root(&raw, &asset, &snapshot);
        let context = context_root(&raw, &asset, &observation);
        let binding = binding_root(
            &observation,
            &context,
            raw["operation-id"].as_str().unwrap(),
        );
        let expected = &raw["expected"];
        for (key, actual) in [
            ("snapshot-hash", snapshot.clone()),
            ("asset-root", asset.clone()),
            ("observation-root", observation.clone()),
            ("context-root", context.clone()),
            ("binding-root", binding.clone()),
        ] {
            assert_eq!(actual, expected[key].as_str().unwrap(), "{key}");
        }
        assert_eq!(
            raw["available-amount"].as_str(),
            expected["available-amount"].as_str()
        );
        let mut changed = raw.clone();
        changed["available-amount"] = Value::String("69".into());
        assert!(validate_raw_reserve(&changed).is_err());
        changed["available-amount"] = Value::String("70".into());
        changed["reserved-amount-before"] = Value::String("91".into());
        assert!(validate_raw_reserve(&changed).is_err());
        changed["reserved-amount-before"] = Value::String("20".into());
        changed["reserve"] = Value::String("0x4444444444444444444444444444444444444444".into());
        assert!(validate_raw_reserve(&changed).is_ok());
        assert_ne!(
            solidity_snapshot_hash(&raw),
            solidity_snapshot_hash(&changed)
        );
        changed["reserve"] = Value::String("0x1234".into());
        assert!(validate_raw_reserve(&changed).is_err());
        changed["reserve"] = raw["reserve"].clone();
        changed["chain-id"] = Value::String("not-a-chain".into());
        assert!(validate_raw_reserve(&changed).is_err());
        changed["chain-id"] = Value::String("31338".into());
        assert!(validate_raw_reserve(&changed).is_ok());
        assert_ne!(
            evm_asset_root(31337, raw["token-address"].as_str().unwrap()),
            evm_asset_root(31338, raw["token-address"].as_str().unwrap())
        );
        changed["chain-id"] = raw["chain-id"].clone();
        changed["token-address"] =
            Value::String("0x5555555555555555555555555555555555555555".into());
        assert_ne!(
            evm_asset_root(31337, raw["token-address"].as_str().unwrap()),
            evm_asset_root(31337, changed["token-address"].as_str().unwrap())
        );
        let changed_asset = evm_asset_root(31337, changed["token-address"].as_str().unwrap());
        let changed_snapshot = solidity_snapshot_hash(&changed);
        let changed_observation = observation_root(&changed, &changed_asset, &changed_snapshot);
        let changed_context = context_root(&changed, &changed_asset, &changed_observation);
        assert_ne!(snapshot, changed_snapshot);
        assert_ne!(observation, changed_observation);
        assert_ne!(context, changed_context);
        assert_ne!(
            binding,
            binding_root(
                &changed_observation,
                &changed_context,
                changed["operation-id"].as_str().unwrap()
            )
        );
    }

    #[test]
    fn binding_is_sensitive_to_all_minimal_inputs() {
        let root = binding_root("sha256:a", "sha256:b", "0x01");
        assert_ne!(root, binding_root("sha256:c", "sha256:b", "0x01"));
        assert_ne!(root, binding_root("sha256:a", "sha256:c", "0x01"));
        assert_ne!(root, binding_root("sha256:a", "sha256:b", "0x02"));
    }
}
