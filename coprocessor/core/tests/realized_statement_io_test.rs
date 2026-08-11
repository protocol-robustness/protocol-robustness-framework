use allocation_kernel::realized_statement_io;

#[test]
fn all_active_input_produces_passing_statement() {
    let input: serde_json::Value =
        serde_json::from_str(include_str!("fixtures/realized-statement-all-active.json")).unwrap();
    let out = realized_statement_io::run_realized_statement(&input);
    assert_eq!(out["result/status"], "passing");
    // Clojure oracle statement root for all-active:
    assert_eq!(
        out["statement-root"],
        "c22333a16df1c1efa352e9daab42ccbd78f4a1d7530ee3ed3cf7527ba62cbd81"
    );
    assert_eq!(
        out["allocation-context-root"],
        "5155de4de58f55c1437d52d831cd3c747eea30e8455c3a7945dc715a6907a0b5"
    );
    assert_eq!(out["all-active"], true);
}

#[test]
fn malformed_input_fails_closed() {
    let input: serde_json::Value = serde_json::from_str(r#"{"available": "10"}"#).unwrap();
    let out = realized_statement_io::run_realized_statement(&input);
    assert_eq!(out["result/status"], "rejected");
}

fn all_active_input() -> serde_json::Value {
    serde_json::from_str(include_str!("fixtures/realized-statement-all-active.json")).unwrap()
}

#[test]
fn available_above_total_requested_is_rejected() {
    let mut input = all_active_input();
    input["available"] = serde_json::json!("101");
    let out = realized_statement_io::run_realized_statement(&input);
    assert_eq!(out["result/status"], "rejected");
    assert_eq!(
        out["rejection/classification"],
        "available-exceeds-total-requested"
    );
}

#[test]
fn request_claim_absent_from_context_is_rejected() {
    let mut input = all_active_input();
    input["requested"]["unknown-claim"] = serde_json::json!("1");
    let out = realized_statement_io::run_realized_statement(&input);
    assert_eq!(out["result/status"], "rejected");
    assert_eq!(out["rejection/classification"], "unknown-request-claim-id");
}

#[test]
fn invalid_lifecycle_cannot_produce_passing_statement() {
    let mut input = all_active_input();
    input["round-state"] = serde_json::json!("not-a-round-state");
    let out = realized_statement_io::run_realized_statement(&input);
    assert_eq!(out["result/status"], "rejected");
    assert_eq!(out["rejection/classification"], "invalid-round-lifecycle");
}
