# Appeal EV Calibration: Safe Appeal-Bond Window

## Classification

- **Status:** `:protocol/calibration-recommendation`
- **Kind:** `:finding-derived-guidance`
- **Source findings:** S-DR-075 (insufficient bond deterrence), S-DR-098
  (appeal under-deterred), S-DR-099 (appeal correct-blocked)
- **Model:** `resolver-sim.economics.terminal-payoff` (`appeal-ev`,
  `appeal-indifference-threshold`, `appeal-calibration-window`)

## Description

The slash-appeal decision is economically rational when the expected value of
appealing exceeds accepting the slash. The expected-value model derives a
breakeven uphold probability:

    breakeven = appeal_bond / (slash_amount + appeal_bond)

A slash-appeal system is economically **well-calibrated** when two conditions
hold at once:

1. **Deterrence** — a wrong resolver is not incentivized to appeal:
       P(uphold | wrong) = governance_error_rate  <=  breakeven
2. **Access** — a correct resolver can profitably appeal:
       P(uphold | correct) = governance_accuracy  >=  breakeven

Combining these against `breakeven = bond / (slash + bond)` gives the safe
calibration window as a ratio of appeal-bond to slash amount:

    governance_error_rate / (1 - governance_error_rate)
        <=  appeal_bond / slash_amount
        <=  governance_accuracy / (1 - governance_accuracy)

## Safe window for common governance calibrations

| Governance calibration (accuracy, error-rate) | safe appeal-bond / slash | at slash-bps 2500 |
|---|---|---|
| `(0.7, 0.3)` default | `[0.429, 2.333]` | appeal-bond-bps `[1071, 5833]` |
| `(0.8, 0.2)` lenient governance | `[0.250, 4.000]` | appeal-bond-bps `[625, 10000]` |
| `(0.6, 0.4)` noisy governance | `[0.667, 1.500]` | appeal-bond-bps `[1666, 3749]` |

The window widens as governance gets more accurate relative to its error rate;
it narrows (and eventually inverts) as governance becomes noisy.

## Failure regimes outside the window

**Below the lower bound (under-deterred).** At `appeal-bond-bps < ~1071` for
default governance (bond below ~43% of the slash amount), a wrong resolver's
appeal has positive expected value. S-DR-098 demonstrates this at
`appeal-bond-bps 300`: breakeven `0.107` is below the error-rate `0.30`, so
frivolous appeals are economically attractive. This extends the S-DR-075
finding to the appeal stage.

**Above the upper bound (correct-blocked).** At `appeal-bond-bps > ~5833`
for default governance (bond above ~233% of the slash amount), even a correct
resolver's appeal has negative expected value, so legitimate appeal access is
economically blocked. S-DR-099 demonstrates this at `appeal-bond-bps 7000`:
breakeven `0.737` exceeds the accuracy `0.70`.

**Both-can-appeal.** Between the deterrence boundary and the point where the
breakeven clears the error-rate, both correct and wrong resolvers can
profitably appeal. This is not a correctness bug, but it means the appeal bond
alone does not separate the two — governance accuracy is doing the work.

## Recommended calibration

For the default governance assumption (`accuracy 0.7, error-rate 0.3`), set
the appeal bond **between ~43% and ~233% of the reversal slash amount**:

- **Minimum `appeal-bond-bps`** = `reversal-slash-bps * 0.43` (round up). At
  `reversal-slash-bps 2500` this is `1071` (≈ 10.7% of escrow).
- **Maximum `appeal-bond-bps`** = `reversal-slash-bps * 2.33`. At
  `reversal-slash-bps 2500` this is `5833` (≈ 58% of escrow).

Formalize as a parameter pair so the window is checked rather than hard-coded:
derive both bounds from the declared governance calibration and slash rate
(see `appeal-calibration-window`), and surface a warning when a deployment's
`appeal-bond-bps` falls outside the window.

## Clojure model references

- `appeal-ev` — EV(appeal) vs EV(no-appeal), breakeven, decision margin
  (`src/resolver_sim/economics/terminal_payoff.clj`)
- `appeal-indifference-threshold` — per-regime verdict (`:safe`,
  `:correct-blocked`, `:wrong-incentivized`, `:both`)
  (`src/resolver_sim/economics/terminal_payoff.clj`)
- `appeal-calibration-window` — safe appeal-bond/slash window and bps bounds
  (`src/resolver_sim/economics/terminal_payoff.clj`)
- Scenarios: `S-DR-097` (well-calibrated), `S-DR-098` (under-deterred),
  `S-DR-099` (correct-blocked)
- Equilibrium check: `:appeal-decision-rationality`
  (`protocols_src/resolver_sim/protocols/sew/equilibrium.clj`)
- Benchmark claim: `:claim/appeal-decision-rationality`
  (`benchmarks/packs/sew/reversal-slashing-v1.edn`)

## Caveats

- The governance accuracy / error-rate are **model assumptions, not empirical
  measurements**. The purpose is to identify parameter relationships
  (breakeven surfaces), not to predict real-world appeal rates.
- The protocol fee on the appeal bond shifts the breakeven upward
  (`bond / (slash + bond - fee)`); deployments with a non-zero
  `appeal-bond-protocol-fee-bps` should widen the lower bound accordingly.
- Where the reversal slash is itself the product of an appealed/reversed
  ruling (Track 2), the same window applies to the reversal-reviewer's appeal
  of their own slash.
