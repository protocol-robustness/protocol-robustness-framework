# Build reference

## JAR Variants

| JAR | Contents | Entry point | Use case |
|-----|----------|-------------|---------|
| `prf.jar` | Framework and unified CLI; no Sew implementation or corpus | `resolver-sim.cli.main` | Framework-compatible external inputs |
| `prf-runner-sew-<version>-uber.jar` | Framework, Sew implementation, supported corpus, benchmarks, suites, and unified CLI | `resolver-sim.cli.main` | Canonical Sew scenario and benchmark execution |

## Building

```bash
bb build:prf
bb build:sew
# or: bb build
```

Outputs are `target/prf.jar` and `target/prf-runner-sew-<version>-uber.jar`.

## Portable Usage

The full `prf-runner-sew` JAR includes the supported Sew corpus as classpath
resources. It runs from any directory and does **not** require:
- A git repository
- Source code checkout
- Scenario/benchmark/concept files on the filesystem

```bash
# List available packaged benchmarks (works anywhere)
java -jar prf-runner-sew-0.1.0-uber.jar benchmark list

# Run a packaged benchmark into an exact fresh bundle root
java -jar prf-runner-sew-0.1.0-uber.jar \
  run-benchmark force-authorisation-custody-v1 \
  --run-root ./runs/benchmark

# Run an external scenario input into an exact fresh bundle root
java -jar prf-runner-sew-0.1.0-uber.jar \
  run-scenario /absolute/path/scenario.edn \
  --run-root ./runs/scenario
```

### External/experimental concepts and packs

Use explicit external inputs where supported, always with a distinct
`--run-root`. Do not use the legacy `--output` export path as a bundle root.

## Resource path scheme

Classpath resource paths used for JAR portability:

- `resource:benchmarks/registry.edn` — benchmark pack registry
- `resource:benchmarks/scoring/*.edn` — scoring rule definitions
- `resource:benchmarks/concepts/*.edn` — benchmark-local concepts
- `resource:data/concepts/registry.edn` — global concept registry
- `resource:suites/reference-validation-v1/manifest.edn` — reference validation suite
- `resource:config/evidence.json` — evidence chain configuration

The scenario directory `*scenario-dir*` defaults to `scenarios/edn` (bare
filesystem path). The `scenarios/` directory is included in `:paths` and in
the JAR build (`scripts/build.clj`), so the same set of files serves both
development and packaged use — no separate `resources/scenarios/` copy
is needed.

External paths use `file:` prefix or bare filesystem paths. The resolution
order for bare paths is: filesystem first, classpath second.

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Generic error or benchmark failure |
| 2 | Unknown benchmark ID |
| 3 | Missing concept reference |
| 4 | Missing scenario file |
| 5 | Invalid parameters |
| 6 | Duplicate conflicting registries |
