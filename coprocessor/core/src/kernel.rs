//! The independent Rust allocation kernel.
//!
//! Reproduces the PRF reference computation byte-for-byte at the declared
//! public-output boundary. Consumes the same canonical JSON input document as
//! the PRF `allocation verify-proposal` command and emits the same public-value
//! projection.

use crate::assertions::{run_assertions, AssertionResult, RecomputedRoots};
use crate::canonical::{domain_hash, hex32_to_bytes, tags, CanonValue};
use crate::lifecycle;
use crate::proportionality::build_rate_derived_summary;
use crate::roots;
use crate::selection;
use num_bigint::BigInt;
use num_traits::{Signed, ToPrimitive, Zero};

/// Kernel version, must equal the PRF `allocation-kernel.v1`.
pub const KERNEL_VERSION: &str = "allocation-kernel.v1";
pub const SELECTION_ALGORITHM: &str = "domain-hash-rejection-v1";

/// A structured kernel error carrying a stable rejection classification.
#[derive(Clone, Debug)]
pub struct KernelError {
    pub classification: &'static str,
    pub reason: String,
}

impl KernelError {
    pub fn new(classification: &'static str, reason: impl Into<String>) -> Self {
        KernelError {
            classification,
            reason: reason.into(),
        }
    }
}

impl std::fmt::Display for KernelError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.classification, self.reason)
    }
}

#[derive(Clone, Debug)]
pub struct Claimant {
    pub claim_id: String,
    pub economic_owner_id: String,
    pub amount: BigInt,
    pub weight: BigInt,
}

#[derive(Clone, Debug)]
pub struct AllocationEntry {
    pub claim_id: String,
    pub allocated: BigInt,
}

#[derive(Clone, Debug)]
pub struct Outcome {
    pub outcome_id: String,
    pub allocations: Vec<AllocationEntry>,
}

#[derive(Clone, Debug)]
pub struct RateEntry {
    pub outcome_id: String,
    pub numerator: BigInt,
    pub denominator: BigInt,
}

#[derive(Clone, Debug)]
pub struct Policy {
    pub policy_id: String,
    pub policy_hash: String,
    pub forbid_duplicate_owners: bool,
}

#[derive(Clone, Debug)]
pub struct Context {
    pub allocation_id: String,
    pub kernel_version: String,
    pub policy: Policy,
    pub claimants: Vec<Claimant>,
    pub outcomes: Vec<Outcome>,
    pub rates: Vec<RateEntry>,
    pub capacity: BigInt,
    pub total_eligible_weight: BigInt,
    pub exact_pro_rata_denominator: BigInt,
    pub randomness: Vec<u8>,
}

/// Optional committed projection used to evaluate root/selection assertions.
#[derive(Clone, Debug, Default)]
pub struct Committed {
    pub claimant_set_root: Option<String>,
    pub outcome_set_root: Option<String>,
    pub proposed_rates_root: Option<String>,
    pub result_root: Option<String>,
    pub selected_outcome_id: Option<String>,
    pub selected_outcome_index: Option<BigInt>,
}

/// The public-value projection emitted by the kernel.
#[derive(Clone, Debug)]
pub struct PublicResult {
    pub status: String,
    pub allocation_context_hash: String,
    pub claimant_set_root: String,
    pub outcome_set_root: String,
    pub proposed_rates_root: String,
    pub rate_derived_summary_hash: String,
    pub assertions: Vec<AssertionResult>,
    pub selection_receipt: selection::SelectionReceipt,
    pub selected_outcome_id: String,
    pub selected_outcome_index: BigInt,
    pub selected_outcome_hash: String,
    pub result_root: String,
    pub total_allocated: BigInt,
    pub residual_capacity: BigInt,
    pub round_lifecycle: lifecycle::RoundLifecycle,
    pub certificate_assertions_digest: String,
    pub kernel_version: String,
    pub selection_algorithm: String,
    pub rejection_classification: Option<String>,
    pub rejection_reason: Option<String>,
}

// ──────────────────────────────────────────────────────────────────────────────
// Canonical value builders
// ──────────────────────────────────────────────────────────────────────────────

