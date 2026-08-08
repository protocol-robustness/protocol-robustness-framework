//! Rust mirror of the probabilistic-allocation lifecycle profile
//! (cancellation-window.v1 instantiation) and its single-state classifier.
//!
//! This is a byte-for-byte and semantic-by-semantic port of the PRF fixture:
//! `resolver-sim.assurance.canonical-force-authorisation` →
//! `probabilistic-allocation-window` and `classify-lifecycle-window`. It owns
//! only the window mechanics for the allocation lifecycle and never embeds
//! 2-of-3 decision authority. The authoritative randomness-request cutpoint is
//! `:randomness-requested` (the first closed state); every later state is
//! closed; unknown or missing tokens fail closed to :invalid.

use std::collections::BTreeSet;

/// The generic `cancellation-window.v1` schema version (matches PRF).
pub const CANCELLATION_WINDOW_V1_SCHEMA: &str = "cancellation-window.v1";

/// The probabilistic-allocation lifecycle profile identifier (matches PRF).
pub const PROFILE_ID: &str = "prf.lifecycle-window/probabilistic-allocation";

/// The probabilistic-allocation lifecycle profile version.
pub const PROFILE_VERSION: u32 = 1;

/// The set of states that are still cancellable (open, pre-cutpoint).
pub fn open_states() -> BTreeSet<&'static str> {
    BTreeSet::from(["allocation-committed"])
}

/// The coprocessor round-state tokens recognised by the mapper, in round
/// order (mirrors PRF `resolver-sim.allocation.round-state`).
pub fn coprocessor_round_states() -> Vec<&'static str> {
    vec![
        "allocation-committed",
        "randomness-requested",
        "randomness-fulfilled",
        "result-proposed",
        "result-accepted",
        "claim-consumption-started",
    ]
}

/// The set of states that are irreversible (the cutpoint plus everything
/// after it).
pub fn irreversible_states() -> BTreeSet<&'static str> {
    BTreeSet::from([
        "randomness-requested",
        "randomness-fulfilled",
        "result-proposed",
        "result-accepted",
        "claim-consumption-started",
    ])
}

/// The blocking reason for each irreversible state (mirrors the PRF
/// `:blocking-reason-by-state` map). The cutpoint reason is
/// `:authoritative-randomness-requested`.
pub fn blocking_reason_by_state() -> BTreeSet<(&'static str, &'static str)> {
    BTreeSet::from([
        ("randomness-requested", "authoritative-randomness-requested"),
        ("randomness-fulfilled", "randomness-fulfilled"),
        ("result-proposed", "result-proposed"),
        ("result-accepted", "result-accepted"),
        ("claim-consumption-started", "claim-consumption-started"),
    ])
}

/// The recognised lifecycle state vocabulary (open ∪ irreversible).
pub fn valid_states() -> BTreeSet<&'static str> {
    let mut s = open_states();
    s.extend(irreversible_states());
    s
}

/// Result of classifying a single target state against the window.
#[derive(Clone, Debug, PartialEq)]
pub struct LifecycleWindow {
    pub schema: &'static str,
    pub state: &'static str, // "open" | "closed" | "invalid"
    pub possible: bool,
    pub blocking_reasons: Vec<String>,
}

/// Build a blocking-reason keyword string from a state keyword's canonical name.
fn reason_for(state: &str) -> String {
    let map = blocking_reason_by_state();
    map.iter()
        .find(|(k, _)| *k == state)
        .map(|(_, v)| (*v).to_string())
        .unwrap_or_else(|| state.to_string())
}

