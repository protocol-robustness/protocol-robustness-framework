//! Realized-allocation-statement I/O: canonical input document -> statement
//! JSON projection.
//!
//! This is the shared execution path for the native CLI and the SP1 guest, so
//! the guest is a thin wrapper (condition B) and
//! `native output == SP1 guest output` holds on the realized statement.

use crate::canonical::CanonValue;
use crate::kernel::{self, Context};
use crate::lifecycle;
use crate::realized_fill::{self, RealizedAllocation};
use crate::realized_statement;
use num_bigint::BigInt;
use serde_json::{json, Value};
use std::collections::HashSet;

/// Parse the canonical realized-statement input document.
///
/// Canonical document:
/// ```json
/// {
///   "allocation-context": { ...allocation-kernel input doc... },
///   "available": "100",
///   "requested": { "A": "50", "B": "30", "C": "20" },
///   "policy": { "mode": "pro-rata", "rounding-policy": "largest-remainder" },
///   "fail-action-policy": { "mode": "pro-rata-treatment",
///                            "deferred-policy": "same-ratio",
///                            "haircut-policy": "same-ratio" },
///   "round-state": "result-accepted"
/// }
/// ```
///
/// Fails closed on malformed input, returning a stable rejection envelope
/// (never a partial statement).
pub fn run_realized_statement(input: &Value) -> Value {
    run_realized_statement_with_details(input, false)
}

