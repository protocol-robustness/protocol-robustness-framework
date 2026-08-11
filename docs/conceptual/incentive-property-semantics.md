# Incentive Property — Concept Ownership / Coverage Matrix

**Status:** Ownership and coverage audit for the incentive-property layer.

This document maps every **implemented** incentive-relevant property (mechanism
properties and solution/equilibrium concepts) to its **semantic owner** in the
concept layer, and records the pass/inconclusive/fail/not-applicable semantics
the trace-end validators actually produce. It is generated against the
executable and catalog state, not an aspiration.

## Schema

Each row records four orthogonal dimensions so that future audits are easier:

| Column | Meaning |
|--------|---------|
| **kind** | `mechanism-property` (a proposition the mechanism is asserted to satisfy) vs `equilibrium-concept` (a solution concept used to analyse incentive properties) |
| **semantic owner** | The concept that defines the proposition's meaning. `framework/incentive` is the umbrella / explanatory home; it is **not** claimed as the canonical owner of equilibrium concepts. `[unresolved]` marks an explicit, unresolved cell. |
| **implemented evaluator** | The predicate/validator actually dispatched (`check-…`), i.e. the `:property` keyword the evaluator is registered under. |
| **alias-of** | When set, the row shares the underlying predicate with another concept and must label results with its own keyword. |

**Catalog distinction (declared / wired / implemented):**

- **declared / catalogued** — present in `game_theory_validation.clj` catalog.
- **wired** — a validator is registered in a dispatcher map, so declaring it in a
  theory block produces a result (not `:inconclusive :unsupported-concept`).
- **implemented** — an evaluation function exists somewhere in the codebase.

A property can be **declared and implemented yet not wired** (the framework
sourced multi-epoch analyses). Declaring such a property in a single-trace
theory block resolves to `:inconclusive :unsupported-concept` and **never**
silently passes.

## Ownership Principles

1. **Ownership follows the proposition asserted, not the mathematical technique
   used to test it.** A check whose technique is "algebraic conservation" is not
   therefore owned by `:concept/conservation` unless that concept defines the
   full end-to-end invariant the check implements.
2. **`framework/incentive` is the umbrella explanatory concept / semantic home**
   for incentive properties. It is **not** asserted as the canonical owner of
   equilibrium concepts (dominant strategy, Nash, SPE, …); those are solution
   concepts used to *analyse* incentive properties and are recorded as such.
3. **Unsupported properties must resolve explicitly to `:inconclusive
   :unsupported-concept`, never silently pass.** This is enforced at the
   dispatchers (`scenario/equilibrium.clj`); the concept layer's
   `research_command` assumption encodes the same rule.
4. **An explicit unresolved cell is preferable to manufactured ownership.**
   Where a real owner is not established, the cell is marked `[unresolved]`
   rather than forcing the property into a plausible-but-unsupported owner.

## Mechanism Properties

| Property | kind | semantic owner | wired? | implemented? | evaluator |
|----------|------|----------------|--------|--------------|-----------|
| `incentive-compatibility` | mechanism-property | `framework/incentive-compatibility` | yes | yes | `check-incentive-compatibility` |
| `individual-rationality` | mechanism-property | `framework/incentive` (umbrella) | yes | yes | `check-individual-rationality` |
| `collusion-resistance` | mechanism-property | `framework/incentive` (umbrella) | yes | yes | `check-collusion-resistance` |
| `sybil-resistance` | mechanism-property | `framework/incentive` (umbrella) | yes | yes | `check-sybil-resistance` |
| `budget-balance` | mechanism-property | `concept/conservation` | yes | yes | `check-budget-balance` |
| `budget-balance-detailed` | mechanism-property | `concept/conservation` | yes | yes | `check-budget-balance-detailed` |
| `stake-flow-conservation` | mechanism-property | `concept/conservation` | yes | yes | `check-stake-flow-conservation` |
| `pro-rata-fairness` | mechanism-property | `allocation/pro-rata-fairness` | yes | yes | `check-pro-rata-fairness` |
| `redistribution-fairness` | mechanism-property | `allocation/redistribution-fairness` | yes | yes | `check-redistribution-fairness` |
| `force-refund-path-integrity` | mechanism-property | **[unresolved]** | yes | yes | `check-force-refund-path-integrity` |
| `force-reversal-path-integrity` | mechanism-property | **[unresolved]** | yes | yes | `check-force-reversal-path-integrity` |
| `pending-lifecycle-integrity` | mechanism-property | **[unresolved]** | yes | yes | `check-pending-lifecycle-integrity` |
| `coalition-aggregate-payoff` | mechanism-property | `framework/incentive` (umbrella) | **no** | yes | `coalition-aggregate-payoff` (multi-epoch helper) |
| `grim-trigger-stability` | mechanism-property | `framework/incentive` (umbrella) | **no** | yes | `evaluate-grim-trigger-stability` (multi-epoch) |
| `incentive-margin` | mechanism-property | `framework/incentive` (umbrella) | **yes** | yes | `incentive-margin` (terminal-payoff helper, included in observe payload by check-incentive-compatibility) |

