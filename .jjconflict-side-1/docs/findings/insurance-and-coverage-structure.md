# Insurance and Coverage Structure

## Classification

- **Status:** `:documentation/reference`
- **Kind:** `:economic-mechanism`
- **Scope:** slash distribution, senior-bond coverage, reversal-slashing credit

## Overview

The protocol has two distinct insurance-related concepts:

1. **Slash distribution** — how slashed bond funds are split among insurance, protocol, retained reserves, and challenger bounties.
2. **Senior bond coverage** — senior resolvers providing a coverage pool for junior resolver losses during fraud cascades.

These are independent mechanisms. Slash distribution is always active (with configurable BPS splits). Senior bond coverage is disabled by default and must be explicitly activated via governance actions.

---

## 1. Slash Distribution

### Source: `sew/economics.clj`

The `calculate-slashing-distribution` function allocates slashed bond funds into three buckets:

```
insurance-cut-bps   → insurance pool  (default 5000 = 50%)
protocol-retained-bps → protocol pool (default 3000 = 30%)
retained             → retained reserves (default 20%)
```

A challenger bounty is deducted equally from the insurance and protocol portions.

### Runtime overrides

The actual BPS values are read from world `:params` at slash time:

```clojure
;; accounting.clj:843
split-opts (select-keys (:params world) [:insurance-cut-bps :protocol-retained-bps])
```

If not set in `:params`, the defaults in `calculate-slashing-distribution` apply (50/30/20).

### Economic policy presets

The `ECONOMIC-POLICIES` map in `economics.clj` defines three governance bands:

| Policy | capacity-multiplier | insurance-cut-bps | alpha-bps |
|---|---|---|---|
| conservative | 1.0 | 8000 (80%) | 500 |
| balanced | 1.5 | 5000 (50%) | 1000 |
| aggressive | 4.0 | 2000 (20%) | 3000 |

---

## 2. Senior Bond Coverage

### Source: `sew.clj` (actions), `types.clj` (state)

Senior resolvers can register a bond with a `coverage-max` limit. Junior resolvers can then delegate a portion of their coverage to a senior.

### Disabled by default

In `register-senior-bond` (`sew.clj:938`):

```clojure
coverage-max (get p :coverage-max 0)
```

If no governance action sets `coverage-max` to a positive value, senior bonds can provide **zero** coverage. Juniors attempting to delegate will fail with `:senior-coverage-exceeded` because the delegate call at `sew.clj:954` enforces:

```clojure
(if (> new-reserved max-coverage)
  (t/fail :senior-coverage-exceeded)
  ...)
```

With `max-coverage = 0`, any positive delegation is rejected.

### Activation path

To activate senior bond coverage, governance must:
1. Call `register-senior-bond` with a positive `coverage-max`.
2. The invariant `senior-coverage-not-exceeded?` (`invariants.clj:1281-1293`) enforces that `reserved-coverage <= coverage-max` at all times.

---

## 3. Reversal Slashing — No Clawback

### Source: `sew/resolution.clj`

When a resolver is vindicated on appeal after having been slashed via reversal (`reverse-reversal-slash-on-vindication` at line 347), the resolver's stake is credited back:

```clojure
(update-in [:resolver-stakes resolver] (fnil + 0) amount)
(update-in [:resolver-slash-total resolver] (fnil - 0) amount)
```

However, the slashed funds have **already been distributed** to insurance, protocol, and retained-reserve pools. They are **not clawed back**. The comment at line 355 documents this:

> *"The slashed funds have already been distributed to insurance/protocol/burned pools — this does NOT claw them back."*

### `slash-credit-liabilities` accumulator

Added in `types.clj:266` and `resolution.clj:385` to track the protocol's outstanding restoration obligation:

```clojure
:slash-credit-liabilities {}   ; {addr nat-int}
```

Each vindication appends:

```clojure
(update-in [:slash-credit-liabilities resolver] (fnil + 0) amount)
```

This makes the liability auditable. The `slash-distribution-consistent?` invariant (`invariants.clj:1246`) includes this term in the accounting equation:

```
insurance + protocol + retained-slash-reserves + credit-liabilities = bond-slashed
```

### Track 2 (pending) reversal slashes

Track 2 slashes have their own governance appeal path and are never auto-resolved when an escrow finalizes (`cleanup-orphaned-slashes` at `lifecycle.clj:614` only removes entries with expired appeal deadlines, not all orphans for terminal escrows). An open issue exists to mark pending slashes as `:moot` when their workflow escrow reaches a terminal state.

---

## 4. Accounting Invariant

### Source: `sew/invariants.clj`

`slash-distribution-consistent?` (invariant 24) enforces that every slashed bond unit is accounted for somewhere. The updated equation (post-`slash-credit-liabilities`):

```clojure
(let [bd          (:bond-distribution world)
      retained    (:retained-slash-reserves world 0)
      credit      (reduce + 0 (vals (:slash-credit-liabilities world {})))
      dist-total  (+ (:insurance bd 0) (:protocol bd 0) retained credit)
      slash-total (reduce + 0 (vals (:bond-slashed world {})))]
  (= dist-total slash-total))
```

Without `slash-credit-liabilities`, the invariant would fail after any reversal-vindication cycle because the credited stake (credited from `bond-slashed` but re-added to `resolver-stakes`) would not appear in any distribution bucket.

---

## Summary of Key Code Locations

| Mechanism | File | Key Function/Location |
|---|---|---|
| Slash distribution calculation | `sew/economics.clj` | `calculate-slashing-distribution` (line 78) |
| Slash distribution execution | `sew/accounting.clj` | `distribute-slashed-funds` (line 831) |
| Senior bond registration | `sew.clj` | `register-senior-bond` action (line 932) |
| Senior bond delegation | `sew.clj` | `delegate-to-senior` action (line 942) |
| Senior coverage invariant | `sew/invariants.clj` | `senior-coverage-not-exceeded?` (line 1281) |
| Reversal slash vindication | `sew/resolution.clj` | `reverse-reversal-slash-on-vindication` (line 347) |
| Credit liability accumulator | `sew/types.clj` | `:slash-credit-liabilities` (line 266) |
| Credit liability write | `sew/resolution.clj` | `update-in [:slash-credit-liabilities]` (line 385) |
| Distribution invariant | `sew/invariants.clj` | `slash-distribution-consistent?` (line 1234) |
