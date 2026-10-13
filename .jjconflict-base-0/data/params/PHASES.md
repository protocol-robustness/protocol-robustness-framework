# Dispute Resolution Phases

This directory contains parameter configurations for different DR phases. The simulation model supports all phases via parameters - no code changes needed.

## Quick Start

**Most people want DR3 (the full system):**

```bash
clojure -M:run -- -p data/params/baseline.edn
```

## Phase Comparison

| Parameter   | DR1 (Fee-Only) | DR2 (Reputation) | DR3 (Full)   |
| ----------- | -------------- | ---------------- | ------------ |
| Fee         | 1.5%           | 1.5%             | 2.5%         |
| Bond        | 0%             | 5%               | 10%          |
| Slashing    | None           | 1.5×             | Progressive  |
| Reputation  | No             | Yes (quadratic)  | Yes          |
| L2 Backstop | No             | No               | Yes (Kleros) |

## Running Each Phase

### DR3 (Full System) - Default

```bash
clojure -M:run -- -p data/params/baseline.edn
```

- 10% resolver bond
- 2.5% fee
- Progressive slashing (1.5× → 2× → 3×)
- Kleros L2 backstop
- Most thoroughly tested

### DR1 (Fee-Only)

```bash
clojure -M:run -- -p data/params/dr1-fee-only.edn
```

- No bonds required
- No slashing risk
- Resolvers earn 1.5% fee
- Single appeal allowed
- **Result**: Malicious earns same as honest (ratio ≈ 1.0)

### DR2 (Reputation + Bond)

```bash
clojure -M:run -- -p data/params/dr2-reputation.edn
```

- 5% resolver bond required
- 1.5× slashing on wrong decisions
- Reputation-weighted voting
- **Result**: Malicious earns ~20% of honest (ratio ≈ 5.0)

## Moving Features Between Phases

Features can be moved by adjusting parameters. For example, to add reputation to DR1:

```edn
;; In dr1-fee-only.edn, add:
:reputation-enabled? true
:reputation-initial 500
:reputation-decay-bps-per-month 100
```

Key parameters:

| Parameter            | DR1   | DR2  | DR3         | Description                  |
| -------------------- | ----- | ---- | ----------- | ---------------------------- |
| `:resolver-fee-bps`  | 150   | 150  | 250         | Fee in bps (1.5% = 150)      |
| `:resolver-bond-bps` | 0     | 500  | 1000        | Bond required (5% = 500)     |
| `:slash-multiplier`  | 0     | 1.5  | progressive | Slash penalty (0 = disabled) |
| `:allow-slashing?`   | false | true | true        | Enable/disable slashing      |
| `:l2-detection-prob` | 0     | 0    | >0          | Kleros backstop probability  |

## Oracle Fixtures and Notebook Tracing

**MC-only:** `:oracle-fixture` affects the Monte Carlo dispute model (`stochastic/dispute`).
Live replay (`contract_model/replay.clj`, `protocols/sew/resolution`) does not read it;
reversal slashing in replay remains deterministic when a verdict is overturned.

`:on-exhaustion` (`:throw`, `:repeat-last`, `:cycle`) controls stochastic oracle
fixtures only. `:repeat-last` preserves MC trial continuity after scripted rolls
are exhausted; it does not model replay determinism and is not validated by
`replay-with-protocol`. For replay-comparable evidence, prefer `:throw` with fully
specified `:rolls`. See `docs/architecture/ORACLE_FIXTURE_EXHAUSTION.md`.

The stochastic dispute model supports oracle fixture overrides for deterministic
experiments and notebook evidence capture. Param files are validated at load time
(`io/params` `validate-and-merge`): invalid `:scope`, unknown per-kind roll keys,
conflicting legacy keys, and orphan `:oracle-roll-sequence` / `:oracle-mode` raise errors.

### Fixture modes

```edn
:oracle-fixture {:mode :stochastic}
:oracle-fixture {:mode :static-no-slash}
:oracle-fixture {:mode :static-always-detect}
:oracle-fixture
{:mode :fixed-roll-sequence
 :rolls {:fraud-detection [0.99 0.01]
         :timeout-detection [0.75]
         :reversal-detection [0.20]
         :l2-detection [0.50]
         :default [0.90]}
 :scope #{:detection}
 :on-exhaustion :throw}   ; default for evidence-quality runs
```

| Use case | Recommended `:on-exhaustion` |
|----------|------------------------------|
| Regression / replay-comparable evidence | `:throw` |
| Exploratory MC sweeps | `:repeat-last` acceptable |
| Long MC with periodic script | `:cycle` |

