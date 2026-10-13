# Baseline Sew property-test failures

This document records the pre-existing Sew protocol property-test failures that
are unrelated to the allocation kernel work, so the boundary is explicit and
reproducible.

## Baseline commit

The failures exist on the pristine parent commit of the `agent-c` jj workspace:

- Full commit id: `33099cbb851b81c916f1cc32611f75c98caf7071`
- Description: `boundaries, with-bounty`
- Source tree: `/home/user/Code/.workspaces/agent-c` at revision `@-`

The workspace's allocation-slice working copy (revision `@`) adds the
allocation namespaces, allocation CLI commands, allocation domain tags, and
allocation tests. It does not modify the Sew protocol, its lifecycle, its
invariants, or the randomized property generators.

## Failing test IDs (exact)

Namespace `resolver-sim.protocols.sew.properties-test`, 11 of 14 tests fail:

```
property-adversarial-delayed-resolver
property-adversarial-escalation-clears-pending
property-adversarial-repeated-escalation
property-appeal-window-enforcement
property-escalation-monotonic
property-fee-monotonicity
property-interrupted-flow-timeout
property-irreversibility
property-multi-step-lifecycle
property-resolver-exclusivity
property-solvency
```

These are `test.check` randomized property tests over the Sew lifecycle
(create/cancel/release, escalation, appeal windows, fee monotonicity,
irreversibility). They fail in both the baseline and the allocation slice with
the same set.

## Evidence in the isolated baseline worktree

The same 11 failures were reproduced in a fresh, isolated `jj` worktree checked
out at the baseline revision `33099cbb851b81c916f1cc32611f75c98caf7071`:

```
clojure -M:test:with-sew -e \
  "(require 'resolver-sim.protocols.sew.properties-test)
   (clojure.test/run-tests 'resolver-sim.protocols.sew.properties-test)"
```

Result (baseline worktree): 14 tests, 3 pass, 11 fail (identical IDs).

Result (allocation working copy): 14 tests, 3 pass, 11 fail (identical IDs).

## Confirmation: the allocation slice introduces no new failures

Running the same property namespace against the allocation-slice working copy
produces exactly the same 11 failing IDs and no additional failures. The
allocation slice touches only:

- `src/resolver_sim/allocation/*`
- `src/resolver_sim/commands/allocation.clj`
- `resources/prf/commands/registry.edn` (4 new commands)
- `src/resolver_sim/cli/dispatch.clj` (handlers + `--input` option)
- `src/resolver_sim/hash/canonical.clj` (8 added domain tags, purely additive)
- `test/resolver_sim/allocation/*`, `test/resolver_sim/hash/canonical_test.clj`,
  `test/resolver_sim/commands/registry_validate_test.clj`

The allocation-related test suites are green: 244 tests, 2312 assertions, 0
failures (allocation + canonical-hash + registry-validate).

## Note on the registry count

The documented registry availability matrix in
`test/resolver_sim/commands/registry_validate_test.clj` expected 70 commands
(39 `:prf :native`). The baseline commit `33099cbb` already had 71 commands
(40 `:prf :native`); the matrix was stale before this phase. The allocation
phase added exactly 4 commands (all `:prf :native`), bringing the count to 75
(44 `:prf :native`). The test was updated to 75/44 accordingly. The single
pre-existing increment (70 → 71) came from commands such as `sentinel-check`,
`root-hash`, `result-root`, `semantic-equivalent`, `declared-dependencies`,
`shadow-check`/`shadow-report`, `workspace-doctor`, `compare`, `build-*`, and
`test-*` that predate this phase and were not reflected in the matrix.