/// Classify a coprocessor round target state against the probabilistic
/// allocation window. Fails closed: nil/unknown states are :invalid and never
/// possible.
pub fn classify_lifecycle_window(state: Option<&str>) -> LifecycleWindow {
    match state {
        None => LifecycleWindow {
            schema: CANCELLATION_WINDOW_V1_SCHEMA,
            state: "invalid",
            possible: false,
            blocking_reasons: vec!["unknown-target-state".to_string()],
        },
        Some(st) if irreversible_states().contains(st) => LifecycleWindow {
            schema: CANCELLATION_WINDOW_V1_SCHEMA,
            state: "closed",
            possible: false,
            blocking_reasons: vec![reason_for(st)],
        },
        Some(st) if open_states().contains(st) => LifecycleWindow {
            schema: CANCELLATION_WINDOW_V1_SCHEMA,
            state: "open",
            possible: true,
            blocking_reasons: Vec::new(),
        },
        Some(_) => LifecycleWindow {
            schema: CANCELLATION_WINDOW_V1_SCHEMA,
            state: "invalid",
            possible: false,
            blocking_reasons: vec!["unknown-target-state".to_string()],
        },
    }
}

/// The public `round-lifecycle` projection emitted by the kernel (mirrors PRF
/// `round-state/round-lifecycle`). Every field is committed into
/// CERTIFICATE_ASSERTIONS_V2.
#[derive(Clone, Debug, PartialEq)]
pub struct RoundLifecycle {
    pub round_state: Option<String>,
    pub derived_state: Option<String>,
    pub lifecycle_profile_id: String,
    pub lifecycle_profile_version: u32,
    pub cancellation_window_schema: String,
    pub cancellation_window: String, // "open" | "closed" | "invalid"
    pub cancellation_possible: bool,
    pub cancellation_blocking_reasons: Vec<String>,
    pub lifecycle_assertion_status: String, // "passing" | "failing"
    pub evidence_status: String,
    pub assurance: String,
}

/// Map a coprocessor round-state token to its canonical lifecycle target-state
/// string, or None when unrecognised (mirrors `lifecycle-target-state`).
pub fn lifecycle_target_state(token: Option<&str>) -> Option<String> {
    match token {
        None => None,
        Some(t) if coprocessor_round_states().contains(&t) => Some(t.to_string()),
        Some(_) => None,
    }
}

