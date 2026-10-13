//! V1 quota and cap-redistribution witness construction.

use std::collections::HashMap;

use num_bigint::BigInt;
use num_traits::{One, ToPrimitive, Zero};
use prf_canonical_codec::CanonValue;

use crate::request::{NormalizedAllocationRequest, NormalizedAllocationRow};

#[derive(Clone, Debug, PartialEq)]
pub struct AllocationWitness {
    pub rows: Vec<CanonValue>,
    pub rounds: Vec<CanonValue>,
    pub residual_reason: CanonValue,
}

#[derive(Clone)]
struct Quota {
    numerator: BigInt,
    denominator: BigInt,
}

fn keyword(name: &str) -> CanonValue {
    CanonValue::keyword(name)
}

fn int(value: &BigInt) -> CanonValue {
    CanonValue::int(value.clone())
}

fn map(entries: Vec<(&str, CanonValue)>) -> CanonValue {
    CanonValue::map(
        entries
            .into_iter()
            .map(|(key, value)| (keyword(key), value))
            .collect(),
    )
}

fn row_weight_total(rows: &[NormalizedAllocationRow], indices: &[usize]) -> BigInt {
    indices.iter().map(|index| &rows[*index].weight).sum()
}

fn fraction_order(
    rows: &[NormalizedAllocationRow],
    fractions: &[(usize, BigInt, BigInt)],
) -> Vec<usize> {
    let mut order: Vec<_> = (0..fractions.len()).collect();
    order.sort_by(|left, right| {
        let (_, left_num, left_den) = &fractions[*left];
        let (_, right_num, right_den) = &fractions[*right];
        (right_num * left_den)
            .cmp(&(left_num * right_den))
            .then_with(|| {
                crate::request::compare_row_ids(
                    &rows[fractions[*left].0].row_id,
                    &rows[fractions[*right].0].row_id,
                )
            })
    });
    order
        .into_iter()
        .map(|position| fractions[position].0)
        .collect()
}

fn normalized_row_fields(row: &NormalizedAllocationRow) -> Vec<(&'static str, CanonValue)> {
    vec![
        ("row/id", row.row_id.clone()),
        ("obligation/id", row.obligation_id.clone()),
        ("requested", int(&row.requested)),
        ("weight", int(&row.weight)),
        (
            "declared-cap",
            row.declared_cap
                .as_ref()
                .map(int)
                .unwrap_or(CanonValue::Null),
        ),
        ("effective-cap", int(&row.effective_cap)),
    ]
}

fn quota_value(quota: &Quota) -> CanonValue {
    map(vec![
        ("quota/numerator", int(&quota.numerator)),
        ("quota/denominator", int(&quota.denominator)),
    ])
}

fn round_value(
    round_index: usize,
    available: &BigInt,
    active: &[usize],
    rows: &[NormalizedAllocationRow],
    weight_total: &BigInt,
    constrained: &[usize],
    committed_amount: &BigInt,
) -> CanonValue {
    let quotas = active
        .iter()
        .map(|index| {
            map(vec![
                ("row/id", rows[*index].row_id.clone()),
                (
                    "quota/numerator",
                    int(&(&*available * &rows[*index].weight)),
                ),
                ("quota/denominator", int(weight_total)),
            ])
        })
        .collect();
    let constrained_ids = CanonValue::array(
        constrained
            .iter()
            .map(|index| rows[*index].row_id.clone())
            .collect(),
    );
    map(vec![
        ("round/index", CanonValue::int(round_index)),
        ("available-at-start", int(available)),
        (
            "active-row-ids",
            CanonValue::array(
                active
                    .iter()
                    .map(|index| rows[*index].row_id.clone())
                    .collect(),
            ),
        ),
        ("active-weight-total", int(weight_total)),
        ("quotas", CanonValue::array(quotas)),
        ("newly-cap-constrained-row-ids", constrained_ids),
        ("committed-by-cap", int(committed_amount)),
        ("available-after-caps", int(&(available - committed_amount))),
    ])
}

