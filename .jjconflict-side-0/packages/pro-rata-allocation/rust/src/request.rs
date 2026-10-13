//! Canonical row normalization for the public pro-rata request profile.
//!
//! This is the Rust counterpart of `prf.pro-rata.rows/canonical-rows`. It
//! owns row identity validation, duplicate rejection, typed identity ordering,
//! and effective-cap derivation. Request-level policy selection and hashing
//! remain separate.

use num_bigint::BigInt;
use prf_canonical_codec::{domain_hash, CanonValue};

pub const REQUEST_SCHEMA: &str = "pro-rata-allocation-request.v1";
pub const PROJECTION_ARTIFACT_DOMAIN: &str = "PROJECTION_ARTIFACT_V1";

#[derive(Clone, Debug, PartialEq)]
pub struct AllocationRowInput {
    pub row_id: Option<CanonValue>,
    pub obligation_id: Option<CanonValue>,
    pub requested: Option<CanonValue>,
    pub weight: Option<CanonValue>,
    pub cap: Option<CanonValue>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct NormalizedAllocationRow {
    pub row_id: CanonValue,
    pub obligation_id: CanonValue,
    pub requested: BigInt,
    pub weight: BigInt,
    pub declared_cap: Option<BigInt>,
    pub effective_cap: BigInt,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RequestNormalizationError {
    MissingRowId,
    DuplicateRowId,
    UnsupportedRowId,
    MissingRowField,
    InvalidRowAmount,
    UnsupportedObligationId,
    UnsupportedRequestSchema,
    UnsupportedMechanismVersion,
    MissingAllocationId,
    UnsupportedAllocationId,
    InvalidAvailable,
    UnsupportedRoundingPolicy,
    UnsupportedTieBreakPolicy,
    UnsupportedRedistributionPolicy,
}

#[derive(Clone, Debug, PartialEq)]
pub struct AllocationRequestInput {
    pub allocation_id: Option<CanonValue>,
    pub schema_version: Option<CanonValue>,
    pub mechanism_version: Option<CanonValue>,
    pub available: Option<CanonValue>,
    pub rows: Vec<AllocationRowInput>,
    pub rounding_policy: Option<CanonValue>,
    pub tie_break_policy: Option<CanonValue>,
    pub redistribution_policy: Option<CanonValue>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct NormalizedAllocationRequest {
    pub allocation_id: CanonValue,
    pub available: BigInt,
    pub rows: Vec<NormalizedAllocationRow>,
    pub rounding_policy: CanonValue,
    pub tie_break_policy: CanonValue,
    pub redistribution_policy: CanonValue,
    pub canonical_request: CanonValue,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
enum IdentityKey {
    Keyword(String, String),
    String(String),
    Vector(Vec<IdentityKey>),
    Map(Vec<u8>),
    Scalar(Vec<u8>),
}

fn canonical_value_is_valid(value: &CanonValue) -> bool {
    match value {
        CanonValue::Null
        | CanonValue::Bool(_)
        | CanonValue::Int(_)
        | CanonValue::Str(_)
        | CanonValue::Keyword(_) => true,
        CanonValue::Array(items) => items.iter().all(canonical_value_is_valid),
        CanonValue::Map(pairs) => {
            let mut keys = Vec::with_capacity(pairs.len());
            for (key, value) in pairs {
                if !matches!(key, CanonValue::Str(_) | CanonValue::Keyword(_))
                    || !canonical_value_is_valid(value)
                {
                    return false;
                }
                let encoded = key.encode();
                if keys.iter().any(|candidate| candidate == &encoded) {
                    return false;
                }
                keys.push(encoded);
            }
            true
        }
    }
}

fn identity_key(value: &CanonValue) -> Result<IdentityKey, RequestNormalizationError> {
    if !canonical_value_is_valid(value) {
        return Err(RequestNormalizationError::UnsupportedRowId);
    }
    match value {
        CanonValue::Null => Err(RequestNormalizationError::UnsupportedRowId),
        CanonValue::Keyword(name) => {
            let (namespace, local_name) = name
                .split_once('/')
                .map(|(namespace, local_name)| (namespace.to_string(), local_name.to_string()))
                .unwrap_or_else(|| (String::new(), name.clone()));
            Ok(IdentityKey::Keyword(namespace, local_name))
        }
        CanonValue::Str(value) => Ok(IdentityKey::String(value.clone())),
        CanonValue::Array(items) => Ok(IdentityKey::Vector(
            items.iter().map(identity_key).collect::<Result<_, _>>()?,
        )),
        CanonValue::Map(_) => Ok(IdentityKey::Map(value.encode())),
        CanonValue::Bool(_) | CanonValue::Int(_) => Ok(IdentityKey::Scalar(value.encode())),
    }
}

pub(crate) fn compare_row_ids(left: &CanonValue, right: &CanonValue) -> std::cmp::Ordering {
    match (identity_key(left), identity_key(right)) {
        (Ok(left), Ok(right)) => left.cmp(&right),
        _ => left.encode().cmp(&right.encode()),
    }
}

fn non_negative_integer(value: Option<&CanonValue>) -> Option<BigInt> {
    match value {
        Some(CanonValue::Int(value)) if value >= &BigInt::from(0) => Some(value.clone()),
        _ => None,
    }
}

fn null_or_missing(value: Option<CanonValue>, default: CanonValue) -> CanonValue {
    match value {
        None | Some(CanonValue::Null) => default,
        Some(value) => value,
    }
}

fn canonical_row_value(row: &NormalizedAllocationRow) -> CanonValue {
    CanonValue::map(vec![
        (CanonValue::keyword("row/id"), row.row_id.clone()),
        (
            CanonValue::keyword("obligation/id"),
            row.obligation_id.clone(),
        ),
        (
            CanonValue::keyword("requested"),
            CanonValue::int(row.requested.clone()),
        ),
        (
            CanonValue::keyword("weight"),
            CanonValue::int(row.weight.clone()),
        ),
        (
            CanonValue::keyword("cap"),
            row.declared_cap
                .as_ref()
                .map(|cap| CanonValue::int(cap.clone()))
                .unwrap_or(CanonValue::Null),
        ),
    ])
}

/// Validate and normalize the complete frozen V1 public request profile.
/// Operational execution options are intentionally not represented here.
pub fn normalize_request(
    input: &AllocationRequestInput,
) -> Result<NormalizedAllocationRequest, RequestNormalizationError> {
    let schema_version = input
        .schema_version
        .clone()
        .unwrap_or_else(|| CanonValue::str(REQUEST_SCHEMA));
    if !matches!(&schema_version, CanonValue::Str(value) if value == REQUEST_SCHEMA) {
        return Err(RequestNormalizationError::UnsupportedRequestSchema);
    }

    let mechanism_version = input
        .mechanism_version
        .clone()
        .unwrap_or_else(|| CanonValue::int(1));
    if mechanism_version != CanonValue::int(1) {
        return Err(RequestNormalizationError::UnsupportedMechanismVersion);
    }

    let allocation_id = input
        .allocation_id
        .clone()
        .ok_or(RequestNormalizationError::MissingAllocationId)?;
    if matches!(allocation_id, CanonValue::Null | CanonValue::Bool(false)) {
        return Err(RequestNormalizationError::MissingAllocationId);
    }
    if !canonical_value_is_valid(&allocation_id) {
        return Err(RequestNormalizationError::UnsupportedAllocationId);
    }

    let available = non_negative_integer(input.available.as_ref())
        .ok_or(RequestNormalizationError::InvalidAvailable)?;
    let rounding_policy = null_or_missing(
        input.rounding_policy.clone(),
        CanonValue::keyword("largest-remainder"),
    );
    if !matches!(
        rounding_policy,
        CanonValue::Keyword(ref policy) if policy == "largest-remainder" || policy == "floor"
    ) {
        return Err(RequestNormalizationError::UnsupportedRoundingPolicy);
    }
    let tie_break_policy = null_or_missing(
        input.tie_break_policy.clone(),
        CanonValue::keyword("canonical-row-id"),
    );
    if tie_break_policy != CanonValue::keyword("canonical-row-id") {
        return Err(RequestNormalizationError::UnsupportedTieBreakPolicy);
    }
    let redistribution_policy = null_or_missing(
        input.redistribution_policy.clone(),
        CanonValue::keyword("unallocated"),
    );
    if redistribution_policy != CanonValue::keyword("unallocated")
        && redistribution_policy != CanonValue::keyword("redistribute-cap-excess")
    {
        return Err(RequestNormalizationError::UnsupportedRedistributionPolicy);
    }

    let rows = normalize_rows(&input.rows)?;
    let canonical_request = CanonValue::map(vec![
        (CanonValue::keyword("schema-version"), schema_version),
        (CanonValue::keyword("mechanism/version"), mechanism_version),
        (CanonValue::keyword("allocation/id"), allocation_id.clone()),
        (
            CanonValue::keyword("available"),
            CanonValue::int(available.clone()),
        ),
        (
            CanonValue::keyword("rows"),
            CanonValue::array(rows.iter().map(canonical_row_value).collect()),
        ),
        (
            CanonValue::keyword("rounding-policy"),
            rounding_policy.clone(),
        ),
        (
            CanonValue::keyword("tie-break-policy"),
            tie_break_policy.clone(),
        ),
        (
            CanonValue::keyword("redistribution-policy"),
            redistribution_policy.clone(),
        ),
    ]);
    Ok(NormalizedAllocationRequest {
        allocation_id,
        available,
        rows,
        rounding_policy,
        tie_break_policy,
        redistribution_policy,
        canonical_request,
    })
}

/// Derive the unchanged V1 projection-artifact request root.
pub fn allocation_request_root(canonical_request: &CanonValue) -> String {
    let preimage = CanonValue::map(vec![
        (
            CanonValue::keyword("intent"),
            CanonValue::keyword("projection-artifact"),
        ),
        (CanonValue::keyword("artifact"), canonical_request.clone()),
    ]);
    domain_hash(PROJECTION_ARTIFACT_DOMAIN, &preimage)
}

/// Validate and normalize rows using the public Clojure request profile's
/// typed identity order. Nil caps become absent caps; every returned numeric
/// field is an arbitrary-precision integer.
pub fn normalize_rows(
    rows: &[AllocationRowInput],
) -> Result<Vec<NormalizedAllocationRow>, RequestNormalizationError> {
    if rows.iter().any(|row| {
        row.row_id
            .as_ref()
            .map_or(true, |id| matches!(id, CanonValue::Null))
    }) {
        return Err(RequestNormalizationError::MissingRowId);
    }

    let mut identity_bytes = Vec::with_capacity(rows.len());
    for row in rows {
        let id = row.row_id.as_ref().expect("row id checked above");
        let bytes = id.encode();
        if identity_bytes.iter().any(|known| known == &bytes) {
            return Err(RequestNormalizationError::DuplicateRowId);
        }
        identity_bytes.push(bytes);
    }

    let identities = rows
        .iter()
        .map(|row| identity_key(row.row_id.as_ref().expect("row id checked above")))
        .collect::<Result<Vec<_>, _>>()?;

    for row in rows {
        if row.requested.is_none() || row.weight.is_none() {
            return Err(RequestNormalizationError::MissingRowField);
        }
    }

    for row in rows {
        if non_negative_integer(row.requested.as_ref()).is_none()
            || non_negative_integer(row.weight.as_ref()).is_none()
            || row.cap.as_ref().is_some_and(|value| {
                !matches!(value, CanonValue::Null) && non_negative_integer(Some(value)).is_none()
            })
        {
            return Err(RequestNormalizationError::InvalidRowAmount);
        }
    }

    let mut normalized = Vec::with_capacity(rows.len());

    for (row, order_key) in rows.iter().zip(identities) {
        let row_id = row.row_id.as_ref().expect("row id checked above");
        let requested =
            non_negative_integer(row.requested.as_ref()).expect("amount validity checked above");
        let weight =
            non_negative_integer(row.weight.as_ref()).expect("amount validity checked above");
        let declared_cap = match row.cap.as_ref() {
            None | Some(CanonValue::Null) => None,
            Some(value) => Some(
                non_negative_integer(Some(value))
                    .ok_or(RequestNormalizationError::InvalidRowAmount)?,
            ),
        };
        let obligation_id = row.obligation_id.clone().unwrap_or(CanonValue::Null);
        if !canonical_value_is_valid(&obligation_id) {
            return Err(RequestNormalizationError::UnsupportedObligationId);
        }

        let effective_cap = declared_cap
            .as_ref()
            .map(|cap| cap.min(&requested).clone())
            .unwrap_or_else(|| requested.clone());
        normalized.push((
            order_key,
            NormalizedAllocationRow {
                row_id: row_id.clone(),
                obligation_id,
                requested,
                weight,
                declared_cap,
                effective_cap,
            },
        ));
    }

    normalized.sort_by(|left, right| left.0.cmp(&right.0));
    Ok(normalized.into_iter().map(|(_, row)| row).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(
        row_id: Option<CanonValue>,
        requested: Option<CanonValue>,
        weight: Option<CanonValue>,
        cap: Option<CanonValue>,
    ) -> AllocationRowInput {
        AllocationRowInput {
            row_id,
            obligation_id: None,
            requested,
            weight,
            cap,
        }
    }

    fn amount(value: i64) -> Option<CanonValue> {
        Some(CanonValue::int(value))
    }

    fn request_fixture_input() -> AllocationRequestInput {
        AllocationRequestInput {
            allocation_id: Some(CanonValue::keyword("portable/rounding")),
            schema_version: None,
            mechanism_version: None,
            available: amount(5),
            rows: vec![
                AllocationRowInput {
                    row_id: Some(CanonValue::keyword("row/a")),
                    obligation_id: Some(CanonValue::keyword("ob/a")),
                    requested: amount(5),
                    weight: amount(1),
                    cap: None,
                },
                AllocationRowInput {
                    row_id: Some(CanonValue::keyword("row/b")),
                    obligation_id: Some(CanonValue::keyword("ob/b")),
                    requested: amount(5),
                    weight: amount(1),
                    cap: None,
                },
            ],
            rounding_policy: None,
            tie_break_policy: None,
            redistribution_policy: None,
        }
    }

    #[test]
    fn full_request_defaults_and_request_root_match_frozen_v1_vector() {
        let normalized = normalize_request(&request_fixture_input()).unwrap();
        assert_eq!(normalized.rows.len(), 2);
        assert_eq!(normalized.available, BigInt::from(5));
        assert_eq!(
            normalized.rounding_policy,
            CanonValue::keyword("largest-remainder")
        );
        assert_eq!(
            normalized.tie_break_policy,
            CanonValue::keyword("canonical-row-id")
        );
        assert_eq!(
            normalized.redistribution_policy,
            CanonValue::keyword("unallocated")
        );
        assert_eq!(
            allocation_request_root(&normalized.canonical_request),
            "6c60df09a5ec1efffaae94da7c0ab8bdf177df9311db525650765fee33e26d85"
        );
    }

    #[test]
    fn full_request_rejects_unsupported_schema_version_and_policies() {
        let mut input = request_fixture_input();
        input.schema_version = Some(CanonValue::str("pro-rata-allocation-request.v2"));
        assert_eq!(
            normalize_request(&input),
            Err(RequestNormalizationError::UnsupportedRequestSchema)
        );

        let mut input = request_fixture_input();
        input.mechanism_version = Some(CanonValue::int(2));
        assert_eq!(
            normalize_request(&input),
            Err(RequestNormalizationError::UnsupportedMechanismVersion)
        );

        let mut input = request_fixture_input();
        input.tie_break_policy = Some(CanonValue::keyword("input-order"));
        assert_eq!(
            normalize_request(&input),
            Err(RequestNormalizationError::UnsupportedTieBreakPolicy)
        );

        let mut input = request_fixture_input();
        input.redistribution_policy = Some(CanonValue::keyword("unknown"));
        assert_eq!(
            normalize_request(&input),
            Err(RequestNormalizationError::UnsupportedRedistributionPolicy)
        );
    }

    #[test]
    fn full_request_rejects_missing_ids_and_invalid_available_amounts() {
        let mut input = request_fixture_input();
        input.allocation_id = Some(CanonValue::Bool(false));
        assert_eq!(
            normalize_request(&input),
            Err(RequestNormalizationError::MissingAllocationId)
        );

        let mut input = request_fixture_input();
        input.available = amount(-1);
        assert_eq!(
            normalize_request(&input),
            Err(RequestNormalizationError::InvalidAvailable)
        );

        let mut input = request_fixture_input();
        input.rounding_policy = Some(CanonValue::keyword("unknown"));
        assert_eq!(
            normalize_request(&input),
            Err(RequestNormalizationError::UnsupportedRoundingPolicy)
        );
    }

    #[test]
    fn rows_use_clojure_typed_identity_order_and_normalize_caps() {
        let rows = vec![
            row(Some(CanonValue::str("a")), amount(5), amount(2), None),
            row(
                Some(CanonValue::keyword("z/id")),
                amount(7),
                amount(1),
                amount(9),
            ),
            row(
                Some(CanonValue::keyword("a/id")),
                amount(3),
                amount(4),
                amount(2),
            ),
        ];

        let actual = normalize_rows(&rows).unwrap();
        assert_eq!(actual[0].row_id, CanonValue::keyword("a/id"));
        assert_eq!(actual[1].row_id, CanonValue::keyword("z/id"));
        assert_eq!(actual[2].row_id, CanonValue::str("a"));
        assert_eq!(actual[0].effective_cap, BigInt::from(2));
        assert_eq!(actual[1].effective_cap, BigInt::from(7));
        assert_eq!(actual[2].declared_cap, None);
        assert_eq!(actual[2].effective_cap, BigInt::from(5));
        assert_eq!(actual[2].obligation_id, CanonValue::Null);
    }

    #[test]
    fn missing_and_duplicate_row_ids_match_legacy_error_classes() {
        let missing = row(None, amount(1), amount(1), None);
        assert_eq!(
            normalize_rows(&[missing]),
            Err(RequestNormalizationError::MissingRowId)
        );

        let null_id = row(Some(CanonValue::Null), amount(1), amount(1), None);
        assert_eq!(
            normalize_rows(&[null_id]),
            Err(RequestNormalizationError::MissingRowId)
        );

        let duplicate = vec![
            row(
                Some(CanonValue::keyword("row/a")),
                amount(1),
                amount(1),
                None,
            ),
            row(
                Some(CanonValue::keyword("row/a")),
                amount(2),
                amount(1),
                None,
            ),
        ];
        assert_eq!(
            normalize_rows(&duplicate),
            Err(RequestNormalizationError::DuplicateRowId)
        );
    }

    #[test]
    fn unsupported_id_and_obligation_values_are_rejected() {
        let invalid_id = row(
            Some(CanonValue::map(vec![(
                CanonValue::int(1),
                CanonValue::int(2),
            )])),
            amount(1),
            amount(1),
            None,
        );
        assert_eq!(
            normalize_rows(&[invalid_id]),
            Err(RequestNormalizationError::UnsupportedRowId)
        );

        let mut invalid_obligation = row(Some(CanonValue::str("a")), amount(1), amount(1), None);
        invalid_obligation.obligation_id = Some(CanonValue::map(vec![(
            CanonValue::int(1),
            CanonValue::int(2),
        )]));
        assert_eq!(
            normalize_rows(&[invalid_obligation]),
            Err(RequestNormalizationError::UnsupportedObligationId)
        );
    }

    #[test]
    fn missing_and_invalid_amounts_are_rejected() {
        let missing_weight = row(Some(CanonValue::str("a")), amount(1), None, None);
        assert_eq!(
            normalize_rows(&[missing_weight]),
            Err(RequestNormalizationError::MissingRowField)
        );

        let negative = row(Some(CanonValue::str("a")), amount(-1), amount(1), None);
        assert_eq!(
            normalize_rows(&[negative]),
            Err(RequestNormalizationError::InvalidRowAmount)
        );

        let non_integer_cap = row(
            Some(CanonValue::str("a")),
            amount(1),
            amount(1),
            Some(CanonValue::bool(true)),
        );
        assert_eq!(
            normalize_rows(&[non_integer_cap]),
            Err(RequestNormalizationError::InvalidRowAmount)
        );
    }

    #[test]
    fn map_identity_compares_canonical_bytes_not_insertion_order() {
        let first = CanonValue::map(vec![
            (CanonValue::keyword("a"), CanonValue::int(1)),
            (CanonValue::keyword("b"), CanonValue::int(2)),
        ]);
        let reordered = CanonValue::map(vec![
            (CanonValue::keyword("b"), CanonValue::int(2)),
            (CanonValue::keyword("a"), CanonValue::int(1)),
        ]);
        let rows = vec![
            row(Some(first), amount(1), amount(1), None),
            row(Some(reordered), amount(1), amount(1), None),
        ];
        assert_eq!(
            normalize_rows(&rows),
            Err(RequestNormalizationError::DuplicateRowId)
        );
    }
}
