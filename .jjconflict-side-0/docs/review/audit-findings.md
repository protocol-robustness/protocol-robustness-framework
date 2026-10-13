# Idempotency Audit Findings

**Date:** 2026-07-24
**Scope:** Idempotency implementation across Sew protocol model, evidence chain, attestation registry, and replay engine.

---

## Summary

Idempotency is implemented via **two cleanly separated models**:
1. **Business-logic** — state guards in lifecycle/resolution (always active)
2. **Replay-boundary** — `apply-once` in `contract-model.idempotency` (activated by `event-id`)

The archived F-004 bug (`force-reversal-slash` not idempotent) has been **fixed**. Most concerns from `docs/archive/idepotence_analysis.md` have been addressed.

**Overall assessment:** Sound architecture with well-documented design. Minor gaps in checklist completeness and cross-layer verification.

---

## Findings

### Finding 1: `force-reversal-slash` fixed but absent from checklist

| Severity | Low |
|----------|-----|
| Files | `resolution.clj:323`, `IDEMPOTENCE_CHECKLIST.md`, `slashing_test.clj:873` |
| Status | Code and test pass, but checklist missing |

`force-reversal-slash` now has a guard at `resolution.clj:323` using `:slash-by-context`, and a dedicated test at `slashing_test.clj:873`. However, `IDEMPOTENCE_CHECKLIST.md` does not list this surface. The archived F-004 (INTERACTIVE_FINALITY_SESSION_LOG.md:518) still reads as unresolved.

**Recommendation:** Add `force-reversal-slash` row to the checklist with `PASS` status.

---

### Finding 2: Guard mechanism differs from original F-004 suggestion

| Severity | Informational |
|----------|---------------|
| Files | `resolution.clj:323`, INTERACTIVE_FINALITY_SESSION_LOG.md:1175 |
| Status | Functionally correct, undocumented rationale |

The original bug report suggested checking `(str wf-id "-force-reversal-0")` in `:pending-fraud-slashes`. The actual fix checks `[:slash-by-context [workflow-id :force-reversal 0]]` — a different path. This is likely more correct (bypasses pending-slashes for immediate-track) but the divergence from the suggested fix is not documented.

**Recommendation:** Add a code comment noting why `:slash-by-context` was chosen over `:pending-fraud-slashes`.

---

### Finding 3: Evidence-chain idempotency not tracked in checklist

| Severity | Low |
|----------|-----|
| Files | `chain.clj:513`, `attestation_registry.clj:68`, `IDEMPOTENCE_CHECKLIST.md` |
| Status | Implemented and tested, but outside Sew checklist scope |

`register-evidence!` uses `locking` + content-addressed hash dedup (with `log/debug!` on duplicate). A concurrent test (`register-evidence-concurrent-idempotent`) verifies 10 parallel futures produce exactly 1 registration. `register-attestation!` is idempotent via `swap! assoc` overwrite. Neither is tracked in IDEMPOTENCE_CHECKLIST.md (which is Sew-protocol-scoped).

**Recommendation:** Either expand checklist scope or create a separate infrastructure idempotency tracker.

---

### Finding 4: Duplicate logging at debug, not warn

| Severity | Informational |
|----------|---------------|
| Files | `chain.clj:530`, `idepotence_analysis.md:68` |
| Status | Deliberate design choice |

The archived analysis suggested `log/warn` for duplicate evidence hashes. The implementation uses `log/debug!` with a comment explaining that content-addressed evidence duplicates are expected during replay and are not an operational concern. This is a **correct decision** but undocumented as a divergence from the original plan.

**Recommendation:** No action needed — the existing comment suffices. Consider archiving the analysis more explicitly.

---

### Finding 5: No cross-layer interaction test

| Severity | Medium |
|----------|--------|
| Files | `idempotency.clj`, `dispute_resolution_coverage_test.clj`, `IDEMPOTENCE_CHECKLIST.md` |
| Status | Missing |

There is no test that verifies the interaction between business-logic idempotence and replay-boundary dedup when both layers are active on the same action. For example, calling `execute-pending-settlement` twice via replay with the same `event-id` — the replay-boundary layer should short-circuit before the business guard even fires.

**Recommendation:** Add a test that exercises both layers simultaneously to confirm `:no-op-duplicate` takes precedence over business-logic rejection.

---

### Finding 6: `register-attestation!` idempotency is implicit

| Severity | Low |
|----------|-----|
| Files | `attestation_registry.clj:68-80` |
| Status | Idempotent by structure, no explicit test |

`register-attestation!` uses `swap! assoc` which is inherently idempotent. No dedicated idempotency test exists (unlike `register-evidence!` which has both sequential and concurrent tests).

**Recommendation:** Add a `register-attestation-idempotent` test analogous to the evidence-chain one.

---

### Finding 7: Archived analysis not fully resolved

| Severity | Low |
|----------|-----|
| File | `docs/archive/idepotence_analysis.md` |
| Status | Partially superseded |

The archived `idepotence_analysis.md` (note misspelled filename) proposes 5 phases. Phase 1 (concurrency controls) and Phase 2 (logging) were implemented for evidence but not for other functions. Phase 3 (concurrency tests) was done for evidence but not for Sew operations. Phase 4 (non-deterministic elements) is partially addressed by the replay design. Phase 5 (state management audit) lacks evidence of completion.

**Recommendation:** Either formally close the archived plan with a disposition table, or remove it if fully superseded by IDEMPOTENCE_CHECKLIST.md.

---

## Sign-off Assessment

| Criteria | Status | Notes |
|----------|--------|-------|
| Calculations reproducible | PASS | Deterministic replay verified by `replay-idempotent-same-trace?` |
| Reserves defensible | N/A | Not a financial model |
| Entity boundaries explicit | N/A | Not a multi-entity model |
| Key risks surfaced | PASS (with caveats) | See Findings 5, 6 |
| Required advisor questions listed | See below | |

**Advisor questions:**
1. Should replay-boundary dedupe be extended to all Sew actions (not just the 8 `replay-sensitive-actions`)?
2. Should `register-attestation!` get explicit concurrency hardening like `register-evidence!`?
3. Should the checklist formally track cross-layer interaction coverage?
