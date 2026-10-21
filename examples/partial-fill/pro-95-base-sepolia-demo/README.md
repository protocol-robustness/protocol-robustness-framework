# PRO-95 Base Sepolia demonstration package

**Demonstration only. Not a pre-mainnet release.** This package demonstrates
the Stage B signed-input adapter using synthetic requests and generated demo
keys. It does not establish production source authenticity or completeness,
authorize real funding, deploy contracts, or record a Base Sepolia transaction.

The controlled example contains requests of 50, 30, and 20 `DEMO_UNIT`s; an
illustrative reserve observation of 80 units, of which 10 are restricted and
70 are available; and a declared largest-remainder allocation of 35, 21, and
14. The remaining 30 are unmet requests in this fixture, not recognized debt
or claimant liabilities. The example uses pseudonymous labels and placeholder
destination addresses. It uses no USDC or other real token.

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

`stage-c-evidence-status.edn` records that public-chain qualification has not
run. The Base Sepolia deployment templates under `etc/deployments/prf-base-sepolia/`
remain owner-approval-required and contain placeholder verifier and authority
values. This demo package does not turn those templates into an approved
deployment.

Stage C still requires a demonstration deployment configuration with approved
demo contract identities, a funded Base Sepolia demo signer, real transaction
and receipt artifacts, and an independently selected Base/L1 header and
finality policy. No public transaction has been submitted as part of preparing
this package. Any future deployment must remain labelled demonstration only;
it does not qualify the system for pre-mainnet release.

Base documents chain ID `84532` and the public RPC endpoint
`https://sepolia.base.org` for Base Sepolia. The endpoint has not been queried
for this package; a public RPC response alone would not provide the independent
header trust or Base/L1 finality evidence required by Stage C.
