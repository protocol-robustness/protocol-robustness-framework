use crate::edn::{map_get, Value};

pub fn kw(ns: &str, name: &str) -> Value {
    Value::Keyword(Some(ns.to_string()), name.to_string())
}

pub fn kw_unq(name: &str) -> Value {
    Value::Keyword(None, name.to_string())
}

pub fn chain_state_projection(state: &Value) -> Value {
    let family_id = map_get(state, "chain/family-id")
        .cloned()
        .unwrap_or(Value::Nil);
    let version = map_get(state, "chain/version")
        .cloned()
        .unwrap_or(Value::Nil);
    let commit_index = map_get(state, "transaction/commit-index")
        .cloned()
        .unwrap_or(Value::Nil);
    let head = map_get(state, "chain/head").cloned().unwrap_or(Value::Nil);
    let successor_by_parent = map_get(state, "chain/successor-by-parent")
        .cloned()
        .unwrap_or(Value::Map(vec![]));
    let eff_disp = map_get(state, "chain/effective-disposition-by-receipt")
        .cloned()
        .unwrap_or(Value::Map(vec![]));
    let disp_head = map_get(state, "chain/disposition-head-by-receipt")
        .cloned()
        .unwrap_or(Value::Map(vec![]));
    let idem = map_get(state, "chain/idempotency-index")
        .cloned()
        .unwrap_or(Value::Map(vec![]));
    let content = map_get(state, "chain/content-index")
        .cloned()
        .unwrap_or(Value::Map(vec![]));

    let mut pairs = vec![
        (kw("chain", "family-id"), family_id),
        (kw("chain", "version"), version),
        (kw("transaction", "commit-index"), commit_index),
        (kw("chain", "head"), head),
        (kw("chain", "successor-by-parent"), successor_by_parent),
        (kw("chain", "effective-disposition-by-receipt"), eff_disp),
        (kw("chain", "disposition-head-by-receipt"), disp_head),
        (kw("chain", "idempotency-index"), idem),
        (kw("chain", "content-index"), content),
    ];

    if let Some(dsb) = map_get(state, "chain/disposition-status-by-receipt") {
        pairs.push((kw("chain", "disposition-status-by-receipt"), dsb.clone()));
    }

    Value::Map(pairs)
}

pub fn state_root(state: &Value) -> String {
    let proj = chain_state_projection(state);
    crate::canonical::domain_hash("prf.resubmission-chain-state.v1", &proj)
}

pub fn effects_root(effects: &Value) -> String {
    let eff_vec = match effects {
        Value::Vec(v) => Value::Vec(v.clone()),
        _ => Value::Vec(vec![]),
    };
    crate::canonical::domain_hash("prf.transaction-effects.v1", &eff_vec)
}

pub fn admit_input_root(input: &Value) -> String {
    let basis = Value::Map(vec![
        (
            kw_unq("parent-receipt-hash"),
            map_get(input, "parent-receipt-hash")
                .cloned()
                .unwrap_or(Value::Nil),
        ),
        (
            kw_unq("candidate-attempt-receipt-id"),
            map_get(input, "candidate-attempt-receipt-id")
                .cloned()
                .unwrap_or(Value::Nil),
        ),
        (
            kw_unq("idempotency-key"),
            map_get(input, "idempotency-key")
                .cloned()
                .unwrap_or(Value::Nil),
        ),
        (
            kw_unq("content-key"),
            map_get(input, "content-key").cloned().unwrap_or(Value::Nil),
        ),
    ]);
    crate::canonical::domain_hash("prf.transaction-input.v1", &basis)
}

pub fn disposition_input_root(input: &Value) -> String {
    // For apply-disposition input, we need the disposition-artifact-hash
    // which must be computed first from the artifact in the input
    let artifact = map_get(input, "disposition-artifact");
    let artifact_hash = match artifact {
        Some(a) => disposition_hash(a),
        None => String::new(),
    };
    let basis = Value::Map(vec![
        (
            kw_unq("attempt-receipt-hash"),
            map_get(input, "attempt-receipt-hash")
                .cloned()
                .unwrap_or(Value::Nil),
        ),
        (
            kw_unq("disposition-artifact-hash"),
            Value::Str(artifact_hash),
        ),
    ]);
    crate::canonical::domain_hash("prf.transaction-input.v1", &basis)
}

