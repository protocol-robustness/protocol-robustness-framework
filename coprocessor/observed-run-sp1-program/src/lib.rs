//! Guest-compatible lineage validation for the observed assured-run statement.

use allocation_kernel::canonical::{domain_hash, CanonValue};
use serde_json::Value;

const INPUT_SCHEMA: &str = "research-observed-run-proof-statement.v1";
const OBSERVED_SCHEMA: &str = "research-observed-run-evidence.v1";
const ASSURANCE_SCHEMA: &str = "research-run-assurance-evidence.v1";
const ASSURED_JOB_SCHEMA: &str = "research-execution-job-assurance.v1";
const ASSIGNMENT_SCHEMA: &str = "research-run-assignment.v1";
const PARTICIPATION_SCHEMA: &str = "runner-participation.v1";
const DEPENDENCIES_SCHEMA: &str = "resolved-research-dependency-set.v1";
const IMPLEMENTATION_SCHEMA: &str = "research-implementation-identity.v1";
const ENVIRONMENT_SCHEMA: &str = "research-execution-environment.v1";
const ENV_OBSERVATION_SCHEMA: &str = "research-execution-environment-observation.v1";
const ANVIL_RECEIPT_SCHEMA: &str = "research-anvil-execution-receipt.v1";
const FIXED_VALIDATION_SCHEMA: &str = "research-observed-fixed-validation-receipt.v1";
const FIXED_CHECKS: [&str; 11] = [
    "declared-environment-bound",
    "observed-environment-bound",
    "chain-id-matches",
    "tool-version-matches",
    "contract-code-matches",
    "prestate-matches",
    "action-matches",
    "poststate-observed",
    "semantic-result-bound",
    "dependency-closure-bound",
    "control-state-consistent",
];

fn decode(value: &Value) -> Result<CanonValue, String> {
    match value {
        Value::Null => Ok(CanonValue::Null),
        Value::Bool(v) => Ok(CanonValue::Bool(*v)),
        Value::Number(v) => {
            if let Some(n) = v.as_i64() {
                Ok(CanonValue::int(n))
            } else if let Some(n) = v.as_u64() {
                Ok(CanonValue::int(n))
            } else {
                Err("non-integer canonical number".into())
            }
        }
        Value::String(v) => Ok(CanonValue::str(v.clone())),
        Value::Array(items) => items
            .iter()
            .map(decode)
            .collect::<Result<Vec<_>, _>>()
            .map(CanonValue::array),
        Value::Object(object) if object.len() == 1 && object.contains_key("$kw") => {
            let value = object["$kw"].as_str().ok_or("keyword tag must be string")?;
            if value.is_empty() {
                return Err("empty keyword".into());
            }
            Ok(CanonValue::keyword(value))
        }
        Value::Object(object) if object.len() == 1 && object.contains_key("$map") => {
            let pairs = object["$map"].as_array().ok_or("map tag must be array")?;
            let mut decoded = Vec::with_capacity(pairs.len());
            for pair in pairs {
                let pair = pair
                    .as_array()
                    .filter(|p| p.len() == 2)
                    .ok_or("map entry must be pair")?;
                let key = decode(&pair[0])?;
                if !matches!(key, CanonValue::Keyword(_) | CanonValue::Str(_)) {
                    return Err("canonical map key must be keyword or string".into());
                }
                decoded.push((key, decode(&pair[1])?));
            }
            Ok(CanonValue::map(decoded))
        }
        _ => Err("unrecognized witness encoding".into()),
    }
}

