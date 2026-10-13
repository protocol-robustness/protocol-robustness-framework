//! Canonical row normalization for the public pro-rata request profile.
//!
//! This is the Rust counterpart of `prf.pro-rata.rows/canonical-rows`. It
//! owns row identity validation, duplicate rejection, typed identity ordering,
//! and effective-cap derivation. Request-level policy selection and hashing
//! remain separate.

use num_bigint::BigInt;
use prf_canonical_codec::CanonValue;

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

fn non_negative_integer(value: Option<&CanonValue>) -> Option<BigInt> {
    match value {
        Some(CanonValue::Int(value)) if value >= &BigInt::from(0) => Some(value.clone()),
        _ => None,
    }
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