pub fn command_input_root(action: &Value, input: &Value) -> String {
    let action_str = match action {
        Value::Keyword(ns, name) => match ns {
            Some(ns) => format!("{}/{}", ns, name),
            None => name.clone(),
        },
        Value::Str(s) => s.clone(),
        _ => String::new(),
    };

    match action_str.as_str() {
        "prf.resubmission/admit-child" => admit_input_root(input),
        "prf.resubmission/apply-disposition" => disposition_input_root(input),
        _ => panic!("unsupported action: {}", action_str),
    }
}

pub fn disposition_hash(disposition: &Value) -> String {
    let unsigned = match disposition {
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
    };
    crate::canonical::domain_hash("prf.attempt-disposition.v1", &unsigned)
}

pub fn extract_str(v: &Value) -> Option<String> {
    match v {
        Value::Str(s) => Some(s.clone()),
        Value::Nil => None,
        _ => None,
    }
}

pub fn extract_int(v: &Value) -> i64 {
    match v {
        Value::Int(i) => *i,
        _ => 0,
    }
}

pub fn admit_child(state: &Value, input: &Value) -> Value {
    let parent_hash = map_get(input, "parent-receipt-hash").and_then(extract_str);
    let candidate_id = map_get(input, "candidate-attempt-receipt-id")
        .and_then(extract_str)
        .unwrap_or_default();
    let idem_key = map_get(input, "idempotency-key").and_then(extract_str);
    let content_key = map_get(input, "content-key").and_then(extract_str);
    let sequence = map_get(input, "sequence").map(extract_int).unwrap_or(0);

    let idem_index = map_get(state, "chain/idempotency-index");
    let idem = match (&idem_key, idem_index) {
        (Some(k), Some(Value::Map(p))) => map_get(&Value::Map(p.clone()), k).cloned(),
        _ => None,
    };

    let content_index = map_get(state, "chain/content-index");
    let content = match (&content_key, content_index) {
        (Some(k), Some(Value::Map(p))) => map_get(&Value::Map(p.clone()), k).cloned(),
        _ => None,
    };

    // Precedence 1: idempotent replay vs content mismatch
    if let (Some(_idem_key), Some(idem)) = (idem_key, idem) {
        let existing_ck = map_get(&idem, "content-key")
            .and_then(extract_str)
            .unwrap_or_default();
        let existing_rh = map_get(&idem, "receipt-hash")
            .cloned()
            .unwrap_or(Value::Nil);

        if existing_ck == content_key.clone().unwrap_or_default() {
            return Value::Map(vec![
                (
                    kw_unq("status"),
                    Value::Keyword(None, "idempotent-replay".to_string()),
                ),
                (
                    kw_unq("reason"),
                    Value::Keyword(None, "submission-already-observed".to_string()),
                ),
                (
                    kw_unq("public-result"),
                    Value::Map(vec![(kw_unq("existing"), existing_rh)]),
                ),
            ]);
        } else {
            return Value::Map(vec![
                (
                    kw_unq("status"),
                    Value::Keyword(None, "rejected".to_string()),
                ),
                (
                    kw_unq("reason"),
                    Value::Keyword(None, "idempotency-content-mismatch".to_string()),
                ),
                (
                    kw_unq("public-result"),
                    Value::Map(vec![(kw_unq("existing"), existing_rh)]),
                ),
            ]);
        }
    }

    if let Some(c) = content {
        let content_parent = map_get(&c, "parent-receipt-hash").and_then(extract_str);
        let content_hash = map_get(&c, "receipt-hash").cloned().unwrap_or(Value::Nil);

        if content_parent == parent_hash {
            return reject(
                "duplicate-content-submission",
                Value::Map(vec![(kw_unq("existing"), content_hash)]),
            );
        } else {
            return reject(
                "idempotency-key-rebound",
                Value::Map(vec![(
                    kw_unq("prior-parent"),
                    map_get(&c, "parent-receipt-hash")
                        .cloned()
                        .unwrap_or(Value::Nil),
                )]),
            );
        }
    }

    // Precedence 3: parent is nil — genesis
    if parent_hash.is_none() {
        let head = map_get(state, "chain/head");
        if head.is_none() || matches!(head, Some(Value::Nil)) {
            if sequence == 1 {
                return commit_admit(state, input, &candidate_id, None);
            } else {
                return reject("initial-sequence-mismatch", Value::Nil);
            }
        }
    }

    // Parent not found
    if let Some(ref ph) = parent_hash {
        let receipts = map_get(state, "chain/attempt-receipts").and_then(|r| map_get(r, ph));
        if receipts.is_none() {
            return reject("previous-not-found", Value::Nil);
        }
    }

    // Check head
    if let Some(ref ph) = parent_hash {
        let head = map_get(state, "chain/head").and_then(extract_str);
        if head.as_deref() != Some(ph.as_str()) {
            return reject("parent-not-current-head", Value::Nil);
        }
    }

    // For fixtures, if we get here with a valid parent, commit the admit
    if let Some(ref ph) = parent_hash {
        commit_admit(state, input, &candidate_id, Some(ph.as_str()))
    } else {
        reject("initial-sequence-mismatch", Value::Nil)
    }
}

