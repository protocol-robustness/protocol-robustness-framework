# Capital lifecycle package boundary and interface

Status: **PROPOSED INTERFACE; documentation only; no runtime consumer**

Interface version: `capital-lifecycle-interface.v1` (document version only; not
a canonical artifact identity)  
Target repository: `prf-capital-lifecycle`  
Domain label: **Protected Capital**

This document defines the intended semantic boundary and first package
interface for capital lifecycle. The normative NOW-tranche economic rules remain
in [Protected Capital Pro-Rata V2 semantics](PROTECTED_CAPITAL_PRO_RATA_V2_SEMANTICS.md).
This interface does not modify that freeze, existing artifacts, Solidity
behavior, SP1 public values, or verifier identities.

## Purpose and ownership

Capital lifecycle owns economic state after a capital operation is proposed:

- reservation state and its release/expiry consequences;
- admission and binding of an authoritative entitlement basis;
- activation, the economic irreversibility cutpoint;
- liability and single-use entitlement accounting;
- atomic withdrawal realization;
- cancellation cause and realized capital consequence before activation;
- closure and realized settlement.

The package is independent of any particular allocation mechanism. An
allocation or application domain determines the entitlement economics: who is
eligible, what amount each row receives, and why. Capital lifecycle validates
and admits that basis against operation, asset, source, funding, and beneficiary
bindings; it does not recompute allocation arithmetic or reinterpret
application scarcity.

The package boundary is semantic, not a new implementation repository today.
The first code boundary should be completed in-tree before code moves to
`prf-capital-lifecycle`.

## Dependency direction

```text
                         ┌── pro-rata-allocation ──┐
canonical ───────────────┤                         ├── AllocationBasis adapter ─┐
                         └── other allocation apps ┘                            │
                                                                                  v
Shortfall ── SourceBinding adapter ───────────────────────────────────────> capital-lifecycle
protected-operations ── Admission / consume adapter ─────────────────────>       │
capital-lifecycle ── generic transition contract ──> EVM implementation in prf-evm
all domains ── integration and demos ──> PRF / prf-examples

protocol-deployments remains authoritative for deployment ledger, admission /
observation registry, manifests, and release semantics.
```

`capital-lifecycle` depends on canonical codec and typed-root primitives. It
does not depend on `pro-rata-allocation` or `Shortfall`. Those domains provide
adapters to its input ports. `prf-evm` owns the Solidity implementation and
EVM-local projections/conformance; it does not own the domain rules or an
authoritative deployment registry.

## Package ports

The following are language-neutral interface concepts, not canonical wire
schemas or newly registered artifacts.

### `AllocationBasisPort`

An allocation or application adapter supplies a typed reference to its already
computed basis and a small admission summary:

```text
AllocationBasis {
  basisRef: TypedRootRef
  operationRef: OperationRef
  assetRef: TypedRootRef
  total: non-negative base-unit integer
  memberCount: positive integer
  representationRef: optional TypedRootRef
}
```

`basisRef` identifies the source domain's canonical basis artifact. The adapter
owns the underlying rows, beneficiary assignment, eligibility, ordering,
amounts, and proof that those rows derive from `basisRef`. The lifecycle package
compares the opaque reference values against reservation/funding scope, requires
`total` to equal the funded amount, and accounts for the admitted member count
and total liability. The transition kernel does not parse or hash typed root
references; adapters provide references validated under the canonical contract.
It does not define a universal entitlement row or encoding. An application may
supply its own representation contract through `representationRef`.

For the current NOW path, the source basis is the pro-rata-owned
`realized-allocation-statement.v1`; the downstream beneficiary set remains a
separate capital-lifecycle artifact. A different application can supply a
module-owned basis artifact through the same port. `AllocationBasis` is an
in-process value, not a canonical wire schema or newly registered artifact.
`TypedRootRef` is supplied by `canonical`. Shared lifecycle vectors cover
reference equality and scope checks using fixture-only opaque reference values;
they do not claim canonical-root conformance.

### `SourceBindingPort`

An application/source adapter supplies the binding needed to establish where
capital came from and which operation may consume it:

```text
SourceBinding {
  sourceRef: TypedRootRef
  operationRef: OperationRef
  assetRef: TypedRootRef
  amount: non-negative base-unit integer
  reserveRef: optional TypedRootRef
}
```

Shortfall owns source observation, eligible demand, authoritative available
capital, reserve binding, and Shortfall-specific unmet-demand semantics. The
port does not transfer those semantics into capital lifecycle. Other
applications may implement the same port with their own source contract.

### `ProtectedOperationsPort`

Protected operations owns reusable assurance and safe-consumption mechanics.
Lifecycle asks it to authorize a scoped transition and, where required, consume
the admission exactly once:

```text
authorizeAndConsume {
  operationRef
  transitionKind
  expectedStateRevision
  requiredRoute / caller scope
  currentConfigurationRef
  freshness and nonce context
} -> AdmissionDecision
```

The exact proof, verifier, freshness, replay, route, configuration, and receipt
rules belong to protected operations. Capital lifecycle does not reimplement
them. Lifecycle defines which transitions require an admission and which
economic invariants must hold before and after it. The admission decision is
scoped to the requested transition and must not be treated as a verifier or
deployment registry entry.

### `CapitalCustodyPort`

The implementation adapter reports exact custody effects for reserve/funding,
release/refund, and beneficiary transfer. It returns a transaction-local result
that the lifecycle transition validates before committing accounting. Token
admission and EVM transfer behavior are implementation concerns; supported
assets must satisfy the asset profile declared by the application deployment.

### Existing adapter inventory

Read-only inventory of the current PRF and companion `prf-evm` implementations.
These are ownership and parity findings, not a proposal to move or rewrite the
current implementations.

| Port / transition | Existing implementation and owner | Coverage and extraction gap |
| --- | --- | --- |
| Allocation basis | PRF Clojure `src/resolver_sim/shortfall/allocation_binding.clj`; Rust `coprocessor/core/src/source_bound_entitlement.rs`; EVM `ProductionProRataAllocatorV2.sol` | Shortfall validates the application binding and reconstructed realized statement. The entitlement EVM path admits binding, descriptor, table, claim roots, beneficiary profile, count, total, and token. EVM activation checks the deployed representation root/count/total. The lifecycle kernel now checks opaque basis, operation, asset, and optional representation references plus total/count; no canonical reference decoder or universal entitlement rows are introduced. EVM representation/table/proof rules remain adapter-owned. |
| Source binding | Shortfall Clojure `reserve_observation.clj`, `reserve_binding.clj`, and `allocation_binding.clj`; Rust `coprocessor/core/src/source_bound_v2.rs` and `source_bound_entitlement.rs`; EVM `OnchainShortfallReserveV2.sol` and source-bound controllers | These bind Shortfall source, reserve observation, operation, configuration, and assurance. They are application-specific and should remain outside the lifecycle kernel. The observation/funding path is not yet a generic `SourceBindingPort` implementation. Existing V1 and V2 observation/proof identities remain separate. |
| Protected operations | EVM `SourceBoundProRataRatificationControllerV2.sol`, `SourceBoundProRataControllerV2.sol`, and assurance contracts | Ratification consumes admitted assurance and checks prepared operation, route, chain, reserve, token, allocator, configuration, and residual recipient before funding. This is a concrete Shortfall/EVM composition, not a reusable port consumed by the Clojure/Rust kernel. Keep AO policy and replay/configuration semantics in protected-operations adapters. |
| Reserve, funding, and refund | EVM `OnchainShortfallReserveV2.sol` and `ProductionProRataAllocatorV2.sol` | Exact token deltas and allowance cleanup are checked on funding/refund; funded pre-activation operations can be cancelled and refunded to the funding reserve. This supports the custody port. Solidity `reserveForOtherObligation` has release and permissionless expiry, but is a non-fundable exclusion hold, not the operation-bound prepare/fund reservation represented by the kernel. Do not claim those paths are equivalent. |
| Activation, withdrawal, closure | EVM `ProductionProRataAllocatorV2.sol`; Clojure/Rust lifecycle transition kernels | EVM activates only after its representation matches the admitted EVM table commitment. Withdrawals validate table openings, apply live proof policy and caller minimum, prevent replay, transfer exact amounts, and auto-close when count and amount are discharged. The kernel models liabilities and terminal state only; beneficiary, opening, proof-policy, and token checks belong to its adapter. The EVM path has no separate caller-invoked close transition. |
| Lifecycle kernel | `packages/capital-lifecycle/clj` and `packages/capital-lifecycle/rust` | Both implementations consume the same 26 transition vectors and remain unconnected to production runtimes. Vectors establish semantic transition parity, not Solidity adapter conformance or canonical-root equivalence. |

