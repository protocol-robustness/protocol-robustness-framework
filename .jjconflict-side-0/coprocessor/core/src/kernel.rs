//! The independent Rust allocation kernel.
//!
//! Reproduces the PRF reference computation byte-for-byte at the declared
//! public-output boundary. Consumes the same canonical JSON input document as
//! the PRF `allocation verify-proposal` command and emits the same public-value
//! projection.

use crate::assertions::AssertionResult;
use crate::canonical::{hex32_to_bytes, CanonValue};
use crate::lifecycle;
use crate::roots;
use crate::selection;
use num_bigint::BigInt;
use num_traits::{Signed, Zero};
pub use prf_pro_rata_allocation::allocation_context::{
    context_preimage, result_leaf, SELECTION_ALGORITHM,
};
use prf_pro_rata_allocation::allocation_evaluation::evaluate_allocation;
use prf_pro_rata_allocation::canonical_order::{sort_claimants, sort_outcomes};
use prf_pro_rata_allocation::context_validation::{
    rates_for_outcomes, validate_capacity, validate_claimants, validate_outcomes, validate_rate,
    validate_weight_totals,
};

/// Kernel version, must equal the PRF `allocation-kernel.v1`.
pub const KERNEL_VERSION: &str = "allocation-kernel.v1";

/// A structured kernel error carrying a stable rejection classification.
#[derive(Clone, Debug)]
pub struct KernelError {
    pub classification: &'static str,
    pub reason: String,
}

impl KernelError {
    pub fn new(classification: &'static str, reason: impl Into<String>) -> Self {
        KernelError {
            classification,
            reason: reason.into(),
        }
    }
}

impl std::fmt::Display for KernelError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.classification, self.reason)
    }
}

pub use crate::assertions::Committed;
pub use prf_pro_rata_allocation::exact_ratio::RateEntry;
pub use prf_pro_rata_allocation::kernel_types::{
    AllocationEntry, Claimant, Context, Outcome, Policy,
};

/// The public-value projection emitted by the kernel.
#[derive(Clone, Debug)]
pub struct PublicResult {
    pub status: String,
    pub allocation_context_hash: String,
    pub claimant_set_root: String,
    pub outcome_set_root: String,
    pub proposed_rates_root: String,
    pub rate_derived_summary_hash: String,
    pub assertions: Vec<AssertionResult>,
    pub selection_receipt: selection::SelectionReceipt,
    pub selected_outcome_id: String,
    pub selected_outcome_index: BigInt,
    pub selected_outcome_hash: String,
    pub result_root: String,
    pub total_allocated: BigInt,
    pub residual_capacity: BigInt,
    pub round_lifecycle: lifecycle::RoundLifecycle,
    pub certificate_assertions_digest: String,
    pub kernel_version: String,
    pub selection_algorithm: String,
    pub rejection_classification: Option<String>,
    pub rejection_reason: Option<String>,
}

// ──────────────────────────────────────────────────────────────────────────────
// Kernel execution
// ──────────────────────────────────────────────────────────────────────────────

/// Compute the full public result from a parsed context and committed block.
/// Parse the optional `round-state` input field. Returns the raw JSON value
/// (string, null, or any other type). Callers scope malformed handling to the
/// lifecycle projection, which fails closed; this never rejects the kernel.
pub fn parse_round_state(input: &serde_json::Value) -> Option<serde_json::Value> {
    input.get("round-state").cloned()
}