fn claimant_canonical(c: &Claimant) -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("claim/id"),
            CanonValue::str(&c.claim_id),
        ),
        (
            CanonValue::keyword("economic-owner-id"),
            CanonValue::str(&c.economic_owner_id),
        ),
        (
            CanonValue::keyword("amount"),
            CanonValue::int(c.amount.clone()),
        ),
        (
            CanonValue::keyword("weight"),
            CanonValue::int(c.weight.clone()),
        ),
    ])
}

fn allocation_entry_canonical(a: &AllocationEntry) -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("claim/id"),
            CanonValue::str(&a.claim_id),
        ),
        (
            CanonValue::keyword("allocated"),
            CanonValue::int(a.allocated.clone()),
        ),
    ])
}

fn outcome_canonical(o: &Outcome) -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("outcome/id"),
            CanonValue::str(&o.outcome_id),
        ),
        (
            CanonValue::keyword("allocations"),
            CanonValue::array(
                o.allocations
                    .iter()
                    .map(allocation_entry_canonical)
                    .collect(),
            ),
        ),
    ])
}

fn rate_entry_canonical(r: &RateEntry) -> CanonValue {
    // rate maps commit {numerator, denominator} flattened next to outcome/id
    CanonValue::map(vec![
        (
            CanonValue::keyword("outcome/id"),
            CanonValue::str(&r.outcome_id),
        ),
        (
            CanonValue::keyword("numerator"),
            CanonValue::int(r.numerator.clone()),
        ),
        (
            CanonValue::keyword("denominator"),
            CanonValue::int(r.denominator.clone()),
        ),
    ])
}

/// The proposed-rates value committed inside the allocation context hash: each
/// rate entry is wrapped in a :rate map ({outcome/id, rate {numerator,
/// denominator}}). This differs from the proposed-rates ROOT projection which
/// flattens the ratio.
fn rate_entry_context_canonical(r: &RateEntry) -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("outcome/id"),
            CanonValue::str(&r.outcome_id),
        ),
        (
            CanonValue::keyword("rate"),
            CanonValue::map(vec![
                (
                    CanonValue::keyword("numerator"),
                    CanonValue::int(r.numerator.clone()),
                ),
                (
                    CanonValue::keyword("denominator"),
                    CanonValue::int(r.denominator.clone()),
                ),
            ]),
        ),
    ])
}

fn policy_canonical(p: &Policy) -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("policy/id"),
            CanonValue::str(&p.policy_id),
        ),
        (
            CanonValue::keyword("policy/hash"),
            CanonValue::str(&p.policy_hash),
        ),
        (
            CanonValue::keyword("forbid-duplicate-owners"),
            CanonValue::bool(p.forbid_duplicate_owners),
        ),
    ])
}

/// The canonical value tree committed by the allocation context hash.
pub fn context_preimage(ctx: &Context) -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("schema-version"),
            CanonValue::str("allocation-context.v1"),
        ),
        (
            CanonValue::keyword("artifact-kind"),
            CanonValue::keyword("allocation-context"),
        ),
        (
            CanonValue::keyword("allocation/id"),
            CanonValue::str(&ctx.allocation_id),
        ),
        (
            CanonValue::keyword("allocation-kernel-version"),
            CanonValue::str(&ctx.kernel_version),
        ),
        (
            CanonValue::keyword("selection-algorithm"),
            CanonValue::keyword(SELECTION_ALGORITHM),
        ),
        (CanonValue::keyword("policy"), policy_canonical(&ctx.policy)),
        (
            CanonValue::keyword("claimants"),
            CanonValue::array(ctx.claimants.iter().map(claimant_canonical).collect()),
        ),
        (
            CanonValue::keyword("outcomes"),
            CanonValue::array(ctx.outcomes.iter().map(outcome_canonical).collect()),
        ),
        (
            CanonValue::keyword("proposed-rates"),
            CanonValue::array(ctx.rates.iter().map(rate_entry_context_canonical).collect()),
        ),
        (
            CanonValue::keyword("capacity"),
            CanonValue::int(ctx.capacity.clone()),
        ),
        (
            CanonValue::keyword("total-eligible-weight"),
            CanonValue::int(ctx.total_eligible_weight.clone()),
        ),
        (
            CanonValue::keyword("exact-pro-rata-denominator"),
            CanonValue::int(ctx.exact_pro_rata_denominator.clone()),
        ),
        (
            CanonValue::keyword("authoritative-randomness"),
            CanonValue::array(
                ctx.randomness
                    .iter()
                    .map(|b| CanonValue::int(BigInt::from(*b)))
                    .collect(),
            ),
        ),
    ])
}

