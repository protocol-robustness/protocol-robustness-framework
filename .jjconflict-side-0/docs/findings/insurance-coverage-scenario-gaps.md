# Insurance & Coverage — Scenario Gaps

## Classification

- **Status:** `:analysis/scenario-gaps`
- **Kind:** `:test-coverage`
- **Scope:** senior-bond coverage, slash distribution, insurance pool activation

## Current State

### What exists

| Scenario / Test | What it covers | Limits |
|---|---|---|
| **S39** (`s39-dr3-senior-coverage-delegation`) | Senior bond registration + junior delegation | Registration/delegation capacity path; does not slash. |
| **S108–S113** | End-to-end delegated coverage consumption, exhaustion, multi-junior, gradual exhaustion, over-capacity rejection, and insurance-bps override | Canonical invariant scenarios now exercise the senior-bond coverage path and insurance accounting. |
| **Slashing test** `slash-distribution-tracks-retained-reserves` | Bond-distribution tracking after slash | Unit test, not a scenario. No coverage path. |
| **Waterfall simulator** (`waterfall.clj`, `delegation.clj`) | Senior/junior pool adequacy, coverage consumption | Standalone research model. Uses separate data model — not connected to Sew protocol state or invariants. |

### What the Sew protocol supports (but nothing exercises)

The protocol has working actions for a coverage pipeline:

```
register-senior-bond  →  [:senior-bonds addr {:coverage-max N :reserved-coverage 0}]
delegate-to-senior    →  [+:reserved-coverage N]  (capped at coverage-max)
```

And an invariant that checks:
And there are invariants that check:

```
reserved-coverage <= coverage-max      (invariants.clj:1281)
slash-distribution-consistent?         (invariants.clj)
```

The delegated slash path now consumes `:reserved-coverage` in `registry/slash-resolver-stake` before distributing the slash through `accounting/distribute-slashed-funds`. The canonical S108–S113 scenarios verify consumption, exhaustion, multi-junior isolation, gradual depletion, rejection at the coverage cap, and the insurance allocation override.

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

### Gap A (RESOLVED): Coverage pool consumption

S108 now chains senior registration, junior delegation, fraud slash proposal/execution, senior coverage consumption, insurance allocation, and invariant checks. The implementation debits `[:senior-bonds <senior-addr> :reserved-coverage]` in `registry/slash-resolver-stake` before distributing the slash.

### Gap B (RESOLVED): End-to-end slash-through-coverage

S108 covers the primary path:

```
1. senior registers bond with coverage-max = 10000
2. junior delegates coverage = 5000
3. junior incurs a 3000 fraud slash
4. senior coverage is consumed and bond-distribution :insurance is credited
5. slash-distribution-consistent? and senior-coverage-not-exceeded? hold
```

S109–S113 extend this with coverage exhaustion, multiple juniors, gradual depletion, over-capacity rejection, and insurance-bps configuration.

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
