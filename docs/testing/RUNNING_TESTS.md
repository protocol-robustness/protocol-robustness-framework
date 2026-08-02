# Running Simulations & Tests

## Quick Start

### Canonical test entrypoint (recommended)
```bash
bb test
# Equivalent direct runner:
./scripts/test.sh all
```
This is the authoritative repository-wide validation command. It runs `unit`,
`generators`, `contracts`, `invariants`, `suites`, `reference-validation`,
`coverage`, and `triage`; it also runs `monte-carlo` unless fast mode is selected.

For a focused edit-loop gate, run:

```bash
./scripts/test.sh fast
```

`fast` runs `unit`, `generators`, `contracts`, `invariants`, `suites`, and
`reference-validation`. Use individual targets below when narrowing a failure.

### Run comprehensive suite with full reporting

Use `bb test` or `./scripts/test.sh all` as the canonical validation gate.

### Run specific canonical targets
```bash
./scripts/test.sh unit
./scripts/test.sh generators
./scripts/test.sh contracts
./scripts/test.sh invariants
./scripts/test.sh suites
./scripts/test.sh triage
```


### Equivalence suites: per-trace expected outcomes

Fixture suites can now declare mixed `:traces` entry shapes:

1. **Keyword trace ref** (default behavior, expected `:pass`):

```clojure
:traces [:traces/s48-max-escalation-exact-boundary]
```

2. **Map entry with explicit expected outcome/halt reason**:

```clojure
:traces [{:trace :traces/s49-max-escalation-plus-one-rejected
          :expected-outcome :invalid
          :expected-halt-reason :adversarial-requires-analysis}]
```

This avoids false failures for intentional rejection/negative-path traces in equivalence gates.

## Concurrent Test Execution

`bb test` and `bb backstop` share a global lock on `results/.test-artifact.lock` to
prevent clobbering `results/test-artifacts/`.  For concurrent runs, use the
`:concurrent` variants — each writes to a separate artifact directory and skips the
lock:

| Task | Artifact dir | Lock | Runs alongside |
|---|---|---|---|
| `bb test` | `results/test-artifacts` | Yes | — (serial only) |
| `bb test:concurrent` | `results/test-artifacts-<timestamp>` | No | Anything |
| `bb backstop` | `results/test-artifacts` | Yes | — (serial only) |
| `bb backstop:concurrent` | `results/backstop-artifacts` | No | Anything |
| `bb backstop:fast:concurrent` | `results/backstop-fast-artifacts` | No | Anything |
| `bb test:quick-sew:concurrent` | `/tmp/parallel-test-*` (noop) | No | Anything |
| `bb test:quick:concurrent` | `/tmp/parallel-test-*` (noop) | No | Anything |

For example, to run the full validation gate and backstop in parallel:

```bash
bb test:concurrent &
bb backstop:concurrent &
```

These work because each process writes to its own artifact directory (`PRF_ARTIFACT_DIR`)
and neither acquires the global lock.

## 🔴 Required: Trace Equivalence Verification (Model + Solidity, manifest-scoped)

This is a **mandatory release check** for equivalence claims on the
manifest-selected trace subset. See `etc/trace-solidity-manifest.edn`
for the current scope (18 traces across 3 suites) and
`docs/review/EF_REVIEW_GUIDE.md` for the review procedure.

Run **both** layers:

1) **Model-side equivalence gate (Clojure)**

```bash
./scripts/test.sh equivalence-new
```

2) **On-chain trace replay + projection comparison (Forge Solidity)**

Requires a local checkout of the Sew Solidity contracts. Set `SEW_SOLIDITY_PATH` to the checkout root and run:

```bash
forge test --match-contract TraceEquivalenceTest -vvv
```

Optional focused probe (single canonical trace):

```bash
forge test --match-contract TraceEquivalenceTest --match-test test_trace_create_release -vvv
```

### Why this is required

- Clojure equivalence suites validate simulator/model semantics and gate fixture quality.
- `TraceEquivalenceTest` replays fixture traces on live EVM contracts and asserts per-step projection equivalence.

You should not claim trace equivalence for the manifest-selected subset unless
**both commands pass**.

### Machine-readable CI artifacts

When running `./scripts/test.sh all`, the script writes a JSON summary:

```text
results/test-artifacts/test-summary.json
```

It includes per-target status, exit codes, durations, and log file paths.

