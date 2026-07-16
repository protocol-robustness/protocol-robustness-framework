# Usage Reference

This is the supported command reference for the current repository. The preferred entry points are the Babashka tasks (`bb ...`) and the canonical test runner (`./scripts/test.sh ...`). The standalone replay-comparison utilities live under `scripts/`; use their Babashka wrappers where available.

## Command surfaces

| Surface | Use it for | Entry point |
|---|---|---|
| Babashka tasks | Contributor-friendly workflows and wrappers | `bb <task>` |
| Canonical test runner | Full and targeted validation | `./scripts/test.sh <target>` |
| PRF CLI | Registered JVM commands, including jar-distributed commands | `clojure -M:cli -- <command>` or `java -jar <runner>.jar <command>` |
| Make | Generated-document checks and selected integration helpers | `make <target>` |

The PRF command registry is `resources/prf/commands/registry.edn`; its dispatch implementation is `src/resolver_sim/cli/dispatch.clj`.

## Core workflows

```bash
# Canonical repository validation
bb test

# Fast validation path
./scripts/test.sh fast

# Framework-only unit tests
bb test:framework

# Framework plus Sew unit tests
bb test:unit

# Run one registered scenario (writes a self-contained bundle under results/runs/)
bb run:scenario <scenario-id>

# Run a parameterized simulation
bb sim:run -p data/params/baseline.edn
```

`bb run:scenario` accepts `--result-display-level summary|failures|standard|verbose|audit`.

For a self-contained structured execution bundle, use the canonical option:

```bash
bb run:scenario <scenario-id> --run-root <dir>
```

`--output-dir`, `--scenario-output-dir`, and `--save-output` are temporary compatibility aliases for `--run-root`. At the CLI, the **run root** is the complete bundle (`manifest/` plus `scenarios/<slug>/...`). Internally, an **artifact directory** is only the low-level `scenarios/<slug>/forensic/` destination supplied to evidence and forensic writers. When no output-root option is supplied, the runner generates a unique bundle under `results/runs/<scenario-slug>-<timestamp>-<nonce>/`; it does not use the legacy scattered-output path.

## Canonical test-runner targets

```bash
./scripts/test.sh <target>
```

| Target | Purpose |
|---|---|
| `all` | Canonical broad gate: unit, generators, contracts, invariants, suites, reference validation, coverage, triage, and Monte Carlo unless fast mode is selected. |
| `fast` | Edit-loop gate: unit, generators, contracts, invariants, suites, and reference validation. |
| `unit` | Framework and Sew unit tests. |
| `framework` | Framework-only unit tests. |
| `sew` | Sew-specific unit tests. |
| `generators` | Deterministic generator and equilibrium regression checks. |
| `contracts` | Cross-layer contract checks. |
| `invariants` | Deterministic invariant scenarios (S01–S100). |
| `suites` | Registered fixture suites. |
| `reference-validation` | Public reference-evidence harness. |
| `coverage` | Coverage gates. |
| `triage` | Failure triage grouped by purpose and threat tag. |
| `equivalence-new` | Model-side trace-equivalence comparison. |
| `monte-carlo` | Representative Monte Carlo sweep. |
| `long-horizon` | Extended multi-epoch scenarios. |
| `dispute-resolution` | Dispute resolution coverage (S-DR-* scenarios). |
| `yield` | Yield protocol unit tests. |
| `yield-provider-scenarios` | Standalone yield-v1 JSON scenarios. |
| `sew-yield-scenarios` | Sew escrow + yield integration JSON scenarios. |
| `adversarial-sweep` | Adversarial profitability sweep. |
| `adversarial-gates` | Adversarial release gates. |

The runner records a machine-readable summary at `results/test-artifacts/test-summary.json`. Individual tasks may emit additional files under `results/`.

## Registered PRF CLI commands

Invoke with the development classpath:

```bash
clojure -M:cli -- help
clojure -M:cli -- <command> [options]
```

The same native commands are intended to be available from a built runner jar:

```bash
java -jar target/prf-runner-core-<version>.jar <command> [options]
```

