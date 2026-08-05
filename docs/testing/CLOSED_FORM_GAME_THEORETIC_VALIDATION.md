# Closed-Form Validation: Partial-Fill, Pro-Rata, and Shortfalls

## Overview

The validation architecture cross-cuts four layers:

| Layer | What it validates | Game-theoretic? |
|-------|------------------|-----------------|
| **Closed-form checks** (`partial_fill.clj`) | Algebraic correctness of individual settlement decisions | Algebraic foundation |
| **Claims evaluators** (`pro_rata_claims.clj`) | Structural correctness of allocation evidence | No — but feeds mechanism map |
| **Yield invariants** (`invariants.clj`) | World-state consistency after shortfall events | No — necessary precondition |
| **Equilibrium validators** (`equilibrium.clj`) | Trace-level consistency with equilibrium properties | **Yes** — single-trace proxy |
| **Strategic claim validation** (`strategic_claim_validation.clj`) | Mechanism-level consistency across scenarios | **Yes** — crosses scenarios |

The relationship between layers:

```
[Partial-fill decision] ──→ [Closed-form checks (15)] ──→ per-decision pass/fail
       │
       ├──→ [Claims evaluator bridge] ──→ [Mechanism map] ──→ [Strategic claims]
       │
       └──→ [Yield position] ──→ [Yield invariants (9)] ──→ per-step pass/fail
                                      │
                                      └──→ [Equilibrium validators] ──→ trace-level proxy
```

---

## Layer 1: Closed-Form Checks (`src/resolver_sim/yield/partial_fill.clj`)

### Entry Point

```clojure
(partial-fill-closed-form-checks decision)
```

Takes a settlement decision map (from `calculate-fulfillment`). Returns a vector of check results:

```clojure
[{:check/id :partial-fill/conservation
  :status :pass | :fail | :not-applicable
  :details {...}}
 ...]
```

### All 15 Check IDs

#### Algebraic Core

| Check ID | Logic | Mode-sensitive? |
|----------|-------|-----------------|
| `:partial-fill/conservation` | `total-requested = total-filled + total-deferred + total-haircut` | No |
| `:partial-fill/capacity-bound` | `total-filled <= available-liquidity` | No |
| `:partial-fill/per-claim-bound` | Every claim: `filled[i] <= requested[i]`, `deferred[i] <= requested[i]`, `haircut[i] <= requested[i]` | No |
| `:partial-fill/per-claim-conservation` | Per claim: `requested[i] = filled[i] + deferred[i] + haircut[i]` over union of all key sets | No |
| `:partial-fill/rounding-residual-bounded` | Policy-aware: `:floor-and-carry`/`:floor`/`:principal-protective-floor` → residual < max(1, count); `:largest-remainder` → residual = 0 | Policy-sensitive |

#### Consistency & Integrity

| Check ID | Logic | Mode-sensitive? |
|----------|-------|-----------------|
| `:partial-fill/claim-key-consistency` | No keys in `filled`/`deferred`/`haircut` that are not in `requested` | No |
| `:partial-fill/non-negative-amounts` | All amounts in all buckets >= 0 (covers `requested`, `filled`, `deferred`, `haircut`, `unrealized`) | No |
| `:partial-fill/settlement-mode-consistency` | `:full-fill` must have empty `deferred`/`haircut` and `filled = requested` | Yes |
| `:partial-fill/settlement-mode-valid` | `settlement-mode` must be `:full-fill` or `:partial-fill` | No |
| `:partial-fill/mode-valid` | Mode must be `:pro-rata`, `:principal-first`, or `:waterfall` | No |
| `:partial-fill/deferred-haircut-overlap` | No claim key appears in both `deferred` and `haircut` with positive amounts | No |
| `:partial-fill/evidence-self-consistency` | Evidence `:shortage`, `:fill-mode`, `:total-requested` match computed values (only when key present) | No |
| `:partial-fill/unrealized-bucket-valid` | All keys in `:unrealized` exist in `:requested` | No |
| `:partial-fill/decision-artifact-format` | `:decision/hash` matches `sha256:[0-9a-f]{64}`, `:decision/id` matches `partial-fill-[0-9a-f]{1,16}` | No |

#### Mode-Specific Priority

