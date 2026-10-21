# PRO-95 Base Sepolia demonstration package

**Demonstration only. Not a pre-mainnet release.** This package demonstrates
the Stage B signed-input adapter using synthetic requests and generated demo
keys. It does not establish production source authenticity or completeness,
authorize real funding, deploy contracts, or record a Base Sepolia transaction.

The controlled example contains requests of 50, 30, and 20 `DEMO_UNIT`s; an
illustrative reserve observation of 80 units, of which 10 are restricted and
70 are available; and a declared largest-remainder allocation of 35, 21, and
14. The remaining 30 are unmet requests in this fixture, not recognized debt
or claimant liabilities. The Stage B example uses pseudonymous labels and placeholder destination
addresses. The generated recipient keys listed separately below have not yet
been bound into a Base Sepolia witness/proof or approved for use. The example
uses no USDC or other real token.

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

`stage-c-evidence-status.edn` records that public-chain qualification has not
run. The Base Sepolia deployment templates under `etc/deployments/prf-base-sepolia/`
remain owner-approval-required and contain placeholder verifier and authority
values. This demo package does not turn those templates into an approved
deployment.

A dedicated encrypted test-only deployer and three encrypted recipient wallets
have been generated outside the repository at the owner-approved secure
location. Their public addresses are recorded in
[`demo-recipient-wallets.json`](./demo-recipient-wallets.json); they are not
funded or approved for this run. A read-only preflight
from `https://sepolia.base.org` returned chain ID `84532` and head
`49259922` (`0x49a868fe73ae4775215dd0eef0b8a4a01e9be8d1c61c2c9badf68cf9bae5f37e`); see
[`rpc-preflight-20261023.json`](./rpc-preflight-20261023.json). This is a
single-provider observation, not independent header or finality evidence.
Blockscout reports the same hash for this block; it remains only a candidate
second observer, not a selected trust anchor. At
block 49260186, the deployer balance was reported as zero; the raw Foundry
result is retained with the block-pinned balance query in the preflight artifact.

Stage C still requires an owner-approved demonstration deployment and verifier
configuration, approved signer role and spend budget, approval of the generated
recipient mapping, a Base Sepolia-bound source witness/proof, a reviewed
Base-specific deployment/operation runner, real transaction and receipt
artifacts, and an independently selected header-pin source. Base safe/finalized
status remains outside this bounded demo and unverified. No public transaction has been submitted.
Any future deployment must remain labelled demonstration only; it does not
qualify the system for pre-mainnet release.

Base documents chain ID 84532 and the public RPC endpoint
https://sepolia.base.org for Base Sepolia. The endpoint was queried read-only,
as recorded above. A single RPC response does not authenticate the header; the
matching Blockscout response is only a candidate second observation. Neither
observation establishes Base safe/finalized status. Stage C.
