//! JSON projection for the kernel public-value output.
//!
//! Wire rules:
//!   - arbitrary-size integers are decimal strings;
//!   - hashes are lowercase 0x-prefixed 32-byte hex;
//!   - keywords are strings ("ns/name" or "name");
//!   - ratios contain decimal-string numerator and denominator;
//!   - ordered collections are JSON arrays;
//!   - JSON object property ordering has no semantic significance.

use crate::kernel::PublicResult;
use serde_json::{json, Map, Value};

fn norm_hash(h: &str) -> String {
    if h.starts_with("0x") {
        h.to_string()
    } else {
        format!("0x{}", h)
    }
}

/// Project the public result into the JSON wire format.
pub fn public_result_to_json(result: &PublicResult) -> Value {
    let mut assertions = Vec::new();
    for a in &result.assertions {
        assertions.push(json!({
            "assertion/id": a.id,
            "assertion/result": a.result,
        }));
    }

    let mut m = Map::new();
    m.insert("result/status".into(), Value::String(result.status.clone()));
    m.insert(
        "allocation-context-hash".into(),
        Value::String(norm_hash(&result.allocation_context_hash)),
    );
    m.insert(
        "claimant-set-root".into(),
        Value::String(norm_hash(&result.claimant_set_root)),
    );
    m.insert(
        "outcome-set-root".into(),
        Value::String(norm_hash(&result.outcome_set_root)),
    );
    m.insert(
        "proposed-rates-root".into(),
        Value::String(norm_hash(&result.proposed_rates_root)),
    );
    m.insert(
        "rate-derived-summary-hash".into(),
        Value::String(norm_hash(&result.rate_derived_summary_hash)),
    );

    m.insert("assertions".into(), Value::Array(assertions));

    m.insert(
        "selection-receipt".into(),
        json!({
            "algorithm": result.selection_receipt.algorithm,
            "outcome-count": result.selection_receipt.outcome_count.to_string(),
            "accepted-counter": result.selection_receipt.accepted_counter.to_string(),
            "candidate-digest": norm_hash(&result.selection_receipt.candidate_digest),
            "selected-index": result.selection_receipt.selected_index.to_string(),
            "selected-outcome-id": result.selection_receipt.selected_outcome_id,
            "selected-outcome-hash": norm_hash(&result.selection_receipt.selected_outcome_hash),
        }),
    );
    m.insert(
        "selected-outcome-id".into(),
        Value::String(result.selected_outcome_id.clone()),
    );
    m.insert(
        "selected-outcome-index".into(),
        Value::String(result.selected_outcome_index.to_string()),
    );
    m.insert(
        "selected-outcome-hash".into(),
        Value::String(norm_hash(&result.selected_outcome_hash)),
    );
    m.insert(
        "result-root".into(),
        Value::String(norm_hash(&result.result_root)),
    );
    m.insert(
        "total-allocated".into(),
        Value::String(result.total_allocated.to_string()),
    );
    m.insert(
        "residual-capacity".into(),
        Value::String(result.residual_capacity.to_string()),
    );
    m.insert(
        "round-lifecycle".into(),
        json!({
            "round-state": result.round_lifecycle.round_state,
            "derived-state": result.round_lifecycle.derived_state,
            "lifecycle-profile-id": result.round_lifecycle.lifecycle_profile_id,
            "lifecycle-profile-version": result.round_lifecycle.lifecycle_profile_version.to_string(),
            "cancellation-window-schema": result.round_lifecycle.cancellation_window_schema,
            "cancellation-window": result.round_lifecycle.cancellation_window,
            "cancellation-possible": result.round_lifecycle.cancellation_possible,
            "cancellation-blocking-reasons": result.round_lifecycle.cancellation_blocking_reasons,
            "lifecycle-assertion-status": result.round_lifecycle.lifecycle_assertion_status,
            "evidence-status": result.round_lifecycle.evidence_status,
            "assurance": result.round_lifecycle.assurance,
        }),
    );
    m.insert(
        "certificate-assertions-digest".into(),
        Value::String(norm_hash(&result.certificate_assertions_digest)),
    );
    m.insert(
        "allocation-kernel-version".into(),
        Value::String(result.kernel_version.clone()),
    );
    m.insert(
        "selection-algorithm".into(),
        Value::String(result.selection_algorithm.clone()),
    );

    if let Some(class) = &result.rejection_classification {
        m.insert(
            "rejection/classification".into(),
            Value::String(class.clone()),
        );
    }

    Value::Object(m)
}
