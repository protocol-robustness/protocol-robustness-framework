//! Deterministic integer allocation for normalized V1 pro-rata requests.
//!
//! This is the generic allocation kernel. It uses arbitrary-precision integer
//! arithmetic and keeps rows in the canonical order established by
//! `request::normalize_rows`.

use std::collections::HashMap;

use num_bigint::BigInt;
use num_traits::{ToPrimitive, Zero};
use prf_canonical_codec::CanonValue;

use crate::request::NormalizedAllocationRequest;

#[derive(Clone, Debug, PartialEq)]
pub struct AllocatedRow {
    pub row_id: CanonValue,
    pub allocated: BigInt,
    pub unmet: BigInt,
}

#[derive(Clone, Debug, PartialEq)]
pub struct AllocationOutput {
    pub rows: Vec<AllocatedRow>,
    pub total_allocated: BigInt,
    pub total_unmet: BigInt,
    pub remainder: BigInt,
}

fn distribute(
    amount: &BigInt,
    rows: &[crate::request::NormalizedAllocationRow],
    largest_remainder: bool,
) -> Vec<BigInt> {
    let weight_total: BigInt = rows.iter().map(|row| &row.weight).sum();
    if weight_total.is_zero() || amount.is_zero() {
        return vec![BigInt::zero(); rows.len()];
    }

    let numerators: Vec<BigInt> = rows.iter().map(|row| amount * &row.weight).collect();
    let floors: Vec<BigInt> = numerators
        .iter()
        .map(|numerator| numerator / &weight_total)
        .collect();
    if !largest_remainder {
        return floors;
    }

    let remainder_units = amount - floors.iter().sum::<BigInt>();
    let remainder_count = remainder_units.to_usize().unwrap_or(0);
    let mut order: Vec<usize> = (0..rows.len()).collect();
    order.sort_by(|left, right| {
        let left_remainder = &numerators[*left] % &weight_total;
        let right_remainder = &numerators[*right] % &weight_total;
        right_remainder
            .cmp(&left_remainder)
            .then_with(|| left.cmp(right))
    });
    let awarded: std::collections::HashSet<usize> =
        order.into_iter().take(remainder_count).collect();
    floors
        .into_iter()
        .enumerate()
        .map(|(index, floor)| {
            if awarded.contains(&index) {
                floor + BigInt::from(1)
            } else {
                floor
            }
        })
        .collect()
}

fn output(rows: Vec<AllocatedRow>, remainder: BigInt) -> AllocationOutput {
    let total_allocated = rows.iter().map(|row| &row.allocated).sum();
    let total_unmet = rows.iter().map(|row| &row.unmet).sum();
    AllocationOutput {
        rows,
        total_allocated,
        total_unmet,
        remainder,
    }
}

/// Allocate a normalized public request using its frozen V1 policies.
pub fn allocate_normalized(request: &NormalizedAllocationRequest) -> AllocationOutput {
    let largest_remainder = request.rounding_policy == CanonValue::keyword("largest-remainder");
    if request.redistribution_policy == CanonValue::keyword("unallocated") {
        return allocate_unallocated(request, largest_remainder);
    }
    allocate_redistributed(request, largest_remainder)
}

fn allocate_unallocated(
    request: &NormalizedAllocationRequest,
    largest_remainder: bool,
) -> AllocationOutput {
    let quotas = distribute(&request.available, &request.rows, largest_remainder);
    let mut residual = request.available.clone();
    let rows = request
        .rows
        .iter()
        .zip(quotas)
        .map(|(row, quota)| {
            let allocated = if quota <= row.effective_cap {
                quota.clone()
            } else {
                row.effective_cap.clone()
            };
            let unmet = quota - &allocated;
            residual -= &allocated;
            residual -= &unmet;
            AllocatedRow {
                row_id: row.row_id.clone(),
                allocated,
                unmet,
            }
        })
        .collect();
    output(rows, residual)
}

