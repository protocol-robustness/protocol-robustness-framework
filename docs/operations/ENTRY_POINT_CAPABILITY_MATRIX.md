# Entry-Point Capability Matrix

This matrix states the currently supported assurance claim for each execution
entry point. It prevents an execution-capable path from being mistaken for a
finalized canonical package path.

| Path | Supported claim | Not currently claimed |
|---|---|---|
| `resolver-sim.io.scenario-runner/run-and-report` | Inner execution, replay output, scenario-local evidence, and partial/diagnostic output | Complete canonical package, run finalization, package index, completion seal, or package-level runnability |
| `resolver-sim.commands.scenario-orchestration/run-scenario!` | Root-owned canonical single-scenario lifecycle with a pre-completion package gate: scenario finalization, runner/run finalization, registry validation, canonical assurance, required DAG, package index, completion seal, and declaration-driven value-at-risk artifact validation | `verify-scenario` is not yet the completion-first package-validation consumer; unsigned content integrity is not release/signer assurance; no production scenario has yet opted into a timestamped value-at-risk observation |
| `run-benchmark --run-root` | Root-owned canonical benchmark package lifecycle: immutable execution plan, benchmark content finalization, assurance and conservation artifacts, unsigned canonical integrity, completion-bound `:benchmark` package index, final registry/validation, terminal completion, and completion-first `verify-benchmark` validation | Unsigned integrity is not signer/operator assurance; signed forensic assurance and runtime-isolation guarantees remain deferred |
| `parallel-benchmark-run --run-root` | Bounded capability composition over the above `run-benchmark` path: same canonical algorithm (`run-with-root!` → `run->benchmark`) with bounded local scenario/claimant parallelism. Build composed with (and validated to include) the incentive and incentive-compatibility capabilities. Canonical output invariant to parallelism; automatic worker pool default bounded at min(scenario-count, ceiling) and never exceeding the ceiling; an explicit `--parallelism` honored exactly; exact-set → plan-ordinal (bound-sequence) locality preserved; `--execution-budget` bounds total concurrency. Ordinary `verify-benchmark` verifies its output unchanged | Same deferred assurance as `run-benchmark`; neither command claims signed operator-level or runtime-isolation guarantees |
| Direct benchmark output | Legacy benchmark execution/output | Root-owned canonical package lifecycle or validated package profile |
| Named suites, registry suites, fixture suites | Execution/reporting only | Canonical aggregate package finalization or aggregate runnable package validation |
| Parallel scenario paths | Execution only, subject to runner behavior | Canonical aggregate package (except via `parallel-benchmark-run`) |
| `parallel-benchmark-run` (row above) | Canonical aggregate package with coordinator-owned exact-set reconciliation (exact-set → frozen-plan-ordinal locality) and completion-bound `:benchmark` package index | That implementation is scoped to the benchmark runner; other parallel/scenario entry points do not inherit it |

## Boundary rules

- A successful replay is not evidence that a complete package exists.
- Semantic pass/fail is independent from package completeness, content integrity,
  and runnability. A completed canonical single-scenario package may faithfully
  report a semantic failure.
- `manifest/run-package-index.json` is the immutable package boundary for a
  finalized structured single-scenario run. `completion.json` is its terminal
  lifecycle seal: it commits to the contained index path, exact persisted index
  SHA-256, and exact byte length. Canonical lifecycle writers may subsequently
  remove `.run-state` only; this does not claim filesystem immutability against
  external processes.
- `run-and-report` owns inner execution and scenario-local finalization only.
  `run-scenario!` owns the outer structured lifecycle. It must not write a
  second scenario finalization.
- Package validation begins from `completion.json`, then verifies the exact
  persisted package-index bytes, validates the supported package profile,
  validates the indexed closure and semantic finalizations, and reconciles
  authoritative commitments.
- Semantic hashes validate schema-defined logical payloads. Persisted-byte
  commitments validate exact on-disk bytes; neither replaces the other.
- A scenario may optionally declare one `scenario-value-at-risk.v1` observation.
  The current narrow contract supports only a workflow-scoped, post-event,
  non-negative `scenario-native-integer` field read. Its declared UTC timestamp,
  event index, event ID, selector, scope, asset, and amount are validated before
  canonical completion. The standalone `manifest/value-at-risk.json` artifact is
  CORE-inventoried and is embedded exactly in
  `manifest/summary.json.value_at_risk`. Declaration-free scenarios persist a
  stable `not-declared` observation and remain supported.
- The value-at-risk artifact is not yet an EF-facing verified claim: before a
  production scenario opts in, completion-first package verification must
  independently load the package-bound input snapshot and replay output,
  revalidate the standalone observation with trusted provenance/source identity,
  and require exact equality with the summary embedding.
- Unsigned canonical integrity establishes content integrity only. Release
  eligibility requires separate signer/operator assurance.
- `verify-benchmark` is completion-first package validation for the supported
  `:benchmark` package profile. It verifies the exact completion-bound package
  index bytes, declared role closure, immutable execution plan/index/summary
  reconciliation, benchmark assurance and conservation, content finalization,
  canonical integrity, outer registry/validation, input-set commitment, and
  terminal completion bindings.
- Benchmark and single-scenario package profiles are distinct. A benchmark does
  not inherit single-scenario identity, scenario-finalization, or execution-DAG
  requirements. Suites, fixture suites, registry suites, and parallel aggregate
  profiles remain unsupported and fail closed with `:package/unsupported-run-type`. 
- A finalized `:single-scenario` package declares `:run/id`, `:scenario/id`,
  and `:execution/id` in its package index. Its execution ID is exactly
  `execution:<run-id>`. This rule is profile-specific and must not be reused
  for benchmarks or suites, which require immutable-plan-derived identities
  for multiple executions.
- The snapshot is the canonical scenario identity source: orchestration reads
  the scenario ID only after snapshotting input bytes, and the package commits
  to that snapshot through its frozen artifact closure.
