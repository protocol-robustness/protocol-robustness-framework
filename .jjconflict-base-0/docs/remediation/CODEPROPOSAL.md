# Code Proposal: Remove or Upgrade

Results of a systematic scan for code that does not meet research-grade standards.
Each item is classified as **REMOVE** (dead/stale), **UPGRADE** (fixable with effort),
or **DOCUMENT** (acceptable with clear scope notes).

---

## REMOVE (dead code, no callers)

| Item | Location | Reason | Action |
|------|----------|--------|--------|
| `result_display.clj` | `src/resolver_sim/sim/result_display.clj` | Zero external references. Logging/display was replaced by `fixtures.clj` runner. | Delete file; move any referenced helpers inline if needed |
| `appeal_outcomes.clj` | `src/resolver_sim/sim/appeal_outcomes.clj` | **293 lines, zero callers.** Contains `Math/random` fallback (line 74). Return value `{:appeal-outcomes ...}` is never consumed. | Delete file. The `subjective` probability constants (15%/30%/50%) were never param-driven. |
| `delegation.clj` | `src/resolver_sim/protocols/sew/research_models/delegation.clj` | No `:passed?` output, no falsifiable claim, no test. | Delete file |
| `panel_decision.clj` | `src/resolver_sim/protocols/sew/research_models/panel_decision.clj` | No `:passed?`, no test, no callers outside directory. | Delete file |
| `resolver_ring.clj` | `src/resolver_sim/protocols/sew/research_models/resolver_ring.clj` | Same as above. | Delete file |
| `make-module-snapshot` | `src/resolver_sim/protocols/sew/types.clj:186` | Deprecated alias for `make-escrow-snapshot`. Still exported but unused externally. | Remove alias; move any internal refs to the canonical name. |
| `:yield-scenarios` suite alias | `src/resolver_sim/scenario/suites.clj:37` | Deprecated alias for `:sew-yield-scenarios`. Still in the deprecation map. | Remove mapping entry. |
| `include-legacy-derived-top-levels?` | `scenario/theory_result.clj`, `theory.clj` | Deprecated opt-in for flat diagnostic copies. Default false, but still wired. | Remove the option and the `attach-legacy-derived-top-levels` function. |

---

## UPGRADE (fixable — convert to research-grade)

| Item | Location | Issue | Upgrade path |
|------|----------|-------|-------------|
| `normalize-detection-probabilities` `:reversal` key | `detection.clj:267` | Computed but only consumed by legacy `detect-fraud-rolls` (not by `resolve-dispute`). The two paths could diverge. | Remove `:reversal` from the probs map, or add an assertion that `reversal-slashed-live?` matches it. |
| Phase C/E/F/M analytic relic | 4 files | Already labeled `:class :analytic`. The files remain as `sim/phase_*.clj` which implies they're protocol-kernel phases. | Move to `research/sew/analytic/` directory with a namespace rename to `resolver-sim.research.sew.analytic.corruption-economics`. |
| `correlated_failures.clj` | `stochastic/correlated_failures.clj` | Returns "LOW"/"MODERATE"/"HIGH"/"CRITICAL" bands instead of falsifiable pass/fail. Non-reproducible RNG. | Upgrade to seeded RNG + `:passed?` output with `:threshold` in summary, or move to `research_models/` directory. |
| `information_cascade.clj` | `research_models/information_cascade.clj` | Returns `:diffusion-status :stable/:wave/:crash` — exploration, not falsification. | Same as above: add falsifiable pass/fail or move to `research_models/` directory. |
| `bribery_markets.clj` | `research_models/bribery_markets.clj` | Has `(rand)` fallback on line 140 (already replaced with `throw` in June 2026 session — verify change persisted). No `:passed?`, no test. | Same: add falsifiable pass/fail or move to `research_models/` directory. |
| `evidence_spoofing.clj` | `research_models/evidence_spoofing.clj` | Qualitative risk bands, no test. | Same. |
| `escalation_economics.clj` | `research_models/escalation_economics.clj` | Qualitative, no test. | Same. |
| `contigent_bribery.clj` | `research_models/contingent_bribery.clj` | Has `:passed?` (good) but untested. | Add test or move to `research_models/`. |
| ~~Financial mock proofs~~ | ~~`financial/solvency.clj:17,34`~~ | ~~`"mock — real proofs not yet implemented"` — produces pass/fail structured output but the underlying proof layer is a placeholder.~~ | ~~DONE: `:class :analytic` added. Live SHA-256 commitment chain since June 2026. No mock code remains.~~ |
| Trial router incomplete | `sim/trial_router.clj` | Only `:uniform-random` mode implemented; capacity/reputation weighting is interface-only. | Document the limitation in the namespace docstring. No action required — interface completeness is not a research-grade issue. |
| XTDB integration tests | `test/resolver_sim/db/telemetry_integration_test.clj` | Requires live XTDB on `localhost:5432`; not in default unit path. | Add `:integration` tag and exclude from `test.sh`; document that a local XTDB instance is required. |
| `result_display.clj` unused functions | `sim/result_display.clj` | `scenario-entry-ok?`, `yield-expectation-failed?`, `scenario-references-yield?`, `suite-report-lines` — zero references. | Remove unreachable code paths. |

