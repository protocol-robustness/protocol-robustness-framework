# Yield-Bearing Asset Invariants

Yield-bearing scenarios extend the Sew protocol invariants with yield-specific
edge cases where escrows carry on-chain yield positions. Value can change
through index movement, shortfalls, haircuts, and accrual quantization.

## Category Definition

A scenario is classified as **yield-bearing** when escrow principal is placed
into a configured yield module and finalization may crystallize yield,
shortfall, haircut, or index-driven value movement.

Mechanically, this requires:

- `yield-preset` normalized to a value other than `:off`
- a configured yield module such as `:aave-v3`, `:fixed-rate`

`yield-bearing` is a scenario classification, not necessarily a distinct
protocol type. It is used to select invariant profiles, interpret expected
failures, and group evidence/artifacts.

## Classification Axes

Yield-bearing scenarios are classified along four axes:

| Axis | Field | Values |
|---|---|---|
| Enablement | `:yield/enabled?` | `true`, `false` |
| Module | `:yield/module` | `:aave-v3`, `:fixed-rate` |
| Risk class | `:yield/risk-class` | `:principal-preserving`, `:liquidity-shortfall`, `:principal-loss`, `:historical-index-replay`, `:negative-yield`, `:quantization-drift` |
| Invariant profile | `:invariant/profile` | `:sew/base`, `:sew/yield-bearing` |

### Risk-class semantics

| Risk class | Meaning | Expected invariant interpretation |
|---|---|---|
| `:principal-preserving` | Yield may accrue; principal always recoverable | All invariants pass |
| `:liquidity-shortfall` | Temporary liquidity shortage may defer fulfillment | `:conservation-of-funds` passes (deferred is still owed) |
| `:principal-loss` | Unrecoverable haircut permanently reduces assets | `:conservation-of-funds` passes (loss recognized); `:solvency` held-custody reconciliation passes post-haircut (loss is a flow, not a remaining liability). The canonical assessment reports `:assessment/status :impaired` with `:assessment/reasons #{:realized-loss}`. |
| `:historical-index-replay` | External index schedule drives accrual | All invariants pass |
| `:negative-yield` | Mark-to-market may reduce position value below principal | `:conservation-of-funds` passes; temporary dips allowed |
| `:quantization-drift` | Floor rounding across accrual + settlement may create small deltas | Expectations widened to accommodate rounding |

## Scenario Map

| ID | Categories | Risk class | Stress theme | Core invariants | Expected diagnostic |
|---|---|---|---|---|---|
| S113 | `#{:yield-bearing :principal-loss}` | `:principal-loss` | Unrecoverable haircut at 50% liquidity | All pass | `:assessment/status :impaired` (haircut destroys 2475 of 4950 deposited; obligations covered post-haircut) |
| S115 | `#{:yield-bearing :historical-index-replay}` | `:historical-index-replay` | External aave V3 index schedule | All pass | None |

### Expected invariant results

**S113:**

```clojure
{:expected-invariant-results
 {:solvency          {:status :pass :expected? false}   ; held-custody reconciles post-haircut
  :conservation-of-funds {:status :pass :expected? false}
  :token-tax-reconciliation {:status :pass :expected? false}
  :held-delta-accounted    {:status :pass :expected? false}}}
;; canonical assessment (see resolver-sim.protocols.sew.financial.solvency):
;; {:assessment/status :impaired :assessment/reasons #{:realized-loss}}
```

**S115:**

```clojure
{:expected-invariant-results
 {:solvency          {:status :pass :expected? false}
  :conservation-of-funds {:status :pass :expected? false}
  :single-resolution-payout-consistent {:status :pass :expected? false}}}
```

## Detailed Scenario Analysis

### S113 — Principal-Loss Haircut

**Setup:** 5000 USDC escrow (fee 50 → 4975 deposited). Liquidity schedule
drops `available-ratio` to 0.5 at t=1500. Shortfall model
`{:type :principal-loss, :recoverable false}`.

**Haircut mechanics:**

- Available = floor(4975 × 0.5) = 2475
- Haircut = 4975 − 2475 = **2475** (permanent — `recoverable = false`)

**Accounting trace:**

| Step | Action | total-held | claimable | fees | losses |
|------|--------|-----------|-----------|------|--------|
| 0 | create_escrow(5000) | 4950 | — | 50 | — |
| 1 | withdraw (50% shortfall) | 0 | 2475 | 50 | 2475 |

**Invariant evaluation:**

| Check | Pass? | Equation |
|-------|-------|----------|
| `:conservation-of-funds` | Yes | `inflow = 5000 − 2475 = 2525` = `held(0) + fees(50) + claimable(2475) = 2525` |
| `:token-tax-reconciliation` | Yes | No unexplained delta — `sub-held` matches `fulfilled + haircut` |
| `:held-delta-accounted` | Yes | `delta-inflow (−5000 − 0 + 0 − 2475) = −7475` = `delta-held (−4950) − delta-claimable (2475) − 0 − 0 − fees(50) = −7475` |
| `:solvency` | **Pass** | Held-custody reconciles post-haircut: the 2475 haircut reduced both entitlement and custody, so it is a flow, not a remaining liability. The canonical assessment classifies `:assessment/status :impaired` with `:assessment/reasons #{:realized-loss}` (obligations covered post-haircut). Value destruction is tracked by `:conservation-of-funds`, not by calling the protocol insolvent. |

