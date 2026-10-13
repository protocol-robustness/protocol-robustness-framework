use allocation_kernel::source_bound_entitlement::{derive, projection_json, PUBLIC_VALUES_LEN};
use serde_json::{json, Value};

#[path = "../../sp1-script/src/source_bound_entitlement_vectors.rs"]
mod source_bound_entitlement_vectors;

fn fixture() -> Value {
    serde_json::from_str(include_str!(
        "../../../etc/conformance/vectors/source-bound-entitlement-v1.json"
    ))
    .expect("valid conformance fixture")
}

fn replace_mapping_field(base: &mut Value, claim: &str, field: &str, value: &str) {
    let row = base["beneficiary-mapping"]
        .as_array_mut()
        .unwrap()
        .iter_mut()
        .find(|row| row["claim-id"] == claim)
        .unwrap();
    row[field] = Value::String(value.to_owned());
}

#[test]
fn clojure_fixture_and_rust_derivation_are_byte_exact() {
    let fixture = fixture();
    for vector in fixture["vectors"].as_array().unwrap() {
        let id = vector["id"].as_str().unwrap();
        let materialized =
            source_bound_entitlement_vectors::materialize_vector(&fixture, id).unwrap();
        let input = materialized.input;
        if vector["expected-status"] == "rejected" {
            let error = derive(&input).unwrap_err();
            assert!(
                error.contains(vector["expected-error"].as_str().unwrap()),
                "{id}: {error}"
            );
        } else {
            let derived = derive(&input).unwrap_or_else(|error| panic!("{id}: {error}"));
            assert_eq!(projection_json(&derived), fixture["expected"][id], "{id}");
            assert_eq!(derived.public_values.encode().len(), PUBLIC_VALUES_LEN);
        }
    }
}

#[test]
fn salt_changes_only_evm_opening_dependent_outputs() {
    let fixture = fixture();
    let base = &fixture["base-input"];
    let original =
        derive(&source_bound_entitlement_vectors::materialize_transform(base, "identity").unwrap())
            .unwrap();
    let salted = derive(
        &source_bound_entitlement_vectors::materialize_transform(base, "replace-claim-b-salt")
            .unwrap(),
    )
    .unwrap();

    assert_eq!(
        original.realized_statement_root,
        salted.realized_statement_root
    );
    assert_eq!(
        original.allocation_binding_root,
        salted.allocation_binding_root
    );
    assert_eq!(
        original.canonical_entitlement_set_root,
        salted.canonical_entitlement_set_root
    );
    assert_eq!(original.allocation_claim_root, salted.allocation_claim_root);
    assert_ne!(
        original.evm_entitlement_commitments,
        salted.evm_entitlement_commitments
    );
    assert_ne!(
        original.evm_entitlement_table_root,
        salted.evm_entitlement_table_root
    );
}

#[test]
fn input_order_is_not_authoritative() {
    let fixture = fixture();
    let base = &fixture["base-input"];
    let original =
        derive(&source_bound_entitlement_vectors::materialize_transform(base, "identity").unwrap())
            .unwrap();
    let reordered = derive(
        &source_bound_entitlement_vectors::materialize_transform(base, "reverse-all-input-rows")
            .unwrap(),
    )
    .unwrap();
    assert_eq!(original, reordered);
}

#[test]
fn downstream_beneficiary_remapping_is_rejected() {
    let fixture = fixture();
    let mut remapped = fixture["base-input"].clone();
    replace_mapping_field(
        &mut remapped,
        "claim-b",
        "beneficiary",
        "0x4444444444444444444444444444444444444444",
    );
    assert_eq!(
        derive(&remapped).unwrap_err(),
        "beneficiary must match allocation-context economic owner"
    );
}

#[test]
fn prepared_operation_profile_is_authenticated_and_changes_its_root() {
    let fixture = fixture();
    let base = &fixture["base-input"];
    let original = derive(base).unwrap();
    let mut changed = base.clone();
    changed["prepared-operation"]["beneficiary-profile"] = json!("immutable-address-v1");
    let changed = derive(&changed).unwrap();
    assert_ne!(
        original.prepared_operation_root,
        changed.prepared_operation_root
    );
    assert_ne!(
        original.canonical_entitlement_set_root,
        changed.canonical_entitlement_set_root
    );
    assert_ne!(
        original.allocation_claim_root,
        changed.allocation_claim_root
    );
    assert_ne!(
        original.evm_entitlement_table_root,
        changed.evm_entitlement_table_root
    );
}

#[test]
fn v3_reserve_commitment_binds_policy_root_without_changing_v2_vectors() {
    let fixture = fixture();
    let mut input = fixture["base-input"].clone();
    let v2 = derive(&input).unwrap();

    input["economic-reserve-state"]["commitment-version"] = json!("v3");
    input["economic-reserve-state"]["policy-root"] =
        json!("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    let v3 = derive(&input).unwrap();
    assert_ne!(v2.reserve_state_commitment, v3.reserve_state_commitment);

    input["economic-reserve-state"]["policy-root"] =
        json!("sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    let changed_policy = derive(&input).unwrap();
    assert_ne!(
        v3.reserve_state_commitment,
        changed_policy.reserve_state_commitment
    );

    input["economic-reserve-state"]
        .as_object_mut()
        .unwrap()
        .remove("policy-root");
    assert_eq!(derive(&input).unwrap_err(), "v3 policy-root required");
}

#[test]
fn policy_root_is_not_accepted_under_v2_commitment_domain() {
    let fixture = fixture();
    let mut input = fixture["base-input"].clone();
    input["economic-reserve-state"]["policy-root"] =
        json!("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    assert_eq!(
        derive(&input).unwrap_err(),
        "policy-root requires reserve commitment version v3"
    );
}

#[test]
fn rejects_top_level_derived_roots_and_beneficiary_profile() {
    let fixture = fixture();
    let base = fixture["base-input"].clone();
    for field in [
        "beneficiary-profile",
        "realizedStatementRoot",
        "allocationBindingRoot",
        "canonicalEntitlementSetRoot",
        "evmEntitlementTableRoot",
        "allocationClaimRoot",
    ] {
        let mut attempted = base.clone();
        attempted[field] =
            json!("0x1111111111111111111111111111111111111111111111111111111111111111");
        assert_eq!(
            derive(&attempted).unwrap_err(),
            "unknown successor witness field",
            "unexpectedly accepted top-level authority field {field}"
        );
    }
}
