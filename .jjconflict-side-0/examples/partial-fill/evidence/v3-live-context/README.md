# Partial Fill V3 live-context qualification

This local evidence set binds the controlled shortfall proof to an actual V3 reserve policy root and reserve-state snapshot. It uses 80 deposited test-token units, 10 policy-restricted units and 70 available allocation units. The source-derived allocation is 35 / 21 / 14 for Alice, Bob and Carol.

## What passed

- The SP1 guest proof was generated for `witness.json`; its 288 public bytes match the Rust source-bound derivation, and the SP1 SDK verifier accepted it.
- Rust and Clojure independently reconstructed equal replay projections, including reserve commitment `a40e4d…f1dfd0c`.
- The EVM Groth16 verifier accepted the proof and rejected modified proof material.
- `PrfStatementAdmission` admitted it under the retained verifier registry, configuration and genesis roots.
- The V3 reserve and source-bound ratification controller completed proof-backed funding, activation and recipient withdrawals: 70 funded, then 35/21/14 withdrawn.
- After the allocator reached `Closed` with zero liability, the controller forwarded terminal finalization. The reserve transferred exactly 10 units to its immutable Carol beneficiary. Tests reject early and unauthorized finalization, unresolved claimant liability, replay, and finalization after terminal state.
- The stage-2 source-bound qualification passed 10/10 tests; the focused V3 reserve suite passed 8/8. Test logs and source snapshots are retained alongside this manifest.

The V3 reserve policy root is `0x88cad6aed4bc4e33b364275dc9a0b7d1b21b67faf11833ce1a0a63b6bfe122c4`. It is distinct from the prepared operation's policy root, which commits the allocation statement. `protected-capital-policy.json` records the reserve policy fields, `funding-observation.json` records the locally observed 80-unit sponsor transfer, and `terminal-observation.json` records the post-release state. `reconciliation.json` derives the request, source-capital, funded-claim, restricted-refund and terminal-custody equations from those observations. All five checks pass for this fixture; the 30 units are unmet requests, not a recognized loss or continuing liability. The final transfer sequence and artifact hashes are in `manifest.json`.

## Accounting and assurance boundary

The controlled fixture establishes `100 requested = 70 allocated/funded/withdrawn + 30 unmet request`. The separate 10-unit protected amount is restricted sponsor capital, excluded from claimant entitlements and allocation capacity, and refunded only after terminal closure. The test records a local ERC-20 funding transfer and enforces the fixture's sponsor and beneficiary addresses. Those local facts do not establish external legal ownership or a public sponsor relationship.

The proof and reserve execution are local Foundry evidence on chain ID 31337. They do not establish a Base Sepolia deployment or any public-chain execution. Carol's beneficial attribution follows the explicit fixture policy and is not independently established beyond that policy and local funding trace.

Stale-reserve rejection is tested with state unchanged, but recovery using a fresh V3 proof is not qualified because no fresh replacement-proof fixture is retained. The stage-2 qualification entry point is `PARTIAL_FILL_STAGE=2`. Bootstrap and genesis export tests are separate earlier stages and are not included in the stage-2 command.

See `manifest.json` for the full evidence index. The earlier placeholder-root vector remains separate under `../v3-conformance/`.
