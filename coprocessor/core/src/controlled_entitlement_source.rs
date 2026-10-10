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
}
