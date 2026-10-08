//! Closed arithmetic statement for existing PRF sum-by-category accounting.
//! No proof/verification, execution lineage, allocation or authority semantics.
use crate::canonical::{domain_hash, CanonValue as C};
use num_traits::ToPrimitive;
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};

fn decode(v: &Value) -> Result<C, String> {
    Ok(match v {
        Value::Null => C::Null,
        Value::Bool(b) => C::Bool(*b),
        Value::String(s) => C::str(s),
        Value::Number(n) => C::int(n.as_i64().ok_or("outside checked-i64 domain")?),
        Value::Array(a) => C::array(a.iter().map(decode).collect::<Result<_, _>>()?),
        Value::Object(m) if m.len() == 1 && m.contains_key("$kw") => {
            let s = m["$kw"]
                .as_str()
                .filter(|s| !s.is_empty())
                .ok_or("bad keyword")?;
            C::keyword(s)
        }
        Value::Object(m) if m.len() == 1 && m.contains_key("$map") => {
            let mut pairs = Vec::new();
            let mut keys = BTreeSet::new();
            for p in m["$map"].as_array().ok_or("bad map")? {
                let a = p.as_array().filter(|a| a.len() == 2).ok_or("bad pair")?;
                let k = decode(&a[0])?;
                if !matches!(k, C::Keyword(_) | C::Str(_)) || !keys.insert(k.encode()) {
                    return Err("invalid/duplicate map key".into());
                }
                pairs.push((k, decode(&a[1])?));
            }
            C::map(pairs)
        }
        _ => return Err("unrecognized canonical witness encoding".into()),
    })
}
fn field<'a>(v: &'a C, key: &str) -> Result<&'a C, String> {
    match v {
        C::Map(m) => m
            .iter()
            .find(|(k, _)| *k == C::keyword(key))
            .map(|(_, v)| v)
            .ok_or(format!("missing {key}")),
        _ => Err("expected map".into()),
    }
}
fn text(v: &C) -> Result<&str, String> {
    if let C::Str(s) = v {
        Ok(s)
    } else {
        Err("expected string".into())
    }
}
fn array(v: &C) -> Result<&[C], String> {
    if let C::Array(a) = v {
        Ok(a)
    } else {
        Err("expected vector".into())
    }
}
fn number(v: &C) -> Result<i64, String> {
    if let C::Int(n) = v {
        n.to_i64().ok_or("outside checked-i64 domain".into())
    } else {
        Err("expected integer".into())
    }
}
fn root(v: &C, domain: &str, key: &str, schema: &str) -> Result<String, String> {
    if field(v, "artifact/schema")? != &C::str(schema) {
        return Err("unsupported schema".into());
    }
    let C::Map(m) = v else {
        return Err("expected artifact".into());
    };
    let body = C::map(
        m.iter()
            .filter(|(k, _)| *k != C::keyword(key))
            .cloned()
            .collect(),
    );
    let actual = format!("sha256:{}", domain_hash(domain, &body));
    if text(field(v, key)?)? != actual {
        return Err(format!("self-root mismatch: {key}"));
    }
    Ok(actual)
}
fn root_ref(v: &C) -> Result<String, String> {
    let s = text(v)?;
    if s.len() != 71
        || !s.starts_with("sha256:")
        || !s[7..]
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
    {
        return Err("invalid root reference".into());
    }
    Ok(s.to_string())
}
fn qualified(v: &C) -> bool {
    matches!(v, C::Keyword(s) if s.split_once('/').is_some_and(|(a,b)| !a.is_empty() && !b.is_empty()))
}
fn binding(v: &C, key: &str, expected: &str) -> Result<(), String> {
    if text(field(v, key)?)? != expected {
        Err(format!("binding mismatch: {key}"))
    } else {
        Ok(())
    }
}
fn sorted_roots(v: &C) -> Result<Vec<String>, String> {
    let roots = array(v)?
        .iter()
        .map(root_ref)
        .collect::<Result<Vec<_>, _>>()?;
    if roots.is_empty() || !roots.windows(2).all(|w| w[0] < w[1]) {
        return Err("roots must be nonempty sorted unique".into());
    }
    Ok(roots)
}
fn sum<'a>(rows: impl Iterator<Item = &'a C>) -> Result<C, String> {
    let mut totals = BTreeMap::<String, i64>::new();
    for row in rows {
        let category = field(row, "accounting/category")?;
        if !qualified(category) {
            return Err("unqualified category".into());
        }
        let C::Keyword(k) = category else {
            unreachable!()
        };
        let value = number(field(row, "accounting/value")?)?;
        let next = totals
            .get(k)
            .unwrap_or(&0)
            .checked_add(value)
            .ok_or("checked-long overflow")?;
        totals.insert(k.clone(), next);
    }
    Ok(C::map(
        totals
            .into_iter()
            .map(|(k, v)| (C::keyword(k), C::int(v)))
            .collect(),
    ))
}
fn contract_body() -> C {
    let props = [
        "associative",
        "commutative",
        "idempotent",
        "order-sensitive",
        "partition-independent",
        "requires-global-constraint",
    ];
    let values = [true, true, false, false, true, false];
    C::map(vec![
        (
            C::keyword("artifact/schema"),
            C::str("accounting-reduction-contract.v1"),
        ),
        (
            C::keyword("accounting/operation"),
            C::keyword("accounting.operation/sum-by-category.v1"),
        ),
        (
            C::keyword("algebraic/properties"),
            C::map(
                props
                    .iter()
                    .zip(values)
                    .map(|(k, v)| (C::keyword(*k), C::Bool(v)))
                    .collect(),
            ),
        ),
        (
            C::keyword("reduction/rule"),
            C::keyword("integer-sum-by-qualified-category"),
        ),
        (
            C::keyword("reduction/proof-obligation"),
            C::keyword("serial-equivalence-conservation-and-exactly-once-source-coverage"),
        ),
    ])
}

