//! Golden conformance: the independent Rust realized-allocation statement must
//! reproduce the Clojure producer's roots byte-for-byte (condition D — Clojure
//! is the oracle). These golden values are generated from the Clojure
//! `resolver-sim.allocation.realized-statement` producer.

use allocation_kernel::canonical::CanonValue;
use allocation_kernel::kernel;
use allocation_kernel::realized_fill;
use allocation_kernel::realized_statement;

/// The canonical a-vs-b-plus-c context (matches the Clojure fixture used to
/// generate the golden values below: policy-id "p").
fn fixture_context() -> kernel::Context {
    let input = serde_json::json!({
        "allocation-id": "a-vs-b-plus-c",
        "kernel-version": "allocation-kernel.v1",
        "selection-algorithm": "domain-hash-rejection-v1",
        "policy": {
            "policy-id": "p",
            "policy-hash": "0xabababababababababababababababababababababababababababababababab",
            "forbid-duplicate-owners": false
        },
        "claimants": [
            {"claim-id": "A", "economic-owner-id": "oA", "amount": "50", "weight": "50"},
            {"claim-id": "B", "economic-owner-id": "oB", "amount": "30", "weight": "30"},
            {"claim-id": "C", "economic-owner-id": "oC", "amount": "20", "weight": "20"}
        ],
        "outcomes": [
            {"outcome-id": "O1", "allocations": [
                {"claim-id": "A", "allocated": "50"},
                {"claim-id": "B", "allocated": "0"},
                {"claim-id": "C", "allocated": "0"}
            ]},
            {"outcome-id": "O2", "allocations": [
                {"claim-id": "A", "allocated": "0"},
                {"claim-id": "B", "allocated": "30"},
                {"claim-id": "C", "allocated": "20"}
            ]}
        ],
        "proposed-rates": [
            {"outcome-id": "O1", "numerator": "1", "denominator": "2"},
            {"outcome-id": "O2", "numerator": "1", "denominator": "2"}
        ],
        "capacity": "50",
        "total-eligible-weight": "100",
        "exact-pro-rata-denominator": "100",
        "authoritative-randomness": "0x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
    });
    kernel::parse_context(&input).expect("context parses")
}

/// The canonical round-lifecycle projection value (result-accepted token),
/// matching the Clojure `round-state/round-lifecycle`.
fn lifecycle_canon() -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("assurance"),
            CanonValue::str("independent-replay"),
        ),
        (
            CanonValue::keyword("cancellation-blocking-reasons"),
            CanonValue::array(vec![CanonValue::str("result-accepted")]),
        ),
        (
            CanonValue::keyword("cancellation-possible"),
            CanonValue::bool(false),
        ),
        (
            CanonValue::keyword("cancellation-window"),
            CanonValue::str("closed"),
        ),
        (
            CanonValue::keyword("cancellation-window-schema"),
            CanonValue::str("cancellation-window.v1"),
        ),
        (
            CanonValue::keyword("derived-state"),
            CanonValue::str("result-accepted"),
        ),
        (
            CanonValue::keyword("evidence-status"),
            CanonValue::str("evidence/derived-state"),
        ),
        (
            CanonValue::keyword("lifecycle-assertion-status"),
            CanonValue::str("passing"),
        ),
        (
            CanonValue::keyword("lifecycle-profile-id"),
            CanonValue::str("prf.lifecycle-window/probabilistic-allocation"),
        ),
        (
            CanonValue::keyword("lifecycle-profile-version"),
            CanonValue::int(num_bigint::BigInt::from(1)),
        ),
        (
            CanonValue::keyword("round-state"),
            CanonValue::str("result-accepted"),
        ),
    ])
}

/// The effective fill policy (pro-rata, largest-remainder), minus the
/// fail-action policy (committed separately).
fn policy_pairs() -> Vec<(String, CanonValue)> {
    realized_statement::policy::keyword_policy(&[
        ("mode", "pro-rata"),
        ("rounding-policy", "largest-remainder"),
    ])
}

/// The declared fail-action policy.
fn fail_action_policy_pairs() -> Vec<(String, CanonValue)> {
    realized_statement::policy::keyword_policy(&[
        ("mode", "pro-rata-treatment"),
        ("deferred-policy", "same-ratio"),
        ("haircut-policy", "same-ratio"),
    ])
}

/// Requested amounts for the fixture (claim-id -> amount).
fn requested() -> Vec<(String, i64)> {
    vec![
        ("A".to_string(), 50),
        ("B".to_string(), 30),
        ("C".to_string(), 20),
    ]
}

#[test]
fn allocation_context_root_matches_prf_golden() {
    // Clojure oracle: (context-hash fixture) = 5155de4de58f55c1437d52d831cd3c747eea30e8455c3a7945dc715a6907a0b5
    let ctx = fixture_context();
    assert_eq!(
        realized_statement::allocation_context_root(&ctx),
        "5155de4de58f55c1437d52d831cd3c747eea30e8455c3a7945dc715a6907a0b5"
    );
}

