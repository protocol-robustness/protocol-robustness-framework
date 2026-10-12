//! Pure Protected Capital lifecycle transition rules.
//!
//! This crate owns only lifecycle state transitions. Source binding, assurance,
//! canonical artifacts, custody, EVM execution, and application policy are
//! supplied by adapters and are not implemented here.

use num_bigint::BigInt;
use num_traits::Zero;
use serde_json::Value;

#[derive(Clone, Debug, PartialEq)]
pub struct State {
    pub status: String,
    pub operation_id: Option<String>,
    pub reserved_amount: BigInt,
    pub expires_at: Option<u64>,
    pub funded_amount: BigInt,
    pub entitlement_count: u64,
    pub claimed_count: u64,
    pub liability_total: BigInt,
    pub liability_remaining: BigInt,
    pub claimed_ids: Vec<String>,
}

impl Default for State {
    fn default() -> Self {
        Self {
            status: "none".into(),
            operation_id: None,
            reserved_amount: BigInt::zero(),
            expires_at: None,
            funded_amount: BigInt::zero(),
            entitlement_count: 0,
            claimed_count: 0,
            liability_total: BigInt::zero(),
            liability_remaining: BigInt::zero(),
            claimed_ids: Vec::new(),
        }
    }
}

fn field<'a>(value: &'a Value, key: &str) -> Option<&'a Value> {
    value.as_object()?.get(key)
}
fn string(value: &Value, key: &str) -> Option<String> {
    field(value, key)?.as_str().map(str::to_owned)
}
fn uint(value: &Value, key: &str) -> Option<u64> {
    field(value, key)?.as_u64()
}
fn boolean(value: &Value, key: &str) -> bool {
    field(value, key).and_then(Value::as_bool).unwrap_or(false)
}
fn parse_amount(value: &Value, key: &str, default: Option<BigInt>) -> Option<BigInt> {
    field(value, key)
        .and_then(|v| {
            v.as_u64()
                .map(BigInt::from)
                .or_else(|| v.as_str()?.parse().ok())
        })
        .or(default)
}
fn same_operation(state: &State, command: &Value) -> bool {
    match (
        state.operation_id.as_deref(),
        string(command, "operation-id"),
    ) {
        (Some(state_id), Some(command_id)) => !state_id.is_empty() && state_id == command_id,
        _ => false,
    }
}
fn rejected(reason: &'static str) -> Result<State, &'static str> {
    Err(reason)
}

/// Apply a closed lifecycle command. Errors leave the input state untouched.
pub fn transition(state: &State, command: &Value) -> Result<State, &'static str> {
    let mut next = state.clone();
    let kind = string(command, "type").unwrap_or_default();
    let requested_amount =
        parse_amount(command, "amount", Some(BigInt::zero())).unwrap_or_default();
    let op = string(command, "operation-id");

    match kind.as_str() {
        "reserve" => {
            if state.status != "none" {
                return rejected("operation-not-new");
            }
            if op.as_deref().is_none_or(str::is_empty) {
                return rejected("invalid-operation-id");
            }
            if !boolean(command, "authorized?") {
                return rejected("not-authorized");
            }
            if requested_amount <= BigInt::zero() {
                return rejected("invalid-amount");
            }
            let expires = uint(command, "expires-at").unwrap_or(0);
            if expires <= uint(command, "now").unwrap_or(0) {
                return rejected("invalid-expiry");
            }
            next.status = "reserved".into();
            next.operation_id = op;
            next.reserved_amount = requested_amount;
            next.expires_at = Some(expires);
        }
        "release" => {
            if !same_operation(state, command) {
                return rejected("operation-mismatch");
            }
            if state.status != "reserved" {
                return rejected("reservation-not-live");
            }
            if !boolean(command, "authorized?") {
                return rejected("not-authorized");
            }
            next.status = "released".into();
            next.reserved_amount = BigInt::zero();
        }
        "expire" => {
            if !same_operation(state, command) {
                return rejected("operation-mismatch");
            }
            if state.status != "reserved" {
                return rejected("reservation-not-live");
            }
            if uint(command, "now").unwrap_or(0) < state.expires_at.unwrap_or(0) {
                return rejected("reservation-not-expired");
            }
            next.status = "expired".into();
            next.reserved_amount = BigInt::zero();
        }
        "fund" => {
            if !same_operation(state, command) {
                return rejected("operation-mismatch");
            }
            if state.status != "reserved" {
                return rejected("reservation-not-live");
            }
            if uint(command, "now").unwrap_or(0) >= state.expires_at.unwrap_or(0) {
                return rejected("reservation-expired");
            }
            if requested_amount != state.reserved_amount {
                return rejected("funding-amount-mismatch");
            }
            if parse_amount(command, "custody-received", None).as_ref()
                != Some(&state.reserved_amount)
            {
                return rejected("custody-delta-mismatch");
            }
            next.status = "funded".into();
            next.reserved_amount = BigInt::zero();
            next.funded_amount = requested_amount;
        }
        "activate" => {
            if !same_operation(state, command) {
                return rejected("operation-mismatch");
            }
            if state.status != "funded" {
                return rejected("operation-not-funded");
            }
            if !boolean(command, "admitted?") {
                return rejected("basis-not-admitted");
            }
            if parse_amount(command, "basis-total", None).as_ref() != Some(&state.funded_amount) {
                return rejected("basis-total-mismatch");
            }
            let count = uint(command, "entitlement-count").unwrap_or(0);
            if count == 0 {
                return rejected("invalid-entitlement-count");
            }
            next.status = "activated".into();
            next.entitlement_count = count;
            next.liability_total = state.funded_amount.clone();
            next.liability_remaining = state.funded_amount.clone();
        }
        "cancel" => {
            if !same_operation(state, command) {
                return rejected("operation-mismatch");
            }
            if state.status != "funded" {
                return rejected("operation-not-cancellable");
            }
            if !boolean(command, "authorized?") {
                return rejected("not-authorized");
            }
            if parse_amount(command, "custody-refunded", None).as_ref()
                != Some(&state.funded_amount)
            {
                return rejected("refund-delta-mismatch");
            }
            next.status = "cancelled-refunded".into();
            next.funded_amount = BigInt::zero();
        }
        "withdraw" => {
            if !same_operation(state, command) {
                return rejected("operation-mismatch");
            }
            if state.status != "activated" {
                return rejected("operation-not-active");
            }
            let entitlement = string(command, "entitlement-id").unwrap_or_default();
            if state.claimed_ids.contains(&entitlement) {
                return rejected("entitlement-already-claimed");
            }
            if entitlement.is_empty() {
                return rejected("invalid-entitlement-id");
            }
            if !boolean(command, "admitted?") {
                return rejected("withdrawal-not-admitted");
            }
            if state.claimed_count >= state.entitlement_count {
                return rejected("entitlement-count-exhausted");
            }
            if requested_amount <= BigInt::zero() {
                return rejected("invalid-amount");
            }
            if requested_amount > state.liability_remaining {
                return rejected("liability-exceeded");
            }
            if parse_amount(command, "custody-sent", None).as_ref() != Some(&requested_amount) {
                return rejected("custody-delta-mismatch");
            }
            next.claimed_count += 1;
            next.claimed_ids.push(entitlement);
            next.liability_remaining -= requested_amount;
        }
        "close" => {
            if !same_operation(state, command) {
                return rejected("operation-mismatch");
            }
            if state.status != "activated" {
                return rejected("operation-not-active");
            }
            if state.claimed_count != state.entitlement_count
                || !state.liability_remaining.is_zero()
            {
                return rejected("liability-outstanding");
            }
            next.status = "closed".into();
        }
        _ => return rejected("unknown-command"),
    }
    Ok(next)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    #[test]
    fn shared_transition_vectors() {
        for line in
            include_str!("../../test-vectors/capital-lifecycle-transitions-v1.jsonl").lines()
        {
            let vector: Value = serde_json::from_str(line).expect("valid transition vector JSON");
            let id = vector["id"].as_str().unwrap_or("unnamed");
            let mut state = State::default();
            for (key, value) in vector["state"].as_object().unwrap() {
                match key.as_str() {
                    "status" => state.status = value.as_str().unwrap().into(),
                    "operation-id" => state.operation_id = value.as_str().map(str::to_owned),
                    "reserved-amount" => state.reserved_amount = value.as_u64().unwrap().into(),
                    "expires-at" => state.expires_at = value.as_u64(),
                    "funded-amount" => state.funded_amount = value.as_u64().unwrap().into(),
                    "entitlement-count" => state.entitlement_count = value.as_u64().unwrap(),
                    "claimed-count" => state.claimed_count = value.as_u64().unwrap(),
                    "liability-total" => state.liability_total = value.as_u64().unwrap().into(),
                    "liability-remaining" => {
                        state.liability_remaining = value.as_u64().unwrap().into()
                    }
                    "claimed-ids" => {
                        state.claimed_ids = value
                            .as_array()
                            .unwrap()
                            .iter()
                            .map(|v| v.as_str().unwrap().to_owned())
                            .collect()
                    }
                    _ => panic!("{id}: unknown state field {key}"),
                }
            }
            let expected_ok = vector["expected"]["accepted?"].as_bool().unwrap();
            let result = transition(&state, &vector["command"]);
            assert_eq!(result.is_ok(), expected_ok, "{id}");
            if let Some(reason) = vector["expected"]["reason"].as_str() {
                assert_eq!(result.unwrap_err(), reason, "{id}");
            } else {
                let actual = result.unwrap();
                for (key, expected) in vector["expected"]["state"].as_object().unwrap() {
                    match key.as_str() {
                        "status" => assert_eq!(actual.status, expected.as_str().unwrap(), "{id}"),
                        "operation-id" => {
                            assert_eq!(actual.operation_id.as_deref(), expected.as_str(), "{id}")
                        }
                        "reserved-amount" => assert_eq!(
                            actual.reserved_amount,
                            BigInt::from(expected.as_u64().unwrap()),
                            "{id}"
                        ),
                        "expires-at" => assert_eq!(actual.expires_at, expected.as_u64(), "{id}"),
                        "funded-amount" => assert_eq!(
                            actual.funded_amount,
                            BigInt::from(expected.as_u64().unwrap()),
                            "{id}"
                        ),
                        "entitlement-count" => {
                            assert_eq!(actual.entitlement_count, expected.as_u64().unwrap(), "{id}")
                        }
                        "claimed-count" => {
                            assert_eq!(actual.claimed_count, expected.as_u64().unwrap(), "{id}")
                        }
                        "liability-total" => assert_eq!(
                            actual.liability_total,
                            BigInt::from(expected.as_u64().unwrap()),
                            "{id}"
                        ),
                        "liability-remaining" => assert_eq!(
                            actual.liability_remaining,
                            BigInt::from(expected.as_u64().unwrap()),
                            "{id}"
                        ),
                        "claimed-ids" => assert_eq!(
                            actual.claimed_ids,
                            expected
                                .as_array()
                                .unwrap()
                                .iter()
                                .map(|v| v.as_str().unwrap().to_owned())
                                .collect::<Vec<_>>(),
                            "{id}"
                        ),
                        _ => panic!("{id}: unknown expected field {key}"),
                    }
                }
            }
        }
    }
}
