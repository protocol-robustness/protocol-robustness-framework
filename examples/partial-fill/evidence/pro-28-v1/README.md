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
  --expected-report examples/partial-fill/evidence/pro-28-v1/fresh-replay-20261017-reconstruction.json \
  --expected-report-sha256 "$(cut -d ' ' -f 1 examples/partial-fill/evidence/pro-28-v1/fresh-replay-20261017-reconstruction.json.sha256)"
```

The verifier reloads the manifest, witness, source vector, proof fixture, original runner broadcasts, RPC-bound transaction/receipt records, terminal observation, and runtime bytecode. It recomputes artifact digests and runtime SHA-256/Keccak identities, checks transaction/receipt one-to-one correspondence, binds the 80-unit reserve deposit to `deposit(80)`, the 70-unit transfer to the fixed-operation funding call, and each 35/21/14 payout to its allocator withdrawal opening. The runner broadcast source hashes are retained and checked separately. A success string in `fork-test.log` is reported only as a test-log observation; it does not establish cryptographic proof validity. This verifier does not rerun SP1 proof verification, so `cryptographicVerification` remains `not_independently_verified`.

To also check the retained transaction and runtime anchors against the currently running local chain:

```bash
python3 scripts/forensic/pro28_receipts.py \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261017 \
  --rpc-url "$RPC_URL" \
  --write-chain-report /tmp/pro28-live-chain-check.json
```


The live check requires capture metadata schema `eae.pro-28-capture-metadata.v2` with `localChainIdentity` (schema `prf.local-chain-fingerprint.v1`), containing chain ID, genesis block hash and a recomputable fingerprint. It compares that identity with the live RPC before checking transaction receipts, block ordering and parent links, runtime code at the captured block with EIP-1898 `requireCanonical`, and current runtime/proxy implementation bindings. The retained 2026-10-17 capture remains V1; it is not retroactively assigned a chain identity.

## Fresh local V2 capture

Use two terminals and keep Anvil running until the final live check finishes.

Terminal 1:

```bash
anvil --host 127.0.0.1 --port 18547 --chain-id 31337
```

Terminal 2, from the PRF repository root:

```bash
python3 scripts/forensic/replay_pro28_local_capture.py \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261017 \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261018 \
  --rpc-url http://127.0.0.1:18547
```

The command accepts only a loopback Anvil endpoint at chain ID 31337 and block zero. It records the genesis identity before changing local state, sets the local test deployer's nonce to `0x4e`, reconstructs and hash-checks the 41 retained signed EIP-1559 envelopes, then replays them in nonce order. It writes a new V2 bundle only after transactions and runtime identities have been checked, and refuses to overwrite an existing output directory. The old V1 bundle is unchanged. The replay uses the original signed transaction payloads; its copied Foundry log remains a historical log observation and does not represent a newly run SP1 SDK verification.

Independently rerun both checks while that same Anvil process is still running:

```bash
python3 scripts/forensic/pro28_receipts.py \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261018 \
  --expected-report examples/partial-fill/evidence/pro-28-v1/fresh-replay-20261018-reconstruction.json \
  --expected-report-sha256 "$(cut -d ' ' -f 1 examples/partial-fill/evidence/pro-28-v1/fresh-replay-20261018-reconstruction.json.sha256)"

python3 scripts/forensic/pro28_receipts.py \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261018 \
  --rpc-url http://127.0.0.1:18547 \
  --write-chain-report /tmp/pro28-live-chain-check-20261018.json
```

The second command must pass against the same chain captured by the replay command. Its fingerprint identifies observed chain lineage, not a unique RPC provider or Anvil process. Results remain provider-relative and do not prove provider honesty, public-chain consensus, or finality. This local replay is not public-chain qualification.

The expected report digest is kept beside, but outside, the raw receipt bundle. The verifier compares its canonical reconstruction bytes to this digest as well as comparing the full report object. An operator must obtain the digest through a trusted source revision or separate authenticated channel; a digest copied from the same untrusted bundle is not an independent trust anchor. Updating the expected report and digest is a reviewed evidence change, not an automatic re-pin.

To independently repeat EVM proof verification and settlement, use the retained proof fixture and the matching `prf-evm` source revision recorded in `capture-metadata.json`, on a disposable local Anvil only. Start Anvil with chain ID 31337, set the test deployer's nonce to `0x4e`, deploy using the recorded deployment inputs, and regenerate the witness from the retained source vector. The manifest digest and witness hash must match before the proof fixture is used. Run `GateEEntitlementDeploymentDerivedRealProofTest` before ratification/funding; it expects the pre-funding reserve commitment. Then execute the funding, activation and withdrawal scripts in that order. Do not treat these local results as public-chain qualification.

The runtime-code observation is unsigned local RPC evidence. Offline reconstruction verifies that the retained bytecode matches its recorded hashes and the addresses in the manifest; it does not establish canonical network state. The separate-workspace replay report is operator separation, not organizational independence.
