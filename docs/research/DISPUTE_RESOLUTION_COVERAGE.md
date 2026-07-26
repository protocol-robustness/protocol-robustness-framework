# Dispute Resolution Coverage Map

**Audience:** First-time researcher. No prior codebase knowledge assumed.

**Last updated:** 2026-06-19

This document maps the dispute-resolution research coverage in the Protocol
Robustness Framework. It answers:

- Which dispute behaviours can I inspect today?
- Which scenarios exercise which failure modes?
- Which research gaps are still open?
- How do I run a scenario and read its artifacts?

---

## 1. Quick start

```bash
# Run all dispute-resolution scenarios
clojure -M:test -n resolver-sim.dispute-resolution-coverage-test

# Generate coverage report
clojure -M:coverage-report -- scenarios results/test-artifacts/coverage.json

# Run one scenario file directly
clojure -M:run -- --scenario scenarios/S-DR-001-basic-release-ruling.json
```

After a run, artifacts appear under `results/test-artifacts/`. Each dispute
scenario produces at least:

| Artifact | File | What it contains |
|----------|------|-----------------|
| Trace summary | `trace-summary.json` | Ordered event list, reverts, invariant results per step |
| Dispute summary | `dispute-summary.json` | Dispute ID, parties, resolver, status, ruling, appeal status |
| Evidence summary | `evidence-summary.json` | Evidence IDs, hashes, timestamps, world-before/world-after hashes |
| Financial outcome | `financial-outcome.json` | Requested amount, paid, deferred, unmet, waived |
| Invariant results | `invariant-results.json` | All invariant checks per step (holds? / violations) |
| Artifact registry | `test-artifacts.json` | Links all above artifacts into one content-addressed registry |

---

## 2. Scenario inventory

Scenarios are named `S-DR-{NNN}-{descriptive-name}.json` in `scenarios/`.

### 2.1 Basic lifecycle — `:coverage/basic-lifecycle`

Verifies the fundamental dispute flow from open to final.

| ID | Scenario | Tests | Implemented |
|----|----------|-------|-------------|
| S-DR-001 | basic-release-ruling | Dispute raised → resolver rules release → settlement finalizes | ✅ |
| S-DR-002 | basic-refund-ruling | Dispute raised → resolver rules refund → settlement finalizes | ✅ |
| S-DR-003 | duplicate-dispute-rejected | Second `raise_dispute` on same escrow fails with state-machine error | ✅ |
| S-DR-004 | timeout-default-resolution | No resolver action → timeout → keeper auto-cancels | ✅ |
| S-DR-055 | sender-cancel-refund | Sender cancels non-disputed escrow → refunded | ✅ |

**Invariant expectations:**
- `:dispute-level-bounded` — level stays in [0, max-dispute-level]
- `:dispute-timestamp-consistent` — timestamp > 0 while disputed
- `:single-resolution-payout-consistent` — exactly one payout direction
- `:conservation-of-funds` — no value leak after settlement

### 2.2 Evidence robustness — `:coverage/evidence`

Tests how the protocol behaves when evidence quality varies.

| ID | Scenario | Tests | Implemented |
|----|----------|-------|-------------|
| S-DR-010 | missing-evidence | `submit-evidence` with no hash; record accepted | ✅ |
| S-DR-011 | contradictory-evidence | Multiple `submit-evidence` calls with different hashes | ✅ |
| S-DR-056 | evidence-non-disputed-rejected | `submit-evidence` on pending (non-disputed) escrow rejected | ✅ |
| S-DR-012 | late-evidence-rejected | Submit after resolution deadline | ❌ *See gap |
| S-DR-013 | evidence-at-deadline | Submit exactly at the deadline boundary | ❌ *See gap |

**Invariant expectations:**
- `:evidence-updated` flag set on workflow after submission
- Evidence hashes recorded on world state

**Coverage gaps:**
- Model has no evidence deadline concept. `submit-evidence` is accepted
  anytime while `:disputed`. To test deadline enforcement, add an
  `evidence-window-duration` protocol param and guard.
- World-before/world-after hashes are not yet linked to evidence records in
  targeted evidence. See `:world-hash-linkage` gap.

### 2.3 Strategic disputants — `:coverage/strategic`

Tests that the protocol correctly handles adversarial participant strategies.