| Check ID | Logic | Mode-sensitive? |
|----------|-------|-----------------|
| `:partial-fill/pro-rata-cross-product` | **Game-theoretic invariant**: for all pairs i,j: `filled[i] × requested[j] = filled[j] × requested[i]` (proportional fairness) | `:pro-rata` only |
| `:partial-fill/principal-first-priority` | If principal is requested but not fully filled, all yield claims must have zero fill | `:principal-first` only |
| `:partial-fill/waterfall-priority` | For each pair (higher, lower) in fill-order: if higher has unmet requested amount, lower must have zero fill | `:waterfall` only |

### Batch Validation

```clojure
(validate-batch-decisions [decision1 decision2 ...])
;; Returns:
{:batch/valid? bool
 :batch/summary {:total-decisions n :passed-count n :failed-decisions [...]}
 :batch/checks [[decision check-results] ...]}
```

Runs `partial-fill-closed-form-checks` on every decision in a collection and aggregates.

### Artifact Hash Validation

```clojure
(validate-decision-artifact position decision)
;; Returns:
{:check/id :artifact/hash-integrity
 :status :pass | :fail | :not-applicable
 :details {...}}
```

Recomputes the content-addressed hash (via `decision-artifact`) and compares to `:decision/hash` embedded in the decision. Requires the original position map.

---

## Layer 2: Claims Evaluators (`src/resolver_sim/yield/pro_rata_claims.clj`)

### Registered Evaluators

| Claim ID | What it validates |
|----------|-------------------|
| `:projection-deterministic` | Projection artifact hash is deterministic |
| `:projection-canonical-safe` | Only canonical hash-safe values |
| `:allocation-complete` | Every liable party gets a row; no extra rows |
| `:non-negative` | No negative values on paid/unmet/owed/etc. |
| `:conservation` | `total-requested = total-allocated + total-unmet + remainder` |
| `:rounding-bounded` | No allocation deviates from ideal share by > 1 unit |
| `:ordering-independent` | Allocation invariant under input permutation |
| `:pro-rata-fairness` | Cross-product equality `received[i] × owed[j] = received[j] × owed[i]` (Sew slash format) |
| `:partial-fill-fairness` | Same cross-product check, but reads from partial-fill decision artifacts (`:requested`/`:filled` maps) |

### Bridge Architecture

The `:partial-fill-fairness` evaluator adapts partial-fill decision maps into the claims engine's expected evidence format:

```
Decision artifact                 Claims-compatible content
┌──────────────────┐              ┌────────────────────────────┐
│ :requested {:a 40 │  ──adapt──→ │ :claims/direct-result      │
│            :b 60} │              │   {:allocations            │
│ :filled {:a 20   │              │     [{:id :a :paid 20 :owed 40}
│          :b 30}  │              │      {:id :b :paid 30 :owed 60}]}
└──────────────────┘              └────────────────────────────┘
```

This reuses the existing `pro-rata-fairness-violations` function, avoiding duplication.

### Usage

```clojure
(evaluate-claim :partial-fill-fairness
  {:evidence-nodes [{:result decision-map}]})
```

---

## Layer 3: Yield Invariants (`src/resolver_sim/yield/invariants.clj`)

### All 9 Invariants

| ID | What it validates | Shortfall-specific? |
|----|-------------------|---------------------|
| `:yield/position-consistency` | Principal/shares/realized >= 0; unrealized >= 0 unless mtm | No |
| `:yield/exposure` | Held balances cover active position economic value | No |
| `:yield/shortfall-splits` | `fulfilled + deferred + haircut = basis-amount` when shortfall exists | **Yes** |
| `:yield/shortfall-detected` | Over-detection (basis <= position value) and under-detection (unwinding in shortfall mode must have shortfall) | **Yes** |
| `:yield/status-fsm` | Position status in `#{:active :unwinding :withdrawn}` | No |
| `:yield/realized-non-negative` | Realized-yield never negative | No |
| `:yield/partial-liquidity-principal` | Under partial-liquidity, principal kept intact | **Yes** |
| `:yield/value-conservation` | `deferred + haircut <= principal + max(0, unrealized)` | **Yes** |
| `:yield/deferred-reclaim` | Withdrawn positions: no shortfall, reclaimed >= 0 | **Yes** |

### Shortfall-Detected Invariant Details

Two-tier check added in Phase 3:

**Over-detection** — No position's `:shortfall :basis-amount` exceeds its total economic value:
```clojure
(and sf (pos? (:basis-amount sf 0))
     (> (long (:basis-amount sf 0)) total-value))
→ false (violation)
```

**Under-detection** — When a module/token is in `:shortfall` liquidity mode with `available-ratio < 1.0`, any position in `:unwinding` status must have `:shortfall` data:
```clojure
(and shortfall-mode?
     (#{:unwinding} status)
     (nil? sf))
→ false (violation)
```

