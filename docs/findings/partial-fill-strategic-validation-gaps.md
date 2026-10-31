# Partial-fill strategic validation corrections (2026-10-31)

The bounded strategic validator now compares allocations belonging to the same
claimant across split and ordering transformations. Split invariance checks the
original allocation against the sum of the owner's child allocations. Sybil
resistance searches profitable unilateral splits while other requests remain
unchanged. Permutation invariance maps new positions back to original owners.
A counterexample may establish a property violation; absence of a counterexample
within this bounded model does not establish equilibrium or universal resistance.

The regression witness is requests [2, 1], liquidity 2, and largest-remainder
rounding. Baseline allocation is [1, 1]. Splitting the first request produces
[1, 1, 1] and allocations [1, 1, 0]. Its owner gains one unit although aggregate
payout remains two. Reordering equal requests can likewise change which owner
receives the rounding unit. These are strategic properties, separate from
conservation and capacity safety.

Omitted deviations now select the registered deviation families. Explicitly
empty deviations or policies and a nonpositive evaluation budget fail closed.
A run with no executed checks cannot report a valid summary. Sampled scopes are
labeled bounded-sampled rather than bounded-exhaustive.

Exhaustive enumeration generates each request independently within its lower
and upper bounds, paired with each liquidity value, up to the configured budget.
For two requests in [0, 3] and liquidity in [0, 3], this gives 64 states rather
than 16 fixed-sum states. Stratified boundaries are filtered to the declared
bounds and deduplicated; random request vectors and their totals are materialized
under the configured seed.

Semantic decision verification accepts inapplicable closed-form theorems when
independent reconstruction matches and all applicable checks pass. Empty check
results and failed checks remain rejection conditions. The independent reference
reconstructs no-row priority decisions and capped-row pro-rata, principal-first,
and waterfall decisions through the separately implemented portable contract.
Remainder-ranking checks are now marked not applicable for priority modes, where
the pro-rata ranking theorem does not apply.

Row-based pro-rata, principal-first, and waterfall paths derive `:full-fill`
versus `:partial-fill` from actual deferred amounts, including when a row cap
binds.
Priority closed-form checks compare against effective caps, so paying a lower
bucket after a higher bucket reaches its cap is accepted.

## Remaining scope

The strategic harness includes pure-strategy analysis and exact support
enumeration for two-player mixed equilibria. The recovery-game adapter evaluates
each supplied terminal outcome using the declared utility model before solving.
Profile counts, budgets, and table uniqueness are checked before outcome
evaluation. Degenerate supports with non-unique exact solutions return
inconclusive; games with more than two players are not solved for mixed
equilibria.

Recovery assumptions can be signed with Ed25519 and verified against an explicit
trust policy. The signature binds the entire model, including utility
assumptions. Protocol action permissions use a separate signed per-player claim
set with source roots; authenticated analysis requires the action domain to be
covered by that set. Verification is opt-in. Trusted signers authenticate who
made the assertions, not the underlying economic or protocol facts. No universal
game-theoretic claim follows from the focused regression suite.

## Verification

Focused strategic, partial-fill, strategic-property adapter and semantic-admission
suites pass 245 tests / 864 assertions with zero failures or errors. Scoped
clj-kondo reports zero errors and zero warnings.

## Follow-up review and second pass (2026-11-01)

Semantic reconstruction now rejects policy and available-liquidity metadata that
differs from the authoritative input, even when the decision's own evidence is
internally consistent. The priority-row reference preserves largest-remainder
rounding. Strategic reports expose domain cardinality and whether every
state/policy pair was evaluated; a budget-truncated exhaustive run is incomplete.
The transformation harness explicitly identifies its amount-only ordinal
allocator and does not assert production allocator validation.

Recovery results now carry execution assurance separately from signed utility
and permission assertions. Outcomes are caller supplied, are not authenticated,
and have not been replayed. State-dependent permissions have not been verified.
`:require-validated-execution? true` fails closed before outcome evaluation with
`:validated-execution-unavailable`. This guard remains on the caller-outcome API;
the new, separate replay API verifies traces instead. Pure and two-player mixed
caller-outcome results share these fields.

### Contracts for the next implementation pass

The concrete interface and transition design is now in
[Partial-fill strategic execution and replay v1](../specs/PARTIAL_FILL_STRATEGIC_REPLAY_V1.md).
It defines production adapter limits, explicit deposit accounting, residual-claim
retry semantics, permission resolution, evidence binding and solver integration.
The bounded core is now implemented; the design separates its constraints from
the remaining fixture and documentation work for Luna.

1. **Production strategic adapter (Sol owns design and review).** Each transformed
   row must retain an owner ID independently of its canonical row ID. Feed the
   transformed rows, authoritative liquidity and complete policy into the actual
   partial-fill producer; independently verify every decision before scoring it.
   Compute payoff by summing filled amounts for the same owner before and after
   a transformation. Keep request, weight, cap and priority independently
   specified; splitting must conserve each additive entitlement and cannot mint
   cap or priority permissions. Preserve existing amount-only diagnostics as a
   separately named model. Luna can implement fixtures after that adapter exists:
   weighted/capped rows, equal-remainder canonical ties, principal-first and
   waterfall buckets, and owner-preserving permutations. Acceptance requires
   an invalid production decision to stop the strategic analysis, and a known
   split witness to report the same owner's exact gain.

