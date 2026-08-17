//! The ordered 14-assertion contract. The order is part of the compatibility
//! contract between PRF and the Rust kernel.

use crate::kernel::{Committed, Context};
use crate::proportionality::{proportional_proposed, rates_canonical_exact, rates_sum_to_one};
use crate::selection::SelectionReceipt;
use num_bigint::BigInt;
use num_traits::{Signed, Zero};

/// Ordered assertion IDs. The order is part of the compatibility contract.
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

fn outcomes_eligible_only(ctx: &Context) -> bool {
    let ids: std::collections::HashSet<&str> =
        ctx.claimants.iter().map(|c| c.claim_id.as_str()).collect();
    ctx.outcomes.iter().all(|o| {
        o.allocations
            .iter()
            .all(|a| ids.contains(a.claim_id.as_str()))
    })
}

fn outcomes_no_duplicate_claims(ctx: &Context) -> bool {
    ctx.outcomes.iter().all(|o| {
        let mut ids: Vec<&str> = o.allocations.iter().map(|a| a.claim_id.as_str()).collect();
        ids.sort_unstable();
        ids.windows(2).all(|w| w[0] != w[1])
    })
}

fn outcomes_all_or_nothing(ctx: &Context) -> bool {
    let amount_by_claim: std::collections::HashMap<&str, &BigInt> = ctx
        .claimants
        .iter()
        .map(|c| (c.claim_id.as_str(), &c.amount))
        .collect();
    ctx.outcomes.iter().all(|o| {
        o.allocations.iter().all(|a| {
            let allocated = &a.allocated;
            if allocated.is_zero() {
                true
            } else {
                match amount_by_claim.get(a.claim_id.as_str()) {
                    Some(amount) => allocated == *amount,
                    None => false,
                }
            }
        })
    })
}

fn outcomes_exact_capacity(ctx: &Context) -> bool {
    ctx.outcomes.iter().all(|o| {
        let sum: BigInt = o.allocations.iter().map(|a| a.allocated.clone()).sum();
        sum == ctx.capacity
    })
}

fn selected_outcome_membership(
    ctx: &Context,
    committed: &Committed,
    sel: &SelectionReceipt,
) -> bool {
    let idx = usize::try_from(&sel.selected_index);
    if idx.is_err() || idx.unwrap() >= ctx.outcomes.len() {
        return false;
    }
    let idx = idx.unwrap();
    let selected_id = &ctx.outcomes[idx].outcome_id;
    if let Some(committed_id) = &committed.selected_outcome_id {
        if committed_id != selected_id {
            return false;
        }
    }
    if let Some(committed_index) = &committed.selected_outcome_index {
        if committed_index != &sel.selected_index {
            return false;
        }
    }
    true
}

fn root_eq(computed: &str, committed: &Option<String>) -> bool {
    match committed {
        None => true,
        Some(c) => {
            let computed_norm = computed.strip_prefix("0x").unwrap_or(computed);
            let committed_norm = c.strip_prefix("0x").unwrap_or(c);
            computed_norm == committed_norm
        }
    }
}

/// The recomputed roots needed by the root-validity assertions.
#[derive(Clone, Debug)]
pub struct RecomputedRoots {
    pub claim_root: String,
    pub outcome_root: String,
    pub rates_root: String,
    pub result_root: String,
}

/// Evaluate the ordered 14 assertions. `total_allocated` and `residual`
/// reconcile assertion 14.
pub fn run_assertions(
    ctx: &Context,
    committed: &Committed,
    roots: &RecomputedRoots,
    sel: &SelectionReceipt,
    total_allocated: &BigInt,
    residual: &BigInt,
) -> Vec<AssertionResult> {
    let selection_ok = !ctx.outcomes.is_empty() && !sel.selected_index.is_negative();
    let membership_ok = selected_outcome_membership(ctx, committed, sel);
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
