use std::fs;

use crate::edn::Value;

fn fixtures_dir() -> String {
    let manifest_dir = env!("CARGO_MANIFEST_DIR");
    format!("{}/../../fixtures", manifest_dir)
}

pub fn load_fixture(name: &str) -> Value {
    let path = format!("{}/{}.edn", fixtures_dir(), name);
    let content =
        fs::read_to_string(&path).unwrap_or_else(|_| panic!("failed to read fixture: {}", path));
    crate::edn::parse(&content).expect("failed to parse fixture EDN")
}

pub fn fixture_id(fx: &Value) -> String {
    crate::edn::map_get(fx, "fixture/id")
        .and_then(|v| match v {
            Value::Str(s) => Some(s.clone()),
            _ => None,
        })
        .unwrap_or_default()
}

pub fn fixture_kind(fx: &Value) -> String {
    crate::edn::map_get(fx, "fixture/kind")
        .and_then(|v| match v {
            Value::Keyword(_, n) => Some(n.clone()),
            _ => None,
        })
        .unwrap_or_default()
}

pub fn fixture_profile(fx: &Value) -> String {
    crate::edn::map_get(fx, "fixture/profile")
        .and_then(|v| match v {
            Value::Str(s) => Some(s.clone()),
            _ => None,
        })
        .unwrap_or_default()
}

pub fn state_before(fx: &Value) -> &Value {
    crate::edn::map_get(fx, "state-before").expect("fixture must have state-before")
}

pub fn command(fx: &Value) -> &Value {
    crate::edn::map_get(fx, "command").expect("fixture must have command")
}

pub fn semantic_context(fx: &Value) -> &Value {
    crate::edn::map_get(fx, "semantic-context").expect("fixture must have semantic-context")
}

pub fn state_after(fx: &Value) -> Option<&Value> {
    crate::edn::map_get(fx, "state-after")
}

pub fn pinned_hex(fx: &Value, key: &str) -> String {
    let v = crate::edn::map_get(fx, key);
    match v {
        Some(Value::Str(s)) => s.clone(),
        _ => String::new(),
    }
}

pub fn pinned_value<'a>(fx: &'a Value, key: &str) -> Option<&'a Value> {
    crate::edn::map_get(fx, key)
}