fn allocate_redistributed(
    request: &NormalizedAllocationRequest,
    largest_remainder: bool,
) -> AllocationOutput {
    let mut remaining = request.available.clone();
    let mut active: Vec<usize> = (0..request.rows.len()).collect();
    let mut committed: HashMap<usize, AllocatedRow> = HashMap::new();

    loop {
        if active.is_empty() {
            let mut committed_rows: Vec<_> = committed.keys().copied().collect();
            committed_rows.sort_unstable();
            let source_rows: Vec<_> = committed_rows
                .iter()
                .map(|index| request.rows[*index].clone())
                .collect();
            let shares = distribute(&remaining, &source_rows, true);
            for (index, unmet) in committed_rows.into_iter().zip(shares) {
                if let Some(row) = committed.get_mut(&index) {
                    row.unmet = unmet;
                }
            }
            let rows = (0..request.rows.len())
                .filter_map(|index| committed.remove(&index))
                .collect();
            return output(rows, BigInt::zero());
        }

        let active_rows: Vec<_> = active
            .iter()
            .map(|index| request.rows[*index].clone())
            .collect();
        let weight_total: BigInt = active_rows.iter().map(|row| &row.weight).sum();
        if weight_total.is_zero() {
            let mut rows = Vec::with_capacity(request.rows.len());
            for index in 0..request.rows.len() {
                if let Some(row) = committed.remove(&index) {
                    rows.push(row);
                } else {
                    rows.push(AllocatedRow {
                        row_id: request.rows[index].row_id.clone(),
                        allocated: BigInt::zero(),
                        unmet: BigInt::zero(),
                    });
                }
            }
            return output(rows, remaining);
        }

        let constrained: Vec<usize> = active
            .iter()
            .copied()
            .filter(|index| {
                let row = &request.rows[*index];
                &remaining * &row.weight >= &row.effective_cap * &weight_total
            })
            .collect();

        if constrained.is_empty() {
            let allocations = distribute(&remaining, &active_rows, largest_remainder);
            for (index, allocated) in active.iter().copied().zip(allocations) {
                committed.insert(
                    index,
                    AllocatedRow {
                        row_id: request.rows[index].row_id.clone(),
                        allocated: allocated.clone(),
                        unmet: BigInt::zero(),
                    },
                );
                remaining -= allocated;
            }
            let rows = (0..request.rows.len())
                .filter_map(|index| committed.remove(&index))
                .collect();
            return output(rows, remaining);
        }

        let constrained_set: std::collections::HashSet<usize> =
            constrained.iter().copied().collect();
        for index in constrained {
            let cap = request.rows[index].effective_cap.clone();
            remaining -= &cap;
            committed.insert(
                index,
                AllocatedRow {
                    row_id: request.rows[index].row_id.clone(),
                    allocated: cap,
                    unmet: BigInt::zero(),
                },
            );
        }
        active.retain(|index| !constrained_set.contains(index));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::request::{normalize_request, AllocationRequestInput, AllocationRowInput};

    fn amount(value: i64) -> Option<CanonValue> {
        Some(CanonValue::int(value))
    }

    fn row(id: &str, requested: i64, weight: i64, cap: Option<i64>) -> AllocationRowInput {
        AllocationRowInput {
            row_id: Some(CanonValue::keyword(id)),
            obligation_id: None,
            requested: amount(requested),
            weight: amount(weight),
            cap: cap.map(CanonValue::int),
        }
    }

    fn request(
        rows: Vec<AllocationRowInput>,
        available: i64,
        redistribution: &str,
    ) -> NormalizedAllocationRequest {
        normalize_request(&AllocationRequestInput {
            allocation_id: Some(CanonValue::keyword("allocation/test")),
            schema_version: None,
            mechanism_version: None,
            available: amount(available),
            rows,
            rounding_policy: None,
            tie_break_policy: None,
            redistribution_policy: Some(CanonValue::keyword(redistribution)),
        })
        .unwrap()
    }

    #[test]
    fn largest_remainder_matches_frozen_rounding_vector() {
        let request = request(
            vec![row("row/a", 5, 1, None), row("row/b", 5, 1, None)],
            5,
            "unallocated",
        );
        let result = allocate_normalized(&request);
        assert_eq!(
            result
                .rows
                .iter()
                .map(|row| row.allocated.clone())
                .collect::<Vec<_>>(),
            vec![3.into(), 2.into()]
        );
        assert_eq!(result.total_allocated, BigInt::from(5));
        assert_eq!(result.remainder, BigInt::zero());
    }

    #[test]
    fn floor_preserves_v1_residual() {
        let mut request = request(
            vec![row("row/a", 5, 1, None), row("row/b", 5, 1, None)],
            5,
            "unallocated",
        );
        request.rounding_policy = CanonValue::keyword("floor");
        let result = allocate_normalized(&request);
        assert_eq!(
            result
                .rows
                .iter()
                .map(|row| row.allocated.clone())
                .collect::<Vec<_>>(),
            vec![2.into(), 2.into()]
        );
        assert_eq!(result.remainder, BigInt::from(1));
    }

    #[test]
    fn caps_are_clamped_without_redistribution_for_unallocated_policy() {
        let request = request(
            vec![row("row/a", 1, 1, Some(1)), row("row/b", 10, 1, None)],
            5,
            "unallocated",
        );
        let result = allocate_normalized(&request);
        assert_eq!(
            result
                .rows
                .iter()
                .map(|row| row.allocated.clone())
                .collect::<Vec<_>>(),
            vec![1.into(), 2.into()]
        );
        assert_eq!(result.rows[0].unmet, BigInt::from(2));
        assert_eq!(result.remainder, BigInt::zero());
    }

    #[test]
    fn capped_share_is_redistributed_to_surviving_rows() {
        let request = request(
            vec![row("row/a", 1, 1, Some(1)), row("row/b", 10, 1, None)],
            5,
            "redistribute-cap-excess",
        );
        let result = allocate_normalized(&request);
        assert_eq!(
            result
                .rows
                .iter()
                .map(|row| row.allocated.clone())
                .collect::<Vec<_>>(),
            vec![1.into(), 4.into()]
        );
        assert_eq!(result.remainder, BigInt::zero());
    }

    #[test]
    fn frozen_cap_clamping_and_redistribution_vectors_match() {
        let unallocated = request(
            vec![row("row/a", 10, 1, Some(2)), row("row/b", 10, 1, Some(10))],
            10,
            "unallocated",
        );
        let result = allocate_normalized(&unallocated);
        assert_eq!(
            result
                .rows
                .iter()
                .map(|row| (row.allocated.clone(), row.unmet.clone()))
                .collect::<Vec<_>>(),
            vec![(2.into(), 3.into()), (5.into(), 0.into())]
        );
        assert_eq!(result.remainder, BigInt::zero());

        let redistributed = request(
            vec![row("row/a", 10, 1, Some(2)), row("row/b", 10, 1, Some(10))],
            10,
            "redistribute-cap-excess",
        );
        let result = allocate_normalized(&redistributed);
        assert_eq!(
            result
                .rows
                .iter()
                .map(|row| row.allocated.clone())
                .collect::<Vec<_>>(),
            vec![2.into(), 8.into()]
        );
        assert_eq!(result.total_allocated, BigInt::from(10));
        assert_eq!(result.remainder, BigInt::zero());
    }

    #[test]
    fn empty_and_zero_weight_requests_preserve_available_as_residual() {
        let empty = request(vec![], 5, "unallocated");
        let result = allocate_normalized(&empty);
        assert!(result.rows.is_empty());
        assert_eq!(result.remainder, BigInt::from(5));

        let zero_weight = request(
            vec![row("row/a", 5, 0, None), row("row/b", 5, 0, None)],
            5,
            "unallocated",
        );
        let result = allocate_normalized(&zero_weight);
        assert_eq!(result.total_allocated, BigInt::zero());
        assert_eq!(result.remainder, BigInt::from(5));
    }

    #[test]
    fn arithmetic_supports_amounts_larger_than_machine_integers() {
        let huge = BigInt::from(1u8) << 100usize;
        let mut request = request(
            vec![row("row/a", 1, 1, None), row("row/b", 1, 1, None)],
            1,
            "unallocated",
        );
        request.available = huge.clone();
        request.rows.iter_mut().for_each(|row| {
            row.effective_cap = huge.clone();
            row.requested = huge.clone();
        });
        let result = allocate_normalized(&request);
        assert_eq!(result.total_allocated, huge);
        assert_eq!(result.remainder, BigInt::zero());
    }
}
