//! Controlled-source gate for the source-bound entitlement successor.
//!
//! The committed source identity is the canonical realized-allocation
//! statement root. The operation initiator commits that root in
//! `prepared-operation.policy-root`; the SP1 verifier consumer later checks
//! the derived prepared-operation root against the on-chain prepared record.
//! This is suitable for an explicitly controlled source such as Vector A. It
//! is not a production claims-authority mechanism.

use crate::source_bound_entitlement::{self, Derivation};
use serde_json::Value;

pub const PROGRAM_ID: &str = "source-bound-entitlement-controlled-sp1-program.v1";

/// Derive the unchanged nine-word entitlement projection, requiring the
/// operation's committed policy root to equal the independently reconstructed
/// realized-allocation statement root.
pub fn derive(input: &Value) -> Result<Derivation, String> {
    let derived = source_bound_entitlement::derive(input)?;
    let committed = input["prepared-operation"]["policy-root"]
        .as_str()
        .ok_or("prepared-operation policy-root required")?;
    let statement_digest = derived.realized_statement_root.as_str();
    if statement_digest.len() != 64
        || !statement_digest
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
    {
        return Err("realized statement root must be a lowercase SHA-256 digest".into());
    }
    let expected = format!("0x{statement_digest}");
    if committed != expected {
        return Err("prepared-operation policy-root must equal realized statement root".into());
    }
    Ok(derived)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn base() -> Value {
        let bundle: Value = serde_json::from_str(include_str!(
            "../../../etc/conformance/vectors/source-bound-entitlement-v1.json"
        ))
        .unwrap();
        bundle["base-input"].clone()
    }

    fn set_source_root(input: &mut Value) {
        let unbound = source_bound_entitlement::derive(input).unwrap();
        let digest = unbound.realized_statement_root;
        input["prepared-operation"]["policy-root"] = json!(format!("0x{digest}"));
    }

    #[test]
    fn accepts_only_the_source_committed_by_the_prepared_operation() {
        let mut input = base();
        set_source_root(&mut input);
        let accepted = derive(&input).unwrap();
        assert_eq!(
            accepted.realized_statement_root,
            format!(
                "{}",
                input["prepared-operation"]["policy-root"]
                    .as_str()
                    .unwrap()
                    .trim_start_matches("0x")
            )
        );
    }

    #[test]
    fn changed_allocation_facts_reject_under_the_old_source_commitment() {
        let mut input = base();
        set_source_root(&mut input);
        input["realized-statement-input"]["requested"]["claim-a"] = json!("49");
        assert_eq!(
            derive(&input).unwrap_err(),
            "prepared-operation policy-root must equal realized statement root"
        );
    }

    #[test]
    fn mismatched_or_malformed_committed_source_root_rejects() {
        let mut input = base();
        input["prepared-operation"]["policy-root"] =
            json!("0x0101010101010101010101010101010101010101010101010101010101010101");
        assert!(derive(&input)
            .unwrap_err()
            .contains("policy-root must equal"));
        input["prepared-operation"]["policy-root"] = json!("not-a-bytes32");
        assert!(derive(&input).is_err());
    }

    #[test]
    fn unmet_request_profile_preserves_shortfall_without_deferred_liability() {
        let mut input = base();
        input["realized-statement-input"]["fail-action-policy"] = json!({"mode":"unmet-request"});
        set_source_root(&mut input);
        let result = derive(&input).unwrap();
        assert_eq!(result.entitlement_total, 70);
        assert_eq!(result.entitlements.iter().map(|m| m.amount).collect::<Vec<_>>(), vec![35,21,14]);
        let details = crate::realized_statement_io::run_realized_statement_with_details(&input["realized-statement-input"], true);
        assert_eq!(details["details"]["audit"]["unmet-request-total"], 30);
        assert_eq!(details["details"]["audit"]["deferred-total"], 0);
        assert_eq!(details["details"]["audit"]["haircut-total"], 0);
        assert_eq!(details["details"]["audit"]["conservation"], true);
        for (index, remaining) in [15,9,6].iter().enumerate() {
            assert_eq!(details["details"]["realized-results"][index]["unrealized"], *remaining);
        }
        input["realized-statement-input"]["fail-action-policy"] = json!({"mode":"pro-rata-treatment","deferred-policy":"same-ratio","haircut-policy":"same-ratio"});
        assert!(derive(&input).is_err(), "a different disposition policy cannot reuse the committed source");
    }

    #[test]
    fn unmet_profile_rounding_and_conservation_edges() {
        let mut input = base()["realized-statement-input"].clone();
        input["fail-action-policy"] = json!({"mode":"unmet-request"});
        input["requested"] = json!({"claim-a":"5","claim-b":"3","claim-c":"2"});
        for available in 0..=10 {
            input["available"] = json!(available.to_string());
            let details = crate::realized_statement_io::run_realized_statement_with_details(&input,true);
            assert_eq!(details["result/status"],"passing");
            assert_eq!(details["details"]["audit"]["filled-total"],available);
            assert_eq!(details["details"]["audit"]["unmet-request-total"],10-available);
            assert_eq!(details["details"]["audit"]["conservation"],true);
            assert_eq!(details["details"]["audit"]["deferred-total"],0);
        }
        input["available"] = json!("7");
        let details = crate::realized_statement_io::run_realized_statement_with_details(&input,true);
        let rows = details["details"]["realized-results"].as_array().unwrap();
        assert_eq!(rows.iter().map(|p|p["filled"].as_i64().unwrap()).collect::<Vec<_>>(),vec![4,2,1]);
        input["fail-action-policy"]["future-obligation"] = json!("deferred");
        assert_eq!(crate::realized_statement_io::run_realized_statement(&input)["result/status"],"rejected");
    }
}