| ID | Scenario | Tests | Implemented |
|----|----------|-------|-------------|
| S-DR-020 | false-claimant-slashed | Seller proposes fraud slash against false-claimant buyer | ✅ |
| S-DR-021 | griefing-claim-cost | Buyer raises frivolous dispute; no financial gain | ✅ |
| S-DR-022 | lazy-counterparty-timeout | Counterparty never responds; timeout path triggers | ✅ |
| S-DR-052 | custom-resolver-bypasses-module | Custom resolver bypasses resolution module via Priority 1 | ✅ |
| S-DR-053 | module-false-fallthrough | Module returns false, falls through to dispute-resolver | ✅ |
| S-DR-054 | missing-escalation-level | escalation-resolvers missing level 1 → clean rejection | ✅ |

**Invariant expectations:**
- `:slash-status-consistent` — slash lifecycle coherent
- `:fraud-slash-executions-accounted` — executed slashes reflected in totals
- `:conservation-of-funds` — slashed amounts distributed correctly

**Coverage gaps:**
- "Party appeals only when expected value is positive" requires an EVM
  game-theoretic model not yet integrated.
- "Party appeals irrationally despite negative EV" same gap.

### 2.4 Resolver integrity — `:coverage/resolver-integrity`

Tests that resolver behaviour is attributable, slashable, and reversible.

| ID | Scenario | Tests | Implemented |
|----|----------|-------|-------------|
| S-DR-030 | biased-resolver-appealed | L0 rules incorrectly → challenge → L1 reverses | ✅ |
| S-DR-031 | colluding-resolver-detected | Resolver colludes with buyer; watchdog challenges | ✅ |
| S-DR-032 | resolver-insufficient-stake | Slash amount exceeds resolver stake; partial execution | ✅ |

**Invariant expectations:**
- `:escalation-level-monotonic` — levels advance by 1 only
- `:bond-slash-bounded` — slash <= posted bond
- `:slash-distribution-consistent` — slashed amount = insurance + protocol + retained
- `:resolver-capacity` — counter tracks open disputes

**Coverage gaps:**
- "Honest resolver" is the baseline; covered by S-DR-001, S-DR-002.
- "Lazy resolver misses deadline" — model has no resolver-assignment deadline.
  Would need `resolver-response-window` param.
- "Resolver is slashed and obligation allocated correctly" — partial coverage
  via S-DR-020; full allocation waterfall (prorata, senior pool, reserve)
  is a yield/liquidity concern (see Y scenarios).

### 2.5 Finality and payout correctness — `:coverage/finality`

Tests that payouts are blocked until finality and correct after.

| ID | Scenario | Tests | Implemented |
|----|----------|-------|-------------|
| S-DR-040 | finality-blocked-during-appeal | Settlement rejected while appeal window open | ✅ |
| S-DR-041 | finality-after-appeal-window | Settlement succeeds after appeal window closes | ✅ |
| S-DR-042 | duplicate-claim-after-finality-rejected | Second `execute_pending_settlement` fails | ✅ |
| S-DR-043 | payout-shortfall-deferred | Yield shortfall: partial payout, remainder deferred | ✅ |
| S-DR-044 | slash-obligation-unmet-recorded | Slash proposed but insufficient balance; unmet recorded | ✅ |

**Invariant expectations:**
- `:finalization-accounting-correct` — held drops by AFA, claimable increases
- `:no-double-finalize` — each workflow finalizes at most once
- `:settlement-principal-boundary` — claims never exceed escrow principal
- `:shortfall-fidelity` — fulfilled + deferred + haircut = basis

**Coverage gaps:**
- "Senior pool / reserve exposure capped" — covered by
  `:senior-coverage-not-exceeded` invariant, but no dedicated scenario yet.
  Add S-DR-045 when senior-pool integration matures.

---

## 3. Invariant catalog (dispute-specific)

Invariants below supplement the world-level invariants in
`src/resolver_sim/protocols/sew/invariants.clj`.

### 3.1 Dispute lifecycle invariants

| ID | Check | Enforced |
|----|-------|----------|
| `:dispute-resolved-once` | A dispute cannot be resolved twice | Structural (state machine: `:disputed` → terminal is absorbing) |
| `:no-appeal-before-ruling` | Appeal requires a prior resolution | `:no-resolution-to-appeal` guard on escalation |
| `:no-finality-before-appeal-window` | Settlement blocked while window open | `:appeal-window-not-expired` guard on `execute_pending_settlement` |
| `:evidence-after-deadline-rejected` | Evidence rejected after deadline | **NOT ENFORCED** — no deadline concept in model |
| `:duplicate-dispute-rejected` | Cannot dispute an already-disputed escrow | State machine (`:pending` → `:disputed` only) |

### 3.2 Evidence linkage invariants

