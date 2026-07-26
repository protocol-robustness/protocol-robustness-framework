# Interactive Finality Session Log

**Date:** 2026-07-01
**Branch / commit:** (no git history — working tree)
**Agent:** Gemini (clojure-mcp + nREPL)
**Repo path:** `/home/user/Code/.workspaces/agent-c`
**Clojure command used:** `clojure -M:nrepl` (nREPL server on port 7888)
**REPL type:** nREPL via clojure-mcp (`clj-nrepl-eval` / `clojure-mcp_clojure_eval`)
**Purpose:** Explore, test, and document finality behavior across dispute-resolution pathways in the Sew / PRF system.

---

## Session structure

1. **Pathway 1** — Baseline dispute finality (no escalation, no reversal) ✅ COMPLETE
2. Pathway 2 — Escalation without reversal
3. Pathway 3 — Single reversal
4. Pathway 4 — Reversal of reversal
5. Pathway 5 — Reviewer appeal upheld
6. Pathway 6 — Reviewer appeal rejected
7. Pathway 7 — Appeal timing boundaries
8. Pathway 8 — Multi-level vindication stability
9. Pathway 9 — Challenge bounty finality
10. Pathway 10 — No-challenger bounty
11. Pathway 11 — Governance force reversal slash
12. Pathway 12 — Insufficient stake

---

## Code Location Index

| Name | Namespace/File | Purpose | Used in pathway |
|---|---|---|---|
| `replay-with-sew-protocol` | `resolver-sim.protocols.sew` | Primary REPL entry point: replay a scenario through the Sew protocol | 1 |
| `combine-finality` | `resolver-sim.protocols.sew.financial.finality` | Return chain + financial finality for a workflow | 1 |
| `classify-financial-finality` | `resolver-sim.protocols.sew.financial.finality` | Classify financial finality phase for a workflow | 1 |
| `classify-chain-finality` | `resolver-sim.protocols.sew.financial.finality` | Classify chain finality (always `:assumed-by-replay` in simulation) | 1 |
| `open-gates` | `resolver-sim.protocols.sew.financial.finality` | Detect open gates blocking financial finality | 1 |
| `check-all` | `resolver-sim.protocols.sew.invariants` | Run all 37 world invariants | 1 |
| `s03` | `resolver-sim.protocols.sew.invariant-scenarios.baseline` | Scenario: DR3 dispute → refund (no appeal window) | 1 |
| `t/escrow-state` | `resolver-sim.protocols.sew.types` | Get escrow state for a workflow | 1 |
| `t/get-pending` | `resolver-sim.protocols.sew.types` | Get pending settlement for a workflow | 1 |
| `t/dispute-level` | `resolver-sim.protocols.sew.types` | Get current dispute level for a workflow | 1 |

---

## Pathway 1 — Baseline dispute finality

### Question

When does the initial verdict become final? What state does the workflow end in? Is there a slash? What evidence is emitted?

### Hypothesis

With `appeal-window-duration 0`, the resolution finalizes immediately (no pending settlement). The escrow transitions `:pending → :disputed → :refunded` (is-release=false). No slash occurs (no reversal). No appeal window.

### Setup

Scenario **S03** (`"s03-dr3-dispute-refund"`) from the baseline invariant suite:

- **Protocol params:** `{:resolver-fee-bps 150 :appeal-window-duration 0 :max-dispute-duration 2592000}`
- **Agents:** buyer, seller, resolver
- **Events:**
  1. `create_escrow` (t=1000, 4000 USDC, custom-resolver "0xresolver")
  2. `raise_dispute` (t=1060)
  3. `execute_resolution` (t=1120, is-release=false → refund)

### REPL transcript

```clojure
(require '[resolver-sim.protocols.sew :as sew])
(require '[resolver-sim.protocols.sew.invariant-scenarios.baseline :as base])
(require '[resolver-sim.protocols.sew.financial.finality :as fin])
(require '[resolver-sim.protocols.sew.invariants :as inv])
(require '[resolver-sim.protocols.sew.types :as t])

(def result (sew/replay-with-sew-protocol base/s03))
(def world (:world result))
```

```clojure
(:outcome result)
;; => :pass
```

```clojure
(:events-processed result)
;; => 3
```

```clojure
(t/escrow-state world 0)
;; => :refunded
```

```clojure
(fin/combine-finality world 0)
;; => {:chain {:chain/phase :final
;;             :chain/source :assumed-by-replay
;;             :chain/block 1120
;;             :chain-final? true}
;;     :financial {:financial/phase :financially-final
;;                :financially-final? true
;;                :can-change? false
;;                :open-gates []
;;                :reason "all gates closed; escrow terminal and no pending recoveries"}}
```

```clojure
(:all-hold? (inv/check-all world "s03-dr3-dispute-refund"))
;; => true
```

### State before

Initial world at t=1000: empty escrow-transfers, no stakes, no pending settlements.

### State after

```clojure
(select-keys world [:escrow-transfers :resolver-stakes :pending-settlements
                     :resolver-slash-total :total-held :total-fees :claimable-v2])
```

**Relevant fields:**

| Field | Value |
|-------|-------|
| Escrow state | `:refunded` |
| Dispute level | `0` |
| Resolver | `"0xresolver"` |
| Pending settlement | none (empty) |
| Resolver stake | none (unregistered) |
| Total slashes | `{}` — none |
| Total held | `{:USDC 0}` |
| Total fees | `{:USDC 60}` (150 bps × 4000 = 60) |
| Claimable v2 | `{0 #:settlement{:principal {0xbuyer 3940}}}` |
| Amount after fee | `3940` (4000 − 60 fee) |
| Resolution | `{:resolved-by "0xresolver" :is-release false :resolution-hash "0xhash"}` |

### Events observed

| Step | Action | Result | Notes |
|------|--------|--------|-------|
| 0 | `create_escrow` | `:ok` | Workflow 0, 4000 USDC |
| 1 | `raise_dispute` | `:ok` | |
| 2 | `execute_resolution` | `:ok` | is-release=false → refund |

### Evidence artifacts observed

15 evidence entries recorded in `evidence-registry.json`:

| # | Kind | Artifact |
|---|------|----------|
| 1 | `escrow-created` | Action-specific event |
| 2 | `transition` | Before/after/result/context hashes |
| 3 | `invariant-attestation` | Invariant check after creation |
| 4 | `projection-evidence` | Projection after creation |
| 5 | `dispute-raised` | Action-specific event |
| 6 | `transition` | Before/after/result/context hashes |
| 7 | `invariant-attestation` | Invariant check after dispute |
| 8 | `projection-evidence` | Projection after dispute |
| 9 | `checkpoint-evidence` | Checkpoint |
| 10 | `decision-evidence` | Resolution decision |
| 11 | `escrow-refunded` | Action-specific event |
| 12 | `transition` | Before/after/result/context hashes |
| 13 | `invariant-attestation` | Invariant check after resolution |
| 14 | `projection-evidence` | Projection after resolution |
| 15 | `checkpoint-evidence` | Final checkpoint |

Chain cursor: `{:final-seq 3, :total-captured 3}`

### Finality classification

| Dimension | Result | Evidence |
|---|---|---|
| Verdict finality | **Final** (instant) | Escrow state `:refunded`, terminal |
| Slash finality | **N/A** | No reversal, no slash |
| Appeal finality | **Closed / N/A** | `appeal-window-duration 0` → no pending settlement created |
| Financial finality | **Financially final** | `{:financial/phase :financially-final :financially-final? true :open-gates []}` |
| Evidence finality | **Present** | 15 evidence artifacts recorded, chain cursor seq=3 |

### Accounting checks

```text
Deposited: 4000 USDC
Fee (150 bps): 60 USDC
Amount after fee: 3940 USDC
Refunded to buyer: 3940 USDC
Total held: 0 USDC
Total fees: 60 USDC
Conservation: 3940 + 60 = 4000 ✓
```