2. **Replay-derived recovery outcomes (Sol).** Define a trace per joint profile
   and stochastic branch containing ordered round inputs, action submissions,
   settlement decisions and resulting entitlement balances. Replay from the
   authoritative initial state, verify every decision, conserve liquidity and
   entitlements across rounds, and reject duplicate consumption. Derive payments,
   timing and residual claims from that trace. Bind the canonical complete
   profile/branch table, model root, initial state root and trace roots in one
   evidence root. Model signatures alone must never set execution assurance.
   Only this path may satisfy `:require-validated-execution?`.

3. **State-dependent feasibility (Sol).** Authorize an action against the player,
   entitlement, amount, cap, priority, round and pre-state root. Resolve and
   validate permission evidence rather than merely accepting a source-root
   string. Check permission again during replay at the action's actual state.
   Exclude infeasible profiles with an explicit reason; do not silently treat
   missing/infeasible profiles as zero utility in a complete normal-form game.
   Specify information sets and continuation strategies before claiming a
   sequential or subgame-perfect equilibrium. Existing Nash results apply only
   to the supplied simultaneous finite table.

### Bounded tasks suitable for Luna

- Add table-driven negative regressions for policy-only, liquidity-only and
  internally consistent metadata mutations; cover all three allocation modes.
- Add supported priority-row rounding controls and duplicate waterfall bucket
  rejection coverage. Genuine same-bucket ties need a later row-ID producer
  change: current maps and the semantic verifier require unique claim keys.
- Add sampled, zero-sized and multi-policy enumeration coverage cases; assert
  that sampled runs never claim complete exhaustive coverage.
- Assert the execution-assurance fields survive both recovery adapters, even
  when utility and static action-permission signatures are valid.
- Add API documentation for the execution requirement and coverage fields.

The bounded regression items above remain follow-up tasks; core execution
support is described in the implementation section below.
Degenerate mixed supports and mixed games with more than two players remain
explicitly inconclusive and do not need broader solver work before these
execution and feasibility boundaries are implemented.

Verification for this pass: 249 tests / 881 assertions, zero failures or errors,
across partial-fill, strategic partial-fill, strategic-property adapter and
semantic-admission suites. Scoped clj-kondo: zero errors and zero warnings.

## Core execution implementation (2026-11-01)

Implemented `strategic-execution/allocate-owned-rows`, conserved owner-based
transformation comparison, explicit execution-model preparation, signed
state-bound permission resolution, profile replay, and
`strategic-partial-fill/analyze-replayed-recovery-game-profiles`.
The latter verifies every branch and joint profile before utility evaluation;
it binds the complete table, model, initial state and replay results in one root.
Exact retries do not pay twice; changed payloads are rejected. Two-round recovery
reduces residual balances and shared cash using verified production decisions.
The reference row adapter now preserves production composite-ID order when
feeding the independent portable arithmetic engine.

V1 supports fixed public submission plans, claim/defer actions, a single pool,
explicit deposit schedules, zero fees/penalties/externalities, and unique claim
keys. It does not implement arbitrary contingent programs, multi-owner priority
buckets, private information, withholding, or generalized feasible games.
Unsupported executions are inconclusive. The original caller-outcome API still
rejects an execution-validation requirement; the new replay API satisfies it.
Verified replay is separate from external authentication of the economic inputs.

### Completed bounded follow-up

- Completed the metadata mutation matrix and sampling/empty-domain coverage.
- Added weighted/capped/permutation allocation fixtures and duplicate-bucket
  rejection, retaining the existing exact split-gain regression.
- Extended replay fixtures for duplicate deposits, excessive entitlement requests,
  stale/wrong-role/revoked grants, changed roots, missing branches, profile and
  replay budgets, and profile-dependent infeasibility. The tests require zero
  utility calls for rejected traces and verify signed stale grants explicitly.
- Added two-player mixed and stochastic replay fixtures, including assurance
  propagation with authenticated utility assumptions. Verified that signed static
  action claims cannot replace state-bound grants, and that authenticated
  caller-outcome APIs retain their narrower assurance flags.
- Added `examples/partial_fill_replay.clj` and
  [API documentation](../research/PARTIAL_FILL_REPLAY_API.md), including coverage
  fields and the distinction between replay and signed economic assumptions.

Keep later schema/solver extensions listed above outside this fixture pass.

Core verification: five focused suites passed 254 tests / 908 assertions. After
the final table-shape and assumption-authentication guards, the core suite passed
3 tests / 21 assertions. Scoped clj-kondo reported zero errors and zero warnings.

Follow-up verification: all five focused suites passed 263 tests / 1002 assertions
with zero failures or errors. Scoped clj-kondo reported zero errors and warnings.
The runnable example produced an analyzed pure equilibrium with authenticated
utility assumptions and verified replay. The remaining schema/solver extensions
are explicit unsupported scope, not unfinished bounded follow-up tasks.
