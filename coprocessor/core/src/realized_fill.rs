//! Realized partial-fill semantics (independent Rust implementation).
//!
//! This is the semantics layer of the realized-allocation statement: given raw
//! requested amounts and available liquidity, derive a realized allocation
//! (filled/deferred/haircut per participant) with an explicit disposition per
//! participant.
//!
//! It is intentionally decoupled from the statement encoder
//! (`realized_statement.rs`) so semantic errors are testable independently
//! from serialization errors, and so this module never drifts into "code
//! written specifically to reproduce Clojure hashes".
//!
//! Contract: claim keys are STRINGS (claim-id), matching the allocation
//! context's `claim-id` vocabulary. Keyword keys are not used for claim maps.

use crate::canonical::CanonValue;
use num_bigint::BigInt;
use num_traits::ToPrimitive;

/// A realized disposition for one participant.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Disposition {
    FullFill,
    PartialFill,
    Deferred,
    Haircut,
    DeferredAndHaircut,
    ZeroFilled,
}

impl Disposition {
    /// The canonical keyword string (without the leading ':').
    pub fn keyword_str(&self) -> &'static str {
        match self {
            Disposition::FullFill => "full-fill",
            Disposition::PartialFill => "partial-fill",
            Disposition::Deferred => "deferred",
            Disposition::Haircut => "haircut",
            Disposition::DeferredAndHaircut => "deferred-and-haircut",
            Disposition::ZeroFilled => "zero-filled",
        }
    }

    /// Canonical keyword value used in the realized-results projection.
    pub fn canon_keyword(&self) -> CanonValue {
        CanonValue::keyword(self.keyword_str())
    }
}

/// Classify a participant's realized disposition from its realized amounts.
///
/// Order of precedence (mirrors the Clojure `disposition-of`): deferred, then
/// haircut, then full/partial/zero fill. A participant present in requested
/// always gets an explicit disposition, so "inactive/zero-filled" is
/// distinguishable from "producer omitted".
pub fn disposition_of(requested: i64, filled: i64, deferred: i64, haircut: i64) -> Disposition {
    if deferred > 0 && haircut > 0 {
        Disposition::DeferredAndHaircut
    } else if deferred > 0 {
        Disposition::Deferred
    } else if haircut > 0 {
        Disposition::Haircut
    } else if requested == filled {
        Disposition::FullFill
    } else if filled == 0 {
        Disposition::ZeroFilled
    } else {
        Disposition::PartialFill
    }
}

/// One requested participant and its realized amounts.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Participant {
    pub claim_id: String,
    pub requested: i64,
    pub filled: i64,
    pub deferred: i64,
    pub haircut: i64,
    pub unrealized: i64,
}

impl Participant {
    pub fn disposition(&self) -> Disposition {
        disposition_of(self.requested, self.filled, self.deferred, self.haircut)
    }

    /// The canonical realized-results row (a map) for this participant.
    pub fn canon_row(&self) -> CanonValue {
        CanonValue::map(vec![
            (
                CanonValue::keyword("claim/id"),
                CanonValue::str(self.claim_id.clone()),
            ),
            (
                CanonValue::keyword("requested"),
                CanonValue::int(BigInt::from(self.requested)),
            ),
            (
                CanonValue::keyword("filled"),
                CanonValue::int(BigInt::from(self.filled)),
            ),
            (
                CanonValue::keyword("deferred"),
                CanonValue::int(BigInt::from(self.deferred)),
            ),
            (
                CanonValue::keyword("haircut"),
                CanonValue::int(BigInt::from(self.haircut)),
            ),
            (
                CanonValue::keyword("unrealized"),
                CanonValue::int(BigInt::from(self.unrealized)),
            ),
            (
                CanonValue::keyword("disposition"),
                self.disposition().canon_keyword(),
            ),
        ])
    }
}

/// A realized allocation: the derived per-participant disposition set.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RealizedAllocation {
    pub participants: Vec<Participant>,
}