### Semantics (pass / inconclusive / fail / not-applicable)

- **`incentive-compatibility`** — `:fail` if `attack-successes > 0` or
  `funds-lost > 0`; `:inconclusive` if no adversarial actors (`attack-attempts = 0`);
  otherwise `:pass`.
- **`individual-rationality`** — uses the terminal-payoff IR check when a per-actor
  ledger exists; falls back to `negative-payoff-count`, then `funds-lost`; if no
  evidence at all, `:inconclusive` (`:absent-evidence`). `:fail` on a negative net
  payoff.
- **`collusion-resistance`** — `:inconclusive` when `coalition-net-profit` metric is
  absent; `:fail` when coalition net profit `> 0`; else `:pass`.
- **`sybil-resistance`** — `:inconclusive` when no attacks; `:fail` when
  `attack-successes > 0`; else `:pass`.
- **`budget-balance` / `budget-balance-detailed`** — `:not-applicable` when escrows
  are non-terminal or open disputes are allowed; `:fail` if any `total-held` is
  non-zero (or payoffs do not sum to zero within epsilon); else `:pass`.
- **`stake-flow-conservation`** — `:fail` if `start - withdrawn - slashed != end`
  for any resolver; else `:pass`.
- **`pro-rata-fairness` / `redistribution-fairness`** — `:inconclusive` when no
  shortfall / no redistribution occurred; `:fail` on imbalance / iteration-limit /
  negative allocations; else `:pass`.

### Unresolved ownership

`force-refund-path-integrity`, `force-reversal-path-integrity`, and
`pending-lifecycle-integrity` are **declared, wired, and implemented** with
algebraic-integrity–style checks, but they are fundamentally **execution /
lifecycle integrity** propositions. `:concept/conservation` does not (yet) define
the full end-to-end invariant these checks implement, so they are **not** assigned
to it. Their canonical concept owner is **unresolved** and is recorded as such
rather than manufactured. (A lifecycle/execution-integrity concept, or an explicit
statement that `:concept/conservation` covers them, is the required resolution.)

## Equilibrium / Solution Concepts

The following are **solution concepts used to analyse incentive properties**.
Their **semantic home** is `framework/incentive` as umbrella explanatory concept;
this is **not** a claim that SPE/Nash/dominant-strategy are themselves incentive
properties owned by that concept.

| Concept | kind | semantic owner | wired? | implemented? | evaluator | alias-of |
|---------|------|----------------|--------|--------------|-----------|----------|
| `dominant-strategy-equilibrium` | equilibrium-concept | `framework/incentive` (semantic home) | yes | yes | `check-dominant-strategy-equilibrium` | — |
| `empirical-strategy-dominance` | equilibrium-concept | `framework/incentive` (semantic home) | yes | yes | `check-dominant-strategy-equilibrium` | `dominant-strategy-equilibrium` |
| `nash-equilibrium` | equilibrium-concept | `framework/incentive` (semantic home) | yes | yes | `check-nash-equilibrium` | — |
| `bounded-nash-diagnostic` | equilibrium-concept | `framework/incentive` (semantic home) | yes | yes | `check-nash-equilibrium` | `nash-equilibrium` |
| `bayesian-nash-equilibrium` | equilibrium-concept | `framework/incentive` (semantic home) | yes | yes (always `:inconclusive`) | `check-bayesian-nash-equilibrium` | — |
| `subgame-perfect-equilibrium` | equilibrium-concept | `framework/incentive` (semantic home) | yes | yes | `check-subgame-perfect-equilibrium` | — |
| `trace-conditioned-epsilon-spe` | equilibrium-concept | `framework/incentive` (semantic home) | yes | yes | `check-subgame-perfect-equilibrium` | `subgame-perfect-equilibrium` |
| `bounded-public-state-epsilon-spe` | equilibrium-concept | `framework/incentive` (semantic home) | yes | yes | `check-bounded-public-state-epsilon-spe` | — |
| `bounded-backward-induction-spe` | equilibrium-concept | `framework/incentive` (semantic home) | yes | yes | `check-bounded-backward-induction-spe` | — |
| `resolver-reputation-spe` | equilibrium-concept | `framework/incentive` (semantic home) | yes | yes | `check-resolver-reputation-spe` | — |
| `resolver-reputation-profile-matrix` | equilibrium-concept | `framework/incentive` (semantic home) | yes | yes | `check-resolver-reputation-profile-matrix` | — |
| `cancellation-dominance` | equilibrium-concept | `framework/incentive` (semantic home) | yes | yes | `check-cancellation-dominance` | — |
| `folk-theorem-cooperation-region` | equilibrium-concept | `framework/incentive` (semantic home) | **no** | yes | `evaluate-grim-trigger-deterrence` (multi-epoch; **deprecated** alias of `repeated-game/grim-trigger-deterrence`) | — |
| `repeated-game/grim-trigger-deterrence` | equilibrium-concept | `framework/incentive` (semantic home) | **no** | yes | `evaluate-grim-trigger-deterrence` (multi-epoch) | — |

