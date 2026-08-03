# Protocol Robustness Framework: State, Interpretation, and Roadmap

*A plain-language guide for protocol designers, researchers, and grant reviewers.*

---

## What this system is

Sew is a dispute-resolution protocol for on-chain escrow. When a buyer and seller
disagree, a resolver is called in to make a binding decision. The protocol must ensure
that the resolver behaves honestly — and that an attacker cannot game the outcome,
exhaust the resolver network, or drain the insurance pool.

This repository contains **two complementary simulation engines** that test whether
the protocol actually achieves those goals.

---

## The two engines

### Engine 1 — Deterministic replay (the "proof engine")

Think of this as a **formal stress-test**. You give it a precise scenario — a sequence
of actions taken by specific actors — and it runs the protocol logic step by step,
checking 30+ invariants after every action. An invariant is a property that must
*always* hold: "no escrow can be released twice," "the contract can never owe more
than it holds," "a resolver without authority cannot finalise a dispute."

There are 116 built-in scenarios (S01–S116) covering the full lifecycle: honest
resolution, adversarial manipulation, governance failure, liveness under no-resolver
conditions, and Kleros fallback. If a scenario produces a single invariant violation,
the run fails.

**What it proves:** Specific, named attack sequences either succeed or fail under the
current protocol logic. This is *mechanistic* evidence — not statistical.

**What it cannot prove:** That the scenarios chosen cover every possible attack. It
tests what you put in front of it.

### Engine 2 — Monte Carlo simulation (the "economic pressure test")

Think of this as running **thousands of disputes simultaneously** with randomised
actors. Resolvers are assigned strategies — honest, lazy, malicious, or collusive —
and the engine measures whether the economic payoffs make honesty rational. Does an
honest resolver consistently earn more than a malicious one? Does governance capture
become profitable above a certain coalition size?

There are 23 Monte Carlo phases (O through AI) sweeping across different hypotheses:
market-exit dynamics, governance threshold fragility, adversarial profitability,
ring-attack stability, multi-epoch reputation decay.

**What it proves:** That under a *distribution of behaviours*, the protocol's economic
incentives point in the right direction. This is *statistical* evidence.

**What it cannot prove:** That no individual attack path is profitable in isolation.
That requires the deterministic engine.

---

## How the two engines relate

The engines share the same fee, bond, and slashing formulas — verified by a suite of
cross-engine calibration tests (over 700 assertions). When the Monte Carlo model says
"malicious profit is negative on average," it is using identical arithmetic to the
contract model. A calibration failure would surface as a test failure before it reached
any results.

The engines are **not redundant**. They address different threat models:


| Question                                                    | Engine               |
| ----------------------------------------------------------- | -------------------- |
| *Can this specific attack succeed?*                         | Deterministic replay |
| *Is fraud economically rational on average?*                | Monte Carlo          |
| *Does the state machine follow the spec?*                   | Deterministic replay |
| *Does governance capture become viable at scale?*           | Monte Carlo          |
| *Are funds conserved across all transitions?*               | Deterministic replay |
| *Do honest resolvers consistently out-earn malicious ones?* | Monte Carlo          |


Think of them as a double-entry bookkeeping check: the same economic reality, measured
from two directions.

---

## Current state

### What is solid

**Deterministic engine — production-quality.**
The 116-scenario invariant suite is the most reliable part of the codebase. Scenarios
are named, documented, and independently runnable. The 30+ invariant predicates mirror
the intended on-chain guards. This output is suitable for inclusion in a grant
application or audit brief.

**Cross-engine calibration — verified.**
Fee, bond, reversal-slash, and appeal-bond formulas are identical across both engines
(15 test groups, 762 assertions). Results from the two engines are commensurable.

**Governance and liveness failure classes — demonstrated.**
Three distinct failure classes have reproducible evidence with named scenarios:

- *Security*: adversarial pressure (S01 vs S08)
- *Governance*: resolver-role takeover mid-dispute (`governance-decay-exploit`)
- *Liveness*: fund-lock vs Kleros fallback (S17 vs S18)

**Monte Carlo phases — functional for directional analysis.**
Phases O (market-exit), P-lite (adversarial), AA (governance), and the full phase
suite provide consistent directional signals across thousands of parameter combinations.

### What is recent (and changes what results mean)

The Monte Carlo model has been updated with four accuracy improvements:

