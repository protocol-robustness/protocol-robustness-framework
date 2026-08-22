use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use resubmission_conformance::canonical;
use resubmission_conformance::edn::{map_get, Value};
use resubmission_conformance::fixture;
use resubmission_conformance::ordering;
use resubmission_conformance::transition;
use sha2::{Digest, Sha256};

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
    // Add derived change-identity (v2 derives this from scope, conflict-key, action, input-root)
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

// FIXTURE 1: Genesis admit (successful transition)

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

    // Independently derive transition (NOT using fixture state-after)
    let cmd = fixture::command(&fx);
    let input = map_get(cmd, "transaction/input").unwrap();
    let result = transition::admit_child(state_before, input);

    let status = map_get(&result, "status")
        .and_then(|v| match v {
            Value::Keyword(_, n) => Some(n.clone()),
            _ => None,
        })
        .unwrap();
    assert_eq!(status, "committed");

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

// FIXTURE 2: Rejected duplicate-content submission

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

    // Independently derive the rejection (NOT using fixture state-after)
    let result = transition::admit_child(state_before, input);

    let status = map_get(&result, "status")
        .and_then(|v| match v {
            Value::Keyword(_, n) => Some(n.clone()),
            _ => None,
        })
        .unwrap();
    assert_eq!(status, "rejected");

    let reason = map_get(&result, "reason")
        .and_then(|v| match v {
            Value::Keyword(_, n) => Some(n.clone()),
            _ => None,
        })
        .unwrap();
    assert_eq!(reason, "duplicate-content-submission");

    let pub_result = map_get(&result, "public-result").unwrap();
    let existing = map_get(pub_result, "existing").and_then(|v| match v {
        Value::Str(s) => Some(s.clone()),
        _ => None,
    });
    assert_eq!(existing, Some("sha256:R1".to_string()));

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

    // State is unchanged: re-derive root from the same state
    assert_eq!(
        transition::state_root(state_before),
        fixture::pinned_hex(&fx, "state-before-root")
    );
}

// FIXTURE 3: Final disposition (committed, with disposition-status)

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

    // Independent Ed25519 verification of the disposition signature
    let auth = map_get(&fx, "fixture/disposition-authority")
        .expect("fixture must have disposition authority");
    let pub_hex = map_get(auth, "public-key-hex")
        .and_then(|v| match v {
            Value::Str(s) => Some(s.clone()),
            _ => None,
        })
        .expect("must have public-key-hex");

    let artifact =
        map_get(input, "disposition-artifact").expect("command must have disposition-artifact");

    // Compute unsigned projection and canonical bytes
    let unsigned = match artifact {
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
        _ => panic!("artifact must be a map"),
    };
    let unsigned_bytes = canonical::canonical_bytes(&unsigned);

    // Verify signature
    let sig_hex = match map_get(artifact, "attempt-disposition/signature")
        .and_then(|v| map_get(v, "signature"))
    {
        Some(Value::Str(s)) => s.clone(),
        _ => panic!("no signature found"),
    };
    let sig_bytes = hex_to_bytes(&sig_hex);
    let pub_bytes = hex_to_bytes(&pub_hex);
    assert_eq!(pub_bytes.len(), 32, "Ed25519 public key must be 32 bytes");

    let pubkey = VerifyingKey::from_bytes(&pub_bytes.try_into().unwrap()).expect("valid pubkey");
    let signature = Signature::from_slice(&sig_bytes).expect("valid signature format");
    pubkey
        .verify(&unsigned_bytes, &signature)
        .expect("disposition signature must verify");

    // Independently compute disposition hash
    assert_eq!(
        transition::disposition_hash(artifact),
        fixture::pinned_hex(&fx, "disposition-artifact-hash")
    );

    // Independently compute input-root
    let action = map_get(cmd, "transaction/action").unwrap();
    assert_eq!(
        transition::command_input_root(action, input),
        fixture::pinned_hex(&fx, "input-root")
    );

    // Independently derive state-after via transition logic
    let result = transition::apply_disposition(state_before, input);

    let status = map_get(&result, "status")
        .and_then(|v| match v {
            Value::Keyword(_, n) => Some(n.clone()),
            _ => None,
        })
        .unwrap();
    assert_eq!(status, "committed");

    // State-after root
    let state_after = map_get(&result, "state").unwrap();
    assert_eq!(
        transition::state_root(state_after),
        fixture::pinned_hex(&fx, "state-after-root")
    );

    // State-after canonical bytes
    let proj_after = transition::chain_state_projection(state_after);
    assert_eq!(
        canonical::canonical_bytes_hex(&proj_after),
        fixture::pinned_hex(&fx, "state-after-canonical-bytes-hex")
    );

    // Verify disposition-status-by-receipt in state-after
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

    // Effects
    let effects = map_get(&result, "effects").unwrap();
    assert_eq!(
        transition::effects_root(effects),
        fixture::pinned_hex(&fx, "effects-root")
    );
    assert_eq!(
        canonical::canonical_bytes_hex(effects),
        fixture::pinned_hex(&fx, "effects-canonical-bytes-hex")
    );

    // Change-identity and ordering
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