fn claimants_canonical(ctx: &Context) -> CanonValue {
    CanonValue::array(ctx.claimants.iter().map(claimant_canonical).collect())
}

fn outcomes_canonical(ctx: &Context) -> CanonValue {
    CanonValue::array(ctx.outcomes.iter().map(outcome_canonical).collect())
}

fn rates_canonical(ctx: &Context) -> CanonValue {
    CanonValue::array(ctx.rates.iter().map(rate_entry_canonical).collect())
}

fn selected_outcome_canonical(_ctx: &Context, outcome: &Outcome) -> CanonValue {
    outcome_canonical(outcome)
}

/// One result Merkle leaf value tree. Matches the PRF kernel's `result-leaves`
/// (note: key is :context-hash, not :round/context-hash).
pub fn result_leaf(
    _ctx: &Context,
    claim: &Claimant,
    allocated: BigInt,
    selected_outcome_id: &str,
    context_hash: &str,
) -> CanonValue {
    CanonValue::map(vec![
        (
            CanonValue::keyword("claim/id"),
            CanonValue::str(&claim.claim_id),
        ),
        (
            CanonValue::keyword("beneficiary"),
            CanonValue::str(&claim.economic_owner_id),
        ),
        (
            CanonValue::keyword("context-hash"),
            CanonValue::str(context_hash),
        ),
        (
            CanonValue::keyword("input-amount"),
            CanonValue::int(claim.amount.clone()),
        ),
        (
            CanonValue::keyword("input-weight"),
            CanonValue::int(claim.weight.clone()),
        ),
        (
            CanonValue::keyword("result-status"),
            CanonValue::str(if allocated.is_zero() {
                "not-allocated"
            } else {
                "allocated"
            }),
        ),
        (
            CanonValue::keyword("final-allocation"),
            CanonValue::int(allocated.clone()),
        ),
        (
            CanonValue::keyword("selected-outcome-id"),
            CanonValue::str(selected_outcome_id),
        ),
    ])
}

// ──────────────────────────────────────────────────────────────────────────────
// Kernel execution
// ──────────────────────────────────────────────────────────────────────────────

/// Compute the full public result from a parsed context and committed block.
/// Parse the optional `round-state` input field. Returns the raw JSON value
/// (string, null, or any other type). Callers scope malformed handling to the
/// lifecycle projection, which fails closed; this never rejects the kernel.
pub fn parse_round_state(input: &serde_json::Value) -> Option<serde_json::Value> {
    input.get("round-state").cloned()
}