1. **Fraud upside is now modellable — and the corrected model produces the most
  important finding in the repository.** The original model only counted the resolver's
   *protocol income* (fee minus bond loss). It ignored the fact that a malicious
   resolver who is not caught can redirect the full escrow — an upside roughly 665×
   larger than the fee. With this correction, two results follow immediately:
  - **At calibrated fraud-success-rate (0.22)**, malice EV (201) already exceeds
  honest EV (142). The original "honest dominates" result was pointing in the wrong
  direction.
  - **The breakeven detection rate is 70%.** To deter fraud through bond-and-detection
  alone (ignoring the state machine), detection probability must be ≥ 70%.
  Current baseline: 10%. Required bond at current detection: **21× current levels**
  (88,650 wei per 10,000 wei escrow; current: 4,250).
   **What this means for the protocol:** The state machine is not just good design —
   it is *load-bearing* for economic security. The invariant suite constrains the
   effective fraud-success-rate to near zero for all 41 modelled attack vectors.
   Bond deterrence alone is insufficient at current parameter levels.
   The new `fraud-success-rate` parameter (default 0.0) allows this to be set
   explicitly. At the default, all existing results are unchanged.
2. **Slash proceeds are now tracked.** Every slashing event now records how the
  penalty is split between the insurance pool, the protocol, and the burn address.
   This enables insurance solvency questions to be asked of the Monte Carlo output.
3. **Appeal economics are modellable.** Honest resolvers can now optionally earn a
  share of failed challenge bonds, making the appeal incentive structure more
   realistic. Off by default.
4. **Collusive gain rate is now parameter-driven.** The original model used a
  hard-coded mathematical formula for collusion bonuses. This is now a calibratable
   input, with the original formula as the fallback.
5. **Governance capacity boundary — calibrated result, thin margin.**
   Phase AA ("governance as adversary") tests whether attackers can exceed a 20% attacker
   win rate via selective enforcement gaming. The harness is fully parameter-driven
   (`data/params/phase-aa-governance.edn`): no remediation floor is baked into the Phase AA
   scenarios, and the biased-governance scenario uses the `:bias` tier map directly. Results
   across 5 scenarios at the calibrated win probabilities (`base-win-prob = 0.22` from the
   9/41 deterministic invariant suite, `reviewed-win-prob = 0.03`):

   | Scenario                                       | Attacker win rate | Result       |
   | ---------------------------------------------- | ----------------: | ------------ |
   | High capacity, naive attacker                  |             9.2% | ✅ SAFE       |
   | Limited capacity (cap=3), learning attacker    |            16.8% | ✅ SAFE       |
   | Biased governance (focus on high-value)        |            18.4% | ✅ SAFE       |
   | Low-value flooding (cap=2)                     |            17.2% | ✅ SAFE       |
   | Below-minimum capacity (cap=1, STRESS)         |            22.8% | ❌ VULNERABLE |

   **Key numbers:** Max attacker win rate (operational) 18.4% — just below the 20% threshold,
   a WARNING envelope. The 20% bound is **not falsified at calibrated parameters**, but the
   headroom is only ~1.6 pp and the below-minimum-viable case (cap=1) breaks it at 22.8%.
   Required reviewed-share to hold the ≤20% bound: ≈ 10.5%; approximate capacity floor:
   1 review per 5 disputes.
   **Historical note (retired figure):** the earlier "33.6% attacker win rate / VULNERABLE"
   claim was computed with `base-win-prob = 0.35` **before** the win-rate recalibration to
   `0.22` from the deterministic invariant suite (9/41 adversarial scenarios). At the
   calibrated parameters the operational scenarios hold the 20% bound; the 33.6% figure is no
   longer reproducible and is retired from this report. The deterministic
   `governance-decay-exploit` trace remains a reproducible single-trace example of the same
   failure class (attacker controlling the resolver role).
   **Policy implication:** the margin is thin by design. Phase AD (below) shows that a
   minimum review floor of 2 per 5 disputes restores comfortable headroom and is the
   recommended hardening before mainnet if governance capacity cannot be kept near its
   floor of 1 review per 5 disputes.

---

## How to interpret results today

### The deterministic engine — high confidence

A scenario that passes all 31 invariants is strong evidence that the *specific event
sequence* does not violate the protocol. A scenario that fails is a falsifiable,
reproducible bug.

Read the scenario IDs as claims: S08 (*adversarial pressure does not break
invariants*), S17 (*liveness holds without a resolver*). The scenario library is the
primary output.

### The Monte Carlo engine — directional, not exact

**Dominance ratios** (honest EV / malicious EV) tell you the *direction* of economic
incentives, not exact magnitudes. A ratio of 3× means honest play earns three times
as much *in protocol income*, not that fraud is three times less valuable overall.

