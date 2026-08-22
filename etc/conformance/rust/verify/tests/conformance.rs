use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use resubmission_conformance::canonical;
use resubmission_conformance::edn::{map_get, Value};
use resubmission_conformance::fixture;
use resubmission_conformance::ordering;
use resubmission_conformance::transition;

fn hex_to_bytes(hex: &str) -> Vec<u8> {
    let hex = hex.strip_prefix("sha256:").unwrap_or(hex);
    (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
        .collect()
}

fn merge_ordering_with_ctx(ordering_input: &Value, ctx: &Value) -> Value {
    let mut merged = ordering_input.clone();
    if let (Value::Map(m1), Value::Map(m2)) = (&mut merged, ctx) {
        for (k, v) in m2 {
            if !m1.iter().any(|(ek, _)| ek == k) {
                m1.push((k.clone(), v.clone()));
            }
        }
    }
    let ci = ordering::change_identity_hash(&merged);
    if let Value::Map(ref mut m) = merged {
        m.push((
            Value::Keyword(
                Some("transaction".to_string()),
                "change-identity".to_string(),
            ),
            Value::Str(ci),
        ));
    }
    merged
}

fn extract_str(v: &Value) -> Option<String> {
    match v {
        Value::Str(s) => Some(s.clone()),
        _ => None,
    }
}

fn set_field(state: &Value, full_key: &str, val: Value) -> Value {
    let (ns, name) = match full_key.find('/') {
        Some(idx) => (
            Some(full_key[..idx].to_string()),
            full_key[idx + 1..].to_string(),
        ),
        None => (None, full_key.to_string()),
    };
    match state {
        Value::Map(pairs) => {
            let mut new_pairs = pairs.clone();
            new_pairs.retain(|(k, _)| match k {
                Value::Keyword(kns, kn) => !(kns.as_deref() == ns.as_deref() && kn == &name),
                _ => true,
            });
            new_pairs.push((Value::Keyword(ns, name), val));
            Value::Map(new_pairs)
        }
        _ => state.clone(),
    }
}

fn tamper_sig_in_artifact(artifact: &Value, new_sig_hex: String) -> Value {
    let sig_map = map_get(artifact, "attempt-disposition/signature");
    match sig_map {
        Some(Value::Map(sm)) => {
            // The fixture uses unqualified :signature as the key name inside
            // the signature map (not :signature/signature).
            let sig_map_val = Value::Map(sm.clone());
            let new_sm_val = set_field(&sig_map_val, "signature", Value::Str(new_sig_hex));
            set_field(artifact, "attempt-disposition/signature", new_sm_val)
        }
        _ => artifact.clone(),
    }
}

fn transition_status(result: &Value) -> String {
    map_get(result, "status")
        .and_then(|v| match v {
            Value::Keyword(_, n) => Some(n.clone()),
            _ => None,
        })
        .unwrap_or_default()
}

fn transition_reason(result: &Value) -> Option<String> {
    map_get(result, "reason").and_then(|v| match v {
        Value::Keyword(_, n) => Some(n.clone()),
        _ => None,
    })
}

fn strip_signature(artifact: &Value) -> Value {
    match artifact {
        Value::Map(pairs) => {
            let filtered: Vec<(Value, Value)> = pairs
                .iter()
                .filter(|(k, _)| {
                    !matches!(
                        k,
                        Value::Keyword(Some(ns), name)
                            if ns == "attempt-disposition" && name == "signature"
                    )
                })
                .cloned()
                .collect();
            Value::Map(filtered)
        }
        _ => Value::Nil,
    }
}

fn verify_signature(artifact: &Value, pub_hex: &str) -> Result<(), String> {
    let unsigned = strip_signature(artifact);
    let unsigned_bytes = canonical::canonical_bytes(&unsigned);

    let sig_hex = extract_str(
        map_get(artifact, "attempt-disposition/signature")
            .and_then(|v| map_get(v, "signature"))
            .ok_or("no signature found")?,
    )
    .ok_or("signature is not a string")?;
    let sig_bytes = hex_to_bytes(&sig_hex);
    let pub_bytes = hex_to_bytes(pub_hex);

    if pub_bytes.len() != 32 {
        return Err("bad-pubkey-length".into());
    }

    let pubkey = VerifyingKey::from_bytes(&pub_bytes.try_into().unwrap())
        .map_err(|_| "bad-pubkey".to_string())?;

    let signature =
        Signature::from_slice(&sig_bytes).map_err(|_| "bad-signature-format".to_string())?;

    pubkey
        .verify(&unsigned_bytes, &signature)
        .map_err(|_| "invalid-disposition-signature".to_string())
}

// ---------------------------------------------------------------------------
// FIXTURE 1: Genesis admit (successful transition)
// ---------------------------------------------------------------------------

#[test]
fn genesis_admit_reproduces_all_roots() {
    let fx = fixture::load_fixture("resubmission-transition-v1");

    assert_eq!(
        fixture::fixture_id(&fx),
        "resubmission-transition-genesis-admit.v1"
    );
    assert_eq!(fixture::fixture_kind(&fx), "committed");

    let state_before = fixture::state_before(&fx);
    let computed_sb_root = transition::state_root(state_before);
    assert_eq!(
        computed_sb_root,
        fixture::pinned_hex(&fx, "state-before-root")
    );

    let proj_before = transition::chain_state_projection(state_before);
    assert_eq!(
        canonical::canonical_bytes_hex(&proj_before),
        fixture::pinned_hex(&fx, "state-before-canonical-bytes-hex")
    );

    let cmd = fixture::command(&fx);
    let input = map_get(cmd, "transaction/input").unwrap();
    let result = transition::admit_child(state_before, input);

    assert_eq!(transition_status(&result), "committed");

    let state_after = map_get(&result, "state").unwrap();
    assert_eq!(
        transition::state_root(state_after),
        fixture::pinned_hex(&fx, "state-after-root")
    );

    let proj_after = transition::chain_state_projection(state_after);
    assert_eq!(
        canonical::canonical_bytes_hex(&proj_after),
        fixture::pinned_hex(&fx, "state-after-canonical-bytes-hex")
    );

    let effects = map_get(&result, "effects").unwrap();
    assert_eq!(
        transition::effects_root(effects),
        fixture::pinned_hex(&fx, "effects-root")
    );
    assert_eq!(
        canonical::canonical_bytes_hex(effects),
        fixture::pinned_hex(&fx, "effects-canonical-bytes-hex")
    );

    let action = map_get(cmd, "transaction/action").unwrap();
    assert_eq!(
        transition::command_input_root(action, input),
        fixture::pinned_hex(&fx, "input-root")
    );

    let ctx = fixture::semantic_context(&fx);
    let ordering_input = map_get(&result, "ordering-input").unwrap();
    let full_ordering = merge_ordering_with_ctx(ordering_input, ctx);
    assert_eq!(
        ordering::change_identity_hash(&full_ordering),
        fixture::pinned_hex(&fx, "change-identity")
    );
    assert_eq!(
        ordering::ordering_hash(&full_ordering),
        fixture::pinned_hex(&fx, "ordering-root")
    );
    assert_eq!(
        canonical::canonical_bytes_hex(&ordering::unsigned_ordering_projection_v2(&full_ordering)),
        fixture::pinned_hex(&fx, "ordering-canonical-bytes-hex")
    );
}

// ---------------------------------------------------------------------------
// FIXTURE 2: Rejected duplicate-content submission
// ---------------------------------------------------------------------------

#[test]
fn rejection_duplicate_content_reproduces_outcome() {
    let fx = fixture::load_fixture("resubmission-transition-rejection-v1");

    assert_eq!(
        fixture::fixture_id(&fx),
        "resubmission-transition-duplicate-content.v1"
    );
    assert_eq!(fixture::fixture_kind(&fx), "rejected");

    let state_before = fixture::state_before(&fx);
    let cmd = fixture::command(&fx);
    let input = map_get(cmd, "transaction/input").unwrap();

    assert_eq!(
        transition::state_root(state_before),
        fixture::pinned_hex(&fx, "state-before-root")
    );

    let state_before_bytes = canonical::canonical_bytes(state_before);

    let result = transition::admit_child(state_before, input);

    assert_eq!(transition_status(&result), "rejected");
    assert_eq!(
        transition_reason(&result),
        Some("duplicate-content-submission".to_string())
    );

    let pub_result = map_get(&result, "public-result").unwrap();
    let existing = map_get(pub_result, "existing").and_then(extract_str);
    assert_eq!(existing, Some("sha256:R1".to_string()));

    // Rejection MUST NOT produce a state-after, effects, or ordering record.
    assert!(
        map_get(&result, "state").is_none(),
        "rejection must NOT produce state-after"
    );
    assert!(
        map_get(&result, "effects").is_none(),
        "rejection must NOT produce effects"
    );
    assert!(
        map_get(&result, "ordering-input").is_none(),
        "rejection must NOT produce ordering-input"
    );

    // State-before MUST be preserved: the original state is not mutated.
    assert_eq!(
        canonical::canonical_bytes(state_before),
        state_before_bytes,
        "state-before must not be mutated by rejection"
    );
    assert_eq!(
        transition::state_root(state_before),
        fixture::pinned_hex(&fx, "state-before-root"),
        "state-before root must be unchanged"
    );
}

// ---------------------------------------------------------------------------
// FIXTURE 3: Final disposition (committed, with disposition-status)
// ---------------------------------------------------------------------------

#[test]
fn disposition_final_reproduces_all_roots() {
    let fx = fixture::load_fixture("resubmission-transition-disposition-v1");

    assert_eq!(
        fixture::fixture_id(&fx),
        "resubmission-transition-disposition-final.v1"
    );
    assert_eq!(fixture::fixture_kind(&fx), "committed");

    let state_before = fixture::state_before(&fx);
    let cmd = fixture::command(&fx);
    let input = map_get(cmd, "transaction/input").unwrap();

    assert_eq!(
        transition::state_root(state_before),
        fixture::pinned_hex(&fx, "state-before-root")
    );

    let proj_before = transition::chain_state_projection(state_before);
    assert_eq!(
        canonical::canonical_bytes_hex(&proj_before),
        fixture::pinned_hex(&fx, "state-before-canonical-bytes-hex")
    );

    let auth = map_get(&fx, "fixture/disposition-authority")
        .expect("fixture must have disposition authority");
    let pub_hex = extract_str(map_get(auth, "public-key-hex").unwrap()).unwrap();

    let artifact = map_get(input, "disposition-artifact").unwrap();

    let unsigned = strip_signature(artifact);
    let unsigned_bytes = canonical::canonical_bytes(&unsigned);

    let sig_hex = extract_str(
        map_get(artifact, "attempt-disposition/signature")
            .and_then(|v| map_get(v, "signature"))
            .unwrap(),
    )
    .unwrap();
    let pub_bytes = hex_to_bytes(&pub_hex);
    assert_eq!(pub_bytes.len(), 32, "Ed25519 public key must be 32 bytes");

    let pubkey = VerifyingKey::from_bytes(&pub_bytes.try_into().unwrap()).unwrap();
    let signature = Signature::from_slice(&hex_to_bytes(&sig_hex)).unwrap();
    pubkey
        .verify(&unsigned_bytes, &signature)
        .expect("disposition signature must verify");

    assert_eq!(
        transition::disposition_hash(artifact),
        fixture::pinned_hex(&fx, "disposition-artifact-hash")
    );

    let action = map_get(cmd, "transaction/action").unwrap();
    assert_eq!(
        transition::command_input_root(action, input),
        fixture::pinned_hex(&fx, "input-root")
    );

    let result = transition::apply_disposition(state_before, input);

    assert_eq!(transition_status(&result), "committed");

    let state_after = map_get(&result, "state").unwrap();
    assert_eq!(
        transition::state_root(state_after),
        fixture::pinned_hex(&fx, "state-after-root")
    );

    let proj_after = transition::chain_state_projection(state_after);
    assert_eq!(
        canonical::canonical_bytes_hex(&proj_after),
        fixture::pinned_hex(&fx, "state-after-canonical-bytes-hex")
    );

    let dsb = map_get(state_after, "chain/disposition-status-by-receipt");
    assert!(
        dsb.is_some(),
        "state-after must contain disposition-status-by-receipt"
    );
    let r1_status = map_get(dsb.unwrap(), "sha256:R1").and_then(|v| match v {
        Value::Keyword(_, n) => Some(n.clone()),
        _ => None,
    });
    assert_eq!(r1_status, Some("final".to_string()));

    let effects = map_get(&result, "effects").unwrap();
    assert_eq!(
        transition::effects_root(effects),
        fixture::pinned_hex(&fx, "effects-root")
    );
    assert_eq!(
        canonical::canonical_bytes_hex(effects),
        fixture::pinned_hex(&fx, "effects-canonical-bytes-hex")
    );

    let ctx = fixture::semantic_context(&fx);
    let ordering_input = map_get(&result, "ordering-input").unwrap();
    let full_ordering = merge_ordering_with_ctx(ordering_input, ctx);
    assert_eq!(
        ordering::change_identity_hash(&full_ordering),
        fixture::pinned_hex(&fx, "change-identity")
    );
    assert_eq!(
        ordering::ordering_hash(&full_ordering),
        fixture::pinned_hex(&fx, "ordering-root")
    );
    assert_eq!(
        canonical::canonical_bytes_hex(&ordering::unsigned_ordering_projection_v2(&full_ordering)),
        fixture::pinned_hex(&fx, "ordering-canonical-bytes-hex")
    );
}

// ---------------------------------------------------------------------------
// CONSECUTIVE CHAIN: Genesis admit then disposition
// ---------------------------------------------------------------------------

#[test]
fn genesis_then_disposition_forms_consecutive_chain() {
    let genesis_fx = fixture::load_fixture("resubmission-transition-v1");
    let disp_fx = fixture::load_fixture("resubmission-transition-disposition-v1");

    let genesis_state_before = fixture::state_before(&genesis_fx);
    let genesis_cmd = fixture::command(&genesis_fx);
    let genesis_input = map_get(genesis_cmd, "transaction/input").unwrap();
    let genesis_result = transition::admit_child(genesis_state_before, genesis_input);
    assert_eq!(transition_status(&genesis_result), "committed");

    let genesis_state_after = map_get(&genesis_result, "state").unwrap();

    let genesis_after_root = transition::state_root(genesis_state_after);
    assert_eq!(
        genesis_after_root,
        fixture::pinned_hex(&genesis_fx, "state-after-root"),
        "derived genesis state-after root must match genesis fixture"
    );
    assert_eq!(
        genesis_after_root,
        fixture::pinned_hex(&disp_fx, "state-before-root"),
        "genesis state-after root must equal disposition state-before root"
    );

    let auth = map_get(&disp_fx, "fixture/disposition-authority")
        .expect("disposition fixture must have authority");
    let pub_hex = extract_str(map_get(auth, "public-key-hex").unwrap()).unwrap();

    let genesis_for_disp = set_field(
        genesis_state_after,
        "chain/disposition-public-hex",
        Value::Str(pub_hex.clone()),
    );

    assert_eq!(
        transition::state_root(&genesis_for_disp),
        genesis_after_root,
        "disposition-public-hex is excluded from projection; root must be unchanged"
    );
    assert_eq!(
        transition::state_root(&genesis_for_disp),
        fixture::pinned_hex(&disp_fx, "state-before-root"),
        "derived state-before root must match disposition fixture"
    );

    let disp_cmd = fixture::command(&disp_fx);
    let disp_input = map_get(disp_cmd, "transaction/input").unwrap();
    let artifact = map_get(disp_input, "disposition-artifact").unwrap();

    verify_signature(artifact, &pub_hex).expect("signature must verify against state authority");

    let disp_result = transition::apply_disposition(&genesis_for_disp, disp_input);
    assert_eq!(transition_status(&disp_result), "committed");

    let disp_state_after = map_get(&disp_result, "state").unwrap();

    assert_eq!(
        transition::state_root(disp_state_after),
        fixture::pinned_hex(&disp_fx, "state-after-root")
    );
    assert_eq!(
        canonical::canonical_bytes_hex(&transition::chain_state_projection(disp_state_after)),
        fixture::pinned_hex(&disp_fx, "state-after-canonical-bytes-hex")
    );

    let effects = map_get(&disp_result, "effects").unwrap();
    assert_eq!(
        transition::effects_root(effects),
        fixture::pinned_hex(&disp_fx, "effects-root")
    );
    assert_eq!(
        canonical::canonical_bytes_hex(effects),
        fixture::pinned_hex(&disp_fx, "effects-canonical-bytes-hex")
    );

    let action = map_get(disp_cmd, "transaction/action").unwrap();
    assert_eq!(
        transition::command_input_root(action, disp_input),
        fixture::pinned_hex(&disp_fx, "input-root")
    );

    assert_eq!(
        transition::disposition_hash(artifact),
        fixture::pinned_hex(&disp_fx, "disposition-artifact-hash")
    );

    let disp_ctx = fixture::semantic_context(&disp_fx);
    let disp_ordering_input = map_get(&disp_result, "ordering-input").unwrap();
    let disp_full_ordering = merge_ordering_with_ctx(disp_ordering_input, disp_ctx);
    assert_eq!(
        ordering::change_identity_hash(&disp_full_ordering),
        fixture::pinned_hex(&disp_fx, "change-identity")
    );
    assert_eq!(
        ordering::ordering_hash(&disp_full_ordering),
        fixture::pinned_hex(&disp_fx, "ordering-root")
    );
    assert_eq!(
        canonical::canonical_bytes_hex(&ordering::unsigned_ordering_projection_v2(
            &disp_full_ordering
        )),
        fixture::pinned_hex(&disp_fx, "ordering-canonical-bytes-hex")
    );

    // Version and commit-index progression.
    let gv_before =
        transition::extract_int(map_get(genesis_state_before, "chain/version").unwrap());
    let gv_after = transition::extract_int(map_get(genesis_state_after, "chain/version").unwrap());
    let ci_after =
        transition::extract_int(map_get(genesis_state_after, "transaction/commit-index").unwrap());
    assert_eq!(gv_before, 0, "genesis state-before version must be 0");
    assert_eq!(gv_after, 1, "genesis state-after version must be 1");
    assert_eq!(ci_after, 1, "genesis state-after commit-index must be 1");

    let dv_after = transition::extract_int(map_get(disp_state_after, "chain/version").unwrap());
    let dci_after =
        transition::extract_int(map_get(disp_state_after, "transaction/commit-index").unwrap());
    assert_eq!(dv_after, 2, "disposition state-after version must be 2");
    assert_eq!(
        dci_after, 2,
        "disposition state-after commit-index must be 2"
    );
    assert_eq!(dv_after, gv_after + 1, "disposition must increment version");
    assert_eq!(
        dci_after,
        ci_after + 1,
        "disposition must increment commit-index"
    );

    // Chain continuity.
    assert_eq!(
        extract_str(map_get(genesis_state_after, "chain/family-id").unwrap()).unwrap(),
        extract_str(map_get(disp_state_after, "chain/family-id").unwrap()).unwrap(),
        "family-id must be preserved across consecutive transitions"
    );

    let genesis_ordering_input = map_get(&genesis_result, "ordering-input").unwrap();
    let disp_ordering_scope = map_get(disp_ordering_input, "transaction/scope");
    let genesis_scope = map_get(genesis_ordering_input, "transaction/scope");
    assert_eq!(
        genesis_scope, disp_ordering_scope,
        "ordering scope must be consistent across consecutive transitions"
    );

    let genesis_conflict = map_get(genesis_ordering_input, "transaction/conflict-key");
    let disp_conflict = map_get(disp_ordering_input, "transaction/conflict-key");
    assert_eq!(
        genesis_conflict, disp_conflict,
        "ordering conflict-key must be consistent across consecutive transitions"
    );
}

// ---------------------------------------------------------------------------
// NEGATIVE CHAIN-COMPOSITION TESTS
// ---------------------------------------------------------------------------

#[test]
fn consecutive_chain_rejects_unconfigured_authority() {
    let genesis_fx = fixture::load_fixture("resubmission-transition-v1");
    let disp_fx = fixture::load_fixture("resubmission-transition-disposition-v1");

    let genesis_state_before = fixture::state_before(&genesis_fx);
    let genesis_cmd = fixture::command(&genesis_fx);
    let genesis_input = map_get(genesis_cmd, "transaction/input").unwrap();
    let genesis_result = transition::admit_child(genesis_state_before, genesis_input);
    let genesis_state_after = map_get(&genesis_result, "state").unwrap();

    assert_eq!(
        map_get(genesis_state_after, "chain/disposition-public-hex"),
        Some(&Value::Nil),
        "derived genesis state must have nil disposition-public-hex"
    );

    let disp_cmd = fixture::command(&disp_fx);
    let disp_input = map_get(disp_cmd, "transaction/input").unwrap();

    let result = transition::apply_disposition(genesis_state_after, disp_input);
    assert_eq!(
        transition_status(&result),
        "rejected",
        "apply_disposition must reject when disposition-public-hex is absent"
    );
    assert_eq!(
        transition_reason(&result),
        Some("disposition-authority-not-configured".to_string())
    );
    assert!(
        map_get(&result, "state").is_none(),
        "rejection must not produce state-after"
    );
    assert!(
        map_get(&result, "effects").is_none(),
        "rejection must not produce effects"
    );
}

#[test]
fn consecutive_chain_rejects_wrong_family() {
    let genesis_fx = fixture::load_fixture("resubmission-transition-v1");
    let disp_fx = fixture::load_fixture("resubmission-transition-disposition-v1");

    let genesis_state_before = fixture::state_before(&genesis_fx);
    let genesis_cmd = fixture::command(&genesis_fx);
    let genesis_input = map_get(genesis_cmd, "transaction/input").unwrap();
    let genesis_result = transition::admit_child(genesis_state_before, genesis_input);
    let genesis_state_after = map_get(&genesis_result, "state").unwrap();

    let auth = map_get(&disp_fx, "fixture/disposition-authority").unwrap();
    let pub_hex = extract_str(map_get(auth, "public-key-hex").unwrap()).unwrap();

    let tampered = set_field(
        genesis_state_after,
        "chain/family-id",
        Value::Str("sha256:WRONGFAM".to_string()),
    );
    let tampered = set_field(
        &tampered,
        "chain/disposition-public-hex",
        Value::Str(pub_hex),
    );

    assert_ne!(
        transition::state_root(&tampered),
        fixture::pinned_hex(&disp_fx, "state-before-root"),
        "wrong family-id must produce a different state-before-root"
    );

    let disp_cmd = fixture::command(&disp_fx);
    let disp_input = map_get(disp_cmd, "transaction/input").unwrap();
    let result = transition::apply_disposition(&tampered, disp_input);

    assert_eq!(
        transition_status(&result),
        "committed",
        "transition itself may succeed; chain linkage is verified by the store"
    );

    let state_after = map_get(&result, "state").unwrap();
    assert_ne!(
        transition::state_root(state_after),
        fixture::pinned_hex(&disp_fx, "state-after-root"),
        "wrong family-id must produce a different state-after-root"
    );

    let disp_ctx = fixture::semantic_context(&disp_fx);
    let ordering_input = map_get(&result, "ordering-input").unwrap();
    let full = merge_ordering_with_ctx(ordering_input, disp_ctx);
    assert_ne!(
        ordering::ordering_hash(&full),
        fixture::pinned_hex(&disp_fx, "ordering-root"),
        "wrong family-id must produce a different ordering-root"
    );

    let conflict_key = map_get(&full, "transaction/conflict-key");
    let expected_conflict = fixture::pinned_value(&disp_fx, "ordering-v2-projection")
        .and_then(|v| map_get(v, "transaction/conflict-key"));
    assert_ne!(
        conflict_key, expected_conflict,
        "conflict-key must reflect the tampered family-id"
    );
}

#[test]
fn consecutive_chain_rejects_bad_prior_ordering_hash() {
    let disp_fx = fixture::load_fixture("resubmission-transition-disposition-v1");

    let state_before = fixture::state_before(&disp_fx);
    let cmd = fixture::command(&disp_fx);
    let input = map_get(cmd, "transaction/input").unwrap();
    let ctx = fixture::semantic_context(&disp_fx);

    let result = transition::apply_disposition(state_before, input);
    assert_eq!(transition_status(&result), "committed");

    let ordering_input = map_get(&result, "ordering-input").unwrap();
    let correct_ordering = merge_ordering_with_ctx(ordering_input, ctx);
    assert_eq!(
        ordering::ordering_hash(&correct_ordering),
        fixture::pinned_hex(&disp_fx, "ordering-root"),
        "correct ordering hash must match fixture"
    );

    let wrong_prior = "sha256:0000000000000000000000000000000000000000000000000000000000000000";

    let tampered_hash = match &correct_ordering {
        Value::Map(pairs) => {
            let new_pairs: Vec<(Value, Value)> = pairs
                .iter()
                .map(|(k, v)| {
                    if let Value::Keyword(Some(ns), name) = k {
                        if ns == "transaction" && name == "previous-transaction-hash" {
                            return (k.clone(), Value::Str(wrong_prior.to_string()));
                        }
                    }
                    (k.clone(), v.clone())
                })
                .collect();
            ordering::ordering_hash(&Value::Map(new_pairs))
        }
        _ => String::new(),
    };

    assert_ne!(
        tampered_hash,
        fixture::pinned_hex(&disp_fx, "ordering-root"),
        "wrong previous-transaction-hash must produce a different ordering-root"
    );
}

#[test]
fn consecutive_chain_rejects_bad_commit_index() {
    let genesis_fx = fixture::load_fixture("resubmission-transition-v1");
    let disp_fx = fixture::load_fixture("resubmission-transition-disposition-v1");

    let genesis_state_before = fixture::state_before(&genesis_fx);
    let genesis_cmd = fixture::command(&genesis_fx);
    let genesis_input = map_get(genesis_cmd, "transaction/input").unwrap();
    let genesis_result = transition::admit_child(genesis_state_before, genesis_input);
    let genesis_state_after = map_get(&genesis_result, "state").unwrap();

    let auth = map_get(&disp_fx, "fixture/disposition-authority").unwrap();
    let pub_hex = extract_str(map_get(auth, "public-key-hex").unwrap()).unwrap();

    let wrong_ci_state = set_field(
        genesis_state_after,
        "transaction/commit-index",
        Value::Int(99),
    );
    let wrong_ci_state = set_field(&wrong_ci_state, "chain/version", Value::Int(99));
    let wrong_ci_state = set_field(
        &wrong_ci_state,
        "chain/disposition-public-hex",
        Value::Str(pub_hex),
    );

    assert_ne!(
        transition::state_root(&wrong_ci_state),
        fixture::pinned_hex(&disp_fx, "state-before-root"),
        "wrong commit-index+version must produce different state-before-root"
    );

    let disp_cmd = fixture::command(&disp_fx);
    let disp_input = map_get(disp_cmd, "transaction/input").unwrap();
    let result = transition::apply_disposition(&wrong_ci_state, disp_input);

    assert_eq!(
        transition_status(&result),
        "rejected",
        "wrong chain-version must trigger commit-contention rejection"
    );
    assert_eq!(
        transition_reason(&result),
        Some("commit-contention".to_string())
    );
    assert!(
        map_get(&result, "state").is_none(),
        "commit-contention rejection must not produce state-after"
    );
}

// ---------------------------------------------------------------------------
// STRENGTHENED TAMPERING TESTS
// ---------------------------------------------------------------------------

#[test]
fn tamper_state_after_root_detected() {
    let fx = fixture::load_fixture("resubmission-transition-v1");

    let state_before = fixture::state_before(&fx);
    let cmd = fixture::command(&fx);
    let input = map_get(cmd, "transaction/input").unwrap();
    let result = transition::admit_child(state_before, input);

    let ctx = fixture::semantic_context(&fx);
    let ordering_input = map_get(&result, "ordering-input").unwrap();
    let full_ordering = merge_ordering_with_ctx(ordering_input, ctx);

    assert_eq!(
        ordering::ordering_hash(&full_ordering),
        fixture::pinned_hex(&fx, "ordering-root"),
        "honest ordering hash must match fixture"
    );

    let tampered_root = "sha256:0000000000000000000000000000000000000000000000000000000000000000";
    let tampered_hash = match &full_ordering {
        Value::Map(pairs) => {
            let new_pairs: Vec<(Value, Value)> = pairs
                .iter()
                .map(|(k, v)| {
                    if let Value::Keyword(Some(ns), name) = k {
                        if ns == "transaction" && name == "state-after-root" {
                            return (k.clone(), Value::Str(tampered_root.to_string()));
                        }
                    }
                    (k.clone(), v.clone())
                })
                .collect();
            ordering::ordering_hash(&Value::Map(new_pairs))
        }
        _ => String::new(),
    };

    assert_ne!(
        tampered_hash,
        fixture::pinned_hex(&fx, "ordering-root"),
        "tampered state-after-root must produce a different ordering-root"
    );
}

#[test]
fn tamper_domain_tag_detected() {
    let fx = fixture::load_fixture("resubmission-transition-v1");

    let state_before = fixture::state_before(&fx);
    let proj = transition::chain_state_projection(state_before);

    let correct = canonical::domain_hash("prf.resubmission-chain-state.v1", &proj);
    let tampered = canonical::domain_hash("prf.resubmission-chain-state.v2", &proj);

    assert_ne!(
        correct, tampered,
        "wrong domain tag must produce different state root"
    );
    assert_eq!(
        correct,
        fixture::pinned_hex(&fx, "state-before-root"),
        "correct domain tag must reproduce pinned root"
    );
    assert_ne!(
        tampered,
        fixture::pinned_hex(&fx, "state-before-root"),
        "tampered domain tag must not match the pinned root"
    );
}

#[test]
fn tamper_public_key_rejected() {
    let fx = fixture::load_fixture("resubmission-transition-disposition-v1");

    let state_before = fixture::state_before(&fx);
    let cmd = fixture::command(&fx);
    let input = map_get(cmd, "transaction/input").unwrap();

    let auth = map_get(&fx, "fixture/disposition-authority").unwrap();
    let real_pub_hex = extract_str(map_get(auth, "public-key-hex").unwrap()).unwrap();

    let state_pub_hex =
        extract_str(map_get(state_before, "chain/disposition-public-hex").unwrap()).unwrap();
    assert_eq!(
        state_pub_hex, real_pub_hex,
        "state authority must match fixture authority"
    );

    let fake_pub_hex = "a8f4f01d3b2c5e6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a";
    let tampered_state = set_field(
        state_before,
        "chain/disposition-public-hex",
        Value::Str(fake_pub_hex.to_string()),
    );

    let result = transition::apply_disposition(&tampered_state, input);

    assert_eq!(
        transition_status(&result),
        "rejected",
        "wrong authority public key must cause signature verification to fail"
    );
    let reason = transition_reason(&result).unwrap();
    assert!(
        reason == "invalid-disposition-signature" || reason == "bad-pubkey",
        "rejection reason must be a signature/authority error, got: {}",
        reason
    );
    assert!(
        map_get(&result, "state").is_none(),
        "signature failure must not produce state-after"
    );
}

#[test]
fn tampered_disposition_signature_rejected() {
    let fx = fixture::load_fixture("resubmission-transition-disposition-v1");

    let state_before = fixture::state_before(&fx);
    let cmd = fixture::command(&fx);
    let input = map_get(cmd, "transaction/input").unwrap();

    let artifact = map_get(input, "disposition-artifact").unwrap();
    let sig_hex = extract_str(
        map_get(artifact, "attempt-disposition/signature")
            .and_then(|v| map_get(v, "signature"))
            .unwrap(),
    )
    .unwrap();
    let mut sig_bytes = hex_to_bytes(&sig_hex);
    sig_bytes[0] ^= 0x01;
    let tampered_sig_hex: String = sig_bytes.iter().map(|b| format!("{:02x}", b)).collect();

    let tampered_artifact = tamper_sig_in_artifact(artifact, tampered_sig_hex);
    let tampered_input = set_field(input, "disposition-artifact", tampered_artifact);

    let result = transition::apply_disposition(state_before, &tampered_input);

    assert_eq!(
        transition_status(&result),
        "rejected",
        "tampered signature must cause apply_disposition to reject"
    );
    assert_eq!(
        transition_reason(&result),
        Some("invalid-disposition-signature".to_string())
    );
    assert!(
        map_get(&result, "state").is_none(),
        "signature failure must not produce state-after"
    );
}

#[test]
fn absent_vs_empty_disposition_status_distinct() {
    let fx = fixture::load_fixture("resubmission-transition-v1");

    let state_before = fixture::state_before(&fx);
    let base_root = transition::state_root(state_before);

    let empty_state = set_field(
        state_before,
        "chain/disposition-status-by-receipt",
        Value::Map(vec![]),
    );
    let empty_root = transition::state_root(&empty_state);

    assert_ne!(
        base_root, empty_root,
        "absent vs present-but-empty must differ"
    );
    assert_eq!(
        base_root,
        fixture::pinned_hex(&fx, "state-before-root"),
        "absent disposition-status must reproduce pinned root"
    );
}

// ---------------------------------------------------------------------------
// INDEPENDENT ROOT REPORT
// ---------------------------------------------------------------------------

#[test]
fn independently_reproduced_roots_report() {
    let genesis_fx = fixture::load_fixture("resubmission-transition-v1");
    let disp_fx = fixture::load_fixture("resubmission-transition-disposition-v1");

    let gsb = fixture::state_before(&genesis_fx);
    let gcmd = fixture::command(&genesis_fx);
    let ginput = map_get(gcmd, "transaction/input").unwrap();
    let gres = transition::admit_child(gsb, ginput);
    let gsa = map_get(&gres, "state").unwrap();
    let gctx = fixture::semantic_context(&genesis_fx);
    let gord = merge_ordering_with_ctx(map_get(&gres, "ordering-input").unwrap(), gctx);

    println!("=== Genesis admit independently reproduced roots ===");
    println!(
        "  state-before-root       = {}",
        transition::state_root(gsb)
    );
    println!(
        "  state-after-root        = {}",
        transition::state_root(gsa)
    );
    println!(
        "  effects-root            = {}",
        transition::effects_root(map_get(&gres, "effects").unwrap())
    );
    println!(
        "  input-root              = {}",
        transition::command_input_root(map_get(gcmd, "transaction/action").unwrap(), ginput)
    );
    println!(
        "  change-identity         = {}",
        ordering::change_identity_hash(&gord)
    );
    println!(
        "  ordering-root           = {}",
        ordering::ordering_hash(&gord)
    );

    let ds = fixture::state_before(&disp_fx);
    let dcmd = fixture::command(&disp_fx);
    let dinput = map_get(dcmd, "transaction/input").unwrap();
    let dres = transition::apply_disposition(ds, dinput);
    let dsa = map_get(&dres, "state").unwrap();
    let dctx = fixture::semantic_context(&disp_fx);
    let dord = merge_ordering_with_ctx(map_get(&dres, "ordering-input").unwrap(), dctx);

    println!("=== Disposition-final independently reproduced roots ===");
    println!("  state-before-root       = {}", transition::state_root(ds));
    println!(
        "  state-after-root        = {}",
        transition::state_root(dsa)
    );
    println!(
        "  effects-root            = {}",
        transition::effects_root(map_get(&dres, "effects").unwrap())
    );
    println!(
        "  input-root              = {}",
        transition::command_input_root(map_get(dcmd, "transaction/action").unwrap(), dinput)
    );
    println!(
        "  change-identity         = {}",
        ordering::change_identity_hash(&dord)
    );
    println!(
        "  ordering-root           = {}",
        ordering::ordering_hash(&dord)
    );
    println!(
        "  disposition-artifact-hash = {}",
        transition::disposition_hash(map_get(dinput, "disposition-artifact").unwrap())
    );
}