For `:fixed-roll-sequence`, `:rolls` can be either:

- a vector (shared sequence for all detection roll kinds), or
- a map (independent per-kind sequences with optional `:default` fallback).

### `:fixed-or` shorthand

`:fixed-or` is an alias for a fixed-roll oracle fixture (mode keyword `:fixed-or`
normalizes to `:fixed-roll-sequence`):

```edn
;; roll vector only
:fixed-or [0.99 0.01 0.75]

;; or full fixture fields (mode optional)
:fixed-or {:rolls {:fraud-detection [0.99 0.01]}
          :on-exhaustion :throw}

;; legacy flat mode alias
:oracle-mode :fixed-or
:oracle-roll-sequence [0.99 0.01]
```

`:fixed-or` must not be combined with `:oracle-fixture {:rolls ...}` — providing
both raises a validation error. Use `:fixed-or` alone for a shared deterministic
stream, or `:oracle-fixture {:rolls {kind [...]}}` for per-kind fixtures.
Non-roll keys (`:scope`, `:on-exhaustion`) on `:oracle-fixture` may coexist with
`:fixed-or`.

Do not combine `:oracle-fixture {:mode :stochastic}` with a non-empty
`:oracle-roll-sequence` — the sequence is ignored and load validation fails.

### Roll consumption order (shared vector)

When `:rolls` is a single vector, every in-scope detection kind draws from one queue
in the order below (skipped steps do not consume a roll):

1. `:reversal-detection` — after wrong verdict, appeal, and `decision-reversed?`
2. `:pending-evidence` — after reversal slash, if `:new-evidence-probability` > 0
3. `:fraud-detection` — malicious wrong verdict only
4. `:timeout-detection` — lazy or malicious
5. `:l1-detection` — any wrong verdict
6. `:l2-detection` — appealed wrong verdict, if `:l2-detection-prob` > 0

**Fixture-controlled when `:appeal` is in `:scope`:** `:l1-reversal`, `:l2-escalation`,
`:l2-reversal` (compared to `:p-l1-reversal`, `:p-l2-escalation`, `:p-l2-reversal`).

**Still trial `:rng` only:** verdict correctness and whether an appeal is filed.
Script those via `:force-strategy` and `:appeal-probability-if-wrong`.

Full scripted trial control: `data/params/control-oracle-full-trial.edn` (`:scope
#{:detection :appeal}`). Detection-only: `control-oracle-fixed-roll-sequence.edn`.

After load, params include `:oracle-effective` (canonical merged fixture). Trials call
`prepare-oracle-params` for fresh cursors per dispute.

Prefer per-kind `:rolls` maps so each mechanism has its own sequence.

### Notebook roll-trace metadata

Enable trace output on a params file:

```edn
:oracle-roll-trace-enabled? true
```

When enabled, each dispute trial result includes `:oracle-roll-trace`, a vector
of entries:

```edn
{:roll/kind :fraud-detection
 :roll/source :fixed-roll-sequence
 :roll/value 0.01
 :roll/index 1
 :roll/count 3
 :threshold 0.25
 :detected? true}
```

After sequence exhaustion (`:repeat-last` / `:cycle`), entries also include
`:roll/exhausted? true`, `:roll/on-exhaustion`, and `:roll/repeated-index` or
`:roll/cycled-index`.

This is intended for notebook analysis and evidence artifacts where you need to
show exactly why a detection decision fired (or did not fire).

## Architecture

```
src/resolver_sim/
├── model/
│   ├── dispute.clj      # Core dispute resolution logic (all phases)
│   ├── economics.clj    # Fee/bond/slashing calculations
│   └── types.clj        # Parameter schema and validation
└── sim/
    └── batch.clj        # Trial runner (handles all phase configs)

data/params/
├── baseline.edn         # DR3: Full system (default)
├── dr1-fee-only.edn    # DR1: Fee-only
├── dr2-reputation.edn  # DR2: Bonds + reputation
└── ...
```

The model is designed so that:

1. **Main simulation = DR3** (most people interested in full version)
2. **DR1/DR2 = parameter variations** (for interim releases)
3. **Easy to move features** between phases by adjusting params

## Philosophy

- DR1: Prove fee-only model works before adding complexity
- DR2: Add bonds + reputation to deter malicious behavior
- DR3: Full decentralization with all safeguards

Start with DR1 parameters to understand baseline incentives, then compare with DR2/DR3 to see the effect of each additional security layer.
