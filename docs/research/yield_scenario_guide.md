# Yield Scenario Researcher Guide

This guide walks through running, inspecting, and modifying yield scenarios —
specifically **shortfall** and **shortage** scenarios.  It assumes you are
familiar with Clojure basics and the repo checkout structure.

---

## 1.  The two-minute tour

Open the **yield provider demo** in Clerk to see all five presets at once:

```bash
clojure -X:clerk
# → http://localhost:7777/notebooks/src/resolver_sim/notebooks/yield_provider_demo
```

Each card shows: deposit 1 000 USDC → 12 monthly accruals → final position
state.  The shortfall card shows `available-ratio 0.5`: half the yield is
fulfilled immediately, half deferred.  The presets are defined in:

```
src/resolver_sim/yield/presets.clj           # preset definitions
src/resolver_sim/yield/modules/liquid_lending.clj   # per-function failure-mode logic
```

---

## 2.  Scenario categories

Two related but distinct concepts:

| Term | What it affects | Position result | Scenario IDs |
|------|----------------|----------------|--------------|
| **Shortfall** | Existing positions at withdrawal time | `:unwinding` status, yield split into fulfilled + deferred | Y03, Y04, Y05, S82, S103, S107 |
| **Shortage** | New deposit attempts | Deposit rejected (no position created) | Y06, S106 |

**Shortfall** = a liquidity module cannot fulfil the full amount.  The yield
leg (or, for full shortfall, the whole principal) is split into an immediate
`fulfilled-amount` and a deferred remainder that can be claimed later via
`yield_claim_deferred`.

**Shortage** = the liquidity pool is in `:shortfall` liquidity mode, which
blocks new deposits at the entry gate.  No position is created; the deposit
event is rejected.

---

## 3.  Running a shortfall scenario (Y03)

Replay the Y03 (partial-liquidity shortfall-affected) scenario:

```bash
clojure -M:run -- --invariants --scenario scenarios/yield/Y03_partial-liquidity-shortfall-affected.json
```

### What happens step by step

The scenario file at `scenarios/yield/Y03_partial-liquidity-shortfall-affected.json`:

```
seq 0: create yield module (aave-v3, baseline preset)
seq 1: deposit 10 000 USDC
seq 2: accrue for 1 year (5 % APY → ~500 USDC yield)
seq 3: set-yield-risk → liquidity-mode = :shortfall, available-ratio = 0.5
seq 4: withdraw → actually a shortfall-affected partial withdraw
       (500 yield × 0.5 = 250 fulfilled, 250 deferred)
seq 5: claim-deferred → recover the 250 deferred yield
```

*Note: the actual event order in the Y03 JSON file is: deposit → withdraw
with shortfall-affected flag → claim-deferred.*  The yield accrual is implicit
in the scenario (the yield module has a fixed 5 % rate).

### Where the shortfall calculation happens

Open `src/resolver_sim/yield/accounting.clj`:

```
line 122  apply-liquidity-stress     — split amount into fulfilled / deferred / haircut
line 168  apply-liquidity-stress-for-withdraw  — for partial-liquidity,
                                                 only the yield leg is stressed
line 158  partial-yield-shortfall?   — true when basis-amount < principal
```

Then `src/resolver_sim/yield/modules/liquid_lending.clj`:

```
line 128  withdraw                   — entry point, calls liquidity stress
line 176  (partial-liquidity path)   — stress applied to yield leg
line 186  shortfall result enrichment — position status set to :unwinding
```

### The invariant that checks correctness

```
src/resolver_sim/yield/invariants.clj:41    :yield/shortfall-splits
```

This verifies that `fulfilled-amount + deferred-amount == gross-amount`
(mod shortfall-haircut).

---

## 4.  Running a shortage scenario (Y06)

```bash
clojure -M:run -- --invariants --scenario scenarios/yield/Y06_liquidity-shortage-deposit-blocked.json
```

### What happens step by step

```
seq 0: create yield module (aave-v3, shortfall-partial preset)
       → liquidity-mode = :shortfall, available-ratio = 0.5
seq 1: deposit → REJECTED (deposit-blocked? returns true)
```

The scenario expects the deposit to be rejected (see `"expected-outcome"` in
the JSON).  No position is ever created.

### Where the guard fires

```
src/resolver_sim/yield/modules/liquid_lending.clj:44  deposit-blocked?
  — returns true when liquidity-mode ∈ #{:shortfall :frozen :paused}
src/resolver_sim/yield/modules/liquid_lending.clj:77  deposit
  — guard: (when (deposit-blocked? ...) (throw ...))
```

---

## 5.  Running the full yield-provider suite

```bash
clojure -M:run -- --invariants --suite yield-scenarios
```

This runs all seven Y-scenarios (Y01–Y07) and prints a pass/fail summary.
Each scenario replays in under 100 ms.

---

## 6.  Reducing verbosity

The replay engine logs to **stdout by default** at all levels.  To suppress
the per-step noise when running from the REPL:

```clojure
;; Filter to :warn+ only
(binding [resolver-sim.logging/*logger*
          (fn [event]
            (when (#{:warn :error} (:level event))
              (prn event)))]
  ;; run your scenario here
  ...)
```

To suppress all logging entirely:

```clojure
(binding [resolver-sim.logging/*logger* (constantly nil)]
  ...)
```

The log level is **NOT** read from any config file — it is controlled by
rebinding the `*logger*` dynamic var.  There is no level filter in the
default logger.

