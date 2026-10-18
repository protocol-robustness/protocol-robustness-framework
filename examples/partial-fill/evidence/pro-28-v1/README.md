# PRO-28 receipt evidence

This bundle separates the earlier V3 summary from receipt-qualified evidence.

- `legacy-v3-metadata-discrepancy.json` explains why the earlier observations do not establish receipt-level funding or payout identity. The sponsor's 80-unit reserve deposit and the 70-unit allocator funding are separate transfers. Carol's 14-unit entitlement and 10-unit protected-capital refund are distinct transfers; her 24-unit terminal balance is not a 24-unit entitlement claim.
- `raw/` retains the prior separate-workspace local replay. It is useful corroboration, but is still local Anvil evidence.
- `raw/fresh-replay-20261013/` is retained as historical input. It is **superseded**: its Foundry transaction metadata was associated with receipts from different calls. The stricter verifier rejects it.
- `raw/fresh-replay-20261017/` retains a fresh local replay, the original Foundry broadcast outputs, transaction payloads re-fetched by hash from the live local Anvil RPC, matching receipts, and runtime code bytes for all 12 deployed contracts.
- `receipt-reconstruction.json` is the deterministic reconstruction of the earlier separate-workspace replay.
- `fresh-replay-20261017-reconstruction.json` is the deterministic reconstruction of the corrected replay.
- The older `receipt-reconstruction.json` and `fresh-replay-reconstruction.json` summaries have correction metadata and no longer claim cryptographic verification; the earlier 2026-10-13 evidence remains rejected by the current strict receipt verifier.

## Independent reconstruction

From a fresh PRF checkout with Python 3 and Foundry's `cast` available:

```bash
python3 scripts/forensic/pro28_receipts.py \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261017 \
  --expected-report examples/partial-fill/evidence/pro-28-v1/fresh-replay-20261017-reconstruction.json
```

The verifier reloads the manifest, witness, source vector, proof fixture, original runner broadcasts, RPC-bound transaction/receipt records, terminal observation, and runtime bytecode. It recomputes artifact digests and runtime SHA-256/Keccak identities, checks transaction/receipt one-to-one correspondence, binds the 80-unit reserve deposit to `deposit(80)`, the 70-unit transfer to the fixed-operation funding call, and each 35/21/14 payout to its allocator withdrawal opening. The runner broadcast source hashes are retained and checked separately. A success string in `fork-test.log` is reported only as a test-log observation; it does not establish cryptographic proof validity. This verifier does not rerun SP1 proof verification, so `cryptographicVerification` remains `not_independently_verified`.

To also check the retained transaction and runtime anchors against the currently running local chain:

```bash
python3 scripts/forensic/pro28_receipts.py \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261017 \
  --rpc-url "$RPC_URL" \
  --write-chain-report /tmp/pro28-live-chain-check.json
```


The live check requires capture metadata schema `eae.pro-28-capture-metadata.v2` with `localChainIdentity` (schema `prf.local-chain-fingerprint.v1`), containing chain ID, genesis block hash and a recomputable fingerprint. It compares that identity with the live RPC before checking transaction receipts, block ordering and parent links, runtime code at the captured block with EIP-1898 `requireCanonical`, and current runtime/proxy implementation bindings. The retained 2026-10-17 capture is V1 and predates this field, so it fails closed for the strengthened live check and must be freshly captured before claiming this check passed. The fingerprint identifies observed chain lineage, not a unique RPC provider or Anvil process. Results remain provider-relative and do not prove provider honesty, public-chain consensus, or finality. For public-chain evidence, a future verifier must require an explicitly selected chain-specific confirmation policy and report observed depth separately; confirmations alone must not be called finality.

To independently repeat EVM proof verification and settlement, use the retained proof fixture and the matching `prf-evm` source revision recorded in `capture-metadata.json`, on a disposable local Anvil only. Start Anvil with chain ID 31337, set the test deployer's nonce to `0x4e`, deploy using the recorded deployment inputs, and regenerate the witness from the retained source vector. The manifest digest and witness hash must match before the proof fixture is used. Run `GateEEntitlementDeploymentDerivedRealProofTest` before ratification/funding; it expects the pre-funding reserve commitment. Then execute the funding, activation and withdrawal scripts in that order. Do not treat these local results as public-chain qualification.

The runtime-code observation is unsigned local RPC evidence. Offline reconstruction verifies that the retained bytecode matches its recorded hashes and the addresses in the manifest; it does not establish canonical network state. The separate-workspace replay report is operator separation, not organizational independence.
