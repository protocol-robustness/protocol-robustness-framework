# Research Quality Remediation Plan

Tracked issues from the June 2026 research-grade audit. Each item has a priority,
estimated effort, and a clear success criterion. Items marked with a check are
completed.

---

## P0 — Reproducibility (blocking peer review)

### P0.1 Seed all MC draws
- [x] **Phase O market_exit.clj**: Replaced bare `(rand)` with `rng/next-double`; splits RNG per epoch via `rng/split-rng`; reads `:rng-seed` from params.
- [ ] **appeal_outcomes.clj**: Replace `Math/random` fallback with a hard error when no `rng` is provided, or thread seeded RNG through every call site.
- [ ] **bribery_markets.clj**: Same as appeal_outcomes — remove `(rand)` fallback, require seeded RNG.
- [ ] **rng.clj fallback**: Consider removing the `(rand)` fallback in `next-double` so missing RNG is a compile-time/load-time error, not a silent reproducibility bug.

**Success criteria**: Zero uses of `(rand)` or `Math/random` in `src/`. All MC paths deterministically reproducible via `:rng-seed`.

**Effort**: ~2-3 days (most call sites are straightforward plumbing).

---

## P1 — Falsification integrity

### P1.1 Rewire or relabel Phases C/E/F/M
These phases produce pass/fail banners but do not exercise `resolve-dispute` or `replay-with-protocol`. They are analytic sanity checks, not protocol hypothesis tests.

- [ ] Audit each sub-phase for kernel exercise:
  - **Phase C**: Deterministic algebra with fixed constants (escrow 10000, fee-bps 150). Sweeps 0.5×log(N) coordination — no `resolve-dispute` call.
  - **Phase E**: E1 is a date comparison; E2 tests `collision-occurs?` then immediately sets `collision-detected?` to the same value (tautology); E3 docstring says >=80% but implements 67% (2/3).
  - **Phase F**: Some sub-phases route through `run-batch`; others are analytic.
  - **Phase M**: Mixed thresholds (95%, 90%, 70%, 80%) — check each sub-phase.
- [ ] Either:
  a. Rewire to call the replay kernel, OR
  b. Relabel to "analytic sanity check" and exclude from hypothesis evidence packs.

**Success criteria**: Every phase with a `:passed?` banner either calls `resolve-dispute`/`replay-with-protocol` or is explicitly documented as "analytic — not protocol kernel evidence."

**Effort**: ~1 week (requires per-sub-phase analysis).

### P1.2 Fix Phases E2 tautology
- [ ] `phase_e_evidence_integrity.clj:84-88`: `collision-detected?` is set to the same value as `collision-occurs?` — the test passes trivially. Either test actual hash/evidence validation code or remove the sub-phase.

**Effort**: ~1 day.

### P1.3 Unify pass thresholds
- [ ] Create a single policy document: `docs/testing/THRESHOLD_POLICY.md`.
- [ ] Move all hardcoded gates to `data/params/*.edn`:
  - Phase C: 6 sub-phases with 0.70-0.80 gates.
  - Phase E: 6 sub-phases with mixed gates (67%-100%).
  - Phase M: 4 sub-phases with mixed gates (70%-95%).
  - Phase F: 5 sub-phases with 0.75-0.80 gates.
  - Waterfall: coverage-adequacy >= 80.0.
  - Phase O: spike-ratio >= 0.70.
  - Phase Y: min-accuracy >= 0.75.
  - Phase AA: win-rate < 0.20.
  - Phase T: survival >= 0.80.
  - Phase AF: MC >= 0.95 / class >= 0.99.
  - Phase AC: >= 2/3 pass rate.
  - P-X falsification sweeps: vuln counts < 5, <= 1, <= 8, etc.
- [x] Phase AE: Wired to `:pass-threshold` / `:expected-preservation-floor` (params already existed).
- [ ] Add `:pass-threshold` to phases with existing EDN files but no threshold key.
- [ ] Create EDN param files for phases that lack them entirely (C, E, M — currently no params files).

**Success criteria**: Zero hardcoded threshold literals in phase source files. Every gate reads from an EDN param.

**Effort**: ~1 week.

---

## P2 — Layering and structure