### Semantics (pass / inconclusive / fail)

- **`dominant-strategy-equilibrium` / `nash-equilibrium` (and aliases)** —
  `:inconclusive` when no adversarial actors and no violations; `:fail` when
  `invariant-violations > 0` or `attack-successes > 0`; else `:pass` (single-trace
  proxy).
- **`bayesian-nash-equilibrium`** — always `:inconclusive` (`:multi-epoch-required`)
  for single-trace replay.
- **SPE family / `cancellation-dominance`** — delegate to
  `subgame-counterfactual`; `:inconclusive` when no proper subgames / no cancel
  decision nodes are found; `:pass`/`:fail` from bounded regret vs threshold.
- **`repeated-game/grim-trigger-deterrence`** (canonical; legacy id
  `folk-theorem-cooperation-region` is a **deprecated alias**) — evaluates a
  **model-specific grim-trigger one-shot-deviation deterrence claim**, not a
  general Folk-theorem result. It derives from
  `R / (1 - δ) >= T + δP / (1 - δ)`, equivalently `δ(T-P) >= T-R`, so
  `discount-factor >= (T - R) / (T - P)`, where `R=U_honest` is the cooperative
  per-period payoff, `T=U_malicious` is the one-shot deviation payoff, and `P`
  is the per-period punishment payoff (default 0). It applies a **fail-closed
  applicability contract**:
  - `T > R` else `:not-applicable :threshold-inapplicable`
    (`:deviation-not-profitable`; deviation is not genuinely tempting, so the
    result is never presented as theorem support);
  - `R > P` else `:inconclusive :assumptions-unsatisfied`
    (`:punishment-not-deterrent`; punishment is not worse than cooperation);
  - `T > P` else `:invalid-input :assumptions-unsatisfied`
    (`:non-positive-threshold-denominator`);
  - finite payoffs, `U_honest > 0` (else `:inconclusive`/`:assumptions-unsatisfied`);
    and `δ in [0,1)` (`δ = 1` diverges and is `:invalid-input
    :discount-at-horizon-boundary`).

  Equality satisfies the threshold and is reported as `:boundary-pass`. The
  result taxonomy is **deviation-deterrence oriented**: every outcome carries a
  `:claim/conclusion` from
  `:deviation-deterred | :deviation-profitable | :threshold-inapplicable |
  :assumptions-unsatisfied | :inconclusive`, and the result never asserts a
  generic `:cooperation-supported` claim.

  **Hardened theorem certificate.** Every outcome (including failures and
  context rejections) carries:
  - `:theorem/type`, `:theorem/claim`, `:theorem/assumptions`;
  - `:theorem/inputs` — the committed stage-game payoffs (`:cooperate R`,
    `:unilateral-deviation T`, `:punishment P`), `:repeated-game/discount-factor`,
    `:repeated-game/horizon`, `:strategy/profile`, `:deviation/model`,
    `:monitoring/model`, `:payoff/model`;
  - `:theorem/root-hash` — a SHA-256 domain-separated hash commitment over the
    committed inputs and the deviation domain, so the exact claim is
    recomputable and mutation-detectable;
  - `:threshold/value`, `:threshold/formula-id`, `:discount-factor/value`,
    `:inequality/evaluated` (present-value and normalized forms),
    `:inequality/holds?`, `:assumptions/evaluated`;
  - `:theorem/inequality-left`, `:theorem/inequality-right`,
    `:theorem/threshold`, `:theorem/margin`, `:theorem/holds?`;
  - robustness fields `:deterrence/margin`, `:deterrence/slack-classification`,
    `:discount-margin`, `:normalized-deterrence-margin`,
    `:nearest-failing-discount`, `:boundary-classification`;
  - sensitivity outputs `:sensitivity/required-minimum-discount`,
    `:sensitivity/maximum-deviation-payoff`,
    `:sensitivity/minimum-punishment-severity`,
    `:sensitivity/payoff-uncertainty`;
  - scope obligations `:punishment/credibility` (`:assumed` | `:unverified`),
    `:coalition-resistance? false`, `:evidence-tier`, and the
    `:deviation-domain` (`:actors :single :duration-epochs 1
    :timing :cooperative-path :actions #{:specified-deviation}
    :coalitions? false`).

  **Fail-closed model enforcement.** The evaluation rejects unsupported models
  before any payoff algebra:
  - `:horizon` must be `:infinite` (default) — finite horizons
    `{:type :finite ...}` are `:invalid-input :finite-horizon-unsupported`
    (the infinite-horizon geometric-series threshold does not apply) and unknown
    horizons are `:unsupported-horizon-model`;
  - `:monitoring-model` must be `{:type :perfect-public :deviation-detected?
    true :detection-delay 0}` — imperfect or delayed monitoring is
    `:invalid-input :unsupported-monitoring-model`;
  - `:evidence-tier` (`:parameter-level-theorem` default) distinguishes
    parameter-level, scenario-backed, and trace-observed attestation; the
    scenario-backed tiers require a `:scenario-evidence` map with the multi-epoch
    keys (`:epoch-sequence :cooperative-history :deviation-event
    :punishment-activation :punishment-persistence :branch-payoff-projection`),
    otherwise `:inconclusive :evidence-tier-unmet |
    :scenario-evidence-incomplete`.

  On failure it returns a concrete profitable-deviation witness
  (`:deviation/payoff`, `:cooperation/value`, `:deviation/gain`,
  `:minimum-discount-required`). Payoffs may be supplied as intervals
  `{:min .. :max ..}` via `:payoffs` to enable uncertainty-aware classification
  (`:robustly-deterrent` / `:possibly-deterrent` / `:robustly-not-deterrent`).

  The claim is multi-epoch only. Both the canonical id and the deprecated alias
  are **not wired** to the single-trace dispatcher (which lacks multi-epoch
  evidence), so declaring either in a single-trace theory block resolves to
  `:inconclusive :unsupported-concept`.

