# Invariant Parity: Sew Protocol

This document maps the Clojure-based simulation invariants (defined in `src/resolver_sim/protocols/sew/invariants.clj`) to their equivalent testing implementations in Foundry.

For Reference Validation v1 **evidence** invariant IDs (claim layer), see
`docs/protocol/evidence-invariant-mapping.md`.

| Simulation Invariant ID | Foundry/Solidity Test Equivalent | Status |
| :--- | :--- | :--- |
| `:solvency` | `test_SolvencyMaintainsEscrowBalance` | Validated |
| `:conservation-of-funds` | `test_ConservationOfFunds` | Validated |
| `:cancellation-mutex` | `test_cancellation_mutex_terminal_states` | Validated |
| `:persisted-escrow-state-valid` | — | Pending |
| `:escrow-state-in-graph` | — | Pending |
| `:escrow-dispute-metadata-consistent` | — | Pending |
| `:module-snapshot-immutable` | — | Pending |
| `:fees-non-negative` | `test_ProtocolFeesNonNegative` | Validated |
| `:held-non-negative` | `test_EscrowHoldingsPositive` | Validated |
| `:yield-position-consistency`| `test_YieldPositionConsistency` | Pending |
| `:yield-exposure` | `test_YieldExposureBounds` | Pending |

*Status key: `Validated` (verified against on-chain impl), `Pending` (needs Foundry test implementation).*