**Do not read MC results as proof that fraud is economically unattractive.** The
corrected fraud model (fraud-success-rate = 0.22, calibrated from the adversarial
suite) shows malice EV (201) > honest EV (142) at baseline parameters. The original
"honest dominates" finding applied only to protocol income. The correct claims are:

> "The protocol's fee and slashing parameters make honest participation
> economically superior to malicious participation *in terms of protocol income*."

> "At current bond and detection parameters, deterrence of escrow-diversion requires
> detection probability ≥ 70% (current: 10%), or bond-at-stake 21× current levels.
> The invariant suite constrains effective fraud-success-rate to near zero for the
> 41 modelled attack vectors — making it the load-bearing mechanism for economic security."

The correct claim about fraud's *total* unattractiveness comes from the deterministic
engine's funds-conservation and no-double-release invariants — not the MC output.

**Phase results read as pass/fail per hypothesis.** Each MC phase tests one hypothesis
(e.g., "honest dominance holds across all market-exit parameter combinations"). The
binary outcome is what matters; the exact numbers are illustrative.

### The 22% adversarial success rate

Nine of 41 attack scenarios in the adversarial suite produce a successful outcome for
the attacker. This is the denominator for the headline claim. It does *not* mean
9 of 41 scenarios in the invariant suite fail — all 96 deterministic invariant scenarios pass (82/99 with current known-baseline failures). The
adversarial suite tests *whether an attacker can find a profitable position*; the
invariant suite tests *whether the state machine breaks*. These are different questions.

---

## Recommendations for use today

### For protocol designers

1. **Run the invariant suite first.** `./scripts/test.sh invariants` takes about
  one second and is the most reliable signal in the repository. If you change a
   protocol parameter, run this.
2. **Use the deterministic engine for any specific attack claim.** If you want to
  know whether attack pattern X is possible, write a scenario trace and run it. Do
   not use Monte Carlo output to answer scenario-specific questions.
3. **Use Monte Carlo for parameter sensitivity.** Sweeping `fee-bps` from 100 to 500,
  or `fraud-slash-bps` from 1000 to 8000, is exactly the right use of the MC engine.
   It will tell you how directional incentives change across the parameter space.
4. **Set `fraud-success-rate` when modelling adversarial economics.** The calibrated
  value (0.22, from the adversarial suite) gives a more realistic picture of whether
   fraud is economically rational for a given parameter set. The zero default is
   conservative in the wrong direction.

### For grant reviewers and auditors

The most auditable output is the deterministic scenario suite. Each of the 96
scenarios can be run individually and its full event trace inspected:

```
clojure -M:run -- --invariants
```

The scenario IDs, expected outcomes, and invariant predicates are all documented in
`docs/evidence/RESEARCHER_EVIDENCE_PACK.md`. The full reproducibility instructions —
exact commands, parameter files, and expected outputs — are in that document.

The Monte Carlo results are supporting evidence for the economic design. Treat them
as sensitivity analysis, not as security proofs.

### For researchers who want to challenge the results

The falsification checklist in the evidence pack lists the exact assumptions that
would invalidate the headline claim if violated. Three specific challenge tasks
(find a profitable attack not covered by the 41 scenarios, find an invariant violation,
beat the baseline DR cost/latency model) are documented in
`docs/archive/challenge/BENCHMARK_CHALLENGE.md`.

---

## What comes next

### Immediate gap: the multi-party fraud model

The most important open problem is extending the economic argument to cover the full
payoff matrix. The breakeven analysis (implemented in mc-b1 through mc-b4) establishes
the required conditions for bond-and-detection deterrence to work. The current finding:

- **At baseline params:** detection must be ≥ 70% OR bond must be 21× current to deter
fraud through incentives alone
- **The state machine is load-bearing:** if the invariant suite constrains fraud-success-rate
to near zero (which it does for the 41 modelled vectors), the bond-and-detection gap
does not matter
- **The open question:** what is the effective fraud-success-rate for *unmodelled* attack
vectors? The invariant suite cannot answer this for attacks it has not yet been shown

Closing this requires:

1. Calibrating `fraud-success-rate` empirically from on-chain dispute data or a richer
  agent model
2. Adding a multi-party model where the *recipient of the misdirected escrow* is also
  a modelled actor with costs and risks
3. Running the combined model across the fee/bond/detection-probability parameter space

This is the single change that would most materially strengthen the economic security
argument — and the breakeven calculation now provides the target numbers.

### Governance capacity design

