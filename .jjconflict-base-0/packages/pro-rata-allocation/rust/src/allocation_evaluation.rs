//! Pure allocation computation shared by host and independent implementations.

use crate::allocation_context::{
    claimants_value, context_preimage, outcomes_value, rates_value, result_leaf,
    selected_outcome_value, SELECTION_ALGORITHM,
};
use crate::allocation_roots;
use crate::kernel_types::{Context, Outcome};
use crate::proportionality::build_rate_derived_summary;
use crate::selection::{select_index, SelectionIndex, SelectionReceipt};
use crate::validation::{run_assertions, AssertionResult, Committed, RecomputedRoots};
use num_bigint::BigInt;
use num_traits::{ToPrimitive, Zero};
use prf_canonical_codec::{domain_hash, CanonValue};

/// Frozen allocation-context domain used by the existing kernel.
pub const ALLOCATION_CONTEXT_DOMAIN: &str = "ALLOCATION_CONTEXT_V1";

/// Results of allocation semantics, excluding lifecycle and proof-output policy.
#[derive(Clone, Debug)]
pub struct AllocationEvaluation {
    pub context_hash: String,
    pub claimant_set_root: String,
    pub outcome_set_root: String,
    pub proposed_rates_root: String,
    pub rate_derived_summary_hash: String,
    pub assertions: Vec<AssertionResult>,
    pub selection_receipt: SelectionReceipt,
    pub selected_outcome: Outcome,
    pub selected_outcome_index: BigInt,
    pub selected_outcome_hash: String,
    pub result_root: String,
    pub total_allocated: BigInt,
    pub residual_capacity: BigInt,
}

/// Compute the deterministic allocation result and evaluate allocation assertions.
pub fn evaluate_allocation(
    context: &Context,
    committed: &Committed,
) -> Result<AllocationEvaluation, String> {
    let context_hash = domain_hash(ALLOCATION_CONTEXT_DOMAIN, &context_preimage(context));
    let claimant_set_root = allocation_roots::claimant_set_root(&claimants_value(context));
    let outcome_set_root = allocation_roots::outcome_set_root(&outcomes_value(context));
    let proposed_rates_root = allocation_roots::proposed_rates_root(&rates_value(context));
    let summary = build_rate_derived_summary(context);
    let rate_derived_summary_hash = allocation_roots::rate_derived_summary_hash(&summary);

    // Empty outcome sets are rejected during input parsing. Keep the legacy defensive
    // fallback so direct callers retain the same behavior.
    let selection_index =
        select_index(&context.randomness, context.outcomes.len()).unwrap_or_else(|_| {
            SelectionIndex {
                accepted_counter: BigInt::zero(),
                candidate_digest: String::new(),
                candidate_value: BigInt::zero(),
                selected_index: BigInt::zero(),
            }
        });
    let selected_index = selection_index.selected_index.to_usize().unwrap_or(0);
    let selected_outcome = context
        .outcomes
        .get(selected_index)
        .or_else(|| context.outcomes.first())
        .ok_or_else(|| "allocation context has no outcomes".to_string())?;
    let selected_outcome_index = context
        .outcomes
        .iter()
        .position(|outcome| outcome.outcome_id == selected_outcome.outcome_id)
        .map(BigInt::from)
        .unwrap_or_else(BigInt::zero);
    let selected_outcome_hash =
        allocation_roots::selected_outcome_hash(&selected_outcome_value(selected_outcome));
    let selection_receipt = SelectionReceipt {
        algorithm: SELECTION_ALGORITHM.to_string(),
        outcome_count: BigInt::from(context.outcomes.len()),
        accepted_counter: selection_index.accepted_counter,
        candidate_digest: selection_index.candidate_digest,
        selected_index: selection_index.selected_index,
        selected_outcome_id: selected_outcome.outcome_id.clone(),
        selected_outcome_hash: selected_outcome_hash.clone(),
    };

    let leaves: Vec<CanonValue> = context
        .claimants
        .iter()
        .map(|claim| {
            let allocated = selected_outcome
                .allocations
                .iter()
                .find(|allocation| allocation.claim_id == claim.claim_id)
                .map(|allocation| allocation.allocated.clone())
                .unwrap_or_else(BigInt::zero);
            result_leaf(
                claim,
                allocated,
                &selected_outcome.outcome_id,
                &context_hash,
            )
        })
        .collect();
    let result_root = allocation_roots::result_merkle_root(&leaves)?;
    let total_allocated: BigInt = context
        .claimants
        .iter()
        .map(|claim| {
            selected_outcome
                .allocations
                .iter()
                .find(|allocation| allocation.claim_id == claim.claim_id)
                .map(|allocation| allocation.allocated.clone())
                .unwrap_or_else(BigInt::zero)
        })
        .sum();
    let residual_capacity = &context.capacity - &total_allocated;
    let recomputed = RecomputedRoots {
        claim_root: claimant_set_root.clone(),
        outcome_root: outcome_set_root.clone(),
        rates_root: proposed_rates_root.clone(),
        result_root: result_root.clone(),
    };
    let assertions = run_assertions(
        context,
        committed,
        &recomputed,
        &selection_receipt,
        &total_allocated,
        &residual_capacity,
    );

    Ok(AllocationEvaluation {
        context_hash,
        claimant_set_root,
        outcome_set_root,
        proposed_rates_root,
        rate_derived_summary_hash,
        assertions,
        selection_receipt,
        selected_outcome: selected_outcome.clone(),
        selected_outcome_index,
        selected_outcome_hash,
        result_root,
        total_allocated,
        residual_capacity,
    })
}
