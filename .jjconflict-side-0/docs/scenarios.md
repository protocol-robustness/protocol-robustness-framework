# Scenario Index

## Evidence Classification

> Merged from `docs/simulation-checklist.md`.

Scenarios mature along three dimensions:

| Dimension | Scope | Values |
| :--- | :--- | :--- |
| **Execution backing** | How was the trace generated? | `pinned-derivation`, `simulator-backed-single-path`, `simulator-backed-parameter-sweep` |
| **Model depth** | Adversarial coverage | `single-path`, `branch-covered`, `counterfactual`, `adversarial-sweep` |
| **Claim confidence** | Strength of evidence | `provisional`, `medium`, `high` |

**Promotion criteria:**
1. A scenario moves from `pinned-derivation` to `simulator-backed` only when the simulator executes it and CI verifies the trace hash.
2. `high` confidence requires sufficient branch coverage, adversarial variants (including negative "should-fail" cases), and sensitivity analysis.
3. For every passing scenario, implement at least one "should-fail/reject" variant.

**Required meta-scenarios:**
- `reference-suite-integrity-v1` — verifies metadata consistency (trace hashes, artifact sources, confidence levels).
- `contract-sim-parity-v1` — validates simulator/Solidity parity on core state transitions.
- `economic-assumption-sensitivity-v1` — stress-tests complex assumptions across pessimistic parameter ranges.

---



## Scenario naming and suite structure

This repository maintains two scenario suites:

| Suite | Prefix | Count | Runtime | Purpose |
|-------|--------|-------|---------|---------|
| **Deterministic invariant suite** | `S` | S01–S41 (41 scenarios) | ~1 s in-process, no server | Protocol correctness, state machine validation, adversarial rejection, edge cases |


**S-prefix** — all in-process deterministic scenarios (S01–S67 in the full suite; S01–S41 in the baseline invariant run). Each has fixed inputs, a known expected outcome, and invariants checked at every step.



**Running the suites:**
```bash
# Deterministic in-process suite (S01–S41)
clojure -M:run -- --invariants



# Focused adversarial search or scenario replay
bb run:scenario:search <text>
```

---

## S01–S23: Protocol Correctness

These scenarios verify that the protocol behaves correctly under normal and edge-case conditions.

> The table below is generated from `src/resolver_sim/protocols/sew/invariant_scenarios/doc_summaries.clj`.
> Regenerate with `bb docs:scenarios` after editing summaries or scenario ids.

| ID | Name | What it tests |
|----|------|--------------|
<!-- GENERATED-S01-S23-START -->
| S01 | baseline-happy-path | Create escrow → release; no disputes |
| S02 | dr3-dispute-release | Dispute opened; resolver releases to seller |
| S03 | dr3-dispute-refund | Dispute opened; resolver refunds buyer |
| S04 | dispute-timeout-autocancel | Dispute expires without resolution; auto-cancel |
| S05 | pending-settlement-execute | Pending settlement window; honest execution after deadline |
| S06 | mutual-cancel | Both parties agree to cancel |
| S07 | unauthorized-resolver-rejected | Non-authorized resolver call is rejected |
| S08 | state-machine-attack-gauntlet | Invalid state transitions attempted; all correctly rejected |
| S09 | multi-escrow-solvency | Multiple concurrent escrows; solvency maintained |
| S10 | double-finalize-rejected | Attempt to finalize an already-finalized escrow is rejected |
| S11 | zero-fee-edge-case | Escrow with fee_bps=0; correct handling |
| S12 | governance-snapshot-isolation (s12a+s12b) | Fee-param change after escrow A created does not apply retroactively to A / Escrow B created after fee change uses new params; A unchanged |
| S13 | pending-settlement-refund | Pending settlement resolved as refund |
| S14 | dr3-module-authorized | DR3 module resolves with correct authority |
| S15 | dr3-module-unauthorized-rejected | DR3 module with wrong authority is rejected |
| S16 | ieo-create-release | IEO escrow: create and release without dispute |
| S17 | ieo-dispute-no-resolver-timeout | IEO dispute with no resolver; timeout resolution |
| S18 | dr3-kleros-l0-resolves | Kleros L0 resolver resolves dispute at level 0 (zero appeal window) |
| S19 | dr3-kleros-escalation-rejected-l0-resolves | Preemptive escalation rejected (no pending settlement); L0 resolves; L1 blocked on terminal escrow |
| S20 | dr3-kleros-max-escalation-guard | Repeated preemptive escalations rejected; wrong-tier resolver rejected; dispute may stay open |
| S21 | dr3-kleros-pending-cleared-on-escalation | L0 resolves to pending; buyer escalates (clears pending); L1 resolves; keeper executes settlement |
| S22 | status-leak-agree-cancel-over-dispute | Regression: agree-to-cancel status cleared when dispute is raised |
| S23 | preemptive-escalation-blocked | Seller preemptive escalation rejected; L0 resolves; post-terminal escalation rejected |