pub fn run_kernel(
    ctx: &Context,
    committed: &Committed,
    round_state: Option<&serde_json::Value>,
) -> PublicResult {
    let lifecycle = lifecycle::round_lifecycle(round_state);
    let ctx_hash = domain_hash(tags::ALLOCATION_CONTEXT, &context_preimage(ctx));
    let claim_root = roots::claimant_set_root(&claimants_canonical(ctx));
    let outcome_root = roots::outcome_set_root(&outcomes_canonical(ctx));
    let rates_root = roots::proposed_rates_root(&rates_canonical(ctx));
    let summary = build_rate_derived_summary(ctx);
    let summary_hash = roots::rate_derived_summary_hash(&summary);

    let outcome_count = ctx.outcomes.len();
    // Selection failure (empty outcome set) cannot occur here because
    // parse_context rejects empty outcome sets; a defensive default is used.
    let sel_index = selection::select_index(&ctx.randomness, outcome_count).unwrap_or_else(|_| {
        selection::SelectionIndex {
            accepted_counter: BigInt::zero(),
            candidate_digest: String::new(),
            candidate_value: BigInt::zero(),
            selected_index: BigInt::zero(),
        }
    });
    let sel = selection::SelectionReceipt {
        algorithm: SELECTION_ALGORITHM.to_string(),
        outcome_count: BigInt::from(outcome_count),
        accepted_counter: sel_index.accepted_counter.clone(),
        candidate_digest: sel_index.candidate_digest.clone(),
        selected_index: sel_index.selected_index.clone(),
        selected_outcome_id: ctx.outcomes[sel_index.selected_index.to_usize().unwrap_or(0)]
            .outcome_id
            .clone(),
        selected_outcome_hash: String::new(), // filled below
    };
    let selected = &ctx.outcomes[sel.selected_index.to_usize().unwrap_or(0)];
    let selected_hash = roots::selected_outcome_hash(&selected_outcome_canonical(ctx, selected));
    let mut sel = sel;
    sel.selected_outcome_hash = selected_hash.clone();

    let leaves: Vec<CanonValue> = ctx
        .claimants
        .iter()
        .map(|claim| {
            let alloc = selected
                .allocations
                .iter()
                .find(|a| a.claim_id == claim.claim_id)
                .map(|a| a.allocated.clone())
                .unwrap_or_else(BigInt::zero);
            result_leaf(ctx, claim, alloc, &selected.outcome_id, &ctx_hash)
        })
        .collect();
    let result_root = roots::result_merkle_root(&leaves).unwrap_or_default();
    let total_allocated: BigInt = ctx
        .claimants
        .iter()
        .map(|claim| {
            selected
                .allocations
                .iter()
                .find(|a| a.claim_id == claim.claim_id)
                .map(|a| a.allocated.clone())
                .unwrap_or_else(BigInt::zero)
        })
        .sum();
    let residual = &ctx.capacity - &total_allocated;

    let recomputed = RecomputedRoots {
        claim_root: claim_root.clone(),
        outcome_root: outcome_root.clone(),
        rates_root: rates_root.clone(),
        result_root: result_root.clone(),
    };
    let assertions = run_assertions(
        ctx,
        committed,
        &recomputed,
        &sel,
        &total_allocated,
        &residual,
    );

    let all_pass = assertions.iter().all(|a| a.result);
    let status = if all_pass { "passing" } else { "rejected" };

    let digest_preimage = CanonValue::map(vec![
        (
            CanonValue::keyword("allocation-context-hash"),
            CanonValue::str(&ctx_hash),
        ),
        (
            CanonValue::keyword("assertions"),
            CanonValue::array(
                assertions
                    .iter()
                    .map(|a| {
                        CanonValue::map(vec![
                            (
                                CanonValue::keyword("assertion/id"),
                                CanonValue::keyword(a.id.to_string()),
                            ),
                            (
                                CanonValue::keyword("assertion/result"),
                                CanonValue::bool(a.result),
                            ),
                        ])
                    })
                    .collect(),
            ),
        ),
        (
            CanonValue::keyword("selected-outcome-id"),
            CanonValue::str(&selected.outcome_id),
        ),
        (
            CanonValue::keyword("selected-outcome-index"),
            CanonValue::int(sel.selected_index.clone()),
        ),
        (
            CanonValue::keyword("result-root"),
            CanonValue::str(&result_root),
        ),
        (
            CanonValue::keyword("total-allocated"),
            CanonValue::int(total_allocated.clone()),
        ),
        (
            CanonValue::keyword("residual-capacity"),
            CanonValue::int(residual.clone()),
        ),
        (
            CanonValue::keyword("allocation-kernel-version"),
            CanonValue::str(KERNEL_VERSION),
        ),
        (
            CanonValue::keyword("round-state"),
            match &lifecycle.round_state {
                Some(s) => CanonValue::str(s),
                None => CanonValue::null(),
            },
        ),
        (
            CanonValue::keyword("derived-state"),
            match &lifecycle.derived_state {
                Some(s) => CanonValue::str(s),
                None => CanonValue::null(),
            },
        ),
        (
            CanonValue::keyword("lifecycle-profile-id"),
            CanonValue::str(&lifecycle.lifecycle_profile_id),
        ),
        (
            CanonValue::keyword("lifecycle-profile-version"),
            CanonValue::int(BigInt::from(lifecycle.lifecycle_profile_version)),
        ),
        (
            CanonValue::keyword("cancellation-window-schema"),
            CanonValue::str(&lifecycle.cancellation_window_schema),
        ),
        (
            CanonValue::keyword("cancellation-window"),
            CanonValue::str(&lifecycle.cancellation_window),
        ),
        (
            CanonValue::keyword("cancellation-possible"),
            CanonValue::bool(lifecycle.cancellation_possible),
        ),
        (
            CanonValue::keyword("cancellation-blocking-reasons"),
            CanonValue::array(
                lifecycle
                    .cancellation_blocking_reasons
                    .iter()
                    .map(CanonValue::str)
                    .collect(),
            ),
        ),
        (
            CanonValue::keyword("lifecycle-assertion-status"),
            CanonValue::str(&lifecycle.lifecycle_assertion_status),
        ),
        (
            CanonValue::keyword("lifecycle-assurance"),
            CanonValue::str(&lifecycle.assurance),
        ),
    ]);
    let digest = roots::certificate_assertions_digest_v2(&digest_preimage);

    let (rejection_classification, rejection_reason) = if all_pass {
        (None, None)
    } else {
        let first_failed = assertions.iter().find(|a| !a.result);
        let classification = first_failed.map(|a| a.classification().to_string());
        (
            classification,
            Some("One or more kernel assertions failed".to_string()),
        )
    };

    PublicResult {
        status: status.to_string(),
        allocation_context_hash: ctx_hash,
        claimant_set_root: claim_root,
        outcome_set_root: outcome_root,
        proposed_rates_root: rates_root,
        rate_derived_summary_hash: summary_hash,
        assertions,
        selection_receipt: sel,
        selected_outcome_id: selected.outcome_id.clone(),
        selected_outcome_index: selected_index_of(ctx, selected),
        selected_outcome_hash: selected_hash,
        result_root,
        total_allocated,
        residual_capacity: residual,
        round_lifecycle: lifecycle,
        certificate_assertions_digest: digest,
        kernel_version: KERNEL_VERSION.to_string(),
        selection_algorithm: SELECTION_ALGORITHM.to_string(),
        rejection_classification,
        rejection_reason,
    }
}

