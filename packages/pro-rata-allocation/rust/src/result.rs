//! Canonical V1 allocation result projection and root derivation.

use num_bigint::BigInt;
use num_integer::Integer;
use num_traits::{One, Zero};
use prf_canonical_codec::{domain_hash, CanonValue};

use crate::allocation::AllocationOutput;
use crate::request::NormalizedAllocationRequest;
use crate::witness::AllocationWitness;

pub const RESULT_SCHEMA: &str = "pro-rata-allocation-result.v1";
pub const RESULT_DOMAIN: &str = "PROJECTION_ARTIFACT_V1";

#[derive(Clone, Debug, PartialEq)]
pub struct CanonicalAllocationResult {
    /// The canonical result including the diagnostic fractional remainder sum.
    pub value: CanonValue,
    /// Frozen root over `root_input`, which excludes the diagnostic sum.
    pub root: String,
}

fn keyword(key: &str) -> CanonValue {
    CanonValue::keyword(key)
}

fn map(entries: Vec<(&str, CanonValue)>) -> CanonValue {
    CanonValue::map(
        entries
            .into_iter()
            .map(|(key, value)| (keyword(key), value))
            .collect(),
    )
}

fn value<'a>(value: &'a CanonValue, field: &str) -> Option<&'a CanonValue> {
    let CanonValue::Map(pairs) = value else {
        return None;
    };
    pairs.iter().find_map(|(key, value)| {
        matches!(key, CanonValue::Keyword(name) if name == field).then_some(value)
    })
}

fn result_row_value(witness: &CanonValue, allocated: &BigInt, unmet: &BigInt) -> CanonValue {
    let mut fields = match witness {
        CanonValue::Map(fields) => fields.clone(),
        _ => unreachable!("witness rows are canonical maps"),
    };
    fields.extend([
        (keyword("allocated"), CanonValue::int(allocated.clone())),
        (keyword("unmet"), CanonValue::int(unmet.clone())),
    ]);
    CanonValue::map(fields)
}

fn rational_remainder_total(rows: &[CanonValue]) -> CanonValue {
    let (numerator, denominator) = rows.iter().fold(
        (BigInt::zero(), BigInt::one()),
        |(total_numerator, total_denominator), row| {
            let Some(fraction) = value(row, "fractional-remainder") else {
                return (total_numerator, total_denominator);
            };
            let Some(CanonValue::Int(numerator)) = value(fraction, "remainder-numerator") else {
                return (total_numerator, total_denominator);
            };
            let Some(CanonValue::Int(denominator)) = value(fraction, "remainder-denominator")
            else {
                return (total_numerator, total_denominator);
            };
            if denominator.is_zero() {
                return (total_numerator, total_denominator);
            }
            (
                total_numerator * denominator + numerator * &total_denominator,
                total_denominator * denominator,
            )
        },
    );
    let divisor = numerator.gcd(&denominator);
    map(vec![
        ("rational/numerator", CanonValue::int(&numerator / &divisor)),
        (
            "rational/denominator",
            CanonValue::int(&denominator / divisor),
        ),
    ])
}

fn distribution_summary(witness: &AllocationWitness) -> CanonValue {
    if witness.rounds.is_empty() {
        return CanonValue::Null;
    }
    let passes = witness
        .rounds
        .iter()
        .map(|round| {
            let active = value(round, "active-row-ids")
                .cloned()
                .unwrap_or_else(|| CanonValue::array(Vec::new()));
            let capped = value(round, "newly-cap-constrained-row-ids")
                .cloned()
                .unwrap_or_else(|| CanonValue::array(Vec::new()));
            map(vec![
                (
                    "pass",
                    value(round, "round/index")
                        .cloned()
                        .unwrap_or_else(|| CanonValue::int(0)),
                ),
                (
                    "available-at-start",
                    value(round, "available-at-start")
                        .cloned()
                        .unwrap_or_else(|| CanonValue::int(0)),
                ),
                ("active-ids", active.clone()),
                (
                    "active-weight-total",
                    value(round, "active-weight-total")
                        .cloned()
                        .unwrap_or_else(|| CanonValue::int(0)),
                ),
                ("capped-ids", capped.clone()),
                (
                    "excess",
                    value(round, "available-at-start")
                        .cloned()
                        .unwrap_or_else(|| CanonValue::int(0)),
                ),
                ("newly-capped-ids", capped),
                (
                    "committed-by-cap",
                    value(round, "committed-by-cap")
                        .cloned()
                        .unwrap_or_else(|| CanonValue::int(0)),
                ),
                (
                    "available-after-caps",
                    value(round, "available-after-caps")
                        .cloned()
                        .unwrap_or_else(|| CanonValue::int(0)),
                ),
            ])
        })
        .collect();
    map(vec![
        ("passes", CanonValue::array(passes)),
        ("total-passes", CanonValue::int(witness.rounds.len())),
        (
            "residual-reason",
            if witness.residual_reason == keyword("all-participants-capped") {
                keyword("no-remaining-capacity")
            } else {
                witness.residual_reason.clone()
            },
        ),
    ])
}

fn root_input(result: &CanonValue) -> CanonValue {
    let CanonValue::Map(fields) = result else {
        return result.clone();
    };
    CanonValue::map(
        fields
            .iter()
            .filter(|(key, _)| {
                !matches!(key, CanonValue::Keyword(name) if name == "allocation/hash" || name == "allocation/fractional-remainder-total")
            })
            .cloned()
            .collect(),
    )
}

