//! The ordered allocation assertion contract used by the legacy Rust kernel.
//!
//! These checks establish allocation validity and compare computed outputs to
//! supplied commitments. Proof admission and execution authorization remain
//! with the host/domain adapter.

use crate::exact_ratio::{rates_canonical_exact, rates_sum_to_one};
use crate::kernel_types::Context;
use crate::proportionality::proportional_proposed;
use crate::selection::SelectionReceipt;
use num_bigint::BigInt;
use num_traits::{Signed, Zero};

/// Ordered assertion IDs. Their order is part of the existing compatibility contract.
pub const ASSERTION_IDS: [&str; 14] = [
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

/// Optional committed projection used to evaluate allocation assertions.
#[derive(Clone, Debug, Default)]
pub struct Committed {
    pub claimant_set_root: Option<String>,
    pub outcome_set_root: Option<String>,
    pub proposed_rates_root: Option<String>,
    pub result_root: Option<String>,
    pub selected_outcome_id: Option<String>,
    pub selected_outcome_index: Option<BigInt>,
}

/// One ordered assertion result.
#[derive(Clone, Debug)]
pub struct AssertionResult {
    pub id: &'static str,
    pub result: bool,
}

impl AssertionResult {
    /// Stable rejection classification for the first failing assertion.
    pub fn classification(&self) -> &'static str {
        match self.id {
            "allocation.assertion/claimant-set-root-valid" => "claimant-set-root-mismatch",
            "allocation.assertion/outcome-set-root-valid" => "outcome-set-root-mismatch",
            "allocation.assertion/proposed-rates-root-valid" => "proposed-rates-root-mismatch",
            "allocation.assertion/rates-canonical-exact" => "rates-not-canonical",
            "allocation.assertion/rates-sum-to-one" => "rates-not-sum-to-one",
            "allocation.assertion/outcomes-eligible-only" => "ineligible-claimant",
            "allocation.assertion/outcomes-no-duplicate-claims" => "duplicate-claim-in-outcome",
            "allocation.assertion/outcomes-all-or-nothing" => "allocation-not-all-or-nothing",
            "allocation.assertion/outcomes-exact-capacity" => "outcome-not-exact-capacity",
            "allocation.assertion/proportional-proposed" => "proportionality-failure",
            "allocation.assertion/randomness-selection-valid" => "randomness-selection-invalid",
            "allocation.assertion/selected-outcome-membership" => "selected-outcome-mismatch",
            "allocation.assertion/result-root-valid" => "result-root-mismatch",
            "allocation.assertion/result-capacity-reconciles" => "result-capacity-mismatch",
            _ => "assertion-failed",
        }
    }
}

/// The recomputed roots needed by root-validity assertions.
#[derive(Clone, Debug)]
pub struct RecomputedRoots {
    pub claim_root: String,
    pub outcome_root: String,
    pub rates_root: String,
    pub result_root: String,
}

fn outcomes_eligible_only(ctx: &Context) -> bool {
    let ids: std::collections::HashSet<&str> = ctx
        .claimants
        .iter()
        .map(|claim| claim.claim_id.as_str())
        .collect();
    ctx.outcomes.iter().all(|outcome| {
        outcome
            .allocations
            .iter()
            .all(|allocation| ids.contains(allocation.claim_id.as_str()))
    })
}

fn outcomes_no_duplicate_claims(ctx: &Context) -> bool {
    ctx.outcomes.iter().all(|outcome| {
        let mut ids: Vec<&str> = outcome
            .allocations
            .iter()
            .map(|allocation| allocation.claim_id.as_str())
            .collect();
        ids.sort_unstable();
        ids.windows(2).all(|pair| pair[0] != pair[1])
    })
}

fn outcomes_all_or_nothing(ctx: &Context) -> bool {
    let amount_by_claim: std::collections::HashMap<&str, &BigInt> = ctx
        .claimants
        .iter()
        .map(|claim| (claim.claim_id.as_str(), &claim.amount))
        .collect();
    ctx.outcomes.iter().all(|outcome| {
        outcome.allocations.iter().all(|allocation| {
            if allocation.allocated.is_zero() {
                true
            } else {
                match amount_by_claim.get(allocation.claim_id.as_str()) {
                    Some(amount) => &allocation.allocated == *amount,
                    None => false,
                }
            }
        })
    })
}

