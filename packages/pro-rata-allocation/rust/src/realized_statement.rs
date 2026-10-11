//! Canonical identities for `realized-allocation-statement.v1`.
//!
//! Application context, lifecycle derivation, and realized-fill calculation
//! stay in their owning adapters. This module owns the canonical domain tags,
//! package-level projections, and six-root statement preimage.

use prf_canonical_codec::{domain_hash, CanonValue};

pub const SCHEMA_VERSION: &str = "realized-allocation-statement.v1";
pub const REQUEST_SET_DOMAIN: &str = "REALIZED_REQUEST_SET_V1";
pub const ALLOCATION_POLICY_DOMAIN: &str = "ALLOCATION_POLICY_V1";
pub const FAIL_ACTION_POLICY_DOMAIN: &str = "FAIL_ACTION_POLICY_V1";
pub const REALIZED_RESULTS_DOMAIN: &str = "REALIZED_RESULTS_V1";
pub const ROUND_LIFECYCLE_DOMAIN: &str = "ROUND_LIFECYCLE_V1";
pub const STATEMENT_DOMAIN: &str = "REALIZED_ALLOCATION_STATEMENT_V1";

fn keyword_map(pairs: &[(String, CanonValue)]) -> CanonValue {
    let mut converted: Vec<(CanonValue, CanonValue)> = pairs
        .iter()
        .map(|(key, value)| (CanonValue::keyword(key.clone()), value.clone()))
        .collect();
    converted.sort_by_key(|(key, _)| key.encode());
    CanonValue::map(converted)
}

/// Build the canonical claim-keyed request set and commit it under its V1 tag.
pub fn request_set_root(requested: &[(String, i64)]) -> String {
    let mut pairs: Vec<(CanonValue, CanonValue)> = requested
        .iter()
        .map(|(claim_id, amount)| (CanonValue::str(claim_id.clone()), CanonValue::int(*amount)))
        .collect();
    pairs.sort_by_key(|(key, _)| key.encode());
    domain_hash(REQUEST_SET_DOMAIN, &CanonValue::map(pairs))
}

/// Commit the effective fill policy; fail-action policy is committed separately.
pub fn allocation_policy_root(policy: &[(String, CanonValue)]) -> String {
    domain_hash(ALLOCATION_POLICY_DOMAIN, &keyword_map(policy))
}

/// Commit a declared fail-action policy or its frozen conservative default.
pub fn fail_action_policy_root(policy: Option<&[(String, CanonValue)]>) -> String {
    let default_policy = vec![
        (
            "mode".to_string(),
            CanonValue::keyword("pro-rata-treatment"),
        ),
        (
            "deferred-policy".to_string(),
            CanonValue::keyword("same-ratio"),
        ),
        (
            "haircut-policy".to_string(),
            CanonValue::keyword("same-ratio"),
        ),
    ];
    let preimage = policy
        .map(keyword_map)
        .unwrap_or_else(|| keyword_map(&default_policy));
    domain_hash(FAIL_ACTION_POLICY_DOMAIN, &preimage)
}

/// Commit an already-derived realized-results canonical projection.
pub fn realized_results_root(results: &CanonValue) -> String {
    domain_hash(REALIZED_RESULTS_DOMAIN, results)
}

/// Commit an already-derived round-lifecycle canonical projection.
pub fn round_lifecycle_root(lifecycle: &CanonValue) -> String {
    domain_hash(ROUND_LIFECYCLE_DOMAIN, lifecycle)
}

/// Build the frozen statement value tree from its six derived roots.
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

/// Commit the frozen statement preimage under its semantic domain tag.
pub fn statement_root(
    allocation_context_root: &str,
    request_set_root: &str,
    allocation_policy_root: &str,
    realized_results_root: &str,
    fail_action_policy_root: &str,
    round_lifecycle_root: &str,
) -> String {
    domain_hash(
        STATEMENT_DOMAIN,
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

#[cfg(test)]
mod tests {
    use super::*;

    fn policy() -> Vec<(String, CanonValue)> {
        vec![
            ("mode".to_string(), CanonValue::keyword("pro-rata")),
            (
                "rounding-policy".to_string(),
                CanonValue::keyword("largest-remainder"),
            ),
        ]
    }

    #[test]
    fn request_policy_default_fail_action_and_statement_roots_match_frozen_v1() {
        let request = vec![
            ("A".to_string(), 50),
            ("B".to_string(), 30),
            ("C".to_string(), 20),
        ];
        let request_root = request_set_root(&request);
        let allocation_policy_root = allocation_policy_root(&policy());
        let fail_action_policy_root = fail_action_policy_root(None);

        assert_eq!(
            request_root,
            "9c495e37e9844035bd5273dac30682bfb99293c1f380034a69107e8765076114"
        );
        assert_eq!(
            allocation_policy_root,
            "798399d750475539bb518657121104c3c4ddea934cb0d61c044699f6671b64cb"
        );
        assert_eq!(
            fail_action_policy_root,
            "10ff923ee0517c2e1dfbbb208946fe0834a8992273ebd983c2c2cd59658c08e7"
        );

        assert_eq!(
            statement_root(
                "5155de4de58f55c1437d52d831cd3c747eea30e8455c3a7945dc715a6907a0b5",
                &request_root,
                &allocation_policy_root,
                "f0ba9de83a691600c73de0ddde4f8d1ab673ba4942a72340c782a5a350278b83",
                &fail_action_policy_root,
                "1e7d41793ce424f39e2b2afc83dbb5c528cdd96a746d0a48949f1db5845b4a4a",
            ),
            "c22333a16df1c1efa352e9daab42ccbd78f4a1d7530ee3ed3cf7527ba62cbd81"
        );
    }
}