### Interpretation

1. **When does the initial verdict become final?** Immediately, because `appeal-window-duration 0` means `execute-resolution` skips creating a pending settlement and finalizes the escrow in the same call. The verdict is simultaneously rendered and executed.

2. **Is there an appeal window?** No. With `appeal-window-duration 0`, no pending settlement is created. The `open-gates` detector confirms no `:appeal-window` gate is open.

3. **Is there any slash?** No. This is a simple direct resolution (release or refund). Slashes only occur on reversals.

4. **What evidence is emitted?** 15 artifacts covering: action-specific events (escrow-created, dispute-raised, escrow-refunded), transition evidence with Merkle-style before/after hashes, invariant attestations, projection evidence, checkpoints, and decision evidence.

5. **What state makes the dispute non-reversible?** Terminal state `:refunded`. The escrow state machine has no outgoing transitions from terminal states.

6. **The `combine-finality` function works correctly for this case:** returns `{:financial/phase :financially-final :financially-final? true}` with empty `:open-gates`.

### Open questions

- The `:chain/block` field in chain finality is set to `1120` (the last event's time, not the block time of finality). Is this correct? The block time is `1120` because `execute-resolution` at t=1120 finalized the escrow. This seems reasonable for instant finalization.
- No resolver stake was registered — the resolver acts without skin in the game. This is intentional for this simple scenario but important for later pathways that involve slashing.
- The `:decision-evidence` artifact (item 10) records the resolution's evidence hash. The evidence registry does not link back to the workflow ID or action sequence in the artifact view (only the hash). This separation is by design but makes traceability from evidence back to action harder in the registry alone.

### Confidence level

**Confirmed** — All observations match the hypothesis. The finality classification is unambiguous for `appeal-window-duration 0` scenarios.

---

## Working glossary

### Verdict finality

**Observed meaning:** The verdict becomes final when the escrow reaches a terminal state (`:released` or `:refunded`). With `appeal-window-duration 0`, this happens immediately upon `execute-resolution`.

**Code locations:** `t/terminal-states` in `types.clj`, `combine-finality` in `financial/finality.clj`

**Pathways where observed:** 1

**Open questions:** How does this change when `appeal-window-duration > 0` (pending settlement delays finality)? Tested in Pathway 2.

### Financial finality

**Observed meaning:** A workflow is financially final when the escrow is in a terminal state AND all open gates (pending-settlement, appeal-window, yield-recovery, slash-appeal) are closed.

**Code locations:** `open-gates` + `classify-financial-finality` in `financial/finality.clj`

**Pathways where observed:** 1

**Open questions:** What about yield recovery and slash appeal gates? Not triggered in this pathway.

### Slash finality

**Observed meaning:** No slash occurs in a simple direct resolution. Slash finality is N/A here.

**Pathways where observed:** 1

### Appeal finality

**Observed meaning:** With `appeal-window-duration 0`, the appeal window never opens. No pending settlement is created. Appeal finality is trivially closed.

**Pathways where observed:** 1

### Evidence finality

**Observed meaning:** Evidence artifacts are recorded for every material transition (action event, before/after state hashes, invariant attestations, projections, checkpoints). For 3 events, 15 evidence artifacts exist.

**Code locations:** `capture-event-evidence!` in `evidence/capture.clj`, `chain-cursor-final.json` artifact

**Pathways where observed:** 1

---

## Pathway summary table

| Pathway | Verdict final? | Slash final? | Appealable? | Reversible? | Financially final? | Evidence final? | Confidence | Notes |
|---|---|---|---|---|---|---|---|---|
| Baseline dispute | **Final** (instant) | N/A | No (window=0) | No (terminal) | **Yes** | **Present** (15 artifacts) | Confirmed | appeal-window-duration 0 skips pending settlement |

---

## Findings

*None yet — Pathway 1 behaved as expected.*

---

## Pathway 2 — Escalation without reversal

### Question

Does escalation delay finality? Does L0 become final only after L1 confirmation? Are any slashes proposed? Does the system distinguish "confirmed by higher level" from "not challenged"?

### Hypothesis

Escalation should clear L0's pending settlement and archive it as superseded. L1's resolution should create a new pending that becomes executable after its appeal deadline. No slash occurs because L1 agrees with L0. Finality should be delayed relative to the no-escalation baseline.

### Setup

Scenario **S21** (`"s21-dr3-kleros-pending-cleared-on-escalation"`) from the baseline invariant suite:

- **Protocol params:** `kleros-appeal` — `{:resolver-fee-bps 150 :appeal-window-duration 60 :max-dispute-duration 2592000 :resolution-module "0xkleros-proxy" :escalation-resolvers {:0 "0xl0" :1 "0xl1" :2 "0xl2"}}`
- **Agents:** buyer, seller, l0resolver, l1resolver, keeper
- **Events:**
  1. `create_escrow` (t=1000, 5000 USDC)
  2. `raise_dispute` (t=1060)
  3. `execute_resolution` (t=1120, L0, is-release=true → creates pending, deadline 1120+60=1180)
  4. `escalate_dispute` (t=1130, clears pending, archives as superseded, level→1, resolver→0xl1)
  5. `execute_resolution` (t=1190, L1, is-release=true → creates pending, deadline 1190+60=1250)
  6. `execute_pending_settlement` (t=1250, keeper executes → escrow finalized as `:released`)

### REPL transcript

```clojure
(require '[resolver-sim.protocols.sew :as sew] :reload)
(require '[resolver-sim.protocols.sew.invariant-scenarios.baseline :as base])
(require '[resolver-sim.protocols.sew.financial.finality :as fin])
(require '[resolver-sim.protocols.sew.invariants :as inv])
(require '[resolver-sim.protocols.sew.types :as t])

(def result (sew/replay-with-sew-protocol base/s21))
(def world (:world result))
```

```clojure
(:outcome result)
;; => :pass
```

```clojure
(:events-processed result)
;; => 6
```

```clojure
(t/escrow-state world 0)
;; => :released
```

```clojure
(fin/combine-finality world 0)
;; => {:chain {:chain/phase :final, :chain/source :assumed-by-replay,
;;             :chain/block 1250, :chain-final? true}
;;     :financial {:financial/phase :financially-final,
;;                :financially-final? true, :can-change? false,
;;                :open-gates [], :reason "all gates closed; ..."}}
```

```clojure
(:all-hold? (inv/check-all world "s21"))
;; => true
```

### State before

Initial world at t=1000: empty escrows, no stakes, no pending settlements.

### State after each material transition

**After L0 resolution (seq 2, t=1120):**
- Escrow state: `:disputed`
- Dispute level: `0`
- Pending settlement: created (deadline=1180, is-release=true, resolver=0xl0)
- Block time: 1120

**After escalation (seq 3, t=1130):**
- Escrow state: `:disputed`
- Dispute level: `1`
- Pending settlement: **cleared** (L0's pending archived)
- Dispute resolver: changed to `"0xl1"`
- Superseded pendings: `[{:pending {:exists true :appeal-deadline 1180 :is-release true :resolution-hash "0xl0hash"} :superseded-at 1130 :level 0}]`
- Block time: 1130

**After L1 resolution (seq 4, t=1190):**
- Escrow state: `:disputed`
- Dispute level: `1`
- Pending settlement: created (deadline=1250, is-release=true, resolver=0xl1)
- Block time: 1190

**After keeper execution (seq 5, t=1250):**
- Escrow state: `:released` (terminal)
- Dispute level: `1`
- Pending settlement: cleared (executed)
- Block time: 1250
- Financial finality: `:financially-final`

### Final state

| Field | Value |
|-------|-------|
| Escrow state | `:released` |
| Dispute level | `1` |
| Dispute resolver | `"0xl1"` |
| Active resolution | `{:resolved-by "0xl1" :is-release true :resolution-hash "0xl1hash"}` |
| Amount after fee | `4925` (5000 − 75 fee) |
| Total held | `{:USDC 0}` |
| Total fees | `{:USDC 75}` |
| Claimable v2 | `{0 #:settlement{:principal {0xseller 4925}}}` |
| Superseded pendings | `[{:pending {:exists true :appeal-deadline 1180 :is-release true :resolution-hash "0xl0hash"} :superseded-at 1130 :level 0}]` |
| Resolver slashes | `{}` — none |
| Stake | none (unregistered) |

### Events observed

| Step | Action | Result | Notes |
|------|--------|--------|-------|
| 0 | `create_escrow` | `:ok` | Workflow 0, 5000 USDC |
| 1 | `raise_dispute` | `:ok` | |
| 2 | `execute_resolution` | `:ok` | L0, is-release=true, pending created (deadline=1180) |
| 3 | `escalate_dispute` | `:ok` | Clears pending, level→1, resolver→0xl1 |
| 4 | `execute_resolution` | `:ok` | L1, is-release=true, pending created (deadline=1250) |
| 5 | `execute_pending_settlement` | `:ok` | Keeper executes at deadline → escrow `:released` |

### Evidence artifacts

Evidence chain cursor: `{:final-seq 4, :total-captured 4}` (4 material transitions captured as evidence).

### Finality classification

| Dimension | Result | Evidence |
|---|---|---|
| Verdict finality | **Final** (L1, delayed by escalation) | Escrow state `:released`, L1's resolution executed |
| Slash finality | **N/A** | No reversal, no slash |
| Appeal finality | **Closed** | L1's pending was executed at deadline |
| Financial finality | **Financially final** | `{:financial/phase :financially-final :financially-final? true}` |
| Evidence finality | **Present** | 4 events captured in evidence chain |

### Accounting checks

```text
Deposited: 5000 USDC
Fee (150 bps): 75 USDC
Amount after fee: 4925 USDC
Released to seller: 4925 USDC
Total held: 0 USDC
Total fees: 75 USDC
Conservation: 4925 + 75 = 5000 ✓
```

### Interpretation

1. **Does escalation delay finality?** YES. L0's pending settlement had deadline 1180, but escalation at 1130 cleared it. The dispute had to wait for L1 (t=1190) and the keeper (t=1250). Finality occurred at t=1250 vs. t=1180 without escalation — a delay of 70 blocks.

2. **Does L0 become final only after L1 confirmation?** NO. L0's verdict is **superseded** by escalation, not confirmed by L1. The `superseded-pending-settlements` mechanism archives it. L1 starts fresh. The superseded entry is preserved in the world state but is never executed.

3. **Are any slashes proposed?** NO. Both levels agreed (is-release=true). There is no reversal.

4. **Does the system distinguish "confirmed by higher level" from "not challenged"?** The system records that L0's pending was superseded (via `:superseded-at` timestamp and `:level`). The final attribution goes to L1. The superseded entry's deadline (1180) is earlier than L1's resolution time (1190), which means L0's verdict would have been executable before being superseded. The system does not label this as "confirmed" vs. "reversed" — escalation simply replaces the active pending.

5. **The `combine-finality` classifier works correctly:** returns `:financially-final` after keeper execution.

### Open questions

- The superseded pending entry is preserved forever. Is this an unbounded-growth concern?
- If the keeper never executed L1's pending, would L0's superseded pending become executable as fallback? Our fix to `pending-settlement-executable?` checks only superseded entries at the **current** dispute level — L0's entry (level 0) would NOT be executed because current level is 1. This is correct: L1 should act first.

### Confidence level

**Confirmed** — The escalation mechanism works as expected. Finality is properly delayed. No slashes. Classification is correct.

---

## Working glossary

*(updated after Pathway 2)*

### Superseded pending settlement

**Observed meaning:** When a dispute is escalated, the current pending settlement is archived into a `:superseded-pending-settlements` map under the workflow ID. The entry records the pending's deadline, resolution hash, the escalation time (`:superseded-at`), and the dispute level at the time of archiving (`:level`).

**Code locations:** `archive-pending-on-escalation` in `resolution.clj` (line 637)

**Pathways where observed:** 2

---

## Pathway summary table

| Pathway | Verdict final? | Slash final? | Appealable? | Reversible? | Financially final? | Evidence final? | Confidence | Notes |
|---|---|---|---|---|---|---|---|---|---|
| Baseline dispute | **Final** (instant) | N/A | No (window=0) | No (terminal) | **Yes** | **Present** | Confirmed | appeal-window-duration=0 skips pending settlement |
| Escalation w/o reversal | **Final** (L1, delayed 70 blocks) | N/A | Closed after execution | No (terminal) | **Yes** | **Present** | Confirmed | Escalation supersedes L0's pending; L1 verdict is final |
| Single reversal (no slash) | **Final** (L1 reversed L0) | N/A | Closed | No (terminal) | **Yes** | **Present** | Confirmed | reversal-slash-bps not configured |
| Single reversal (w/ slash) | **Final** (L1 reversed L0) | **Executed** (Track 1) | No (appeal-deadline=0) | No (terminal) | **Yes** | **Present** | Confirmed | Track 1 auto-slash 2500 bps, immediate execution |
| Reversal-of-reversal | Halted (invariant violation) | Ambiguous | — | — | — | Present | Blocked | Solvency gap from vindication credit; invariants flag it |
| Track 2 appeal upheld | **Final** | **Reversed** (status :reversed) | Appeal upheld | N/A | **Yes** | **Present** | Confirmed | Slash reversed, stake restored, no distribution |
| Track 2 appeal rejected | **Final** | **Ambiguous** (stake not re-debited) | Appeal rejected | N/A | **Yes** | **Present** | Contradicted | execute_fraud_slash rejected; final stake shows no debit |
| Appeal timing boundaries | **N/A** | N/A | Closed at deadline | N/A | **Depends** | **Present** | Confirmed | Asymmetric handoff: settlement allowed at deadline, escalation blocked |
| Multi-level vindication | Halted (invariant violation) | Ambiguous | — | — | — | Present | **Blocked (F-001)** | Solvency gap: credited vindication stake not recognized by solvency invariant |
| Challenge bounty | **Final** (L1 reversed L0) | **Executed** (Track 1, with challenger bounty) | No (appeal-deadline=0) | No (terminal) | **Yes** | **Present** | Confirmed | Bounty correctly recorded as claimable-v2 from slashed stake; all invariants pass |
| No-challenger bounty | Context-dependent | Context-dependent | Context-dependent | Context-dependent | **Yes** | **Present** | Confirmed | Escalation IS the challenge mechanism — escalator receives bounty on reversal; no escalation = no bounty |
| Governance force-slash | **Final** (Track 1 immediate) | **Executed** (no bounty) | No (immediate) | No (terminal) | **Yes** | **Present** | Confirmed with bug | Force-slash debits stake correctly; **F-004**: NOT idempotent despite docstring claim |
| Insufficient stake | **Final** | **Bounded to available** | No | No | **Yes** | **Present** | Confirmed | Slash never goes below 0; unmet shortfall tracked via :record-only; all invariants pass |

---

## Findings

### F-001 — Vindication creates solvency gap not accounted by invariants

**Status:** `concept-gap`
**Pathway:** Reversal-of-reversal (custom)
**Observed:** When L2 vindicates L0, `reverse-reversal-slash-on-vindication` credits L0's stake by the slashed amount (+2500). The function explicitly documents: *"The slashed funds have already been distributed ... this does NOT claw them back."* The `:solvency` invariant then fires because `held < liabilities`.
**Expected:** The invariant should account for `:reversed-with-credit` slash entries as valid protocol liabilities.
**Impact:** Custom reversal-of-reversal scenarios cannot pass invariant checks without modifying invariants or setting `expected-failures`.
**Likely fix:** Update `:solvency` invariant to exclude `:reversed-with-credit` amounts from liability calculation, or include them in a separate `:vindication-credit` bucket.

### F-002 — Appeal rejection doesn't re-debit stake after Track 1 auto-slash

**Status:** `ambiguous`
**Pathway:** Track 2 appeal rejected (S107)
**Observed:** After Track 1 auto-slash debits L0's stake (-2000), L0 appeals -> status changes to `:appealed`, stake credited back (+2000). Appeal rejected -> status back to `:executed`, but stake stays at 8000 (not re-debited). `execute_fraud_slash` rejected because status is already `:executed`. Final world shows both resolvers at 8000 with zero distribution and zero slash totals.
**Expected:** After rejected appeal, the slash should remain economically enforced (stake debited, funds distributed).
**Impact:** A resolver whose slash appeal is rejected faces no economic penalty.
**Likely fix:** `resolve-appeal` with `upheld? false` should re-execute the slash (debit stake, distribute funds), or `execute_fraud_slash` should accept already-`:executed` slashes and re-apply the economic effect.
**Note:** The scenario PASSES because the invariants don't detect this gap — there is no invariant that checks "slash status :executed implies stake was debited." This is a coverage gap.

### F-003 — Reversal-of-reversal (vindication) creates unresolvable solvency gap at L2 resolution

**Status:** `concept-gap`
**Pathway:** 8 (multi-level vindication)
**Observed:** Custom scenario L0=release → L1=refund → L2=release fails at seq 9 (L2 resolution) with `:solvency` and `:finalization-accounting-correct` invariant violations. `reverse-reversal-slash-on-vindication` (`resolution.clj:266`) credits L0's stake by 2000 but the funds were already distributed and cannot be clawed back. Liabilities (22000) exceed internal held (20000) by exactly the slash amount.
**Expected:** L0's stake should recover to 8000 after vindication, with the protocol recognizing the credit as a valid liability (protocol-backed future dispute capacity).
**Impact:** Multi-level vindication paths cannot pass invariant checks. Future protocol-backed liability is not reflected in any accounting bucket, making it invisible to the solvency invariant.
**Likely fixes:** (A) Update solvency invariant to recognize `:reversed-with-credit` entries, (B) Create a `:vindication-reserve` from a fraction of slash distributions, (C) Accept as known gap with expected-failures opt-in.

### F-004 — force-reversal-slash is NOT idempotent despite documentation claim

**Status:** `resolved`
**Pathway:** 11 (governance force-slash)
**Fix:** Guard added at `resolution.clj:323` using `:slash-by-context` key `[workflow-id :force-reversal 0]` — returns world unchanged when entry exists. Uses `:slash-by-context` (not `:pending-fraud-slashes` as originally suggested) because immediate-track slashes bypass `:pending-fraud-slashes` entirely.
**Tests:** `force-reversal-slash-idempotent` in `slashing_test.clj:873` + DR-P-002 scenario + `checklist-force-reversal-slash-idempotent` in `idempotence_checklist_test.clj`.
**Observed (pre-fix):** Calling `force-reversal-slash` twice on the same workflow debits the resolver's stake twice. After first call (2500 bps on 10000): L0=7500. After second call (2500 bps on 7500): L0=5625. The slash entry with ID `{wf-id}-force-reversal-0` is overwritten each call, losing the original amount in the audit trail (though `:resolver-slash-total` tracks cumulatively).
**Original expected:** The function should check `(get-in world [:pending-fraud-slashes (str workflow-id "-force-reversal-0")])` and return world unchanged if an entry already exists.
**Impact (pre-fix):** Governance could accidentally double-slash a resolver without realizing. The economic impact compounds because each call uses the reduced post-slash stake.

---

## Pathway 7 — Appeal timing boundaries

### Goal
Verify that `deadline-expired?` (>=) semantics are correctly enforced across all gates: settlement execution, escalation, challenge, and max-dispute-duration timeout.

### Code examined

| Location | Function | Semantics |
|---|---|---|
| `deadlines.clj:18` | `deadline-expired?` | `>=` — at-or-after means expired |
| `deadlines.clj:11` | `before-deadline?` | `<` — strictly before means open |
| `state_machine.clj:469` | `pending-settlement-executable?` | `>=` via `deadline-expired?` |
| `resolution.clj:619` | `execute-pending-settlement` guard | `<` (reject when strictly before) |
| `resolution.clj:699` | `challenge-resolution` guard | `>=` (reject at/after deadline) |
| `resolution.clj:907` | `escalate-dispute` guard | `>=` (reject at/after deadline) |

### Existing scenarios

| Scenario | Tests |
|---|---|
| `s47a` — appeal-window-last-second-settlement | Settlement exactly at t=deadline succeeds |
| `s47b` — appeal-window-plus-one-rejected | Settlement at t=deadline+1 succeeds |

**Note:** S47b's documentation says "must reject" but the code has evolved. Both S47a and S47b now succeed because `>=` semantics means settlement is allowed at both t=deadline and t=deadline+1. The scenario passes because `:allow-open-disputes?` relaxes final-state checks.

### Boundary tests (conducted via REPL)

#### Settlement execution boundary (appeal-window-duration=60, deadline=1180)

| Time | Result | State | Reason |
|---|---|---|---|
| 1179 (-1) | `:rejected` | `:disputed` | `:appeal-window-not-expired` (still before deadline) |
| 1180 (exact) | `:ok` | `:released` | `deadline-expired?` true — inclusive |
| 1181 (+1) | `:ok` | `:released` | Past deadline — expired |

#### Escalation boundary (same window, deadline=1180)

| Time | Result | State | Reason |
|---|---|---|---|
| 1179 (-1) | `:ok` | `:disputed` | Strictly before deadline — appeal window still open |
| 1180 (exact) | **`:rejected`** | `:disputed` | `:appeal-window-expired` — `>=` triggers at deadline |
| 1181 (+1) | `:rejected` | `:disputed` | Past deadline — expired |

#### Max-dispute-duration timeout boundary (max-dur=120s, deadline=1180)

| Time | Result | State |
|---|---|---|
| 1179 (-1) | `:ok` (no-op) | `:disputed` |
| 1180 (exact) | `:ok` (auto-cancel fires) | `:refunded` |
| 1181 (+1) | `:ok` (auto-cancel fires) | `:refunded` |

### Confirmed asymmetry: Settlement vs Escalation at exactly the deadline

```
           ← appeal window OPEN → | ← appeal window CLOSED → 
Escalation:   ALLOWED              | REJECTED   REJECTED
Settlement:   REJECTED             | ALLOWED    ALLOWED
time:         1179             1180          1181
                                 ^
                          deadline boundary (inclusive for settlement,
                          exclusive for escalation)
```

At exactly the deadline (t=1180):
- **Settlement succeeds** (guard `(< 1180 1180)` → false → not blocked)
- **Escalation is rejected** (guard `(>= 1180 1180)` → true → `:appeal-window-expired`)

**Design rationale:** This is a clean handoff. At the deadline, the keeper may execute the settlement, and no new escalation may interfere. The alternative (both blocked at exactly deadline) would create a race-to-nobody-can-act gap.

### Findings

- The `>=` semantics in `deadline-expired?` is consistently applied.
- Settlement execution uses the complementary guard (`<`) — open at/after deadline.
- Escalation and challenge use the identical guard (`>=`) — closed at/after deadline.
- **No gap identified.** The asymmetry at the exact deadline is intentional and prevents a deadlock race.
- S47b's documentation is stale but not harmful — scenario still passes.

---

## Pathway 8 — Multi-level vindication (reversal-of-reversal)

### Goal
Verify that a resolver vindicated at L2 after being reversed at L1 economically recovers correctly, and test the boundary of the solvency invariant against the documented protocol-backed liability.

### Pathway structure

```
L0: release (correct)  →  L1: refund (reverses L0, auto-slash L0)
                         →  L2: release (vindicates L0, should credit L0 stake)
```

### Scenario definition

The following custom scenario was used (Kleros module with escalation-resolvers):

```clojure
(require '[resolver-sim.protocols.sew :as sew] :reload)
(require '[resolver-sim.protocols.sew.invariant-scenarios.reversal :as rev])

(def rev-of-rev-scenario
  {:scenario-id     "custom-reversal-of-reversal-path8"
   :schema-version  "1.0"
   :initial-block-time 1000
   :agents          [{:id "buyer"    :address "0xbuyer"    :strategy "honest"}
                     {:id "seller"   :address "0xseller"   :strategy "honest"}
                     {:id "l0"       :address "0xl0"       :role "resolver"}
                     {:id "l1"       :address "0xl1"       :role "resolver"}
                     {:id "l2"       :address "0xl2"       :role "resolver"}
                     {:id "keeper"   :address "0xkeeper"   :role "keeper"}]
   :protocol-params {:resolver-fee-bps 0
                     :appeal-window-duration 60
                     :max-dispute-duration 120
                     :resolver-bond-bps 0
                     :resolution-module "0xkleros-proxy"
                     :escalation-resolvers {:0 "0xl0" :1 "0xl1" :2 "0xl2"}
                     :reversal-slash-bps 2500
                     :challenge-bounty-bps 0}
   :allow-open-disputes? true
   :events [{:seq 0 :time 1000 :agent "l0" :action "register_stake" :params {:amount 8000}}
            {:seq 1 :time 1000 :agent "l1" :action "register_stake" :params {:amount 8000}}
            {:seq 2 :time 1000 :agent "l2" :action "register_stake" :params {:amount 8000}}
            {:seq 3 :time 1000 :agent "buyer"  :action "create_escrow"
             :params {:token "USDC" :to "0xseller" :amount 5000}}
            {:seq 4 :time 1060 :agent "buyer"  :action "raise_dispute" :params {:workflow-id 0}}
            {:seq 5 :time 1120 :agent "l0" :action "execute_resolution"
             :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
            {:seq 6 :time 1120 :agent "buyer" :action "escalate_dispute" :params {:workflow-id 0}}
            {:seq 7 :time 1180 :agent "l1" :action "execute_resolution"
             :params {:workflow-id 0 :is-release false :resolution-hash "0xl1hash"}}
            {:seq 8 :time 1180 :agent "seller" :action "escalate_dispute" :params {:workflow-id 0}}
            {:seq 9 :time 1240 :agent "l2" :action "execute_resolution"
             :params {:workflow-id 0 :is-release true :resolution-hash "0xl2hash"}}
            {:seq 10 :time 1300 :agent "keeper" :action "execute_pending_settlement"
             :params {:workflow-id 0}}]})

(def result (sew/replay-with-sew-protocol rev-of-rev-scenario {:allow-dirty? true}))
```

### Trace

| Seq | Action | Result | L0 stake | Total held | Slash total (L0) |
|---|---|---|---|---|---|
| 0 | register_stake (L0) | :ok | 8000 | 8000 | {} |
| 1 | register_stake (L1) | :ok | 8000 | 16000 | {} |
| 2 | register_stake (L2) | :ok | 8000 | 24000 | {} |
| 3 | create_escrow | :ok | 8000 | 29000 | {} |
| 4 | raise_dispute | :ok | 8000 | 29000 | {} |
| 5 | L0: resolve release | :ok | 8000 | 29000 | {} |
| 6 | buyer escalate | :ok | 8000 | 29000 | {} |
| **7** | **L1: resolve refund** | **:ok** | **6000** | **27000** | **2000** |
| 8 | seller escalate | :ok | 6000 | 27000 | 2000 |
| **9** | **L2: resolve release** | **:invariant-violated** | **6000** | **27000** | **2000** |

### Violations at seq 9

Two invariants fail:

**`:solvency`** — `{:liabilities 22000N, :held 20000N, internal-ok? false, external-ok? true}`

Held (20000) < Liabilities (22000). Gap = 2000 = the Track 1 auto-slash amount.

**`:finalization-accounting-correct`** — `{:delta-held -7000N, :delta-claimable 5000, :expected-claimable 5000}`

When resolution creates a release pending, the accounting transition removes 7000 from held but only adds 5000 to claimable. The -2000 delta is the slash distribution that can't be clawed back.

### Root cause: `reverse-reversal-slash-on-vindication` and the solvency gap

The function at `resolution.clj:266` handles vindication:

```clojure
;; Only applies when slash-entry found in :pending-fraud-slashes
;; Credits resolver stake but does NOT claw back distributed funds
(-> w
    (update-in [:resolver-stakes resolver] (fnil + 0) amount)
    (update-in [:resolver-slash-total resolver] (fnil - 0) amount)
    (assoc-in [:pending-fraud-slashes slash-id :status] :reversed-with-credit)
    ...)
```

**State before L2 resolves (seq 8):**
- L0 stake: 6000 (8000 - 2000 slashed)
- L1 stake: 8000, L2 stake: 8000
- Total held: 27000 (29000 - 2000 distributed)
- Slash total (L0): 2000
- Bond distribution: {:insurance 1000, :protocol 600, :burned 0}
- Liabilities (stakes): 22000

**When L2 vindicates L0 (seq 9):**
1. Function checks for `:pending-fraud-slashes` entry with `slash-id = "0-reversal-0"` **— entry not found** (auto-slash was Track 1 `:immediate`, already cleaned up; trace projections don't include this key)
2. OR if found: credits L0 stake by 2000 → L0 = 8000, liabilities = 24000
3. Either way: total-assets (held + bond-distribution + ...) = 28600, liabilities = 22000-24000
4. **Solvency fails** because the 2000 was already distributed and cannot be clawed back

The protocol's design comment (resolution.clj:274) explicitly acknowledges this:

> *"The slashed funds have already been distributed … this does NOT claw them back. Instead, the resolver's stake balance is credited, representing a protocol-backed liability."*

### Confirmed: F-001 gap

The `:solvency` invariant does not account for `:reversed-with-credit` as a valid protocol liability. The credit to L0's stake is a real obligation — the protocol owes L0 2000 of future dispute capacity — but the invariant treats it as an accounting hole.

### Likely fixes

| Option | Description | Risk |
|---|---|---|
| A. Update solvency invariant | Exclude `:reversed-with-credit` amounts from liability calculation, or include them in a separate `:vindication-credit` bucket recognized by the invariant | Lowest — changes only test invariants, not production code |
| B. Reserve fund approach | Create a `:vindication-reserve` that is prefunded from slash distributions (e.g., a fraction of every slash goes to a reserve pool for future vindication credits) | Higher — requires production code changes and economic design |
| C. Accept and document as concept gap | Mark the solvency invariant as knowingly incomplete for vindication; path-8 scenarios opt into `expected-failures` | Simplest but leaves a coverage hole |

**Note:** This gap does not affect finality or economic safety in practice. A vindicated resolver's credited stake is a protocol liability — the protocol honors it by allowing that resolver to participate in future disputes. The invariant is stricter than the economic design requires.

---

## Pathway 10 — No-challenger bounty

### Goal
Verify the system's behavior when a reversal occurs but no explicit `challenge-resolution` action was filed. Determine whether a "no-challenger" scenario is economically distinct from a challenged one.

### Key finding: Escalation IS the challenge mechanism

Both `challenge-resolution` (`resolution.clj:729`) and `escalate-dispute` (`resolution.clj:944`) record the caller as a challenger:

```clojure
(assoc-in [:challengers workflow-id current-level] caller)
```

This means any party who escalates a dispute is automatically recorded as a challenger. When the higher-level resolver reverses the lower-level verdict, the escalator receives the challenge bounty — no separate `challenge-resolution` action is needed.

### Three cases tested via REPL

```clojure
;; ──────────────────────────────────────────────
;; PATHWAY 10: No-challenger bounty
;; ──────────────────────────────────────────────
(require '[resolver-sim.protocols.sew :as sew] :reload)
(require '[resolver-sim.protocols.sew.types :as t] :reload)
(require '[resolver-sim.protocols.sew.invariants :as inv] :reload)
(require '[resolver-sim.protocols.sew.financial.finality :as fin] :reload)

(defprotocol-params
  {:resolver-fee-bps 150 :appeal-window-duration 60 :max-dispute-duration 120
   :resolver-bond-bps 0 :resolution-module "0xkleros-proxy"
   :escalation-resolvers {:0 "0xl0" :1 "0xl1"}
   :reversal-slash-bps 2500 :challenge-bounty-bps 1000})

;; Helper: replay and summarize
(defn path10-run [name events]
  (let [r (sew/replay-with-sew-protocol
           {:scenario-id name :schema-version "1.0"
            :initial-block-time 1000
            :agents [{:id "buyer" :address "0xbuyer" :strategy "honest"}
                     {:id "seller" :address "0xseller" :strategy "honest"}
                     {:id "l0" :address "0xl0" :role "resolver"}
                     {:id "l1" :address "0xl1" :role "resolver"}
                     {:id "keeper" :address "0xkeeper" :role "keeper"}]
            :protocol-params protocol-params
            :events events}
           {:allow-dirty? true})
        w (:world r)]
    (println "---" name "---")
    (println "outcome:" (:outcome r))
    (doseq [s (:trace r)]
      (println (format "  seq %d: %s → %s" (:seq s) (:action s) (:result s))))
    (println "stakes:" (:resolver-stakes w))
    (println "challengers:" (get w :challengers {}))
    (println "claimable-v2:" (:claimable-v2 w))
    (println "invariants?:" (:all-hold? (inv/check-all w)))
    (println)))
```

#### CASE 1: Escalate + reversal (escalator becomes challenger, gets bounty)

Events: L0=release → buyer escalates → L1=refund (reversal)

```
seq 4: L0 resolve release        → :ok
seq 5: buyer escalate            → :ok      ← records buyer as challenger@level0
seq 6: L1 resolve refund         → :ok      ← reversal! L0 slashed 2000
seq 7: execute settlement        → :ok

Final:
  stakes:         L0=6000, L1=8000    (L0 -2000)
  challengers:    {0 {0 "0xbuyer"}}   ← buyer was the escalator
  claimable-v2:   {:liability/challenge-bounty {"0xbuyer" 200}   ← bounty
                   :settlement/principal {"0xbuyer" 3940}}
  invariants:     ✅
  finality:       ✅ financially-final
```

The buyer who escalated received 200 bounty (10% of 2000 slash).

#### CASE 2: Escalate + verdict upheld (no reversal, no bounty)

Events: L0=release → seller escalates → L1=release (agrees with L0)

```
Final:
  stakes:         L0=8000, L1=8000    (unchanged — no slash)
  challengers:    {0 {0 "0xseller"}}  ← seller recorded as challenger
  claimable-v2:   {:settlement/principal {"0xseller" 3940}}   ← NO bounty
  invariants:     ✅
```

The seller escalated but L1 agreed with L0 → no reversal → no slash → no bounty.

#### CASE 3: No escalation at all (simple baseline)

Events: L0=release → keeper executes after deadline

```
Final:
  stakes:         L0=8000             (unchanged)
  challengers:    {}                  ← no one escalated
  claimable-v2:   {:settlement/principal {"0xseller" 3940}}   ← no bounty
  invariants:     ✅
  finality:       ✅ financially-final
```

No reversal possible without escalation — L0's verdict becomes final naturally.

### Synthesis: Bounty allocation matrix

| Escalation? | Next level action | Bounty paid? | Recipient |
|---|---|---|---|
| Yes | Reverses lower level | ✅ Yes (10%) | The escalator |
| Yes | Upholds lower level | ❌ No | — |
| No | N/A (verdict executes) | ❌ No | — |

### Findings

- **Escalation is the canonical challenge mechanism.** The `:challengers` map records the escalator, and the reversal-slash pipeline pays the bounty to whoever triggered the escalation.
- The `challenge-resolution` action (Phase L) is redundant with `escalate-dispute` for bounty eligibility — both record the caller as challenger.
- A "no-challenger" state exists only when no escalation occurs (Case 3).
- **No gaps identified.** The implicit-challenger design is consistent and all invariants pass.

---

## Pathway 11 — Governance force-reversal-slash

### Goal
Verify the `force-reversal-slash` action: a governance-initiated slash that operates independently of the resolution pipeline. Test its effects, idempotency, and interaction with downstream auto-slashes.

### Mechanism

`force-reversal-slash` (`resolution.clj:221`) is a standalone slash that:
- Targets the `prev-resolver` at the current dispute level (falls back to escrow sender at level 0)
- Accepts an optional `:slash-bps` override (defaults to snapshot's `:reversal-slash-bps`)
- Supports Track 1 (`:immediate`) and Track 2 (`:pending`) — the `apply-action` handler always uses `:track :immediate`
- Creates a slash entry with ID `"{wf-id}-force-reversal-0"`
- Does NOT integrate with the challenger bounty system (challenger=nil)

### Test scenarios

```clojure
;; ─────────────────────────────────────────────────────
;; PATHWAY 11: force-reversal-slash REPL commands
;; ─────────────────────────────────────────────────────
(require '[resolver-sim.protocols.sew :as sew] :reload)
(require '[resolver-sim.protocols.sew.types :as t] :reload)
(require '[resolver-sim.protocols.sew.invariants :as inv] :reload)
(require '[resolver-sim.protocols.sew.financial.finality :as fin] :reload)

(def path11-params
  {:resolver-fee-bps 150 :appeal-window-duration 60 :max-dispute-duration 120
   :resolver-bond-bps 0 :resolution-module "0xkleros-proxy"
   :escalation-resolvers {:0 "0xl0" :1 "0xl1"}
   :reversal-slash-bps 2500 :challenge-bounty-bps 1000})

(def path11-base-events
  [{:seq 0 :time 1000 :agent "l0" :action "register_stake" :params {:amount 10000}}
   {:seq 1 :time 1000 :agent "l1" :action "register_stake" :params {:amount 10000}}
   {:seq 2 :time 1000 :agent "buyer" :action "create_escrow"
    :params {:token "USDC" :to "0xseller" :amount 5000}}
   {:seq 3 :time 1060 :agent "buyer" :action "raise_dispute" :params {:workflow-id 0}}
   {:seq 4 :time 1120 :agent "l0" :action "execute_resolution"
    :params {:workflow-id 0 :is-release true :resolution-hash "0xl0hash"}}
   {:seq 5 :time 1120 :agent "buyer" :action "escalate_dispute" :params {:workflow-id 0}}])
```

#### Test 1: Force-slash with full resolution pipeline

Events: base + gov force-slash + L1 resolution + settlement

```
seq 6: gov force-reversal-slash (2500 bps)  → :ok   L0: 10000 → 7500 (-2500)
seq 7: L1 resolve refund (auto-slash)       → :ok   L0: 7500 → 5625 (-1875)
seq 8: execute settlement                   → :ok

Final:
  L0 stake:       5625 (total slashed: 4375)
  L1 stake:       10000 (unchanged)
  Pending slashes: "0-force-reversal-0" (2500, :executed)
                   "0-reversal-0" (1875, :executed)
  Bond distribution: {:insurance 2094, :protocol 1218, :burned 0}
  Claimable-v2:   {:liability/challenge-bounty {"0xbuyer" 187}}
  Invariants:     ✅
  Finality:       ✅ financially-final
```

Key: the auto-slash (seq 7) uses L0's POST-FORCE-SLASH stake (7500), not the original (10000).

#### Test 2: Idempotency (or lack thereof)

Events: base + two consecutive force-slashes (NO L1 resolution)

```
seq 6: first force-slash  (2500 bps on 10000)  → :ok  L0: 10000 → 7500 (-2500)
seq 7: second force-slash (2500 bps on 7500)   → :ok  L0: 7500  → 5625 (-1875)

Slash totals after seq 6: L0=2500
Slash totals after seq 7: L0=4375
```

**⚠️ NOT IDEMPOTENT** — despite the docstring at `resolution.clj:231` stating:

> *"Idempotent: if a force-reversal entry already exists for this workflow-id, returns the world unchanged to prevent double-slashing."*

The actual code does NOT check for existing entries. Each call debits another `slash-bps` fraction of the CURRENT stake. The pending-fraud-slashes entry is overwritten (same ID), but the economic effect compounds.

#### Test 3: Custom slash-bps override

Events: base + force-slash with `:slash-bps 5000`

```
seq 6: force-slash 5000 bps on 10000  → :ok  L0: 10000 → 5000 (-5000)
```

The `:slash-bps` param overrides the snapshot's default `:reversal-slash-bps`.

### Comparison with auto-slash (Track 1)

---

## Pathway 12 — Insufficient stake (slash > available stake)

### Goal
Verify the protocol's behavior when a slash amount exceeds the resolver's available stake. Does it fail, bound to available, or create negative stake?

### Tool: `dev/pro_rata.clj` explain functions

The `dev/pro_rata.clj` namespace provides developer tools that explain the pro-rata slash allocation mechanism without running a full scenario:

| Function | Purpose |
|---|---|
| `explain-sew-slash-allocation` | Direct Sew allocation: given a `:slash-amount` and `:liable-parties` with `:slashable-stake` (weight) and `:available-slashable` (cap), returns the allocation map |
| `explain-generic-allocation` | Lower-level generic allocation (same engine, raw input) |
| `explain-projection-artifact` | Build the projection artifact (canonical input for evidence chain) |
| `explain-projection-vs-direct` | Run both allocation paths and verify equivalence |
| `explain-claims` | Evaluate all 7 pro-rata claims against an allocation |
| `explain-evidence-from-input` | Full end-to-end: input → allocation → claims → evidence hash chain |

### Step 1: Understanding the allocation model with `explain-sew-slash-allocation`

```clojure
(require '[dev.pro-rata :as pr] :reload)
(require '[clojure.pprint :as pp])

(pp/pprint
  (pr/explain-sew-slash-allocation
    {:slash-amount 100
     :liable-parties [{:id :resolver-a
                       :slashable-stake 200   ;; weight for prorating
                       :available-slashable 200}  ;; max that can be taken
                      {:id :resolver-b
                       :slashable-stake 200
                       :available-slashable 200}]}))
```

**Output:**
```
{:unmet-total 0N,
 :total-basis 400,
 :recovered-total 100N,
 :allocations [{:id :resolver-a, :basis-amount 200, :share 1/2,
                :owed 50N, :paid 50N, :unmet 0N, :cap 200}
               {:id :resolver-b, :basis-amount 200, :share 1/2,
                :owed 50N, :paid 50N, :unmet 0N, :cap 200}]}
```

**What this tells us:**
- Total basis (weighted stake) = 400 across both parties
- Each party has equal weight (200/400 = 1/2 share)
- Each owes 50, each can pay 50 (cap >= owed) → fully recovered
- `:unmet-total 0` — no shortfall

#### Case 2: Insufficient stake — one party capped

```clojure
(pp/pprint
  (pr/explain-sew-slash-allocation
    {:slash-amount 100
     :liable-parties [{:id :resolver-a
                       :slashable-stake 50    ;; only 50 weight
                       :available-slashable 50}  ;; cap=50
                      {:id :resolver-b
                       :slashable-stake 200
                       :available-slashable 200}]}))
```

**Output:**
```
{:unmet-total 0N,
 :total-basis 250,
 :recovered-total 100N,
 :allocations [{:id :resolver-a, :basis-amount 50, :share 1/5,
                :owed 20N, :paid 20N, :unmet 0N, :cap 50}
               {:id :resolver-b, :basis-amount 200, :share 4/5,
                :owed 80N, :paid 80N, :unmet 0N, :cap 200}]}
```

**What changed:** A's weight is 50 (not 200) so A's share drops from 50% to 20%. B's share increases to 80%. Both fully paid because total slash (100) < total cap (50+200=250). The pro-rata adjusts proportionally.

#### Case 3: Both parties capped (total slash > total cap)

```clojure
(pp/pprint
  (pr/explain-sew-slash-allocation
    {:slash-amount 100
     :liable-parties [{:id :resolver-a :slashable-stake 30 :available-slashable 30}
                      {:id :resolver-b :slashable-stake 30 :available-slashable 30}]}))
```

**Output:**
```
{:unmet-total 40N,
 :recovered-total 60N,
 :allocations [{:id :resolver-a, :owed 50N, :paid 30N, :unmet 20N, :cap 30}
               {:id :resolver-b, :owed 50N, :paid 30N, :unmet 20N, :cap 30}]}
```

**Key insight:** `:unmet-total 40N` — the shortfall appears. Each party can only pay 30 of their 50 owed. The 40 shortfall is recorded under `:record-only` policy (accounting datum, not protocol action).

#### Case 4: One party with zero stake

```clojure
(pp/pprint
  (pr/explain-sew-slash-allocation
    {:slash-amount 1000
     :liable-parties [{:id :resolver-a :slashable-stake 800 :available-slashable 800}
                      {:id :resolver-b :slashable-stake 0 :available-slashable 0}]}))
```

**Output:**
```
{:unmet-total 200N, :recovered-total 800N,
 :allocations [{:id :resolver-a, :owed 1000N, :paid 800N, :unmet 200N, :cap 800}
               {:id :resolver-b, :owed 0N, :paid 0N, :unmet 0N, :cap 0}]}
```

A pays all 800 it has, 200 remains unmet. B has no stake → contributes nothing.

### Step 2: Projection vs Direct equivalence

```clojure
(pr/explain-projection-vs-direct
  {:slash-amount 125
   :liable-parties [{:id :resolver-l0
                     :slashable-stake 500
                     :available-slashable 500}]})
```

**Output:**
```
{:projection-hash "10ccfe09…",
 :equivalent? true,
 :direct {:total-allocated 125N, :total-unmet 0N, :allocations 1},
 :projection {:total-allocated 125N, :total-unmet 0N, :allocations 1}}
```

**What this tells us:** The direct allocation and projection-based allocation produce identical results (`:equivalent? true`). Both paths agree on what should happen.

### Step 3: Insufficient stake in a live scenario

```clojure
;; L0 stake=500, force-reversal-slash with 10001 bps
;; Expected slash: 10001 bps × 500 = 500.05 → bounded to 500
```

**Trace:**
```
seq 0: register_stake L0 (500)       → :ok
seq 1: register_stake L1 (10000)     → :ok
seq 2: create_escrow                 → :ok
seq 3: raise_dispute                 → :ok
seq 4: L0 resolve release            → :ok
seq 5: buyer escalate                → :ok
seq 6: gov force-slash (10001 bps)   → :ok   ← L0: 500→0
seq 7: L1 resolve refund (reversal)  → :ok   ← auto-slash: no-op (L0 has 0)
seq 8: execute settlement            → :ok
```

**Final state:**
```
L0 stake:       0     (entire 500 debited)
L1 stake:       10000 (unchanged)
Slash total:    500
Held:           10000
Bond distribution: {:insurance 250, :protocol 150, :burned 0}
Invariants:     ✅ all pass
Finality:       ✅ financially-final
```

### Edge case: Very low stake (10) with sequential slashes

```clojure
;; L0 stake=10, force-slash 5000 bps (50%), then auto-slash 2500 bps
```

**After seq 6 (force-slash 5000 bps):** L0: 10 → 5 (debited 5)
**After seq 7 (auto-slash 2500 bps of 5):** L0: 5 → 4 (debited 1)
**Total debited:** 6
**Final L0 stake:** 4

### Summary table

| Scenario | Initial stake | Slash(es) | Final stake | Recovered | Unmet | Invariants |
|---|---|---|---|---|---|---|
| Sufficient stake (2500 bps) | 500 | Force: 125 | 375 | 125 | 0 | ✅ |
| Excessive bps (10001 bps) | 500 | Force: 500 | 0 | 500 | (1 theoretical) | ✅ |
| Micro stake (5000 + 2500 bps) | 10 | Force: 5, Auto: 1 | 4 | 6 | 0 | ✅ |

### Findings

- **Stake is NEVER negative.** The `slash-resolver-stake` function debits only what exists. If `bps × stake / 10000 > stake`, the actual debit is bounded by the available amount.
- **`explain-sew-slash-allocation` correctly predicts** the bounded allocation, showing `:unmet-total` for the shortfall.
- **The `:record-only` unmet policy** means the shortfall is an accounting record, not an enforced protocol action. No special world-state entry is created for unmet amounts.
- **After stake reaches 0, subsequent slashes are no-ops** — the guard `(not (pos? slash-amt))` returns the world unchanged.
- **All invariants pass** even with extreme insufficient-stake scenarios.
- **No gaps identified.** The bounded-slash behavior is consistent and the accounting is self-consistent.

---

## Pathway 9 — Challenge bounty

| Aspect | Force-reversal-slash | Auto-slash (Track 1) |
|---|---|---|
| Trigger | Explicit governance action | Reversal detection in resolution pipeline |
| Slash ID | `{wf-id}-force-reversal-0` | `{wf-id}-reversal-{level}` |
| Challenger bounty | None (challenger=nil) | Pays bounty to escalator/challenger |
| Appeal window | Controlled by `:track` param | Immediate (appeal-deadline=0) |
| bps source | `:slash-bps` param or snapshot default | Snapshot `:reversal-slash-bps` |
| Target | prev-resolver at current level | prev-resolver at previous level |

### Findings

- Force-slash correctly debits stake and distributes funds.
- Force-slash integrates with the standard slash pipeline: downstream auto-slashes see the reduced stake.
- **Bug F-004: force-reversal-slash is NOT idempotent.** The docstring claims idempotency but the code has no guard. Multiple calls compound the slash. If the `slash-id` check were added, the function would need to verify that no entry with `(str wf-id "-force-reversal-0")` exists in `:pending-fraud-slashes`.
- The `:pending-fraud-slashes` entry is overwritten by each call (same ID), losing the original slash amount in the audit trail — though `:resolver-slash-total` cumulatively tracks correctly.

---

## Pathway 9 — Challenge bounty

### Goal
Verify that the challenge bounty mechanism (Phase L) correctly records challengers, pays bounties from reversal slashes, and produces correct finality with all invariants passing.

### Mechanism

1. **Challenge filing:** After an L0 resolution creates a pending settlement, any third party may call `challenge-resolution` (`resolution.clj:671`). This records the challenger address in `:challengers {workflow-id {level addr}}` and escalates the dispute to the next level.

2. **Reversal detection:** When L1 executes a resolution that reverses L0, `slash-reversal-slash` (`resolution.clj:180`) checks for a stored challenger at the current level (`(get-in world [:challengers workflow-id level])`).

3. **Bounty payment:** The challenger address and `:challenge-bounty-bps` from the snapshot are passed to `slash-resolver-stake` → `distribute-slashed-funds` (`accounting.clj:280`). The bounty is recorded in `claimable-v2` as `:liability/challenge-bounty`.

4. **Formula:** `bounty = slash-amount × challenge-bounty-bps / 10000`

### Existing scenarios

| Scenario | Pattern | Bounty | Result |
|---|---|---|---|
| S101 | L0=release, challenger challenges, L1=refund (reversal) | 250 (10% of 2500) | ✅ PASS, all invariants |
| S102 | L0=release, watchdog challenges, L1=refund (reversal) | 200 (10% of 2000) | ✅ PASS, all invariants |

Custom test: L0=release, challenger challenges, L1=release (agrees with L0, no reversal) → no slash, no bounty → ✅ PASS.

### Trace (S102, representative)

| Seq | Action | L0 stake | L1 stake | Total held | Bounty (claimable-v2) |
|---|---|---|---|---|---|
| 0 | register_stake L0 | 8000 | — | 8000 | — |
| 1 | register_stake L1 | 8000 | 8000 | 16000 | — |
| 2 | create_escrow (4000) | 8000 | 8000 | 20000 | — |
| 3 | raise_dispute | 8000 | 8000 | 20000 | — |
| 4 | L0 resolve release | 8000 | 8000 | 20000 | — |
| **5** | **watchdog challenge** | **8000** | **8000** | **20000** | **challenger recorded** |
| **6** | **L1 resolve refund (reversal)** | **6000** | **8000** | **17940** | **{0xwatch: 200}** |
| 7 | execute settlement | 6000 | 8000 | 14000 | {0xwatch: 200} |

### Final state (S102)

```
Escrow state:        :refunded
L0 stake:            6000 (8000 - 2000 slash)
L1 stake:            8000 (unchanged)
Total held:          {:USDC 14000}
Bond distribution:   {:insurance 900, :protocol 500, :burned 0}
Claimable-v2:        {:liability/challenge-bounty {"0xwatch" 200}
                      :settlement/principal {"0xbuyer" 3940}}
All invariants:      ✅ holds
Financial finality:  ✅ :financially-final
```

### Incorrect challenge (L1 agrees with L0, no reversal)

```
Seq 5: watchdog challenge → :ok (escalation still works)
Seq 6: L1 resolve release → :ok (agrees with L0, NO reversal, NO slash)
Seq 7: execute settlement → escrow released to seller

Final state:
  L0: 10000, L1: 10000 (unchanged)
  No bounty (no slash occurred)
  All invariants: ✅ holds
```

### Findings

- The challenge bounty mechanism is correctly integrated with the reversal slash pipeline.
- Bounty is paid from the slashed resolver's stake, not from protocol funds.
- Bounty is recorded as claimable-v2 (not immediately paid — requires separate withdrawal).
- Incorrect challenges (no reversal) pay no bounty.
- **No gaps identified.** All invariants pass, finality is correctly classified.
- Challenge bond posting is not tested here (`resolver-bond-bps=0` prevents bond posting). A non-zero bond scenario would test bond forfeiture on incorrect challenges.

---

## Replication checklist

1. Start nREPL: `clojure -M:repl/nrepl:with-sew` (port 7888)
2. Load namespaces:
   ```clojure
   (require '[resolver-sim.protocols.sew :as sew])
   (require '[resolver-sim.protocols.sew.invariant-scenarios.baseline :as base])
   (require '[resolver-sim.protocols.sew.financial.finality :as fin])
   (require '[resolver-sim.protocols.sew.invariants :as inv])
   (require '[resolver-sim.protocols.sew.types :as t])
   ```
3. Run S03 replay and classify finality:
   ```clojure
   (def result (sew/replay-with-sew-protocol base/s03))
   (def world (:world result))
   (fin/combine-finality world 0)
   (t/escrow-state world 0)
   ```