fn selected_index_of(ctx: &Context, selected: &Outcome) -> BigInt {
    let idx = ctx
        .outcomes
        .iter()
        .position(|o| o.outcome_id == selected.outcome_id)
        .unwrap_or(0);
    BigInt::from(idx)
}

/// Parse a raw JSON input document into a context, applying canonical ordering.
pub fn parse_context(input: &serde_json::Value) -> Result<Context, KernelError> {
    let allocation_id = string_field(input, "allocation-id")?;
    let kernel_version = input
        .get("kernel-version")
        .and_then(|v| v.as_str())
        .unwrap_or(KERNEL_VERSION)
        .to_string();
    if kernel_version != KERNEL_VERSION {
        return Err(KernelError::new(
            "unsupported-kernel-version",
            format!("unsupported kernel version: {}", kernel_version),
        ));
    }
    let selection_algorithm = input
        .get("selection-algorithm")
        .and_then(|v| v.as_str())
        .unwrap_or(SELECTION_ALGORITHM)
        .to_string();
    if selection_algorithm != SELECTION_ALGORITHM {
        return Err(KernelError::new(
            "unsupported-selection-algorithm",
            format!("unsupported selection algorithm: {}", selection_algorithm),
        ));
    }

    let policy_value = input
        .get("policy")
        .cloned()
        .unwrap_or(serde_json::Value::Null);
    let policy = parse_policy(&policy_value)?;

    let raw_claimants = input
        .get("claimants")
        .and_then(|v| v.as_array())
        .ok_or_else(|| KernelError::new("malformed-claimants", "claimants must be a JSON array"))?;
    let mut claimants = Vec::with_capacity(raw_claimants.len());
    for raw in raw_claimants {
        claimants.push(parse_claimant(raw)?);
    }
    // duplicate claim ids rejected
    let mut ids: Vec<&str> = claimants.iter().map(|c| c.claim_id.as_str()).collect();
    ids.sort_unstable();
    for w in ids.windows(2) {
        if w[0] == w[1] {
            return Err(KernelError::new("duplicate-claim-id", "duplicate claim id"));
        }
    }
    if policy.forbid_duplicate_owners {
        let mut owners: Vec<&str> = claimants
            .iter()
            .map(|c| c.economic_owner_id.as_str())
            .collect();
        owners.sort_unstable();
        for w in owners.windows(2) {
            if w[0] == w[1] {
                return Err(KernelError::new(
                    "duplicate-economic-owner",
                    "duplicate economic owner",
                ));
            }
        }
    }
    if claimants.is_empty() {
        return Err(KernelError::new("empty-claimant-set", "empty claimant set"));
    }
    sort_claimants(&mut claimants);

    let raw_outcomes = input
        .get("outcomes")
        .and_then(|v| v.as_array())
        .ok_or_else(|| KernelError::new("malformed-outcomes", "outcomes must be a JSON array"))?;
    let mut outcomes = Vec::with_capacity(raw_outcomes.len());
    for raw in raw_outcomes {
        outcomes.push(parse_outcome(raw)?);
    }
    if outcomes.is_empty() {
        return Err(KernelError::new("empty-outcome-set", "empty outcome set"));
    }
    let mut outcome_ids: Vec<&str> = outcomes.iter().map(|o| o.outcome_id.as_str()).collect();
    outcome_ids.sort_unstable();
    for w in outcome_ids.windows(2) {
        if w[0] == w[1] {
            return Err(KernelError::new(
                "duplicate-outcome-id",
                "duplicate outcome id",
            ));
        }
    }
    sort_outcomes(&mut outcomes);

    let raw_rates = input
        .get("proposed-rates")
        .and_then(|v| v.as_array())
        .ok_or_else(|| {
            KernelError::new("malformed-rates", "proposed-rates must be a JSON array")
        })?;
    let mut rate_map = std::collections::HashMap::new();
    for raw in raw_rates {
        let outcome_id = string_field(raw, "outcome-id")?;
        let numerator = bigint_field(raw, "numerator")?;
        let denominator = bigint_field(raw, "denominator")?;
        if numerator.is_negative() {
            return Err(KernelError::new(
                "negative-rate-numerator",
                "negative rate numerator",
            ));
        }
        if denominator.is_zero() {
            return Err(KernelError::new(
                "non-positive-rate-denominator",
                "non-positive rate denominator",
            ));
        }
        rate_map.insert(outcome_id, (numerator, denominator));
    }
    let rates: Vec<RateEntry> = outcomes
        .iter()
        .map(|o| {
            let (numerator, denominator) =
                rate_map.get(&o.outcome_id).cloned().ok_or_else(|| {
                    KernelError::new(
                        "rates-outcome-mismatch",
                        format!("missing rate for outcome {}", o.outcome_id),
                    )
                })?;
            Ok(RateEntry {
                outcome_id: o.outcome_id.clone(),
                numerator,
                denominator,
            })
        })
        .collect::<Result<Vec<_>, KernelError>>()?;

    let capacity = bigint_field(input, "capacity")?;
    if capacity.is_zero() || capacity.is_negative() {
        return Err(KernelError::new(
            "non-positive-capacity",
            "non-positive capacity",
        ));
    }
    let total_eligible_weight = bigint_field(input, "total-eligible-weight")?;
    let exact_pro_rata_denominator = bigint_field(input, "exact-pro-rata-denominator")?;

    let randomness = input
        .get("authoritative-randomness")
        .and_then(|v| v.as_str())
        .ok_or_else(|| {
            KernelError::new("malformed-randomness", "authoritative-randomness required")
        })?;
    let randomness = hex32_to_bytes(randomness.strip_prefix("0x").unwrap_or(randomness))
        .ok_or_else(|| {
            KernelError::new(
                "malformed-randomness",
                "authoritative-randomness must be exactly 32 bytes hex",
            )
        })?;

    let sum_weights: BigInt = claimants.iter().map(|c| c.weight.clone()).sum();
    if sum_weights.is_zero() {
        return Err(KernelError::new("zero-total-weight", "zero total weight"));
    }
    if sum_weights != total_eligible_weight {
        return Err(KernelError::new(
            "inconsistent-total-weight",
            "inconsistent total weight",
        ));
    }
    if sum_weights != exact_pro_rata_denominator {
        return Err(KernelError::new(
            "inconsistent-pro-rata-denominator",
            "inconsistent pro-rata denominator",
        ));
    }

    Ok(Context {
        allocation_id,
        kernel_version,
        policy,
        claimants,
        outcomes,
        rates,
        capacity,
        total_eligible_weight,
        exact_pro_rata_denominator,
        randomness,
    })
}