### Baseline vs scenario comparison (research-shareable)

Use this to generate a compact, researcher-facing diff between two replay outputs.

```bash
bb trace:compare \
  --baseline results/baseline.trace.json \
  --candidate results/candidate.trace.json \
  --out-dir results/trace-compare/example

# Compare scenario replay at an older git commit vs HEAD
bb sim:diff --baseline <commit-sha> --scenario data/fixtures/traces/s46a-settlement-before-escalation-window-edge.trace.json

# Structural world diff (first divergence point; Clojure io/diff)
bb trace:structural-diff --baseline results/a.json --candidate results/b.json
```

Outputs:
- `results/trace-compare/example/comparison.json`
- `results/trace-compare/example/comparison.md`

The report includes:
- outcome + events processed
- **projection-hash** match/mismatch (terminal replay step)
- **first structural world divergence** (seq, action, field diff when traces differ)
- key metric deltas
- terminal-state count differences
- a single headline line you can paste into research notes

### Cross-repository trace sync (Clojure simulation → Solidity)

CDRS v0.2 traces selected for Solidity equivalence are managed through
a manifest-bound workflow. See `etc/trace-solidity-manifest.edn` for
the canonical trace list and `docs/review/EF_REVIEW_GUIDE.md` for the
review procedure.

```bash
# Export selected traces to sew-protocol
bb trace:solidity:sync --sew-repo ../sew-protocol

# Verify integrity (read-only gate)
bb trace:solidity:verify --sew-repo ../sew-protocol
```

### Transition/guard coverage release gate

```bash
bb test   # runs scripts/validate/coverage_gates.py (max-unhit-transitions gate)
```

Prints:
- transition hit frequencies
- guard hit frequencies
- purpose-grouped hit maps
- explicit **unhit transition backlog** for release-candidate closure

The generated `docs/generated/transition-guard-catalog.md` (via
`make core-generated-docs-generate`) exposes the per-transition hit counts and
the unhit backlog.

### Adversarial profitability surfaces (rational-agent coverage)

```bash
clojure -M:run -- -p data/params/phase-i-all-mechanisms.edn   # 1D profitability sweep
```

Outputs:
- `results/profitability-surfaces/<timestamp>/surface.csv`
- `results/profitability-surfaces/<timestamp>/surface.json`
- `results/profitability-surfaces/<timestamp>/regions.json`
- `results/profitability-surfaces/<timestamp>/promotions.json`

For the expanded promotion backlog, run the multi-epoch sweep:

```bash
clojure -M:run -- -p data/params/phase-j-baseline-stable.edn -m
```

### Run specific phase
```bash
clojure -M:run -- -p data/params/phase-i-all-mechanisms.edn    # Phase I (1D sweeps)
clojure -M:run -- -p data/params/phase-j-baseline-stable.edn -m  # Phase J (multi-epoch)
clojure -M:run -- -p data/params/baseline.edn                  # Baseline scenario
```

---

## Individual Simulations

### Baseline (control scenario)
```bash
clojure -M:run -- -p data/params/baseline.edn
```
Expected output:
- Honest profit: 150.00
- Malice profit: 150.00
- Dominance: 1.0 (neutral)

### Phase I: Detection Mechanisms (1D sweep)
```bash
clojure -M:run -- -p data/params/phase-i-all-mechanisms.edn -s
```
Expected output:
- All strategies pass
- Malice profit: -199.60 (deeply unprofitable)

### Phase I: 2D Sensitivity Sweep
```bash
clojure -M:run -- -p data/params/phase-i-2d-all-mechanisms.edn -s
```
Sweeps detection vs slash multiplier combinations.

### Phase H: Realistic Bond Mechanics
```bash
clojure -M:run -- -p data/params/phase-h-realistic-mechanics.edn
```
Expected output:
- Escape: BLOCKED (freeze + unstaking + appeal)
- Bond security: PROVEN

### Phase G: 2D Parameter Sweep
```bash
clojure -M:run -- -p data/params/phase-g-sensitivity-2d.edn -s
```
Identifies break-even point: 10% detection + 2.5× slash

### Phase J: Multi-Epoch Reputation