Phase AA establishes a concrete design requirement: at calibrated parameters the simple
governance model keeps the attacker win rate just under the 20% bound (max 18.4%, operational),
but the margin is thin and the below-minimum-viable case (cap=1) breaks it at 22.8%. The
governance mechanism should therefore either guarantee a reviewed-share comfortably above the
derived ≈10.5% minimum (i.e. an explicit floor), or adopt one of the following hardening
options to widen the margin from a defensive near-threshold posture. Concrete options:

1. **Minimum review floor (Phase AD).** Mandate a floor of reviews per epoch regardless of
   dispute value — 2 per 5 disputes restores comfortable headroom against low-value flooding.
   This is implementable without changing the core state machine.
2. **Minimum reviewed-share protocol rule.** Require that every governance epoch reviews at
   least ~50% of disputes before threshold decisions execute, extending capacity.
3. **Commit-reveal threshold submission.** Attackers exploit the visibility of which disputes
   are reviewed to target unreviewed ones selectively. Hiding review status until the epoch
   closes removes the information advantage. Adds one round-trip to governance.
4. **Random audit with deterrence bond.** Randomly audit a fraction of disputes after the
   fact and retroactively slash governance actors who gamed the review queue. Effective at
   lower governance cost, but requires a slashing mechanism for governance actors.

The Phase AA model (parameterised, deterministic with `:rng-seed`) can be used to evaluate each
of these options before implementation.

The 96 deterministic scenarios cover the main lifecycle paths well. The gaps are:

- **Multi-party collusion** at scale (>2 colluders with coordinated timing)
- **Economic griefing** (attacker absorbs loss to impose disproportionate cost on
honest parties)
- **Oracle manipulation** (false price signals fed to a resolver that uses external
data)
- **Cross-workflow attacks** (attacker uses a sequence of small disputes to build
position before a large one)

Each of these can be added as new scenario traces without changing the protocol logic.

### Medium-term: live contract integration

The simulation currently mirrors the protocol specification. The next step is to run
the deterministic engine against a deployed testnet contract, using real transaction
traces as the scenario input. This would convert the simulation from a *spec-level*
test to a *contract-level* test — directly falsifiable against deployed code.

The architecture already supports this: the replay engine is designed to accept any
event sequence, and the protocol interface is pluggable.

### Target end state

A complete security argument for the Sew protocol requires three layers that reinforce
each other:

```
Layer 3 — Formal verification
         ┌─────────────────────────────────────────────────┐
         │  Halmos / Foundry invariant tests on deployed   │
         │  contract bytecode. Proves properties hold for  │
         │  ALL inputs, not just sampled ones.             │
         └─────────────────────────────────────────────────┘
                              ↑ feeds
Layer 2 — Simulation (current work)
         ┌─────────────────────────────────────────────────┐
         │  Deterministic scenarios + Monte Carlo sweeps.  │
         │  Discovers candidate invariants and parameter   │
         │  ranges. Shows which claims are worth proving.  │
         └─────────────────────────────────────────────────┘
                              ↑ feeds
Layer 1 — Economic theory
         ┌─────────────────────────────────────────────────┐
         │  Game-theoretic equilibrium analysis.           │
         │  Establishes conditions under which no rational │
         │  actor would prefer to defect.                  │
         └─────────────────────────────────────────────────┘
```

The repository currently covers Layer 2 well and provides partial evidence for
Layer 1. The target end state is a system where:

- Every claim made in the research note is directly traceable to a passing test or
calibrated model output
- The simulation suite is used as the *specification* for Layer 3 formal tests — each
invariant predicate in `invariants.clj` becomes a Halmos property
- The economic model includes a full multi-party payoff matrix (resolver, challenger,
colluder, insurance pool) with calibrated parameters
- The fraud-success-rate is bounded empirically, not assumed

At that point the simulation moves from *exploratory evidence* to *engineering
specification*.

---

## Quick reference


| Task                             | Command                                            |
| -------------------------------- | -------------------------------------------------- |
| Run all deterministic tests      | `./scripts/test.sh invariants`                     |
| Run full unit suite              | `./scripts/test.sh unit`                           |
| Run Monte Carlo phases           | `./scripts/test.sh monte-carlo`                    |
| Run everything                   | `./scripts/test.sh all`                            |
| Run single MC phase              | `clojure -M:run -- -p data/params/baseline.edn -O` |
| Compare replay outputs           | `bb trace:compare --baseline a.json --candidate b.json` |


Parameter files live in `data/params/`. Results are written to `results/`. The
evidence pack is in `docs/evidence/`.

---

## Technical architecture

