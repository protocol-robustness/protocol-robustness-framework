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

`verify-withdrawal-attribution` requires a complete reconstruction of all
known claimable categories. It accepts the withdrawal as bounty-attributable
only when all non-bounty categories are zero and both the bounty category and
withdrawal amounts equal the route amount. Missing categories, unknown
categories, mixed claims, and mismatched workflow, asset, or beneficiary fail
closed. Each ordered event also binds its source sequence and predecessor route
root; stale and duplicate events fail closed. Isolation of a workflow alone is
not evidence of attribution. Each event also binds the route's chain-instance
root, asset, workflow, and funding-source root.

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
contains revision `be00f52684893b468a4f4fa584893711e536e95e`. At that revision,
`contracts/core/BaseEscrow.sol` is blob
`b451174aade17707c458e2c00d9de2b30a98f454` and
`test/foundry/core/WithdrawEscrow.t.sol` is blob
`7358f38c12b9c8462cf82a052b080a412aafb6a3`; both blobs match the inspected
checkout files. The source and test files are revision-pinned, while the broken
worktree and external `forge-std` symlink prevent pinning the complete
dependency tree. A test run from a clean archive of that revision was
attempted with
`forge test --root <archive> --offline --match-path test/foundry/core/WithdrawEscrow.t.sol --out <archive>/out --cache-path <archive>/cache-foundry -vv`
and exited 1 before compilation because `solc` 0.8.37 is unavailable offline
(installed CLI solc is 0.8.25 and linked solcjs is 0.8.26). The file SHA-256
observations were `4c7162991be853052a737857c789a2904a00ae00b9e7a9bb08206922f37486cb`
for `BaseEscrow.sol` and
`6bb860473524a98e88a1d9a05acdad5da21276c15b33bafbf7753141b70e3c55` for
`WithdrawEscrow.t.sol`.

The route adapter tests establish only model mapping, transition, and
category-attribution behavior. They do not satisfy the controlled Sew deposit
→ release → withdraw acceptance path. Canonical
`obligation-settlement.v1` in this checkout is limited to the direct native
transfer route and its `BountyPaid` observation. Sew withdrawal evidence needs
a reviewed versioned settlement-interface decision before it can be replayed
as a bounty payout. No Sew core contract behavior is added here.