pub fn run_kernel(
    ctx: &Context,
    committed: &Committed,
    round_state: Option<&serde_json::Value>,
) -> PublicResult {
    let lifecycle = lifecycle::round_lifecycle(round_state);
    let allocation = evaluate_allocation(ctx, committed).unwrap_or_else(|e| {
        eprintln!("allocation-kernel: allocation evaluation error: {}", e);
        std::process::exit(1);
    });
    let ctx_hash = &allocation.context_hash;
    let claim_root = &allocation.claimant_set_root;
    let outcome_root = &allocation.outcome_set_root;
    let rates_root = &allocation.proposed_rates_root;
    let summary_hash = &allocation.rate_derived_summary_hash;
    let assertions = &allocation.assertions;
    let sel = &allocation.selection_receipt;
    let selected = &allocation.selected_outcome;
    let selected_hash = &allocation.selected_outcome_hash;
    let result_root = &allocation.result_root;
    let total_allocated = &allocation.total_allocated;
    let residual = &allocation.residual_capacity;

    let all_pass = assertions.iter().all(|a| a.result);
    let status = if all_pass { "passing" } else { "rejected" };

    let digest_preimage = CanonValue::map(vec![
        (
            CanonValue::keyword("allocation-context-hash"),
            CanonValue::str(ctx_hash),
        ),
        (
            CanonValue::keyword("assertions"),
            CanonValue::array(
                assertions
                    .iter()
                    .map(|a| {
                        CanonValue::map(vec![
                            (
                                CanonValue::keyword("assertion/id"),
                                CanonValue::keyword(a.id.to_string()),
                            ),
                            (
                                CanonValue::keyword("assertion/result"),
                                CanonValue::bool(a.result),
                            ),
                        ])
                    })
                    .collect(),
            ),
        ),
        (
            CanonValue::keyword("selected-outcome-id"),
            CanonValue::str(&selected.outcome_id),
        ),
        (
            CanonValue::keyword("selected-outcome-index"),
            CanonValue::int(sel.selected_index.clone()),
        ),
        (
            CanonValue::keyword("result-root"),
            CanonValue::str(result_root),
        ),
        (
            CanonValue::keyword("total-allocated"),
            CanonValue::int(total_allocated.clone()),
        ),
        (
            CanonValue::keyword("residual-capacity"),
            CanonValue::int(residual.clone()),
        ),
        (
            CanonValue::keyword("allocation-kernel-version"),
            CanonValue::str(KERNEL_VERSION),
        ),
        (
            CanonValue::keyword("round-state"),
            match &lifecycle.round_state {
                Some(s) => CanonValue::str(s),
                None => CanonValue::null(),
            },
        ),
        (
            CanonValue::keyword("derived-state"),
            match &lifecycle.derived_state {
                Some(s) => CanonValue::str(s),
                None => CanonValue::null(),
            },
        ),
        (
            CanonValue::keyword("lifecycle-profile-id"),
            CanonValue::str(&lifecycle.lifecycle_profile_id),
        ),
        (
            CanonValue::keyword("lifecycle-profile-version"),
            CanonValue::int(BigInt::from(lifecycle.lifecycle_profile_version)),
        ),
        (
            CanonValue::keyword("cancellation-window-schema"),
            CanonValue::str(&lifecycle.cancellation_window_schema),
        ),
        (
            CanonValue::keyword("cancellation-window"),
            CanonValue::str(&lifecycle.cancellation_window),
        ),
        (
            CanonValue::keyword("cancellation-possible"),
            CanonValue::bool(lifecycle.cancellation_possible),
        ),
        (
            CanonValue::keyword("cancellation-blocking-reasons"),
            CanonValue::array(
                lifecycle
                    .cancellation_blocking_reasons
                    .iter()
                    .map(CanonValue::str)
                    .collect(),
            ),
        ),
        (
            CanonValue::keyword("lifecycle-assertion-status"),
            CanonValue::str(&lifecycle.lifecycle_assertion_status),
        ),
        (
            CanonValue::keyword("lifecycle-assurance"),
            CanonValue::str(&lifecycle.assurance),
        ),
    ]);
    let digest = roots::certificate_assertions_digest_v2(&digest_preimage);

    let (rejection_classification, rejection_reason) = if all_pass {
        (None, None)
    } else {
        let first_failed = assertions.iter().find(|a| !a.result);
        let classification = first_failed.map(|a| a.classification().to_string());
        (
            classification,
            Some("One or more kernel assertions failed".to_string()),
        )
    };

    PublicResult {
        status: status.to_string(),
        allocation_context_hash: ctx_hash.clone(),
        claimant_set_root: claim_root.clone(),
        outcome_set_root: outcome_root.clone(),
        proposed_rates_root: rates_root.clone(),
        rate_derived_summary_hash: summary_hash.clone(),
        assertions: assertions.clone(),
        selection_receipt: sel.clone(),
        selected_outcome_id: selected.outcome_id.clone(),
        selected_outcome_index: allocation.selected_outcome_index,
        selected_outcome_hash: selected_hash.clone(),
        result_root: result_root.clone(),
        total_allocated: total_allocated.clone(),
        residual_capacity: residual.clone(),
        round_lifecycle: lifecycle,
        certificate_assertions_digest: digest,
        kernel_version: KERNEL_VERSION.to_string(),
        selection_algorithm: SELECTION_ALGORITHM.to_string(),
        rejection_classification,
        rejection_reason,
    }
}

