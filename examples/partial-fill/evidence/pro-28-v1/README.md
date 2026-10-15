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

To also check the retained transaction and runtime anchors against the currently running local chain:

```bash
python3 scripts/forensic/pro28_receipts.py \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261013 \
  --rpc-url "$RPC_URL" \
  --write-chain-report examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261013/live-chain-check.json
```

The live check requires the retained `localOnly` classification and matching chain ID. It re-fetches every transaction receipt and block, verifies transaction ordering and parent links, checks runtime code at the captured block with EIP-1898 `requireCanonical`, and compares current runtime code and proxy implementation bindings where declared. A changed canonical block, receipt, runtime bytecode or implementation fails closed. The result is relative to the queried RPC at check time; it does not prove provider honesty, public-chain consensus, or finality. For public-chain evidence, a future verifier must require an explicitly selected chain-specific confirmation policy and report observed depth separately; confirmations alone must not be called finality.

To independently repeat EVM proof verification and settlement, use the retained proof fixture and the matching `prf-evm` source revision recorded in `capture-metadata.json`, on a disposable local Anvil only. Start Anvil with chain ID 31337, set the test deployer's nonce to `0x4e`, deploy using the recorded deployment inputs, and regenerate the witness from the retained source vector. The manifest digest and witness hash must match before the proof fixture is used. Run `GateEEntitlementDeploymentDerivedRealProofTest` before ratification/funding; it expects the pre-funding reserve commitment. Then execute the funding, activation and withdrawal scripts in that order. Do not treat these local results as public-chain qualification.

The runtime-code observation is unsigned local RPC evidence. Offline reconstruction verifies that the retained bytecode matches its recorded hashes and the addresses in the manifest; it does not establish canonical network state. The separate-workspace replay report is operator separation, not organizational independence.

Offline receipt reconstruction deliberately reports Groth16 cryptographic verification as `not verified`; it does not infer proof validity from the retained Foundry log. To execute the pinned reference check against the exact retained proof and public values, run:

```bash
python3 scripts/forensic/pro28_receipts.py \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261013 \
  --verify-groth16 \
  --write-proof-check examples/partial-fill/evidence/pro-28-v1/groth16-reference-verification.json
```

The command installs the captured, pinned SP1 Groth16 v6.1.0 runtime into a disposable local Anvil instance and calls `verifyProof` with the captured VKey, proof and 288-byte public values. It verifies the exact fixture's proof hash, public-values hash, program identity, operation and prepared/reserve roots before reporting `verified`. A missing executable, unsupported local reference verifier or any changed byte fails closed. This establishes proof validity for those public values; it does not establish the live chain's historical state or finality. It broadcasts no network transaction.

The retained `groth16-reference-verification.json` is an unsigned result record, not authority by itself. An independent process should rerun the command and compare the complete deterministic report; offline receipt reconstruction continues to show `not verified` until a reference verifier is actually executed.