---

## DOCUMENT (acceptable with clear scope notes)

| Item | Location | Status | Documentation needed |
|------|----------|--------|---------------------|
| DummyProtocol in registry | `protocols/registry.clj` | Valid test double. | Document that it is for test harness only, not evidence packs. |
| Archive docs | `docs/archive/WEAKNESS_ANALYSIS.md`, `ASSESSMENT_RESOLUTION.md`, `PHASE_J_SPECIFICATION.md`, `phase-i-automatic-detection.md` | Contain "production-ready / 92% confidence" language — citation hazard. | Add a disclaimer to each file: "This document is archived. Claims herein predate the June 2026 research-grade audit and should not be cited without qualification." |
| `:single-simulation-evidence` labels | `sim/stochastic_equilibrium.clj` | Already documented as exploratory. The namespace docstring explicitly marks which evidence levels are single-simulation. | No action needed — already best practice. |
| S43–S47+ fixture backlog | `docs/archive/testing/FIXTURE_BACKLOG_DIFF_SEMANTICS.md` | Proposed scenarios not implemented. | No action needed — tracked as backlog. |
| Cancellation game theory gap | `docs/testing/CANCELLATION_GAME_THEORY_GAP_CHECKLIST.md` | Open checklist. | No action needed — tracked as open checklist. |

---

## COST-BENEFIT

### Highest impact per effort

| Action | Effort | Impact | Why |
|--------|--------|--------|-----|
| **Delete** `appeal_outcomes.clj` (293 lines, 0 callers, Math/random) | 5 min | Removes unreproducible code with misleading appearance of use | Dead code + non-reproducible RNG |
| **Delete** `result_display.clj` (200+ lines, 0 callers) | 5 min | Removes dead code from module count | Dead code |
| **Delete** 4 unused research models (delegation, panel_decision, resolver_ring, contingent_bribery) | 10 min | Removes untested, unmaintained modules | Dead code |
| **Remove** 3 deprecated aliases (make-module-snapshot, yield-scenarios, legacy-derived-top-levels) | 15 min | Cleans up deprecated surface area | Dead option surface |
| **Move** Phase C/E/F/M → `research/sew/analytic/` | 1 hr | Clarifies that these are not protocol-kernel evidence | Taxonomy cleanup |
| **Move** `correlated_failures.clj` → `research_models/` | 30 min | Clarifies that this is not stochastic evidence | Taxonomy cleanup |
| **~~Add~~ Added** `:class :analytic` to `financial/solvency.clj` | ~~5 min~~ DONE | ~~Evidence packs can filter mock proofs~~ `:class :analytic` added to `classify-solvency` return map | ~~Low effort, high signal~~ |
| **Add** archive doc disclaimers | 30 min | Eliminates citation hazard | Prevents misuse |
| **Upgrade** `normalize-detection-probabilities` :reversal dead code path | 15 min | Removes stale code path divergence risk | Minor but real bug class |