fn outcomes_exact_capacity(ctx: &Context) -> bool {
    ctx.outcomes.iter().all(|outcome| {
        let sum: BigInt = outcome
            .allocations
            .iter()
            .map(|allocation| allocation.allocated.clone())
            .sum();
        sum == ctx.capacity
    })
}

fn selected_outcome_membership(
    ctx: &Context,
    committed: &Committed,
    selection: &SelectionReceipt,
) -> bool {
    let Ok(index) = usize::try_from(&selection.selected_index) else {
        return false;
    };
    let Some(outcome) = ctx.outcomes.get(index) else {
        return false;
    };
    if committed
        .selected_outcome_id
        .as_ref()
        .is_some_and(|committed_id| committed_id != &outcome.outcome_id)
    {
        return false;
    }
    if committed
        .selected_outcome_index
        .as_ref()
        .is_some_and(|committed_index| committed_index != &selection.selected_index)
    {
        return false;
    }
    true
}

fn root_eq(computed: &str, committed: &Option<String>) -> bool {
    match committed {
        None => true,
        Some(committed) => {
            let computed = computed.strip_prefix("0x").unwrap_or(computed);
            let committed = committed.strip_prefix("0x").unwrap_or(committed);
            computed == committed
        }
    }
}

/// Evaluate the ordered 14 allocation assertions. `total_allocated` and
/// `residual` reconcile assertion 14.
pub fn run_assertions(
    ctx: &Context,
    committed: &Committed,
    roots: &RecomputedRoots,
    selection: &SelectionReceipt,
    total_allocated: &BigInt,
    residual: &BigInt,
) -> Vec<AssertionResult> {
    let selection_ok = !ctx.outcomes.is_empty() && !selection.selected_index.is_negative();
    let membership_ok = selected_outcome_membership(ctx, committed, selection);
    let capacity_reconcile_ok = total_allocated == &ctx.capacity && residual.is_zero();

    vec![
        AssertionResult {
            id: ASSERTION_IDS[0],
            result: root_eq(&roots.claim_root, &committed.claimant_set_root),
        },
        AssertionResult {
            id: ASSERTION_IDS[1],
            result: root_eq(&roots.outcome_root, &committed.outcome_set_root),
        },
        AssertionResult {
            id: ASSERTION_IDS[2],
            result: root_eq(&roots.rates_root, &committed.proposed_rates_root),
        },
        AssertionResult {
            id: ASSERTION_IDS[3],
            result: rates_canonical_exact(&ctx.rates),
        },
        AssertionResult {
            id: ASSERTION_IDS[4],
            result: rates_sum_to_one(&ctx.rates),
        },
        AssertionResult {
            id: ASSERTION_IDS[5],
            result: outcomes_eligible_only(ctx),
        },
        AssertionResult {
            id: ASSERTION_IDS[6],
            result: outcomes_no_duplicate_claims(ctx),
        },
        AssertionResult {
            id: ASSERTION_IDS[7],
            result: outcomes_all_or_nothing(ctx),
        },
        AssertionResult {
            id: ASSERTION_IDS[8],
            result: outcomes_exact_capacity(ctx),
        },
        AssertionResult {
            id: ASSERTION_IDS[9],
            result: proportional_proposed(ctx),
        },
        AssertionResult {
            id: ASSERTION_IDS[10],
            result: selection_ok,
        },
        AssertionResult {
            id: ASSERTION_IDS[11],
            result: membership_ok,
        },
        AssertionResult {
            id: ASSERTION_IDS[12],
            result: root_eq(&roots.result_root, &committed.result_root),
        },
        AssertionResult {
            id: ASSERTION_IDS[13],
            result: capacity_reconcile_ok,
        },
    ]
}
