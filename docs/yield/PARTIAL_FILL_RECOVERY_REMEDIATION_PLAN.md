# Partial-fill recovery and parallel qualification remediation plan

## Goal

Close the evidence gaps around short-lived and partially restored liquidity
shortfalls while claims can arrive or settle in parallel. Preserve the existing
35/21/14 allocation, restricted 10-unit refund, allocator behavior, canonical
roots, and settlement authority. Prepare explicit inputs for later strategic
analysis without presenting modeled behavior as protocol permission or an
equilibrium result.

This plan is split across PRF evidence/model work and the EVM execution
qualification. Source qualification, release qualification, SP1/Solidity proof
qualification, and public-chain qualification remain separate claims.

## Current state and limits

- PRF `shortfall-recovery-episode.v2` can record sequential liquidity
  observations, shortfalls, recovery events, incoming entitlements, beneficiary
  payments, partial restoration, and claims while a deficit remains.
- `short-lived-and-partially-restored-shortfalls-preserve-intermediate-observations`
  already exercises an observed zero state, a 10,000 shortfall, 5,000 recovery,
  a 1,000 payment during the open shortfall, another 5,000 recovery, and closure
  within the declared duration bound. This is source-referenced model evidence;
  its source-authentication result is `:not-performed`.
- The same test now also truncates the trace after partial recovery while 5,000
  remains short. It verifies the episode stays `:not-closed`, with no closure
  time, duration upper bound, or short-lived classification. An elapsed-duration
  lower bound for a still-open episode is not currently emitted.
- The PRF recovery fixtures use their own amounts and model-time assumptions;
  they are not the EVM 35/21/14 entitlement fixture. They share invariants and
  evidence concepts, not a common economic identity or root.
- It validates event-to-observation deltas and keeps immutable decision rows as
  the source of entitlement amount and beneficiary.
- A trace beginning after the shortfall opened is correctly marked left-censored;
  it cannot establish the onset or that the shortfall was short-lived.
- Source authentication is an optional callback contract. No chain-specific
  authenticator is implemented or exercised. Declared source roots do not prove
  chain state or event inclusion.
- The recovery-game model already validates players, rights, rounds, actions,
  transaction-order metadata, information assumptions, recovery assumptions,
  payoff inputs, and computes payoff components/expected payoffs. It does not
  enumerate strategy profiles, calculate best responses, or establish an
  equilibrium. Candidate actions remain model inputs, not protocol permissions.
- The EVM workspace now retains a passing Anvil same-block run under
  `evidence/partial-fill-v3-anvil-same-block/`. It covers one claim-only order,
  with a competing duplicate; the test factory runtime is injected with
  `anvil_setCode` at a deterministic test address to reproduce addresses
  committed by the frozen proof. Recovery interleavings remain unqualified.

## Work plan

### 1. Freeze a shared parameter and invariant contract

Document a common versioned invariant contract for the PRF recovery evidence and
EVM 35/21/14 execution fixture, while retaining separate fixture identities,
units, amounts, and source roots. Do not imply the PRF model fixture is the same
economic execution as the EVM fixture. Parameterize:

- entitlement count, identity, beneficiary, and amount;
- unrestricted allocation capital and separately restricted refund capital;
- initial available liquidity and each shortfall amount;
- recovery amount, observation time, event time, and whether it is a gross
  authenticated inflow or only a balance observation;
- claim arrival/submission time, transaction index, round, caller, and
  entitlement;
- failed/retried claim identity and reason;
- observation interval/time unit and source chain/deployment identity;
- game information assumptions, candidate actions, payoff coefficients,
  recovery distribution assumptions, and terminal horizon.

For every prefix assert operation, asset, entitlement, beneficiary, amount, and
policy identity; paid plus remaining funded liability equals the original 70;
restricted capital remains separately accounted; and rejected actions leave
balances, consumed flags, liabilities, and claim counts unchanged.

**Exit evidence:** a fixture schema/manifest with ranges, units, defaults,
source class (`modeled-assumption` or authenticated observation), and invariant
definitions. Reject inconsistent or ambiguous fixtures during preparation.

### 2. Extend temporal evidence for episodes that are still open

The fully observed short-lived and still-open partial-restoration cases are now
covered by the same focused PRF test; do not create duplicates. One enhancement
remains if lower-bound duration reporting is needed: emit elapsed time since the
observed opening (or detection point when left-censored), label its bound
direction and unit, then verify it independently.

Check onset, elapsed duration bounds, partial-recovery deltas, full closure,
claims during the deficit, late recovery, and left/right censoring. Separate
observed balance change from authenticated gross recovery flows. Do not infer a
recovery event from a balance increase alone.

**Exit evidence:** stable evidence roots for fully observed short-lived,
partially restored and still-open cases; per-prefix liability/liquidity
reconciliation; explicit duration bounds and censoring classification.

### 3. Bind recovery observations to an actual source verifier

