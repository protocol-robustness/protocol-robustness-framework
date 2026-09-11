//! Realized-allocation-statement projection (independent Rust implementation).
//!
//! `realized-allocation-statement.v1`: commits six roots derived from raw
//! inputs, then commits the statement root under
//! REALIZED_ALLOCATION_STATEMENT_V1.
//!
//! This is the projection + canonical-encode layer. It is deliberately
//! decoupled from `realized_fill.rs` (the semantics layer) so serialization
//! and semantic errors are testable independently.
//!
//! Per acceptance condition A, Rust reconstructs all six roots from raw inputs;
//! it does NOT accept pre-computed roots as inputs. Each root function takes
//! the canonical raw inputs and recomputes the root itself.
//!
//! Claim keys are STRINGS (claim-id), matching the allocation context.

use crate::canonical::{domain_hash, tags, CanonValue};
use crate::kernel::{context_preimage, Context};
use crate::lifecycle::RoundLifecycle;
use crate::realized_fill::{canon_request_set, RealizedAllocation};

pub const SCHEMA_VERSION: &str = "realized-allocation-statement.v1";

/// The allocation-context root: domain hash of the context preimage, derived
/// from the raw `Context` (condition A: Rust reconstructs, does not bind a
/// precomputed root). The context itself is the round-level input committed by
/// the kernel and reproduced byte-for-byte by `kernel::context_preimage`.
pub fn allocation_context_root(ctx: &Context) -> String {
    domain_hash(tags::ALLOCATION_CONTEXT, &context_preimage(ctx))
}

/// Request-set root: domain hash of the claim-keyed requested set under
/// REALIZED_REQUEST_SET_V1.
pub fn request_set_root(requested: &[(String, i64)]) -> String {
    domain_hash("REALIZED_REQUEST_SET_V1", &canon_request_set(requested))
}

/// Allocation-policy root: domain hash of the effective fill policy under
/// ALLOCATION_POLICY_V1. The declared fail-action policy is excluded here and
/// committed separately, so a policy change cannot silently change the
/// verifier's meaning without changing evidence identity.
pub fn allocation_policy_root(policy: &[(String, CanonValue)]) -> String {
    let policy_canon = keyword_map(policy);
    domain_hash("ALLOCATION_POLICY_V1", &policy_canon)
}

/// Fail-action-policy root: domain hash of the declared fail-action policy
/// under FAIL_ACTION_POLICY_V1. When no policy is declared, the canonical
/// conservative default is committed so the root is always defined.
pub fn fail_action_policy_root(fail_action_policy: Option<&[(String, CanonValue)]>) -> String {
    let canonical = match fail_action_policy {
        Some(pairs) => keyword_map(pairs),
        None => CanonValue::map(vec![
            (
                CanonValue::keyword("mode"),
                CanonValue::keyword("pro-rata-treatment"),
            ),
            (
                CanonValue::keyword("deferred-policy"),
                CanonValue::keyword("same-ratio"),
            ),
            (
                CanonValue::keyword("haircut-policy"),
                CanonValue::keyword("same-ratio"),
            ),
        ]),
    };
    domain_hash("FAIL_ACTION_POLICY_V1", &canonical)
}

/// Convert (string-key, value) policy pairs to a canonical map with keyword
/// keys.
fn keyword_map(pairs: &[(String, CanonValue)]) -> CanonValue {
    let mut converted: Vec<(CanonValue, CanonValue)> = pairs
        .iter()
        .map(|(k, v)| (CanonValue::keyword(k.clone()), v.clone()))
        .collect();
    // CanonValue::map sorts pairs by encoded key bytes internally, but sorting
    // here keeps the preimage deterministic regardless.
    converted.sort_by_key(|a| a.0.encode());
    CanonValue::map(converted)
}

/// Round-lifecycle root: domain hash of the round-lifecycle projection under
/// ROUND_LIFECYCLE_V1.
pub fn round_lifecycle_root(lifecycle: &CanonValue) -> String {
    domain_hash("ROUND_LIFECYCLE_V1", lifecycle)
}

/// Canonical ROUND_LIFECYCLE_V1 preimage for a `RoundLifecycle`, mirroring the
/// Clojure `round-state/round-lifecycle` map field-for-field.
pub fn lifecycle_canon_value(lc: &RoundLifecycle) -> CanonValue {
    let mut pairs: Vec<(CanonValue, CanonValue)> = vec![
        (
            CanonValue::keyword("assurance"),
            CanonValue::str(lc.assurance.clone()),
        ),
        (
            CanonValue::keyword("cancellation-blocking-reasons"),
            CanonValue::array(
                lc.cancellation_blocking_reasons
                    .iter()
                    .map(|s| CanonValue::str(s.clone()))
                    .collect(),
            ),
        ),
        (
            CanonValue::keyword("cancellation-possible"),
            CanonValue::bool(lc.cancellation_possible),
        ),
        (
            CanonValue::keyword("cancellation-window"),
            CanonValue::str(lc.cancellation_window.clone()),
        ),
        (
            CanonValue::keyword("cancellation-window-schema"),
            CanonValue::str(lc.cancellation_window_schema.clone()),
        ),
        (
            CanonValue::keyword("derived-state"),
            match &lc.derived_state {
                Some(s) => CanonValue::str(s.clone()),
                None => CanonValue::null(),
            },
        ),
        (
            CanonValue::keyword("evidence-status"),
            CanonValue::str(lc.evidence_status.clone()),
        ),
        (
            CanonValue::keyword("lifecycle-assertion-status"),
            CanonValue::str(lc.lifecycle_assertion_status.clone()),
        ),
        (
            CanonValue::keyword("lifecycle-profile-id"),
            CanonValue::str(lc.lifecycle_profile_id.clone()),
        ),
        (
            CanonValue::keyword("lifecycle-profile-version"),
            CanonValue::int(num_bigint::BigInt::from(lc.lifecycle_profile_version)),
        ),
        (
            CanonValue::keyword("round-state"),
            match &lc.round_state {
                Some(s) => CanonValue::str(s.clone()),
                None => CanonValue::null(),
            },
        ),
    ];
    pairs.sort_by_key(|(k, _)| k.encode());
    CanonValue::map(pairs)
}