fn reject(reason: &str, public_result: Value) -> Value {
    Value::Map(vec![
        (
            kw_unq("status"),
            Value::Keyword(None, "rejected".to_string()),
        ),
        (kw_unq("reason"), Value::Keyword(None, reason.to_string())),
        (kw_unq("public-result"), public_result),
    ])
}

fn commit_admit(
    state: &Value,
    input: &Value,
    child_hash: &str,
    parent_hash: Option<&str>,
) -> Value {
    let family_id = map_get(state, "chain/family-id")
        .and_then(extract_str)
        .unwrap_or_default();
    let version = map_get(state, "chain/version")
        .map(extract_int)
        .unwrap_or(0);
    let commit_index = map_get(state, "transaction/commit-index")
        .map(extract_int)
        .unwrap_or(0);

    let idem_key = map_get(input, "idempotency-key").and_then(extract_str);
    let content_key = map_get(input, "content-key").and_then(extract_str);
    let candidate = map_get(input, "candidate-attempt-receipt").cloned();
    let seq_val = map_get(input, "sequence").cloned().unwrap_or(Value::Int(0));

    // successor-by-parent
    let mut succ_pairs = match map_get(state, "chain/successor-by-parent") {
        Some(Value::Map(p)) => p.clone(),
        _ => vec![],
    };
    let parent_key = parent_hash
        .map(|s| Value::Str(s.to_string()))
        .unwrap_or(Value::Nil);
    succ_pairs.push((parent_key.clone(), Value::Str(child_hash.to_string())));

    // attempt-receipts
    let mut receipts = match map_get(state, "chain/attempt-receipts") {
        Some(Value::Map(p)) => p.clone(),
        _ => vec![],
    };
    receipts.push((
        Value::Str(child_hash.to_string()),
        Value::Map(vec![
            (kw_unq("attempt-receipt"), candidate.unwrap_or(Value::Nil)),
            (kw_unq("sequence"), seq_val.clone()),
            (
                kw_unq("parent-receipt-hash"),
                match parent_hash {
                    Some(ph) => Value::Str(ph.to_string()),
                    None => Value::Nil,
                },
            ),
        ]),
    ));

    // idempotency-index
    let mut idem_pairs = match map_get(state, "chain/idempotency-index") {
        Some(Value::Map(p)) => p.clone(),
        _ => vec![],
    };
    if let Some(ref ikey) = idem_key {
        idem_pairs.push((
            Value::Str(ikey.clone()),
            Value::Map(vec![
                (
                    kw_unq("content-key"),
                    Value::Str(content_key.clone().unwrap_or_default()),
                ),
                (kw_unq("receipt-hash"), Value::Str(child_hash.to_string())),
            ]),
        ));
    }

    // content-index
    let mut content_pairs = match map_get(state, "chain/content-index") {
        Some(Value::Map(p)) => p.clone(),
        _ => vec![],
    };
    if let Some(ref ckey) = content_key {
        content_pairs.push((
            Value::Str(ckey.clone()),
            Value::Map(vec![
                (
                    kw_unq("parent-receipt-hash"),
                    match parent_hash {
                        Some(ph) => Value::Str(ph.to_string()),
                        None => Value::Nil,
                    },
                ),
                (kw_unq("receipt-hash"), Value::Str(child_hash.to_string())),
            ]),
        ));
    }

    let new_state = Value::Map(vec![
        (kw("transaction", "last-hash"), Value::Nil),
        (kw("chain", "family-id"), Value::Str(family_id.clone())),
        (
            kw("chain", "disposition-public-hex"),
            map_get(state, "chain/disposition-public-hex")
                .cloned()
                .unwrap_or(Value::Nil),
        ),
        (kw("chain", "version"), Value::Int(version + 1)),
        (
            kw("transaction", "commit-index"),
            Value::Int(commit_index + 1),
        ),
        (kw("chain", "head"), Value::Str(child_hash.to_string())),
        (kw("chain", "successor-by-parent"), Value::Map(succ_pairs)),
        (kw("chain", "attempt-receipts"), Value::Map(receipts)),
        (kw("chain", "idempotency-index"), Value::Map(idem_pairs)),
        (kw("chain", "content-index"), Value::Map(content_pairs)),
        (
            kw("chain", "effective-disposition-by-receipt"),
            map_get(state, "chain/effective-disposition-by-receipt")
                .cloned()
                .unwrap_or(Value::Map(vec![])),
        ),
        (
            kw("chain", "disposition-head-by-receipt"),
            map_get(state, "chain/disposition-head-by-receipt")
                .cloned()
                .unwrap_or(Value::Map(vec![])),
        ),
    ]);

    // Also carry disposition-status-by-receipt if present
    let mut state_pairs = match &new_state {
        Value::Map(p) => p.clone(),
        _ => vec![],
    };
    if let Some(dsb) = map_get(state, "chain/disposition-status-by-receipt") {
        state_pairs.push((kw("chain", "disposition-status-by-receipt"), dsb.clone()));
    }

    let new_state = Value::Map(state_pairs);

    let effects = Value::Vec(vec![
        Value::Map(vec![
            (
                kw("effect", "type"),
                Value::Keyword(None, "chain-successor".to_string()),
            ),
            (
                kw_unq("parent"),
                match parent_hash {
                    Some(ph) => Value::Str(ph.to_string()),
                    None => Value::Nil,
                },
            ),
            (kw_unq("child"), Value::Str(child_hash.to_string())),
        ]),
        Value::Map(vec![
            (
                kw("effect", "type"),
                Value::Keyword(None, "chain-head".to_string()),
            ),
            (kw_unq("family-id"), Value::Str(family_id.clone())),
            (kw_unq("head"), Value::Str(child_hash.to_string())),
        ]),
        Value::Map(vec![
            (
                kw("effect", "type"),
                Value::Keyword(None, "chain-version".to_string()),
            ),
            (kw_unq("family-id"), Value::Str(family_id.clone())),
            (kw_unq("version"), Value::Int(version + 1)),
        ]),
        Value::Map(vec![
            (
                kw("effect", "type"),
                Value::Keyword(None, "idempotency-index".to_string()),
            ),
            (
                kw_unq("key"),
                map_get(input, "idempotency-key")
                    .cloned()
                    .unwrap_or(Value::Nil),
            ),
            (kw_unq("child"), Value::Str(child_hash.to_string())),
        ]),
        Value::Map(vec![
            (
                kw("effect", "type"),
                Value::Keyword(None, "content-index".to_string()),
            ),
            (
                kw_unq("key"),
                map_get(input, "content-key").cloned().unwrap_or(Value::Nil),
            ),
            (kw_unq("child"), Value::Str(child_hash.to_string())),
        ]),
        Value::Map(vec![
            (
                kw("effect", "type"),
                Value::Keyword(None, "attempt-receipt".to_string()),
            ),
            (kw_unq("receipt-id"), Value::Str(child_hash.to_string())),
        ]),
    ]);

    let input_root = command_input_root(
        &Value::Keyword(
            Some("prf.resubmission".to_string()),
            "admit-child".to_string(),
        ),
        input,
    );

    let ordering_input = Value::Map(vec![
        (
            kw("transaction", "action"),
            Value::Keyword(
                Some("prf.resubmission".to_string()),
                "admit-child".to_string(),
            ),
        ),
        (
            kw("transaction", "scope"),
            Value::Keyword(None, "resubmission-family".to_string()),
        ),
        (
            kw("transaction", "conflict-key"),
            Value::Vec(vec![
                Value::Keyword(None, "resubmission-family".to_string()),
                Value::Str(family_id.clone()),
            ]),
        ),
        (
            kw("transaction", "expected"),
            Value::Map(vec![
                (
                    kw_unq("chain-head"),
                    match parent_hash {
                        Some(ph) => Value::Str(ph.to_string()),
                        None => Value::Nil,
                    },
                ),
                (kw_unq("chain-version"), Value::Int(version)),
            ]),
        ),
        (
            kw("transaction", "observed"),
            Value::Map(vec![
                (
                    kw_unq("chain-head"),
                    match parent_hash {
                        Some(ph) => Value::Str(ph.to_string()),
                        None => Value::Nil,
                    },
                ),
                (kw_unq("chain-version"), Value::Int(version)),
            ]),
        ),
        (
            kw("transaction-ordering", "schema"),
            Value::Str("transaction-ordering.v2".to_string()),
        ),
        (kw("transaction", "input-root"), Value::Str(input_root)),
    ]);

    let public_result = Value::Map(vec![
        (kw_unq("chain-head"), Value::Str(child_hash.to_string())),
        (kw_unq("sequence"), seq_val),
        (
            kw_unq("admission-status"),
            Value::Keyword(None, "admitted".to_string()),
        ),
    ]);

    Value::Map(vec![
        (
            kw_unq("status"),
            Value::Keyword(None, "committed".to_string()),
        ),
        (kw_unq("state"), new_state),
        (kw_unq("public-result"), public_result),
        (kw_unq("effects"), effects),
        (kw_unq("ordering-input"), ordering_input),
    ])
}

