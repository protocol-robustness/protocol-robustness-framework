# Cancellation Shipping Gate Criteria

## Purpose

Define measurable thresholds that must be met before cancellation analysis can be
considered high-confidence. These gates apply to the `:suites/cancellation-equilibrium-validation`
suite and the `:cancellation-dominance` equilibrium concept.

## Gate 1: No profitable unilateral cancellation deviation

**Threshold:** >= 95% of tested seeds must show zero profitable cancellation deviations.

**Measurement:**
- Run `:suites/cancellation-equilibrium-validation` with `:cancellation-dominance`
  equilibrium concept enabled for each trace.
- Each trace must produce `:status :pass` for `:cancellation-dominance`.
- A trace fails if any cancel decision node has `:local-regret > 0`.
- Across all traces: `pass_count / total_count >= 0.95`.

**Enforcement:**
- Suite run produces aggregate pass/fail for cancellation-dominance across all traces.
- CI gate: fail the build if cancellation-dominance pass rate < 95%.

## Gate 2: Zero invariant violations

**Threshold:** Zero invariant violations across all cancellation bundle scenarios.

**Measurement:**
- Run full invariant suite on all cancellation scenarios (S04, S06, S17, S22,
  S60, S75, S76, S94, S98, S-DR-055).
- All world-invariants (`:cancellation-mutex`, `:all-status-combinations-valid`,
  `:persisted-escrow-state-valid`, etc.) must pass on every step.
- All transition-invariants (`:terminal-states-unchanged`, `:escrow-state-transition-valid`)
  must pass on every transition.

**Enforcement:**
- `:suites/all-invariants` must pass when cancellation scenarios are included.
- No invariant violation is tolerated (hard severity).

## Gate 3: No extortion-positive equilibrium

**Threshold:** No scenario with an enabled unilateral cancellation strategy produces
a positive extortion equilibrium within the production parameter band.

**Measurement:**
- For the `BuyerOnlyCancellationStrategy`:
  - Buyer wealth delta from threatening cancel must be <= 0.
  - Seller wealth delta when buyer threatens cancel must be >= seller's dispute-payoff.
- Measured across parameter sweep: fee levels 0–500 bps, escrow amounts 100–1M.

**Enforcement:**
- Dedicated extortion scenario with unilateral strategy enabled.
- `:cancellation-dominance` must pass for the cancel decision node.
- If no extortion scenario exists yet: Gate 3 is "not-applicable" until a scenario
  with unilateral cancellation strategy is added to the suite.

## Gate 4: Solidity parity

**Threshold:** All cancellation invariants have a Foundry test equivalent in `Validated` status.

**Measurement:**
- `:cancellation-mutex` → `test_cancellation_mutex_terminal_states` — **Validated**.
- S22 status-clearing → `test_status_leak_agree_cancel_over_dispute` — **Validated**.
- S04 timeout → `test_autoCancelDisputedEscrow` — **Validated**.
- S06 mutual cancel → `test_bothPartiesCancel`, `test_Cancel_DoubleAgreement` — **Validated**.

**Enforcement:**
- CI runs Foundry tests matching cancellation patterns.
- Update `docs/protocol/invariant-parity.md` when new parity tests are added.

## Gate 5: Multi-trace evidence for deviation-tested claims

**Threshold:** All `:cancellation-dominance` claims with claim-tier `:deviation-tested`
must include deviation bundles meeting the minimum count.

**Measurement:**
- Projection must have `:deviation-bundle :meets-minimum? true`.
- If deviation bundles are absent and the claim tier requires them, the result
  is `:inconclusive` with basis `:multi-trace-required`.

**Enforcement:**
- The `check-cancellation-dominance` validator uses `:single-trace-cancel-proxy`
  basis when deviation bundles are absent, and `:multi-trace-deviation-tested`
  when they are present.
- Review reports to confirm evidence-strength labeling is correct.

## Summary

| Gate | Threshold | Current status |
|---|---|---|
| 1. No profitable deviation | >= 95% pass | Pending — deviation bundles not yet generated for cancellation traces |
| 2. Zero invariant violations | 100% pass | ✅ Validated — all cancellation scenarios pass invariants |
| 3. No extortion equilibrium | Zero positive extortion | Pending — no unilateral-cancel scenario exists yet |
| 4. Solidity parity | All Validated | ✅ Validated — S06, S04, S22, cancellation-mutex all have Foundry tests |
| 5. Multi-trace evidence | Deviation-tested where required | ✅ Implemented — validator uses evidence-strength basis labeling |
