# SPEDS Production-Readiness Plan

## Current State

**10 source namespaces** (1,778 lines), **2 test files** (7 tests), **17 notebooks**, **3 scripts**.

| Surface | Lines | Tests | Coverage |
|---------|-------|-------|----------|
| `speds.core` (visual primitives) | 165 | 0 | — |
| `speds.tokens` (design tokens) | 55 | 0 | — |
| `speds.config` (paths, profiles) | 110 | 0 | — |
| `speds.data` (artifact ingestion) | 241 | 0 | — |
| `speds.semantics` (purpose→kind) | 18 | 0 | — |
| `speds.findings` (envelope gen) | 397 | 5 | partial |
| `speds.issues` (issue bundles) | 201 | 2 | partial |
| `speds.validation` (consistency) | 161 | 0 | — |
| `speds.story` (narrative engines) | 418 | 0 | — |
| `speds.story-data` (thin wrapper) | 12 | 0 | — |

## Known Bugs (pre-existing)

1. **`story_data.clj:9-11`** — fully-qualified calls use `resolver_sim.notebooks.speds.data/...` (doesn't exist; should be `resolver-sim.notebook-support.speds.data/...`). Will throw `ClassNotFoundException` when `build-story-data` is called.
2. **Makefile `speds-check`, `speds-issues`, `speds-findings`** — script paths reference `scripts/` but files are at `scripts/scenarios/`. `speds-findings` runs the issues script (wrong target).
3. **Consistency check script** — hardcoded path `src/resolver_sim/notebooks/speds/story.clj` (doesn't exist; should be `notebook_support/speds/story.clj`).
4. **No SPEDS tests in any `bb test:*` target** — tests exist but can't be run through the standard pipeline.

---

## Tier Structure

### P0 — Ship-blocking (fix immediately)
### P1 — Core integrity (ship without if time-critical, but risky)
### P2 — Quality of service (should have before next milestone)
### P3 — Polish (ongoing, non-blocking)

---

## P0 — Ship-Blocking (2–3 hours)

| # | Item | Effort | Depends On |
|---|------|--------|------------|
| 1 | **Fix story_data.clj wrong namespace paths** — change 3 fully-qualified calls from `resolver_sim.notebooks.speds.data/...` to `resolver-sim.notebook-support.speds.data/...` | 5 min | — |
| 2 | **Fix Makefile script paths** — `scripts/` → `scripts/scenarios/` for `speds-check`, `speds-issues`, `speds-findings`. Fix `speds-findings` to call findings generator (create one or rename). | 15 min | — |
| 3 | **Fix consistency check hardcoded path** — change `notebooks` to `notebook_support` in claim source scanning | 5 min | — |
| 4 | **Add `bb test:speds` task** — wire the 2 existing SPEDS test files into `bb.edn` so they run in CI | 10 min | — |

**Total P0**: ~35 min. Zero risk. Fixes known broken paths that make parts of SPEDS non-functional.

---

## P1 — Core Integrity (6–10 hours)

| # | Item | Effort | Depends On |
|---|------|--------|------------|
| 5 | **Silent-swallow audit** — `data.clj` loaders catch *all* exceptions and return nil/[] with no logging. Replace with structured error reporting: at minimum `log/warn` on failures, optionally return `{:error msg :data {}}` or use `ex-info` for recoverable failures. | 1–2 h | — |
| 6 | **Schema validation for output artifacts** — define Malli schemas for findings bundle (`speds.findings.v1`) and issues bundle (`speds-issues-v1`). Validate on write. Add schema files under `schemas/speds/`. | 2–3 h | — |
| 7 | **Nil/edge-case hardening** — audit all public functions in `findings.clj`, `issues.clj`, `story.clj` for nil input handling. Add `when-let`, `some->`, or early returns. | 1 h | — |
| 8 | **Script robustness** — add exit codes, basic arg validation, and `try/catch` with `(System/exit 1)` to the 3 scripts. | 1 h | — |
| 9 | **`config.clj` profile loading** — replace load-time side-effect `(def profile (active-profile))` with a zero-arg function `(profile)` so environment changes are reflected without restart. | 15 min | — |
| 10 | **Add `adversarial-robustness` to `data/speds/definitions.edn`** — currently falls through to `:default` producing `"inconclusive_result"` kind, which is semantically wrong for a defined purpose. | 5 min | — |

**Total P1**: ~6–8 h.

---

## P2 — Quality of Service (12–18 hours)

| # | Item | Effort | Depends On |
|---|------|--------|------------|
| 11 | **Test the remaining 8 namespaces** — add test coverage for `speds.core` (renderer smoke tests with valid/nil/missing args), `speds.data` (loaders with missing files, corrupt JSON, empty results), `speds.semantics` (purpose mappings), `speds.config` (profile resolution). Target: basic smoke tests, not exhaustive. | 4–6 h | P1#5 |
| 12 | **Add error-path tests** — corrupt files, missing directories, nil inputs, edge-case scenarios (zero scenarios, no traces, no golden reports). | 2–3 h | P1#5, #7 |
| 13 | **Path consolidation** — move hardcoded paths in `findings.clj:14`, `issues.clj:16` to use `config/artifact-paths`. Remove duplication with `evcfg/artifact-path`. | 1 h | — |
| 14 | **Deprecation cleanup** — remove `:definitions_hash` (underscore form) from findings bundle if migration period is over, or document the sunset date. | 30 min | — |
| 15 | **Add SPEDS tests to CI** — include `bb test:speds` in `bb validate` or the CI pipeline config. | 15 min | P0#4 |
| 16 | **Report writer tests** — add round-trip tests for `save-findings!`/`load-findings` and `save-issues!`/`load-issues` using temp dirs. | 1 h | — |
| 17 | **Documentation namespace-path fixes** — update `docs/visual/PROTOCOL_EVIDENCE_DESIGN_SYSTEM.md`, `docs/overview/OUTCOME_MODEL.md`, and `docs/notebooks/NOTEBOOK_STYLE.md` to use correct namespace paths (`resolver-sim.notebook-support.speds.*` not `resolver-sim.notebooks.speds.*`). | 1 h | — |
| 18 | **Story engine edge-case hardening** — test `generate-story-by-family` with nil artifacts, missing scenarios, unknown family keys. | 2 h | P1#7 |

**Total P2**: ~12–16 h.

---

## P3 — Polish (ongoing, 8–12 hours)

| # | Item | Effort | Rationale |
|---|------|--------|-----------|
| 19 | **Implement missing primitives** — V-TIM (escalation timeline), V-ATK (attack overlay), V-CON (confidence indicator) as defined in the design doc, or explicitly document them as deferred. | 4–6 h | Completes the design spec, but existing notebooks work without them. |
| 20 | **CSS/Motion system** — implement animation primitives from PEMS design doc. | 4–8 h | Enhances visual quality; no functional impact. |
| 21 | **Formal schema version registry** — register SPEDS artifact versions (`speds.findings.v1`, `speds-issues-v1`) in `config/evidence.json` with proper versioning and migration policy. | 2 h | Good practice but no immediate risk — only SPEDS code produces/consumes these. |
| 22 | **Adversarial-robustness classification** — add a dedicated `:adversarial-robustness` entry to `data/speds/definitions.edn` classification. | 10 min | Small gap — currently falls to default. |
| 23 | **Default fallback in `generate-story`** — review whether the default case (calls `generate-issue-gallery`) is intentional or should throw with a helpful message for unknown modes. | 30 min | Minor design clarity. |
| 24 | **VENS/ESC/RCS alignment** — update aspirational design docs to clarify which primitives are implemented vs deferred. | 2 h | Reduces confusion for new contributors. |

**Total P3**: ~12–16 h.

---

## Recommended Execution Order

```
Week 1 (P0 + P1):
  Day 1: P0 items 1–4  (35 min — urgent bug fixes)
  Day 2: P1 items 5, 7  (error handling audit + nil hardening)
  Day 3: P1 items 6, 10 (schema + missing purpose entry)
  Day 4: P1 items 8, 9  (script robustness + config fix)

Week 2 (P2):
  Day 1: P2 items 11    (test coverage for 8 namespaces)
  Day 2: P2 items 12, 16 (error-path tests + round-trip tests)
  Day 3: P2 items 13, 14 (path consolidation + deprecation cleanup)
  Day 4: P2 items 15, 17 (CI wiring + doc namespace fixes)

Week 3 (P2 + P3):
  Day 1: P2 item 18     (story engine edge-case tests)
  Day 2-4: P3 items 19-24 (primitives, motion, registry, alignment)
```

## Summary

| Tier | Items | Effort | Risk if skipped |
|------|-------|--------|-----------------|
| P0 | 4 | ~35 min | **Parts of SPEDS are broken** (story generation crashes, Makefile targets fail) |
| P1 | 6 | ~6–8 h | Silent data corruption, ungraceful failure on bad inputs, wrong purpose→kind mapping |
| P2 | 8 | ~12–16 h | No regression safety net, untested edge cases, outdated docs |
| P3 | 6 | ~12–16 h | Missing primitives, no animation, aspirational docs misaligned |

**Minimum viable production cut**: P0 + P1 (~7 h) — SPEDS works reliably, handles errors gracefully, validates outputs.
