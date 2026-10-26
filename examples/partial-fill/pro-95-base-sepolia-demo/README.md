# PRO-95 Base Sepolia demonstration package

**Demonstration only. Not a pre-mainnet release.** This package demonstrates
the Stage B signed-input adapter using synthetic requests and generated demo
keys. It does not establish production source authenticity or completeness,
authorize real funding, deploy contracts, or record a Base Sepolia transaction.

The controlled scenario uses Circle Base Sepolia testnet USDC at
`0x036CbD53842c5426634e7929541eC2318f3dCF7e` (6 decimals): requests of 50,
30, and 20 USDC; an 80 USDC deposit, of which 10 is restricted/refundable and
70 is available; and allocations of 35, 21, and 14 USDC. Corresponding amounts
are encoded in six-decimal base units in the deployment profile. The remaining
30 USDC of requests are unmet, not recognized debt or claimant liabilities.
Stage B remains a synthetic signed-input fixture and does not evidence USDC
balances or transfers. The selected contract is Circle testnet USDC, not
mainnet-redeemable USDC. The same address is referenced in the SEW Aave Base
Sepolia integration sources; this PRO-95 workflow does not use Aave.

The source, policy, beneficiary, and funding envelopes are signed with
ephemeral Ed25519 demonstration keys. Their public keys are included in the
package trust policies so a fresh process can verify the signatures. Those
bundled keys are self-selected demonstration trust roots, not independent
source, governance, beneficiary, or funding authorities. Private keys were
not retained. `:bound` means only that the demo envelope structure,
signatures, roots, and coverage validate under the bundled demo keys.

## Verify Stage B demonstration

From the repository root:

```sh
clojure -M:test scripts/forensic/verify-pro95-base-sepolia-demo-package.clj
```

The verifier independently rechecks signatures, cross-bindings, the
demonstration-only scope markers, and the stated 100 / 70 / 35-21-14 / 30
fixture arithmetic. It does not trust the embedded verification summary.

## Stage C status

See [`TESTNET_PREPARATION.md`](./TESTNET_PREPARATION.md) for the owner inputs,
configuration fields, safe setup, transaction evidence, Base finality gates,
and completion checklist required before any testnet run.
[`FINALITY_QUALIFICATION.md`](./FINALITY_QUALIFICATION.md) defines the
demonstration's evidence classes and expressly separates receipt inclusion,
Base safe/finalized L2 status, and L1 withdrawal-output finalization.

`stage-c-evidence-status.edn` records that public-chain qualification has not
run. The Base Sepolia deployment templates under `etc/deployments/prf-base-sepolia/`
remain owner-approval-required and contain placeholder verifier and authority
values. This demo package does not turn those templates into an approved
deployment.

A dedicated encrypted test-only deployer and three encrypted recipient wallets
have been generated outside the repository at the owner-approved secure
location. Their public addresses are recorded in
[`demo-recipient-wallets.json`](./demo-recipient-wallets.json); their mapping is
approved for this demonstration, but they are not yet bound into the Base
Sepolia witness/proof. The recipients returned zero native ETH in a
single-provider, unpinned `latest` observation on 2026-10-23; current recipient
balances are unknown. The signer and payer returned zero ETH and USDC,
respectively, at the block-pinned 2026-10-25 preflight; their current balances
also require rechecking. The approval and its exclusions are recorded in
[`owner-decisions-20261023.edn`](./owner-decisions-20261023.edn); it does not
set a transaction count, gas cap, or funding amount. No testnet transaction has
been signed or broadcast. One test-only EOA is
approved to act as deployer, USDC payer/funder, configuration authority, and
withdrawal relayer; this is centralized demonstration control, not independent
authority separation. A read-only token/source observation at block
`50091313`, hash
`0x6f025c552b5df58108ae1bd753a511d3f87c0804005abcf948a2e0541816334c`,
is retained in `protocol-deployments` at
`config/pro-95-base-sepolia-demo/observations/usdc-read-only-20261025.json`.
A separate balance preflight at block `50204763`, hash
`0x0472d6d8d50c6e5b6f9370340ec2c0a197c154df69165a0238a97bd387e2d4c8`,
found zero deployer ETH, zero payer USDC and nonce zero at both queried RPCs;
allowance was not observed. Its raw request/response artifact is
`config/pro-95-base-sepolia-demo/observations/usdc-funding-preflight-20261025-50204763.json`
(SHA-256 `b9889d3d187a9a0d09d0a358261d76089138e698d0e611b1a4b76d1e825b90fc`).
Both records are read-only observations, not independent trust or finality
evidence. The older single-provider preflight remains available at
[`rpc-preflight-20261023.json`](./rpc-preflight-20261023.json).

Stage C still requires a demonstration deployment and verifier configuration,
a numeric transaction and ETH spend cap, a Base Sepolia-bound source
witness/proof, a reviewed Base-specific deployment/operation runner, real
transaction and receipt artifacts, and independent review of the header source
and freshness/reorg policy. Base safe/finalized
status remains outside this bounded demo and unverified. No public transaction has been submitted.
Any future deployment must remain labelled demonstration only; it does not
qualify the system for pre-mainnet release.

The Base Sepolia initial-configuration root is derived for the USDC-bound
candidate policy as `sha256:f36913f0fb319e80aee3a1b795bd31a94486d08d51aa27df825277050e4768cb`; the authority-state root is unchanged. Both are
candidate derivations only and have not been approved for deployment. The chain-instance genesis root still requires the
deployment addresses and runtime code hashes; the local proof and witness do
not bind the approved recipients or Base Sepolia state.

Base documents chain ID 84532 and the public RPC endpoint
https://sepolia.base.org for Base Sepolia. The endpoint was queried read-only,
as recorded above. A single RPC response does not authenticate the header; the
matching Blockscout response is only a candidate second observation. Neither
observation establishes Base safe/finalized status. Stage C.
