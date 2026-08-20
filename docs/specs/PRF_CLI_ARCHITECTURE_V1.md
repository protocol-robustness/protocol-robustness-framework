# PRF_CLI_ARCHITECTURE_V1

Status: Draft V1

## 1. Purpose

Define the architecture of the PRF CLI — a JAR-first command surface for the
Protocol Robustness Framework. The CLI provides a unified entry point for all
PRF operations (validation, evidence, scenarios, benchmarks, maintenance)
without depending on Babashka or a project checkout.

## 2. Design Principles

### 2.1 JAR-First

The primary deployment artifact is `target/prf.jar` — a self-contained uberjar
with all dependencies. Users run `java -jar prf.jar <command>` without needing
Clojure, Babashka, or the PRF source tree.

Babashka tasks (`bb <command>`) remain the development-day wrapper. The JAR
is the deployment and CI artifact.

### 2.2 Classpath Resource Command Registry

Commands are declared in `resources/prf/commands/registry.edn` — a classpath
resource, not a file on disk. This ensures the registry is always available
inside the JAR without external configuration.

Every command carries metadata:

| Field | Description |
|---|---|
| `:command/id` | Unique keyword identifier |
| `:command/path` | CLI path vector (e.g. `["evidence" "validate"]`) |
| `:command/category` | Functional category: `:validation`, `:evidence`, `:scenario`, `:benchmark`, `:concept`, `:maintenance` |
| `:command/surface` | Always `:prf` |
| `:command/backstop-tier` | Backstop tier: `:fast`, `:default`, `:full`, or `:manual` |
| `:command/jar-availability` | `:native` (has JVM handler), `:external` (separate JAR artifact), `:none` (pure `bb` task, no JAR command) |
| `:command/runtime` | `:jvm` |
| `:command/options` | Declared CLI flags and their types |
| `:command/outputs` | Expected output paths |
| `:command/description` | Human-readable description |

### 2.3 Registry-Dispatch Parity

Every `:native` command in the registry must have a handler in the dispatch
table (`resolver-sim.cli.dispatch`), and every dispatch handler must have a
registry entry. This is enforced by `commands:validate`.

### 2.4 Lazy Handler Loading

Command namespaces use `requiring-resolve` to defer loading until first
invocation. This keeps startup time low and avoids circular dependency issues
at compile time.

## 3. Architecture

```
┌─────────────────────────────────────────────────────┐
│                    prf.jar                           │
│                                                      │
│  java -jar prf.jar <command> [options]               │
│         │                                            │
│         ▼                                            │
│  resolver-sim.cli.main/-main                         │
│         │                                            │
│         ▼                                            │
│  resolver-sim.cli.dispatch/run                       │
│         │                                            │
│         ├── Parse CLI args (tools.cli)               │
│         ├── Resolve command path                     │
│         │       │                                    │
│         │       ▼                                    │
│         │  resolver-sim.cli.registry                 │
│         │       │                                    │
│         │       ▼                                    │
│         │  resources/prf/commands/registry.edn       │
│         │                                            │
│         └── Dispatch to handler                      │
│                 │                                    │
│                 ▼                                    │
│  resolver-sim.commands.*                             │
│  (backstop, validate, evidence, scenario, etc.)       │
└─────────────────────────────────────────────────────┘
```

## 4. Build

### 4.1 Building prf.jar

```bash
bb build:cli
```

Produces `target/prf.jar` — an uberjar with main class `resolver-sim.cli.main`.

The build variant is defined in `build.clj`:
- Main namespace: `resolver-sim.cli.main`
- Dependencies: same as sew (tools.cli, buddy, protocols_src) + CLI bootstrap
- Output: `target/prf.jar` (symlink to `target/prf-uber.jar`)

### 4.2 Full Build Chain

```bash
bb build          # Builds core, sew, and CLI uberjars
bb build:cli      # Build only the CLI jar
```

### 4.3 Usage

```bash
java -jar target/prf.jar help                      # List commands
java -jar target/prf.jar backstop                   # Run default backstop
java -jar target/prf.jar evidence validate          # Validate evidence
java -jar target/prf.jar run-scenario --scenario S01  # Run a scenario
```

## 5. Command Registry

### 5.1 Schema

The registry file (`resources/prf/commands/registry.edn`) follows schema
`"prf.commands.registry.v1"`.

```clojure
{:schema-version "prf.commands.registry.v1"
 :commands [{:command/id <keyword>
             :command/path [<string> ...]
             :command/title <string>
             :command/category <keyword>
             :command/surface :prf
             :command/backstop-tier <keyword>
             :command/jar-availability :native
             :command/runtime :jvm
             :command/options [{:name <string> :type <:flag|:string> :doc <string>}]
             :command/outputs [<string> ...]
             :command/description <string>}
            ...]}
```

### 5.2 Registered Commands

