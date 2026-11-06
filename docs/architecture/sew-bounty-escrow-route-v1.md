# Sew bounty escrow route v1

This adapter records an optional bounty to Sew workflow mapping and a
model-only journal of route events. It does not change the canonical bounty
obligation or settlement schemas.

## Mapping and attribution

Each route binds one obligation root, payable root, backing root, exact
beneficiary address, asset, chain-instance root, amount, funding-source root,
and Sew workflow ID. A route set must have one mapping per obligation and one
per workflow. The route hash commits to this mapping and its ordered event
journal. A pseudonymous address is sufficient; legal identity is not inferred.

`verify-withdrawal-attribution` requires the caller to supply the expected
obligation root and a complete reconstruction of all known claimable
categories. It accepts the withdrawal as bounty-attributable only when the
expected obligation, route, workflow, asset, beneficiary and exact amount
match, all non-bounty categories are zero, and both the bounty category and
withdrawal amounts equal the route amount. Missing or unknown categories,
negative or malformed category amounts, mixed claims, and mismatched context
fail closed. Each ordered event also binds its source sequence and predecessor
route root; stale and duplicate events fail closed. Isolation of a workflow
alone is not evidence of attribution. Each event also binds the route's
chain-instance root, asset, workflow, and funding-source root.

The regression suite now exercises substituted obligation context, altered
route roots and fields, beneficiary/token/workflow/amount mismatches, incomplete
and unknown category sets, negative and malformed category amounts, mixed
claims, duplicate/stale events, invalid transitions, and partial payout
amounts. These are model-adapter checks against caller-supplied reconstruction
data. They do not prove that a real Sew withdrawal is bounty-attributable.

## Evidence boundary

The route and event journal have trust class `model-only`. Event evidence roots
are references supplied to the model; this adapter does not authenticate
receipts, query an EVM node, verify consensus inclusion, or establish finality.
The event journal distinguishes deposit, release, claimable, withdrawal
submission, withdrawal failure, cancellation, and dispute. Release and
withdrawal submission do not discharge an obligation. A failed withdrawal
keeps the route amount outstanding.

Any payment claim must still pass the existing canonical
`obligation-settlement.v1` verifier with its signed payout and chain-observer
evidence, custody/liability reconciliation, chain instance, asset,
beneficiary, and source bindings. The v1 settlement path in this checkout is
the direct native-transfer example; this route adapter does not adapt bulk Sew
withdrawal into that settlement artifact.

## Controlled execution limitation

This checkout contains the controlled `BountySettlement` direct-transfer
example, but no Sew escrow Solidity implementation or controlled Sew adapter
fixture. A separate Sew checkout was inspected read-only: its `BaseEscrow`
withdrawal reads and clears the full workflow/recipient claimable balance and
emits `EscrowWithdrawn` without a bounty-category allocation. That checkout's
`.git` worktree target is missing, but the local Sew Git object database
contains revision `be00f52684893b468a4f4fa584893711e` in the
`hardhat-deploy-hybrid` Git object store. At that revision,
`contracts/core/BaseEscrow.sol` is blob
`b451174aade17707c458e2c00d9de2b30a98f454` and
`test/foundry/core/WithdrawEscrow.t.sol` is blob
`7358f38c12b9c8462cf82a052b080a412aafb6a3`; both blobs match the inspected
checkout files. An isolated archive of that revision compiled offline with
Solidity 0.8.37 and Foundry 1.7.1. The pinned `WithdrawEscrow.t.sol` suite
passed all 6 tests. The replay used OpenZeppelin Contracts 5.4.0, as specified
in that revision's `pnpm-lock.yaml`, and the available external `forge-std`
checkout at commit `e3386f2f9cc5ebf16ec5370869d5a49cdb85a0cb`; the Sew source
itself still uses an unpinned external symlink for `forge-std`. Source
SHA-256 observations are `4c7162991be853052a737857c789a2904a00ae00b9e7a9bb08206922f37486cb`
for `BaseEscrow.sol` and
`6bb860473524a98e88a1d9a05acdad5da21276c15b33bafbf7753141b70e3c55` for
`WithdrawEscrow.t.sol`.

At this pinned source revision, `withdrawEscrow` reads only
`claimableBalances[workflowId][recipient]`, clears it, decrements
`totalClaimableAssets`, performs the token transfer, then emits
`EscrowWithdrawn`. Bond protocol fees and excess ETH refunds use separate
claim functions. However, the contract does not label a balance as a bounty,
and the claimable balance may combine principal and yield when yield is
enabled. Attribution therefore requires a source-bound workflow decision and
reconstruction of the relevant state; an isolated workflow ID alone is not a
bounty fact. The supplemented passing suite is pinned local Sew withdrawal
evidence, not a PRO-88 bounty-to-settlement integration or an independent
replay.