```bash
# Baseline (control - no detection decay)
clojure -M:run -- -p data/params/phase-j-baseline-stable.edn -m

# Governance decay (50% detection loss per epoch)
clojure -M:run -- -p data/params/phase-j-governance-decay.edn -m

# Governance failure (detection → 0 at epoch 5)
clojure -M:run -- -p data/params/phase-j-governance-failure.edn -m

# Sybil re-entry test
clojure -M:run -- -p data/params/phase-j-sybil-re-entry.edn -m
```

### Phase J: Strategy Adaptation Defaults

Use `:strategy-adaptation` to make adaptation assumptions explicit. `nil` means "resolve from params or fallback default", and resolved values are emitted in epoch evidence under `:defection`.

| Setting | Default | Meaning | Override |
|---|---|---|---|
| `:slash-risk-inhibition` | `0.7` | Reduces honest→malicious switching when slash risk is high | `:strategy-adaptation` |
| `:max-switch-probability` | `0.8` | Upper bound on per-epoch strategy switch probability | `:strategy-adaptation` or top-level fallback |
| `:detection-probability` | `0.1` | Fallback detection probability for load snapshot | `:strategy-adaptation` or scenario params |
| `:slash-multiplier` | `2.0` | Fallback penalty multiplier for load snapshot | `:strategy-adaptation` or scenario params |
| `:allowed-targets` | `#{:honest :lazy :malicious}` | Strategies load-optimal adaptation may select | `:strategy-adaptation` |
| `:blocked-target-policy` | `:inconclusive` | How to classify blocked optimal targets (`:inconclusive`, `:fail`, `:warn`) | `:strategy-adaptation` |

Policy intent:
- `:inconclusive` — conservative benchmark evidence.
- `:fail` — strict scenario validation.
- `:warn` — exploratory runs where classification continues with diagnostics.

Expected Phase J output (all scenarios):
- 10 epochs executed
- Honest cumulative profit: 1500
- Malice cumulative profit: 1200-1400
- Reputation prevents profitable exit/re-entry

---

## Output & Results

### Results Directory
All results saved to `results/` with timestamp:
```
results/
├── 2026-02-12_15-45-08/
│   ├── COMPREHENSIVE_REPORT.md      # Main report
│   ├── 01-baseline.log              # Test logs
│   ├── 02-phase-i-1d.log
│   ├── 2026-02-12_15-45-11_baseline-v1/    # Simulation outputs
│   │   ├── summary.edn              # Raw results
│   │   ├── metadata.edn             # Test metadata
│   │   └── results.csv              # Profit distribution (if applicable)
│   └── ...
```

### Understanding Results

**Key metrics by phase:**

| Phase | Key Metric | Interpretation |
|-------|-----------|-----------------|
| Baseline | Dominance ratio | Should be 1.0 (neutral) |
| Phase I | Malice profit | Should be negative (< -100) |
| Phase H | Escape-count | Should be 0 (impossible) |
| Phase G | Break-even | ~10% detection, 2.5× slash |
| Phase J | Honest vs Malice | Grows ~13× difference (1500 vs ~1300) |

---

## Troubleshooting

### Current baseline

Canonical invariant suite (`bb test:invariants`): **82/99 pass** (S01–S100) at baseline date 2026-05-29.

Remaining failures are known behavioural gaps; see `docs/scenarios.md` for per-scenario status.

Unit tests (`./scripts/test.sh unit`): 25 known failures + 2 errors in stochastic
model tests — pre-existing, do not block invariant or integration work.

Contributors can run `clojure -M:run -- --invariants` in ~1 second to check the
invariant gate without the full suite.

### "Could not find artifact io.github.nextjournal:clerk"
Clerk report generation not available (network/dependency issue).
- This is optional - simulation results still saved to markdown
- Reports still generated without Clerk

### Clojure command not found
Install Clojure:
```bash
# macOS
brew install clojure

# Linux (download script)
curl -O https://download.clojure.org/install/linux-install-1.11.0.sh
chmod +x linux-install-1.11.0.sh
sudo ./linux-install-1.11.0.sh
```

### Simulations hang or crash
Check system resources:
```bash
# Verify Clojure is running
ps aux | grep clojure

# Check available memory
free -h

# Try with smaller parameter set
clojure -M:run -p data/params/baseline.edn
```

---

## CLI Reference

```
Usage: clojure -M:run [options]

Options:
  -p, --params PATH  data/params/baseline.edn  Path to params.edn file
  -o, --output DIR   results              Output directory for results
  -s, --sweep                             Run strategy sweep
  -m, --multi-epoch                       Run Phase J multi-epoch simulation
  -h, --help                              Show this help
```