A known source-bound constraint remains outside this inventory's scope: the
legacy and successor prepared-operation V2 paths must retain their current
identities and layouts while their cross-language equivalence issue is handled
as a separate identity task. Do not combine those preimages during extraction.

### Foundry conformance evidence

The Foundry suites below were run on 2026-10-12: 45 tests passed, 0 failed.
They test the existing EVM implementation; they do not consume the package's
JSONL vectors directly.

| Kernel vector behavior | Existing Foundry evidence | Conformance scope |
| --- | --- | --- |
| `reserve-*`, `expire-*`, terminal reservation | `OnchainShortfallReserveV2.t.sol`: `test_ReservationExpiryIsPermissionlessAndPreservesCustody`, `test_RevisionChangesWhenReservationIsReleased`, `test_PreparedStateDoesNotReserveCapital` | Partial. EVM verifies an expiring exclusion hold and proves prepared work does not reserve capital. It has no operation-bound reserve → release/expire → fund sequence matching the kernel. |
| `fund-*`, exact funding, rollback | `OnchainShortfallReserveV2.t.sol`: `test_RatifyAndFundComparesCommitmentAndClearsEphemeralReservation`, `test_FundFailuresLeaveNoObservableRatificationState`, `test_TokenFundingFailuresLeaveNoObservableRatificationState`; controller `test_ReserveFailureRestoresPreparedStateAndAssurance` | Partial. EVM ratifies and funds atomically, checks exact token deltas and rollback; it does not expose the kernel's staged `fund` transition. |
| `activate-*`, activation/cancellation race | `SourceBoundProRataEntitlementRatificationControllerV1.t.sol`: `test_RatifiesAndFundsTheExactAdmittedEntitlementTuple`, `test_ActivationRequiresTheAdmittedLogicalRootCountAndTotal`, `test_ActivationRejectsCountAndTotalMismatches`, `test_ActivationWinsAgainstCancelAndRefund` | EVM checks the application tuple and deployed representation. Kernel's generic opaque basis references are not EVM canonical-root vectors. |
| `cancel-*`, refund terminality | `SourceBoundProRataEntitlementRatificationControllerV1.t.sol`: `test_CancelAndRefundReturnsExactCapitalAndIsTerminal`; `SourceBoundProRataRatificationControllerV2.t.sol`: `test_LegacyFundCancelRefundRestoresReserveAccountingEndToEnd` | Covered for the existing pre-activation cancellation/refund paths, including physical balance restoration and terminal state. |
| `withdraw-*`, `close-*` | `ProductionProRataAllocatorV2Withdrawal.t.sol`: `test_RequestedNoProofAndInvalidOptionalProofExecuteAndCloseExactly`, `test_RequiredRejectsMissingAndInvalidButAcceptsValidProof`, `test_TableOpeningSubstitutionAndDuplicateClaimReplayReject`, `test_FeeOnTransferRollsBackConsumptionAndAccounting` | Covered for EVM opening/proof policy, replay protection, token deltas, and automatic closure. Kernel vectors cover only abstract liability/count transitions. |
| `activate-alternative-adapter` | No current EVM application adapter | Intentionally untested in Solidity; the vector checks that the generic lifecycle kernel does not require a pro-rata basis type. |

Executed commands from `prf-evm`:

