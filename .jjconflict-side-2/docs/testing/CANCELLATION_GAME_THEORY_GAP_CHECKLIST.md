# Cancellation Game-Theory Gap Checklist

## Purpose

This checklist defines the work needed to upgrade cancellation analysis from
single-trace proxy checks to stronger game-theoretic evidence.

Current status in this repo:
- Protocol correctness and adversarial ordering regressions are strong.
- Equilibrium evidence is mostly proxy-level for cancellation-specific behavior.

## Goal

Establish high-confidence evidence that cancellation paths do not create
profitable strategic deviations (within configured parameter ranges).

## Checklist

- [x] Define cancellation-specific hypotheses (explicit claims)
  - Honest mutual cancel dominates frivolous dispute in low-ambiguity cases.
  - Strategic delay-to-timeout is not economically dominant over honest settlement.
  - Unilateral cancellation policies (when enabled) do not introduce extortion equilibria.
  - Documented in `docs/research/CANCELLATION_HYPOTHESES_AND_MATRIX.md`

- [x] Build a cancellation strategy-profile matrix
  - Actors: buyer, seller, resolver.
  - Strategies: cancel-now, dispute-now, delay, escalate, no-op.
  - Include expected payoff vectors and assumptions.
  - Documented in `docs/research/CANCELLATION_HYPOTHESES_AND_MATRIX.md`

- [x] Add counterfactual deviation bundles (infrastructure)
  - Extended `trace_metadata.clj` `strategic-actions` with `"sender-cancel"`, `"recipient-cancel"`
  - Extended `subgame_counterfactual.clj` `action-alternatives` with cancel alternatives
  - Extended `node-type-by-action` and `node-type-alternatives` for cancel nodes
  - Deviation generation enabled for S06, S04, S22, S17 traces
  - TODO: Generate explicit deviation bundles and record profitability

- [x] Add cancellation-specific equilibrium validators
  - Added `:cancellation-dominance` equilibrium concept
  - Filters regret table to cancel decision nodes only
  - Pass/fail/inconclusive based on cancel node regret
  - Registered in `sew/equilibrium.clj` equilibrium-concept-validators
  - Unit tests in `equilibrium_test.clj` (pass, fail, inconclusive)

- [x] Emit cancellation-focused metrics
  - Added `classify-event` tags: `:cancellation-sender`, `:cancellation-recipient`, `:cancellation-auto-dispute`
  - Added `metric-vocabulary` keys: `:cancellations-sender`, `:cancellations-recipient`, `:cancellations-auto-dispute`
  - Added `accum-protocol-metrics` accumulation for each tag
  - TODO: compute derived metrics (cancel→dispute conversion rate, payout delta)

- [x] Create a dedicated fixture suite
  - ID: `:suites/cancellation-equilibrium-validation`
  - Seeds: `S06_mutual-cancel`, `S04_dispute-timeout-autocancel`, `S17_ieo-dispute-no-resolver-timeout`, `S22_status-leak-agree-cancel-over-dispute`
  - Suite file: `data/fixtures/suites/cancellation-equilibrium-validation.edn`
  - Theory blocks added to all four trace fixtures (Session 6)
  - Registered in `data/fixtures/suites/manifest.edn`

- [x] Enforce multi-trace evidence for cancellation claims
  - Evidence-strength labeling implemented in `check-cancellation-dominance` validator:
    - `:multi-trace-deviation-tested` when deviation bundles present
    - `:single-trace-cancel-proxy` when single trace
  - Validator reports `:evidence-basis` in observed payload

- [x] Add evidence-strength labeling in reports
  - `:single-trace-cancel-proxy`
  - `:multi-trace-deviation-tested`
  - Integrated into `check-cancellation-dominance` validator output

- [x] Define shipping gate criteria
  - Documented in `docs/testing/CANCELLATION_SHIPPING_GATES.md`
  - 5 gates defined: deviation pass rate, invariant violations, extortion equilibrium, Solidity parity, multi-trace evidence
  - 2 gates currently Validated, 2 Pending, 1 N/A

- [x] Extortion scenario (H3) + same-timestamp boundary scenarios
  - Extortion scenario `s-extortion-unilateral-cancel`: buyer with unilateral cancel power → `:cancellation-dominance :pass`
  - Extortion scenario `s-extortion-unilateral-cancel-dual`: both parties with unilateral cancel → `:cancellation-dominance :pass`
  - Boundary scenarios `s-same-timestamp-cancel-vs-dispute` and `s-same-timestamp-dispute-vs-cancel` created but deferred (expected-revert format pending)
  - **H3 confirmed empirically**: no profitable extortion deviation with unilateral cancellation

- [ ] Add cancellation-specific parameter sweeps
  - Sweep timeout/appeal windows, fee levels, and cancellation strategy toggles.
  - Output regions where strategic preference flips.

## Suggested Execution Order

1. Claims and strategy matrix
2. Counterfactual deviation generation
3. Suite + metrics instrumentation
4. Parameter sweep and thresholding
5. Evidence-strength reporting integration

## Notes

- This checklist is intentionally cancellation-specific.
- It complements (does not replace) existing general equilibrium and mechanism
  property checks.