fn make_round(
    index: usize,
    available: &BigInt,
    active: &[usize],
    rows: &[NormalizedAllocationRow],
    weight_total: &BigInt,
    include_constraints: bool,
) -> (CanonValue, Vec<usize>, BigInt) {
    let constrained: Vec<_> = if include_constraints {
        active
            .iter()
            .copied()
            .filter(|row_index| {
                available * &rows[*row_index].weight
                    >= &rows[*row_index].effective_cap * weight_total
            })
            .collect()
    } else {
        Vec::new()
    };
    let committed_amount: BigInt = constrained
        .iter()
        .map(|row_index| &rows[*row_index].effective_cap)
        .sum();
    (
        round_value(
            index,
            available,
            active,
            rows,
            weight_total,
            &constrained,
            &committed_amount,
        ),
        constrained,
        committed_amount,
    )
}

/// Build the exact row and round projections used by the frozen Clojure V1
/// witness function.
pub fn build_witness(request: &NormalizedAllocationRequest) -> AllocationWitness {
    if request.redistribution_policy == keyword("unallocated") {
        return unallocated_witness(request);
    }
    redistributed_witness(request)
}

fn unallocated_witness(request: &NormalizedAllocationRequest) -> AllocationWitness {
    let rows = &request.rows;
    let available = &request.available;
    let active: Vec<_> = (0..rows.len()).collect();
    let weight_total = row_weight_total(rows, &active);
    if rows.is_empty() || weight_total.is_zero() {
        let denominator = weight_total.clone().max(BigInt::one());
        let witness_rows = rows
            .iter()
            .map(|row| {
                let quota = Quota {
                    numerator: available * &row.weight,
                    denominator: denominator.clone(),
                };
                let mut fields = normalized_row_fields(row);
                fields.extend([
                    ("initial-quota", quota_value(&quota)),
                    ("effective-quota", quota_value(&quota)),
                    ("floor-allocation", int(&BigInt::zero())),
                    (
                        "fractional-remainder",
                        map(vec![
                            ("remainder-numerator", int(&BigInt::zero())),
                            ("remainder-denominator", int(&denominator)),
                        ]),
                    ),
                    ("remainder-rank", CanonValue::Null),
                    ("remainder-unit-awarded?", CanonValue::bool(false)),
                ]);
                map(fields)
            })
            .collect();
        return AllocationWitness {
            rows: witness_rows,
            rounds: Vec::new(),
            residual_reason: keyword(if rows.is_empty() {
                "no-remaining-capacity"
            } else {
                "no-active-weight"
            }),
        };
    }

    let (round, constrained, _committed_amount) =
        make_round(0, available, &active, rows, &weight_total, true);
    let constrained_set: std::collections::HashSet<_> = constrained.iter().copied().collect();
    let floors: Vec<BigInt> = rows
        .iter()
        .map(|row| available * &row.weight / &weight_total)
        .collect();
    let remainder_count = if request.rounding_policy == keyword("largest-remainder") {
        (available - floors.iter().sum::<BigInt>())
            .to_usize()
            .unwrap_or(0)
    } else {
        0
    };
    let fractions: Vec<_> = rows
        .iter()
        .enumerate()
        .map(|(index, row)| {
            (
                index,
                available * &row.weight % &weight_total,
                weight_total.clone(),
            )
        })
        .collect();
    let ordered = fraction_order(rows, &fractions);
    let awarded: std::collections::HashSet<_> =
        ordered.iter().copied().take(remainder_count).collect();
    let eligible_order: Vec<_> = ordered
        .iter()
        .copied()
        .filter(|index| !constrained_set.contains(index))
        .collect();
    let ranks: HashMap<_, _> = eligible_order
        .iter()
        .enumerate()
        .map(|(rank, index)| (*index, rank))
        .collect();
    let witness_rows = rows
        .iter()
        .enumerate()
        .map(|(index, row)| {
            let committed = constrained_set.contains(&index);
            let remainder = if committed {
                BigInt::zero()
            } else {
                fractions[index].1.clone()
            };
            let floor = if committed {
                row.effective_cap.clone()
            } else {
                floors[index].clone()
            };
            let mut fields = normalized_row_fields(row);
            fields.extend([
                (
                    "initial-quota",
                    quota_value(&Quota {
                        numerator: available * &row.weight,
                        denominator: weight_total.clone(),
                    }),
                ),
                (
                    "effective-quota",
                    quota_value(&Quota {
                        numerator: available * &row.weight,
                        denominator: weight_total.clone(),
                    }),
                ),
                ("floor-allocation", int(&floor)),
                (
                    "fractional-remainder",
                    map(vec![
                        ("remainder-numerator", int(&remainder)),
                        ("remainder-denominator", int(&weight_total)),
                    ]),
                ),
                (
                    "remainder-rank",
                    ranks
                        .get(&index)
                        .map(|rank| CanonValue::int(*rank))
                        .unwrap_or(CanonValue::Null),
                ),
                (
                    "remainder-unit-awarded?",
                    CanonValue::bool(!committed && awarded.contains(&index)),
                ),
            ]);
            map(fields)
        })
        .collect();
    AllocationWitness {
        rows: witness_rows,
        rounds: vec![round],
        residual_reason: if request.rounding_policy == keyword("floor") {
            keyword("floor-rounding")
        } else {
            keyword("none")
        },
    }
}

