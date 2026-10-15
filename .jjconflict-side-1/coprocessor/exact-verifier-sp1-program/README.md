# Pro-rata exact-verifier SP1 profile (candidate)

This guest is a separate proof profile from `allocation-sp1-program`. It proves
that a supplied claim matches the independent, request-authoritative verifier
in `prf-pro-rata-allocation`. It does not run the allocation producer and does
not change that guest's public values, ELF, VKey, or program identity.

## Input

Input is JSON with a top-level `request` and `claim`. Amounts, weights, caps,
and totals are decimal strings so they retain arbitrary precision. Allocation
IDs use tagged values (`kind` plus `value`) so canonical keywords, strings,
integers, booleans, nulls, arrays, and maps are distinguishable. Map IDs encode
their entries as arrays of two-element key/value pairs.

The positive smoke fixture is `fixtures/valid-v1.json`.

## Public values

Successful execution commits exactly 96 bytes:

```text
fairness-spec-root (32) || supplemental-corpus-root (32) || verification-input-root (32)
```

The third value commits the canonical request and claimed allocation. Claim
rows are sorted by canonical ID encoding because claim order is not
semantically significant; request item order is preserved because the
`input-order` policy depends on it. The input root is a proof-profile
commitment, not an allocation artifact identity.

Malformed, unsupported, or incorrect claims abort guest execution and cannot
produce these success public values.

## Local qualification

```bash
cargo run --manifest-path coprocessor/Cargo.toml \
  -p pro-rata-exact-verifier-sp1-host -- \
  --input coprocessor/exact-verifier-sp1-program/fixtures/valid-v1.json
```

The host command executes the guest and prints its public values. It does not
generate or qualify a proof, VKey, verifier snapshot, deployment, or production
admission policy. Those remain separate release gates. This profile has no
assigned immutable program/VKey identity until those gates are completed.
