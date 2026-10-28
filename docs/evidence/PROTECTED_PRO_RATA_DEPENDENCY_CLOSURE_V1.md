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
  examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261018 \
  --trusted-report-sha256 9ba1ffccdc9acb94fe525dde1ea93e78d3fc986448ae0ccdc5884625cdc14982 \
  --output /tmp/pro-113-dependency-closure.json
```

To recheck retained blocks, receipts and code against one currently available
RPC, add `--rpc-url <endpoint>` and optionally `--min-confirmations N`. This is
provider-relative revalidation; it does not establish independent consensus
or public finality. The CLI's zero exit code means the report was reconstructed
and written. Consumers must inspect `result`; this fixture reports
`not_qualified`.

## Status semantics

- `observed_and_hash_checked` means runtime bytes in the retained local bundle
  were checked against the addresses and digests in that bundle. It does not
  mean the code is approved or currently deployed at that address.
- `bound_between_manifest_witness_and_fixture` means the existing verifier
  cross-checked those identities. Cryptographic proof verification remains
  `not_independently_verified` in this profile.
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
