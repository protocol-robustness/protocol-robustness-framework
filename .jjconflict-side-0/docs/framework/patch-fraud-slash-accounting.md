# Patch Plan: Reconciliation of `execute-fraud-slash` Accounting

## Problem Description
The `execute-fraud-slash` action in `src/resolver_sim/protocols/sew/resolution.clj` triggers a `conservation-of-funds` invariant violation. This occurs because slashed funds are added to `bond-distribution` (the "Accounted" side of the invariant) but are not removed from the corresponding `held` or `stakes` partition (the "Inflow" side) of the world state, creating an accounting discrepancy.

## Reproduction
Run the invariant suite and observe failure in `S34_profit-maximizer-unchallenged-slash` and related scenarios:
`clojure -M:test scripts/run_invariants.clj`

## Patch Plan
1. **Locate**: Identify where `execute-fraud-slash` interacts with state registries.
2. **Update**: Ensure `execute-fraud-slash` invokes a balancing function to remove the slashed amount from the `held` stake partition of the resolver, matching the addition to `bond-distribution`.
3. **Verify**: Run the invariant suite again to ensure `S34` and related scenarios pass the `conservation-of-funds` check.