> Merged from `docs/overview/SIMULATION_OVERVIEW.md`.

The Protocol Robustness Framework is an adapter-oriented robustness framework with a **framework substrate**
and a **protocol adapter layer**. The repository currently includes the Sew protocol model
as the primary validation target.

- The replay kernel (`contract_model/replay.clj`) is protocol-agnostic.
- Protocol logic is provided via the `SimulationAdapter` interface (`protocols/protocol.clj`),
with optional `EconomicModel` and `AnalysisModule` extensions.
- `SewProtocol` (`protocols/sew.clj`) is the adapter that connects Sew domain logic
(`protocols/sew/`*) to those interfaces.

### Architecture in one view


| Layer                            | Purpose                                                               | Key namespaces                                                       |
| -------------------------------- | --------------------------------------------------------------------- | -------------------------------------------------------------------- |
| Protocol-agnostic kernel         | Deterministic replay of scenario actions                              | `contract_model/replay.clj`                                          |
| Protocol adapters                | Plug-in interface + concrete implementation wiring                    | `protocols/protocol.clj`, `protocols/sew.clj`, `protocols/dummy.clj` |
| Shared adapter flow-control      | Reusable precondition wrappers (actor resolution, checks, role gates) | `protocols/common/action_context.clj`                                |
| Sew domain implementation        | State machine, lifecycle, accounting, resolution, invariants          | `protocols/sew/*`                                                    |
| Simulation and stochastic models | Parameter sweeps, adversarial/economic phases, deterministic fixtures | `sim/*`, `stochastic/*`, `adversaries/*`                             |
| Shell/integration                | Persistence, file I/O, gRPC/session management, CLI                   | `db/*`, `io/*`, `server/*`, `core.clj`                               |


### Namespace maturity

**Stable core (framework-level):**
`contract_model/`*, `protocols/protocol.clj`, `protocols/common/*`, `scenario/*`,
`io/scenarios.clj`, `db/*`, `server/*`

**Sew implementation (production-grade for this repo):**
`protocols/sew/`*, `yield/modules/aave.clj` and Sew-integrated yield flows.
Sew is the primary protocol model. Its semantics are not assumed to be universal.

**Generic adapter façades (stable API; Sew default providers today):**
`generators/actions.clj` → `generators/sew/actions.clj`,
`generators/adversarial.clj` → `generators/sew/adversarial.clj`,
`io/trace_score.clj` → `io/sew/trace_score.clj`

**Experimental / research track (exclude from core capability claims):**
`sim/adversarial/reorg_check.clj`, selected exploratory `sim/`* modules, `research/sew/*`

For the complete namespace-to-layer mapping table see `docs/architecture/ARCHITECTURE.md` Appendix A.

### Yield module status controls

Yield behavior is configured through the scenario DSL and enforced at module-op execution time:

- Scenario config supports `:yield-config` with per-module `:module-status`.
- Module status semantics (Aave v3 currently):
  - `:active` — deposits and withdrawals allowed (subject to liquidity mode)
  - `:disabled-for-new-deposits` — deposits blocked, withdrawals allowed
  - `:paused` — deposits and withdrawals blocked
- Liquidity stress modes (`:shortfall`, `:frozen`, `:paused`) block both paths for affected token/module pairs.

These controls are tested in focused yield failure tests and are replay/scenario-driven rather than ad-hoc fixture mutation.

### How to navigate


| Goal                                            | Document                                                      |
| ----------------------------------------------- | ------------------------------------------------------------- |
| Deep architecture and layering rules            | `docs/architecture/ARCHITECTURE.md`                           |
| Adapter implementation and authoring            | `docs/architecture/ADAPTER_AUTHORING_GUIDE.md`                |
| Framework / adapter / Sew / research boundaries | `docs/architecture/framework-boundaries.md`                                |
| Reusable adapter design notes                   | `docs/overview/REUSABLE_COMPONENTS.md`                        |
| Use-of-funds accounting contract                | `docs/overview/USE_OF_FUNDS.md`                               |
| End-user and CLI workflows                      | `docs/quickstart/README.md`, `docs/usage.md`              |
| Test suite reference                            | `docs/testing/TEST_SUITE.md`, `docs/testing/RUNNING_TESTS.md` |
| Evidence and reproducibility                    | `docs/evidence/RESEARCHER_EVIDENCE_PACK.md`                   |


---

*Document reflects repository HEAD. Scope notes intentionally avoid hard-coding volatile counts (exact scenario totals, phase totals, benchmark percentages) so this file stays accurate as the simulation suite evolves.*