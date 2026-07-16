# Quick Start: Protocol Robustness Framework

This guide uses the repository's current Clojure and Babashka entry points. It does not require the legacy Python/gRPC workflow.

## Requirements

### Required

- **JDK 21** — the version used by the GitHub Actions workflows.
- **Clojure CLI** — used for aliases, builds, and direct namespace execution.
- **Babashka (`bb`)** — used by the supported task wrappers, including `bb test` and `bb run:scenario`.

Verify the installation from the repository root:

```bash
java -version
clojure -Sdescribe
bb --version
```

### Optional tools

| Tool | Needed for |
|---|---|
| Python 3 | Evidence helper scripts invoked by some scenario and test tasks. |
| Docker Compose | Local XTDB services (`make xtdb`). |
| Foundry (`forge`) | Solidity trace-equivalence checks only. |

## Choose a project view

The default classpath contains the protocol-agnostic framework. Add Sew when working with the included protocol model:

```bash
clojure -M:with-sew
clojure -M:test:with-sew
```

`workspaces/with-sew/` provides the same full-stack view without requiring the alias. See `workspaces/MAP.md`.

## First successful run

Run one registered scenario through the Sew replay path:

```bash
bb run:scenario <scenario-id>
# Output: results/runs/<scenario-slug>-<timestamp>-<nonce>/
```

To discover scenario IDs, inspect `docs/scenarios.md` or search the `scenarios/` directory. Use a display level when investigating results:

```bash
bb run:scenario <scenario-id> --result-display-level standard
```

Scenario artifacts are written below `results/`; this directory is generated local output and is not source material.

## Validate the repository

Run the canonical validation gate:

```bash
bb test
# Equivalent direct runner:
./scripts/test.sh all
```

`all` runs unit, generator, contract, invariant, fixture-suite, reference-validation, coverage, and triage targets. It also runs a representative Monte Carlo target unless invoked in fast mode. See `docs/testing/RUNNING_TESTS.md` for target-level commands and artifacts.

For an edit-loop-friendly gate:

```bash
bb test:fast
# Equivalent direct runner:
./scripts/test.sh fast
```

For framework-only or Sew-inclusive unit coverage:

```bash
bb test:framework
bb test:unit
```

## Common next steps

```bash
# Run the invariant suite through the registered CLI
clojure -M:cli -- run-invariants --protocol sew-v1

# Run a simulation from an EDN parameter file
bb sim:run -p data/params/baseline.edn

# Regenerate the scenario documentation table
bb docs:scenarios

# Check generated documentation is current
make docs-as-code-check

# Run PRF CLI commands from a built jar
java -jar target/prf-runner-core-<version>.jar help
```

## Where to go next

- `docs/reference/usage.md` — supported CLI and Babashka command reference.
- `docs/testing/RUNNING_TESTS.md` — test targets and validation artifacts.
- `docs/architecture/ADAPTER_AUTHORING_GUIDE.md` — protocol adapter interfaces.
- `schemas/README.md` — machine-readable schema catalog.
- `docs/specs/COMMANDS.md` — review-gate command tiers.

## Troubleshooting

**`bb` is not found** — install Babashka, or use the corresponding `clojure` / `scripts/test.sh` commands where documented.

**A Sew namespace cannot be found** — add `:with-sew` to the Clojure command or use a `bb` task that already selects it.

**A test task cannot execute a Python helper** — install Python 3; evidence-oriented tasks call scripts under `scripts/evidence/`.

**Generated files differ after a command** — consult the command's documentation before committing generated output. `results/` is local output; generated documentation has explicit `make *-check` targets.