---

## Layer 4: Equilibrium Validators (`src/resolver_sim/scenario/equilibrium.clj`)

### Generic Mechanism Properties

| ID | Basis | What it checks |
|----|-------|----------------|
| `:incentive-compatibility` | `:single-trace-metric-proxy` | No attack succeeded, no funds lost |
| `:sybil-resistance` | `:single-trace-metric-proxy` | No identity attack succeeded |
| `:pro-rata-fairness` | `:single-trace-metric-proxy` | Shortfall conservation holds (basis = filled + deferred + haircut) |

### Pro-Rata Fairness Validator Details

```clojure
;; Metrics consumed:
;;   :total-shortfall-basis     — sum of all shortfall basis-amounts
;;   :total-shortfall-filled    — sum of all fulfilled-amounts
;;   :total-shortfall-deferred  — sum of all deferred-amounts
;;   :total-shortfall-haircut   — sum of all haircut-amounts

;; :inconclusive — no shortfall events (basis = 0)
;; :pass         — conservation holds (recovery = basis)
;; :fail         — conservation violated (imbalance detected)
```

This is a single-trace proxy (like all generic mechanism properties). Per-claimant proportional fairness is independently verified by `:partial-fill/pro-rata-cross-product` in the closed-form checks.

### Registration

```clojure
(def ^:private mechanism-validators
  {:incentive-compatibility   check-incentive-compatibility
   :sybil-resistance          check-sybil-resistance
   :pro-rata-fairness         check-pro-rata-fairness})
```

To add a new validator: write a function matching the signature `[projection] → result-map`, add to this map.

---

## Layer 5: Strategic Claim Validation (`src/resolver_sim/benchmark/strategic_claim_validation.clj`)

### Catalog: 6 Claims

| Claim ID | Mechanism Levels | Match Dimensions | Benchmarked |
|----------|-----------------|------------------|-------------|
| `:claim/pro-rata-shortfall-conservation` | `[:allocation/partial-fill :allocation/shortfall]` | `#{:allocation/partial-fill :allocation/shortfall}` | Research validation; partial-fill level runs closed-form conservation checks |
| `:claim/waterfall-fill-integrity` | `[:allocation/partial-fill]` | `#{:allocation/partial-fill}` | Research validation; runs waterfall-priority check |
| `:claim/partial-fill-rounding-integrity` | `[:allocation/partial-fill]` | `#{:allocation/partial-fill}` | Research validation; runs rounding-residual check |
| `:claim/mode-validity` | `[:allocation/partial-fill]` | `#{:allocation/partial-fill}` | Research validation; runs mode-validity checks |
| `:claim/shortfall-detection-validity` | `[:allocation/shortfall]` | `#{:allocation/shortfall}` | Research validation; invariant-backed only |
| `:claim/pro-rata-fairness-end-to-end` | `[:allocation/partial-fill]` | `#{:allocation/partial-fill}` | Research validation; runs cross-product check when a pro-rata decision artifact exists |

### Running a Validation

```bash
bb benchmark:game-theory --strategic \
  --claim-id claim/pro-rata-shortfall-conservation \
  --out ./prf-out/game-theory
```

Or from Clojure:

```clojure
(require 'resolver-sim.benchmark.game-theory-validation :as gt)
(gt/run-strategic-claim-validation
  :claim-id :claim/shortfall-detection-validity
  :out-dir "./prf-out/game-theory")
```

### Artifact Output

Each claim produces two files:
```
prf-out/game-theory/<claim-name>/
  game-theoretic-validation-artifact.edn
  game-theoretic-validation-artifact.json
```

The artifact contains:
- Matched scenarios with evidence references
- Level verdicts (`:pass`, `:fail`, `:uncovered`)
- Coverage gaps with reasons (`:no-declared-scenarios-for-level`, `:declared-scenarios-failed-match-basis`)
- Summary with pass/fail/uncovered counts

For a partial-fill mechanism level, an absent decision artifact produces an
explicit `:no-partial-fill-decision-artifacts` coverage gap. It is not treated
as evidence that the named closed-form property passed.

---

## Redistribution Engine (`src/resolver_sim/economics/payoffs.clj`)

### Iterative Capping

`allocate-pro-rata-with-redistribution` now performs multi-pass iterative redistribution (up to 10 passes), not single-pass:

```
Pass 0: allocate to all items → detect capped items → compute excess
Pass 1: allocate excess to uncapped items → detect newly-capped → compute excess
Pass 2: allocate remaining excess to still-uncapped items → ...
...until no new caps or iteration limit
```

Redistribution metadata uses structured `:passes` vector:
```clojure
:redistribution {:passes [{:pass 0 :capped-ids [...] :excess n}
                          {:pass 1 :capped-ids [...] :excess n}]
                 :total-passes n}
```

### Merge Helper

```clojure
(merge-into-base base-map additional-allocs)
```

Adds redistribution allocations to prior-pass allocations, summing `:allocated` for matching IDs.

---

## Scenario: Multi-Party Shortfall (Y06)

File: `scenarios/Y06_multi-party-pro-rata-shortfall.json`

Exercises pro-rata split between two competing claimants under shortfall:

| Agent | Deposit | Shortfall (60% ratio) |
|-------|---------|----------------------|
| Alice | 1000 USDC | Receives 600, defers 400 |
| Bob | 2000 USDC | Receives 1200, defers 800 |

Registered in `yield-provider-scenario-ids`. Replays with `:outcome :pass`.

---

## Integration Guide

### Adding a New Check to the Closed-Form Suite

1. Add computation binding in `partial-fill-closed-form-checks` `let` block
2. Add `(future (check-result :partial-fill/<check-id> ...))` 
3. Add to `mapv deref` vector
4. Update docstring
5. Add test with pass, fail, and (if applicable) not-applicable cases

### Adding a New Strategic Claim

1. Add entry to `strategic-claim-catalog` in `strategic_claim_validation.clj`
2. Choose a benchmark pack with declared scenarios
3. Set `:mechanism-levels` and `:match-dimensions` to overlap with pack's scenario dimensions
4. Set `:required-threat-tags` that appear in target scenarios

### Wiring a New Validator into Equilibrium

1. Add check function in `scenario/equilibrium.clj` (generic) or `protocols_src/sew/equilibrium.clj` (Sew-specific)
2. Register in `mechanism-validators` or `equilibrium-validators` map
3. Add test in `equilibrium_test.clj` using the `projection` helper

---

## Test Coverage

| Test file | Tests | Coverage |
|-----------|-------|----------|
| `partial_fill_test.clj` | 78 | All 15 closed-form checks + mode-specific + batch + artifact |
| `pro_rata_claims_test.clj` | 13 | 9 evaluators including bridge evaluator |
| `pro_rata_characterization_test.clj` | 39 | LRA allocator, redistribution, caps, conservation |
| `payoffs_test.clj` | 23 | Pro-rata allocation, iterative redistribution |
| `equilibrium_test.clj` | 72 | All mechanism properties + equilibrium concepts |
| `game_theory_validation_test.clj` | 4 | Strategic claim artifact emission, matching, level verdicts |
| `invariants_test.clj` | partial | Shortfall-splits, shortfall-detected (segmented from pre-existing ns issue) |

---

## Key Files

| File | Purpose |
|------|---------|
| `src/resolver_sim/yield/partial_fill.clj` | 15 closed-form checks, batch validation, artifact validation |
| `src/resolver_sim/yield/pro_rata_claims.clj` | 9 claims evaluators including partial-fill bridge |
| `src/resolver_sim/yield/invariants.clj` | 9 yield invariants including shortfall-detected |
| `src/resolver_sim/yield/invariant_catalog.clj` | Invariant metadata registry |
| `src/resolver_sim/economics/payoffs.clj` | Pro-rata allocator with iterative redistribution |
| `src/resolver_sim/yield/exact_math.clj` | Largest-remainder allocator primitive |
| `src/resolver_sim/scenario/equilibrium.clj` | Equilibrium validators including pro-rata-fairness |
| `src/resolver_sim/benchmark/strategic_claim_validation.clj` | Strategic claim catalog (6 claims) and artifact builder |
| `src/resolver_sim/benchmark/game_theory_validation.clj` | Orchestration: equilibrium suites, held-custody, strategic claims |
| `benchmarks/packs/prf-core/shortfall-allocation-v0.edn` | Benchmark pack for all strategic claims (4 scenarios) |
| `benchmarks/mechanisms/shortfall-v1.edn` | Mechanism-to-claim mapping (pro-rata-fairness corrected) |
| `scenarios/Y06_multi-party-pro-rata-shortfall.json` | Multi-party shortfall scenario |
| `scenarios/edn/Y06_multi-party-pro-rata-shortfall.edn` | Multi-party shortfall scenario (EDN) |