/// Derive the stable public `round-lifecycle` projection from the observed
/// `round-state` input field. Fails closed: absent/null, unrecognised, and
/// malformed tokens all classify :invalid with distinct blocking reasons
/// (missing-target-state, unknown-target-state, malformed-round-state), mirroring
/// PRF `round-state/round-lifecycle`.
pub fn round_lifecycle(raw: Option<&serde_json::Value>) -> RoundLifecycle {
    let missing = match raw {
        None => true,
        Some(v) if v.is_null() => true,
        _ => false,
    };
    let malformed = matches!(raw, Some(v) if !v.is_null() && !v.is_string());
    let token_str = raw.and_then(|v| v.as_str());
    let target = lifecycle_target_state(token_str);
    let window = classify_lifecycle_window(target.as_deref());

    let reasons = if malformed {
        vec!["malformed-round-state".to_string()]
    } else if missing {
        vec!["missing-target-state".to_string()]
    } else if target.is_none() {
        vec!["unknown-target-state".to_string()]
    } else {
        window.blocking_reasons
    };
    let window_state = if malformed || missing || target.is_none() {
        "invalid"
    } else {
        window.state
    };

    RoundLifecycle {
        round_state: token_str.map(|s| s.to_string()),
        derived_state: target,
        lifecycle_profile_id: PROFILE_ID.to_string(),
        lifecycle_profile_version: PROFILE_VERSION,
        cancellation_window_schema: CANCELLATION_WINDOW_V1_SCHEMA.to_string(),
        cancellation_window: window_state.to_string(),
        cancellation_possible: window_state == "open",
        cancellation_blocking_reasons: reasons,
        lifecycle_assertion_status: if window_state == "invalid" {
            "failing".to_string()
        } else {
            "passing".to_string()
        },
        evidence_status: "evidence/derived-state".to_string(),
        assurance: "independent-replay".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn schema_and_profile_identify_match_prf() {
        assert_eq!(CANCELLATION_WINDOW_V1_SCHEMA, "cancellation-window.v1");
    }

    #[test]
    fn cutpoint_randomness_closes() {
        let w = classify_lifecycle_window(Some("randomness-requested"));
        assert_eq!(w.state, "closed");
        assert!(!w.possible);
        assert_eq!(
            w.blocking_reasons,
            vec!["authoritative-randomness-requested"]
        );
    }

    #[test]
    fn pre_cutpoint_open() {
        let w = classify_lifecycle_window(Some("allocation-committed"));
        assert_eq!(w.state, "open");
        assert!(w.possible);
        assert!(w.blocking_reasons.is_empty());
    }

    #[test]
    fn post_cutpoint_states_all_closed() {
        for st in [
            "randomness-fulfilled",
            "result-proposed",
            "result-accepted",
            "claim-consumption-started",
        ] {
            let w = classify_lifecycle_window(Some(st));
            assert_eq!(w.state, "closed");
            assert!(!w.possible);
        }
    }

    #[test]
    fn unknown_falls_closed() {
        let w = classify_lifecycle_window(Some("nonsense"));
        assert_eq!(w.state, "invalid");
        assert!(!w.possible);
        assert_eq!(w.blocking_reasons, vec!["unknown-target-state"]);
    }

    #[test]
    fn missing_round_state_falls_closed() {
        let w = classify_lifecycle_window(None);
        assert_eq!(w.state, "invalid");
        assert!(!w.possible);
    }

    #[test]
    fn round_lifecycle_open_pre_cutpoint() {
        let l = round_lifecycle(Some(&serde_json::Value::String(
            "allocation-committed".into(),
        )));
        assert_eq!(l.cancellation_window, "open");
        assert!(l.cancellation_possible);
        assert!(l.cancellation_blocking_reasons.is_empty());
        assert_eq!(l.lifecycle_assertion_status, "passing");
        assert_eq!(l.derived_state.as_deref(), Some("allocation-committed"));
        assert_eq!(l.evidence_status, "evidence/derived-state");
        assert_eq!(l.assurance, "independent-replay");
        assert_eq!(l.lifecycle_profile_id, PROFILE_ID);
        assert_eq!(l.lifecycle_profile_version, PROFILE_VERSION);
        assert_eq!(l.cancellation_window_schema, CANCELLATION_WINDOW_V1_SCHEMA);
    }

    #[test]
    fn round_lifecycle_cutpoint_closes() {
        let l = round_lifecycle(Some(&serde_json::Value::String(
            "randomness-requested".into(),
        )));
        assert_eq!(l.cancellation_window, "closed");
        assert!(!l.cancellation_possible);
        assert_eq!(
            l.cancellation_blocking_reasons,
            vec!["authoritative-randomness-requested".to_string()]
        );
        assert_eq!(l.lifecycle_assertion_status, "passing");
    }

    #[test]
    fn round_lifecycle_fail_closed_reasons() {
        let unknown = round_lifecycle(Some(&serde_json::Value::String("no-such-state".into())));
        assert_eq!(unknown.cancellation_window, "invalid");
        assert_eq!(
            unknown.cancellation_blocking_reasons,
            vec!["unknown-target-state"]
        );
        assert_eq!(unknown.lifecycle_assertion_status, "failing");
        assert!(unknown.derived_state.is_none());

        let missing = round_lifecycle(Some(&serde_json::Value::Null));
        assert_eq!(
            missing.cancellation_blocking_reasons,
            vec!["missing-target-state"]
        );
        assert!(missing.round_state.is_none());

        let malformed = round_lifecycle(Some(&serde_json::Value::Number(42.into())));
        assert_eq!(
            malformed.cancellation_blocking_reasons,
            vec!["malformed-round-state"]
        );
        assert!(malformed.round_state.is_none());
    }
}
