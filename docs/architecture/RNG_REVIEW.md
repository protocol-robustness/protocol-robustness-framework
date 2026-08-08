# RNG Architecture Review & Reproducibility Contract

**Date**: 2026-06-06  
**Scope**: All `next-double`, `roll-double`, `(rand)`, and `Math/random` call sites in `src/resolver_sim/`  
**Status**: Applied (4 fixes), 3 deferred

---

## 1. RNG Architecture

### Two Entry Points

| Function | Requires RNG? | On nil | Typical Use |
|----------|-------------|--------|-------------|
| `rng/next-double` | Yes (`SplittableRandom`) | NPE | Trial loops, `resolve-dispute`, waterfall draws |
| `rng/roll-double` | Optional | Falls back to `(rand)` | Oracle `:stochastic` mode, adversary helpers |

The split is intentional: `next-double` is strict for core MC paths (fails fast on missing RNG), while `roll-double` is lenient for oracle/adversary code paths that legitimately omit `:rng` in non-MC contexts.

### RNG Source

- **Single source**: `java.util.SplittableRandom` — all MC paths use this.
- **Seeding**: `rng/make-rng` + `rng/seed-from-index` for parallel trial isolation.
- **Splitting**: `rng/split-rng` for stream bifurcation (supports parallel sweeps).
- **Exception**: `sim/appeal_outcomes.clj` uses `java.util.Random` (legacy, not unified).

---

## 2. Call Site Inventory

### 2.1 `resolve-dispute` (stochastic/dispute.clj)

**Purpose**: Trial-level dispute resolution with probabilistic outcomes.

**RNG consumption per call, in order**:

```
1. Verdict correctness  — 1× next-double  (honest=0 draws, lazy=1, mal=1, coll=1)
2. Appeal roll          — 1× next-double  (all strategies)
3. Oracle rolls         — N× roll-double  (detection, reversal, l1/l2 phases)
4. Escrow size          — (caller-side, not in this function)
```

**Assertions**:

- **A1**: Verdict and appeal draws are always from the trial `:rng` (not oracle-controlled). You cannot fully script a trial with `:fixed-or` alone. Strategy and appeal probability must be set separately.
- **A2**: Oracle fixture `:scope` controls only detection and appeal-reversal rolls. Verdict correctness and the decision to appeal are outside the fixture scope by design.
- **A3**: Because verdict/appeal come first, any change to upstream draws (escrow size, strategy selection) shifts all downstream oracle rolls deterministically. This is the expected behavior of a single advancing RNG stream.

### 2.2 Oracle / Detection (stochastic/detection.clj)

**Purpose**: Probabilistic slashing detection with optional fixture scripting.

**Key function**: `prepare-oracle-params` — called once per trial (inside `resolve-dispute`).

**Assertions**:

- **A4**: `prepare-oracle-params` always creates fresh atoms for `:oracle-roll-cursor`, `:oracle-roll-cursors`, and `:oracle-fixture/exhausted?`. Cursors reset to 0 on every call.
- **A5**: For `:fixed-roll-sequence` mode, this means the fixture pattern repeats trial after trial: roll 1 always applies to the first detection check of every trial, roll 2 to the second, etc. This is **per-trial sequencing**, not per-batch.
- **A6**: To persist cursors across a batch of trials, callers must invoke `prepare-oracle-params` once and reuse the returned params map — or manage cursor atoms externally. The built-in `resolve-dispute` path does not do this.
- **A7**: `roll-double` is used inside `oracle-roll-event` because oracle code paths share the `:rng` in the params map (which may be nil in non-MC contexts). When nil, `roll-double` falls back to `(rand)`.

### 2.3 Probabilistic Waterfall (sim/waterfall.clj)

**Purpose**: Monte Carlo-powered slash stress testing with per-epoch caps.

**RNG consumption per trial**:

```
1. Escrow size        — 2× next-double  (lognormal draw via Box-Muller)
2. Strategy           — 1× next-double  (weighted mix selection)
3. Dispute resolution — N× next-double  (full resolve-dispute chain)
```

**Assertions**:

- **A8**: Each trial receives its own RNG forked from `seed-from-index(base-seed, trial-idx)`. Reproducible per-trial semantics: trial `i` is deterministic independent of trials `j ≠ i`.
- **A9**: The per-trial RNG fork decouples escrow/strategy draws from dispute resolution rolls. Adding trial 51 does not shift the results of trials 0–50. This is the key difference from a single shared advancing RNG stream.
- **A10**: `draw-strategy` validates that `(sum weights) ≈ 1.0` with ±0.001 tolerance. Mismatched weights throw `ex-info` at the call site, catching stale param files early.
- **A11**: `draw-lognormal` guards against `log(0)` by clamping `u1 ≥ 1e-10` before the Box-Muller transform.
- **A12**: Per-epoch caps (20% junior / 10% senior) are enforced in the probabilistic path via `cap-junior-slash`, which composes the two DISTINCT junior constraints — the per-epoch budget (20% of initial bond) and the per-slash max (50% of current bond). The deterministic path (`process-slash-event`) does not enforce per-epoch caps (it tests worst-case capacity, not protocol-compliant robustness). Cap constants are single-source (`junior-per-epoch-cap-ratio` / `senior-per-epoch-cap-ratio`); no public/private duplicates.
- **A18**: `probabilistic-process-slash-pool` validates pool topology before processing (`validate-pool-params`: `:n-seniors`, juniors-per-senior, even division) and derives junior→senior assignment from the pool's actual topology, so a slash can never target a non-existent resolver. `process-slash-event` additionally throws on an unknown resolver — fail-closed, no phantom slash event.