<!-- GENERATED-S01-S23-END -->
| S76 | sponsored-appeal-third-party-funding | Third-party sponsor challenges a pending decision and funds escalation via challenge bond |

### Appeal sponsorship policy (current model)

- Bond payer is the **caller** of `escalate_dispute` / `challenge_resolution`.
- `escalate_dispute` remains participant-only (`from`/`to` required).
- `challenge_resolution` is open-challenger and can be called by non-participants.
- The sponsor address is recorded as the bond poster of record in `:bond-balances`.
- In practice, third-party sponsorship currently flows through `challenge_resolution`.

### Same-block ordering contract (S51 family)

`s51-same-block-challenge-finalize-race`, `s51-inverse-same-block-escalate-then-finalize`, `s51c-deadline-matrix-execute-then-escalate`, and `s51d-deadline-matrix-escalate-then-execute` define the deterministic same-block ordering rules at the appeal-deadline boundary. These are fixture-based scenarios (defined in `protocols_src/.../extended.clj`, no standalone JSON).

- Same timestamp events are ordered by scenario `:seq` (engine preserves list order for equal `:time`).
- Escalation/challenge eligibility is strict before deadline only (`now < deadline`).
- Pending execution eligibility is inclusive at deadline (`now >= deadline`).
- At `deadline-1`:
  - `execute -> escalate` yields `execute` reject then `escalate` accept.
  - `escalate -> execute` yields `escalate` accept then `execute` reject (pending cleared).
- At `deadline` and `deadline+1`:
  - `execute -> escalate` yields `execute` accept then `escalate` reject (terminal state).
  - `escalate -> execute` yields `escalate` reject (`appeal-window-expired`) then `execute` accept.

These scenarios are regression guards for deadline interpretation, same-block determinism, and pending-settlement replacement behavior.

---

## S24–S35: Ethereum Failure Modes (F1–F12)

These scenarios model attack strategies that have been observed or theorised in deployed Ethereum protocols. Each encodes a concrete failure that would cause fund loss or liveness failure.

---

### F1 — Liveness Extraction (`S24`)

**Attack:** Flood the resolver with 6 simultaneous disputes against a resolver throttled to 2 resolutions per block. Remaining disputes expire before the backlog clears.

**Failure:** Disputes expire without resolution — funds permanently locked. No invariant violations (throttling is not a protocol bug; it is a capacity misconfiguration).

**Why audits miss it:** Static analysis sees a valid `resolve()` path. The failure only emerges under load — time-bounded capacity exhaustion across concurrent escrows.

---

### F2 — Appeal Window Race (`S25`)

**Attack:** Attacker with block-ordering advantage escalates a pending settlement the moment it appears, preventing honest execution and resetting the resolution clock.

**Failure:** Pending settlement cleared before honest execution; finality delayed by one full escalation cycle. Attack fires ≥1 time.

**Why audits miss it:** The escalation call is individually valid. The vulnerability only manifests when modelling agent ordering within a tick.

---

### F3 — Governance Sandwich (`S26`)

**Attack:** Governance actor rotates the authorized resolver after a dispute opens but before it resolves. Malicious replacement resolver resolves in attacker's favour.