/// Realized-results root: domain hash of the realized-results disposition
/// vector under REALIZED_RESULTS_V1.
pub fn realized_results_root(realized: &RealizedAllocation) -> String {
    domain_hash("REALIZED_RESULTS_V1", &realized.canon_results())
}

/// The canonical statement preimage (the value committed by the statement root).
pub fn statement_preimage(
    allocation_context_root: &str,
    request_set_root: &str,
    allocation_policy_root: &str,
    realized_results_root: &str,
    fail_action_policy_root: &str,
    round_lifecycle_root: &str,
) -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("schema-version"),
            CanonValue::str(SCHEMA_VERSION),
        ),
        (
            CanonValue::keyword("allocation-context-root"),
            CanonValue::str(allocation_context_root),
        ),
        (
            CanonValue::keyword("request-set-root"),
            CanonValue::str(request_set_root),
        ),
        (
            CanonValue::keyword("allocation-policy-root"),
            CanonValue::str(allocation_policy_root),
        ),
        (
            CanonValue::keyword("realized-results-root"),
            CanonValue::str(realized_results_root),
        ),
        (
            CanonValue::keyword("fail-action-policy-root"),
            CanonValue::str(fail_action_policy_root),
        ),
        (
            CanonValue::keyword("round-lifecycle-root"),
            CanonValue::str(round_lifecycle_root),
        ),
    ])
}

/// The canonical statement root: domain hash of the statement preimage under
/// REALIZED_ALLOCATION_STATEMENT_V1.
pub fn statement_root(
    allocation_context_root: &str,
    request_set_root: &str,
    allocation_policy_root: &str,
    realized_results_root: &str,
    fail_action_policy_root: &str,
    round_lifecycle_root: &str,
) -> String {
    domain_hash(
        "REALIZED_ALLOCATION_STATEMENT_V1",
        &statement_preimage(
            allocation_context_root,
            request_set_root,
            allocation_policy_root,
            realized_results_root,
            fail_action_policy_root,
            round_lifecycle_root,
        ),
    )
}

/// A fully projected realized-allocation statement.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RealizedAllocationStatement {
    pub schema_version: String,
    pub allocation_context_root: String,
    pub request_set_root: String,
    pub allocation_policy_root: String,
    pub realized_results_root: String,
    pub fail_action_policy_root: String,
    pub round_lifecycle_root: String,
    pub statement_root: String,
    pub all_active: bool,
}

/// Build a realized-allocation-statement.v1 from raw inputs, recomputing all
/// six roots.
///
/// Args:
///   - `ctx`                — the parsed allocation context (raw round inputs)
///   - `requested`          — claim-id -> requested amount (sorted)
///   - `policy`             — effective fill policy key/value pairs (minus
///     :fail-action-policy)
///   - `fail_action_policy` — declared fail-action policy, or None for the
///     conservative default
///   - `lifecycle`          — canonical round-lifecycle projection
///   - `realized`           — the realized allocation (semantics layer output)
pub fn build_statement(
    ctx: &Context,
    requested: &[(String, i64)],
    policy: &[(String, CanonValue)],
    fail_action_policy: Option<&[(String, CanonValue)]>,
    lifecycle: &CanonValue,
    realized: &RealizedAllocation,
) -> RealizedAllocationStatement {
    let acr = allocation_context_root(ctx);
    let rsr = request_set_root(requested);
    let apr = allocation_policy_root(policy);
    let rrr = realized_results_root(realized);
    let fap = fail_action_policy_root(fail_action_policy);
    let rl = round_lifecycle_root(lifecycle);
    let sr = statement_root(&acr, &rsr, &apr, &rrr, &fap, &rl);
    let all_active = realized
        .participants
        .iter()
        .all(|p| p.filled == p.requested && p.deferred == 0 && p.haircut == 0);
    RealizedAllocationStatement {
        schema_version: SCHEMA_VERSION.to_string(),
        allocation_context_root: acr,
        request_set_root: rsr,
        allocation_policy_root: apr,
        realized_results_root: rrr,
        fail_action_policy_root: fap,
        round_lifecycle_root: rl,
        statement_root: sr,
        all_active,
    }
}

/// Helpers for constructing canonical policy maps.
pub mod policy {
    use crate::canonical::CanonValue;

    /// Build a fill-policy map from (key, keyword-value) pairs, e.g.
    /// [("mode", "pro-rata"), ("rounding-policy", "largest-remainder")].
    pub fn keyword_policy(pairs: &[(&str, &str)]) -> Vec<(String, CanonValue)> {
        pairs
            .iter()
            .map(|(k, v)| ((*k).to_string(), CanonValue::keyword(*v)))
            .collect()
    }
}

/// Minimal public-input helpers for exposing the statement (what SP1 should
/// reveal): the canonical statement root plus whichever public inputs are
/// intentionally exposed. The scenario-evidence binding stays simulator-side.
pub fn reveal(statement: &RealizedAllocationStatement, public_inputs: &[&str]) -> CanonValue {
    CanonValue::map(
        std::iter::once((
            CanonValue::keyword("statement-root"),
            CanonValue::str(statement.statement_root.clone()),
        ))
        .chain(
            public_inputs
                .iter()
                .map(|k| (CanonValue::keyword(*k), CanonValue::str(*k))),
        )
        .collect(),
    )
}