#[allow(dead_code)]
pub fn apply_disposition(state: &Value, input: &Value) -> Value {
    let attempt_hash = map_get(input, "attempt-receipt-hash").and_then(extract_str);
    let artifact = map_get(input, "disposition-artifact");

    let receipts = map_get(state, "chain/attempt-receipts");
    let attempt_exists = attempt_hash
        .as_ref()
        .and_then(|h| receipts.and_then(|r| map_get(r, h)))
        .is_some();

    if !attempt_exists {
        return reject("attempt-not-found", Value::Nil);
    }

    let disp_pub = map_get(state, "chain/disposition-public-hex");
    if disp_pub.is_none() || matches!(disp_pub, Some(Value::Nil)) {
        return reject("disposition-authority-not-configured", Value::Nil);
    }

    let artifact = artifact.expect("disposition-artifact is required");

    let pub_hex = disp_pub.and_then(extract_str).unwrap_or_default();
    let verification = verify_disposition_internal(artifact, &pub_hex);
    if !verification.0 {
        return reject(&verification.1, Value::Nil);
    }

    let declared_attempt = map_get(artifact, "attempt-receipt-hash").and_then(extract_str);
    if declared_attempt != attempt_hash {
        return reject("disposition-receipt-mismatch", Value::Nil);
    }

    let disp_head_by_receipt = map_get(state, "chain/disposition-head-by-receipt");
    let empty_map = Value::Map(vec![]);
    let cur_head = attempt_hash
        .as_ref()
        .and_then(|h| map_get(disp_head_by_receipt.unwrap_or(&empty_map), h))
        .cloned();
    let declared_prev = map_get(artifact, "attempt-disposition/previous-disposition-hash").cloned();

    let cur_head_str = match &cur_head {
        Some(Value::Str(s)) => Some(s.as_str()),
        _ => None,
    };
    let prev_str = match &declared_prev {
        Some(v) => match v {
            Value::Str(s) => Some(s.as_str()),
            Value::Nil => None,
            _ => None,
        },
        None => None,
    };

    if cur_head_str != prev_str {
        return reject("disposition-previous-hash-mismatch", Value::Nil);
    }

    let status_val = map_get(artifact, "attempt-disposition/status")
        .and_then(|v| match v {
            Value::Keyword(_, n) => Some(n.clone()),
            _ => None,
        })
        .unwrap_or_default();

    let current_status = {
        let dsb = map_get(state, "chain/disposition-status-by-receipt");
        attempt_hash
            .as_ref()
            .and_then(|h| dsb.and_then(|d| map_get(d, h)))
            .and_then(|v| match v {
                Value::Keyword(_, n) => Some(n.clone()),
                _ => None,
            })
            .unwrap_or_else(|| "active".to_string())
    };

    if !is_allowed_transition(&current_status, &status_val) {
        return reject(
            "invalid-disposition-transition",
            Value::Map(vec![
                (kw_unq("from"), Value::Keyword(None, current_status)),
                (kw_unq("to"), Value::Keyword(None, status_val.clone())),
            ]),
        );
    }

    let _family_id = map_get(state, "chain/family-id")
        .and_then(extract_str)
        .unwrap_or_default();
    let version = map_get(state, "chain/version")
        .map(extract_int)
        .unwrap_or(0);
    let _commit_index = map_get(state, "transaction/commit-index")
        .map(extract_int)
        .unwrap_or(0);

    let expected_chain_version = match map_get(input, "expected-chain-version") {
        Some(Value::Int(i)) => Some(*i),
        _ => None,
    };
    if let Some(ecv) = expected_chain_version {
        if ecv != version {
            return reject("commit-contention", Value::Nil);
        }
    }

    if let Some(Value::Str(expected_head)) = map_get(input, "expected-disposition-head") {
        if Some(expected_head.as_str()) != cur_head_str {
            return reject("disposition-head-mismatch", Value::Nil);
        }
    }

    let artifact_hash = disposition_hash(artifact);
    let artifact_hash_clone = artifact_hash.clone();

    let family_id = map_get(state, "chain/family-id")
        .and_then(extract_str)
        .unwrap_or_default();
    let version = map_get(state, "chain/version")
        .map(extract_int)
        .unwrap_or(0);
    let commit_index = map_get(state, "transaction/commit-index")
        .map(extract_int)
        .unwrap_or(0);

    // Clone existing state pairs and modify
    let mut new_pairs: Vec<(Value, Value)> = match state {
        Value::Map(p) => p.clone(),
        _ => vec![],
    };

    // Update version and commit-index
    new_pairs.retain(|(k, _)| {
        !matches!(k, Value::Keyword(Some(ns), n) if ns == "chain" && n == "version")
            && !matches!(k, Value::Keyword(Some(ns), n) if ns == "transaction" && n == "commit-index")
    });
    new_pairs.push((kw("chain", "version"), Value::Int(version + 1)));
    new_pairs.push((
        kw("transaction", "commit-index"),
        Value::Int(commit_index + 1),
    ));

    // Update effective-disposition-by-receipt
    let lifecycle = disposition_to_lifecycle(&status_val);
    let mut eff_pairs = match map_get(state, "chain/effective-disposition-by-receipt") {
        Some(Value::Map(p)) => p.clone(),
        _ => vec![],
    };
    let ah = attempt_hash.clone().unwrap_or_default();
    let ah_ref = &ah;
    eff_pairs.retain(|(k, _)| !matches!(k, Value::Str(s) if s == ah_ref));
    eff_pairs.push((Value::Str(ah.clone()), Value::Keyword(None, lifecycle)));
    new_pairs.retain(|(k, _)| {
        !matches!(k, Value::Keyword(Some(ns), n) if ns == "chain" && n == "effective-disposition-by-receipt")
    });
    new_pairs.push((
        kw("chain", "effective-disposition-by-receipt"),
        Value::Map(eff_pairs),
    ));

    // Update disposition-status-by-receipt
    let mut dsb_pairs = match map_get(state, "chain/disposition-status-by-receipt") {
        Some(Value::Map(p)) => p.clone(),
        _ => vec![],
    };
    let ah_clone = ah.clone();
    dsb_pairs.retain(|(k, _)| !matches!(k, Value::Str(s) if s == &ah_clone));
    dsb_pairs.push((
        Value::Str(ah.clone()),
        Value::Keyword(None, status_val.clone()),
    ));
    new_pairs.retain(|(k, _)| {
        !matches!(k, Value::Keyword(Some(ns), n) if ns == "chain" && n == "disposition-status-by-receipt")
    });
    new_pairs.push((
        kw("chain", "disposition-status-by-receipt"),
        Value::Map(dsb_pairs),
    ));

    // Update disposition-head-by-receipt
    let mut dhb_pairs = match map_get(state, "chain/disposition-head-by-receipt") {
        Some(Value::Map(p)) => p.clone(),
        _ => vec![],
    };
    dhb_pairs.retain(|(k, _)| !matches!(k, Value::Str(s) if s == ah_ref));
    dhb_pairs.push((
        Value::Str(ah.clone()),
        Value::Str(artifact_hash_clone.clone()),
    ));
    new_pairs.retain(|(k, _)| {
        !matches!(k, Value::Keyword(Some(ns), n) if ns == "chain" && n == "disposition-head-by-receipt")
    });
    new_pairs.push((
        kw("chain", "disposition-head-by-receipt"),
        Value::Map(dhb_pairs),
    ));

    let new_state = Value::Map(new_pairs);

    let effects = Value::Vec(vec![Value::Map(vec![
        (
            kw("effect", "type"),
            Value::Keyword(None, "disposition".to_string()),
        ),
        (
            kw_unq("attempt-receipt-hash"),
            Value::Str(attempt_hash.clone().unwrap_or_default()),
        ),
        (kw_unq("status"), Value::Keyword(None, status_val.clone())),
        (
            kw_unq("disposition-artifact-hash"),
            Value::Str(artifact_hash),
        ),
    ])]);

    let input_root = command_input_root(
        &Value::Keyword(
            Some("prf.resubmission".to_string()),
            "apply-disposition".to_string(),
        ),
        input,
    );

    let observed_disp_head = match &cur_head {
        Some(Value::Str(s)) => Value::Str(s.clone()),
        _ => Value::Nil,
    };
    let expected_disp_head = map_get(input, "expected-disposition-head")
        .cloned()
        .unwrap_or(Value::Nil);

    let ordering_input = Value::Map(vec![
        (
            kw("transaction", "action"),
            Value::Keyword(
                Some("prf.resubmission".to_string()),
                "apply-disposition".to_string(),
            ),
        ),
        (
            kw("transaction", "scope"),
            Value::Keyword(None, "resubmission-family".to_string()),
        ),
        (
            kw("transaction", "conflict-key"),
            Value::Vec(vec![
                Value::Keyword(None, "resubmission-family".to_string()),
                Value::Str(family_id.clone()),
            ]),
        ),
        (
            kw("transaction-ordering", "schema"),
            Value::Str("transaction-ordering.v2".to_string()),
        ),
        (kw("transaction", "input-root"), Value::Str(input_root)),
        (
            kw("transaction", "expected"),
            Value::Map(vec![
                (kw_unq("disposition-head"), expected_disp_head),
                (kw_unq("chain-version"), Value::Int(version)),
            ]),
        ),
        (
            kw("transaction", "observed"),
            Value::Map(vec![
                (kw_unq("disposition-head"), observed_disp_head),
                (kw_unq("chain-version"), Value::Int(version)),
            ]),
        ),
    ]);

    let public_result = Value::Map(vec![
        (
            kw_unq("attempt-receipt-hash"),
            Value::Str(attempt_hash.clone().unwrap_or_default()),
        ),
        (
            kw_unq("disposition-status"),
            Value::Keyword(None, status_val),
        ),
    ]);

    Value::Map(vec![
        (
            kw_unq("status"),
            Value::Keyword(None, "committed".to_string()),
        ),
        (kw_unq("state"), new_state),
        (kw_unq("public-result"), public_result),
        (kw_unq("effects"), effects),
        (kw_unq("ordering-input"), ordering_input),
    ])
}