```text
forge test --match-path test/OnchainShortfallReserveV2.t.sol
forge test --match-path test/SourceBoundProRataRatificationControllerV2.t.sol
forge test --match-path test/SourceBoundProRataEntitlementRatificationControllerV1.t.sol
forge test --match-path test/ProductionProRataAllocatorV2Withdrawal.t.sol
```

### Current EVM profile boundary

The current EVM profile is atomic: prepared operation → atomic ratify-and-fund
→ activation → withdrawals and automatic closure, or pre-activation
cancel-and-refund. Preparing an operation does not reserve capital. The generic
lifecycle kernel retains staged operation reservation, release, and expiry for
other implementations, but those transitions are outside the current EVM
profile and must not be reported as EVM-conformant. This records the existing
implementation boundary; it does not add or change Solidity behavior.

The Foundry evidence therefore covers the EVM profile's existing transition
subset, not direct consumption of every package JSONL vector. Future adapter
work can add an explicit vector-to-EVM harness when it can preserve this
boundary and keep receipts separate from semantic inputs/outputs.

## Transition API

The package's pure semantic API is a closed transition function. Runtime
implementations may wrap it in transactions and adapters, but may not add
success states that bypass these guards.

```text
transition(currentState, command, boundInputs, admissionDecision)
  -> Accepted(nextState, semanticResult)
   | Rejected(stableReason)
```

Commands for the initial Protected Capital surface:

| Command | Required effects and guards |
| --- | --- |
| `reserve` | Bind a unique operation and amount to a source/asset; exclude it from available capital exactly once. |
| `release` | Authorized pre-funding release; restore reserved availability; make the reservation terminal and non-reusable. |
| `expire` | Permissionless only after the immutable reservation deadline; same accounting consequence as release, with a distinct terminal cause. |
| `fund` | Consume a matching live reservation and require exact custody delta. |
| `activate` | Admit one immutable basis; require basis total = funded amount; create liabilities equal to the entitlement total. |
| `cancel` | Allowed only before activation. If custody has moved, the same atomic transition must refund the exact amount to its bound source. |
| `withdraw` | Validate the authoritative entitlement and opening; enforce the stricter of current policy and caller minimum; consume once, reduce liability, transfer exact amount atomically. |
| `close` | Require all count and amount liabilities discharged and zero outstanding liability; become terminal without sweeping funds. |

The abstract states are:

```text
None -> Reserved -> Funded -> Activated -> Closed
          |            |
          |            +-> CancelledRefunded
          +-> Released
          +-> Expired

Activated --successful atomic withdrawal--> Activated
```

`Funded -> Activated` should be atomic where the execution environment permits.
If an implementation exposes a persistent `Funded` state, it must also provide
the pre-activation cancel-and-refund transition. `Activated` is the economic
irreversibility cutpoint: no operation cancellation, entitlement revocation,
or return of capital to the funder is permitted after it.

This state machine is the semantic profile. An implementation may combine
steps in one transaction; it must preserve the same observable accepted states
and terminal outcomes.

## Core invariants

Every conforming implementation must enforce:

1. A reservation ID and operation ID are never reused after a terminal result.
2. Reserved amount is counted once and can be released, expired, or funded at
   most once. Funding and terminal release/expiry contend on the same state.
3. Activation requires an admitted basis whose operation, source, asset, and
   funded amount all match; no supplied expected root substitutes for deriving
   or validating the basis.
4. The admitted entitlement set is immutable after activation. Its member
   amounts sum exactly to the funded amount; each member is uniquely
   identifiable and consumable once.
5. Before activation, cancellation either leaves custody unmoved or refunds the
   exact funded amount to the bound source. After activation, cancellation and
   refund are forbidden.
6. Withdrawal is atomic: consumption, claimed count/amount, liability decrease,
   and exact beneficiary transfer either all commit or all revert.
7. `available + reserved + protected` accounting must reconcile to the
   authoritative source balance under the source adapter's declared profile.
   Activated liability starts at the admitted total and decreases only by
   successful withdrawals.
8. Closure is derived from authoritative state: claimed amount equals admitted
   amount, claimed count equals admitted count, and outstanding liability is
   zero. Events or indexer observations cannot establish closure.
