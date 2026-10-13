# Phase C/E/F/M Audit — P1.1 Remediation

Audit of whether each sub-phase exercises the Sew protocol kernel
(`resolve-dispute`, `replay-with-protocol`, or `run-batch`).

---

## Phase C — Corruption Economics

| Sub-phase | Classification | Kernel? | Notes |
|-----------|---------------|---------|-------|
| C1 Bribery Cost Model | :analytic | ✗ | `profit = gain - cost` algebra; fixed escrow 10000, fee-bps 150 |
| C2 External Collusion | :analytic | ✗ | `cost = overhead × (1 + 0.5·log(N))` algebra |
| C3 Layer Escalation Attack | :analytic | ✗ | `cost = Σ(bond × 1.5^n)` algebra |
| C4 Detection Probability Trade-off | :analytic | ✗ | `net-profit = gross - expected-loss - attack-cost` algebra |
| C5 Profit-Maximizer Lifecycle | :analytic | ✗ | `total-profit = fees - slashing-loss` over K disputes |
| C6 Strategic Abstention | :analytic | ✗ | `lazy-ev vs malicious-ev` comparison; fixed constants |

**All 6 sub-phases** use fixed constants (escrow=10000, fee-bps=150, bond-mult=2.0)
and pure algebra. No RNG use despite importing `rng`. No protocol calls.

**Recommendation:** Keep as :analytic. A protocol-kernel version would require
defining each concept (bribery, collusion, escalation cost) as deterministic
scenarios with invariants — a large redesign.

---

## Phase E — Evidence Integrity

| Sub-phase | Classification | Kernel? | Notes |
|-----------|---------------|---------|-------|
| E1 Deadline Enforcement | :analytic | ✗ | `(<= submission deadline)` comparison |
| **E2 Hash Mismatch** | **:analytic** | **✗** | **Was tautology (`detected? = occurs?`). Replaced with probabilistic detection model (detection-prob 0.90-0.99). Still analytic.** |
| E3 Conflicting Evidence | :analytic | ✗ | Weight function comparison (equal/recency/reputation) over 3 evidence pieces |
| E4 Bloat Griefing | :analytic | ✗ | `cost = 16 gas/byte × size` gas model |
| **E5 Yield Accrual** | **:protocol-kernel-evidence** | **✓** | **Creates escrow, raises dispute, executes resolution, checks yield via `held-delta-accounted?` — exercises protocol logic** |
| E6 Availability | :analytic | ✗ | `availability = 1 - (1-0.95)^redundancy` probability model |

**E5 is the only kernel-exercising sub-phase.** E2 was a tautology (fixed
to use a probabilistic detection model). E1 is a simple date comparison.

**Recommendation:** Keep E1-E4, E6 as :analytic. E5 already uses the kernel.
Consider expanding E2 to test actual hash verification logic from the Sew
protocol (currently no on-chain hash verification is implemented).

---

## Phase F — Economic Parameters

| Sub-phase | Classification | Kernel? | Notes |
|-----------|---------------|---------|-------|
| F1 Detection Probability Sweep | :analytic | ✗ | Algebraic EV model across detection rates |
| F2 Bond Size Sweep | :analytic | ✗ | EV comparison across bond multiples |
| F3 Fee Adequacy Sweep | :analytic | ✗ | Fee vs bond vs EV algebraic model |
| F4 Escrow Concentration Sweep | :analytic | ✗ | Concentration risk via algebraic model |
| F5 Multi-Resolver Equilibrium | :analytic | ✗ | Nash equilibrium proxy via algebraic EV |
| F6 Appeal Window Adequacy | :analytic | ✗ | Window vs loss-rate algebraic model |

**All 6 sub-phases** are algebraic EV models. No protocol calls.

**Recommendation:** Keep as :analytic. Each could be rewired to run
`run-batch` with varying params (the batch pipeline already exists), but
the current implementation pre-dates the batch runner and would require
significant refactoring.

---

## Phase M — Fairness Analysis

| Sub-phase | Classification | Kernel? | Notes |
|-----------|---------------|---------|-------|
| M1 Access to Justice | :analytic | ✗ | Fee/escrow ratio model |
| M2 Asymmetric Information | :analytic | ✗ | Information cost algebraic model |
| M3 Frivolous Appeal | :analytic | ✗ | Bond sufficiency algebraic model |
| M4 Expert Availability | :analytic | ✗ | Availability probability model |

**All 4 sub-phases** are analytic. No protocol calls.

**Recommendation:** Keep as :analytic.

---

## Summary

| Phase | Sub-phases | Kernel-exercising | Analytic | Tautology/broken |
|-------|-----------|------------------|----------|-----------------|
| C | 6 | 0 | 6 | 0 |
| E | 6 | 1 (E5) | 5 | 1 (E2 — fixed) |
| F | 6 | 0 | 6 | 0 |
| M | 4 | 0 | 4 | 0 |
| **Total** | **22** | **1 (4.5%)** | **21** | **1 (fixed)** |

Only 1 of 22 sub-phases (E5 — Yield Accrual) exercises the Sew protocol
kernel. The remaining 21 are analytic closed-form models.

---

## Changes applied

1. **`engine.clj`**: Added `:class` field to `make-result` — defaults to
   `:protocol-kernel-evidence`; analytic sub-phases declare `:class :analytic`.
2. **Phase C**: All 6 sub-phases declared `:class :analytic`.
3. **Phase E**: E1-E4, E6 declared `:class :analytic`; E5 left as kernel.
4. **Phase F**: All 6 sub-phases declared `:class :analytic`.
5. **Phase M**: All 4 sub-phases declared `:class :analytic`.
6. **E2 tautology fixed**: `collision-detected?` was previously `(if collision-occurs? true false)`
   (always passes). Replaced with probabilistic detection model where
   `detected? = and occurs? (< random detection-prob)`, with a 6×3 parameter
   sweep over collision-prob × detection-prob.
7. **E3/E4/E6 threshold discrepancy**: File-level docstring now accurately
   documents per-sub-phase thresholds (E3 67%, E4 75%, etc.).