| Command | Path | Category | Tier | Description |
|---|---|---|---|---|
| `:backstop` | `backstop` | validation | manual | Run the default review gate |
| `:backstop-fast` | `backstop --fast` | validation | manual | Run the fast registered review gate |
| `:commands-validate` | `commands validate` | validation | fast | Validate registry/dispatch parity |
| `:evidence-verify-chain` | `evidence verify-chain` | evidence | manual | Verify evidence chain hashes and links |
| `:evidence-validate` | `evidence validate` | evidence | manual | Validate evidence artifacts |
| `:evidence-coverage` | `evidence coverage` | evidence | manual | Check evidence coverage completeness |
| `:evidence-backstop` | `evidence backstop` | evidence | default | Run evidence forensic review gate |
| `:validate` | `validate` | validation | manual | Structural validation pipeline (lint + notebook checks) |
| `:concepts-validate` | `concepts validate` | concept | default | Validate concept data and registry |
| `:benchmark-validate` | `benchmark validate` | benchmark | default | Validate benchmark pack definitions |
| `:run-scenario` | `run-scenario` | scenario | manual | Run scenarios through the replay engine |
| `:run-invariants` | `run-invariants` | scenario | default | Run protocol invariant suite |
| `:run-benchmark` | `run-benchmark` | benchmark | full | Run benchmark and produce evidence |
| `:parallel-benchmark-run` | `parallel-benchmark-run` | benchmark | full | Bounded capability composition over `run-benchmark`: adds bounded local scenario/claimant parallelism to the same canonical algorithm. Build composed with (and validated to include) the incentive and incentive-compatibility capabilities. Canonical output invariant to parallelism; automatic worker pool default is bounded at min(scenario-count, ceiling) and never exceeds the ceiling; an explicit `--parallelism` is honored exactly; `--execution-budget` bounds total concurrency |
| `:check-aggregate` | `check-aggregate` | benchmark | manual | Run the review-aggregate check surface (three-member standard, member bit-width/key-density, and the three-member-classifications checks when an authority report is supplied) over a review round and emit the complete machine-readable result |
| `:fmt-check` | `fmt check` | maintenance | fast | Check code formatting |
| `:lint` | `lint` | validation | fast | Lint source with clj-kondo |

### 5.3 Command Categories

| Category | Purpose |
|---|---|
| `:validation` | Structural and registry validation gates |
| `:evidence` | Evidence chain verification, validation, coverage, and forensic backstop |
| `:scenario` | Scenario execution and invariant suite |
| `:benchmark` | Benchmark validation and execution |
| `:concept` | Concept registry validation |
| `:maintenance` | Code quality (fmt, lint) |

### 5.4 Backstop Tiers

| Tier | Included in | Intended for |
|---|---|---|
| `:fast` | `bb backstop:fast` | Edit-loop safety checks (< 5s) |
| `:default` | `bb backstop` | Pre-commit validation gate |
| `:full` | Manual | Full benchmark execution (minutes) |
| `:manual` | — | Explicit invocation only |

## 6. Dispatch

### 6.1 Command Resolution

The dispatch system in `resolver-sim.cli.dispatch` resolves command path strings
to `[:command-id positional-args]` pairs via a `case` expression:

```
"backstop"               → [:backstop []]
"evidence validate"      → [:evidence-validate []]
"run-scenario"           → [:run-scenario args]
```

### 6.2 Handler Map

Each command ID maps to a handler var, resolved lazily:

```clojure
{:backstop              resolver-sim.commands.backstop/run-default
 :backstop-fast         resolver-sim.commands.backstop/run-fast
 :commands-validate     resolver-sim.commands.registry-validate/validate
 :evidence-verify-chain resolver-sim.commands.evidence/verify-chain
 :evidence-validate     resolver-sim.commands.evidence/validate
 :evidence-coverage     resolver-sim.commands.evidence/coverage
 :evidence-backstop     resolver-sim.commands.evidence/run-backstop
 :validate              resolver-sim.commands.validate/run
 :concepts-validate     resolver-sim.commands.concepts/validate
 :benchmark-validate    resolver-sim.commands.benchmark/validate
 :run-scenario          resolver-sim.commands.scenario/run
 :run-invariants        resolver-sim.commands.invariants/run
 :run-benchmark         resolver-sim.commands.run-benchmark/run
 :check-aggregate       resolver-sim.commands.check-aggregate/run
 :fmt-check             resolver-sim.commands.validate/fmt-check
 :lint                  resolver-sim.commands.validate/lint}
```

### 6.3 Handler Contract

Every handler receives an opts map and returns either:

```clojure
;; Success
{:exit-code 0 :message "ok"}

;; Failure
{:exit-code <non-zero> :message "error description"}
```

Or an integer exit code directly (for simple pass/fail commands).

### 6.4 Global CLI Options

| Flag | Type | Default | Description |
|---|---|---|---|
| `-h`, `--help` | flag | — | Show help |
| `-j`, `--json` | flag | — | Output results as JSON |
| `--artifact-dir` | string | `target/run` | Evidence artifact directory |
| `--scenario` | string | — | Scenario ID to run |
| `--scenario-file` | string | — | Scenario file path |
| `--suite` | string | — | Suite name |
| `--pack` | string | — | Benchmark pack name |
| `--fast` | flag | — | Run fast tier only |
| `--full` | flag | — | Run full tier |
| `--strict` | flag | — | Strict validation mode |
| `--explain` | flag | — | Explain results in detail |
| `--out` | string | `target/report` | Output directory |
| `--output` | string | — | Output path for evidence bundle |
| `--protocol` | string | `sew-v1` | Protocol ID |
| `--key` | string | — | Path to private key |