/// Run the statement kernel and, when requested, include semantic rows and a
/// deterministic audit projection. The compact result remains the default
/// public statement projection; details are diagnostic/conformance material and
/// are not additional statement-root inputs.
pub fn run_realized_statement_with_details(input: &Value, detailed: bool) -> Value {
    let reject = |classification: &str, reason: String| -> Value {
        json!({
            "result/status": "rejected",
            "rejection/classification": classification,
            "rejection/reason": reason,
        })
    };

    let ctx_value = match input.get("allocation-context") {
        Some(v) => v,
        None => {
            return reject(
                "missing-allocation-context",
                "allocation-context required".to_string(),
            )
        }
    };
    let ctx: Context = match kernel::parse_context(ctx_value) {
        Ok(c) => c,
        Err(e) => return reject(e.classification, e.reason),
    };

    let available: i64 = match input.get("available") {
        Some(Value::String(s)) => match s.parse::<i64>() {
            Ok(v) => v,
            Err(_) => {
                return reject(
                    "malformed-available",
                    "available must be a non-negative integer".to_string(),
                )
            }
        },
        Some(Value::Number(n)) => match n.as_i64() {
            Some(v) => v,
            None => {
                return reject(
                    "malformed-available",
                    "available must be a non-negative integer".to_string(),
                )
            }
        },
        _ => {
            return reject(
                "malformed-available",
                "available must be a non-negative integer".to_string(),
            )
        }
    };
    if available < 0 {
        return reject(
            "malformed-available",
            "available must be a non-negative integer".to_string(),
        );
    }

    let requested: Vec<(String, i64)> = match input.get("requested").and_then(|v| v.as_object()) {
        Some(map) => {
            let mut out = Vec::with_capacity(map.len());
            for (k, v) in map {
                let amt: i64 = match v {
                    Value::String(s) => s.parse::<i64>().unwrap_or(-1),
                    Value::Number(n) => n.as_i64().unwrap_or(-1),
                    _ => -1,
                };
                if amt < 0 {
                    return reject(
                        "malformed-requested",
                        format!(
                            "requested amount for claim {} must be a non-negative integer",
                            k
                        ),
                    );
                }
                out.push((k.clone(), amt));
            }
            out
        }
        None => {
            return reject(
                "malformed-requested",
                "requested must be an object".to_string(),
            );
        }
    };

    let context_claim_ids: HashSet<&str> = ctx
        .claimants
        .iter()
        .map(|claim| claim.claim_id.as_str())
        .collect();
    if let Some((claim_id, _)) = requested
        .iter()
        .find(|(claim_id, _)| !context_claim_ids.contains(claim_id.as_str()))
    {
        return reject(
            "unknown-request-claim-id",
            format!(
                "requested claim {} is absent from allocation context",
                claim_id
            ),
        );
    }
    let total_requested = match requested
        .iter()
        .try_fold(0_i64, |total, (_, amount)| total.checked_add(*amount))
    {
        Some(total) => total,
        None => {
            return reject(
                "requested-total-overflow",
                "total requested exceeds supported integer range".to_string(),
            )
        }
    };
    if available > total_requested {
        return reject(
            "available-exceeds-total-requested",
            "available must not exceed total requested".to_string(),
        );
    }

    let policy_pairs = match parse_keyword_map(input.get("policy")) {
        Ok(p) => p,
        Err(rejection) => return rejection,
    };
    let fail_action_policy = match input.get("fail-action-policy") {
        Some(v) => match parse_keyword_map(Some(v)) {
            Ok(p) => Some(p),
            Err(rejection) => return rejection,
        },
        None => None,
    };
    if let Some(rejection) =
        validate_supported_profile(&policy_pairs, fail_action_policy.as_deref())
    {
        return rejection;
    }

    let round_state = input.get("round-state");
    let rl = lifecycle::round_lifecycle(round_state);
    if rl.lifecycle_assertion_status != "passing" {
        return reject(
            "invalid-round-lifecycle",
            "round-state must be a recognized lifecycle state".to_string(),
        );
    }
    let lc = realized_statement::lifecycle_canon_value(&rl);

    // Realize the partial fill (semantics layer), fail-closed on malformed.
    let unmet_request = fail_action_policy.as_deref().is_some_and(|pairs| {
        pairs.len() == 1 && pairs[0] == ("mode".to_owned(), CanonValue::keyword("unmet-request"))
    });
    let mut realized: RealizedAllocation = match realized_fill::partial_fill(available, &requested) {
        Ok(r) => r,
        Err(e) => return reject("realization-failed", e),
    };
    // This explicitly selected profile describes requests, not continuing
    // obligations. Preserve the unmet amount in the existing unrealized field;
    // never promote it into a deferred claim or funded entitlement.
    if unmet_request {
        for participant in &mut realized.participants {
            participant.unrealized = participant.requested - participant.filled;
            participant.deferred = 0;
        }
    }

    let stmt = realized_statement::build_statement(
        &ctx,
        &requested,
        &policy_pairs,
        fail_action_policy.as_deref(),
        &lc,
        &realized,
    );

    let compact = json!({
        "result/status": "passing",
        "schema-version": realized_statement::SCHEMA_VERSION,
        "allocation-context-root": stmt.allocation_context_root,
        "request-set-root": stmt.request_set_root,
        "allocation-policy-root": stmt.allocation_policy_root,
        "realized-results-root": stmt.realized_results_root,
        "fail-action-policy-root": stmt.fail_action_policy_root,
        "round-lifecycle-root": stmt.round_lifecycle_root,
        "statement-root": stmt.statement_root,
        "all-active": stmt.all_active,
    });
    if !detailed {
        return compact;
    }

    let mut rows = realized
        .participants
        .iter()
        .map(|p| {
            json!({
                "claim/id": p.claim_id,
                "requested": p.requested,
                "filled": p.filled,
                "deferred": p.deferred,
                "haircut": p.haircut,
                "unrealized": p.unrealized,
                "disposition": p.disposition().keyword_str(),
            })
        })
        .collect::<Vec<_>>();
    rows.sort_by(|a, b| a["claim/id"].as_str().cmp(&b["claim/id"].as_str()));
    let requested_total: i64 = requested.iter().map(|(_, amount)| amount).sum();
    let filled_total: i64 = realized.participants.iter().map(|p| p.filled).sum();
    let deferred_total: i64 = realized.participants.iter().map(|p| p.deferred).sum();
    let haircut_total: i64 = realized.participants.iter().map(|p| p.haircut).sum();
    let unrealized_total: i64 = realized.participants.iter().map(|p| p.unrealized).sum();
    Some(compact).map(|mut result| {
        result["details"] = json!({
            "projection/schema": "realized-partial-fill-details.v1",
            "claim-order": rows.iter().map(|row| row["claim/id"].clone()).collect::<Vec<_>>(),
            "realized-results": rows,
            "audit": {
                "available": available,
                "requested-total": requested_total,
                "filled-total": filled_total,
                "deferred-total": deferred_total,
                "haircut-total": haircut_total,
                "conservation": filled_total + deferred_total + haircut_total + unrealized_total == requested_total,
                "supported-profile": if unmet_request { "largest-remainder-unmet-request-pro-rata.v1" } else { "largest-remainder-deferred-pro-rata.v1" },
            },
            "committed-roots": {
                "allocation-context-root": stmt.allocation_context_root,
                "request-set-root": stmt.request_set_root,
                "allocation-policy-root": stmt.allocation_policy_root,
                "realized-results-root": stmt.realized_results_root,
                "fail-action-policy-root": stmt.fail_action_policy_root,
                "round-lifecycle-root": stmt.round_lifecycle_root,
                "statement-root": stmt.statement_root,
            },
        });
        if unmet_request {
            result["details"]["audit"]["unmet-request-total"] = json!(unrealized_total);
        }
        result
    }).unwrap()
}

