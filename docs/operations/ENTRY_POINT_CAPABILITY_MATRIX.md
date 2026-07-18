# Entry-Point Capability Matrix

This matrix states the currently supported assurance claim for each execution
entry point. It prevents an execution-capable path from being mistaken for a
finalized canonical package path.

| Path | Supported claim | Not currently claimed |
|---|---|---|
| `resolver-sim.io.scenario-runner/run-and-report` | Inner execution, replay output, scenario-local evidence, and partial/diagnostic output | Complete canonical package, run finalization, package index, completion seal, or package-level runnability |
| `resolver-sim.commands.scenario-orchestration/run-scenario!` | Root-owned canonical single-scenario lifecycle: scenario finalization, runner/run finalization, registry, assurance, package index, and completion | Final package assurance is still under active completion; do not infer verifier-grade runnable/release status until the completion-first package validator is enabled |
| `run-benchmark --run-root` | Root-owned benchmark lifecycle and benchmark-specific finalization artifacts | A supported package-validation profile; benchmark currently returns `:package/unsupported-run-type` to the single-scenario package validator |
| Direct benchmark output | Legacy benchmark execution/output | Root-owned canonical package lifecycle or validated package profile |
| Named suites, registry suites, fixture suites | Execution/reporting only | Canonical aggregate package finalization or aggregate runnable package validation |
| Parallel scenario paths | Execution only, subject to runner behavior | Canonical aggregate package: no coordinator-owned exact-set reconciliation, isolated worker contract, or deterministic aggregate package profile is currently implemented |

## Boundary rules

- A successful replay is not evidence that a complete package exists.
- Semantic pass/fail is independent from package integrity and future package
  runnability. An expected semantic failure may still be a complete verified
  package when the corresponding profile is implemented.
- `manifest/run-package-index.json` is the intended immutable package boundary
  for a finalized structured run. `completion.json` is its terminal lifecycle
  seal and commits to the exact persisted index bytes.
- `run-and-report` owns inner execution and scenario-local finalization only.
  `run-scenario!` owns the outer structured lifecycle. It must not write a
  second scenario finalization.
- Unsupported profiles fail closed. They must not inherit the
  `:single-scenario` package contract.
- A finalized `:single-scenario` package declares `:run/id`, `:scenario/id`,
  and `:execution/id` in its package index. Its execution ID is exactly
  `execution:<run-id>`. This rule is profile-specific and must not be reused
  for benchmarks or suites, which require immutable-plan-derived identities
  for multiple executions.
- The snapshot is the canonical scenario identity source: orchestration reads
  the scenario ID only after snapshotting input bytes, and the package commits
  to that snapshot through its frozen artifact closure.