9. Runtime observations, transaction/block metadata, prover runs, proof bytes,
   and read-back evidence do not change canonical economic identities.

Reservation expiry is immutable once created. Its deadline boundary and
permissionless expiry behavior must be specified by the reservation profile;
expiry cannot extend, transfer custody, or trigger allocator execution.

## Canonical artifacts and versioning

The artifact-definition registry lists semantic type owners; it does not store
realized instances. The following existing V1 identities remain unchanged:

- `beneficiary-entitlement.v1`;
- `beneficiary-entitlement-set.v1`;
- `entitlement-representation-descriptor.v1`;
- every frozen allocation, source-binding, configuration, and proof identity
  consumed by the NOW-tranche path.

Those three entitlement artifacts are lifecycle-owned, but their current
successor derivation binds Shortfall allocation-binding and realized-statement
roots. Treat that derivation as the existing NOW/Shortfall adapter while the
generic basis port is implemented; do not move the whole
`source_bound_entitlement` module into the lifecycle repository.

The existing V1 entitlement set must not be claimed to serve another allocation
mechanism unless its fields and derivation genuinely support that mechanism. If
a concrete consumer cannot express its basis through an existing stable
contract, design a separately versioned successor artifact with independent
vectors. This proposal does not mint its name, schema, fields, domain, or hash.

Cancellation cause/consequence, realized capital consequence, withdrawal
realization, and closure/realized settlement are semantic requirements in the
NOW freeze, but have no adopted canonical schema/domain/vector set in the
registry. Their required economic facts are listed in the normative document.
Do not register provisional identities as though they were frozen contracts.

Keep these registries and records distinct:

- canonical artifact definitions: owned by this package or the source domain;
- verifier/program identities: protected-operations/verifier registry;
- deployments and release records: `protocol-deployments`;
- realized instances and forensic evidence: evidence/instance stores.

## Implementation and conformance layout

The future repository should contain the language-neutral contract, pure
Clojure and Rust transition implementations, and cross-language fixtures for
the same scenarios. The package currently passes its standalone Clojure shared
vector suite (54 assertions) and Rust locked test/clippy checks (2 tests,
zero path dependencies). Solidity remains in `prf-evm`; a direct Foundry harness
should consume the immutable package revision after extraction rather than a
workspace-local file path or duplicate copy of the semantic vectors.

Each language fixture must cover accepted and rejected transitions, exact
accounting deltas, terminal/non-reuse behavior, race orderings, rollback on
failed custody effects, and closure derived from accounting. The shared vectors
must pin semantic inputs/outputs; EVM receipts and forensic observations remain
separate evidence.

## Extraction order

1. Keep this contract in PRF as the reviewed package boundary.
2. The read-only adapter inventory above is complete. Preserve the identified
   boundaries and record the reservation-path difference rather than treating
   the non-fundable EVM exclusion hold as the lifecycle operation reservation.
3. The AllocationBasis kernel binding is implemented in Clojure and Rust with
   shared positive/negative vectors, including an alternative adapter fixture.
   The EDN examples remain explanatory placeholders. Do not pass row formats
   into the core.
4. The current EVM profile is the atomic ratify/fund lifecycle subset above;
   staged reservation, release, and expiry remain generic kernel transitions
   outside that profile. The existing Foundry suite is its current evidence.
5. Independent package loading and dependency direction are verified: the
   Clojure package loads from its own directory with only Maven dependencies;
   the Rust crate passes locked offline dependency resolution and has no path
   dependencies. The shared 26 vectors pass in both languages.
6. Extract this bounded package into `prf-capital-lifecycle` while retaining
   the in-tree PRF integration surface. Use a pinned immutable package revision
   for downstream consumers; do not add developer-local paths or copy the
   authoritative semantic vectors.
7. Once `prf-evm` can consume that pinned revision, add a vector-consuming
   harness for the matching atomic EVM profile transitions. Keep staged
   reservation transitions outside that profile, and keep receipts separate
   from semantic inputs/outputs.

No source files are moved by this specification.
