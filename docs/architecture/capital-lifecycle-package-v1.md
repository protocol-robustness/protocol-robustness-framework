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
the same scenarios. Solidity remains in `prf-evm`; tests there assert that the
EVM state machine implements these transitions without changing economic
behavior during extraction.

Each language fixture must cover accepted and rejected transitions, exact
accounting deltas, terminal/non-reuse behavior, race orderings, rollback on
failed custody effects, and closure derived from accounting. The shared vectors
must pin semantic inputs/outputs; EVM receipts and forensic observations remain
separate evidence.

## Extraction order

1. Keep this contract in PRF as the reviewed package boundary.
2. Inventory existing Solidity and source-bound implementations against each
   port; mark missing methods instead of filling them speculatively.
3. The illustrative NOW and application-defined port cases now live in
   `allocation-basis-port-examples-v1.edn`. Remaining: make them executable
   cross-language adapter fixtures when the kernel binds typed basis, operation,
   asset, and representation references. Do not pass row formats into the core.
4. Extract pure lifecycle transition logic and conformance fixtures into an
   in-tree package; leave Shortfall and EVM adapters at their current locations.
5. Once that package is independently loadable and pinned, move the package into
   `prf-capital-lifecycle`. Do not create the external repository before the
   in-tree boundary and dependency directions validate.

No source files are moved by this specification.