**Failure:** In-flight dispute outcome altered by a governance action the original parties could not anticipate.

**Why audits miss it:** Each step (rotation, resolution) is individually authorized. The attack is an emergent property of two valid state transitions.

---

### F4 — Escalation Loop Amplification (`S27`)

**Attack:** Attacker escalates immediately after each resolver submission, forcing re-submission at every escalation level.

**Failure:** Resolver gas costs multiplied by escalation depth (measured: 14.00× amplification). Without escalation bonds, this is a zero-cost griefing vector.

**Why audits miss it:** Post-fix, every individual escalation call is valid. Economic damage is only visible when the full sequence is simulated.

---

### F5 — Concurrent Status Desync (`S28`)

**Attack:** Two concurrent operations — `sender_cancel` and `raise_dispute` — are submitted in both possible orderings.

**Failure (pre-fix, now resolved):** Ordering B produced `agree_to_cancel=true` on a disputed escrow — Invariant 7 violation. Both orderings now produce zero violations.

**Why audits miss it:** Unit tests typically test one ordering. The bug was a two-transaction interaction invisible to single-call analysis.

---

### F6 — Resolver Cartel (`S29`)

**Attack:** Both L0 and L1 escalation roles are controlled by the same colluding entity, resolving all disputes in a fixed direction.

**Failure:** Escalation provides zero corrective value. Final state: released (attacker's favour). Multi-level escalation only provides safety if each level is independently controlled.

**Why audits miss it:** The protocol is internally consistent. The failure requires modelling actor identity across escalation levels.

---

### F7 — Profit-Threshold Strike (`S30`)

**Attack:** Rational resolver refuses to service escrows below its cost floor. `fee_bps=100` on 100-token escrow → fee=1 token; resolver min_profit=5; refuses to act.

**Failure:** 0 resolutions, ≥1 refusal. Dispute permanently unresolved within the run window. No on-chain signal raised.

**Why audits miss it:** The resolver's refusal is off-chain behaviour. No on-chain tool can model a rational agent's economic threshold.

---

### F8 — Appeal Fee Amplification (`S31`)

**Attack:** Buyer escalates after every resolution, forcing the protocol to pay multiple resolution fees across all escalation levels.

**Failure:** ≥2 resolutions executed. Fee burden multiplied by escalation depth with no additional cost to the escalating party (absent escalation bonds).

**Why audits miss it:** Each escalation call is individually valid. Aggregate economic impact is only visible when simulating the full sequence.

---

### F9 — Sub-Threshold Misresolution (`S32`)

**Attack:** L0 issues a fraudulent resolution (releases to seller). Buyer detects the wrong outcome and escalates. L1 corrects the outcome.

**Result:** This is a positive scenario — the escalation mechanism works as designed. L0 misresolution fires; L1 correction fires; final state: refunded.

**Value:** Proves the economic cost of recovering from L0 fraud: a buyer must detect, escalate, and pay for a second resolution.

---

### F10 — Cascade Escalation Drain (`S33`)

**Attack:** 4 disputes raised simultaneously against an arbitrator capped at 2 resolutions.

**Failure:** Escrows 2 and 3 permanently stuck in `disputed` state. `disputes_triggered=4`, `arbitrator.resolutions=2`, `still_disputed=2`.

**Why audits miss it:** Static analysis confirms `resolve()` is callable. Capacity exhaustion failure only emerges under concurrent load.

---

### F11 — Pending Settlement Races + Fraud Slash (`S34`)

**Attack:** Pending settlement window is raced by a fraudulent resolver attempting to execute a slashed settlement before the honest party can intervene.

**Failure:** A slashed resolver's settlement executes if the honest party fails to claim within the same block. Tests the interaction between fraud proof timelines and settlement execution ordering.

---

### F12 — Profit Maximizer Governance Wins Appeal (`S35`)

**Attack:** A profit-maximizing resolver, after being slashed by governance, appeals the decision and wins at a higher escalation level.

**Failure:** Governance slashing is overturned on appeal. Tests the escalation layer's ability to correct governance-level decisions when the resolver's appeal is valid.