fn redistributed_witness(request: &NormalizedAllocationRequest) -> AllocationWitness {
    let rows = &request.rows;
    let mut active: Vec<_> = (0..rows.len()).collect();
    let mut remaining = request.available.clone();
    let mut rounds = Vec::new();
    let mut committed: HashMap<usize, Quota> = HashMap::new();
    let mut final_weight_total = BigInt::zero();
    let residual_reason;

    loop {
        if active.is_empty() {
            residual_reason = if rows.is_empty() {
                "no-remaining-capacity"
            } else {
                "all-participants-capped"
            };
            break;
        }
        let weight_total = row_weight_total(rows, &active);
        if weight_total.is_zero() {
            final_weight_total = weight_total;
            residual_reason = "no-active-weight";
            break;
        }
        let (round, constrained, committed_amount) =
            make_round(rounds.len(), &remaining, &active, rows, &weight_total, true);
        if constrained.is_empty() {
            rounds.push(round);
            final_weight_total = weight_total;
            residual_reason = if request.rounding_policy == keyword("floor") {
                "floor-rounding"
            } else {
                "none"
            };
            break;
        }
        for index in &constrained {
            committed.insert(
                *index,
                Quota {
                    numerator: &remaining * &rows[*index].weight,
                    denominator: weight_total.clone(),
                },
            );
        }
        remaining -= committed_amount;
        rounds.push(round);
        let removed: std::collections::HashSet<_> = constrained.into_iter().collect();
        active.retain(|index| !removed.contains(index));
    }

    let denominator = if final_weight_total.is_zero() {
        BigInt::one()
    } else {
        final_weight_total.clone()
    };
    let floors: Vec<BigInt> = active
        .iter()
        .map(|index| &remaining * &rows[*index].weight / &denominator)
        .collect();
    let remainder_count = if request.rounding_policy == keyword("largest-remainder")
        && !final_weight_total.is_zero()
    {
        (&remaining - floors.iter().sum::<BigInt>())
            .to_usize()
            .unwrap_or(0)
    } else {
        0
    };
    let fractions: Vec<_> = active
        .iter()
        .enumerate()
        .map(|(_, index)| {
            (
                *index,
                &remaining * &rows[*index].weight % &denominator,
                denominator.clone(),
            )
        })
        .collect();
    let order = fraction_order(rows, &fractions);
    let awarded: std::collections::HashSet<_> =
        order.iter().copied().take(remainder_count).collect();
    let ranks: HashMap<_, _> = order
        .iter()
        .enumerate()
        .map(|(rank, index)| (*index, rank))
        .collect();
    let active_positions: HashMap<_, _> = active
        .iter()
        .enumerate()
        .map(|(position, index)| (*index, position))
        .collect();

    let witness_rows = rows
        .iter()
        .enumerate()
        .map(|(index, row)| {
            let initial_denominator =
                row_weight_total(rows, &(0..rows.len()).collect::<Vec<_>>()).max(BigInt::one());
            let initial_quota = Quota {
                numerator: &request.available * &row.weight,
                denominator: initial_denominator,
            };
            let (quota, floor, remainder, rank, awarded_unit) =
                if let Some(committed_quota) = committed.get(&index) {
                    (
                        committed_quota.clone(),
                        row.effective_cap.clone(),
                        BigInt::zero(),
                        None,
                        false,
                    )
                } else if let Some(position) = active_positions.get(&index) {
                    let quota = Quota {
                        numerator: &remaining * &row.weight,
                        denominator: denominator.clone(),
                    };
                    let remainder = fractions[*position].1.clone();
                    (
                        quota,
                        floors[*position].clone(),
                        remainder,
                        ranks.get(&index).copied(),
                        awarded.contains(&index),
                    )
                } else {
                    (
                        initial_quota.clone(),
                        BigInt::zero(),
                        BigInt::zero(),
                        None,
                        false,
                    )
                };
            let mut fields = normalized_row_fields(row);
            fields.extend([
                ("initial-quota", quota_value(&initial_quota)),
                ("effective-quota", quota_value(&quota)),
                ("floor-allocation", int(&floor)),
                (
                    "fractional-remainder",
                    map(vec![
                        ("remainder-numerator", int(&remainder)),
                        ("remainder-denominator", int(&quota.denominator)),
                    ]),
                ),
                (
                    "remainder-rank",
                    rank.map(CanonValue::int).unwrap_or(CanonValue::Null),
                ),
                ("remainder-unit-awarded?", CanonValue::bool(awarded_unit)),
            ]);
            map(fields)
        })
        .collect();
    AllocationWitness {
        rows: witness_rows,
        rounds,
        residual_reason: keyword(residual_reason),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::request::{normalize_request, AllocationRequestInput, AllocationRowInput};

    fn amount(value: i64) -> Option<CanonValue> {
        Some(CanonValue::int(value))
    }

    fn row(id: &str, cap: Option<i64>) -> AllocationRowInput {
        AllocationRowInput {
            row_id: Some(CanonValue::keyword(id)),
            obligation_id: Some(CanonValue::keyword(format!("ob/{id}"))),
            requested: amount(10),
            weight: amount(1),
            cap: cap.map(CanonValue::int),
        }
    }

    fn request(
        redistribution: &str,
        cap_a: Option<i64>,
        available: i64,
    ) -> NormalizedAllocationRequest {
        normalize_request(&AllocationRequestInput {
            allocation_id: Some(CanonValue::keyword("allocation/test")),
            schema_version: None,
            mechanism_version: None,
            available: amount(available),
            rows: vec![row("row/b", Some(10)), row("row/a", cap_a)],
            rounding_policy: None,
            tie_break_policy: None,
            redistribution_policy: Some(CanonValue::keyword(redistribution)),
        })
        .unwrap()
    }

    fn get<'a>(value: &'a CanonValue, key: &str) -> &'a CanonValue {
        let CanonValue::Map(pairs) = value else {
            panic!("expected map")
        };
        pairs
            .iter()
            .find_map(|(candidate, value)| {
                matches!(candidate, CanonValue::Keyword(name) if name == key).then_some(value)
            })
            .unwrap_or_else(|| panic!("missing key {key}"))
    }

    #[test]
    fn unallocated_witness_matches_frozen_row_quota_and_remainder_fields() {
        let request = request("unallocated", None, 5);
        let witness = build_witness(&request);
        assert_eq!(witness.rows.len(), 2);
        assert_eq!(witness.rounds.len(), 1);
        assert_eq!(
            get(&witness.rows[0], "row/id"),
            &CanonValue::keyword("row/a")
        );
        assert_eq!(
            get(&witness.rows[0], "initial-quota"),
            &map(vec![
                ("quota/numerator", CanonValue::int(5)),
                ("quota/denominator", CanonValue::int(2)),
            ])
        );
        assert_eq!(get(&witness.rows[0], "remainder-rank"), &CanonValue::int(0));
        assert_eq!(
            get(&witness.rows[0], "remainder-unit-awarded?"),
            &CanonValue::bool(true)
        );
        assert_eq!(
            get(&witness.rounds[0], "active-row-ids"),
            &CanonValue::array(vec![
                CanonValue::keyword("row/a"),
                CanonValue::keyword("row/b")
            ])
        );
        assert_eq!(witness.residual_reason, keyword("none"));
    }

    #[test]
    fn redistributed_witness_commits_caps_then_builds_survivor_round() {
        let witness = build_witness(&request("redistribute-cap-excess", Some(2), 10));
        assert_eq!(witness.rounds.len(), 2);
        assert_eq!(
            get(&witness.rounds[0], "newly-cap-constrained-row-ids"),
            &CanonValue::array(vec![CanonValue::keyword("row/a")])
        );
        assert_eq!(
            get(&witness.rounds[0], "committed-by-cap"),
            &CanonValue::int(2)
        );
        assert_eq!(
            get(&witness.rounds[1], "available-at-start"),
            &CanonValue::int(8)
        );
        assert_eq!(
            get(&witness.rows[0], "floor-allocation"),
            &CanonValue::int(2)
        );
        assert_eq!(
            get(&witness.rows[1], "floor-allocation"),
            &CanonValue::int(8)
        );
        assert_eq!(witness.residual_reason, keyword("none"));
    }
}