### 2.4 Adversaries (adversaries/strategy.clj)

**Purpose**: Adversarial strategy models for multi-epoch trajectory analysis.

**Assertions**:

- **A13**: Uses `roll-double` via a local `next-double` wrapper that reads `(:rng params)`. When `:rng` is nil, silently falls back to `(rand)`.
- **A14**: This is safe for adversaries because the adversary framework is designed for qualitative exploration (trajectory shape), not exact numerical reproducibility. Callers requiring determinism should pass `:rng` explicitly.

### 2.5 Batch Runners (sim/batch.clj, run-simulation-loop)

**Assertions**:

- **A15**: The batch runner passes a single advancing `SplittableRandom` instance through `repeatedly`, calling `resolve-dispute` once per trial. Each trial consumes N draws from the shared stream.
- **A16**: `run-simulation-loop` seeds the PRNG once per run via `rng/make-rng`. Same seed → same full batch result (prior to per-trial RNG forking in the waterfall path).
- **A17**: Determinism tests in `core_tests.clj` and `reproducibility_test.clj` verify seed equality for the standard MC pipeline.

### 2.6 Research & Sim Modules with `(rand)` or `Math/random`

| File | Pattern | Status |
|------|---------|--------|
| `protocols/sew/research_models/bribery_markets.clj` | `(rand)` | **Fixed**: optional `:rng` kwarg added |
| `research/sew/economic/market_exit.clj` | `(rand)` | Deferred: research-only, exit-prob computation |
| `sim/appeal_outcomes.clj` | `Math/random` | Deferred: uses `java.util.Random`, needs wider unification |

---

## 3. Assumptions

### Design Assumptions

**AS1 — Single-stream determinism**: The standard MC pipeline assumes a single advancing `SplittableRandom` across all trials in a batch. Trial `i+1` depends on the RNG state after trial `i`. This is the expected behavior — not a bug — for sequential replay and parameter sweeps.

**AS2 — Oracle per-trial scope**: Oracle fixture scripting assumes per-trial cursor reset. Each trial sees the same sequence of oracle rolls. This is by design: oracle fixtures are authored to describe the detection sequence *within* a single trial, not across an entire batch.

**AS3 — Verdict/appeal independence**: The strategic decision to judge correctly (`:honest`/`:malicious`/`:lazy`/`:collusive`) and the decision to appeal are modeled as independent draws from the trial RNG, not as oracle-scripted events. This is intentional: the oracle controls detection (what the protocol can observe), not resolver behavior (what the resolver decides).

**AS4 — Strategy weights sum to 1.0**: All callers of `draw-strategy` must supply weights that sum to approximately 1.0. The validation will catch param drift. Excess roll mass (beyond the sum) falls through to `:honest` as the default.

**AS5 — Lognormal escrow distribution**: Escrow sizes are drawn from a lognormal distribution using Box-Muller. The distribution assumes `:mean > 0` and `:std > 0`. Mean values below 10 wei are clamped to 1.

**AS6 — Per-epoch caps apply to probabilistic only**: The deterministic waterfall path intentionally omits per-epoch caps to test worst-case pool capacity. The probabilistic path enforces per-epoch caps (and the per-slash cap) via `cap-junior-slash` to model protocol-compliant behavior.

### Numerical Assumptions

**AS7 — u1 > 0 in Box-Muller**: `draw-lognormal` clamps `u1` to `[1e-10, 1)` to prevent `Math/log(0)`. This introduces a negligible bias but guarantees no runtime errors.

**AS8 — Double precision**: All slash amounts, bond values, and yield computations use `double`. For values below `2^53` (≈ 9 quadrillion), double provides exact integer representation. Protocol parameters (bond amounts, escrow values) are well within this range.

**AS9 — SplittableRandom determinism**: `SplittableRandom` is assumed to be fully deterministic given a seed, across all JVM versions and platforms. This is a documented property of the Java standard library.

---

## 4. Fixes Applied (2026-06-06)

### Fix 1: Document Oracle Cursor Reset

**File**: `stochastic/detection.clj:193-207`

Added explicit documentation to `prepare-oracle-params` clarifying that cursors reset to 0 on every call. Documents the per-trial, not per-batch, semantics and suggests how to persist cursors across trials.

### Fix 2: Document RNG Consumption Order

**File**: `stochastic/dispute.clj:11-19`

Added RNG draw order to `resolve-dispute` docstring. Lists exact number and sequence of `next-double` / `roll-double` calls per invocation. Documents the verdict-first, appeal-second, oracle-last ordering.

