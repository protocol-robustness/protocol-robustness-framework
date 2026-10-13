# Partial Fill V3 live-context qualification

This local evidence set binds the controlled shortfall proof to a V3 reserve policy root and reserve-state snapshot. The fixture deposited 80 synthetic test-token units, restricted 10 to sponsor-policy capital and made 70 available for the source-bound allocation of 35 / 21 / 14 to Alice, Bob and Carol.

## What passed

- The SP1 guest proof's 288 public bytes match the Rust source-bound derivation, and the SP1 SDK verifier accepted the proof.
- Rust and Clojure independently reconstructed equal replay projections, including the source reserve commitment.
- The EVM Groth16 verifier accepted the proof and rejected modified proof material.
- `PrfStatementAdmission` admitted it under retained verifier registry, configuration and genesis roots.
- The V3 reserve and bound ratification controller completed funding, activation and withdrawals: 70 funded, then 35/21/14 claimed.
- After allocator closure and zero liability, controller-authorized terminal finalization refunded exactly 10 units to the immutable Carol fixture address.
- The stage-2 source-bound qualification passes 11/11 tests. The V3 reserve guard and terminal-release suite passes 8/8 tests.
- Stale reserve assurance rejects funding without state mutation. A newly generated source-bound proof for the changed reserve snapshot is admitted, replaces the stale assurance, and recovers the same 70-unit allocation. All three claims then close.
- In that recovery case, a separate one-unit reserve hold remains active. Terminal finalization is explicitly attempted and rejected; the reserve remains at 11 units and the captured state digest is unchanged.

All results are local Foundry evidence on chain ID 31337. No Base Sepolia deployment or public-chain transaction is claimed.

## Transition accounting evidence

The focused successful V3 terminal test emits eight actual getter observations at call boundaries: opening, proof admitted, funded, activated, after each of three claims, and reserve finalized. `derive-accounting-journal.py` derives `accounting-states.json` and `accounting-journal.json` from that retained Foundry output and its five ERC-20 transfers. It checks custody at every observation, activated liability against paid claims and remaining liability, the 10-unit protected balance before finalization, the exact refund, and terminal custody. The projection digest is an integrity digest for this derived artifact, not a PRF commitment or proof.

The original V2 journal remains scoped to its seven original observations. V2 and V3 are distinct local runs and are not represented as one continuous ledger. Neither journal establishes a complete population of external obligations, external legal ownership, solvency, or a universal financial ledger. The 30 units are unmet requests in this fixture, not a recognized loss or continuing liability.

## Policy and artifacts

The V3 reserve policy root is `0x88cad6aed4bc4e33b364275dc9a0b7d1b21b67faf11833ce1a0a63b6bfe122c4`. `protected-capital-policy.json` records the policy fields; `funding-observation.json` records the observed local sponsor transfer; `terminal-observation.json` records the terminal state; and `reconciliation.json` derives the aggregate equations. Carol's beneficiary is declared in fixture policy and enforced by this local V3 reserve, but external beneficial ownership is not independently established.

- `v3-accounting-qualification.log`: focused test output and the eight retained state observations.
- `accounting-states.json`: parsed observations with source-log line references.
- `accounting-journal.json`: bounded event projection with balance and liability checks.
- `derive-accounting-journal.py`: deterministic regeneration and assertions.
- `stage2-qualification.log`: full 11-test source-bound stage-2 qualification.
- `recovery/`: replacement proof, independent replay, active-hold release rejection, and full qualification log.
- `manifest.json`: SHA-256 and byte length for each retained file.

The recovery test requires the owning `prf-evm` checkout, local Foundry tools and the retained proof/build artifacts. Its stage-2 entry point is `PARTIAL_FILL_STAGE=2`; bootstrap and genesis export stages are separate. The earlier placeholder-root conformance vector remains under `../v3-conformance/`.