/// Parse the committed block for non-proving replay use. Bindings remain optional
/// here so individual assertions can be evaluated in isolation.
pub fn parse_committed(input: &serde_json::Value) -> Result<Committed, KernelError> {
    let mut committed = Committed::default();
    let Some(value) = input.get("committed") else {
        return Ok(committed);
    };
    let map = value
        .as_object()
        .ok_or_else(|| KernelError::new("malformed-committed", "committed must be an object"))?;
    committed.claimant_set_root = opt_hex(map, "claimant-set-root")?;
    committed.outcome_set_root = opt_hex(map, "outcome-set-root")?;
    committed.proposed_rates_root = opt_hex(map, "proposed-rates-root")?;
    committed.result_root = opt_hex(map, "result-root")?;
    committed.selected_outcome_id = map
        .get("selected-outcome-id")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    committed.selected_outcome_index = map
        .get("selected-outcome-index")
        .and_then(|v| v.as_str())
        .map(|s| parse_bigint(s).unwrap_or_else(|_| BigInt::zero()));
    Ok(committed)
}

/// Parse the committed bindings required for allocation proof and verification
/// inputs. A proof must bind every recomputed root and the selected outcome;
/// otherwise it could attest only to self-derived, uncommitted values.
pub fn parse_committed_for_proving(input: &serde_json::Value) -> Result<Committed, KernelError> {
    let committed = parse_committed(input)?;
    let committed_map = input
        .get("committed")
        .and_then(|value| value.as_object())
        .ok_or_else(|| {
            KernelError::new(
                "missing-committed-binding",
                "committed block required for proving",
            )
        })?;
    let required_roots = [
        ("claimant-set-root", &committed.claimant_set_root),
        ("outcome-set-root", &committed.outcome_set_root),
        ("proposed-rates-root", &committed.proposed_rates_root),
        ("result-root", &committed.result_root),
    ];
    for (name, value) in required_roots {
        if value.is_none() {
            return Err(KernelError::new(
                "missing-committed-binding",
                format!("committed {} required for proving", name),
            ));
        }
    }
    let selected_outcome_id = committed_map
        .get("selected-outcome-id")
        .and_then(|value| value.as_str())
        .filter(|value| !value.is_empty())
        .ok_or_else(|| {
            KernelError::new(
                "missing-committed-binding",
                "committed selected-outcome-id required for proving",
            )
        })?;
    let selected_outcome_index = committed_map
        .get("selected-outcome-index")
        .and_then(|value| value.as_str())
        .ok_or_else(|| {
            KernelError::new(
                "missing-committed-binding",
                "committed selected-outcome-index required for proving",
            )
        })?;
    if parse_bigint(selected_outcome_index).is_err() {
        return Err(KernelError::new(
            "malformed-committed",
            "committed selected-outcome-index must be an integer string",
        ));
    }
    if committed.selected_outcome_id.as_deref() != Some(selected_outcome_id)
        || committed.selected_outcome_index.is_none()
    {
        return Err(KernelError::new(
            "malformed-committed",
            "committed selected outcome binding is malformed",
        ));
    }
    Ok(committed)
}

