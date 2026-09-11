# Usage Reference

## Core workflows

```bash
# Framework-only unit tests
bb test:framework

# Framework plus Sew unit tests
bb test:unit

# Build the Sew distribution
bb build:sew

# Canonical Sew scenario execution: use a fresh exact bundle root
java -jar target/prf-runner-sew-0.1.0-uber.jar run-scenario scenarios/edn/<scenario>.edn --run-root /tmp/prf-run

# Repository-development adapter; same scenario command contract
bb run:scenario scenarios/edn/<scenario>.edn --run-root /tmp/prf-run

# Run a parameterized simulation
bb sim:run -p data/params/baseline.edn
```

`run-scenario` accepts:

```text
--report-format summary|failures|standard|verbose|audit
--sensitivity-profile public|internal
```

## Scenario bundle contract

`--run-root` is the authoritative root for one complete scenario evidence bundle:

```text
<run-root>/
  completion.json
  manifest/
    run.json
    summary.json
    artifacts.json
    artifact-registry-validation.json
    run-package-index.json
    sensitivity-report.json
  scenarios/<slug>/
    execution/
    summaries/
    state/
    forensic/
```

`completion.json` is the terminal lifecycle seal for a canonical single-scenario run. It commits to the contained path, exact SHA-256, and exact byte length of `manifest/run-package-index.json`. Completed, incomplete, and unrelated non-empty roots are rejected; use a new root for each run.

The immutable package boundary is `manifest/run-package-index.json`. It commits to the required authoritative pre-completion closure: the snapshotted input, scenario/runner/run finalizations, canonical-integrity assurance, registry and registry-validation result, and execution DAG. The index does not include `completion.json`; the pre-package registry intentionally excludes both `manifest/run-package-index.json` and `completion.json` to avoid circular commitments.

Package validation begins at `completion.json`, verifies the exact persisted package-index bytes, then validates its supported profile, indexed closure, semantic artifacts, and authoritative reconciliation. A semantic payload hash and an exact persisted-byte SHA-256 are distinct commitments and both must validate.

For `:single-scenario`, the canonical lifecycle requires a persisted valid execution DAG before completion. Inner `run-and-report` execution may retain partial or diagnostic output when DAG materialization fails, but it does not produce a complete canonical package.

A sealed package can be complete, integrity-valid, and runnable while its semantic result is `fail`. Unsigned canonical integrity is not release authorization; release eligibility additionally requires signer/operator assurance.

The immutable artifact registry is `manifest/artifacts.json`. Registered artifact paths are relative to the complete run root.

Verify a completed scenario bundle read-only, including its artifact registry, persisted scenario-chain finalization, run-level finalization, and reconciled event-evidence set:

```bash
java -jar target/prf-runner-sew-0.1.0-uber.jar \
  verify-scenario --run-root /tmp/prf-run
```

`--output-dir` and `--scenario-output-dir` are deprecated aliases for `--run-root`. `--save-output` is rejected; a future explicit export command will own copying/export semantics.

When no root is specified, scenario runs default under `results/runs/`. `results/test-artifacts/` remains reserved for test/CI artifacts and is not a scenario-bundle destination.

## Benchmarks and suites

Use a benchmark for a canonical multi-scenario evidence bundle:

```bash
java -jar target/prf-runner-sew-0.1.0-uber.jar \
  run-benchmark force-authorisation-custody-v1 \
  --run-root /tmp/prf-benchmark
```

A completed benchmark bundle contains its frozen definition, execution plan,
child execution artifacts, aggregate summary, conclusion, conservation and
assurance assertions, registry, finalization, and completion record. Its
conclusion (`pass`, `fail`, or `inconclusive`) is separate from lifecycle
completion.

Verify a completed benchmark bundle without mutating it:

```bash
java -jar target/prf-runner-sew-0.1.0-uber.jar \
  verify-benchmark --run-root /tmp/prf-benchmark
```

Registered suites remain reusable internal execution-set definitions for CI,
fixtures, and benchmark membership. `bb run:scenario:suites` and
`run-invariants` are legacy/internal runners; they are not canonical bundle
producers. A public `run-suite` command is deferred until a concrete consumer
needs a finalized execution-set bundle without benchmark claims.

## Sensitivity profiles

- `public` is the default. The public world projection removes known secret-bearing fields, and the complete retained bundle is scanned before finalization. A scan finding prevents completion.
- `internal` retains full-fidelity artifacts and records `internal-retention` policy metadata in the registry.

Inspect `manifest/sensitivity-report.json` before sharing a bundle.

## Registry validation

Validate a completed canonical bundle without mutating it:

```bash
java -jar target/prf-runner-sew-0.1.0-uber.jar \
  evidence validate --run-root /tmp/prf-run

# Development adapter
bb cli evidence validate --run-root /tmp/prf-run
```

The legacy `--artifact-dir` option is only for pre-canonical evidence directories.

Use the registry rather than filesystem discovery or `latest`/mtime heuristics when investigating evidence.

## Comparison and inspection

Compare and inspect completed packages and artifacts:

```bash
# Canonical comparison of two EDN/JSON artifacts (byte-identical canonical encoding)
java -jar prf.jar compare a.json b.json
java -jar prf.jar compare --json a.json b.json

# Structural root hashes of a completed run package
java -jar prf.jar root-hash --run-root /tmp/prf-run

# Roots of a run's realized result (bundle root, stable-result hash, evidence roots)
java -jar prf.jar result-root --run-root /tmp/prf-run

# Semantic equivalence of two run packages (stable-result projection)
java -jar prf.jar semantic-equivalent --package-a /tmp/run-a --package-b /tmp/run-b

# Declared dependency surface of a run package
java -jar prf.jar declared-dependencies --run-root /tmp/prf-run

# Completion-sealed package comparison (verification + policy/evaluator/distribution)
java -jar prf.jar compare-runs --package-a /tmp/run-a --package-b /tmp/run-b
```

`compare` exits 0 when the two files are canonically equivalent and 1 otherwise.
`semantic-equivalent` exits 0 when both runs realized a non-empty result set with
identical stable-result hashes and matching verdict-policy outcomes.
`root-hash` / `result-root` / `declared-dependencies` are read-only inspections of a
single completed run package; they do not require a fully re-verified package.