// TAMPERING TESTS

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

    let real_ord_hash = ordering::ordering_hash(&full_ordering);
    let pinned_ord = fixture::pinned_hex(&fx, "ordering-root");
    assert_eq!(
        real_ord_hash, pinned_ord,
        "honest ordering hash must match fixture"
    );

    // Tamper: replace state-after-root in the ordering projection
    let tampered = match &full_ordering {
        Value::Map(pairs) => {
            let new_pairs: Vec<(Value, Value)> = pairs
                .iter()
                .map(|(k, v)| {
                    if let Value::Keyword(Some(ns), name) = k {
                        if ns == "transaction" && name == "state-after-root" {
                            (
                                k.clone(),
                                Value::Str(
                                    "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                                        .to_string(),
                                ),
                            )
                        } else {
                            (k.clone(), v.clone())
                        }
                    } else {
                        (k.clone(), v.clone())
                    }
                })
                .collect();
            ordering::ordering_hash(&Value::Map(new_pairs))
        }
        _ => String::new(),
    };

    assert_ne!(
        tampered, pinned_ord,
        "tampered state-after-root must produce different ordering hash"
    );
}

#[test]
fn tamper_domain_tag_detected() {
    let fx = fixture::load_fixture("resubmission-transition-v1");

    let state_before = fixture::state_before(&fx);
    let proj = transition::chain_state_projection(state_before);

    let correct = canonical::domain_hash("prf.resubmission-chain-state.v1", &proj);
    let tampered = canonical::domain_hash("prf.resubmission-chain-state.v2", &proj);

    assert_ne!(correct, tampered);
    assert_eq!(correct, fixture::pinned_hex(&fx, "state-before-root"));
}

#[test]
fn invalid_ed25519_signature_detected() {
    let fx = fixture::load_fixture("resubmission-transition-disposition-v1");

    let auth = map_get(&fx, "fixture/disposition-authority")
        .expect("fixture must have disposition authority");
    let pub_hex = map_get(auth, "public-key-hex")
        .and_then(|v| match v {
            Value::Str(s) => Some(s.clone()),
            _ => None,
        })
        .unwrap();

    let cmd = fixture::command(&fx);
    let input = map_get(cmd, "transaction/input").unwrap();
    let artifact = map_get(input, "disposition-artifact").unwrap();

    // Real signature must verify
    let unsigned = match artifact {
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
        _ => panic!("artifact must be a map"),
    };
    let unsigned_bytes = canonical::canonical_bytes(&unsigned);

    let sig_hex = match map_get(artifact, "attempt-disposition/signature")
        .and_then(|v| map_get(v, "signature"))
    {
        Some(Value::Str(s)) => s.clone(),
        _ => panic!("no signature found"),
    };
    let sig_bytes = hex_to_bytes(&sig_hex);
    let pub_bytes = hex_to_bytes(&pub_hex);

    let pubkey = VerifyingKey::from_bytes(&pub_bytes.try_into().unwrap()).expect("valid pubkey");
    let signature = Signature::from_slice(&sig_bytes).unwrap();
    pubkey
        .verify(&unsigned_bytes, &signature)
        .expect("real signature must verify");

    // Tamper: flip first byte of signature
    let mut tampered_bytes = sig_bytes.clone();
    tampered_bytes[0] ^= 0x01;
    let tampered_sig = Signature::from_slice(&tampered_bytes).unwrap();
    assert!(
        pubkey.verify(&unsigned_bytes, &tampered_sig).is_err(),
        "tampered signature must fail"
    );
}

#[test]
fn absent_vs_empty_disposition_status_distinct() {
    let fx = fixture::load_fixture("resubmission-transition-v1");

    let state_before = fixture::state_before(&fx);
    let base_root = transition::state_root(state_before);

    // The genesis empty state does NOT contain :chain/disposition-status-by-receipt
    // Adding an explicit empty map {} IS included in the projection
    let empty_state = match state_before {
        Value::Map(pairs) => {
            let mut new_pairs = pairs.clone();
            new_pairs.push((
                Value::Keyword(
                    Some("chain".to_string()),
                    "disposition-status-by-receipt".to_string(),
                ),
                Value::Map(vec![]),
            ));
            Value::Map(new_pairs)
        }
        _ => Value::Nil,
    };
    let empty_root = transition::state_root(&empty_state);

    assert_ne!(
        base_root, empty_root,
        "absent vs present-but-empty must differ"
    );
    assert_eq!(base_root, fixture::pinned_hex(&fx, "state-before-root"));
}