### Per-step debug output

Each event prints a line like:

```edn
{:ts "2026-06-05T...", :level :debug, :message "scenario/step",
 :context {:id "Y03", :seq 3, :action "set-yield-risk"}}
```

When running `clojure -M:run -- --invariants --scenario ...`, the stdout
stream is typically piped to a file.  To pipe to a file and exclude
per-step noise:

```bash
clojure -M:run -- --invariants --scenario scenarios/yield/Y03_partial-liquidity-shortfall-affected.json 2>&1 | grep -v ':level :debug'
```

---

## 7.  Key files to inspect

| What | File | Where to look |
|------|------|---------------|
| Shortfall stress calculator | `src/resolver_sim/yield/accounting.clj` | `apply-liquidity-stress` (line 122) |
| Withdraw + shortfall enrichment | `src/resolver_sim/yield/modules/liquid_lending.clj` | `withdraw` (line 128), liquidity branch (line 176) |
| Deposit shortage guard | `src/resolver_sim/yield/modules/liquid_lending.clj` | `deposit-blocked?` (line 44), `deposit` guard (line 77) |
| Yield presets | `src/resolver_sim/yield/presets.clj` | Shortfall-partial preset (line 23) |
| Risk/liquidity mode | `src/resolver_sim/yield/risk.clj` | Liquidity effective-mode (line 21) |
| Shortfall invariant | `src/resolver_sim/yield/invariants.clj` | `:yield/shortfall-splits` (line 41) |
| Y03 scenario JSON | `scenarios/yield/Y03_partial-liquidity-shortfall-affected.json` | Events + expected-outcome |
| Y06 scenario JSON | `scenarios/yield/Y06_liquidity-shortage-deposit-blocked.json` | Events + expected-outcome |
| Generator (builds shortfall scenarios) | `src/resolver_sim/generators/yield/scenario.clj` | `build-shortfall-scenario` (line 45), `build-liquidity-shortage-scenario` (line 60) |

---

## 8.  Tracing a shortfall withdrawal (cheat sheet)

To instrument a single withdrawal under shortfall, evaluate from the REPL:

```clojure
(require '[resolver-sim.yield.modules.liquid-lending :as liquid]
         '[resolver-sim.yield.registry :as yreg]
         '[resolver-sim.yield.presets :as presets])

;; 1. Build world with shortfall-partial preset
(def world (-> (yreg/init-yield-modules {:yield/module-aliases {:aave-v3 :yield.provider/liquid-lending}})
               (presets/apply-preset :yield.preset/shortfall-partial)))

(def module {:module/id :yield.provider/liquid-lending
             :module/type :yield.provider/liquid-lending
             :module/capabilities #{:deposit :withdraw :accrue :emergency-unwind :claim-deferred}
             :accounting/type :shares})

;; 2. Deposit 10 000
(def w1 (liquid/deposit world module {:owner/id "user1" :amount 10000 :token :USDC}))

;; 3. Accrue (simulate 1 year of yield)
(def w2 (liquid/accrue w1 module {:token :USDC :dt 31536000}))

;; 4. Withdraw — this triggers shortfall splitting
(def w3 (liquid/withdraw w2 module {:owner/id "user1"}))

;; 5. Inspect the position
(get-in w3 [:yield/positions "user1"])
;; → {:status :unwinding, :principal 10000,
;;     :unrealized-yield 0, :shortfall {:fulfilled-amount 10250, :deferred-amount 250, ...}}
```

Subtract the 50 bps escrow fee — the gross is ~10 500 (10 000 principal + 500
yield × 0.5 available-ratio = 250 deferred).  The position is `:unwinding`.
Call `claim-deferred` to recover the deferred amount:

```clojure
(def w4 (liquid/claim-deferred w3 module {:owner/id "user1"}))
(get-in w4 [:yield/positions "user1" :status])
;; → :withdrawn
```

---

## 9.  Common issues

**"The deposit was rejected but I expected it to succeed"** → the liquidity
mode is set to `:shortfall` (or `:frozen`/`:paused`).  Check
`deposit-blocked?` at `liquid_lending.clj:44`.  Use a baseline preset
(`:yield.preset/aave-baseline`) for normal operations.

**"The withdrawal returned less than the principal"** → the liquidity mode
set a `:haircut` (full shortfall, not partial).  Partial-liquidity
(`:failure-modes #{:partial-liquidity}`) stresses only the yield leg, not
the principal.

**"I see per-step debug output flooding stdout"** → pipe through `grep -v
':level :debug'`, or rebind `*logger*` (see §6).

**"The scenario JSON has `expected_semantics` but the replay says
`unsupported-schema-version`"** → That scenario (e.g. S83) uses the
`yield-scenario` trace format, not the standard `protocol-transition` format.
Run it via `replay-yield-scenario`, not `replay-with-protocol`.  The
`--suite yield-scenarios` flag handles both formats correctly.

**"Which yield preset should I use for my experiment?"** →

| Goal | Preset |
|------|--------|
| Normal operations, no failures | `:yield.preset/aave-baseline` |
| Shortfall on withdraw (yield-leg only) | `:yield.preset/shortfall-partial` |
| Negative APY | `:yield.preset/negative-yield-mild` |
| Oracle-stale rate freeze | `:yield.preset/oracle-stale-aave` |
| Withdrawal queue | `:yield.preset/withdrawal-queue-aave` |
