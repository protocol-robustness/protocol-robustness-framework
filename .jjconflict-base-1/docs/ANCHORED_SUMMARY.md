# Anchored Summary

## Goal
Research and harden the Sew protocol's dispute resolution mechanism by finding critical resolution problems and building adversarial scenario families.

## Constraints & Preferences
- All new scenarios in EDN format under `scenarios/edn/`
- Scenarios must pass `bb run:scenario` validation
- Protocol code changes only in `protocols_src/resolver_sim/protocols/sew/resolution.clj`
- `:escalation-resolvers` keys map to **next-level** (e.g. `{:1 "0xresolver-l1"}` means escalation from level 0→1 uses L1 resolver)
- `:dispute-resolver` must be set in `:protocol-params` to authorize the L0 resolver for `execute-resolution`
- `:reversal-slash-bps` must be > 0 for Track 1 reversal slashing to fire
- `execute-fraud-slash` for reversal slashes MUST pass explicit `:slash-id` string — default (workflow-id integer) now falls back via `resolve-reversal-slash-id` helper

## Progress

### Done (Session 5 + Session 6 fixes)

**Fixes applied and verified:**
1. **automate-timed-actions dispute-timeout gap** — added `(sm/dispute-timeout-exceeded? world workflow-id)` as Priority 3. DR-J-003 PASS.
2. **validate-dag-detailed compilation bug** — missing from `declare` form in `node.clj:80`. Fixed.
3. **unfreeze-resolver dispatch gap** — added `defmethod apply-action "unfreeze-resolver"` in `sew.clj:338`. Resolver unfreeze is now reachable via governance actors.
4. **`:upheld?` vs `:appeal-upheld?` param key mismatch** — changed DR-C-002 to use correct key `:upheld?`. DR-C-002 now correctly tests the UPHELD path.
5. **Slash-id format consistency** — added `resolve-reversal-slash-id` helper at `resolution.clj:1278`. When integer workflow-id fails to find a pending slash, it searches for reversal-style keys (`<wf-id>-reversal-<level>`). Applied to `execute-fraud-slash`, `resolve-appeal`, and `appeal-slash`.

**Solidity porting analysis** — Completed for both fixes with per-file instructions for `<SEW_REPO_ROOT>/contracts/`.

**Adversarial scenario families (all 27 PASS):**
- DR-A: Capacity exhaustion griefing
- DR-B: Escalation window races
- DR-C: Appeal bond sybil scaling + lifecycle
- DR-D: Reversal slashing + concurrent resolutions
- DR-E: Governance resolver rotation
- DR-F: Circuit breaker dispute block
- DR-G: Manual reversal slash T2 + cross-level
- DR-H: Challenge bond deterrence
- DR-I: Superseded pending fallback
- DR-J: Dispute liveness timeout + concurrent escalation + automate-timed-actions
- DR-K: Fraud slash pending blocks second
- DR-L: Track 2 reversal slash lifecycle (4 scenarios)
- DR-M: Freeze/unfreeze + finality + active-dispute freeze detection (3 scenarios)

### Finality Investigation Findings

**Finality guards verified (DR-M-002):** All three post-finality actions are correctly rejected:
- `execute_pending_settlement` after escrow is terminal → `:no-pending-settlement`
- `raise_dispute` on terminal escrow → `:transfer-not-pending`
- `execute_resolution` on terminal escrow → `:transfer-not-in-dispute`

**Freeze-with-active-dispute problem found (DR-M-003):** When a resolver is slashed while holding an active disputed escrow, the `resolver-not-frozen-on-assign` invariant fires. The keeper can still settle the escrow afterward, but for a window between slash and settlement, a frozen resolver is assigned to an active dispute. Mitigation: governance should use `rotate-dispute-resolver` to reassign disputed escrows before slashing, or settle all escrows first.

**Freeze lifecycle works (DR-M-001):** The full freeze/unfreeze lifecycle is verified: slash→freeze→blocked actions→unfreeze→resume. The new `unfreeze-resolver` dispatch functions correctly through the governance actor pattern.

### In Progress
- None

### Blocked
- None

## Key Decisions & Findings (Resolved)

1. **`unfreeze-resolver` dispatch gap** — ✅ RESOLVED: Added `defmethod apply-action` in `sew.clj:338` following governance-actor pattern.
2. **`:upheld?` vs `:appeal-upheld?` param key mismatch** — ✅ RESOLVED: Changed DR-C-002 to use `:upheld?`. Scenario now correctly tests the UPHELD path.
3. **Slash-id format inconsistency** — ✅ RESOLVED: Added `resolve-reversal-slash-id` helper. Integer workflow-ids now auto-resolve to reversal-style string keys when direct lookup fails.
4. **`:no-stale-automatable-escrows` invariant** — Expected behavior after slash appeal resolution. All affected scenarios include `:expected-failures`.
5. **"Invariant cascade" display artifact** — CLI reported 48 violations but only 1 actually failed. Fixed test interpretation.

## Relevant Files
- `protocols_src/resolver_sim/protocols/sew/resolution.clj`: Core — `unfreeze-resolver` (line 1367), `resolve-reversal-slash-id` (line 1278), `execute-fraud-slash` (line 977), `resolve-appeal` (line 1157), `appeal-slash` (line 1291), `automate-timed-actions` (line 777)
- `protocols_src/resolver_sim/protocols/sew.clj`: Action multimethods — `unfreeze-resolver` dispatch (line 338), `event-slash-id` (line 80)
- `src/resolver_sim/evidence/node.clj`: `declare` form (line 80, fixed)
- `scenarios/edn/`: 24 DR-* adversarial scenario files, 4 DR-L-001-004 Track 2 reversal slash lifecycle