/// Assemble the frozen result map and its `PROJECTION_ARTIFACT_V1` root.
pub fn assemble_result(
    request: &NormalizedAllocationRequest,
    request_root: &str,
    allocation: &AllocationOutput,
    witness: &AllocationWitness,
) -> CanonicalAllocationResult {
    let rows: Vec<_> = request
        .rows
        .iter()
        .zip(witness.rows.iter())
        .zip(allocation.rows.iter())
        .map(|((_normalized, witness), allocated)| {
            result_row_value(witness, &allocated.allocated, &allocated.unmet)
        })
        .collect();
    let remainder_reason = if allocation.remainder > BigInt::zero() {
        witness.residual_reason.clone()
    } else {
        keyword("none")
    };
    let redistribution = if request.redistribution_policy == keyword("unallocated") {
        CanonValue::Null
    } else {
        distribution_summary(witness)
    };
    let value = map(vec![
        ("schema-version", CanonValue::str(RESULT_SCHEMA)),
        (
            "mechanism",
            map(vec![
                ("id", keyword("mechanism/pro-rata-allocation")),
                ("version", CanonValue::int(1)),
            ]),
        ),
        ("allocation/id", request.allocation_id.clone()),
        ("canonical-request", request.canonical_request.clone()),
        ("request/hash", CanonValue::str(request_root)),
        ("available", CanonValue::int(request.available.clone())),
        (
            "allocated-total",
            CanonValue::int(allocation.total_allocated.clone()),
        ),
        (
            "unallocated-residual",
            CanonValue::int(allocation.remainder.clone()),
        ),
        ("rounding-policy", request.rounding_policy.clone()),
        ("tie-break-policy", request.tie_break_policy.clone()),
        (
            "redistribution-policy",
            request.redistribution_policy.clone(),
        ),
        ("redistribution", redistribution),
        (
            "allocation/rounds",
            CanonValue::array(witness.rounds.clone()),
        ),
        ("residual-reason", remainder_reason),
        ("rows", CanonValue::array(rows.clone())),
        (
            "allocation/fractional-remainder-total",
            rational_remainder_total(&rows),
        ),
    ]);
    let root = domain_hash(RESULT_DOMAIN, &root_input(&value));
    CanonicalAllocationResult { value, root }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::allocation::allocate_normalized;
    use crate::request::{
        allocation_request_root, normalize_request, AllocationRequestInput, AllocationRowInput,
    };
    use crate::witness::build_witness;

    fn row(id: &str, obligation: &str, requested: i64, cap: Option<i64>) -> AllocationRowInput {
        AllocationRowInput {
            row_id: Some(CanonValue::keyword(id)),
            obligation_id: Some(CanonValue::keyword(obligation)),
            requested: Some(CanonValue::int(requested)),
            weight: Some(CanonValue::int(1)),
            cap: cap.map(CanonValue::int),
        }
    }

    fn check_vector(
        allocation_id: &str,
        available: i64,
        rounding: &str,
        redistribution: &str,
        rows: Vec<AllocationRowInput>,
        expected_root: &str,
    ) {
        let request = normalize_request(&AllocationRequestInput {
            allocation_id: Some(CanonValue::keyword(allocation_id)),
            schema_version: None,
            mechanism_version: None,
            available: Some(CanonValue::int(available)),
            rows,
            rounding_policy: Some(keyword(rounding)),
            tie_break_policy: None,
            redistribution_policy: Some(keyword(redistribution)),
        })
        .unwrap();
        let request_root = allocation_request_root(&request.canonical_request);
        let allocation = allocate_normalized(&request);
        let witness = build_witness(&request);
        let result = assemble_result(&request, &request_root, &allocation, &witness);
        assert_eq!(result.root, expected_root);
    }

    #[test]
    fn result_roots_match_frozen_allocation_vectors() {
        check_vector(
            "portable/rounding",
            5,
            "largest-remainder",
            "unallocated",
            vec![row("row/a", "ob/a", 5, None), row("row/b", "ob/b", 5, None)],
            "7ca6b7b57766a0c2ec92058f0a6fcbc2ff23c01de93904593839d8a449a48454",
        );
        check_vector(
            "cap-clamping-001",
            10,
            "largest-remainder",
            "unallocated",
            vec![
                row("row/b", "ob/b", 10, Some(10)),
                row("row/a", "ob/a", 10, Some(2)),
            ],
            "9b1ce7425ade7f815793414eecdcd073f8db024c960d550253c852fd9be1e996",
        );
        check_vector(
            "redistribute-cap-excess-001",
            10,
            "largest-remainder",
            "redistribute-cap-excess",
            vec![
                row("row/b", "ob/b", 10, Some(10)),
                row("row/a", "ob/a", 10, Some(2)),
            ],
            "96917b24fba42389d7c535546a99b994f354bb4208d45f5dae05f38b835c06de",
        );
        check_vector(
            "floor-residual-001",
            5,
            "floor",
            "unallocated",
            vec![row("row/b", "ob/b", 5, None), row("row/a", "ob/a", 5, None)],
            "cddb502c6577a1742c029de2f1bfe1e6ef8dedc8491f870c5935c228edab12b8",
        );
    }
}
