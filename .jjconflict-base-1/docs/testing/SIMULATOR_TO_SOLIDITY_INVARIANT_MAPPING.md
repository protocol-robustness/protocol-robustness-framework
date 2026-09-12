# Simulator → Solidity Invariant Mapping

This table maps canonical Sew simulator invariants (`src/resolver_sim/protocols/sew/invariants.clj`) to current Solidity enforcement/tests.

> Maintenance note: update this file whenever a new canonical invariant is added or Solidity coverage changes.

## Core mapping

| Canonical invariant ID (simulator) | Solidity equivalent/assertion | Current coverage |
|---|---|---|
| `:solvency` | Vault balance must cover principal + fees (`EscrowVaultAnalytics.getAccountingBreakdown`) | `test/foundry/invariants/StateInvariants.t.sol` → `invariant_solvency` + `test/foundry/halmos/HalmosEscrowProperties.t.sol` → `check_solvency_after_create` |
| `:terminal-states-unchanged` | Released/refunded/resolved escrows are absorbing | `StateInvariants.t.sol` → `invariant_terminal_states_absorbing`; Halmos mirror: `check_released_state_absorbing` |
| `:fees-monotone` / `:fees-non-negative` | Fees do not decrease between non-withdraw actions; never negative | `StateInvariants.t.sol` → `invariant_fees_monotone`; Halmos mirror: `check_fees_monotone_after_create` |
| `:pending-settlement-consistent` | Pending settlement only coexists with `DISPUTED` | `StateInvariants.t.sol` → `invariant_pending_settlement_consistency`; Halmos mirror: `check_pending_settlement_consistent_after_resolution` |
| `:single-resolution-payout-consistent` | Single-sided payout semantics on terminal escrows | `StateInvariants.t.sol` → `invariant_single_sided_claimable_terminal`; Halmos mirror: `check_single_sided_claimable_after_release_execution` |
| `:no-double-finalize` | Contract state machine disallows second terminalization path | Covered structurally by `EscrowStateMachine` and resolver/appeal flow tests (`test/foundry/core/EscrowStateMachine.t.sol`, `test/foundry/core/AppealWindowEnforcement.t.sol`) |
| `:time-no-action-after-finality` | Attempts to mutate terminal escrows fail | Covered via state-machine and edge-case suites in `test/foundry/core/*` plus invariant absorbing checks |
| `:appeal-window` guards | `executePendingSettlement` must fail pre-deadline | `ResolverInvariants.t.sol` → `invariant_appeal_window_enforced`; Halmos mirror: `check_appeal_window_enforced` |

## Known non-1:1 areas (still requiring parity work)

1. Some simulator invariants are system/accounting-level (e.g. cross-world conservation, simulation-only ledger summaries) and do not have a single on-chain mirror assertion.
2. Alias/canonical-ID replay invariants are implemented in simulator replay/canonicalization layers; Solidity equivalent is covered indirectly by strict `workflowId` bounds/state-machine tests rather than alias map assertions.
3. Full proof-style parity matrix (every canonical ID → explicit on-chain test ID) is still partial and should be extended incrementally as each invariant gets a dedicated Solidity assertion.

## Halmos profile note

- `foundry.toml` now sets `[profile.halmos].via_ir = false` (required for Halmos 0.3.x compatibility).