/// Parse a raw JSON input document into a context, applying canonical ordering.
pub fn parse_context(input: &serde_json::Value) -> Result<Context, KernelError> {
    let allocation_id = input
        .get("allocation-id")
        .and_then(|value| value.as_str())
        .filter(|value| !value.trim().is_empty())
        .map(str::to_string)
        .ok_or_else(|| {
            KernelError::new("malformed-allocation-id", "allocation id must be non-empty")
        })?;
    let kernel_version = input
        .get("kernel-version")
        .and_then(|v| v.as_str())
        .unwrap_or(KERNEL_VERSION)
        .to_string();
    if kernel_version != KERNEL_VERSION {
        return Err(KernelError::new(
            "unsupported-kernel-version",
            format!("unsupported kernel version: {}", kernel_version),
        ));
    }
    let selection_algorithm = input
        .get("selection-algorithm")
        .and_then(|v| v.as_str())
        .unwrap_or(SELECTION_ALGORITHM)
        .to_string();
    if selection_algorithm != SELECTION_ALGORITHM {
        return Err(KernelError::new(
            "unsupported-selection-algorithm",
            format!("unsupported selection algorithm: {}", selection_algorithm),
        ));
    }

    let policy_value = input
        .get("policy")
        .cloned()
        .unwrap_or(serde_json::Value::Null);
    let policy = parse_policy(&policy_value)?;

    let raw_claimants = input
        .get("claimants")
        .and_then(|v| v.as_array())
        .ok_or_else(|| KernelError::new("malformed-claimants", "claimants must be a JSON array"))?;
    let mut claimants = Vec::with_capacity(raw_claimants.len());
    for raw in raw_claimants {
        claimants.push(parse_claimant(raw)?);
    }
    validate_claimants(&claimants, &policy)
        .map_err(|error| KernelError::new(error.classification, error.reason))?;
    sort_claimants(&mut claimants);

    let raw_outcomes = input
        .get("outcomes")
        .and_then(|v| v.as_array())
        .ok_or_else(|| KernelError::new("malformed-outcomes", "outcomes must be a JSON array"))?;
    let mut outcomes = Vec::with_capacity(raw_outcomes.len());
    for raw in raw_outcomes {
        outcomes.push(parse_outcome(raw)?);
    }
    validate_outcomes(&outcomes)
        .map_err(|error| KernelError::new(error.classification, error.reason))?;
    sort_outcomes(&mut outcomes);

    let raw_rates = input
        .get("proposed-rates")
        .and_then(|v| v.as_array())
        .ok_or_else(|| {
            KernelError::new("malformed-rates", "proposed-rates must be a JSON array")
        })?;
    let mut rate_map = std::collections::HashMap::new();
    for raw in raw_rates {
        let outcome_id = string_field(raw, "outcome-id")?;
        let numerator = non_negative_bigint_field(raw, "numerator")?;
        let denominator = bigint_field(raw, "denominator")?;
        validate_rate(&numerator, &denominator)
            .map_err(|error| KernelError::new(error.classification, error.reason))?;
        rate_map.insert(outcome_id, (numerator, denominator));
    }
    let rates = rates_for_outcomes(&outcomes, &rate_map)
        .map_err(|reason| KernelError::new("rates-outcome-mismatch", reason))?;

    let capacity = non_negative_bigint_field(input, "capacity")?;
    validate_capacity(&capacity)
        .map_err(|error| KernelError::new(error.classification, error.reason))?;
    let total_eligible_weight = bigint_field(input, "total-eligible-weight")?;
    let exact_pro_rata_denominator = bigint_field(input, "exact-pro-rata-denominator")?;

    let randomness = input
        .get("authoritative-randomness")
        .and_then(|v| v.as_str())
        .ok_or_else(|| {
            KernelError::new("malformed-randomness", "authoritative-randomness required")
        })?;
    let randomness = hex32_to_bytes(randomness.strip_prefix("0x").unwrap_or(randomness))
        .ok_or_else(|| {
            KernelError::new(
                "malformed-randomness",
                "authoritative-randomness must be exactly 32 bytes hex",
            )
        })?;

    validate_weight_totals(
        &claimants,
        &total_eligible_weight,
        &exact_pro_rata_denominator,
    )
    .map_err(|error| KernelError::new(error.classification, error.reason))?;

    Ok(Context {
        allocation_id,
        kernel_version,
        policy,
        claimants,
        outcomes,
        rates,
        capacity,
        total_eligible_weight,
        exact_pro_rata_denominator,
        randomness,
    })
}

