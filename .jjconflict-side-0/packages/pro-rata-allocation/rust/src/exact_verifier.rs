//! Independent exact verification of weighted pro-rata claims.
//!
//! This module reconstructs the expected result from the request and compares
//! a caller-supplied claim. It deliberately does not call the allocation
//! producer, witness builder, or result assembler.

use std::collections::{HashMap, HashSet};

use num_bigint::BigInt;
use num_traits::{ToPrimitive, Zero};
use prf_canonical_codec::{domain_hash, CanonValue};

/// Domain separator for the exact-verification profile's public input root.
/// This commits a request and claim as proof inputs; it is not an allocation
/// artifact identity or a replacement for any existing V1 root.
pub const EXACT_VERIFICATION_INPUT_DOMAIN: &str = "PRF_PRO_RATA_EXACT_VERIFICATION_INPUT_V1";

pub const FAIRNESS_SPEC_ROOT: &str =
    "e0650cf27f9ba13f2c1799ad0ee09ba79214edd1b80a625b5e63f75a7bc5c7f4";
pub const CONFORMANCE_CORPUS_ROOT: &str =
    "a6d723b27f154d6981cc41a7837ef7a9278ed3265eb98e4a480871845c5b97c1";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RoundingPolicy {
    Floor,
    LargestRemainder,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OrderingPolicy {
    InputOrder,
    CanonicalId,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CapTreatment {
    Unallocated,
    Redistribute,
}

#[derive(Clone, Debug, PartialEq)]
pub struct VerificationItem {
    pub id: CanonValue,
    pub weight: BigInt,
    pub cap: Option<BigInt>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct VerificationRequest {
    pub amount: BigInt,
    pub items: Vec<VerificationItem>,
    pub rounding: RoundingPolicy,
    pub ordering: OrderingPolicy,
    pub cap_treatment: CapTreatment,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ClaimedAllocation {
    pub id: CanonValue,
    pub allocated: BigInt,
}

#[derive(Clone, Debug, PartialEq)]
pub struct VerificationClaim {
    pub allocations: Vec<ClaimedAllocation>,
    pub total_allocated: BigInt,
    pub total_unmet: BigInt,
    pub remainder: BigInt,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ReconstructedAllocation {
    pub id: CanonValue,
    pub allocated: BigInt,
    pub unmet: BigInt,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ReconstructedResult {
    pub allocations: Vec<ReconstructedAllocation>,
    pub total_allocated: BigInt,
    pub total_unmet: BigInt,
    pub remainder: BigInt,
    /// Ordered groups committed by cap in successive redistribution rounds.
    pub redistribution_passes: Vec<Vec<CanonValue>>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum VerificationError {
    NegativeAmount,
    NegativeWeight,
    NegativeCap,
    DuplicateId,
}

fn request_value(request: &VerificationRequest) -> CanonValue {
    let rounding = match request.rounding {
        RoundingPolicy::Floor => "floor",
        RoundingPolicy::LargestRemainder => "floor-with-largest-remainder",
    };
    let ordering = match request.ordering {
        OrderingPolicy::InputOrder => "input-order",
        OrderingPolicy::CanonicalId => "canonical-id",
    };
    let cap_treatment = match request.cap_treatment {
        CapTreatment::Unallocated => "unallocated",
        CapTreatment::Redistribute => "redistribute",
    };
    CanonValue::map(vec![
        (
            CanonValue::keyword("amount"),
            CanonValue::int(request.amount.clone()),
        ),
        (
            CanonValue::keyword("items"),
            CanonValue::array(
                request
                    .items
                    .iter()
                    .map(|item| {
                        CanonValue::map(vec![
                            (CanonValue::keyword("id"), item.id.clone()),
                            (
                                CanonValue::keyword("weight"),
                                CanonValue::int(item.weight.clone()),
                            ),
                            (
                                CanonValue::keyword("cap"),
                                item.cap
                                    .clone()
                                    .map(CanonValue::int)
                                    .unwrap_or(CanonValue::Null),
                            ),
                        ])
                    })
                    .collect(),
            ),
        ),
        (
            CanonValue::keyword("rounding"),
            CanonValue::keyword(rounding),
        ),
        (
            CanonValue::keyword("ordering-policy"),
            CanonValue::keyword(ordering),
        ),
        (
            CanonValue::keyword("cap-treatment"),
            CanonValue::keyword(cap_treatment),
        ),
    ])
}

fn claim_value(claim: &VerificationClaim) -> CanonValue {
    let mut allocations = claim.allocations.iter().collect::<Vec<_>>();
    allocations.sort_by_key(|row| id_bytes(&row.id));
    CanonValue::map(vec![
        (
            CanonValue::keyword("allocations"),
            CanonValue::array(
                allocations
                    .into_iter()
                    .map(|row| {
                        CanonValue::map(vec![
                            (CanonValue::keyword("id"), row.id.clone()),
                            (
                                CanonValue::keyword("allocated"),
                                CanonValue::int(row.allocated.clone()),
                            ),
                        ])
                    })
                    .collect(),
            ),
        ),
        (
            CanonValue::keyword("total-allocated"),
            CanonValue::int(claim.total_allocated.clone()),
        ),
        (
            CanonValue::keyword("total-unmet"),
            CanonValue::int(claim.total_unmet.clone()),
        ),
        (
            CanonValue::keyword("remainder"),
            CanonValue::int(claim.remainder.clone()),
        ),
    ])
}

/// Canonical proof-input commitment for the exact-verification SP1 profile.
/// It is stable across claim-row ordering because the verifier compares claims
/// by canonical ID. Request item order remains committed because input-order
/// allocation semantics depend on it.
pub fn exact_verification_input_root(
    request: &VerificationRequest,
    claim: &VerificationClaim,
) -> String {
    let preimage = CanonValue::map(vec![
        (CanonValue::keyword("request"), request_value(request)),
        (CanonValue::keyword("claim"), claim_value(claim)),
    ]);
    domain_hash(EXACT_VERIFICATION_INPUT_DOMAIN, &preimage)
}

fn id_bytes(id: &CanonValue) -> Vec<u8> {
    id.encode()
}

fn total_weight(items: &[VerificationItem], indices: &[usize]) -> BigInt {
    indices.iter().map(|index| &items[*index].weight).sum()
}

fn compare_tie(request: &VerificationRequest, left: usize, right: usize) -> std::cmp::Ordering {
    match request.ordering {
        OrderingPolicy::InputOrder => left.cmp(&right),
        OrderingPolicy::CanonicalId => {
            id_bytes(&request.items[left].id).cmp(&id_bytes(&request.items[right].id))
        }
    }
}

/// Derive exact integer shares for the selected rows.
fn shares(
    request: &VerificationRequest,
    amount: &BigInt,
    indices: &[usize],
    rounding: RoundingPolicy,
) -> Vec<BigInt> {
    let weight_sum = total_weight(&request.items, indices);
    if weight_sum.is_zero() || amount.is_zero() {
        return vec![BigInt::zero(); indices.len()];
    }

    let mut floors = Vec::with_capacity(indices.len());
    let mut fractions = Vec::with_capacity(indices.len());
    for index in indices {
        let numerator = amount * &request.items[*index].weight;
        floors.push(&numerator / &weight_sum);
        fractions.push(numerator % &weight_sum);
    }
    if rounding == RoundingPolicy::Floor {
        return floors;
    }

    let units = (amount - floors.iter().sum::<BigInt>())
        .to_usize()
        .expect("largest-remainder units are bounded by the number of rows");
    let mut ranking: Vec<usize> = (0..indices.len()).collect();
    ranking.sort_by(|left, right| {
        fractions[*right]
            .cmp(&fractions[*left])
            .then_with(|| compare_tie(request, indices[*left], indices[*right]))
    });
    let awarded: HashSet<usize> = ranking.into_iter().take(units).collect();
    floors
        .into_iter()
        .enumerate()
        .map(|(position, floor)| {
            if awarded.contains(&position) {
                floor + BigInt::from(1)
            } else {
                floor
            }
        })
        .collect()
}

fn validate(request: &VerificationRequest) -> Result<(), VerificationError> {
    if request.amount < BigInt::zero() {
        return Err(VerificationError::NegativeAmount);
    }
    let mut ids = HashSet::with_capacity(request.items.len());
    for item in &request.items {
        if item.weight < BigInt::zero() {
            return Err(VerificationError::NegativeWeight);
        }
        if item.cap.as_ref().is_some_and(|cap| cap < &BigInt::zero()) {
            return Err(VerificationError::NegativeCap);
        }
        if !ids.insert(id_bytes(&item.id)) {
            return Err(VerificationError::DuplicateId);
        }
    }
    Ok(())
}

/// Independently reconstruct the expected allocations and conservation totals.
pub fn reconstruct(
    request: &VerificationRequest,
) -> Result<ReconstructedResult, VerificationError> {
    validate(request)?;
    let indices: Vec<usize> = (0..request.items.len()).collect();
    let mut allocated = vec![BigInt::zero(); request.items.len()];
    let mut unmet = vec![BigInt::zero(); request.items.len()];
    let mut remaining = request.amount.clone();
    let mut passes = Vec::new();

    match request.cap_treatment {
        CapTreatment::Unallocated => {
            let quotas = shares(request, &remaining, &indices, request.rounding);
            for (index, quota) in indices.iter().zip(quotas) {
                allocated[*index] = request.items[*index]
                    .cap
                    .as_ref()
                    .map_or(quota.clone(), |cap| quota.clone().min(cap.clone()));
                unmet[*index] = quota - &allocated[*index];
            }
        }
        CapTreatment::Redistribute => {
            let mut active = indices;
            let mut committed = Vec::new();
            loop {
                if active.is_empty() {
                    if !committed.is_empty() {
                        let residual_unmet = shares(
                            request,
                            &remaining,
                            &committed,
                            RoundingPolicy::LargestRemainder,
                        );
                        for (index, amount) in committed.iter().zip(residual_unmet) {
                            unmet[*index] = amount;
                        }
                    }
                    break;
                }

                let weight_sum = total_weight(&request.items, &active);
                if weight_sum.is_zero() {
                    break;
                }

                let constrained: Vec<usize> = active
                    .iter()
                    .copied()
                    .filter(|index| {
                        request.items[*index].cap.as_ref().is_some_and(|cap| {
                            &remaining * &request.items[*index].weight >= cap * &weight_sum
                        })
                    })
                    .collect();

                if !constrained.is_empty() {
                    for index in &constrained {
                        let cap = request.items[*index]
                            .cap
                            .as_ref()
                            .expect("constrained rows have a cap");
                        allocated[*index] = cap.clone();
                        remaining -= cap;
                    }
                    passes.push(
                        constrained
                            .iter()
                            .map(|index| request.items[*index].id.clone())
                            .collect(),
                    );
                    let constrained_set: HashSet<usize> = constrained.iter().copied().collect();
                    committed.extend(constrained);
                    active.retain(|index| !constrained_set.contains(index));
                    continue;
                }

                let final_shares = shares(request, &remaining, &active, request.rounding);
                for (index, amount) in active.iter().zip(final_shares) {
                    allocated[*index] = amount;
                }
                break;
            }
        }
    }

    let allocations: Vec<ReconstructedAllocation> = request
        .items
        .iter()
        .enumerate()
        .map(|(index, item)| ReconstructedAllocation {
            id: item.id.clone(),
            allocated: allocated[index].clone(),
            unmet: unmet[index].clone(),
        })
        .collect();
    let total_allocated = allocated.iter().sum();
    let total_unmet = unmet.iter().sum();
    let remainder = &request.amount - &total_allocated - &total_unmet;

    Ok(ReconstructedResult {
        allocations,
        total_allocated,
        total_unmet,
        remainder,
        redistribution_passes: passes,
    })
}

/// Compare a claim against an independent reconstruction.
///
/// Extra claim fields and any supplied intermediate witness are ignored: the
/// request determines the expected result, and only claimed allocations and
/// totals participate in this comparison contract.
pub fn verify(
    request: &VerificationRequest,
    claim: &VerificationClaim,
) -> Result<bool, VerificationError> {
    let expected = reconstruct(request)?;
    let expected_by_id: HashMap<Vec<u8>, &BigInt> = expected
        .allocations
        .iter()
        .map(|row| (id_bytes(&row.id), &row.allocated))
        .collect();
    let mut claimed_by_id = HashMap::with_capacity(claim.allocations.len());
    for row in &claim.allocations {
        if claimed_by_id
            .insert(id_bytes(&row.id), &row.allocated)
            .is_some()
        {
            return Ok(false);
        }
    }
    let allocations_match = expected_by_id.len() == claimed_by_id.len()
        && expected_by_id.iter().all(|(id, value)| {
            claimed_by_id
                .get(id)
                .is_some_and(|claimed| *claimed == *value)
        });
    Ok(allocations_match
        && claim.total_allocated == expected.total_allocated
        && claim.total_unmet == expected.total_unmet
        && claim.remainder == expected.remainder)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn kw(value: &str) -> CanonValue {
        CanonValue::keyword(value)
    }

    fn int(value: impl Into<BigInt>) -> CanonValue {
        CanonValue::int(value)
    }

    fn map(entries: Vec<(&str, CanonValue)>) -> CanonValue {
        CanonValue::map(
            entries
                .into_iter()
                .map(|(key, value)| (kw(key), value))
                .collect(),
        )
    }

    fn edge_item(id: &str, weight: i64, cap: Option<i64>) -> CanonValue {
        map(vec![
            ("id", kw(id)),
            ("weight", int(weight)),
            ("cap", cap.map(int).unwrap_or(CanonValue::Null)),
        ])
    }

    fn edge_case(
        id: &str,
        amount: BigInt,
        items: Vec<CanonValue>,
        ordering: &str,
        cap_treatment: &str,
        allocated: Vec<(&str, BigInt)>,
        unmet: Vec<(&str, BigInt)>,
        remainder: BigInt,
        passes: Vec<Vec<&str>>,
    ) -> CanonValue {
        let map_bigints = |values: Vec<(&str, BigInt)>| {
            CanonValue::map(
                values
                    .into_iter()
                    .map(|(id, value)| (kw(id), int(value)))
                    .collect(),
            )
        };
        map(vec![
            ("case/id", kw(id)),
            (
                "request",
                map(vec![
                    ("amount", int(amount)),
                    ("items", CanonValue::array(items)),
                    ("rounding", kw("floor-with-largest-remainder")),
                    ("ordering-policy", kw(ordering)),
                    ("cap-treatment", kw(cap_treatment)),
                ]),
            ),
            (
                "expected",
                map(vec![
                    ("allocated", map_bigints(allocated)),
                    ("unmet", map_bigints(unmet)),
                    ("remainder", int(remainder)),
                    (
                        "passes",
                        CanonValue::array(
                            passes
                                .into_iter()
                                .map(|ids| CanonValue::array(ids.into_iter().map(kw).collect()))
                                .collect(),
                        ),
                    ),
                ]),
            ),
        ])
    }

    fn normative_fairness_spec() -> CanonValue {
        map(vec![
            ("spec/id", CanonValue::str("pro-rata-exact-verification.v1")),
            (
                "numeric-domain",
                kw("non-negative-arbitrary-precision-integer"),
            ),
            (
                "rounding/policies",
                CanonValue::array(vec![kw("floor"), kw("floor-with-largest-remainder")]),
            ),
            (
                "ordering/policies",
                CanonValue::array(vec![kw("input-order"), kw("canonical-id")]),
            ),
            ("ordering/canonical-key", kw("canonical-encoded-id")),
            (
                "cap-treatment/policies",
                CanonValue::array(vec![kw("unallocated"), kw("redistribute")]),
            ),
            (
                "redistribute/constrained-condition",
                kw("remaining-times-weight-gte-cap-times-active-weight-total"),
            ),
            (
                "redistribute/iteration",
                kw("commit-all-constrained-rows-then-repeat-to-fixed-point"),
            ),
            (
                "all-zero-weight/outcome",
                kw("zero-awards-preserve-amount-as-remainder"),
            ),
            (
                "committed-residual/outcome",
                kw("largest-remainder-unmet-by-selected-order"),
            ),
            (
                "claim/comparison-fields",
                CanonValue::array(vec![
                    kw("allocated-by-id"),
                    kw("total-allocated"),
                    kw("total-unmet"),
                    kw("remainder"),
                ]),
            ),
        ])
    }

    fn normative_edge_corpus() -> CanonValue {
        let huge = BigInt::from(1u8) << 100usize;
        CanonValue::array(vec![
            edge_case(
                "zero-weights",
                11.into(),
                vec![edge_item("a", 0, None), edge_item("b", 0, None)],
                "input-order",
                "unallocated",
                vec![("a", 0.into()), ("b", 0.into())],
                vec![("a", 0.into()), ("b", 0.into())],
                11.into(),
                vec![],
            ),
            edge_case(
                "canonical-equal-remainder-one-unit",
                1.into(),
                vec![edge_item("z", 1, None), edge_item("a", 1, None)],
                "canonical-id",
                "unallocated",
                vec![("a", 1.into()), ("z", 0.into())],
                vec![("a", 0.into()), ("z", 0.into())],
                0.into(),
                vec![],
            ),
            edge_case(
                "zero-cap-redistributed",
                10.into(),
                vec![edge_item("a", 1, Some(0)), edge_item("b", 1, None)],
                "input-order",
                "redistribute",
                vec![("a", 0.into()), ("b", 10.into())],
                vec![("a", 0.into()), ("b", 0.into())],
                0.into(),
                vec![vec!["a"]],
            ),
            edge_case(
                "all-capped-residual",
                10.into(),
                vec![edge_item("a", 1, Some(2)), edge_item("b", 1, Some(3))],
                "input-order",
                "redistribute",
                vec![("a", 2.into()), ("b", 3.into())],
                vec![("a", 3.into()), ("b", 2.into())],
                0.into(),
                vec![vec!["a", "b"]],
            ),
            edge_case(
                "three-cap-commitment-rounds",
                100.into(),
                vec![
                    edge_item("a", 80, Some(70)),
                    edge_item("b", 15, Some(20)),
                    edge_item("c", 4, Some(7)),
                    edge_item("d", 1, None),
                ],
                "input-order",
                "redistribute",
                vec![
                    ("a", 70.into()),
                    ("b", 20.into()),
                    ("c", 7.into()),
                    ("d", 3.into()),
                ],
                vec![
                    ("a", 0.into()),
                    ("b", 0.into()),
                    ("c", 0.into()),
                    ("d", 0.into()),
                ],
                0.into(),
                vec![vec!["a"], vec!["b"], vec!["c"]],
            ),
            edge_case(
                "arbitrary-precision-amount",
                huge.clone(),
                vec![edge_item("a", 1, None), edge_item("b", 1, None)],
                "input-order",
                "unallocated",
                vec![("a", &huge / 2), ("b", &huge / 2)],
                vec![("a", 0.into()), ("b", 0.into())],
                0.into(),
                vec![],
            ),
        ])
    }

    fn item(id: &str, weight: i64, cap: Option<i64>) -> VerificationItem {
        VerificationItem {
            id: CanonValue::keyword(id),
            weight: BigInt::from(weight),
            cap: cap.map(BigInt::from),
        }
    }

    fn request(
        amount: BigInt,
        items: Vec<VerificationItem>,
        rounding: RoundingPolicy,
        ordering: OrderingPolicy,
        cap_treatment: CapTreatment,
    ) -> VerificationRequest {
        VerificationRequest {
            amount,
            items,
            rounding,
            ordering,
            cap_treatment,
        }
    }

    fn assert_rows(result: &ReconstructedResult, expected: &[(&str, i64, i64)]) {
        let actual: Vec<_> = result
            .allocations
            .iter()
            .map(|row| {
                let CanonValue::Keyword(id) = &row.id else {
                    panic!("expected keyword ID")
                };
                (
                    id.as_str(),
                    row.allocated.to_i64().unwrap(),
                    row.unmet.to_i64().unwrap(),
                )
            })
            .collect();
        assert_eq!(actual, expected);
    }

    #[test]
    fn zero_weights_preserve_the_entire_unallocated_residual() {
        let request = request(
            11.into(),
            vec![item("a", 0, None), item("b", 0, None)],
            RoundingPolicy::LargestRemainder,
            OrderingPolicy::InputOrder,
            CapTreatment::Unallocated,
        );
        let result = reconstruct(&request).unwrap();
        assert_rows(&result, &[("a", 0, 0), ("b", 0, 0)]);
        assert_eq!(result.remainder, 11.into());
    }

    #[test]
    fn canonical_id_breaks_a_one_unit_remainder_tie() {
        let request = request(
            1.into(),
            vec![item("z", 1, None), item("a", 1, None)],
            RoundingPolicy::LargestRemainder,
            OrderingPolicy::CanonicalId,
            CapTreatment::Unallocated,
        );
        let result = reconstruct(&request).unwrap();
        assert_rows(&result, &[("z", 0, 0), ("a", 1, 0)]);
    }

    #[test]
    fn zero_cap_is_committed_then_redistributed() {
        let request = request(
            10.into(),
            vec![item("a", 1, Some(0)), item("b", 1, None)],
            RoundingPolicy::LargestRemainder,
            OrderingPolicy::InputOrder,
            CapTreatment::Redistribute,
        );
        let result = reconstruct(&request).unwrap();
        assert_rows(&result, &[("a", 0, 0), ("b", 10, 0)]);
        assert_eq!(
            result.redistribution_passes,
            vec![vec![CanonValue::keyword("a")]]
        );
    }

    #[test]
    fn all_capped_residual_becomes_unmet_in_input_order() {
        let request = request(
            10.into(),
            vec![item("a", 1, Some(2)), item("b", 1, Some(3))],
            RoundingPolicy::LargestRemainder,
            OrderingPolicy::InputOrder,
            CapTreatment::Redistribute,
        );
        let result = reconstruct(&request).unwrap();
        assert_rows(&result, &[("a", 2, 3), ("b", 3, 2)]);
        assert_eq!(result.remainder, 0.into());
    }

    #[test]
    fn three_cap_rounds_are_reconstructed_independently() {
        let request = request(
            100.into(),
            vec![
                item("a", 80, Some(70)),
                item("b", 15, Some(20)),
                item("c", 4, Some(7)),
                item("d", 1, None),
            ],
            RoundingPolicy::LargestRemainder,
            OrderingPolicy::InputOrder,
            CapTreatment::Redistribute,
        );
        let result = reconstruct(&request).unwrap();
        assert_rows(
            &result,
            &[("a", 70, 0), ("b", 20, 0), ("c", 7, 0), ("d", 3, 0)],
        );
        assert_eq!(
            result.redistribution_passes,
            vec![
                vec![CanonValue::keyword("a")],
                vec![CanonValue::keyword("b")],
                vec![CanonValue::keyword("c")],
            ]
        );
    }

    #[test]
    fn amount_arithmetic_is_arbitrary_precision() {
        let huge = BigInt::from(1u8) << 100usize;
        let request = request(
            huge.clone(),
            vec![item("a", 1, None), item("b", 1, None)],
            RoundingPolicy::LargestRemainder,
            OrderingPolicy::InputOrder,
            CapTreatment::Unallocated,
        );
        let result = reconstruct(&request).unwrap();
        let half = &huge / 2;
        assert_eq!(result.allocations[0].allocated, half);
        assert_eq!(result.allocations[1].allocated, huge / 2);
        assert_eq!(result.remainder, 0.into());
    }

    #[test]
    fn verification_rejects_an_altered_claim() {
        let request = request(
            7.into(),
            vec![item("a", 4, None), item("b", 4, None), item("c", 2, None)],
            RoundingPolicy::LargestRemainder,
            OrderingPolicy::InputOrder,
            CapTreatment::Unallocated,
        );
        let expected = reconstruct(&request).unwrap();
        let claim = VerificationClaim {
            allocations: expected
                .allocations
                .iter()
                .map(|row| ClaimedAllocation {
                    id: row.id.clone(),
                    allocated: row.allocated.clone(),
                })
                .collect(),
            total_allocated: expected.total_allocated.clone(),
            total_unmet: expected.total_unmet.clone(),
            remainder: expected.remainder.clone(),
        };
        assert!(verify(&request, &claim).unwrap());
        let mut altered = claim;
        altered.allocations[0].allocated += 1;
        assert!(!verify(&request, &altered).unwrap());
    }

    #[test]
    fn malformed_requests_fail_closed() {
        let negative = request(
            1.into(),
            vec![item("a", -1, None)],
            RoundingPolicy::Floor,
            OrderingPolicy::InputOrder,
            CapTreatment::Unallocated,
        );
        assert_eq!(
            reconstruct(&negative),
            Err(VerificationError::NegativeWeight)
        );

        let duplicate = request(
            1.into(),
            vec![item("a", 1, None), item("a", 2, None)],
            RoundingPolicy::Floor,
            OrderingPolicy::InputOrder,
            CapTreatment::Unallocated,
        );
        assert_eq!(reconstruct(&duplicate), Err(VerificationError::DuplicateId));
    }

    #[test]
    fn fairness_spec_and_supplemental_corpus_roots_match_clojure() {
        assert_eq!(
            domain_hash("PRF_PRO_RATA_FAIRNESS_SPEC_V1", &normative_fairness_spec()),
            "e0650cf27f9ba13f2c1799ad0ee09ba79214edd1b80a625b5e63f75a7bc5c7f4"
        );
        assert_eq!(
            domain_hash(
                "PRF_PRO_RATA_CONFORMANCE_CORPUS_V1",
                &normative_edge_corpus()
            ),
            "a6d723b27f154d6981cc41a7837ef7a9278ed3265eb98e4a480871845c5b97c1"
        );
    }

    #[test]
    fn exact_verification_input_root_matches_clojure_vector() {
        let request = request(
            7.into(),
            vec![item("a", 4, None), item("b", 4, None), item("c", 2, None)],
            RoundingPolicy::LargestRemainder,
            OrderingPolicy::InputOrder,
            CapTreatment::Unallocated,
        );
        let result = reconstruct(&request).unwrap();
        let claim = VerificationClaim {
            allocations: result
                .allocations
                .iter()
                .rev()
                .map(|row| ClaimedAllocation {
                    id: row.id.clone(),
                    allocated: row.allocated.clone(),
                })
                .collect(),
            total_allocated: result.total_allocated,
            total_unmet: result.total_unmet,
            remainder: result.remainder,
        };
        assert_eq!(
            exact_verification_input_root(&request, &claim),
            "ee2401b91c694d66990b96114555485b5d8efc9944351c8a9339b64806b4339f"
        );
        let mut reordered_claim = claim.clone();
        reordered_claim.allocations.reverse();
        assert_eq!(
            exact_verification_input_root(&request, &reordered_claim),
            exact_verification_input_root(&request, &claim),
            "claim row order is not semantically significant"
        );
    }
}
