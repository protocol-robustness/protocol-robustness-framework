use crate::canonical;
use crate::edn::{map_get, Value};

pub const ORDERING_V2_SCHEMA: &str = "transaction-ordering.v2";
pub const ORDERING_V2_DOMAIN: &str = "prf.transaction-ordering.v2";
pub const CHANGE_IDENTITY_DOMAIN: &str = "prf.transaction-ordering-change-identity.v1";

/// Build the change-identity basis map from an ordering record.
/// Includes only: scope, conflict-key, action, input-root.
pub fn change_identity_basis(ordering: &Value) -> Value {
    let scope = map_get(ordering, "transaction/scope")
        .cloned()
        .unwrap_or(Value::Nil);
    let conflict_key = map_get(ordering, "transaction/conflict-key")
        .cloned()
        .unwrap_or(Value::Vec(vec![]));
    let action = map_get(ordering, "transaction/action")
        .cloned()
        .unwrap_or(Value::Nil);
    let input_root = map_get(ordering, "transaction/input-root")
        .cloned()
        .unwrap_or(Value::Nil);

    Value::Map(vec![
        (
            Value::Keyword(Some("transaction".to_string()), "scope".to_string()),
            scope,
        ),
        (
            Value::Keyword(Some("transaction".to_string()), "conflict-key".to_string()),
            conflict_key,
        ),
        (
            Value::Keyword(Some("transaction".to_string()), "action".to_string()),
            action,
        ),
        (
            Value::Keyword(Some("transaction".to_string()), "input-root".to_string()),
            input_root,
        ),
    ])
}

pub fn change_identity_hash(ordering: &Value) -> String {
    let basis = change_identity_basis(ordering);
    canonical::domain_hash(CHANGE_IDENTITY_DOMAIN, &basis)
}

/// Build the unsigned v2 ordering projection: all fields except
/// :transaction-ordering/hash
pub fn unsigned_ordering_projection_v2(ordering: &Value) -> Value {
    match ordering {
        Value::Map(pairs) => {
            let filtered: Vec<(Value, Value)> = pairs
                .iter()
                .filter(|(k, _)| {
                    !matches!(
                        k,
                        Value::Keyword(Some(ns), name) if ns == "transaction-ordering" && name == "hash"
                    )
                })
                .cloned()
                .collect();
            Value::Map(filtered)
        }
        _ => Value::Nil,
    }
}

pub fn ordering_hash(ordering: &Value) -> String {
    let proj = unsigned_ordering_projection_v2(ordering);
    canonical::domain_hash(ORDERING_V2_DOMAIN, &proj)
}
