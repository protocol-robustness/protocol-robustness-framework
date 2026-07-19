# Reversal-Slashing Reviewer Guide

## Canonical entry point

[`sew/reversal-slashing-v1`](reversal-slashing-v1.edn) is the canonical
executable benchmark for Sew reversal-reviewer slashing. Its registered suite
is `:suite/sew-reversal-slashing-v1`; the authoritative scenario list is in
[`src/resolver_sim/scenario/suites.clj`](../../../src/resolver_sim/scenario/suites.clj).

The benchmark evaluates simulator behavior. It does not by itself establish a
production guarantee. Treat a claim as demonstrated only when a completed,
hash-verified benchmark evidence bundle records the applicable scenario and
claim outcomes.

## Headline claims and executable coverage

| Claim | Reviewer question | Registered scenarios |
|---|---|---|
| **Reversal reviewer due process** | Can a resolver who is reversed appeal their own reversal slash, including upheld, rejected, expired-window, and wrong-party paths? | `DR-N-001` through `DR-N-004` |
| **Reversal-slash conservation** | Is requested slash value accounted for as `debited + unmet + reversed`? | `DR-N-001`, `DR-R-001` |
| **Vindication stability** | Does a later matching decision credit each vindicated resolver exactly once, without slash drift? | `DR-N-001`, `DR-O-001` through `DR-O-003` |
| **Challenge-bounty correctness** | Is a bounded bounty paid to the recorded challenger, and withheld when there is no challenger? | `DR-Q-001`, `DR-Q-002` |
| **Governance force-slash authorization** | Can authorized governance force a reversal slash while duplicate requests remain idempotent? | `DR-P-001`, `DR-P-002` |

The same mapping is machine-readable in the benchmark manifest under
`:benchmark/review-coverage`.

## Recommended review order

1. Read [`reversal-slashing-v1.edn`](reversal-slashing-v1.edn) for the
   benchmark contract, policies, claims, and coverage map.
2. Start with
   [`DR-N-001-reversal-slash-appeal-lifecycle.edn`](../../../scenarios/edn/DR-N-001-reversal-slash-appeal-lifecycle.edn).
   It is the complete L0 → L1 → L2 appeal and credit lifecycle.
3. Review the boundary cases named in the table above.
4. Trace the lifecycle implementation in
   [`resolution.clj`](../../../protocols_src/resolver_sim/protocols/sew/resolution.clj),
   especially `handle-reversal-slashing`, `force-reversal-slash`, and
   `reverse-reversal-slash-on-vindication`.
5. Verify a completed bundle with the reproduction and completion-verification
   commands recorded in that bundle before relying on its results.

## What the benchmark checks

The registered benchmark claims resolve to benchmark-scoped evaluators in
[`src/resolver_sim/benchmark/claims.clj`](../../../src/resolver_sim/benchmark/claims.clj).
For each claim, the evaluator verifies that every registered coverage scenario
was present, replayed successfully, passed its declared expectations, and
produced the required accounting invariants. The checks include
`:slash-distribution-consistent`, `:conservation-of-funds`, and, where
applicable, `:resolver/balances-conserved`.

Claim results emit per-scenario `:claim/assertions` records with the replay,
expectation, and invariant outcomes. Scenario fixtures additionally declare
expected events, terminal-state metrics, and falsification conditions, which
remain useful context for reviewing those assertion records.

## Evidence boundary

A passing scenario replay is not, by itself, proof that every headline claim
passed. Review the claim outcomes, invariant summary, and completion hash in
the generated benchmark evidence bundle. If no completed bundle is available,
the material here should be described as **active executable coverage**, not
as a passed assurance result.