fn opt_hex(
    map: &serde_json::Map<String, serde_json::Value>,
    key: &str,
) -> Result<Option<String>, KernelError> {
    match map.get(key) {
        None => Ok(None),
        Some(v) => {
            let s = v.as_str().ok_or_else(|| {
                KernelError::new("malformed-committed", format!("{} must be a string", key))
            })?;
            let bare = s.strip_prefix("0x").unwrap_or(s);
            if !(bare.len() == 64
                && bare
                    .bytes()
                    .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()))
            {
                return Err(KernelError::new(
                    "malformed-committed",
                    format!("{} must be 0x-prefixed 32-byte hex", key),
                ));
            }
            Ok(Some(s.to_string()))
        }
    }
}

fn parse_claimant(raw: &serde_json::Value) -> Result<Claimant, KernelError> {
    Ok(Claimant {
        claim_id: string_field(raw, "claim-id")?,
        economic_owner_id: string_field(raw, "economic-owner-id")?,
        amount: bigint_field(raw, "amount")?,
        weight: bigint_field(raw, "weight")?,
    })
}

fn parse_outcome(raw: &serde_json::Value) -> Result<Outcome, KernelError> {
    let outcome_id = string_field(raw, "outcome-id")?;
    let allocations_raw = raw
        .get("allocations")
        .and_then(|v| v.as_array())
        .ok_or_else(|| {
            KernelError::new(
                "malformed-outcome",
                format!("outcome {} allocations must be an array", outcome_id),
            )
        })?;
    let mut allocations = Vec::with_capacity(allocations_raw.len());
    for raw in allocations_raw {
        allocations.push(AllocationEntry {
            claim_id: string_field(raw, "claim-id")?,
            allocated: bigint_field(raw, "allocated")?,
        });
    }
    Ok(Outcome {
        outcome_id,
        allocations,
    })
}