/// Parse the committed block for non-proving replay use. Bindings remain optional
/// here so individual assertions can be evaluated in isolation.
pub fn parse_committed(input: &serde_json::Value) -> Result<Committed, KernelError> {
    let mut committed = Committed::default();
    let Some(value) = input.get("committed") else {
        return Ok(committed);
    };
    let map = value
        .as_object()
        .ok_or_else(|| KernelError::new("malformed-committed", "committed must be an object"))?;
    committed.claimant_set_root = opt_hex(map, "claimant-set-root")?;
    committed.outcome_set_root = opt_hex(map, "outcome-set-root")?;
    committed.proposed_rates_root = opt_hex(map, "proposed-rates-root")?;
    committed.result_root = opt_hex(map, "result-root")?;
    committed.selected_outcome_id = map
        .get("selected-outcome-id")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    committed.selected_outcome_index = map
        .get("selected-outcome-index")
        .and_then(|v| v.as_str())
        .map(|s| parse_bigint(s).unwrap_or_else(|_| BigInt::zero()));
    Ok(committed)
}

/// Parse the committed bindings required for allocation proof and verification
/// inputs. A proof must bind every recomputed root and the selected outcome;
/// otherwise it could attest only to self-derived, uncommitted values.
pub fn parse_committed_for_proving(input: &serde_json::Value) -> Result<Committed, KernelError> {
    let committed = parse_committed(input)?;
    let committed_map = input
        .get("committed")
        .and_then(|value| value.as_object())
        .ok_or_else(|| {
            KernelError::new(
                "missing-committed-binding",
                "committed block required for proving",
            )
        })?;
    let required_roots = [
        ("claimant-set-root", &committed.claimant_set_root),
        ("outcome-set-root", &committed.outcome_set_root),
        ("proposed-rates-root", &committed.proposed_rates_root),
        ("result-root", &committed.result_root),
    ];
    for (name, value) in required_roots {
        if value.is_none() {
            return Err(KernelError::new(
                "missing-committed-binding",
                format!("committed {} required for proving", name),
            ));
        }
    }
    let selected_outcome_id = committed_map
        .get("selected-outcome-id")
        .and_then(|value| value.as_str())
        .filter(|value| !value.is_empty())
        .ok_or_else(|| {
            KernelError::new(
                "missing-committed-binding",
                "committed selected-outcome-id required for proving",
            )
        })?;
    let selected_outcome_index = committed_map
        .get("selected-outcome-index")
        .and_then(|value| value.as_str())
        .ok_or_else(|| {
            KernelError::new(
                "missing-committed-binding",
                "committed selected-outcome-index required for proving",
            )
        })?;
    if parse_bigint(selected_outcome_index).is_err() {
        return Err(KernelError::new(
            "malformed-committed",
            "committed selected-outcome-index must be an integer string",
        ));
    }
    if committed.selected_outcome_id.as_deref() != Some(selected_outcome_id)
        || committed.selected_outcome_index.is_none()
    {
        return Err(KernelError::new(
            "malformed-committed",
            "committed selected outcome binding is malformed",
        ));
    }
    Ok(committed)
}

fn opt_hex(
    map: &serde_json::Map<String, serde_json::Value>,
    key: &str,
) -> Result<Option<String>, KernelError> {
    match map.get(key) {
        None => Ok(None),
        Some(v) => {
            let s = v.as_str().ok_or_else(|| {
                KernelError::new("malformed-committed", format!("{} must be a string", key))
            })?;
            let bare = s.strip_prefix("0x").unwrap_or(s);
            if !(bare.len() == 64
                && bare
                    .bytes()
                    .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()))
            {
                return Err(KernelError::new(
                    "malformed-committed",
                    format!("{} must be 0x-prefixed 32-byte hex", key),
                ));
            }
            Ok(Some(s.to_string()))
        }
    }
}

fn parse_claimant(raw: &serde_json::Value) -> Result<Claimant, KernelError> {
    Ok(Claimant {
        claim_id: nonblank_string_field(raw, "claim-id", "malformed-claim-id")?,
        economic_owner_id: nonblank_string_field(
            raw,
            "economic-owner-id",
            "malformed-economic-owner-id",
        )?,
        amount: non_negative_bigint_field(raw, "amount")?,
        weight: non_negative_bigint_field(raw, "weight")?,
    })
}