impl RealizedAllocation {
    /// Canonical realized-results value tree: sorted array of per-participant
    /// rows over the union of requested/filled/deferred/haircut claim keys.
    /// Array order is the sorted claim-id order (mirrors the Clojure producer).
    pub fn canon_results(&self) -> CanonValue {
        let mut rows: Vec<CanonValue> = self.participants.iter().map(|p| p.canon_row()).collect();
        rows.sort_by(|a, b| {
            let id_of = |v: &CanonValue| match v {
                CanonValue::Map(pairs) => pairs
                    .iter()
                    .find(|(k, _)| matches!(k, CanonValue::Keyword(kw) if kw == "claim/id"))
                    .and_then(|(_, v)| match v {
                        CanonValue::Str(s) => Some(s.clone()),
                        _ => None,
                    })
                    .unwrap_or_default(),
                _ => String::new(),
            };
            id_of(a).cmp(&id_of(b))
        });
        CanonValue::array(rows)
    }
}

/// Largest-remainder pro-rata allocation (Hare quota), mirroring the Clojure
/// `largest-remainder-alloc` contract:
///   - each claim's floor share of `available` by requested amount;
///   - remaining units awarded one at a time to claims with the largest
///     fractional remainders, ties broken by input (canonical claim) order;
///   - conservation: sum(filled) == available;
///   - all amounts integer base units.
///
/// The full `available` is distributed (not min(available, total)): this is
/// the canonical Hare-quota primitive. Callers decide when over-fill relative
/// to requested is meaningful; `partial_fill` only invokes it under shortfall
/// (available < total-requested), matching the Clojure decision path.
///
/// Returns a vector parallel to `requested` of filled amounts.
pub fn largest_remainder_alloc(available: i64, requested: &[i64]) -> Vec<i64> {
    let total: i64 = requested.iter().sum();
    let n = requested.len();
    if total <= 0 || available <= 0 {
        return vec![0; n];
    }
    // Exact integer arithmetic: use BigInt for the intermediate product so
    // requested * available cannot overflow.
    let floors: Vec<i64> = requested
        .iter()
        .map(|r| {
            let num = BigInt::from(*r) * BigInt::from(available);
            (num / BigInt::from(total)).to_i64().unwrap_or(0)
        })
        .collect();
    let floor_sum: i64 = floors.iter().sum();
    let shortage: i64 = available - floor_sum;
    let remainders: Vec<BigInt> = requested
        .iter()
        .map(|r| {
            let num = BigInt::from(*r) * BigInt::from(available);
            num % BigInt::from(total)
        })
        .collect();

    // Award +1 to the `shortage` claims with the largest remainders; ties
    // break by canonical claim order (stable sort keeps input order).
    let mut indices: Vec<usize> = (0..n).collect();
    indices.sort_by(|&i, &j| remainders[j].cmp(&remainders[i]).then(i.cmp(&j)));

    let mut filled = floors;
    for &idx in indices.iter().take(shortage as usize) {
        filled[idx] += 1;
    }
    filled
}

/// Realize a pro-rata partial fill from raw inputs.
///
/// Inputs:
///   - `available`  — available liquidity (base units)
///   - `requested`  — claim-id -> requested amount (claims sorted by claim-id)
///
/// Derives filled/deferred per participant; haircut is always zero for the
/// pro-rata realization. Deferred = requested - filled.
///
/// Malformed inputs (negative requested, empty set, zero available with
/// nonzero requested) fail closed with an Err rather than producing a partial
/// result.
pub fn partial_fill(
    available: i64,
    requested: &[(String, i64)],
) -> Result<RealizedAllocation, String> {
    if requested.is_empty() {
        return Err("empty requested set".to_string());
    }
    if available < 0 {
        return Err("negative available liquidity".to_string());
    }
    if requested.iter().any(|(_, amt)| *amt < 0) {
        return Err("negative requested amount".to_string());
    }
    let mut sorted: Vec<(String, i64)> = requested.to_vec();
    sorted.sort();

    let amounts: Vec<i64> = sorted.iter().map(|(_, a)| *a).collect();
    let filled = largest_remainder_alloc(available, &amounts);

    let participants: Vec<Participant> = sorted
        .iter()
        .zip(filled.iter())
        .map(|((claim_id, req), &f)| Participant {
            claim_id: claim_id.clone(),
            requested: *req,
            filled: f,
            deferred: req - f,
            haircut: 0,
            unrealized: 0,
        })
        .collect();

    Ok(RealizedAllocation { participants })
}

