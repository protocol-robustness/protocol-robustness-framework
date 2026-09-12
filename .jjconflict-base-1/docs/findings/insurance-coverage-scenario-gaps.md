# Insurance & Coverage — Scenario Gaps

## Classification

- **Status:** `:analysis/scenario-gaps`
- **Kind:** `:test-coverage`
- **Scope:** senior-bond coverage, slash distribution, insurance pool activation

## Current State

### What exists

| Scenario / Test | What it covers | Limits |
|---|---|---|
| **S39** (`s39-dr3-senior-coverage-delegation`) | Senior bond registration + junior delegation | Stops after delegation. No slash event occurs. Covers `senior-coverage-not-exceeded?` invariant only. |
| **Phase K tests** | Auto-slashing + manual fraud-slash → `bond-distribution :insurance` | Slashes flow through resolver stake, not through senior-bond coverage. Insurance accrual works but coverage mechanism is never exercised. |
| **Slashing test** `slash-distribution-tracks-retained-reserves` | Bond-distribution tracking after slash | Unit test, not a scenario. No coverage path. |
| **Waterfall simulator** (`waterfall.clj`, `delegation.clj`) | Senior/junior pool adequacy, coverage consumption | Standalone research model. Uses separate data model — not connected to Sew protocol state or invariants. |

### What the Sew protocol supports (but nothing exercises)

The protocol has working actions for a coverage pipeline:

```
register-senior-bond  →  [:senior-bonds addr {:coverage-max N :reserved-coverage 0}]
delegate-to-senior    →  [+:reserved-coverage N]  (capped at coverage-max)
```

And an invariant that checks:

```
reserved-coverage <= coverage-max      (invariants.clj:1281)
```

But there is **no code path that consumes `:reserved-coverage`** during a slash event. The `distribute-slashed-funds` function (`accounting.clj:831`) distributes slashed resolver stake to `:bond-distribution` buckets but never reads or decrements `:senior-bonds`. Coverage is **ornamental** — tracked but never used.

### What the waterfall research model has (but Sew protocol doesn't wire)

The `delegation.clj` research model implements real coverage consumption:

| Action | Effect |
|---|---|
| `junior bond consumed` | Junior pays first loss |
| `coverage pool consumed` | Senior's reserved-coverage pays remaining |
| `senior bond consumed` | Senior's own bond is slashed as last resort |

This logic exists only in the research simulation. The Sew protocol actions (`register-senior-bond`, `delegate-to-senior`) populate the state but no slashing function reads it.

---

## Scenario Gaps (Prioritized)

### Gap A (CRITICAL): Coverage pool never consumed

The biggest gap. S39 proves registration + delegation work, and Phase K tests prove `bond-distribution :insurance` accrues during slashes. But **no scenario chains them together** because the protocol has no function to consume coverage.

**Required fix before scenario can pass**: Wire `distribute-slashed-funds` (or a new function) to decrement `[:senior-bonds <senior-addr> :reserved-coverage]` when a delegated junior is slashed, and credit the insurance bucket proportionally. Without this, any "slash-while-delegated" scenario will pass trivially (since coverage is never consumed) while the contract-level economic safety path remains untested.

### Gap B (HIGH): End-to-end slash-through-coverage

Once the coverage wire exists, add a scenario:

```
1. senior registers bond with coverage-max = 10000
2. junior delegates coverage = 8000
3. junior incurs slash (fraud) → coverage consumed from senior → bond-distribution :insurance credited
4. invariant: slash-distribution-consistent? holds (insurance + protocol + retained + liabilities = total slashed)
5. invariant: senior-coverage-not-exceeded? holds (reserved <= coverage-max after consumption)
```

This is the primary economic safety path that every delegated resolver setup relies on.

### Gap C (HIGH): Coverage exhaustion

```
1. senior registers with coverage-max = 5000
2. junior delegates coverage = 5000
3. slash amount exceeds 5000 → senior coverage exhausted, remaining comes from junior's own stake
4. verify correct split: coverage covers 5000, junior stake covers remainder
```

### Gap D (MEDIUM): Multi-junior delegation to single senior

```
1. senior registers with coverage-max = 10000
2. junior-A delegates 4000, junior-B delegates 4000
3. slash hits junior-A → coverage consumed for junior-A only (4000)
4. separate slash hits junior-B → coverage consumed for junior-B (4000)
5. total consumed = 8000, within coverage-max
```

### Gap E (MEDIUM): Senior coverage exhaustion with subsequent slash

```
1. senior coverage-max = 5000, junior delegates 5000
2. slash #1 consumes 3000 from coverage (reserved → 2000)
3. slash #2 consumes 2000 from coverage (reserved → 0)
4. slash #3 has no coverage left → falls fully on junior stake
```

### Gap F (MEDIUM): `senior-coverage-exceeded` error path

```
1. senior registers with coverage-max = 5000
2. junior delegates 5000 (hits max)
3. junior attempts to delegate another 1000 → :senior-coverage-exceeded
```

### Gap G (MEDIUM): `insurance-cut-bps` governance override

```
1. governance sets :insurance-cut-bps to 8000 (80%) in protocol params
2. slash event occurs → verify distribution is 80/10/10 not the default 50/30/20
```

### Gap H (LOW): `senior-not-registered` error path

```
1. junior attempts delegate_to_senior with unregistered senior-addr
2. → :senior-not-registered error
```

### Gap I (LOW): Reversal vindication with senior coverage active

```
1. senior registered, junior delegated, slash occurs → coverage consumed
2. slash is appealed and reversed (vindicated)
3. slash-credit-liabilities records the protocol obligation
4. verify invariant: slash-distribution-consistent? holds with credit-liabilities term
```

---

## Blocking Issue

Gaps A–E all depend on the protocol's ability to **consume senior coverage during a slash**. Currently `distribute-slashed-funds` (`accounting.clj:831`) and `slash-resolver-stake` (`registry.clj:154`) have no knowledge of senior bonds. The consumption logic exists only in the research model (`delegation.clj`).

To unblock these scenarios, the slashing path needs a new step: when slashing a resolver that has delegated to a senior, first draw from `[:senior-bonds <senior> :reserved-coverage]` before drawing from `[:resolver-stakes resolver]`. The insurance distribution would then credit the senior's coverage consumption to `:bond-distribution`.

This is the same gap as the "senior bonds are ornamental" finding from the previous audit. The action handlers exist, the state exists, the invariant exists, but the consumption wire does not.

---

## Summary

| Priority | Gap | Scenario needed | Blocks |
|---|---|---|---|
| CRITICAL | Coverage pool never consumed | Wire `distribute-slashed-funds` to read `:senior-bonds` | All coverage scenarios |
| HIGH | End-to-end slash-through-coverage | Chain S39 + Phase K slash | Primary safety path |
| HIGH | Coverage exhaustion | Slash > remaining coverage | Edge case safety |
| MEDIUM | Multi-junior delegation | Two juniors → one senior → two slashes | Concurrency safety |
| MEDIUM | Gradual exhaustion | Multiple slashes depleting pool | Sequential safety |
| MEDIUM | `senior-coverage-exceeded` error | Over-delegation reject | Error path |
| MEDIUM | `insurance-cut-bps` override | Governance param change | Configurability |
| LOW | `senior-not-registered` error | Unregistered delegation reject | Error path |
| LOW | Reversal vindication + coverage | Appeal chain with active coverage | Liability audit |
