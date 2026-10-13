//! Allocation data types shared by allocation implementations.

use crate::exact_ratio::RateEntry;
use num_bigint::BigInt;

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