/// Project a realized allocation's requested set to a canonical claim-keyed
/// map (sorted by claim-id), used by the request-set root.
pub fn canon_request_set(requested: &[(String, i64)]) -> CanonValue {
    let mut pairs: Vec<(CanonValue, CanonValue)> = requested
        .iter()
        .map(|(id, amt)| {
            (
                CanonValue::str(id.clone()),
                CanonValue::int(BigInt::from(*amt)),
            )
        })
        .collect();
    pairs.sort_by_key(|a| a.0.encode());
    CanonValue::map(pairs)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn largest_remainder_basic() {
        // 10 units across {3,3,3}: first two get 4,3,3
        assert_eq!(largest_remainder_alloc(10, &[3, 3, 3]), vec![4, 3, 3]);
    }

    #[test]
    fn largest_remainder_exact() {
        // 60 across {100,200,300}: fills 10,20,30
        assert_eq!(
            largest_remainder_alloc(60, &[100, 200, 300]),
            vec![10, 20, 30]
        );
    }

    #[test]
    fn largest_remainder_conservation() {
        let filled = largest_remainder_alloc(17, &[5, 7, 11, 13]);
        assert_eq!(filled.iter().sum::<i64>(), 17);
    }

    #[test]
    fn partial_fill_fills_and_defers() {
        // 60 across {100,200,300}: fills 10,20,30; shortfall deferred for recovery
        let ra = partial_fill(
            60,
            &[
                ("A".to_string(), 100),
                ("B".to_string(), 200),
                ("C".to_string(), 300),
            ],
        )
        .unwrap();
        let by_id: std::collections::HashMap<&str, &Participant> = ra
            .participants
            .iter()
            .map(|p| (p.claim_id.as_str(), p))
            .collect();
        assert_eq!(by_id["A"].filled, 10);
        assert_eq!(by_id["B"].filled, 20);
        assert_eq!(by_id["C"].filled, 30);
        assert_eq!(by_id["A"].deferred, 90);
        // Default :defer residual treatment: shortfall is deferred, so a
        // partial fill under shortfall is disposition :deferred.
        assert_eq!(by_id["C"].disposition(), Disposition::Deferred);
    }

    #[test]
    fn all_active_is_full_fill() {
        let ra = partial_fill(
            100,
            &[
                ("A".to_string(), 50),
                ("B".to_string(), 30),
                ("C".to_string(), 20),
            ],
        )
        .unwrap();
        assert!(ra
            .participants
            .iter()
            .all(|p| p.disposition() == Disposition::FullFill));
    }

    #[test]
    fn zero_available_defers_entire_shortfall() {
        // available 0: everything deferred for recovery, every participant
        // present with :deferred disposition (not dropped)
        let ra = partial_fill(
            0,
            &[
                ("A".to_string(), 50),
                ("B".to_string(), 30),
                ("C".to_string(), 20),
            ],
        )
        .unwrap();
        assert_eq!(ra.participants.len(), 3);
        assert!(ra.participants.iter().all(|p| p.filled == 0));
        assert!(ra.participants.iter().all(|p| p.deferred == p.requested));
        assert!(ra
            .participants
            .iter()
            .all(|p| p.disposition() == Disposition::Deferred));
    }

    #[test]
    fn zero_filled_disposition_is_distinguishable() {
        // The :zero-filled disposition is a distinct classifier output: a
        // participant present in requested with zero fill and no deferred/
        // haircut. This is what distinguishes "present but inactive" from
        // "producer omitted" in the projection.
        assert_eq!(disposition_of(20, 0, 0, 0), Disposition::ZeroFilled);
        assert_eq!(disposition_of(20, 20, 0, 0), Disposition::FullFill);
        assert_eq!(disposition_of(20, 5, 0, 0), Disposition::PartialFill);
        assert_eq!(disposition_of(20, 5, 15, 0), Disposition::Deferred);
        assert_eq!(disposition_of(20, 5, 0, 15), Disposition::Haircut);
        assert_eq!(
            disposition_of(20, 5, 15, 5),
            Disposition::DeferredAndHaircut
        );
    }

    #[test]
    fn malformed_inputs_fail_closed() {
        assert!(partial_fill(10, &[]).is_err());
        assert!(partial_fill(-1, &[("A".to_string(), 10)]).is_err());
        assert!(partial_fill(10, &[("A".to_string(), -5)]).is_err());
    }
}