### P2.1 Fix layering violations
- [ ] `sim/reference-validation → io/scenarios`: Move the reference-validation dependency out of `io/` into a pure namespace.
- [ ] `contract-model/replay/io → io/serialization`: Make `replay/io` depend on a protocol, not on a concrete I/O namespace (or inline the tiny helper).
- [ ] `protocols/sew/invariant-runner → io/scenario-runner`: Extract scenario-runner's pure helpers into a new `scenario/` namespace; have invariant-runner depend on that.
- [ ] `protocols/sew → db/sew, db/temporal`: Extend layering lint to catch these violations (currently allowlisted). Move DB calls to the `db/` adapter layer and have `core.clj` wire them.

**Success criteria**: Layering lint passes with zero violations. Every namespace's imports are within the allowed dependency direction.

**Effort**: ~2-3 days.

### P2.2 Expand CI gate
- [ ] Audit which phases run in `scripts/test.sh` monte-carlo vs. which are excluded.
- [ ] Add tier labels (`:protocol-kernel-evidence`, `:analytic`, `:exploratory`) to each phase's benchmark-id.
- [ ] Add non-CI phases to a separate "nightly" or "extended" script with clear documentation that results are not publication-ready.
- [ ] Add dedicated unit tests for untested sim modules: `phase_c`, `phase_f`, `phase_m`, `governance_impact`, `stochastic_equilibrium`, `kernel_bridge`, `appeal_outcomes`, `reputation`.

**Effort**: ~1 week.

### P2.3 Deprecate archive docs
- [ ] Audit `WEAKNESS_ANALYSIS.md`, `docs/archive/`, and any documents containing "production-ready / 92% confidence" language.
- [ ] Either update to current assessment or archive with a disclaimer.

**Effort**: ~1 day.

---

## P3 — Hardening and documentation

### P3.1 Move priors to EDN
- [ ] `stochastic/dispute.clj:112-116`: Strategy correctness probabilities (`:lazy 0.5`, `:malicious 0.3`, `:collusive 0.8`) — move to param files.
- [ ] `appeal_outcomes.clj`: Appeal success rates (15% / 30% / 50%) — move to param files.
- [ ] `stochastic/types.clj` vs `economics/baseline.edn`: L2 escalation default discrepancies — document and reconcile.
- [ ] `market_exit.clj`: `baseline-profit 150.0` and `/1000` denominator — move to param file.

**Effort**: ~2 days.

### P3.2 SPE conclusive-evaluation mode
- [ ] `subgame_counterfactual.clj`: Implement `:require-conclusive?` (currently marked "future").
- [ ] Add `:spe-config` with `:require-conclusive? true` to spe-v{1-5} traces (currently use default `:inconclusive` early-exit).
- [x] Add `:spe-config {:regret-threshold 0 :epsilon-abs 0.0 :epsilon-rel 0.0}` to spe-v1…spe-v5 traces.

**Effort**: ~2-3 days.

### P3.3 Kleros stub documentation
- [ ] `protocols/sew/authority.clj`: Add docstring notes that court economics, PNK, and juror coherence are absent from the stub.
- [ ] Reference the stub's scope limits in any evidence packs that use escalation-resolution replay.

**Effort**: ~1 day.

### P3.4 Fixture backlog
- [ ] Implement S43–S47+ from `docs/archive/testing/FIXTURE_BACKLOG_DIFF_SEMANTICS.md`.
- [ ] Address open cancellation game theory checklist items from `CANCELLATION_GAME_THEORY_GAP_CHECKLIST.md`.

**Effort**: ~1 week.

---

## Completed in June 2026 session

| Item | Description |
|------|-------------|
| Phase O RNG | `(rand)` → `rng/next-double`; RNG split per epoch; `:rng-seed` in params |
| Phase AE thresholds | Wired `:pass-threshold` / `:expected-preservation-floor` from params |
| Fixture suite thresholds | Added `:thresholds/strict-baseline` to 4 suites; `:max-held-delta` validator |
| SPE config | Added explicit `:spe-config` to spe-v1…spe-v5 traces |
| Kernel validation gate | Added `:kernel-validation-min-pass-rate` gate |
| Phase J audit thresholds | Threaded `:audit-thresholds` from params into `analyze-multi-epoch` |
| Event-id normalization | `compat/event-id`, `compat/hop-id` normalize to string for stable dedupe keys |
| Seq normalization | `normalize-seq` in event pipeline; `get-relaxed` in expectations matching |
