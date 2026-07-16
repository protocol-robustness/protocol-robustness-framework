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
    sensitivity-report.json
  scenarios/<slug>/
    execution/
    summaries/
    state/
    forensic/
```

`completion.json` is the sole positive indication that execution, finalization, registry validation, and sensitivity assessment succeeded. Completed, incomplete, and unrelated non-empty roots are rejected; use a new root for each run.

The immutable artifact registry is `manifest/artifacts.json`. Registered artifact paths are relative to the complete run root.

`--output-dir` and `--scenario-output-dir` are deprecated aliases for `--run-root`. `--save-output` is rejected; a future explicit export command will own copying/export semantics.

When no root is specified, scenario runs default under `results/runs/`. `results/test-artifacts/` remains reserved for test/CI artifacts and is not a scenario-bundle destination.

## Sensitivity profiles

- `public` is the default. The public world projection removes known secret-bearing fields, and the complete retained bundle is scanned before finalization. A scan finding prevents completion.
- `internal` retains full-fidelity artifacts and records `internal-retention` policy metadata in the registry.

Inspect `manifest/sensitivity-report.json` before sharing a bundle.

## Registry validation

```bash
bb validation:artifact-registry /tmp/prf-run/manifest/artifacts.json
```

Use the registry rather than filesystem discovery or `latest`/mtime heuristics when investigating evidence.