fn parse_outcome(raw: &serde_json::Value) -> Result<Outcome, KernelError> {
    let outcome_id = nonblank_string_field(raw, "outcome-id", "malformed-outcome-id")?;
    let allocations_raw = raw
        .get("allocations")
        .and_then(|v| v.as_array())
        .ok_or_else(|| {
            KernelError::new(
                "malformed-outcome",
                format!("outcome {} allocations must be an array", outcome_id),
            )
        })?;
    let mut allocations = Vec::with_capacity(allocations_raw.len());
    for raw in allocations_raw {
        allocations.push(AllocationEntry {
            claim_id: string_field(raw, "claim-id")?,
            allocated: non_negative_bigint_field(raw, "allocated")?,
        });
    }
    Ok(Outcome {
        outcome_id,
        allocations,
    })
}

fn parse_policy(raw: &serde_json::Value) -> Result<Policy, KernelError> {
    let map = raw
        .as_object()
        .ok_or_else(|| KernelError::new("malformed-policy", "policy must be an object"))?;
    let policy_id = map
        .get("policy-id")
        .and_then(|value| value.as_str())
        .filter(|value| !value.trim().is_empty())
        .map(str::to_string)
        .ok_or_else(|| KernelError::new("malformed-policy", "policy id required"))?;
    let policy_hash = map
        .get("policy-hash")
        .and_then(|value| value.as_str())
        .filter(|value| {
            value.len() == 66
                && value.starts_with("0x")
                && value[2..]
                    .bytes()
                    .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        })
        .map(str::to_string)
        .ok_or_else(|| {
            KernelError::new(
                "malformed-policy-hash",
                "policy hash must be 0x-prefixed lowercase 32-byte hex",
            )
        })?;
    // Clojure's boolean coercion treats only false and nil as false.
    let forbid = map
        .get("forbid-duplicate-owners")
        .is_some_and(|value| !value.is_null() && value != &serde_json::Value::Bool(false));
    Ok(Policy {
        policy_id,
        policy_hash,
        forbid_duplicate_owners: forbid,
    })
}

fn string_field(v: &serde_json::Value, key: &str) -> Result<String, KernelError> {
    v.get(key)
        .and_then(|x| x.as_str())
        .map(|s| s.to_string())
        .ok_or_else(|| {
            KernelError::new("malformed-input", format!("missing string field: {}", key))
        })
}

fn nonblank_string_field(
    value: &serde_json::Value,
    key: &str,
    classification: &'static str,
) -> Result<String, KernelError> {
    value
        .get(key)
        .and_then(|field| field.as_str())
        .filter(|field| !field.trim().is_empty())
        .map(str::to_string)
        .ok_or_else(|| KernelError::new(classification, format!("{} must be non-empty", key)))
}

fn bigint_field(v: &serde_json::Value, key: &str) -> Result<BigInt, KernelError> {
    match v.get(key) {
        None => Err(KernelError::new(
            "malformed-input",
            format!("missing integer field: {}", key),
        )),
        Some(serde_json::Value::String(s)) => parse_signed_bigint(s),
        Some(serde_json::Value::Number(n)) => n
            .as_i64()
            .map(BigInt::from)
            .ok_or_else(|| KernelError::new("malformed-input", format!("non-integer {}", key))),
        Some(_) => Err(KernelError::new(
            "malformed-input",
            format!("invalid integer field: {}", key),
        )),
    }
}

fn non_negative_bigint_field(v: &serde_json::Value, key: &str) -> Result<BigInt, KernelError> {
    let value = bigint_field(v, key)?;
    if value.is_negative() {
        return Err(KernelError::new(
            "negative-amount",
            format!("{} must be non-negative, got: {}", key, value),
        ));
    }
    Ok(value)
}

fn parse_bigint(s: &str) -> Result<BigInt, KernelError> {
    if s.is_empty() || !s.bytes().all(|b| b.is_ascii_digit()) {
        return Err(KernelError::new(
            "malformed-integer",
            format!("not a decimal string: {}", s),
        ));
    }
    BigInt::parse_bytes(s.as_bytes(), 10)
        .ok_or_else(|| KernelError::new("malformed-integer", format!("invalid integer: {}", s)))
}

fn parse_signed_bigint(s: &str) -> Result<BigInt, KernelError> {
    let (negative, digits) = match s.strip_prefix('-') {
        Some(digits) => (true, digits),
        None => (false, s),
    };
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return Err(KernelError::new(
            "malformed-integer",
            format!("not a decimal string: {}", s),
        ));
    }
    BigInt::parse_bytes(digits.as_bytes(), 10)
        .map(|value| if negative { -value } else { value })
        .ok_or_else(|| KernelError::new("malformed-integer", format!("invalid integer: {}", s)))
}