**Important**: Use `--` before arguments when using wrapper scripts:
```bash
clojure -M:run -- -p data/params/phase-i-all-mechanisms.edn -s
#                 ^^^ Required separator
```

---

## System Requirements

- **Clojure**: 1.12.0+
- **JVM**: 11+
- **Memory**: 2GB minimum, 4GB recommended
- **CPU**: Multi-core (parallel trials)
- **Time**: 30s baseline → 2min per phase

---

## Phase J CLI Integration (NEW)

Phase J multi-epoch simulation now integrated into main CLI:

### Cancellation game-theory next steps

For a focused roadmap on upgrading cancellation analysis from proxy checks to
stronger game-theoretic evidence, see:

- `docs/testing/CANCELLATION_GAME_THEORY_GAP_CHECKLIST.md`

```bash
# Run baseline (stable) scenario
clojure -M:run -- -p data/params/phase-j-baseline-stable.edn -m

# Run governance failure test
clojure -M:run -- -p data/params/phase-j-governance-failure.edn -m -o my-results/
```

Output includes:
- Per-epoch metrics (honest, malice, dominance ratio)
- Resolver exit tracking
- Cumulative profit by strategy
- Win rate statistics
- Multi-epoch aggregated stats

---

## Monte Carlo oracle fixtures (`:oracle-fixture`, `:fixed-or`)

MC-only controls for detection and appeal rolls (`replay.clj` ignores these).
Not the same as invariant trace fixtures under `data/fixtures/traces/`.

`:on-exhaustion :repeat-last` is MC-only; see
`docs/architecture/ORACLE_FIXTURE_EXHAUSTION.md`.

| Resource | Purpose |
|----------|---------|
| `data/params/PHASES.md` | Modes, `:fixed-or` shorthand, roll consumption order, `:oracle-roll-trace` |
| `data/params/control-oracle-*.edn` | Checked-in control param files (load via `io/params`) |
| `test/.../oracle_fixture_test.clj` | Validation, merge, control EDN loads, full-trial `resolve-dispute` trace |
| `test/.../reproducibility_test.clj` | Static/fixed modes, per-kind cursors, exhaustion, trace shape |

```bash
# Run only oracle fixture unit tests
clojure -M:test -e "(require '[clojure.test :as t] '[resolver-sim.stochastic.oracle-fixture-test]) (t/run-tests 'resolver-sim.stochastic.oracle-fixture-test)"

# Load a control file in the REPL
clojure -M -e "(require '[resolver-sim.io.params :as p]) (prn (:oracle-effective (p/validate-and-merge \"data/params/control-oracle-full-trial.edn\")))"
```

Trial output may include `:oracle-roll-trace` when `:oracle-roll-trace-enabled? true`.
Batch aggregates include `:oracle-effective-mode`.

---

## Scenario fixture parity (trace + public JSON)

Invariant scenarios are authored in Clojure (`protocols/sew/invariant_scenarios/`).
Checked-in `data/fixtures/traces/*.trace.json` and `scenarios/S*.json` must stay aligned
with that source or CI fails.

```bash
# After editing a scenario map, refresh on-disk fixtures (only scenarios with an existing trace file)
bb fixtures:sync
# or: clojure -M:sync-trace-fixtures

# Regenerate S01–S23 docs table from doc-summaries.clj
bb docs:scenarios
```

Unit tests in `resolver-sim.io.scenario-fixture-parity-test` (included in `./scripts/test.sh unit`) check:

- Every baseline S01–S23 id has a doc summary in `doc_summaries.clj`
- Trace contract fields (`expected-errors`, `strict-expected-errors?`, `allow-open-disputes?`) match Clojure
- Public JSON for strict-expected-errors scenarios matches the source

---

## For CI/CD Integration

```bash
#!/bin/bash
# Example CI job

set -e  # Exit on error

echo "Running canonical validation gate..."
./scripts/test.sh all || exit 1

echo "Running canonical validation gate..."
bb test

echo "Generating report..."
# Report automatically created in results/*/COMPREHENSIVE_REPORT.md

# Optional: upload results
# aws s3 cp results/ s3://bucket/protocol-robustness-framework/
```

---

See Phase J parameter files under `data/params/phase-j-*.edn` for scenario configuration details.