/// Validate the deliberately narrow external-assurance profile. Unsupported
/// policies reject; they must not silently normalize into this profile.
fn validate_supported_profile(
    policy: &[(String, CanonValue)],
    fail_action_policy: Option<&[(String, CanonValue)]>,
) -> Option<Value> {
    let keyword_is = |pairs: &[(String, CanonValue)], key: &str, expected: &str| {
        pairs
            .iter()
            .find(|(name, _)| name == key)
            .map(|(_, value)| value)
            == Some(&CanonValue::Keyword(expected.to_string()))
    };
    let supported_policy = policy.len() == 2
        && keyword_is(policy, "mode", "pro-rata")
        && keyword_is(policy, "rounding-policy", "largest-remainder");
    let supported_fail_action = match fail_action_policy {
        None => true,
        Some(pairs) => {
            (pairs.len() == 1 && keyword_is(pairs, "mode", "unmet-request")) || (pairs.len() == 3
                && keyword_is(pairs, "mode", "pro-rata-treatment")
                && keyword_is(pairs, "deferred-policy", "same-ratio")
                && keyword_is(pairs, "haircut-policy", "same-ratio"))
        }
    };
    if supported_policy && supported_fail_action {
        None
    } else {
        Some(json!({
            "result/status": "rejected",
            "rejection/classification": "unsupported-profile-policy",
            "rejection/reason": "only largest-remainder-deferred-pro-rata.v1 is supported"
        }))
    }
}

/// Parse a JSON object into (string-key, CanonValue-keyword) policy pairs,
/// using `?` semantics: returns Err on malformed input.
fn parse_keyword_map(value: Option<&Value>) -> Result<Vec<(String, CanonValue)>, Value> {
    let map = match value {
        Some(v) => v.as_object().ok_or_else(|| {
            json!({"result/status": "rejected",
                                  "rejection/classification": "malformed-policy",
                                  "rejection/reason": "policy must be an object"})
        })?,
        None => return Ok(Vec::new()),
    };
    let mut out = Vec::with_capacity(map.len());
    for (k, v) in map {
        let s = v
            .as_str()
            .ok_or_else(|| json!({"result/status": "rejected",
                                  "rejection/classification": "malformed-policy",
                                  "rejection/reason": format!("policy value for {} must be a string", k)}))?;
        out.push((k.clone(), CanonValue::keyword(s)));
    }
    Ok(out)
}

/// BigInt placeholder to keep exact-integer policy/amount semantics available
/// for future extensions (all current statement roots are string-hash based).
#[allow(dead_code)]
fn _bigint_placeholder() -> BigInt {
    BigInt::from(0)
}