fn parse_policy(raw: &serde_json::Value) -> Result<Policy, KernelError> {
    let policy_id = string_field(raw, "policy-id")?;
    let policy_hash = string_field(raw, "policy-hash")?;
    let forbid = raw
        .get("forbid-duplicate-owners")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    Ok(Policy {
        policy_id,
        policy_hash,
        forbid_duplicate_owners: forbid,
    })
}

fn string_field(v: &serde_json::Value, key: &str) -> Result<String, KernelError> {
    v.get(key)
        .and_then(|x| x.as_str())
        .map(|s| s.to_string())
        .ok_or_else(|| {
            KernelError::new("malformed-input", format!("missing string field: {}", key))
        })
}

fn bigint_field(v: &serde_json::Value, key: &str) -> Result<BigInt, KernelError> {
    match v.get(key) {
        None => Err(KernelError::new(
            "malformed-input",
            format!("missing integer field: {}", key),
        )),
        Some(serde_json::Value::String(s)) => parse_bigint(s),
        Some(serde_json::Value::Number(n)) => n
            .as_i64()
            .map(BigInt::from)
            .ok_or_else(|| KernelError::new("malformed-input", format!("non-integer {}", key))),
        Some(_) => Err(KernelError::new(
            "malformed-input",
            format!("invalid integer field: {}", key),
        )),
    }
}

fn parse_bigint(s: &str) -> Result<BigInt, KernelError> {
    if !s.bytes().all(|b| b.is_ascii_digit()) {
        return Err(KernelError::new(
            "malformed-integer",
            format!("not a decimal string: {}", s),
        ));
    }
    BigInt::parse_bytes(s.as_bytes(), 10)
        .ok_or_else(|| KernelError::new("malformed-integer", format!("invalid integer: {}", s)))
}

/// Canonical ordering by the canonical binary encoding of an id string.
fn canonical_id_key(id: &str) -> Vec<u8> {
    CanonValue::str(id).encode()
}

fn sort_claimants(claimants: &mut [Claimant]) {
    claimants.sort_by_key(|c| canonical_id_key(&c.claim_id));
}

fn sort_outcomes(outcomes: &mut [Outcome]) {
    outcomes.sort_by_key(|o| canonical_id_key(&o.outcome_id));
    for outcome in outcomes.iter_mut() {
        outcome
            .allocations
            .sort_by_key(|a| canonical_id_key(&a.claim_id));
    }
}