The pinned Sew test file has SHA-256
`6bb860473524a98e88a1d9a05acdad5da21276c15b33bafbf7753141b70e3c55`.
The retained supplemental test patch is
[`withdrawal-negative-tests.patch`](../qualifications/pro88/withdrawal-negative-tests.patch)
(SHA-256 of the patched test file:
`c26fb20c1e3013cb2f6214bc05d149884b3bfbda009ea5c1b8de7e028407051b`). It
adds two local contract checks: a non-beneficiary cannot claim; and a failed
ERC-20 transfer preserves both the per-recipient claim and aggregate liability,
which can be retried once after refill but not paid twice. These exercise the
pinned Sew contract in local Foundry, not Base Sepolia or authenticated public
chain receipts.

To reconstruct the local test tree from the pinned Sew source archive, copy the
original test into the isolated test directory and apply the retained patch:

```sh
mkdir -p /tmp/pro88-local-qualification/test/foundry/pro88
cp /tmp/pro88-local-qualification/test/foundry/core/WithdrawEscrow.t.sol \
  /tmp/pro88-local-qualification/test/foundry/pro88/WithdrawEscrow.t.sol
cd /tmp/pro88-local-qualification
patch -p1 < /home/user/Code/.workspaces/agent-c-3/docs/qualifications/pro88/withdrawal-negative-tests.patch
```

The isolated test command is:

```sh
forge test \
  --root /tmp/pro88-local-qualification \
  --offline \
  --use /home/user/.local/share/svm/0.8.37/solc-0.8.37 \
  --match-path test/foundry/pro88/WithdrawEscrow.t.sol \
  --out /tmp/pro88-local-qualification/out-pro88 \
  --cache-path /tmp/pro88-local-qualification/cache-pro88 \
  -vv
```

That temporary tree was assembled from the pinned Sew Git archive, the
source test file plus retained supplemental patch, `forge-std` archived from commit
`e3386f2f9cc5ebf16ec5370869d5a49cdb85a0cb`, and OpenZeppelin Contracts 5.4.0.
Its isolated Foundry config compiled `contracts/core` and the selected test
only. The run reports 8 passed, 0 failed, 0 skipped. It is local Foundry EVM
execution; it does not create retained transaction receipts or public-chain
evidence.

The route adapter tests establish only model mapping, transition, and
category-attribution behavior. The supplemented local Sew suite additionally
checks wrong-caller rejection, failed-transfer state rollback, retry after
refill, and duplicate-withdrawal rejection against the pinned contract. Neither
suite exercises a PRF bounty route against real Sew receipt/state data, proves
category attribution for the bulk claimable balance, or provides independent
payout reconstruction. A substituted evidence-root pointer can remain
well-formed in the model adapter: authentication and receipt-substitution
detection require the still-missing reviewed settlement interface and trusted
chain-observation verifier. No such detection is claimed here.
Canonical
`obligation-settlement.v1` in this checkout is limited to the direct native
transfer route and its `BountyPaid` observation. Sew withdrawal evidence needs
a reviewed versioned settlement-interface decision before it can be replayed
as a bounty payout. No Sew core contract behavior is added here.

## Testnet dependency handoff

The deployment repository's existing, uncommitted Sew profile candidate now
records Circle Base Sepolia testnet USDC as an observed external dependency in
`config/sew-core/standalone-profile.v1.json`. Its source is
`config/pro-95-base-sepolia-demo/observations/usdc-read-only-20261025.json`:
token `0x036CbD53842c5426634e7929541eC2318f3dCF7e`, six decimals, observed at
block `50091313` with the proxy and implementation code hashes retained.
This is a historical, unqualified read-only observation; refresh the code and
implementation pointer before use. It does not establish transfer behavior,
source authentication, finality, available future balance, or permission to
transact. The profile keeps deployment and broadcast disabled.

This supplies only a test-asset dependency. The Sew escrow contracts needed for
the PRO-88 contract path are not yet deployed in that profile. Its seven-entry
Sew core graph is still blocked: the selected `EscrowVault` runtime is 38,983
bytes, above the current 24,576-byte EIP-170 limit, and Base Sepolia activation
of the planned size-limit change has not been confirmed. Role addresses,
profile approval, fresh external-code observations, and independent review are
also outstanding. The model adapter therefore remains separate from any live
deployed workflow or payout evidence.

A fresh read-only Base Sepolia creation probe on 2026-10-27 at block
51,069,238 again accepted a 24,576-byte control and rejected the pinned
38,983-byte Sew vault runtime with `CreateContractSizeLimit` (chain ID 84532;
`eth_call`; unsigned; no broadcast). The supplied deployer key and reported
test balances do not remove this network-level blocker or qualify unresolved
Timelock, guardian, resolver, fee-recipient, and independent-reviewer roles.
No deployment transaction was prepared or signed.