Select the source used by the fixture, then implement an adapter that checks
source identity, chain and deployment, operation/decision/module/token,
observation time and order, event parent/index, event values, and source roots.
The verifier must retrieve or cryptographically validate records rather than
accepting the fixture's declarations as truth. Bind verifier ID and immutable
revision to each attestation.

Exercise missing, stale, wrong-chain, wrong-deployment, wrong-operation,
wrong-token, wrong-root, reordered-event, mismatched-value, duplicate
attestation, and mixed-verifier cases. Make each negative case reach the
intended verifier check and prove the economic state projection is unchanged.

**Exit evidence:** one positive source-backed episode and precise negative
attestation results. Until this is complete, label recovery inputs as modeled
or source-referenced but unauthenticated.

### 4. Qualify parallel execution and recovery interleavings

The fixed claim-only Anvil sequence is qualified. Extend it only for claims that
depend on a real supported recovery lifecycle: recovery-before-claims,
recovery-between-blocks, claims-during-shortfall, failed claim plus unrelated
progress, and valid retry paths. Capture separate transaction hashes, receipts,
transaction indexes, state transitions, events, and observed token balances for
each supported interleaving. Do not simulate protocol recovery by editing
balances or storage.

Keep execution ordering evidence distinct from the simulation catalog's
transaction-order assumptions. Preserve the stated `anvil_setCode` test-harness
boundary; this is not public deployment or proof qualification.

**Exit evidence:** retained fixed-order evidence under
`evidence/partial-fill-v3-anvil-same-block/` already contains raw receipts,
transaction indexes, prefix transitions, and terminal reconciliation. If a
recovery interleaving cannot be exercised by the current contract lifecycle,
mark it as model-only and identify the missing source or protocol capability
rather than changing allocator semantics.

### 5. Make the game-model boundary complete and explicit

First close input-contract gaps: validate exact player/action ownership,
feasible action sets, round timing, strategy observation rules, payoff units,
discounting, terminal values, and recovery probabilities/basis. Ensure every
parameter that affects a result is included in the model root and evidence
manifest. Add fixtures for impatient vs patient claimants, risk-sensitive vs
risk-neutral claimants, strategic liquidity withholding, arrival-order
advantage, split/defer/claim-now choices, and uncertain partial recovery.

Then add a separate analysis phase that enumerates the bounded pure strategy
profiles, computes outcomes and payoffs, reports best responses and any pure
equilibria found, and returns inconclusive when the strategy space or payoff
inputs are incomplete. State assumptions and scope beside every result. Do not
call a result game-theoretic qualification until the deviation set and
equilibrium concept are declared and tested; do not infer implementation
permission from model actions.

**Exit evidence:** model validation tests, parameter-sensitivity comparisons,
hand-calculated payoff vectors, deviation/best-response checks, and an explicit
no-equilibrium/inconclusive fixture. Strategic findings remain descriptive and
bounded to the declared game.

### 6. Regenerate the qualification packet from clean workspaces

Run PRF evidence tests and the EVM Foundry/Anvil qualification from clean
checkouts at recorded source revisions. Do not use manual classpath overrides
or unrecorded local state. Regenerate manifests only from these runs and bind
the source commits, dependency revisions, fixture roots, tool versions,
commands, logs, receipts, journals, and result hashes.

**Exit evidence:** independently reproducible PRF and EVM packets with a
cross-reference mapping the shared fixture identity to each implementation's
evidence roots.

## Order and dependencies

1. Complete the parameter/invariant contract.
2. Build the uncensored and partial-restoration PRF traces.
3. Select and bind a concrete source verifier.
4. Extend EVM qualification to supported recovery interleavings; keep the
   already-qualified claim-only same-block sequence as its own evidence.
5. Complete game-input validation, then implement bounded strategy analysis as
   a separately identified scope.
6. Regenerate clean-checkout evidence after all source changes.

## Blockers for stronger claims

- A shortfall that begins before the first observation cannot be called
  short-lived; an uncensored onset and closure trace is required.
- Source authenticity is unqualified until a concrete verifier validates the
  source records and context.
- Same-block execution is qualified for the fixed local claim-only order. Do
  not generalize it to all same-block orders or recovery interleavings. The
  deterministic code injection is a test-harness limitation recorded in the
  evidence.
- Game-model parameter validation and payoff calculation do not establish
  strategic stability. Best-response/equilibrium claims require the separate
  bounded analysis phase and a named equilibrium concept.
- None of these steps qualifies SP1/Solidity proofs, release readiness, or
  public-chain operation.

## Optional improvements

- More claimant counts and timing distributions beyond the fixed 3-beneficiary
  fixture.
- Additional token behavior and multi-block network congestion cases.
- Calibrating recovery probabilities from an independently qualified dataset.
- Formal equilibrium analysis over larger or mixed-strategy spaces.

These are out of scope for the fixed qualification unless its claims are
expanded.