| Command | Key options | Purpose |
|---|---|---|
| `backstop` | `--fast`, `--full`, `--json` | Run review gates. |
| `commands validate` | `--json` | Validate command registry, dispatch table, and Babashka wrapper parity. |
| `validate` | `--strict`, `--json` | Run structural validation. |
| `concepts validate` | `--json` | Validate concept data and registry. |
| `benchmark validate` | `--json` | Validate benchmark definitions and resources. |
| `run-scenario` | `--scenario`, `--suite`, `--out`, `--json` | Execute replay scenarios. |
| `run-invariants` | `--protocol`, `--json` | Run the Sew invariant suite. |
| `run-benchmark` | `--output`, `--key`, `--json` | Run a benchmark and produce evidence. |
| `evidence verify-chain` | `--artifact-dir`, `--json` | Verify evidence hashes and links. |
| `evidence validate` | `--artifact-dir`, `--json` | Validate evidence artifacts. |
| `evidence coverage` | `--artifact-dir`, `--json` | Check evidence completeness. |
| `evidence backstop` | `--fast`, `--full`, `--artifact-dir`, `--json` | Run the evidence review gate. |
| `fmt check` | `--json` | Check formatting without rewriting files. |
| `lint` | `--json` | Run clj-kondo linting. |

Use `help` to print the registry's currently loaded command list. CLI failures use a non-zero process exit code; argument or unknown-command errors return `2`.

## Babashka task groups

Babashka tasks are the recommended local interface. Run `bb tasks` for the complete live list; notable task groups are summarized below.

| Group | Examples | Notes |
|---|---|---|
| Simulation and scenarios | `bb sim:run`, `bb run:scenario`, `bb run:scenario:suite`, `bb run:scenario:search` | Search is explicitly ad hoc and not canonical CI evidence. |
| Tests | `bb test`, `bb test:fast`, `bb test:framework`, `bb test:unit`, `bb test:slow`, `bb test:ci`, `bb test:evidence`, `bb test:sew`, `bb test:yield` | Prefer focused tasks during development. `bb test:ci` is CI-optimized (fast + monte-carlo, < 2 min). |
| Evidence | `bb evidence:build`, `bb evidence:sign <key>`, `bb evidence:bundle [out-dir]` | Signing requires a user-provided private key; never store it in the repository. |
| Fixtures and docs | `bb fixtures:sync`, `bb fixtures:generate-traces`, `bb regenerate-goldens`, `bb docs:scenarios` | These may update generated files. Review diffs before committing. |
| Registry and parity | `bb scenario-registry:validate`, `bb shadow:check`, `bb shadow:report` | Verify scenario registration and simulation-to-Solidity mapping. |
| Benchmark review | `bb benchmark:review <bundle.edn> [out-dir]` | Generate `BENCHMARK_SUMMARY.md`, `scenario-results.md`, and `claim-results.md` from an evidence bundle; presentation-only. |

The task descriptions in `bb.edn` are authoritative for arguments and side effects. Some tasks are research, reporting, or artifact-maintenance utilities rather than release gates.

### Benchmark reviewer package

After running a benchmark, create a human-readable companion package without modifying the authoritative EDN bundle:

```bash
bb benchmark:run --non-interactive \
  :benchmark/prf-deterministic-replay-v1 \
  --output send-to-ef/03-active-benchmark/evidence-bundle.edn \
  --scenario-output-dir send-to-ef/03-active-benchmark/scenarios

bb benchmark:review \
  send-to-ef/03-active-benchmark/evidence-bundle.edn \
  send-to-ef/03-active-benchmark
```

The formatter writes `BENCHMARK_SUMMARY.md`, `scenario-results.md`, and
`claim-results.md`. When `--scenario-output-dir` is supplied, it also links each
execution to its isolated raw replay and evidence package. The formatter shows
benchmark executions rather than assuming each row is a unique scenario, and it
labels source-directory grouping as non-semantic.

## Optional local services

XTDB is only needed for workflows that explicitly require persistence:

```bash
make xtdb
make db-setup
make xtdb-stop
make xtdb-down
```

The local Compose configuration exposes XTDB on port `5432`; see `config/docker-compose.yaml`. Solidity equivalence additionally requires a separately available Foundry checkout; see `docs/testing/RUNNING_TESTS.md`.