### Fix 3: Per-Trial RNG Fork in Probabilistic Waterfall

**File**: `sim/waterfall.clj:290-295`

Changed `probabilistic-process-slash-pool` to fork a per-trial RNG via `seed-from-index(base-seed, trial-idx)`. Each trial now has an isolated RNG stream, making escrow/strategy draws independent of dispute resolution across trials. The full run is reproducible given the same `:base-seed`.

### Fix 4: Strategy Weight Validation

**File**: `sim/waterfall.clj:250-254`

Added validation to `draw-strategy` that `(sum weights)` is within ±0.001 of 1.0. Throws `ex-info` with the actual total and the provided mix for debugging.

### Fix 5: Optional RNG in Bribery Markets

**File**: `protocols/sew/research_models/bribery_markets.clj`

Added `& {:keys [rng]}` to `budget-with-recycling`. When provided, uses `(.nextDouble rng)` for detection rolls. Falls back to `(rand)` when omitted for backward compatibility.

---

## 5. Deferred (Architectural, Not Bug-Fixes)

### D1: `(rand)` in `market_exit.clj`

**Location**: `research/sew/economic/market_exit.clj:35`  
**Rationale**: Research-only scenario file. `(rand)` computes exit-probability thresholds, not dispute outcomes. Adding an `rng` parameter would change a function API (`apply-resolver-exits`) used only by research probes. Low impact on the main MC pipeline.

### D2: `Math/random` in `appeal_outcomes.clj`

**Location**: `sim/appeal_outcomes.clj:74`  
**Rationale**: Uses `java.util.Random`, not `SplittableRandom`. The 2-arg caller (`resolve-appeal-with-outcome` without params, line 116) hits the fallback path. Fixing requires either replacing `java.util.Random` with `SplittableRandom` throughout appeal_outcomes (wider unification) or threading `:rng` through all appeal call sites. Scope exceeds what can be done without behavioral risk.

### D3: `roll-double` nil fallback in oracle/adversary

**Location**: `stochastic/rng.clj:38-40`, `adversaries/strategy.clj:7-11`  
**Rationale**: The nil fallback exists for REPL and quick-script contexts where `:rng` isn't available. Throwing on nil in MC contexts would break legitimate non-MC usage of these functions. The safer approach is documentation and incremental fixing at call sites that pass through MC (as done in the waterfall).

---

## 6. Reproducibility Contract

### To guarantee deterministic results:

1. **Use `rng/make-rng` for the top-level seed**. Do not use `(rand)` or `Math/random` in MC paths.

2. **For batch trials**: Pass the same `SplittableRandom` instance through sequential trials. Each `next-double` advances the stream. Same seed → same sequence. Verified by `core_tests.clj`.

3. **For waterfall trials**: Use per-trial `seed-from-index`. Each trial is deterministic given `base-seed` and `trial-idx`. Total run is deterministic given `base-seed` and `n-trials`. Verified by `waterfall_test.clj`.

4. **For oracle fixtures**: Cursors reset per trial. The fixture describes intra-trial detection sequences. For per-batch scripting, invoke `prepare-oracle-params` once outside the trial loop and reuse the params map.

5. **For parallel sweeps**: Use `rng/split-rng` or per-core `seed-from-index` to give each thread an independent stream.

### To verify reproducibility:

- Run the same seed twice → identical event outcomes, slash counts, and metrics.
- Run with `n-trials` and `n-trials + 50` → first `n` trials should produce identical results (only true for forked-RNG paths like the probabilistic waterfall; not true for shared-RNG paths like `run-simulation-loop`).

---

## 7. Test Coverage

| Test | What It Verifies |
|------|-----------------|
| `waterfall_test.clj:test-probabilistic-vs-deterministic-semantics` | Probabilistic slashes fewer than deterministic worst-case |
| `waterfall_test.clj:test-probabilistic-waterfall-per-epoch-cap` | Per-epoch cap limits total slash in single epoch |
| `waterfall_test.clj:test-probabilistic-process-slash-pool-returns-metrics` | Result structure is correct (contains resolvers, seniors, events, metrics) |
| `waterfall_test.clj:validate-pool-params-*` / `process-slash-event-unknown-resolver-throws` | Fail-closed on topology mismatch and unknown resolvers |
| `waterfall_test.clj:cap-junior-slash-*` | Both distinct junior caps (per-epoch + per-slash) and epoch-remaining shrink |
| `waterfall_test.clj:coverage-exhaustion-monotonicity` / `adequacy-pct-and-exhaustion-pct-complement` | Overflow-pressure metric rises past junior→senior→unmet; bounded adequacy complements exhaustion (=100) |
| `waterfall_test.clj:test-draw-escrow-size-positive` | Lognormal draws are positive and vary |
| `waterfall_test.clj:test-draw-strategy-in-range` | Strategy distribution respects weights, honest is majority |
| `core_tests.clj:reproducibility suite` | Same seed → same result across runs |
| `properties/invariants_test.clj` | Oracle fixture determinism with pinned seeds |
