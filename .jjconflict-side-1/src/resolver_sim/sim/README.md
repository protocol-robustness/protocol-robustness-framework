# sim/ — Simulation Layer

This namespace sits between the functional core and the CLI. It contains two
distinct sub-systems that are **both part of the simulation layer** but answer
different questions:

---

## Engine 1 — Monte Carlo (`economic/`, `adversarial/`, `governance/`)

Phases O, P, Q, R, T, U, V, W, X, Y, Z, AA–AI.

**Question:** *Is honest participation more profitable than malice across the
parameter space?*

**Mechanism:** Samples resolver payoffs as probability distributions over N
trials. No state machine; no per-event accounting. Uses `stochastic/` for
the payoff functions.

**Calibration:** `stochastic/economics.clj` uses the same fee/bond formula
(`quot (* escrow fee-bps) 10000`) as `protocols/sew/accounting.clj`. They are
independently implemented but numerically identical by design.

**Output:** Dominance ratios, expected-value tables, parameter sensitivity
surfaces.

---

## Engine 2 — Replay / Invariant (`fixtures.clj`, `minimizer.clj`, `phase_z_scenarios.clj`)

**Question:** *Does this specific attack sequence violate a protocol guarantee?*

**Mechanism:** Replays deterministic event sequences through the Sew state
machine (`contract_model/replay`), checking all 31 invariants at every step.
Produces machine-readable JSON traces (`data/fixtures/traces/`).

**Output:** Pass/fail per invariant, minimal counterexample traces.

---

## How they relate

The two engines form a layered evidence argument:

```
MC result: "attack X is unprofitable in expectation (dominance = 0.3)"
       ↓
Raises question: "what does X look like when it succeeds?"
       ↓
Replay result: "trace s08 shows the exact sequence and which invariants hold"
```

A Monte Carlo phase **failing** (dominance < 1) identifies a parameter regime
worth probing with a replay trace. A replay invariant **violation** that MC
doesn't predict means the stochastic model is incomplete.

---

## Shared infrastructure

- `engine.clj` — `run-parameter-sweep`, `make-result`, `print-phase-header`
- `batch.clj` — aggregate N trials into summary statistics
- `sweep.clj` — parameter-space sweep runner
- `multi_epoch.clj` — multi-epoch reputation simulation (shared by Phase J and replay)
- `trajectory.clj` — equity/spread/displacement trajectory helpers
