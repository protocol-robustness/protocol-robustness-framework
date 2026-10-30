# Protected pro-rata dependency closure V1

`prf.deployment-dependency-closure.v1` is a diagnostic projection over the
retained PRO-28 local execution bundle. It reuses
`scripts/forensic/pro28_receipts.py` for receipt, manifest, proof-binding,
runtime-byte, and transfer-event reconstruction. It adds no canonical root,
deployment registry, or admission authority.

## Reproduce

Obtain the reconstruction digest from a trusted revision or authenticated
channel. Do not take it from a file shipped beside the evidence bundle.

```bash
python3 scripts/forensic/pro113_dependency_closure.py \
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-pro56-20261018 \
  --trusted-report-sha256 97daff0e6ae6907d4c0bc501f091f41a5d7d160d227cee031c2118ce2f8ba26f \
  --terminal-state-observation examples/partial-fill/evidence/pro-28-v1/pro-56-live-state-observation-20261018.json \
  --verify-groth16 \
  --output /tmp/pro-113-dependency-closure.json
```

To recheck retained blocks, receipts and code against one currently available
RPC, add `--rpc-url <endpoint>` and optionally `--min-confirmations N`. This is
provider-relative revalidation; it does not establish independent consensus
or public finality. The CLI's zero exit code means the report was reconstructed
and written. Consumers must inspect `result`; this fixture reports
`not_qualified`.

To repeat the exact proof check against the pinned SP1 Groth16 verifier runtime
on a disposable local Anvil, add `--verify-groth16`. This reuses the
PRO-28-owned verifier, reports its identity-bound result under
`proofVerification`, and does not submit a transaction. `not_verified` and
`unsupported` do not count as proof success. A `verified` proof still does not
establish program admission, source authority, or deployment qualification.
The report's `qualificationGates` separately records that configuration and
registry state are not rechecked by the PRO-28 live receipt/runtime verifier,
even when `--rpc-url` is used.

The report records SHA-256 identities for the exact PRO-113 adapter and reused
PRO-28 verifier source files, along with the relevant invocation options.
These fingerprints identify the evaluator bytes; they are not signed source
provenance or a substitute for pinning the checkout commit. The retained
`dependency-closure-v1.json` was reconstructed with the command above and
contains a `verified` result for this exact 288-byte fixture. Its overall
result remains `not_qualified`.

The retained report matches each manifest contract address to its validated
successful top-level creation receipt where available, including transaction,
block and deployer identity. This binds a receipt's reported created address;
it does not independently derive the CREATE address or prove reproducible
creation bytecode. The optional PRO-56 observation adds block-pinned terminal
balances and liability state. It does not contain per-transaction balance
deltas, and its RPC observation remains unsigned and local-only.

## Status semantics

- `observed_and_hash_checked` means runtime bytes in the retained local bundle
  were checked against the addresses and digests in that bundle. It does not
  mean the code is approved or currently deployed at that address.
- `bound_between_manifest_witness_and_fixture` means the existing verifier
  cross-checked those identities. Cryptographic proof verification is
  `not_run` unless `--verify-groth16` is explicitly requested; the exact
  reusable verifier result is retained separately.
- `unknown` means the bundle does not establish approval, effective proxy
  implementation, upgrade authority, current configuration state, or token
  issuer controls. Unknown never passes qualification.
- `detectedOnly` identifies retrospective verifier detection. It is not a
  preventative contract control.
- The out-of-band reconstruction digest authenticates a deterministic report
  byte sequence relative to the digest source. It does not approve the
  dependencies or establish the truth of the RPC observations.

The existing PRO-28 verifier remains authoritative for raw execution evidence.
This report intentionally does not repeat receipt inclusion verification,
deployment execution, package release qualification, or canonical PRF root
derivation. The older PRO-28 proof pin is not interchangeable with the current
V0 successor proof profile. This report therefore cannot qualify the PRO-112
V0 deployment or serve as public-chain readiness evidence.

The `--rpc-url` path checks receipt inclusion and runtime identities against
one provider. It does not call configuration-executor or verifier-registry
state getters, so `configurationObservationStatus` and
`registryObservationStatus` remain `not_rechecked`. The bundle also lacks an
owner-approved dependency profile, authenticated role/upgrade authority,
deployment artifact linkage for every runtime, and public-chain evidence.
Those are explicit qualification blockers; the fixture is a diagnostic result,
not a successful qualified profile.

## Mismatch evidence references

The following tests demonstrate the distinction between enforcement and
retrospective detection; neither is represented as an effect of this report:

- On-chain route rejection: in `prf-evm`, run
  `PARTIAL_FILL_STAGE=2 forge test --match-path test/PartialFillV3Controlled.t.sol --match-test test_RejectUnauthorizedAndRouteBypass`.
  The contract path rejects unauthorized and bypass funding attempts without
  state changes. Stage 2 is required; the default stage-0 setup is incomplete
  for this test.
- Off-chain evidence mismatch detection: in PRF, run
  `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python3 -m pytest -q test/forensic_python/test_pro28_receipts.py::test_tampered_runtime_observation_rejects`.
  The verifier rejects a substituted address in the retained runtime
  observation. This is detection after the fact, not a transaction guard.