## Alias Result Labelling

The three aliases (`empirical-strategy-dominance`, `bounded-nash-diagnostic`,
`trace-conditioned-epsilon-spe`) intentionally share the underlying predicate of
their canonical concept. As of this audit, dispatched results are labelled with the
**requested** concept keyword (`:property`), not the parent's. Regression tests in
`test/resolver_sim/scenario/equilibrium_test.clj` assert the alias result is
identical to the canonical result on all fields **except** `:property`, proving the
shared implementation is labeling-only and that no pass/fail semantics changed.

## Unsupported / Not-Wired Handling

Unknown or not-wired property/concept keywords resolve to
`:inconclusive :unsupported-concept` via the dispatcher fallbacks in
`scenario/equilibrium.clj` (`evaluate-mechanism-properties`,
`evaluate-equilibrium-concepts`). Absent-evidence branches in the individual
validators return `:inconclusive` (with a `:basis` of `:absent-evidence`,
`:multi-trace-required`, or `:multi-epoch-required`), never a silent `:pass`.
This satisfies the requirement that every unsupported property resolves explicitly
to inconclusive/unsupported rather than silently passing.

## Audit Log

- **Alias result labelling fixed** — `:empirical-strategy-dominance`,
  `:bounded-nash-diagnostic`, `:trace-conditioned-epsilon-spe` now report their own
  `:property` keyword; semantic-equivalence regression tests added.
- **Catalog drift fixed** — removed the duplicate `:trace-conditioned-epsilon-spe`
  entry; added `:catalogued?` / `:wired?` / `:implemented?` / `:alias-of` metadata to
  every catalog entry; corrected `budget-balance` source to `:sew`.
- **New concept** — `framework/incentive-compatibility` created as the semantic owner
  of the `:incentive-compatibility` property and the
  `:outcomes/incentive-compatibility-root` projection, resolving the previously
  dangling reference in `framework/incentive.edn`.
- **Explicitly unresolved** — `force-refund-path-integrity`,
  `force-reversal-path-integrity`, `pending-lifecycle-integrity` marked `[unresolved]`
  rather than forced into `:concept/conservation`.