/// Validate witness identities, exact plan coverage and selected-source arithmetic.
/// Derive five raw SHA-256 roots (160 bytes); no caller-provided projection.
pub fn derive_public_values(input: &Value) -> Result<Vec<u8>, String> {
    derive_public_values_inner(input, true)
}

/// Derive the exact expected public commitments for a host verifier without
/// repeating the accounting fold. This validates canonical identities and
/// coverage only; the proof verifier must still authenticate a guest execution
/// that called `derive_public_values` (the arithmetic-checking entry point).
pub fn derive_public_commitments(input: &Value) -> Result<Vec<u8>, String> {
    derive_public_values_inner(input, false)
}

fn derive_public_values_inner(input: &Value, check_arithmetic: bool) -> Result<Vec<u8>, String> {
    if input["schema"] != "research-accounting-arithmetic-statement.v1" {
        return Err("unsupported statement".into());
    }
    let request = decode(&input["request"])?;
    let contract = decode(&input["contract"])?;
    let plan = decode(&input["plan"])?;
    let partial = decode(&input["partial"])?;
    let cr = root(
        &contract,
        "ACCOUNTING_REDUCTION_CONTRACT_V1",
        "accounting-reduction-contract/root",
        "accounting-reduction-contract.v1",
    )?;
    if cr
        != format!(
            "sha256:{}",
            domain_hash("ACCOUNTING_REDUCTION_CONTRACT_V1", &contract_body())
        )
    {
        return Err("unsupported reduction contract".into());
    }
    let rr = root(
        &request,
        "ACCOUNTING_REQUEST_V1",
        "accounting-request/root",
        "accounting-request.v1",
    )?;
    binding(&request, "accounting-reduction-contract/root", &cr)?;
    if !qualified(field(&request, "accounting/domain")?) {
        return Err("unqualified accounting domain".into());
    }
    for key in [
        "accounting/cutpoint-root",
        "accounting-definition/root",
        "accounting-policy/root",
    ] {
        root_ref(field(&request, key)?)?;
    }
    let mut rows = BTreeMap::new();
    let mut previous = String::new();
    for row in array(field(&request, "accounting/sources")?)? {
        let C::Map(fields) = row else {
            return Err("invalid source".into());
        };
        if fields.len() != 5 {
            return Err("unsupported source fields".into());
        }
        let sr = root_ref(field(row, "source/root")?)?;
        if sr <= previous {
            return Err("unsorted/duplicate sources".into());
        }
        previous = sr.clone();
        root_ref(field(row, "accounting/subject-root")?)?;
        root_ref(field(row, "claim-to/root")?)?;
        rows.insert(sr, row);
    }
    if rows.is_empty() {
        return Err("empty request".into());
    }
    if check_arithmetic {
        sum(rows.values().copied())?; // Common checked-long serial domain.
    }
    let pr = root(
        &plan,
        "ACCOUNTING_PARTITION_PLAN_V1",
        "accounting-partition-plan/root",
        "accounting-partition-plan.v1",
    )?;
    binding(&plan, "accounting-request/root", &rr)?;
    binding(&plan, "accounting-reduction-contract/root", &cr)?;
    if field(&plan, "partition/coverage")? != &C::keyword("complete-exactly-once") {
        return Err("unsupported coverage".into());
    }
    let mut coverage = BTreeSet::new();
    let mut indices = BTreeSet::new();
    let mut selected = None;
    let requested_unit = root_ref(field(&partial, "accounting-work-unit/root")?)?;
    for unit in array(field(&plan, "partition/work-units")?)? {
        let ur = root(
            unit,
            "ACCOUNTING_WORK_UNIT_V1",
            "accounting-work-unit/root",
            "accounting-work-unit.v1",
        )?;
        binding(unit, "accounting-request/root", &rr)?;
        let index = number(field(unit, "partition/index")?)?;
        if index < 0 || !indices.insert(index) {
            return Err("invalid/duplicate partition index".into());
        }
        for sr in sorted_roots(field(unit, "partition/source-roots")?)? {
            if !rows.contains_key(&sr) || !coverage.insert(sr) {
                return Err("overlap/unknown source".into());
            }
        }
        if ur == requested_unit {
            selected = Some(unit);
        }
    }
    if coverage != rows.keys().cloned().collect() {
        return Err("incomplete source coverage".into());
    }
    let unit = selected.ok_or("partial unit absent from plan")?;
    binding(&partial, "accounting-request/root", &rr)?;
    if field(&partial, "partition/source-roots")? != field(unit, "partition/source-roots")? {
        return Err("partial coverage mismatch".into());
    }
    if check_arithmetic {
        let sources = sorted_roots(field(unit, "partition/source-roots")?)?;
        let actual = sum(sources.iter().map(|s| rows[s]))?;
        if field(&partial, "accounting/values")?.encode() != actual.encode() {
            return Err("incorrect arithmetic".into());
        }
    }
    let sr = root(
        &partial,
        "CANONICAL_ACCOUNTING_PARTIAL_V1",
        "canonical-accounting-partial/root",
        "canonical-accounting-partial.v1",
    )?;
    let C::Map(p) = &partial else { unreachable!() };
    if p.len() != 6 {
        return Err("unexpected canonical partial fields".into());
    }
    Ok([rr, pr, requested_unit, cr, sr]
        .iter()
        .flat_map(|r| hex::decode(&r[7..]).unwrap())
        .collect())
}
