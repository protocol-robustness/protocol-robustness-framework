# PRO-28 receipt evidence

This bundle separates the earlier V3 summary from receipt-qualified evidence.

- `legacy-v3-metadata-discrepancy.json` explains why the earlier observations do not establish receipt-level funding or payout identity. The sponsor's 80-unit reserve deposit and the 70-unit allocator funding are separate transfers. Carol's 14-unit entitlement and 10-unit protected-capital refund are distinct transfers; her 24-unit terminal balance is not a 24-unit entitlement claim.
- `raw/` retains the prior separate-workspace local replay. It is useful corroboration, but is still local Anvil evidence.
- `raw/fresh-replay-20261013/` is retained as historical input. It is **superseded**: its Foundry transaction metadata was associated with receipts from different calls. The stricter verifier rejects it.
- `raw/fresh-replay-20261017/` retains a fresh local replay, the original Foundry broadcast outputs, transaction payloads re-fetched by hash from the live local Anvil RPC, matching receipts, and runtime code bytes for all 12 deployed contracts.
- `receipt-reconstruction.json` is the deterministic reconstruction of the earlier separate-workspace replay.
- `fresh-replay-20261017-reconstruction.json` is the deterministic reconstruction of the corrected replay.
- `fresh-replay-pro56-20261018-reconstruction.json` binds the block-41 terminal state observation to its exact replay and transaction receipts.
- `groth16-reference-verification-pro56-20261018.json` records the direct pinned-verifier result for that replay's exact proof fixture.
- The older `receipt-reconstruction.json` and `fresh-replay-reconstruction.json` summaries have correction metadata and no longer claim cryptographic verification; the earlier 2026-10-13 evidence remains rejected by the current strict receipt verifier.

## Inspection and pinned verification

An inspection reconstructs and cross-checks the bundle, but is explicitly
unanchored. It exits successfully only to report that inspection completed; its
machine-readable status is `inspection_unanchored`, not verified evidence:

```bash
python3 scripts/forensic/pro28_receipts.py \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261017 \
  --mode inspect \
  --write-verification-result /tmp/pro28-inspection-result.json
```

For the supported `pro28-local-reconstruction-pin-v1` profile, obtain the
reconstruction digest from a trusted source revision or authenticated channel,
independently of the evidence bundle, and pass it directly to the verifier:

```bash
python3 scripts/forensic/pro28_receipts.py \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261017 \
  --mode verify \
  --trusted-report-sha256 "$(cut -d ' ' -f 1 examples/partial-fill/evidence/pro-28-v1/fresh-replay-20261017-reconstruction.json.sha256)" \
  --expected-report examples/partial-fill/evidence/pro-28-v1/fresh-replay-20261017-reconstruction.json \
  --write-verification-result /tmp/pro28-verification-result.json
```

`--mode verify` fails closed without the explicit trusted digest or if the
reconstructed report does not match it. `--expected-report` is only a content
comparison; it is not a trust anchor. The verification result is separate from
the canonical reconstruction report. This profile establishes that the
supported local reconstruction matches the externally pinned report. It does
not authenticate RPC receipts, prove canonical-chain inclusion or finality, or
upgrade the proof result to cryptographically verified.

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
  --mode verify \
  --expected-report examples/partial-fill/evidence/pro-28-v1/fresh-replay-20261018-reconstruction.json \
  --trusted-report-sha256 "$(cut -d ' ' -f 1 examples/partial-fill/evidence/pro-28-v1/fresh-replay-20261018-reconstruction.json.sha256)"

python3 scripts/forensic/pro28_receipts.py \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261018 \
  --rpc-url http://127.0.0.1:18547 \
  --write-chain-report /tmp/pro28-live-chain-check-20261018.json
```

The second command must pass against the same chain captured by the replay command. Its fingerprint identifies observed chain lineage, not a unique RPC provider or Anvil process. Results remain provider-relative and do not prove provider honesty, public-chain consensus, or finality. This local replay is not public-chain qualification.

The 2026-10-18 reconstruction intentionally does not claim terminal liability closure: its retained `final-state.log` says block 12 while the execution receipts extend through block 41, and the log is not treated as an authoritative state read. A separate PRO-56 capture retains same-block `eth_call` results and a live-chain context check for block 41. Reconstruct that evidence with:

```bash
python3 scripts/forensic/pro28_receipts.py \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-pro56-20261018 \
  --mode verify \
  --terminal-state-observation examples/partial-fill/evidence/pro-28-v1/pro-56-live-state-observation-20261018.json \
  --expected-report examples/partial-fill/evidence/pro-28-v1/fresh-replay-pro56-20261018-reconstruction.json \
  --trusted-report-sha256 "$(cut -d ' ' -f 1 examples/partial-fill/evidence/pro-28-v1/fresh-replay-pro56-20261018-reconstruction.json.sha256)"
```

This verifies the observation's manifest, operation, chain lineage, read targets/selectors, common block identity, raw ABI results, decoded successor operation, retained transaction anchors and zero-liability accounting. It remains a provider-relative local observation, not consensus evidence or public-chain qualification. The old `final-state.log` is retained and hash-checked as text; it does not establish closure.

To independently verify the exact Groth16 fixture against the pinned SP1 verifier runtime, run:

```bash
python3 scripts/forensic/pro28_receipts.py \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-pro56-20261018 \
  --verify-groth16 \
  --write-proof-check /tmp/pro28-pro56-groth16-check.json
cmp /tmp/pro28-pro56-groth16-check.json \
  examples/partial-fill/evidence/pro-28-v1/groth16-reference-verification-pro56-20261018.json
```

The proof check is separate from offline receipt reconstruction. It validates only the retained proof/public values against the pinned verifier; it does not authenticate the local chain observation.

The expected report digest is kept beside, but outside, the raw receipt bundle. The verifier compares its canonical reconstruction bytes to this digest as well as comparing the full report object. An operator must obtain the digest through a trusted source revision or separate authenticated channel; a digest copied from the same untrusted bundle is not an independent trust anchor. Updating the expected report and digest is a reviewed evidence change, not an automatic re-pin.

To independently repeat EVM proof verification and settlement, use the retained proof fixture and the matching `prf-evm` source revision recorded in `capture-metadata.json`, on a disposable local Anvil only. Start Anvil with chain ID 31337, set the test deployer's nonce to `0x4e`, deploy using the recorded deployment inputs, and regenerate the witness from the retained source vector. The manifest digest and witness hash must match before the proof fixture is used. Run `GateEEntitlementDeploymentDerivedRealProofTest` before ratification/funding; it expects the pre-funding reserve commitment. Then execute the funding, activation and withdrawal scripts in that order. Do not treat these local results as public-chain qualification.

The runtime-code observation is unsigned local RPC evidence. Offline reconstruction verifies that the retained bytecode matches its recorded hashes and the addresses in the manifest; it does not establish canonical network state. The separate-workspace replay report is operator separation, not organizational independence.