**Correction to earlier draft:** The numbers `5025 < 5000` were wrong. The
correct values are `total-assets = 2525` (claimable 2475 + fees 50),
`total-inflows = 5000` (principal deposited), `ratio = 0.505`.

### S115 — Historical Index Replay

**Setup:** 5000 USDC escrow (fee 50 → 4975 deposited). External index
schedule `aave_historical_v3.json`: `1.0 → 1.002 → 1.005 → 1.008 → 1.012`
over 19000 seconds. No APY configured.

**Yield accrual:** At t=20000, market state resolves index = 1.012.
`update-position-yield` computes `current-value = 4975 × 1.012 = 5034`
(floored), `yield = 5034 − 4975 = 59`.

**Accounting trace:**

| Step | Action | total-held | total-yield-generated | claimable principal | claimable yield |
|------|--------|-----------|---------------------|-------------------|-----------------|
| 0 | create_escrow(5000) | 4950 | — | — | — |
| 1 | trigger-accrue (index 1.012) | 5009 | 59 | — | — |
| 2 | release | 0 | 59 | 4950 (seller) | 59 (buyer) |

**Critical bugs fixed during investigation:**

| Component | Bug | Fix |
|-----------|-----|-----|
| `liquid_lending/accrue` | Index-schedule never read — APY=0 produced zero accrual | Calls `market-state/get-market-state`; uses schedule index directly via `update-position-yield` |
| `liquid_lending/withdraw` | `realized-yield` capped at `(fulfilled − principal)` = 0 under waterfall `:not-claimable` policy | When no shortfall, realized-yield = full `unrealized-yield` |
| `registry/normalize-schedule` | External JSON `:type` was string `"steps"` but `get-value-at-time` pattern-matched on keyword `:steps` | Added `(update :type keyword)` after `load-external-json` |
| `single-resolution-payout-consistent?` | Checked flat legacy `:claimable` map — yield distribution to sender appeared as second payout direction | Switched to v2 domain-level check (`:claimable-v2`); each domain (`:settlement/principal`, `:settlement/yield`) independent |

## Invariant Behavior by Risk Class

### `:principal-preserving`

All core invariants pass. Yield is tracked via `:total-yield-generated`
and `:total-held`. Principal is always fully recoverable.

| Invariant | Expected |
|-----------|----------|
| `:conservation-of-funds` | pass |
| `:solvency` | pass |
| `:held-delta-accounted` | pass |
| `:token-tax-reconciliation` | pass |

### `:principal-loss` (e.g. S113)

| Invariant | Expected | Why |
|-----------|----------|-----|
| `:conservation-of-funds` | pass | Loss subtracted from inflow via `sum-recognized-losses` |
| `:solvency` | pass | Haircut reduced both entitlement and custody; the held-custody ledger still reconciles |
| `:held-delta-accounted` | pass | Losses term in `delta-inflow` |
| `:token-tax-reconciliation` | pass | Loss matched by deferred→haircut reclassification |

### `:historical-index-replay` (e.g. S115)

| Invariant | Expected | Why |
|-----------|----------|-----|
| All core invariants | pass | Index schedule drives index → `update-position-yield` → yield recognized |
| `:single-resolution-payout-consistent` | pass | V2 claimable domains decouple principal and yield payouts |

## Classification Helper

```clojure
(def classify-yield-scenario
  "Return a classification map for a loaded scenario.
   Used to select invariant profiles and interpret expected failures."
  [scenario]
  (let [pp      (:protocol-params scenario {})
        yield   (get pp :yield-config (:yield-config scenario {}))
        preset  (or (get pp :yield-preset)
                    (get-in scenario [:events 0 :params :yield-preset]))
        mod-ref (or (get pp :yield-profile) (get pp :yield-generation-module))
        enabled (and (some? preset)
                     (not= preset :off)
                     (some? mod-ref))
        risk-class (cond
                     (get-in yield [:modules :aave-v3 :tokens :USDC :shortfall-model :type]
                             (get-in yield [:modules :aave-v3 :tokens :USDC :liquidity-schedule]))
                     :principal-loss  ;; simplified: actual logic would inspect shortfall-model
                     :else :principal-preserving)
        categories (cond-> #{}
                    enabled (conj :yield-bearing)
                    (= risk-class :principal-loss) (conj :principal-loss))]
    {:yield/enabled? enabled
     :yield/preset   (when enabled (keyword preset))
     :yield/module   (when enabled (keyword mod-ref))
     :yield/risk-class risk-class
     :scenario/categories categories
     :invariant/profile (if enabled :sew/yield-bearing :sew/base)}))
```

## Remaining Gaps

| Gap | Scenario | Nature |
|-----|----------|--------|
| `:assessment/status :impaired` | S113 | Principal-loss scenario records a realized haircut; the canonical assessment classifies `:impaired` (obligations covered post-haircut) rather than `:insolvent`. The underlying scenario currently also trips `:conservation-of-funds` on its own (see `data/fixtures/golden/s113-principal-loss-haircut.report.edn`) — that pre-existing conservation drift should be resolved before treating the scenario as green. |

## Reference

- Yield-general invariants: `docs/yield/YIELD_INVARIANTS.md`
- Invariant catalog: `src/resolver_sim/yield/invariant_catalog.clj`
- Sew invariants: `src/resolver_sim/protocols/sew/invariants.clj`
- Yield scenarios: `scenarios/S113_principal-loss-haircut.json`, `scenarios/S115_historical-index-replay.json`
- Classification helper: planned for `src/resolver_sim/scenario/yield_classification.clj`