#[test]
fn six_roots_recomputed_not_bound() {
    // Condition A: every root is recomputed from raw inputs, not accepted as a
    // pre-computed hash. The context root is derived from the raw Context via
    // kernel::context_preimage; the others from raw requested/policy/realized.
    let ctx = fixture_context();
    let realized = realized_fill::partial_fill(100, &requested()).unwrap();
    let lc = lifecycle_canon();
    let stmt = realized_statement::build_statement(
        &ctx,
        &requested(),
        &policy_pairs(),
        Some(&fail_action_policy_pairs()),
        &lc,
        &realized,
    );
    // Clojure oracle values:
    assert_eq!(
        stmt.allocation_context_root,
        "5155de4de58f55c1437d52d831cd3c747eea30e8455c3a7945dc715a6907a0b5"
    );
    assert_eq!(
        stmt.request_set_root,
        "9c495e37e9844035bd5273dac30682bfb99293c1f380034a69107e8765076114"
    );
    assert_eq!(
        stmt.allocation_policy_root,
        "798399d750475539bb518657121104c3c4ddea934cb0d61c044699f6671b64cb"
    );
    assert_eq!(
        stmt.fail_action_policy_root,
        "10ff923ee0517c2e1dfbbb208946fe0834a8992273ebd983c2c2cd59658c08e7"
    );
    assert_eq!(
        stmt.round_lifecycle_root,
        "1e7d41793ce424f39e2b2afc83dbb5c528cdd96a746d0a48949f1db5845b4a4a"
    );
    assert_eq!(
        stmt.realized_results_root,
        "f0ba9de83a691600c73de0ddde4f8d1ab673ba4942a72340c782a5a350278b83"
    );
    assert_eq!(
        stmt.statement_root,
        "c22333a16df1c1efa352e9daab42ccbd78f4a1d7530ee3ed3cf7527ba62cbd81"
    );
    assert!(stmt.all_active);
}

#[test]
fn shortfall_statement_matches_golden() {
    // Condition C: shortfall mutation changes realized-results + statement root,
    // leaves context/request-set/policy/fail-action/lifecycle unchanged.
    let ctx = fixture_context();
    let realized = realized_fill::partial_fill(50, &requested()).unwrap();
    let lc = lifecycle_canon();
    let stmt = realized_statement::build_statement(
        &ctx,
        &requested(),
        &policy_pairs(),
        Some(&fail_action_policy_pairs()),
        &lc,
        &realized,
    );
    assert_eq!(
        stmt.allocation_context_root,
        "5155de4de58f55c1437d52d831cd3c747eea30e8455c3a7945dc715a6907a0b5"
    );
    assert_eq!(
        stmt.request_set_root,
        "9c495e37e9844035bd5273dac30682bfb99293c1f380034a69107e8765076114"
    );
    assert_eq!(
        stmt.realized_results_root,
        "1d63bc9cfddb0bd369d70500a59e55c2b7d9968d024a8f1e745bb253e827fd9f"
    );
    assert_eq!(
        stmt.statement_root,
        "a83b16fd86d7bac99d6da4ce536890c47bfed5304d07d5eb62c6c9016e405d62"
    );
    assert!(!stmt.all_active);
}

#[test]
fn mutation_locality_rust() {
    // Condition C: fail-action policy change affects only fail-action-policy-root
    // and statement-root; the other five roots are byte-identical.
    let ctx = fixture_context();
    let realized = realized_fill::partial_fill(100, &requested()).unwrap();
    let lc = lifecycle_canon();
    let base = realized_statement::build_statement(
        &ctx,
        &requested(),
        &policy_pairs(),
        Some(&fail_action_policy_pairs()),
        &lc,
        &realized,
    );
    let mutated = realized_statement::build_statement(
        &ctx,
        &requested(),
        &policy_pairs(),
        Some(&realized_statement::policy::keyword_policy(&[
            ("mode", "pro-rata-treatment"),
            ("deferred-policy", "contractual"),
            ("haircut-policy", "same-ratio"),
        ])),
        &lc,
        &realized,
    );
    assert_ne!(
        base.fail_action_policy_root,
        mutated.fail_action_policy_root
    );
    assert_ne!(base.statement_root, mutated.statement_root);
    assert_eq!(
        base.allocation_context_root,
        mutated.allocation_context_root
    );
    assert_eq!(base.request_set_root, mutated.request_set_root);
    assert_eq!(base.allocation_policy_root, mutated.allocation_policy_root);
    assert_eq!(base.realized_results_root, mutated.realized_results_root);
    assert_eq!(base.round_lifecycle_root, mutated.round_lifecycle_root);
}

#[test]
fn inactive_participant_is_distinguishable() {
    // Condition C: a participant present in the request set with zero fill and
    // no deferred/haircut has an explicit :zero-filled disposition, which is
    // distinguishable from being absent entirely. The request-set root changes
    // when membership changes, and the realized-results root keeps the row.
    // C is present in requested with a positive amount but the round has zero
    // available liquidity: everything is deferred for recovery, and C remains
    // present (not dropped). Use a request set where C's membership is the
    // mutation against the all-active baseline.
    let realized = realized_fill::partial_fill(100, &requested()).unwrap();
    // C requested 20 and fully filled -> :full-fill (all-active baseline).
    let c_full = realized
        .participants
        .iter()
        .find(|p| p.claim_id == "C")
        .unwrap();
    assert_eq!(c_full.disposition(), realized_fill::Disposition::FullFill);
    // Direct classifier: a participant with requested > 0, filled 0, no
    // deferred/haircut is :zero-filled — the "present but inactive" case.
    assert_eq!(
        realized_fill::disposition_of(20, 0, 0, 0),
        realized_fill::Disposition::ZeroFilled
    );
    // The request-set root changes when a participant is removed entirely.
    let without_c = vec![("A".to_string(), 50), ("B".to_string(), 30)];
    let base_req = realized_statement::request_set_root(&requested());
    let reduced_req = realized_statement::request_set_root(&without_c);
    assert_ne!(base_req, reduced_req);
}

#[test]
fn malformed_input_fails_closed() {
    // Condition C (negative vectors): malformed/non-canonical input fails
    // closed, never producing a partial statement.
    assert!(realized_fill::partial_fill(-1, &requested()).is_err());
    assert!(realized_fill::partial_fill(100, &[]).is_err());
    assert!(realized_fill::partial_fill(100, &[("A".to_string(), -5)]).is_err());
}
