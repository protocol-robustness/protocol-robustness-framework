//! Integration tests: native Rust kernel reproduces PRF-generated golden
//! values for the fixed a-vs-b-plus-c scenario.

use allocation_kernel::{json_projection, kernel};
use serde_json::json;

fn happy_input() -> serde_json::Value {
    json!({
        "allocation-id": "a-vs-b-plus-c",
        "kernel-version": "allocation-kernel.v1",
        "selection-algorithm": "domain-hash-rejection-v1",
        "policy": {
            "policy-id": "policy-a-vs-b-plus-c",
            "policy-hash": "0xabababababababababababababababababababababababababababababababab",
            "forbid-duplicate-owners": false
        },
        "claimants": [
            {"claim-id": "A", "economic-owner-id": "owner-A", "amount": "50", "weight": "50"},
            {"claim-id": "B", "economic-owner-id": "owner-B", "amount": "30", "weight": "30"},
            {"claim-id": "C", "economic-owner-id": "owner-C", "amount": "20", "weight": "20"}
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
    })
}

fn run(input: &serde_json::Value) -> serde_json::Value {
    let ctx = kernel::parse_context(input).expect("context parses");
    let committed = kernel::parse_committed(input).expect("committed parses");
    let round_state = kernel::parse_round_state(input);
    let result = kernel::run_kernel(&ctx, &committed, round_state.as_ref());
    json_projection::public_result_to_json(&result)
}

#[test]
fn happy_path_matches_prf_golden() {
    let out = run(&happy_input());
    let golden = [
        (
            "allocation-context-hash",
            "0xaec90fc6a813d8b3f28ca2c27573a70b5daf0c81964ae4121b12d6fa89555dd3",
        ),
        (
            "claimant-set-root",
            "0xc45b857d28c2d643dd9331fe396c44862df632125a6299e523073a197ce7a978",
        ),
        (
            "outcome-set-root",
            "0xc8180b14b358826dad95c2dad6b3783d2a380af2790a66b27a9580a01d99b866",
        ),
        (
            "proposed-rates-root",
            "0x4d32943ddcbf3ced6f774bb2841ec526ddefa5105777e981d3883ce9225c6314",
        ),
        (
            "rate-derived-summary-hash",
            "0xf717d67f518a821d06276f1effcb0bf8971f228bdf0b8c0de456d11546bc32ad",
        ),
        (
            "result-root",
            "0xf82ca61e0b3bb6949606bef5489663b73db9359dfb6eed478c3c2701440fdc06",
        ),
        (
            "selected-outcome-hash",
            "0xc9b6ab9cc713a0206c16a714e1e3955bcf13fa7ad925ac176f52cd5fafaa64ed",
        ),
        (
            "certificate-assertions-digest",
            "0xbae778d3623ca0a69a567b0d374f1020370badd6703b7e0bea1760e2e62a7054",
        ),
    ];
    for (key, expected) in golden {
        assert_eq!(out[key].as_str().unwrap(), expected, "key {}", key);
    }
    assert_eq!(out["result/status"], "passing");
    assert_eq!(out["selected-outcome-id"], "O2");
    assert_eq!(out["total-allocated"], "50");
    assert_eq!(out["residual-capacity"], "0");
}

#[test]
fn happy_path_all_fourteen_assertions_pass() {
    let out = run(&happy_input());
    let assertions = out["assertions"].as_array().unwrap();
    assert_eq!(assertions.len(), 14);
    assert!(assertions.iter().all(|a| a["assertion/result"] == true));
    let ids: Vec<&str> = assertions
        .iter()
        .map(|a| a["assertion/id"].as_str().unwrap())
        .collect();
    let expected_ids = [
        "allocation.assertion/claimant-set-root-valid",
        "allocation.assertion/outcome-set-root-valid",
        "allocation.assertion/proposed-rates-root-valid",
        "allocation.assertion/rates-canonical-exact",
        "allocation.assertion/rates-sum-to-one",
        "allocation.assertion/outcomes-eligible-only",
        "allocation.assertion/outcomes-no-duplicate-claims",
        "allocation.assertion/outcomes-all-or-nothing",
        "allocation.assertion/outcomes-exact-capacity",
        "allocation.assertion/proportional-proposed",
        "allocation.assertion/randomness-selection-valid",
        "allocation.assertion/selected-outcome-membership",
        "allocation.assertion/result-root-valid",
        "allocation.assertion/result-capacity-reconciles",
    ];
    assert_eq!(ids, expected_ids);
}

#[test]
fn selection_receipt_matches_prf_golden() {
    let out = run(&happy_input());
    let receipt = &out["selection-receipt"];
    assert_eq!(receipt["algorithm"], "domain-hash-rejection-v1");
    assert_eq!(
        receipt["candidate-digest"],
        "0x8ae6ab2bb536d3a01b998e2a208c6802952bf1a3701a048c6a1c28acbed38ae3"
    );
    assert_eq!(receipt["selected-index"], "1");
    assert_eq!(receipt["accepted-counter"], "0");
    assert_eq!(receipt["outcome-count"], "2");
    assert_eq!(receipt["selected-outcome-id"], "O2");
}

#[test]
fn claimant_order_permutation_is_invariant() {
    let mut input = happy_input();
    let claimants = input["claimants"].take();
    let mut reversed = json!([]);
    // reverse via serde
    if let serde_json::Value::Array(mut arr) = claimants {
        arr.reverse();
        reversed = serde_json::Value::Array(arr);
    }
    input["claimants"] = reversed;
    let out = run(&input);
    assert_eq!(out["result/status"], "passing");
    assert_eq!(
        out["allocation-context-hash"],
        "0xaec90fc6a813d8b3f28ca2c27573a70b5daf0c81964ae4121b12d6fa89555dd3"
    );
}

#[test]
fn outcome_order_permutation_is_invariant() {
    let mut input = happy_input();
    let outcomes = input["outcomes"].take();
    let mut reversed = json!([]);
    if let serde_json::Value::Array(mut arr) = outcomes {
        arr.reverse();
        reversed = serde_json::Value::Array(arr);
    }
    input["outcomes"] = reversed;
    let out = run(&input);
    assert_eq!(out["result/status"], "passing");
    assert_eq!(
        out["outcome-set-root"],
        "0xc8180b14b358826dad95c2dad6b3783d2a380af2790a66b27a9580a01d99b866"
    );
}

#[test]
fn malformed_rate_total_rejected_with_classification() {
    let mut input = happy_input();
    input["proposed-rates"] = json!([
        {"outcome-id": "O1", "numerator": "1", "denominator": "3"},
        {"outcome-id": "O2", "numerator": "1", "denominator": "3"}
    ]);
    let out = run(&input);
    assert_eq!(out["result/status"], "rejected");
    assert_eq!(out["rejection/classification"], "rates-not-sum-to-one");
}

#[test]
fn non_reduced_ratio_rejected() {
    let mut input = happy_input();
    input["proposed-rates"] = json!([
        {"outcome-id": "O1", "numerator": "2", "denominator": "4"},
        {"outcome-id": "O2", "numerator": "1", "denominator": "2"}
    ]);
    let out = run(&input);
    assert_eq!(out["result/status"], "rejected");
    assert_eq!(out["rejection/classification"], "rates-not-canonical");
}

#[test]
fn partial_claimant_allocation_rejected() {
    let mut input = happy_input();
    input["outcomes"][0]["allocations"] = json!([
        {"claim-id": "A", "allocated": "25"},
        {"claim-id": "B", "allocated": "0"},
        {"claim-id": "C", "allocated": "0"}
    ]);
    let out = run(&input);
    assert_eq!(
        out["rejection/classification"],
        "allocation-not-all-or-nothing"
    );
}

#[test]
fn over_capacity_rejected() {
    let mut input = happy_input();
    input["outcomes"][0]["allocations"] = json!([
        {"claim-id": "A", "allocated": "50"},
        {"claim-id": "B", "allocated": "30"},
        {"claim-id": "C", "allocated": "0"}
    ]);
    let out = run(&input);
    assert_eq!(
        out["rejection/classification"],
        "outcome-not-exact-capacity"
    );
}

#[test]
fn under_capacity_rejected() {
    let mut input = happy_input();
    input["outcomes"][0]["allocations"] = json!([
        {"claim-id": "A", "allocated": "0"},
        {"claim-id": "B", "allocated": "0"},
        {"claim-id": "C", "allocated": "0"}
    ]);
    let out = run(&input);
    assert_eq!(
        out["rejection/classification"],
        "outcome-not-exact-capacity"
    );
}

#[test]
fn ineligible_claimant_rejected() {
    let mut input = happy_input();
    input["outcomes"][0]["allocations"] = json!([
        {"claim-id": "A", "allocated": "50"},
        {"claim-id": "B", "allocated": "0"},
        {"claim-id": "C", "allocated": "0"},
        {"claim-id": "D", "allocated": "0"}
    ]);
    let out = run(&input);
    assert_eq!(out["rejection/classification"], "ineligible-claimant");
}

#[test]
fn duplicate_claim_in_outcome_rejected() {
    let mut input = happy_input();
    input["outcomes"][0]["allocations"] = json!([
        {"claim-id": "A", "allocated": "50"},
        {"claim-id": "A", "allocated": "0"},
        {"claim-id": "B", "allocated": "0"},
        {"claim-id": "C", "allocated": "0"}
    ]);
    let out = run(&input);
    assert_eq!(
        out["rejection/classification"],
        "duplicate-claim-in-outcome"
    );
}

#[test]
fn proportionality_failure_rejected() {
    let mut input = happy_input();
    input["proposed-rates"] = json!([
        {"outcome-id": "O1", "numerator": "1", "denominator": "4"},
        {"outcome-id": "O2", "numerator": "3", "denominator": "4"}
    ]);
    let out = run(&input);
    assert_eq!(out["rejection/classification"], "proportionality-failure");
}

#[test]
fn changed_randomness_rejected() {
    let mut input = happy_input();
    // committed block binds the original selection; changed randomness must not
    // reproduce it
    input["committed"] = json!({
        "claimant-set-root": "0xc45b857d28c2d643dd9331fe396c44862df632125a6299e523073a197ce7a978",
        "outcome-set-root": "0xc8180b14b358826dad95c2dad6b3783d2a380af2790a66b27a9580a01d99b866",
        "proposed-rates-root": "0x4d32943ddcbf3ced6f774bb2841ec526ddefa5105777e981d3883ce9225c6314",
        "result-root": "0xf82ca61e0b3bb6949606bef5489663b73db9359dfb6eed478c3c2701440fdc06",
        "selected-outcome-id": "O2",
        "selected-outcome-index": "1"
    });
    input["authoritative-randomness"] =
        json!("0x0000000000000000000000000000000000000000000000000000000000000001");
    let out = run(&input);
    assert_eq!(out["result/status"], "rejected");
    assert_eq!(out["rejection/classification"], "selected-outcome-mismatch");
}

#[test]
fn forged_committed_root_rejected() {
    let mut input = happy_input();
    input["committed"] = json!({
        "claimant-set-root": "0x0000000000000000000000000000000000000000000000000000000000000000",
        "outcome-set-root": "0xc8180b14b358826dad95c2dad6b3783d2a380af2790a66b27a9580a01d99b866",
        "proposed-rates-root": "0x4d32943ddcbf3ced6f774bb2841ec526ddefa5105777e981d3883ce9225c6314",
        "result-root": "0xf82ca61e0b3bb6949606bef5489663b73db9359dfb6eed478c3c2701440fdc06",
        "selected-outcome-id": "O2",
        "selected-outcome-index": "1"
    });
    let out = run(&input);
    assert_eq!(out["result/status"], "rejected");
    assert_eq!(
        out["rejection/classification"],
        "claimant-set-root-mismatch"
    );
}

#[test]
fn empty_outcome_set_rejected_with_classification() {
    let mut input = happy_input();
    input["outcomes"] = json!([]);
    let ctx = kernel::parse_context(&input);
    assert!(ctx.is_err());
    let err = ctx.unwrap_err();
    assert_eq!(err.classification, "empty-outcome-set");
}
