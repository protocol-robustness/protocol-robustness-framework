# V3 stale-assurance recovery evidence

This local Foundry case qualifies recovery after the V3 reserve commitment becomes stale. The original assurance was bound to reserve revision 0. The fixture then deposits one additional unit and reserves it for a separate obligation. Gross reserve is 81, reserved capital is 1, protected capital is 10, and available capital remains 70 at revision 1.

A fresh SP1 Groth16 proof is generated from that changed snapshot. Rust and Clojure replay outputs match; the proof binds the updated reserve-state commitment while preserving the prepared operation, allocation binding and 70-unit entitlement table. `test_RecoveryWithFreshV3Proof` admits the replacement proof, advances assurance generation to 1, ratifies the unchanged 70-unit allocation, and observes withdrawals of 35/21/14. The stage-2 test suite passes 11/11.

The recovery case deliberately leaves the separate one-unit reserve hold active. After claims close, 11 units remain in reserve custody (10 policy-protected plus 1 held for the separate obligation); terminal finalization is explicitly attempted and reverts while that hold remains active; the captured state digest is unchanged. Finalization remains blocked until the hold is resolved. The independent base V3 qualification covers exact 10-unit terminal refund after all holds are closed. This recovery test is local EVM evidence on chain ID 31337, not public-chain execution.

## Retained artifacts

- `recovery-context.json`: actual Foundry export after the reserve change.
- `recovery-witness.json`: source-bound guest input.
- `recovery-replay.json` and `recovery-clojure-replay.json`: independent matching derivations.
- `recovery-proof.json`: regenerated Groth16 proof and its witness identity metadata.
- `recovery-compressed.bin` and `recovery-wrapper-witness.json`: SP1 SDK continuation artifacts.
- `stage2-qualification.log`: full successful stage-2 Foundry run, including stale-state rejection.
- `blocked-finalization-test.log`: focused passing test showing terminal release is rejected with the separate hold active and state unchanged.
- `proof-verification.json`: roots, reserve snapshot, commands and observed outcome.

Reproduction requires the owning `prf-evm` checkout, Rust/Clojure tools, SP1 SDK 6.3.1 toolchain and installed Groth16 circuit artifacts. Commands are listed in `proof-verification.json`; successful admission and state transition are checked by Foundry rather than inferred from a website or hash alone.
