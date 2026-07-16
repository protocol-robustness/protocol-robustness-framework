# Sew benchmark execution

Benchmarks are executed by the full Sew distribution:
`target/prf-runner-sew-0.1.0-uber.jar`. It includes the supported Sew
scenario corpus, benchmark registries, suites, concepts, and configuration as
classpath resources. It runs from any directory without a source checkout.

## Build

```bash
bb build:sew
```

Output: `target/prf-runner-sew-0.1.0-uber.jar`

### Prerequisites

- Clojure CLI (`clojure` on PATH)
- Java 17+

## What's in the JAR

| Contents | Path (inside JAR) |
|----------|-------------------|
| Benchmark pack registry | `benchmarks/registry.edn` |
| Pack definitions | `benchmarks/packs/*/` |
| Scoring rules | `benchmarks/scoring/*.edn` |
| Benchmark-local concepts | `benchmarks/concepts/*.edn` |
| Global concept registry | `data/concepts/registry.edn` |
| Executable scenarios | `scenarios/edn/` |
| Reference validation suite | `suites/reference-validation-v1/` |
| Evidence config | `config/evidence.json` |

All internal paths use the `resource:` scheme and are loaded from the
classpath. No filesystem access required.

## Run

```bash
# List supported benchmark IDs
java -jar target/prf-runner-sew-0.1.0-uber.jar benchmark list

# Run a benchmark into one fresh, authoritative bundle root
java -jar target/prf-runner-sew-0.1.0-uber.jar \
  run-benchmark sew/sew-force-authorisation-custody-v1 \
  --run-root ./runs/force-authorisation

# Run an external benchmark input when it is supported by the installed corpus
java -jar target/prf-runner-sew-0.1.0-uber.jar \
  run-benchmark /absolute/path/custom-benchmark.edn \
  --run-root ./runs/custom-benchmark
```

### Exit codes

| Code | Meaning |
|------|---------|
| 0 | Orchestration and evidence finalization completed; conclusion may be pass, fail, or inconclusive |
| non-zero | Replay, finalization, or command failure; no completion marker is written |

## Add a custom benchmark pack

1. Create a pack directory: `benchmarks/packs/<name>/`
2. Add a registry file: `benchmarks/packs/<name>/registry.edn`
3. Register it in `benchmarks/registry.edn` under `:packs`
4. Rebuild the JAR

See `BENCHMARK_PACK_SPEC_V1.md` for the pack registry schema.

## Directory layout

```
benchmarks/
├── registry.edn              # Top-level pack index
├── packs/
│   └── sew/                  # Pack directory
│       ├── registry.edn      #   Pack registry
│       └── escrow-dispute-v1.edn  #   Benchmark manifest
├── scoring/                  # Scoring rule definitions
├── concepts/                 # Benchmark-local concepts
└── README.md                 # This file
```
