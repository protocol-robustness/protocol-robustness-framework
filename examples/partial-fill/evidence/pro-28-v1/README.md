# PRO-28 receipt evidence

This bundle separates the earlier V3 summary from receipt-qualified evidence.

- `legacy-v3-metadata-discrepancy.json` explains why the earlier observations do not establish receipt-level funding or payout identity. The sponsor's 80-unit reserve deposit and the 70-unit allocator funding are separate transfers. Carol's 14-unit entitlement and 10-unit protected-capital refund are distinct transfers; her 24-unit terminal balance is not a 24-unit entitlement claim.
- `raw/` retains the prior separate-workspace local replay. It is useful corroboration, but is still local Anvil evidence.
- `raw/fresh-replay-20261013/` retains a fresh local replay with exact transaction/receipt/log context and runtime code bytes for all 12 deployed contracts.
- `receipt-reconstruction.json` is the deterministic reconstruction of the prior separate-workspace replay.
- `fresh-replay-reconstruction.json` is the deterministic reconstruction of the fresh replay.

## Independent reconstruction

From a fresh PRF checkout with Python 3 and Foundry's `cast` available:

```bash
python3 scripts/forensic/pro28_receipts.py \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261013 \
  --expected-report examples/partial-fill/evidence/pro-28-v1/fresh-replay-reconstruction.json
```

The verifier reloads the manifest, witness, source vector, proof fixture, broadcast records, terminal observation, and runtime bytecode. It recomputes artifact digests and runtime SHA-256/Keccak identities, checks transaction/receipt one-to-one correspondence, checks log-to-receipt block and transaction context, and reconstructs the 80 deposit, 70 allocator funding and 35/21/14 entitlement transfers.

To independently repeat EVM proof verification and settlement, use the retained proof fixture and the matching `prf-evm` source revision recorded in `capture-metadata.json`, on a disposable local Anvil only. Start Anvil with chain ID 31337, set the test deployer's nonce to `0x4e`, deploy using the recorded deployment inputs, and regenerate the witness from the retained source vector. The manifest digest and witness hash must match before the proof fixture is used. Run `GateEEntitlementDeploymentDerivedRealProofTest` before ratification/funding; it expects the pre-funding reserve commitment. Then execute the funding, activation and withdrawal scripts in that order. Do not treat these local results as public-chain qualification.

The runtime-code observation is unsigned local RPC evidence. Offline reconstruction verifies that the retained bytecode matches its recorded hashes and the addresses in the manifest; it does not establish canonical network state. The separate-workspace replay report is operator separation, not organizational independence.
