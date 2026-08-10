# Yield-general invariant catalog (yield-v1)

### Data-Driven General Yield

The simulation uses a **fully data-driven yield engine** (General Yield Module) to model market behavior. This engine does not use hardcoded transition logic; instead, its behavior is injected via:
1. **Schedules**: External JSON/EDN files defining interest rates and liquidity indices over time.
2. **Policies**: Data-driven rules for withdrawal fulfillment (e.g., partial-fill, defer, haircut).
3. **Risk Profiles**: Dynamic failure modes like `:withdrawal-queue` or `:negative-yield` activated by simulation events.

*Note: As of this update, all yield modules utilize exact-ratio arithmetic and decision-based accrual.*

Liquid-lending archetype (`:aave-v3` profile) — provider-only replay, no Sew escrow.
...
Replay: `contract-model.replay/replay-yield-scenario` (thin sequential runner). Each
`yield_accrue` event's `:dt` must equal the `:time` delta from the previous event.
Simulated time advances only via each event's `:time` field (no separate time-advance action).

## Runtime (every replay step)

| Id | Check |
|----|--------|
| `yield/position-consistency` | Valid position arithmetic; MTM allows negative unrealized |
| `yield/exposure` | `:yield/held-balances` ≥ active custody need |
| `yield/shortfall-splits` | `fulfilled + deferred + haircut + fold = basis` on `:shortfall` (the negative-unrealized fold is recorded on the shortfall as `:basis-negative-unrealized`) |
| `yield/shortfall-detected` | No over/under-detection of shortfall |
| `yield/status-fsm` | Status ∈ `#{:active :unwinding :withdrawn}` |
| `yield/realized-non-negative` | Crystallized realized yield ≥ 0 |
| `yield/partial-liquidity-principal` | Partial-liquidity unwinding: no principal haircut; splits reconcile with the fold |
| `yield/deferred-reclaim` | Withdrawn positions: no shortfall; reclaimed ≥ 0 |
| `yield/token-key-consistency` | No simultaneous string/keyword token keys in accounting maps |
| `yield/value-conservation` | Deferred + haircut within position residual value |
| `yield/aggregate-shortfall-cap` / `yield/aggregate-shortfall` / `yield/aggregate` | Per-(module,token) aggregate: splits reconcile to basis, and total basis ≤ total value (using the recorded `:settlement-value`) |
| `yield/pro-rata-propagation-complete` | Shared-withdrawal decision → propagation → application binding (position-state checks apply to each participant's latest round; earlier rounds are covered by the lineage invariant) |
| `yield/pro-rata-accounting-reconciles` | Shared-withdrawal accounting entries and participant chains reconcile |
| `yield/shared-withdrawal-conservation` | Round-local: every `:yield-withdraw-shared` decision replay-verifies `Σ filled = min(available, Σ effective-demand)`, per-row caps, no haircut, non-overallocation |
| `yield/withdrawal-lineage-conservation` | Cross-round: per participant, Σ realized-fill across the whole lineage + terminal outstanding = original requested; cumulative fill never exceeds it; each re-entered request equals the prior round's deferred residual; position cumulative reconciles to the decision-derived cumulative |
| `yield/withdrawal-budget-provenance` | L1: committed liquidity budget recomputes from committed source custody + available-ratio (content-addressed roots) |
| `yield/withdrawal-ledger-conservation` | Every recorded withdrawal is a valid fixed-point certificate bound to its execution world and withdrawal subject: recomputed roots (run, params, state cutpoint, request-set, request-order, allocation-policy, basis) reconcile; per-principal uniqueness and exact bijection; per-row and aggregate economic conservation; FCFS prefix pool bound; `filled ≥ 0`. Catches run/execution/state transplants, same-run withdrawal substitution, row-value substitution, duplicate owners, and fabricated totals |

Implementation: `src/resolver_sim/yield/invariants.clj`  
Catalog metadata: `src/resolver_sim/yield/invariant_catalog.clj`  
EDN mirror: `data/yield/invariant-catalog.edn`

## Transition (each successful step)

| Id | Check |
|----|--------|
| `yield/index-monotone` | `:yield/indices` non-decreasing on accrue; non-increasing under `:negative-yield` |

Implementation: `src/resolver_sim/yield/invariants_transition.clj` (via `protocols/yield` `check-invariants-transition`).

## Offline properties (tests only)

| Id | Check |
|----|--------|
| `yield/accrual-partition-bounded` | One-shot vs `N` fragmented accruals within drift budget (`yield/accrual_properties.clj`; budget 365 for liquid-lending) |

## Scenario expectations

`:yield-provider-scenarios` JSON files get default `:expectations :invariants` merged at load time (`yield.invariant-catalog/enrich-expectations`). Runtime invariants are re-checked on the trace end world; transition invariants are enforced per step via `:metrics :invariant-results` (`scenario.expectations/evaluate-invariants`). The canonical gate currently uses the top-level `scenarios/Y01..Y05` provider scenarios; the older `scenarios/yield/Y*` files remain as legacy compatibility fixtures.

## Scenario map

| Scenario | Stress theme |
|----------|----------------|
| Y01 | Baseline accrual |
| Y02 | Negative yield MTM |
| Y03–Y05 | Shortfall-affected / recovery |
| Y06 | Liquidity shortage (deposit blocked) |
| Y07 | Twelve monthly accrues (index monotonicity / long horizon) |

## Scenario suites

| Suite keyword | Protocol | Paths |
|---------------|----------|-------|
| `:yield-provider-scenarios` | `yield-v1` | `scenarios/Y01..Y05` |
| `:sew-yield-scenarios` | `sew-v1` | `scenarios/S*` yield integration |
| `:yield-scenarios` | (alias) | same as `:sew-yield-scenarios` |

## Not in this batch

Pool utilisation, reserve factor, borrow/debt, liquidation timing — require `:yield/pool-state` or Sew integration (phase 2).
