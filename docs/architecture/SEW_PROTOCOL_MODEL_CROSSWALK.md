# Sew protocol model to Solidity crosswalk

Status: initial scope map; not a conformance claim.

sew-protocol-model names PRF's executable behavioral model. It is intended to
describe protocol behavior that can be compared with the Solidity implementation
in ~/Code/.workspaces/sew-protocol. It does not claim the same package
boundary, dependency graph, or deployment responsibilities.

The supported sew-protocol-model profile also selects the PRF runner and
slash-evidence adapter. Those are profile composition choices, not part of the
meaning of the protocol-model component. The profile is not yet a standalone
package qualification.

## Initial behavior map

| Behavior | PRF model | Solidity implementation | Alignment status |
| --- | --- | --- | --- |
| Escrow lifecycle and guarded transitions | protocols_src/resolver_sim/protocols/sew/{types,state_machine,lifecycle}.clj; escrow-kernel component | [BaseEscrow.sol](../../../sew-protocol/contracts/core/BaseEscrow.sol), [EscrowVault.sol](../../../sew-protocol/contracts/core/EscrowVault.sol), [EscrowableERC20.sol](../../../sew-protocol/contracts/core/EscrowableERC20.sol) | Intended shared behavior; compare transitions and boundary cases |
| Dispute resolution and appeal-bond accounting | protocols_src/resolver_sim/protocols/sew/resolution.clj, appeal_bond_projection.clj, and accounting_projection.clj | [DecentralizedResolutionModule.sol](../../../sew-protocol/contracts/modules/decentralized-resolution-module/DecentralizedResolutionModule.sol), [ResolverIncentiveModule.sol](../../../sew-protocol/contracts/modules/decentralized-resolution-module/incentive/ResolverIncentiveModule.sol), [BondLedger.sol](../../../sew-protocol/contracts/shared/BondLedger.sol) | Shared domain; module boundaries differ |
| Yield accrual and settlement | yield_runtime_src/resolver_sim/yield/ and protocols_src/resolver_sim/protocols/sew/yield/ | [EscrowYield.sol](../../../sew-protocol/contracts/core/EscrowYield.sol), [AaveYieldModule.sol](../../../sew-protocol/contracts/modules/AaveYieldModule.sol), IYieldModule | Shared behavior where selected module semantics overlap; simulated providers are not Solidity package equivalents |
| Slash allocation and evidence | Sew pro-rata economics, slash projection, and the optional slash-evidence adapter | ResolverIncentiveModule and ResolverIncentiveModuleV2BondLedger | Compare economic outcomes and evidence claims; PRF evidence artifacts are off-chain |
| Replay receipts, research, and runner assembly | PRF contract-model replay, scenario runner, and research namespaces | No direct on-chain package counterpart | PRF tooling; excluded from a Solidity-core package equivalence claim |
| Governance, deployment, and upgrades | Model-specific governance behavior where simulated | Solidity governance/admin contracts and deployment tooling | Map individual protocol rules when behavior is shared; deployment infrastructure is not model-core parity |

## Sew-core PRF extension target

The Solidity `@sew/sew-core` package defines a narrower product profile than
the current PRF `sew-protocol-model`. A PRF extension aligned with it should be
a separately named behavioral model and conformance layer alongside the
Solidity package, not a rename or extraction of the full protocol model.

| Solidity core operation | Existing PRF candidate | Work required before claiming alignment |
|---|---|---|
| Create and fund escrow | `sew.lifecycle/create-escrow` | Define the core profile's fixed configuration and assert that yield is `:off`; the PRF world models accounting, not ERC-20 calls. |
| Participant release | `sew.lifecycle/release` | Constrain the selected release module and compare terminal state, fees, and pull claims. |
| Bilateral cancellation/refund | `sew.lifecycle/sender-cancel` and `recipient-cancel` | The PRF model supports configurable cancellation strategies. Specify the core profile's native bilateral rule and reject strategy-based behavior. |
| One resolver dispute and resolution | `sew.lifecycle/raise-dispute` plus `sew.resolution/execute-resolution` | Constrain the profile to one resolver and exclude appeal, Kleros, reversal, and multi-level dispute behavior. |
| Timeout refund | `sew.lifecycle/auto-cancel-disputed-escrow` and `auto-cancel-disputed-on-auto-time` | Select the matching timeout rule and compare exact boundary timestamps and refund claims. |
| Pull settlement | Sew claimable accounting and the `withdraw-escrow` action | Bind the PRF claim ledger to the Solidity pull-claim recipient and amount rules. |
| Guardian intake pause | `set-paused` model action | Define guardian-only pause and Timelock-only recovery; the current model authorization rules need a specific parity check. |

The existing 30-file `sew-kernel` candidate does not cover these operations by
itself. An operation-limited in-tree adapter now exists as the separate
`sew-core-extension` candidate profile. It validates its action and feature
boundary and delegates supported behavior to the full Sew model. The adapter
now lives in the optional `extensions/sew-core-model` package, with a separate
`deps.edn`, source, tests, and development classpath alias. Its model dependency
resolves through the independently selectable `packages/sew-protocol-model`
package, while that model package still uses an in-tree framework dependency.
The adapter is not independently downloadable until the model is published.
Its current closure is 194 source files because it still requires the full model, including
shared yield runtime; it is not an independently extracted or Solidity
equivalent package. The profile has no undeclared component crossings and
passes `bb profile:check sew-core-extension`. To make that possible, runner
execution entries now belong to the optional runner component instead of the
framework passive registry. Package tests cover rejected feature/configuration
paths, adapter initialization, guardian pause and governance recovery, native
bilateral cancellation, and release, dispute resolution, timeout, and pull
settlement vectors tied to pinned Solidity test functions and an exact source
revision. The adapter requires the three default module identities and their
runtime-code hashes from the pinned deployment baseline. This rejects a scenario
declaring a different module artifact; verifying deployed address bytecode still
belongs to deployment tooling.

## Rules for claiming alignment

- Compare observable transitions, accounting results, time boundaries, and
  failure conditions; do not infer equivalence from matching names or folders.
- Bind comparison cases to a specific Solidity source revision and the matching
  PRF model revision.
- Keep module-specific behavior conditional on the same module and
  configuration being selected on both sides.
- Treat replay receipts and research outputs as PRF evidence unless a Solidity
  contract explicitly commits to the same claim.
- Record each comparison as matching, divergent, or not applicable, with a
  reproducible case and the relevant source locations.

This crosswalk should grow alongside differential vectors. It does not replace
the Solidity contract specifications, PRF component metadata, or the eventual
standalone-package specification.
