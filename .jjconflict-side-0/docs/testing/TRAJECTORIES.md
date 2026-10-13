# Trajectories

> "A trajectory is not just an array. It is a labelled time series with a
> defined semantics — a claim about how a system property evolves."

---

## Overview

The simulation uses **three active trajectory types** for falsification and
robustness audits. They are defined as vocabulary constants in
`src/resolver_sim/sim/trajectory.clj`:

```clojure
(def trajectory-types
  #{:trajectory/equity
    :trajectory/strategy-spread
    :trajectory/displacement
    :trajectory/invariant-margin})   ; later
```

---

## Trajectory Types

### 1. Equity Trajectory (`:trajectory/equity`)

**Meaning:** Per-resolver cumulative profit over epochs.

**Shape:**
```clojure
{:equity-trajectories
 {"honest-0"    [120.0 245.0 370.0 ...]  ; profit at each epoch
  "malicious-3" [190.0 420.0 680.0 ...]
  ...}
 :trajectory/meta
 {:type :trajectory/equity :epoch-count 500 :unit :profit}}
```

**Where computed:** `sim/multi_epoch.clj` via `trajectory/build-equity-trajectories`.

**Used by:** Phase AH (divergence sweep), Phase AI (escalation trap).

---

### 2. Strategy-Spread Trajectory (`:trajectory/strategy-spread`)

**Meaning:** How the balance between honest and strategic agents evolves over
time — captures equilibrium drift.

**Shape:**
```clojure
{:strategy-spread-trajectories
 [{:epoch            1
   :honest-count     80 :strategic-count 20
   :honest-mean-equity   120.0
   :strategic-mean-equity 145.0
   :spread            25.0}
  {:epoch 2 ...}]}
```

**Where computed:** `sim/trajectory.clj` via `strategy-spread-trajectory`.

**Used by:** Phase AH, any future phase auditing equilibrium stability.

**Falsification metric (Phase AH):**
- `strategic-mean-equity / honest-mean-equity ≤ 2.0` (mean check)
- `strategic-p95-equity / honest-mean-equity ≤ 2.0` (cartel-outlier check)

---

### 3. Displacement Trajectory (`:trajectory/displacement`)

**Meaning:** How the honest resolver share erodes over epochs under ring attack
or escalation pressure. Shows whether displacement is gradual, sudden,
threshold-based, or stable.

**Shape:**
```clojure
{:displacement-trajectory
 [{:epoch 1   :honest-active 80 :ring-active 5 :honest-share 0.94}
  {:epoch 2   :honest-active 78 :ring-active 5 :honest-share 0.93}
  ...
  {:epoch 200 :honest-active 29 :ring-active 5 :honest-share 0.85}]}
```

**Where computed:** `sim/trajectory.clj` via `displacement-trajectory`.
Caller is responsible for providing `[{:epoch :honest-active :ring-active}]`; the
function adds `:honest-share`.

**Used by:** Phase AI (Escalation Trap).

**Falsification metric (Phase AI):**
- `final honest-active / initial honest-count ≥ (1 - displacement-threshold)`
- Default `displacement-threshold = 0.50` (50% displacement = attack succeeds).

---

## Trajectory-Producing Phases

| Phase | File | Trajectory type(s) |
|---|---|---|
| AH — Divergence Sweep | `sim/phase_ah.clj` | `:equity-divergence`, `:strategy-spread` |
| AI — Escalation Trap | `sim/phase_ai.clj` | `:displacement`, `:equity-divergence` |

Each phase declares its trajectory classes in `:trajectory/classes` metadata and
emits them in its result map.

---

## Ring Evasion Runs (T3)

Phase AH can also be run in ring-sweep mode via `adversaries/ring_attacker.clj`.
Each ring-size trial emits a labelled result:

```clojure
{:trajectory/class      :ring-evasion
 :ring-size             5
 :detection-avoidance-rate 0.80   ; 1 - (1/ring-size)
 :equity-trajectories   {...}
 :strategy-spread-trajectories [...]}
```

---

## Output Layout

Results are written under `results/`:

```
results/phase-ah/<run-id>/
  metadata.edn        {:phase/id :phase-ah :trajectory/classes [...]}
  summary.edn         {:pass? :max-spread-mean :drift-detected-at-epoch ...}
  trajectories.edn    {:equity-trajectories {...} :strategy-spread-trajectories [...]}

results/phase-ai/<run-id>/
  metadata.edn
  summary.edn         {:pass? :displacement-rate :displacement-pattern ...}
  trajectories.edn    {:displacement-trajectory [...]}
```

---

## Relation to Traces and Fixtures

- **Traces** (`io/trace-*.clj`, `contract_model/replay.clj`) record deterministic
  state sequences for a *single scenario*. They operate at event granularity.
- **Trajectories** record aggregate or per-agent statistics across *many epochs*.
  They operate at epoch granularity.
- **Fixtures** (`sim/fixtures.clj`) seed multi-epoch runs from pre-validated
  scenario outcomes. A fixture produces initial resolver state; a trajectory
  then shows how that state evolves.

Trajectories are not stored in XTDB. They are written to `results/` as EDN via
`io/results.clj`. The `db/` layer is for live contract event records only.

---

## Layering Rules

| Namespace | May import `sim/trajectory.clj`? |
|---|---|
| `sim/*` | ✅ Yes |
| `adversaries/*` | ❌ No — import `sim/*` indirectly if needed |
| `model/*` | ❌ No |
| `contract_model/*` | ❌ No |
| `db/*`, `io/*` | ❌ No |

The `trajectory-types` set is the canonical vocabulary. Do not add new trajectory
types without also updating this document.