## 7. Parity with Babashka Tasks

The JAR CLI mirrors the `bb <command>` surface. Each registered command carries
a `:command/surface` (how it is exposed) and a `:command/jar-availability`
(whether and where it is available as a JAR command). `commands:validate`
enforces parity between the registry, the dispatch table, and the bb task
definitions.

### Fixed-case availability matrix

Every command falls into exactly one (surface × jar-availability) case:

| Surface | Jar availability | Runtime | Count | Meaning |
|---|---|---|---|---|
| `:prf` | `:native` | `:jvm` | 50 | Standard PRF JAR (`prf.jar`) CLI commands |
| `:dev` | `:native` | `:jvm` | 1 | Developer-only JAR command (`run-invariants`) |
| `:community` | `:native` | `:jvm` | 9 | Community task commands shipped in the JAR |
| `:researcher` | `:native` | `:jvm` | 3 | Researcher interaction commands (`disagree`/`approve`/`check`) |
| `:bb` | `:external` | `:bb` | 9 | `bb` task requiring an external JAR artifact |
| `:bb` | `:none` | `:bb` | 12 | Pure `bb` task; not a JAR command |
| **Total** | | | **84** | All registered commands |

`jar-availability` describes **registry/implementation availability**, not
inclusion in every build artifact. `:native` means the command is implemented
as a JVM command and is distributable within a PRF JAR; whether a particular
built JAR actually contains it is a separate concern. Three distinct layers:

- **Registry availability** — `:native` commands are real JVM commands and must
  have a dispatch handler in `resolver-sim.cli.dispatch` (enforced by
  `commands:validate`). `:external` and `:none` commands are `:bb`-surface tasks,
  not JVM-dispatchable from a PRF JAR.
- **Artifact inclusion** — a command may be `:native` yet still absent from a
  given built artifact. Native commands in `sew-command-ids` (`dispatch.clj`)
  are **artifact-gated** on the Sew distribution and are unavailable in a
  PRF-core-only artifact.
- **Runtime availability** — `command-available?` (`dispatch.clj`) returns
  false when a Sew-gated command is invoked on a distribution that lacks Sew;
  the command is not runnable there even though it is a `:native` JVM command.

`:jar-availability :external` — the `bb` task builds or invokes a JAR artifact
that is not part of the standard `prf.jar` (e.g. attestation signing,
test-suite runners). It is registered as `:bb`-surface with a `:command/bb-task`
and is reported by `commands:validate` as a bb task, not as a runnable `prf.jar`
command. Presence, freshness, and version compatibility of the external artifact
are the responsibility of the invoking `bb` task, not the registry.

### bb-surface commands wrapping native JAR commands

| bb task | JAR command |
|---|---|
| `bb backstop` | `java -jar prf.jar backstop` |
| `bb backstop:fast` | `java -jar prf.jar backstop --fast` |
| `bb commands:validate` | `java -jar prf.jar commands validate` |
| `bb validate` | `java -jar prf.jar validate` |
| `bb run:scenario` | `java -jar prf.jar run-scenario` |
| `bb fmt:check` | `java -jar prf.jar fmt check` |
| `bb lint` | `java -jar prf.jar lint` |

### PRF-only JAR commands (no `bb` wrapper)

The remaining `:native` JAR commands are invoked directly via the JAR and have
no `bb` wrapper — e.g. `evidence verify-chain`, `evidence validate`,
`evidence coverage`, `evidence backstop`, `concepts validate`,
`benchmark validate`, `run-invariants`, `run-benchmark`, `assure-package`,
`pre-application checks`, `scenario list|pick|compare|run-search`, `suite list`,
`verify-run`, `verify-benchmark`, `verify-scenario`, `notebook focus|latest|runs`,
and the `community task *` commands. See `resources/prf/commands/registry.edn`
for the authoritative full list.

## 8. Related Tests

| Test | What it covers |
|---|---|
| `commands-validate` | Registry-dispatch parity, schema validation, bb wrapper parity |

## 9. References

| Document | Location |
|---|---|
| CLI entry point | `src/resolver_sim/cli/main.clj` |
| Command dispatch | `src/resolver_sim/cli/dispatch.clj` |
| Command registry loader | `src/resolver_sim/cli/registry.clj` |
| Command registry (classpath resource) | `resources/prf/commands/registry.edn` |
| Registry validation command | `src/resolver_sim/commands/registry_validate.clj` |
| CLI uberjar build | `build.clj` (variant `:cli`) |
| Build task definition | `bb.edn` (`build:cli` at line 1211) |
| Command listing (bb surface) | `docs/specs/COMMANDS.md` |
| Backstop spec | `src/resolver_sim/commands/backstop.clj` |