fn keyword(name: &str) -> CanonValue {
    CanonValue::keyword(name)
}
fn get<'a>(value: &'a CanonValue, key: &str) -> Result<&'a CanonValue, String> {
    match value {
        CanonValue::Map(entries) => entries
            .iter()
            .find_map(|(k, v)| if *k == keyword(key) { Some(v) } else { None })
            .ok_or_else(|| format!("missing {key}")),
        _ => Err("expected canonical map".into()),
    }
}
fn get_str<'a>(value: &'a CanonValue, key: &str) -> Result<&'a str, String> {
    match get(value, key)? {
        CanonValue::Str(s) => Ok(s),
        _ => Err(format!("{key} must be string")),
    }
}
fn get_kw<'a>(value: &'a CanonValue, key: &str) -> Result<&'a str, String> {
    match get(value, key)? {
        CanonValue::Keyword(s) => Ok(s),
        _ => Err(format!("{key} must be keyword")),
    }
}
fn equal(a: &CanonValue, b: &CanonValue, key: &str) -> Result<(), String> {
    if a == b {
        Ok(())
    } else {
        Err(format!("lineage mismatch at {key}"))
    }
}
fn equal_field(a: &CanonValue, ak: &str, b: &CanonValue, bk: &str) -> Result<(), String> {
    equal(get(a, ak)?, get(b, bk)?, ak)
}
fn root_ref(value: &CanonValue) -> Result<&str, String> {
    let s = match value {
        CanonValue::Str(s) => s.as_str(),
        _ => return Err("root must be string".into()),
    };
    let hex = s
        .strip_prefix("sha256:")
        .ok_or("root must use sha256 reference")?;
    if hex.len() != 64
        || !hex
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
    {
        return Err("invalid sha256 root".into());
    }
    Ok(s)
}
fn root_bytes(value: &CanonValue) -> Result<Vec<u8>, String> {
    let reference = root_ref(value)?;
    hex::decode(&reference[7..]).map_err(|_| "invalid root hex".into())
}
fn verify_identity(value: &CanonValue, field: &str, domain: &str) -> Result<(), String> {
    let expected = root_ref(get(value, field)?)?;
    let mut body = value.clone();
    if let CanonValue::Map(entries) = &mut body {
        entries.retain(|(k, _)| *k != keyword(field));
    }
    let actual = format!("sha256:{}", domain_hash(domain, &body));
    if expected == actual {
        Ok(())
    } else {
        Err(format!("canonical identity mismatch: {field}"))
    }
}
fn schema(value: &CanonValue, expected: &str) -> Result<(), String> {
    if get_str(value, "artifact/schema")? == expected {
        Ok(())
    } else {
        Err(format!("unsupported schema: {expected}"))
    }
}
fn validate_checks(receipt: &CanonValue) -> Result<(), String> {
    let checks = get(receipt, "validation/checks")?;
    let checks = match checks {
        CanonValue::Array(xs) => xs,
        _ => return Err("validation checks must be array".into()),
    };
    if checks.len() != FIXED_CHECKS.len() {
        return Err("fixed validation check count mismatch".into());
    }
    for (check, expected) in checks.iter().zip(FIXED_CHECKS) {
        if get_kw(check, "check/id")? != expected || get_kw(check, "check/status")? != "pass" {
            return Err("fixed validation floor mismatch".into());
        }
    }
    Ok(())
}