fn is_allowed_transition(from: &str, to: &str) -> bool {
    matches!(
        (from, to),
        ("active", "pending-review")
            | ("active", "final")
            | ("pending-review", "final")
            | ("final", "withdrawn")
            | ("final", "revoked")
            | ("final", "superseded")
            | ("superseded", "final")
    )
}

fn disposition_to_lifecycle(status: &str) -> String {
    match status {
        "pending-review" | "final" => "active".to_string(),
        "withdrawn" => "withdrawn".to_string(),
        "revoked" => "revoked".to_string(),
        "superseded" => "superseded".to_string(),
        _ => "active".to_string(),
    }
}

fn verify_disposition_internal(artifact: &Value, pub_hex: &str) -> (bool, String) {
    // Build unsigned projection
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
        _ => return (false, "not-a-map".to_string()),
    };

    let unsigned_bytes = crate::canonical::canonical_bytes(&unsigned);

    // Check schema
    match map_get(&unsigned, "attempt-disposition/schema") {
        Some(Value::Str(s)) if s == "attempt-disposition.v1" => {}
        _ => return (false, "invalid-disposition-schema".to_string()),
    }

    // Check status
    let status = map_get(&unsigned, "attempt-disposition/status");
    match status {
        Some(Value::Keyword(_, n))
            if matches!(
                n.as_str(),
                "pending-review" | "final" | "withdrawn" | "revoked" | "superseded"
            ) => {}
        _ => return (false, "invalid-disposition-status".to_string()),
    }

    // Check signature exists
    let sig =
        map_get(artifact, "attempt-disposition/signature").and_then(|v| map_get(v, "signature"));
    let sig_hex = match sig {
        Some(Value::Str(s)) => s.clone(),
        _ => return (false, "missing-disposition-signature".to_string()),
    };

    let sig_bytes = hex_to_bytes(&sig_hex);
    if sig_bytes.len() != 64 {
        return (false, "bad-signature-length".to_string());
    }

    let pub_bytes = hex_to_bytes(pub_hex);
    if pub_bytes.len() != 32 {
        return (false, "bad-pubkey-length".to_string());
    }

    use ed25519_dalek::{Signature, Verifier, VerifyingKey};

    let pubkey = match VerifyingKey::from_bytes(&pub_bytes.try_into().unwrap()) {
        Ok(k) => k,
        Err(_) => return (false, "bad-pubkey".to_string()),
    };

    let signature = match Signature::from_slice(&sig_bytes) {
        Ok(s) => s,
        Err(_) => return (false, "bad-signature-format".to_string()),
    };

    match pubkey.verify(&unsigned_bytes, &signature) {
        Ok(_) => (true, "ok".to_string()),
        Err(_) => (false, "invalid-disposition-signature".to_string()),
    }
}

fn hex_to_bytes(hex: &str) -> Vec<u8> {
    let hex = hex.strip_prefix("sha256:").unwrap_or(hex);
    (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
        .collect()
}
