//! Frozen V1 pro-rata allocation policy identity.
//!
//! This projects the policy fields from an authoritative
//! `pro-rata-allocation-result.v1` value and hashes the existing
//! `PRO_RATA_ALLOCATION_POLICY_V1` preimage. It does not define a new artifact
//! schema or policy identity.

use prf_canonical_codec::{domain_hash, CanonValue};

pub const RESULT_SCHEMA: &str = "pro-rata-allocation-result.v1";
pub const POLICY_SCHEMA: &str = "pro-rata-allocation-policy.v1";
pub const DOMAIN: &str = "PRO_RATA_ALLOCATION_POLICY_V1";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PolicyIdentityError {
    ResultMustBeMap,
    UnsupportedResultSchema,
    MechanismMustBeMap,
    MissingPolicyField,
}

fn keyword_value<'a>(value: &'a CanonValue, key: &str) -> Option<&'a CanonValue> {
    let CanonValue::Map(pairs) = value else {
        return None;
    };
    pairs.iter().find_map(|(candidate, value)| match candidate {
        CanonValue::Keyword(name) if name == key => Some(value),
        _ => None,
    })
}

fn required_value<'a>(
    value: &'a CanonValue,
    key: &str,
) -> Result<&'a CanonValue, PolicyIdentityError> {
    keyword_value(value, key).ok_or(PolicyIdentityError::MissingPolicyField)
}

/// Return the frozen identity basis, rejecting results that do not satisfy
/// the same required shape as the Clojure `policy/identity-basis` function.
pub fn identity_basis(result: &CanonValue) -> Result<CanonValue, PolicyIdentityError> {
    if !matches!(result, CanonValue::Map(_)) {
        return Err(PolicyIdentityError::ResultMustBeMap);
    }
    if !matches!(keyword_value(result, "schema-version"), Some(CanonValue::Str(s)) if s == RESULT_SCHEMA)
    {
        return Err(PolicyIdentityError::UnsupportedResultSchema);
    }
    let mechanism = required_value(result, "mechanism")?;
    if !matches!(mechanism, CanonValue::Map(_)) {
        return Err(PolicyIdentityError::MechanismMustBeMap);
    }
    let rounding = required_value(result, "rounding-policy")?;
    let tie_break = required_value(result, "tie-break-policy")?;
    let redistribution = required_value(result, "redistribution-policy")?;

    let basis = CanonValue::map(vec![
        (
            CanonValue::keyword("schema/version"),
            CanonValue::str(POLICY_SCHEMA),
        ),
        (
            CanonValue::keyword("mechanism/id"),
            keyword_value(mechanism, "id")
                .cloned()
                .unwrap_or_else(CanonValue::null),
        ),
        (
            CanonValue::keyword("mechanism/version"),
            keyword_value(mechanism, "version")
                .cloned()
                .unwrap_or_else(CanonValue::null),
        ),
        (CanonValue::keyword("rounding-policy"), rounding.clone()),
        (CanonValue::keyword("tie-break-policy"), tie_break.clone()),
        (
            CanonValue::keyword("redistribution-policy"),
            redistribution.clone(),
        ),
    ]);
    Ok(basis)
}

/// Compute the frozen `PRO_RATA_ALLOCATION_POLICY_V1` root from a complete
/// canonical allocation result value.
pub fn allocation_policy_root(result: &CanonValue) -> Result<String, PolicyIdentityError> {
    let basis = identity_basis(result)?;
    let preimage = CanonValue::map(vec![
        (
            CanonValue::keyword("intent"),
            CanonValue::keyword("pro-rata-allocation-policy"),
        ),
        (CanonValue::keyword("artifact"), basis),
    ]);
    Ok(domain_hash(DOMAIN, &preimage))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid_result() -> CanonValue {
        CanonValue::map(vec![
            (
                CanonValue::keyword("schema-version"),
                CanonValue::str(RESULT_SCHEMA),
            ),
            (
                CanonValue::keyword("mechanism"),
                CanonValue::map(vec![
                    (
                        CanonValue::keyword("id"),
                        CanonValue::keyword("mechanism/pro-rata-allocation"),
                    ),
                    (CanonValue::keyword("version"), CanonValue::int(1)),
                ]),
            ),
            (
                CanonValue::keyword("rounding-policy"),
                CanonValue::keyword("largest-remainder"),
            ),
            (
                CanonValue::keyword("tie-break-policy"),
                CanonValue::keyword("canonical-row-id"),
            ),
            (
                CanonValue::keyword("redistribution-policy"),
                CanonValue::keyword("redistribute-cap-excess"),
            ),
        ])
    }

    #[test]
    fn policy_root_matches_frozen_clojure_v1_projection() {
        assert_eq!(
            allocation_policy_root(&valid_result()).unwrap(),
            "2e4c81dc89c3a0b5b15f8a110ceeb66fe1a802fb1f0322ae3e58fed4cef9aeb9"
        );
    }

    #[test]
    fn policy_identity_rejects_wrong_result_schema() {
        let mut result = valid_result();
        if let CanonValue::Map(pairs) = &mut result {
            pairs[0].1 = CanonValue::str("pro-rata-allocation-result.v2");
        }
        assert_eq!(
            identity_basis(&result),
            Err(PolicyIdentityError::UnsupportedResultSchema)
        );
    }
}