/// Validate canonical source witnesses and derive the frozen 384 public bytes.
pub fn derive_public_values(input: &Value) -> Result<Vec<u8>, String> {
    if input.get("schema").and_then(Value::as_str) != Some(INPUT_SCHEMA) {
        return Err("unsupported proof input schema".into());
    }
    let observed = decode(input.get("observed_run").ok_or("missing observed_run")?)?;
    let assurance = decode(input.get("run_assurance").ok_or("missing run_assurance")?)?;
    schema(&observed, OBSERVED_SCHEMA)?;
    schema(&assurance, ASSURANCE_SCHEMA)?;
    verify_identity(
        &observed,
        "research-observed-run-evidence/root",
        "RESEARCH_OBSERVED_RUN_EVIDENCE_V1",
    )?;
    verify_identity(
        &assurance,
        "research-run-assurance-evidence/root",
        "RESEARCH_RUN_ASSURANCE_EVIDENCE_V1",
    )?;

    let support = get(&observed, "proof/source-artifacts")?;
    let assured_job = get(support, "assured-job")?;
    let assignment = get(support, "assignment")?;
    let participation = get(support, "runner-participation")?;
    let dependencies = get(support, "resolved-dependencies")?;
    let implementation = get(support, "implementation")?;
    let environment = get(support, "execution-environment")?;
    let observation = get(support, "environment-observation")?;
    let receipt = get(support, "anvil-receipt")?;
    let fixed = get(support, "fixed-validation")?;

    schema(assured_job, ASSURED_JOB_SCHEMA)?;
    schema(assignment, ASSIGNMENT_SCHEMA)?;
    schema(participation, PARTICIPATION_SCHEMA)?;
    schema(dependencies, DEPENDENCIES_SCHEMA)?;
    schema(implementation, IMPLEMENTATION_SCHEMA)?;
    schema(environment, ENVIRONMENT_SCHEMA)?;
    verify_identity(
        assured_job,
        "research-execution-job-assurance/root",
        "RESEARCH_EXECUTION_JOB_ASSURANCE_V1",
    )?;
    verify_identity(
        assignment,
        "research-run-assignment/root",
        "RESEARCH_RUN_ASSIGNMENT_V1",
    )?;
    verify_identity(
        participation,
        "runner-participation/root",
        "RUNNER_PARTICIPATION_V1",
    )?;
    verify_identity(
        dependencies,
        "resolved-research-dependency-set/root",
        "RESOLVED_RESEARCH_DEPENDENCY_SET_V1",
    )?;
    verify_identity(
        implementation,
        "research-implementation-identity/root",
        "RESEARCH_IMPLEMENTATION_IDENTITY_V1",
    )?;
    verify_identity(
        environment,
        "research-execution-environment/root",
        "RESEARCH_EXECUTION_ENVIRONMENT_V1",
    )?;
    schema(observation, ENV_OBSERVATION_SCHEMA)?;
    verify_identity(
        observation,
        "research-execution-environment-observation/root",
        "RESEARCH_EXECUTION_ENVIRONMENT_OBSERVATION_V1",
    )?;
    schema(receipt, ANVIL_RECEIPT_SCHEMA)?;
    verify_identity(
        receipt,
        "research-anvil-execution-receipt/root",
        "RESEARCH_ANVIL_EXECUTION_RECEIPT_V1",
    )?;
    schema(fixed, FIXED_VALIDATION_SCHEMA)?;
    verify_identity(
        fixed,
        "research-observed-fixed-validation-receipt/root",
        "RESEARCH_OBSERVED_FIXED_VALIDATION_RECEIPT_V1",
    )?;

    // The execution receipt carries the exact declared and observed environment
    // bodies it used.  Bind these copies to the canonical support artifacts,
    // rather than accepting same-shaped but unrelated observations.
    equal(
        get(&receipt, "research-execution-environment/body")?,
        environment,
        "receipt declared environment body",
    )?;
    equal(
        get(&receipt, "research-execution-environment-observation/body")?,
        observation,
        "receipt observed environment body",
    )?;
    equal_field(
        observation,
        "research-execution-environment/root",
        environment,
        "research-execution-environment/root",
    )?;
    equal_field(
        observation,
        "research-execution-environment-observation/root",
        &receipt,
        "research-execution-environment-observation/root",
    )?;

    equal_field(
        &observed,
        "research-execution-job/root",
        assured_job,
        "research-execution-job/root",
    )?;
    equal_field(
        &observed,
        "research-execution-job/root",
        assignment,
        "research-execution-job/root",
    )?;
    equal_field(
        &observed,
        "research-run-assignment/root",
        assignment,
        "research-run-assignment/root",
    )?;
    equal_field(
        &observed,
        "runner-participation/root",
        participation,
        "runner-participation/root",
    )?;
    equal_field(
        &observed,
        "runner-participation/root",
        assignment,
        "runner-participation/root",
    )?;
    equal_field(
        &observed,
        "runner/reference",
        participation,
        "runner/reference",
    )?;
    equal_field(&observed, "runner/site-id", participation, "runner/site-id")?;
    equal_field(
        &observed,
        "runner/reference",
        &assurance,
        "runner/reference",
    )?;
    equal_field(&observed, "runner/site-id", &assurance, "runner/site-id")?;
    equal_field(&observed, "runner/site-id", assignment, "runner/site-id")?;
    equal_field(
        &assurance,
        "research-execution-job-assurance/root",
        assured_job,
        "research-execution-job-assurance/root",
    )?;
    equal_field(
        &assurance,
        "research-run-evidence/root",
        &observed,
        "research-observed-run-evidence/root",
    )?;
    equal_field(
        &observed,
        "resolved-research-dependency-set/root",
        dependencies,
        "resolved-research-dependency-set/root",
    )?;
    equal_field(
        &observed,
        "resolved-research-dependency-set/root",
        implementation,
        "resolved-research-dependency-set/root",
    )?;
    equal_field(
        &observed,
        "resolved-research-dependency-set/root",
        &assurance,
        "resolved-research-dependency-set/root",
    )?;
    equal_field(
        &observed,
        "research-implementation-identity/root",
        implementation,
        "research-implementation-identity/root",
    )?;
    equal_field(
        &observed,
        "research-implementation-identity/root",
        &assurance,
        "research-implementation-identity/root",
    )?;
    equal_field(
        &observed,
        "research-execution-environment/root",
        environment,
        "research-execution-environment/root",
    )?;
    equal_field(
        &observed,
        "research-execution-environment/root",
        assignment,
        "research-execution-environment/root",
    )?;
    equal_field(
        &observed,
        "research-execution-environment-observation/root",
        observation,
        "research-execution-environment-observation/root",
    )?;
    equal_field(
        &observed,
        "research-execution-job/root",
        receipt,
        "research-execution-job/root",
    )?;
    equal_field(
        &observed,
        "research-run-assignment/root",
        receipt,
        "research-run-assignment/root",
    )?;
    equal_field(
        &observed,
        "runner-participation/root",
        receipt,
        "runner-participation/root",
    )?;
    equal_field(&observed, "runner/reference", receipt, "runner/reference")?;
    equal_field(
        &observed,
        "resolved-research-dependency-set/root",
        receipt,
        "resolved-research-dependency-set/root",
    )?;
    equal_field(
        &observed,
        "research-implementation-identity/root",
        receipt,
        "research-implementation-identity/root",
    )?;
    equal_field(
        &observed,
        "research-execution-environment/root",
        receipt,
        "research-execution-environment/root",
    )?;
    equal_field(
        &observed,
        "research-execution-environment-observation/root",
        receipt,
        "research-execution-environment-observation/root",
    )?;
    equal_field(
        &observed,
        "environment-before/root",
        receipt,
        "environment-before/root",
    )?;
    equal_field(
        &observed,
        "executed-action/root",
        receipt,
        "executed-action/root",
    )?;
    equal_field(
        &observed,
        "environment-after/root",
        receipt,
        "environment-after/root",
    )?;
    equal_field(
        &observed,
        "semantic-result/root",
        receipt,
        "semantic-result/root",
    )?;
    equal_field(
        &observed,
        "semantic-result/root",
        &assurance,
        "semantic-result/root",
    )?;
    equal_field(
        &observed,
        "research-observed-fixed-validation-receipt/root",
        fixed,
        "research-observed-fixed-validation-receipt/root",
    )?;
    equal_field(
        &observed,
        "research-observed-fixed-validation-receipt/root",
        &assurance,
        "research-fixed-validation-receipt/root",
    )?;
    equal_field(
        &receipt,
        "research-anvil-execution-receipt/root",
        fixed,
        "research-anvil-execution-receipt/root",
    )?;
    if get_kw(&observed, "execution/assurance")? != "observed-local-execution"
        || get_kw(&assurance, "semantic-result/status")? != "execution-result-only"
        || get_kw(&assurance, "application-authority/status")? != "not-established"
        || get_kw(&observation, "observation/assurance")? != "observed-local-execution"
        || get_kw(&receipt, "execution/assurance")? != "observed-local-execution"
        || get_kw(&receipt, "execution/transaction-status")? != "success"
        || get_kw(&fixed, "validation/status")? != "passed"
    {
        return Err("assurance classification mismatch".into());
    }
    validate_checks(fixed)?;

    let observed_root = get(&observed, "research-observed-run-evidence/root")?;
    let assurance_root = get(&assurance, "research-run-assurance-evidence/root")?;
    let values = [
        get(assured_job, "research-execution-job-assurance/root")?,
        observed_root,
        assurance_root,
        get(&observed, "resolved-research-dependency-set/root")?,
        get(&observed, "research-implementation-identity/root")?,
        get(&observed, "research-execution-environment/root")?,
        get(&observed, "research-execution-environment-observation/root")?,
        get(&observed, "environment-before/root")?,
        get(&observed, "executed-action/root")?,
        get(&observed, "environment-after/root")?,
        get(&observed, "semantic-result/root")?,
        get(&observed, "research-observed-fixed-validation-receipt/root")?,
    ];
    let mut bytes = Vec::with_capacity(384);
    for value in values {
        bytes.extend(root_bytes(value)?);
    }
    if bytes.len() != 384 {
        return Err("public values length mismatch".into());
    }
    Ok(bytes)
}

pub fn public_values_hex(bytes: &[u8]) -> String {
    hex::encode(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_projection_only_input() {
        let input = serde_json::json!({"schema": INPUT_SCHEMA, "public_value_roots": []});
        assert!(derive_public_values(&input).is_err());
    }
}