| ID | Check | Enforced |
|----|-------|----------|
| `:evidence-on-state-change` | Every dispute event has evidence | Yes — `raise-dispute`, `execute-resolution`, `escalate`, etc. all emit evidence |
| `:targeted-evidence-metadata` | Evidence includes scenario-id, run-id, event-index, event-type | Yes — via `resolver-sim.evidence.capture/default-metadata` |
| `:world-hash-linkage` | Targeted evidence includes world-before / world-after hashes | **GAP** — evidence records carry `:world/before-hash` and `:world/after-hash` at the capture level, but they are not exposed as a researcher-visible linkage in `evidence-summary.json` |

### 3.3 Financial conservation invariants

Each dispute outcome partition:

```
requested-amount = paid + deferred + unmet + waived
```

Each slash obligation:

```
slash-amount = debited + allocated + unmet + waived
```

No account, pool, bond, or claimable balance may go negative.

| ID | Check | Enforced |
|----|-------|----------|
| `:conservation-of-funds` | Inflow = accounted | ✅ |
| `:settlement-principal-boundary` | Claims <= escrow principal | ✅ |
| `:settlement-yield-boundary` | Claims <= available yield | ✅ |
| `:shortfall-fidelity` | fulfilled + deferred + haircut = basis | ✅ |
| `:slash-distribution-consistent` | slash-total = insurance + protocol + retained | ✅ |

### 3.4 Finality invariants

Financial finality must remain blocked while:
- Appeal window is open
- Slash appeal is pending
- Yield recovery is pending
- Required evidence decision is unresolved (gap: no evidence deadline)

Once finality is reached, duplicate claims must fail.

| ID | Check | Enforced |
|----|-------|----------|
| `:no-double-finalize` | Each workflow finalizes ≤ 1 | ✅ Structural |
| `:time-no-action-after-finality` | No state changes after terminal | ✅ |
| `:finalization-accounting-correct` | Held → claimable matches | ✅ |

### 3.5 Resolver integrity invariants

| ID | Check | Enforced |
|----|-------|----------|
| `:slash-status-consistent` | Slash lifecycle is coherent | ✅ |
| `:fraud-slash-executions-accounted` | Executed slashes reflected in totals | ✅ |
| `:resolver-capacity` | Counter tracks open disputes | ✅ |
| `:escalation-level-monotonic` | Levels advance by 1 | ✅ |

---

## 4. Coverage gaps summary

| Coverage area | Gap | Impact | Mitigation |
|---------------|-----|--------|------------|
| Evidence | No evidence deadline | Cannot test late-evidence rejection | Add `evidence-window-duration` param |
| Evidence | ~~World-hash linkage not in researcher artifact~~ | ✅ RESOLVED — `evidence-summary.json` now surfaces `world/before-hash` and `world/after-hash` for every evidence record | `evidence-summary.json` emitted by `evidence.summary/write-evidence-summary!` |
| Evidence | ~~`world-before-hash` / `world-after-hash` not populated in targeted evidence~~ | ✅ RESOLVED — world hashes captured at `capture-event-evidence!` level via `:world-before` / `:world-after` opts | Verifiable in `evidence-summary.json` `world/before-hash` and `world/after-hash` fields |
| Strategic | No expected-value model for appeal decisions | Cannot test EV-rational vs irrational appeal | Future: game-theoretic EV module |
| Resolver | No resolver response deadline | Cannot test lazy-resolver timeout | Future: `resolver-response-window` protocol param |
| Resolver | No resolver bribery model | Cannot directly test bribe-then-slash | Covered indirectly via `:adversarial?` flag in S-DR-031 |
| Finality | No senior-pool exposure scenario | Reserve cap only checked by invariant, not scenario | Add S-DR-045 |

---

## 5. Running coverage report

```clojure
;; From the REPL:
(require '[resolver-sim.scenario.dispute-coverage :as dc])
(dc/dispute-resolution-coverage-report)
;; => {:suite :dispute-resolution
;;     :total-scenarios 20
;;     :by-coverage {:basic-lifecycle 4 :evidence 2 :strategic 3 :resolver-integrity 3 :finality 5}
;;     ...}
```

The report returns a structured map with total count, breakdown by coverage
area, missing scenarios list, and researcher-readiness flags.

---

## 6. Adding a new dispute scenario

1. Choose an ID from the gap table above or create a new `S-DR-{NNN}`.
2. Create JSON file in `scenarios/` following the CDRS v1.0 schema.
3. Add `:coverage/` and `:suite/dispute-resolution` tags.
4. Register in `dispute-coverage-report` if needed.
5. Run `clojure -M:test -n resolver-sim.dispute-resolution-coverage-test`
   to verify determinism.
6. Check `results/test-artifacts/dispute-summary.json` for output shape.
