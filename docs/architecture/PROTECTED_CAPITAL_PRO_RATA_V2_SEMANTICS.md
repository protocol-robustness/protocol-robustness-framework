# Protected-capital pro-rata V2 production semantics

Status: **NORMATIVE STAGE A FREEZE; IMPLEMENTATION INCOMPLETE**

Date audited: 2026-09-28

## Current implementation status (2026-10-16)

This document records the 2026-09-28 Stage A baseline. Its statements below
about `SourceBoundProRataRatificationControllerV2` and the 192-byte source-bound
V2 profile remain specific to that legacy path: it still terminates at
`Funded`, and its ABI is not the successor entitlement withdrawal lifecycle.

The bounded successor entitlement profile has since been implemented additively
in `prf-evm`. It uses the 288-byte
`source-bound-entitlement-public-values.v1` profile with
`SourceBoundProRataEntitlementAssuranceV1`,
`SourceBoundProRataEntitlementRatificationControllerV1`, and
`ProductionProRataAllocatorV2`. The successor controller atomically ratifies
and funds, then supports either `Funded -> Activated -> Claims -> Closed` or
`Funded -> CancelledRefunded`. Activation checks the admitted table root, count,
and total; withdrawals consume each committed entitlement once, enforce the
live Requested/Required policy, update outstanding liability, and check exact
token balance deltas. Stale assurance replacement is restricted to the bound
execution caller and checks the full live reserve commitment and generation.

The implementation evidence is pinned to `prf-evm` commit
`d8aee74e9652f44f68e345aff30090325df2ae54`. Focused suites include
`SourceBoundProRataEntitlementRatificationControllerV1.t.sol` (13 tests) and
`ProductionProRataAllocatorV2Withdrawal.t.sol` (6 tests); the broader focused
reserve/assurance/controller set also passes. Deployment-derived proof and
economic observations are retained under
`prf-evm/evidence/gate-e-20261010-current-00d90/` and described in
`prf-evm/docs/gate-e.md`. They establish a local-only demonstration, not public
chain qualification. The current local Anvil has advanced beyond that run's
historical pre-funding block, so the historical fork test cannot be repeated
against the current node without a fresh deployment or archived chain state.

The successor implementation does not upgrade or silently extend the legacy
192-byte path. Keep those profiles and their evidence distinct when interpreting
the Stage A tables below.

This record freezes the production semantics for the protected-capital pro-rata
V2 NOW tranche. It covers:

```text
protected capital
  -> reserve
  -> ratification
  -> allocation
  -> entitlement
  -> withdrawal
  -> closure
```

It does not change Solidity, Clojure, Rust, SP1, public values, deployment, or
Gate E behavior. Later stages must implement this record through V2 successor
components. Historical V1 identities and behavior remain unchanged.

## Audit boundary and classification vocabulary

The audit read the PRF semantic and proof repository at the current `agent-c`
working copy and the companion Solidity repository at `/home/user/Code/prf-evm`.
The last snapshotted PRF state appeared clean at audit start when inspected with
`jj --ignore-working-copy`; a later safe snapshot exposed unrelated workspace
changes in `SEW_NEXT_QUALIFICATION.md` and `reproduction_comparability.clj`.
Those files were not touched by this audit. The Solidity working tree was
already dirty with Gate E deployment, vector, documentation, and test work; no
Stage A edits were made there. Stage A changes are confined to this document
and its changelog entry. The current source-bound guest authority gap and the
absence of a successor guest/layout/decoder are Stage B implementation
requirements, not unresolved Stage A semantic choices; the frozen 192-byte
profile is not modified.

The classifications in this document mean:

- **ALREADY PRESENT**: the checked call path enforces the stated behavior.
- **STALE / NEEDS V2 MIGRATION**: useful V1 or provisional V2 behavior exists,
  but it is not the production V2 contract frozen here.
- **FROZEN — MUST PRESERVE**: an existing identity, schema, domain, ABI, or
  historical behavior must not be reinterpreted.
- **MISSING**: no checked implementation provides the behavior.
- **DESIGN CONFLICT**: the checked implementation would violate this freeze if
  carried into the production V2 path unchanged.

## Repository state found

| Surface | Classification | Checked behavior |
| --- | --- | --- |
| Generic `pro-rata-allocation` kernel and result artifacts | **FROZEN — MUST PRESERVE** | Clojure owns deterministic allocation, rounding, redistribution, row allocations, canonical result identity, and `unallocated-residual`; it is not Shortfall-specific and performs no custody execution. |
| Shortfall application/profile | **ALREADY PRESENT**, incomplete production handoff | Supplies eligible demand, live available capital, source observation/reserve commitment, and application context around the generic allocation. It does not redefine allocation arithmetic. |
| `ProductionProRataAllocatorV1` | **FROZEN — MUST PRESERVE** | Complete `None -> Created -> Funded -> Activated -> Claims -> Closed` lifecycle; authority can cancel `Created`, or cancel and exactly refund `Funded`; activation consumes a matching admitted 384 byte execution projection; each claim requires an ordered Merkle path and transfers to the leaf's `recipient`. |
| `ProductionProRataAllocatorV2` | **STALE / NEEDS V2 MIGRATION** | Implements only `create` and exact-delta `fund`. Its interface names later states, but the contract has no activation, entitlement, withdrawal, close, cancellation, or refund path. A successful V2 ratification leaves persistent `Funded` custody. |
| `OnchainShortfallReserveV1` | **FROZEN — MUST PRESERVE** | Lock-first immutable snapshot, persistent reservation, exact funding, authority cancellation, permissionless expiry, and exact release of `totalReserved`. Funding and termination contend on `ShortfallOperation.state`. Snapshot data and operation identity remain retained. |
| `OnchainShortfallReserveV2` ordinary reservations | **ALREADY PRESENT** | `None -> Active -> Released`, exact `totalReserved` increase/decrease, no transfer on release, no ID reuse, and `availabilityRevision` advancement. |
| `OnchainShortfallReserveV2` Shortfall ratification | **ALREADY PRESENT**, incomplete endpoint | Recomputes the live economic commitment, creates an authoritative observation, temporarily reserves all available capital, creates and exactly funds the V2 allocator, clears the reservation, and reverts the whole transaction on any failure. It does not activate entitlements. |
| `SourceBoundProRataControllerV2` | **ALREADY PRESENT**, preparation only | Authorized initiator derives a prepared-operation root; preparation reserves no capital. Initiator may abandon `Prepared`. Operation IDs cannot be reused. |
| `SourceBoundProRataAssuranceV2` | **ALREADY PRESENT**, source assurance only | Checks a durable admission receipt and exact 192 byte public values, freezes one receipt per operation, then permits only the bound ratification controller to consume the exact `(operation, prepared root, reserve commitment)` tuple. |
| `SourceBoundProRataRatificationControllerV2` | **STALE / NEEDS V2 MIGRATION** | Permissionless registration and ratification orchestrate assurance consumption plus reserve funding atomically. The terminal controller state is `Funded`, not `Activated`. It accepts configuration and residual-recipient arguments that are not independently compared with decoded prepared-operation fields at this call boundary. |
| `PrfStatementAdmission` | **FROZEN — MUST PRESERVE** | Fail-closed proof admission; snapshots chain genesis, exact expected configuration root/epoch, sealed verifier-registry root, verifier identity, program identity, schemas, statement root, and `sha256(publicValues)`. Invalid verification reverts and creates no receipt. |
| `PrfVerifierRegistry` | **FROZEN — MUST PRESERVE** | Governor-controlled construction of immutable sealed verifier snapshots. Configuration chooses a sealed registry root. |
| `SewConfigurationExecutor` | **ALREADY PRESENT** governance mechanism | Applies an expected-parent configuration transition authorized through the SEW Governor/Timelock authority adapter; advances configuration root, verifier-registry root, and epoch together; consumes each decision root once. |
| Canonical chain configuration | **FROZEN — MUST PRESERVE**, with successor versions available | `chain-configuration.v1` is closed and rooted. V2 adds authority-semantics policy; V3 adds `allocation-entitlement-policy/root`; V4 adds risk policy. These versioned identities must not be mutated in place. |
| `allocation-entitlement-policy.v1` | **ALREADY PRESENT**, insufficient for withdrawal mode | Commits fixed allocation, asset, protocol, custody subject/scope, and profile roots. It intentionally excludes realized population. It has no proof-enforcement mode or beneficiary profile. |
| `realized-allocation-statement.v1` | **FROZEN — MUST PRESERVE** | Canonical SHA-256 statement over context, request, allocation policy, realized results, fail-action policy, and lifecycle roots. Realized rows bind claim identity and amounts, but not an EVM beneficiary address or entitlement representation. |
| `realized-allocation-execution.v1` | **FROZEN — MUST PRESERVE** | Derives a 384 byte EVM projection, operation binding, ordered claim leaves/root, count, total, and zero residual. Its companion's exact claim-to-recipient mapping is validated against positive realized rows. |
| `AllocationBindingV1` | **FROZEN — MUST PRESERVE** | Derivationally binds a validated primitive result to an application-context root. It grants no present-tense authority and contains no proof, reserve, beneficiary population, entitlement table, or execution state. |
| Source-bound V2 public values and guest | **DESIGN CONFLICT** for production entitlements | The 192 bytes contain schema, prepared root, reserve commitment, allocation-binding root, realized-statement root, and operation ID. The guest derives the first two roots but reads the allocation and statement roots directly from witness JSON. It neither reconstructs their relationship nor derives an entitlement set. |
| V1 `claim` | **FROZEN — MUST PRESERVE** | One transaction and no pending claim state, but proof is always mandatory and the leaf's `recipient` is also the transfer destination. It is not the new Requested/Required withdrawal policy. |
| V2 withdrawal | **MISSING** | No V2 activation, entitlement, consumption, withdrawal, proof mode, caller minimum, payout boundary, or closure exists. |
| V2 capital cancellation after ratification | **MISSING / DESIGN CONFLICT** | Atomic ratification leaves the V2 allocator persistently `Funded`; that state has neither activation nor cancel-and-refund. This is stranded-capital risk until the path is made atomically activating or gains a refund escape. |
| Gate E real E2E | **STALE / NEEDS V2 MIGRATION** | Option A deploys the topology, prepares an operation, deposits reserve capital, and emits a manifest. The shell invokes an external prover, but the final Foundry test only checks that manifest/proof files are nonempty and have a few expected fields. It does not admit the proof, prepare assurance, ratify, fund, activate, withdraw, or reconcile final accounting. |

The current focused V2 call path is:

```text
prepareOperation(P)
  -> external proof admission
  -> prepareSourceAssurance(receipt, 192-byte values)
  -> registerPrepared(operationId)
  -> ratifyAndFund
       -> consumeForRatification(P, C)
       -> reserve.ratifyAndFund
            -> require live C' == proved C
            -> ephemeral reserve
            -> allocator.create
            -> allocator.fund
            -> clear ephemeral reserve
  -> persistent allocator Funded
```

There is no current call from this path to V1 activation or claims, and adding
such a call would incorrectly merge frozen V1 and V2 identities.

## Frozen terminology and layer boundaries

`pro-rata-allocation` is the generic deterministic allocation primitive:

```text
allocation inputs / claims
+ allocation policy
+ fixed available resource
-> deterministic realized allocation
-> canonical allocation/result roots
```

It owns allocation arithmetic, deterministic rounding and redistribution, the
row allocations, and allocation residual. It does not own Shortfall source
facts, beneficiary derivation, Solidity custody, or token settlement.

Shortfall is one application profile. It supplies authoritative eligible
demand, authoritative available capital, scarcity and eligibility context, and
source/reserve binding to the generic primitive. The EVM allocator never
calculates a pro-rata share. It authenticates and executes an already derived,
proved, and admitted realized allocation.

The production layers are distinct:

```text
generic pro-rata inputs + policy
  -> realized allocation
Shortfall application context + realized allocation
  -> application/allocation binding
realized allocation + beneficiary derivation
  -> execution entitlement set
activated entitlement set + allocator custody
  -> withdrawals and final settlement
```

`AllocationBindingV1` preserves this separation: it binds a validated generic
result to application context, while granting no present execution authority.
The Clojure allocation kernel, result artifacts, and residual invariants are
generic; Shortfall supplies application inputs and reserve binding in separate
artifacts/contracts. The Solidity `ProductionProRataAllocator*` names can obscure
the boundary, but their implementations do not perform pro-rata arithmetic.

The current repository therefore **mostly preserves** these separations. Two
places require explicit care in successor work:

1. V1's execution projection calls its zero `residualAmount` an allocator
   residual and carries a `residualRecipient`, even though production activation
   requires `totalAllocated == availableAmount` and `residualAmount == 0`; the
   recipient has no payout role on the admitted path.
2. The draft semantic freeze previously described the allocated economic object
   as an entitlement set. This section corrects that terminology: entitlements
   are a downstream execution representation of the realized allocation.

For Shortfall, `unfilled demand = eligible demand - realized allocation`. It is
an application quantity and may be positive. Allocation residual is the part of
the fixed available resource that the generic allocation did not allocate. The
current production handoff requires allocation residual to be zero. Thus demand
100 and available capital 70 may produce realized allocation 70, unfilled
demand 30, and allocator residual 0. Post-allocation labels such as fulfilled,
deferred, unmet, waived, or excluded remain application/policy semantics.

## Normative economic object

The object allocated by the generic primitive is a fixed quantity of the
operation's resource, denominated here in ERC-20 base units, across an ordered
set of eligible claims under a versioned allocation policy. Its authoritative
output is the deterministic realized allocation: claim identities and realized
amounts, canonical result roots, totals, and allocation residual.

For the production Shortfall handoff, the realized total equals the exact
available capital and allocation residual is zero. Any Shortfall unfilled demand
remains outside allocator custody and outside the entitlement total.

The downstream execution entitlement is the immutable beneficiary-bound form of
one positive realized-allocation row. The complete entitlement set has the same
total as the realized allocation and therefore the exact capital made available
to the allocator. Each entitlement identifies:

```text
operation binding
claim identity
beneficiary identity
amount
entitlement index / ordinal
```

The realized allocation and its execution entitlements exclude the current
signing key, payout override, table address, salt, proof bytes,
proof-enforcement mode, and consumption state. Those are authority,
representation, assurance, or lifecycle concerns.

After activation, the entitlement set is immutable. Each entitlement is
consumable once. Unconsumed entitlements remain liabilities; there is no expiry,
confiscation, or unclaimed sweep in this tranche.

## Authority boundaries

| Component | Authority |
| --- | --- |
| Canonical allocation kernel | Determines amounts and claim identities. |
| Entitlement derivation | Deterministically derives beneficiary-bound entitlements from the same realized allocation and committed application context. |
| SP1 guest | Independently derives authoritative public outputs from witness material; host-supplied expected roots are not authority. |
| Admission and verifier registry | Establish proof validity under an immutable program/verifier/schema/configuration snapshot. |
| Ratification controller | Orchestrates one operation and consumes the admitted source assurance. |
| Reserve | Owns reserve accounting, current custody, exact live-state comparison, and reserve-to-allocator transfer. |
| Allocator | Owns funded custody, activation, entitlement consumption, liability accounting, transfer, and closure. |
| Live configuration | Selects the present proof-enforcement mode through governed configuration. |
| Beneficiary account | Owns the economic entitlement. Signer and validator rotation occur below this boundary. |

No controller, operator, or live signer may populate an unrelated entitlement
table and call it equivalent to the proved allocation.

## Reserve state machine and cancellation

Every persistent reservation has this state machine:

```text
None
  -> Reserved
       -> Funded
       -> CancelledReleased
```

`Reserved -> CancelledReleased` is **cancel-and-release**, shorthand for two
semantically distinct facts executed atomically:

```text
cause: reservation cancellation accepted
consequence: release the reservation
realized effect:
  totalReserved -= exact reserved amount
  reserve token custody unchanged
```

The transition retains the original amount, source snapshot, commitment,
operation ID, and terminal state. It cannot delete or rewrite historical
availability and cannot return the identity to `None`.

Funding and cancellation read and update the same authoritative operation state.
If cancellation commits first, funding rejects. If funding commits first,
cancellation rejects.

The preferred V2 Shortfall topology has no persistent reservation between
transactions. `Reserved` is an internal state inside atomic ratification; a
failure reverts to `None` with no historical claim that cancellation occurred.
Prepared and abandoned operations reserve zero capital. This is stronger than a
persistent cancellable reservation because there is no pre-funding custody state
to strand. Existing V2 ordinary reservations remain persistent and follow the
state machine above.

## Allocator state machine and irreversible cutpoint

The conceptual allocator lifecycle is:

```text
None
  -> Created
  -> Funded
       -> Activated
            -> Claims
            -> Closed
       -> CancelledRefunded   only if Funded persists across transactions

Created -> Cancelled          only before custody arrives
```

Production V2 should execute:

```text
ratify -> reserve -> create -> fund -> activate
```

in one transaction. `Created` and `Funded` may exist transiently during that
transaction, but the only successful externally observable endpoint should be
`Activated`. Assurance is consumed before the reserve movement; activation is
part of the same transaction. Any later failure reverts assurance consumption,
reservation, funding, table binding, and activation together.

If implementation evidence shows that activation cannot safely join the
ratification transaction, persistent `Funded` must gain:

```text
Funded -> CancelledRefunded
```

before that split topology may be used. **Cancel-and-refund** means:

```text
cause: allocator cancellation accepted before activation
consequence: refund funded capital
realized effect:
  allocator custody -= exact funded amount
  recorded source/funder custody += exact funded amount
```

`Activated` is the exact ordinary economic irreversibility cutpoint. At that
point the immutable entitlement set becomes an outstanding liability. No
ordinary operation cancellation, entitlement revocation, or funder refund is
allowed afterward.

## Withdrawal state machine

Withdrawal is one atomic operation:

```text
validate authoritative entitlement and opening
  -> enforce current proof policy and caller minimum
  -> mark entitlement consumed
  -> decrease outstanding liability
  -> exact transfer to the permitted payout route
```

It succeeds completely or reverts completely. There is no
`requestWithdrawal`, pending withdrawal, fulfillment transaction, ordinary
withdrawal cancellation, fallback signer, or operator bypass.

Consumption and accounting updates precede the external token transfer. A
reentrant duplicate observes consumed state or the reentrancy guard. A failed
transfer or exact-balance postcondition reverts consumption and accounting.

Closure requires both:

```text
claimedAmount == availableAmount
claimedCount == allocationCount
```

and zero outstanding liability. Closure is terminal and moves no unclaimed
capital. A successful closure has a canonical closure/realized-settlement
artifact derived from authoritative operation accounting: operation identity,
entitlement-set identity, final claimed amount/count, zero outstanding
liability, and terminal state. Event count or an indexer's assertion cannot
create closure. The closing transaction, block, caller, events, and read-backs
are separate forensic evidence.

## Requested and Required proof policy

The only production proof modes are ordered:

```text
Requested < Required
```

There is no `None` value, implicit zero mode, or bypass mode.

### Requested

Requested trusts the authoritative Solidity entitlement representation. A valid,
unconsumed opening for the exact operation, index, claim, beneficiary, and amount
is sufficient.

```text
proof absent  + valid entitlement -> execute
proof valid   + valid entitlement -> execute
proof invalid + valid entitlement -> execute
```

If proof bytes are supplied, the allocator should evaluate them without making
their failure enforcement-critical. A successful transaction may emit
`NotProvided`, `Valid`, or an unsuccessful-verification observation. It must not
create persistent proof-submission lifecycle state.

The existing `PrfStatementAdmission` verifier boundary reverts on invalid proof.
It therefore cannot itself persist an invalid result. A V2 withdrawal may use a
non-state-changing verifier call with `try/catch` where the selected proof scheme
permits it. On-chain code cannot reliably distinguish every cryptographic
invalidity from every verifier/runtime error; detailed failure classification
belongs in transaction traces and off-chain telemetry. This limitation must not
turn Requested into Required when proof bytes are present.

For this freeze, the withdrawal proof is the successor ordered Merkle membership
proof against the operation's immutable `allocationClaimRoot`. Source SP1 proof
admission occurs before activation and is not repeated per withdrawal. The
entitlement opening required by `committed-immutable-table-v1` is authoritative
entitlement data, not the optional assurance proof. The successor Merkle checker
should return a validity result so Requested can observe an invalid path without
reverting; the admission limitation above matters only if a later version selects
an external reverting verifier.

### Required

Required trusts both independent facts:

```text
valid authoritative Solidity entitlement
AND
valid proof under the operation's immutable proof scheme/root
```

Empty, malformed, wrong-operation, wrong-index, wrong-claim, wrong-beneficiary,
wrong-amount, wrong-root, wrong-scheme, or otherwise invalid proof reverts.
Governance and operators have no per-withdrawal bypass.

### Proof observation and withdrawal realization

Requested proof status is forensic/operational evidence when the entitlement
independently authorizes execution. `NotProvided`, `Valid`, `Invalid`, and
`VerificationError` do not alter the entitlement or successful withdrawal's
economic identity. In particular, a Requested withdrawal with no proof and the
same withdrawal with an optional valid proof have the same canonical economic
realization.

Every successful withdrawal produces a canonical withdrawal-realization
artifact, whether or not it is materialized on-chain. It minimally binds:

```text
operation identity
canonical entitlement identity and index
stable beneficiary identity/profile
amount
authorization mode actually required by stricter(current, callerMinimum)
successful single-use consumption
resulting claimed amount/count and outstanding liability
resulting operation state
```

For Required, the realization also binds the immutable proof scheme/root and
the canonical policy-bound assurance fact that membership was valid. It binds a
canonical admission/receipt identity when the selected proof profile defines
one. Proof bytes, Merkle-path transport encoding, prover process, runtime,
filesystem location, transaction trace, and gas remain EVM inputs or forensic
evidence unless a future policy explicitly makes them semantic.

Missing or invalid Required proof, a wrong opening, a duplicate attempt, and a
cross-operation substitution produce no withdrawal realization. Their reverts
may be retained as conflict/failed-attempt evidence in the existing forensic
DAG.

## Live governance policy

The current mode is present-tense policy. It is read at withdrawal time and is
not copied into each operation:

```text
Requested <-> Required
```

The mode belongs under the governed chain configuration's parameter-policy
selection. The operation's allocation semantics, beneficiary profile,
entitlement representation, claim-proof scheme, verifier family, and program
identity remain immutable in its activation identity. A mode transition changes
enforcement only; it changes no operation ID, allocation root, entitlement root,
beneficiary, amount, or statement root.

The policy family is a canonical, versioned semantic artifact. It defines the
two modes, their authorization requirements, their ordering, and
`stricter(current, callerMinimum)`. The selected current mode is mutable
governed state, not part of an entitlement or historical withdrawal-policy
identity. Adoption of a different mode is identified by the existing canonical
configuration state and configuration-transition machinery. Its governance
transaction, block, proposer, and event position are forensic evidence of that
transition rather than inputs to the policy or entitlement root.

The existing on-chain configuration executor exposes only an opaque current
configuration root and verifier-registry root. It does not expose a decoded
withdrawal mode. Stage E therefore needs a narrow governed configuration
projection for the V2 allocator. Its updates must be authorized and executed by
the existing `GovGovernor -> TimelockController -> authority adapter ->
SewConfigurationExecutor` transition, and it must agree with the executor's
current configuration root. An allocator-owner setter is forbidden.

The PRF configuration transition is currently governed by the Governor's base
TimelockController path. The separate Sew slow-lane queue/activate machinery is
not wired to `SewConfigurationExecutor`, and there is no PRF emergency lane.
Accordingly, the current lane for this mode is the normal Governor plus timelock
configuration lane. This tranche does not invent a faster emergency path or
silently route the setting through Sew's unrelated module slow lane.

A caller minimum is cleanly compatible with a new V2 ABI. The caller supplies
`Requested` or `Required`; effective enforcement is the stricter of current
governed mode and caller minimum. A caller cannot weaken current policy. A
transaction prepared with minimum `Required` therefore remains proof-required
if governance changes the live mode to `Requested` before inclusion.

The frozen conceptual ABI is:

```solidity
withdraw(
    bytes32 operationId,
    EntitlementOpening entitlement,
    bytes allocationProof,
    ProofMode minimumMode
)
```

`EntitlementOpening` carries index, claim identity, beneficiary, amount, and the
representation opening such as salt. `allocationProof` encodes the successor
ordered Merkle path. There is no payout address in this ABI for the NOW tranche.
Stage E may choose Solidity tuple spelling and integer widths, but it must not
split this semantic operation or add a second settlement path.

## Beneficiary and payout semantics

The entitlement binds a stable economic beneficiary, not a signing key.

The preferred profile is `smart-account-v1`: the entitlement's beneficiary is
the smart-account address. Validator, owner, or recovery-key rotation inside the
account changes neither the entitlement nor the allocation.

The canonical beneficiary identity consists of the stable account identity plus
the versioned beneficiary profile. Present signer, validator set, recovery
guardians, and an ERC-1271 result are current account authorization state and do
not enter the historical entitlement identity. A `smart-account-v1` rotation
therefore leaves the realized-allocation root, individual entitlement root,
canonical entitlement-set root, and operation identity unchanged.

The compatibility profile is `immutable-address-v1`: the entitlement binds an
address whose user explicitly accepts that the protocol provides no recovery or
reassignment. The address may be an EOA. Loss or rotation of its key does not
change the entitlement.

For the NOW tranche, the only payout route is direct:

```text
no payout override -> transfer to canonical beneficiary
```

The protocol does not accept an arbitrary destination or callback. A later
narrow approved-adapter profile may be versioned separately. Wallet/account
recovery remains at the wallet layer; Solidity has no recovery registry or live
authorization service.

V1 leaves bind a `recipient` and V1 transfers to that same address. That surface
can already name a smart-account address, but V1 semantically combines economic
ownership and payout destination. It must not be reinterpreted. V2 uses a new
beneficiary-aware entitlement and claim-proof domain. Because the semantic field
meaning changes and index/salt are added to the production entitlement, the new
scheme must not be called `allocation-merkle-v1`.

## Entitlement representations

Both representations implement the same canonical economic entitlement set.
Each profile resolves to a canonical PRF descriptor artifact, not merely an EVM
enum with off-chain conventions. The closed descriptor specifies:

```text
descriptor schema and profile name/version
hash domains
field ordering and field encodings
commitment and table-root derivation
entitlement-index semantics
beneficiary-profile semantics
consumption representation and bitmap ordering
logical ordered-table construction and `evmEntitlementTableRoot` derivation
```

Its canonical SHA-256 root is
`entitlementRepresentationDescriptorRoot`. An EVM implementation separately exposes its EVM profile identifier and the
logical table commitment. The descriptor defines the logical representation,
not its physical deployment partitioning. Shard size, shard count, shard
addresses, deployment factory, and bytecode-container address are deployment
provenance and must not enter the descriptor merely because a sharded
implementation is chosen. A physical fact becomes descriptor-bound only if it
changes logical interpretation or root derivation. The deployed realization
has a separate `evmEntitlementDeploymentIdentity` covering code/data/shard
identity. Activation binds the logical root to a validated deployment without
confusing deployment provenance with canonical economic identity.

### `public-packed-v1`

This is the reference and conformance profile. It stores the smallest clear
public representation practical and a consumed bitmap. It is the semantic
oracle for small allocations and differential tests. It is not the gas target.

### `committed-immutable-table-v1`

This is the intended production profile, subject to Stage C benchmarks. For
entitlement index `i` it stores an immutable EVM-local commitment:

```text
C_i = keccak256(abi.encode(
  entitlement-domain,
  operationBinding,
  i,
  keccak256(claimIdentityBytes),
  beneficiary,
  amount,
  salt
))
```

The salt is representation/opening material and is not part of the economic
entitlement. It must have enough entropy to resist practical enumeration.
Wallet or application software controls and backs up that claim package. The
protocol does not provide a centralized opening-recovery registry, and an
operator cannot recreate or approve an opening for the beneficiary. Requested
mode therefore has no live service dependency.
Commitments are ordered by entitlement index. The representation commits an
EVM-local ordered table root, count, and total, and consumption uses a bitmap
indexed by `i`. This `evmEntitlementTableRoot` is distinct from the canonical
PRF entitlement-set root and is independent of shard addresses or deployment
layout. The three identity layers are: `canonicalEntitlementSetRoot` (portable
semantic identity), `evmEntitlementTableRoot` (logical EVM commitment), and
`evmEntitlementDeploymentIdentity` (deployed realization/provenance). Claim-identity replay protection remains separately
available so two accepted encodings cannot consume one semantic claim twice.

One monolithic bytecode table is not suitable for realistic maximum sizes:
10,000 32 byte commitments alone require about 320 KiB and exceed EIP-170's
runtime-code limit. The current non-upgradeable deployment model can support
immutable data contracts only through bounded shards and deterministic metadata,
or an equivalently immutable data-container technique. Stage C must benchmark
sharded bytecode deployment against storage and root/opening alternatives at 10,
100, 1,000, and 10,000 entitlements before declaring the bytecode profile the
production implementation.

Before withdrawal, salted commitments can hide beneficiary, amount, and claim
identity if openings have sufficient entropy and are not disclosed elsewhere.
At an ordinary ERC-20 withdrawal the beneficiary and amount become public. This
is commitment privacy, not transaction privacy.

### Hash and encoding domains

`committed-immutable-table-v1` uses a frozen EVM-local `keccak256`/ABI
encoding. It is not a PRF canonical SHA-256 root. The same boundary applies
throughout the production path:

| Identity | Domain |
|---|---|
| canonical allocation statement, execution, and configuration artifacts | PRF canonical SHA-256 encodings |
| Solidity operation binding and ordered claim leaf | frozen EVM-local `keccak256`/ABI encodings |
| entitlement commitment and consumption index | frozen EVM-local commitment/index encodings |
| withdrawal proof digest, if separately materialized | versioned EVM proof-scheme encoding |

Every cross-domain link must carry both the source PRF identity and derived EVM
identity in a versioned binding. No EVM `keccak256` value may be described or
accepted as a PRF canonical root.

The entitlement derivation is therefore:

```text
canonical realized allocation
  -> canonical beneficiary entitlement set
  -> canonicalEntitlementSetRoot                 PRF SHA-256
  -> deterministic representation under descriptor root
  -> evmEntitlementTableRoot                     EVM keccak/ABI
```

The successor guest derives both roots from the same canonical rows,
beneficiary mapping, descriptor, and representation openings. Activation binds
the tuple `(operation, chain, allocator, descriptor root, canonical set root,
EVM table root, count, total, table address/code identity)`. This prevents an
EVM table commitment from being substituted for the portable semantic root.

## Identity and observation classification

Every new NOW-tranche object has exactly one primary classification. Where a
semantic object has an EVM projection and forensic evidence, the binding is
explicit rather than inferred from names.

| Object | Canonical semantic identity | EVM-local execution identity / commitment | Forensic / observational evidence |
|---|---|---|---|
| Realized allocation | Existing canonical realized-allocation artifact/root | Execution projection and claim root only | Derivation run, host/process, proof-generation observations |
| Shortfall/application binding | Existing `AllocationBindingV1` where sufficient; beneficiary-aware successor binding where required | Prepared operation and source/reserve binding projections | Source reads, reserve observation, RPC and runner evidence |
| Beneficiary profile/identity | Stable beneficiary plus canonical profile descriptor | Encoded account address/profile ID in entitlement leaf/commitment | Current signer, validators, guardians, ERC-1271 result, wallet delivery |
| Beneficiary-bound entitlement | Canonical entitlement artifact/root | Leaf or salted commitment | Opening delivery, storage, presentation, and use observations |
| Entitlement set | Canonical ordered set artifact and `canonicalEntitlementSetRoot` | `evmEntitlementTableRoot`, count/total projection, table address/code identity | Deployment, code read-back, opening distribution, indexer observation |
| Representation descriptor | Canonical closed descriptor and descriptor root | Profile ID, implementation/code identity, encoding implementation | Deployment tooling, compiler/build manifest, gas measurements |
| Withdrawal/proof policy family | Canonical policy descriptor/root defining Requested, Required, and `stricter` | Versioned mode enum and governed config projection | Governance proposal/execution transactions and read-backs |
| Current proof mode | Canonical configuration state and transition identity; excluded from entitlement identity | Current enum/config slot or immutable projection contract state | Governance transaction, block, event, caller, RPC observation |
| Optional Requested proof | No entitlement or withdrawal semantic identity | Optional verifier input/result | `NotProvided`, `Valid`, `Invalid`, `VerificationError`, trace/runtime data |
| Required proof assurance | Canonical policy-bound assurance fact and, where applicable, admission/receipt identity | Verifier input/result against immutable scheme/root | Prover run, duration, host, files, transaction trace |
| Cancellation cause/consequence | Canonical accepted-cause and economic-consequence artifacts/roots | Contract state transition and accounting commitment | Transaction, block, caller, events, balance reads, losing attempt |
| Realized capital consequence | Canonical realized-consequence artifact/root | Resulting terminal state and exact accounting values | Receipt, event/log, observed deltas, gas/runtime |
| Withdrawal realization | Canonical economic realization artifact/root | Consumed bit/claim guard, liability state, exact transfer state | Transaction, events, optional-proof telemetry, observed balances |
| Allocation closure / realized settlement | Canonical closure artifact/root derived from authoritative accounting | Terminal operation state and zero-liability accounting | Closure transaction, events, indexer and balance observations |
| Failed or racing attempt | No successful semantic transition | Revert result and unchanged authoritative state | Conflict/revert evidence in the existing forensic DAG |

Canonical semantic roots exclude transaction and block hashes, timestamps,
RPC provider, runner/process identity, gas, deployment script, filesystem path,
proof-generation runtime, and event position unless a future version explicitly
makes a field part of economic semantics. The existing forensic evidence graph
can attach these observations to a semantic subject root without changing it.

## Proof and entitlement-set binding

### Answer to the 192 byte question

**Classification C: a successor public-values and proof profile is required.**

The existing 192 byte source-bound V2 format is:

```text
schema
preparedOperationRoot
reserveStateCommitment
allocationBindingRoot
realizedStatementRoot
operationId
```

Its exact derivation chain is:

```text
witness prepared-operation fields -> guest derives preparedOperationRoot
witness economic-state fields      -> guest derives reserveStateCommitment
witness allocation-binding-root    -> guest copies word into public values
witness realized-statement-root     -> guest copies word into public values
witness operation-id                -> guest copies/compares through prepared fields
```

There is no guest derivation from realized allocation rows to either copied
root, no guest check relating the two copied roots, no beneficiary mapping, and
no entitlement representation scheme, set root, count, total, or allocation
claim root. `PrfStatementAdmission` verifies the registered program and receipt
identity correctly, but it cannot supply semantics the program did not derive.
`AllocationBindingV1` also cannot fill this gap: it commits an application
context root but intentionally contains no entitlement population.

The initially considered successor projection was twelve 32 byte words
(384 bytes). B1 replaces that convenience projection with the minimum direct
EVM verification projection below. The current source-bound guest's copied
roots and absence of successor code are Stage B implementation requirements,
not unresolved semantic choices.

```text
successor schema
preparedOperationRoot
reserveStateCommitment
allocationBindingRoot
realizedStatementRoot
operationId
entitlementRepresentationDescriptorRoot
canonicalEntitlementSetRoot
evmEntitlementTableRoot
entitlementCount
entitlementTotal
allocationClaimRoot
```

The successor guest must receive raw canonical inputs/witnesses, independently:

1. validate and derive the realized allocation and its statement/binding;
2. derive the exact beneficiary mapping from committed application context;
3. derive ordered canonical economic entitlements and their canonical set root;
4. validate the closed representation descriptor and derive the EVM table root,
   count, and total from the same entitlements and openings;
5. derive the successor allocation-claim root from the same rows; and
6. emit only those derived values.

Before freezing the successor projection, classify every candidate word as
guest-derived, canonical, directly consumed by Solidity, transitively committed,
or required to reject on-chain substitution. Only words needed for direct EVM
verification/consumption remain public; canonical artifacts may be bound
transitively.

The host may supply raw witness data and openings. It may not supply an expected
entitlement root as authority. The entitlement total must equal the exact funded
and allocated amount. The successor claim scheme must be separately named and
conformance-tested; it may preserve the ordered duplicate-last Merkle mechanics,
but its beneficiary/index semantics are not V1 leaf semantics.

`canonicalEntitlementSetRoot` is the portable PRF semantic identity.
`evmEntitlementTableRoot` is the deterministic keccak/ABI representation
commitment. Solidity activation must validate the public tuple against the
deployed table, descriptor root, operation/allocator/chain binding, code or data
identity, count, and total. Neither root substitutes for the other.

The existing 192 byte schema, V2 program identity, fixture, and verifier entry
remain frozen. They are not widened under the same identity. Stage B must assign
a unique successor public-values schema, SP1 program/version, VKey/ELF identity,
and Solidity decoder/profile identifier, distinct from
`observed-run-roots-384.v1`, `realized-allocation-execution.v1`, and the
source-bound V2 192-byte profile.

## Stage B1 — successor derivation graph and minimum public values

### Authority graph

The successor guest trusts only raw witnesses whose parent identities it can
validate, never a witness-supplied output root as authority:

```text
prepared-operation raw fields + governed configuration/chain facts
  -> preparedOperationRoot                         EVM-local keccak
prepared-operation beneficiary-profile field
  -> committed operation-level beneficiary profile
raw reserve economic state + prepared operation
  -> reserveStateCommitment                        EVM-local keccak
raw allocation inputs, policy, lifecycle, and Shortfall context
  -> realized allocation rows
  -> realizedStatementRoot                         PRF canonical SHA-256
validated reserve binding + realized statement + operation
  -> allocationBindingRoot                         PRF canonical SHA-256
committed beneficiary mapping/profile + positive realized rows + operation/binding
  -> ordered beneficiary entitlement members
  -> canonicalEntitlementSetRoot                   PRF canonical SHA-256
  -> allocationClaimRoot                           EVM-local keccak
  -> entitlementCount / entitlementTotal
canonical entitlement set + representation descriptor + opening material
  -> logical EVM commitment sequence
  -> evmEntitlementTableRoot                       EVM-local keccak
validated deployed data/code realization
  -> evmEntitlementDeploymentIdentity              deployment/forensic identity
```

`realizedStatementRoot` and `allocationBindingRoot` are independent canonical
artifacts. The latter derives from the former plus the Shortfall reserve binding
and operation; neither is an allocation algorithm nor present-tense execution
authority. The beneficiary mapping is a successor committed application-context
input, never a controller-supplied value after proof admission.

| Node | Classification | Authority rule |
| --- | --- | --- |
| prepared-operation fields (including beneficiary profile); reserve economic fields; allocation inputs/policy/lifecycle; Shortfall context; reserve observation/binding; beneficiary mapping; openings | raw witness/input or authenticated parent | Guest validates schema, closed shape, chain/configuration and parent/root relationships before use. The profile is read only from the prepared-operation parent and is committed by `preparedOperationRoot`; no separate profile witness is accepted. Openings provide non-semantic salt material only. |
| prepared operation; reserve observation; realized statement; allocation binding | canonical semantic artifact or authenticated EVM state | Guest derives their roots; contracts later compare the EVM-local prepared/reserve commitments with current state. |
| realized allocation; beneficiary-entitlement set | canonical semantic artifact | Derived from raw allocation/context and committed beneficiary mapping. |
| canonicalEntitlementSetRoot | derived canonical root | Derived from the ordered canonical set. |
| allocationClaimRoot; evmEntitlementTableRoot | EVM-local derived commitment | Derived from the same ordered entitlement members under separately versioned EVM schemes. |
| successor public values | public-value projection | Minimal proof output consumed by Solidity; not the canonical artifact store. |
| evmEntitlementDeploymentIdentity; transactions, blocks, prover runs, RPC reads | deployment/forensic observation | Establishes deployed realization; never changes semantic allocation or entitlement identity. |

The frozen 192-byte profile violates this rule only for
`allocationBindingRoot` and `realizedStatementRoot`: its host receives and the
guest echoes those words (`coprocessor/core/src/source_bound_v2.rs`,
`derive_fixture`). `preparedOperationRoot` is rederived from raw prepared
fields; `reserveStateCommitment` is rederived from raw economic state; and
`operationId` is parsed into the prepared operation then re-emitted. A successor
must derive all five and must derive the allocation/binding relationship from
raw allocation and Shortfall witnesses. It does not alter the frozen profile.

### Canonical beneficiary entitlement set

The successor canonical artifact is proposed as
`beneficiary-entitlement-set.v1`, subject to registration through the existing
`resolver-sim.hash.canonical/domain-hash` domain-tag registry. Its ordered
members are exactly the positive realized-allocation rows after applying the
committed claim-to-beneficiary mapping. A member conceptually binds:

```text
operation/application binding
realized-allocation identity
claim identity
stable beneficiary identity
beneficiary profile
allocated amount
canonical ordinal
```

The ordinal is the zero-based position after sorting positive members by the
canonical byte encoding of claim identity; duplicate claim identities are
rejected. This follows the existing realized-statement's claim-keyed sorted
construction rather than incidental witness row order. The canonical set artifact
commits operation/application identity, realized allocation identity,
beneficiary-profile descriptor, ordered members, `entitlementCount`, and
`entitlementTotal`. Count and total are independently recomputed from members,
not caller assertions.

The beneficiary profile is selected once per operation by the authenticated
prepared-operation/application policy context. It is included in the
prepared-operation preimage and therefore changes `preparedOperationRoot` when
the committed profile changes. Every member carries the resulting profile as
inherited semantic data; profiles are not independently selectable per
entitlement. The logical representation descriptor defines how that chosen
profile is encoded, without importing current signer, validator, or recovery
state into historical entitlement identity.

For the Shortfall production profile, `entitlementTotal == realized allocation
amount == authoritative available amount` and `entitlementCount` is the number
of positive execution entitlements. Unfilled demand remains application-level
demand outside all three quantities. The already frozen allocation residual is
zero.

`allocationClaimRoot` is **A: a deterministic EVM-local projection of the same
ordered beneficiary entitlement set**, not a separately populated allocation.
Its successor leaf commits operation binding, ordinal, claim, beneficiary, and
amount. Thus a claim proof and a table opening cannot disagree about any of
those fields. The old `allocation-merkle-v1` leaf is frozen and is not reused.

### Logical descriptor, table, and deployment realization

`entitlementRepresentationDescriptorRoot` identifies the logical EVM
representation only. Its closed descriptor binds schema/version, commitment
domain, every commitment field's exact ABI type, uint/address/claim/domain word
schemes, static ABI word concatenation, canonical-index mapping,
beneficiary-profile encoding, salt/opening construction, commitment
construction, claim-leaf fields, claim/table Merkle rules, table summary fields,
ordered logical table aggregation, and consumption bitmap index/bit order. It excludes shard
size/count/addresses, factory, deployment transaction, code location, and gas
strategy unless a fact changes logical record interpretation or root derivation.

Salt is wallet-held EVM-local opening material: exactly 32 bytes per member,
supplied as raw witness material and included in the EVM commitment but
excluded from the canonical entitlement member and canonical set root. High
entropy is an operational privacy assumption. Neither the guest nor the EVM
attempts to prove randomness. Cross-language equivalence checks explicit salt
vectors exactly and never generates random salts inside conformance fixtures.

The precise derivation is therefore:

```text
canonical beneficiary entitlement set
+ entitlement-representation-descriptor.v1
+ ordered bytes32 opening salts keyed by claim identity
-> ordered logical EVM entitlement commitments
-> evmEntitlementTableRoot
```

`evmEntitlementTableRoot` is not a function of the canonical set alone. A salt
change preserves `realizedStatementRoot`, `allocationBindingRoot`, every
canonical member identity, `canonicalEntitlementSetRoot`,
`allocationClaimRoot`, count, and total, while changing the corresponding EVM
commitment and potentially the logical table root.

`evmEntitlementTableRoot` is a domain-separated keccak commitment to the
logical sequence `[C_0, ..., C_(n-1)]`, its count, and total, where `C_i` is the
descriptor-defined ABI commitment for ordinal `i`. The descriptor fixes leaf
encoding, ordinal ordering, aggregation, empty-set behavior, and duplicate
rejection. The Shortfall profile rejects an empty set and duplicate claim IDs;
the generic algorithm still specifies an empty root for conformance. Physical
storage can be packed, blob-backed, or sharded while realizing this same root.

`evmEntitlementDeploymentIdentity` separately identifies deployed code/data and
any shard set. Stage C activation must validate that deployment realizes the
proof-bound logical table root, descriptor, count, and total. Deployment
addresses and code hashes are not SP1 public values unless implementation shows
an extra on-chain comparison is necessary; they belong to activation validation
and the Gate E forensic DAG.

### Root and projection table

| Identity | Layer | Hash/encoding family | Derived from | Consumer |
| --- | --- | --- | --- | --- |
| `realizedStatementRoot` | canonical | PRF SHA-256 canonical encoding | raw allocation inputs, policy, lifecycle, realized rows | successor guest; allocation-binding derivation |
| `allocationBindingRoot` | canonical | PRF SHA-256 canonical encoding | reserve binding, realized statement, operation | admission and reserve/allocator lineage |
| `canonicalEntitlementSetRoot` | canonical | PRF SHA-256 canonical encoding | ordered beneficiary entitlement members | guest/conformance/forensic semantic linkage |
| `entitlementRepresentationDescriptorRoot` | canonical | PRF SHA-256 canonical encoding | closed logical representation descriptor | activation/profile selection |
| `preparedOperationRoot` | EVM-local | existing keccak word hash | prepared operation fields | controller comparison |
| `reserveStateCommitment` | EVM-local | existing keccak word hash | reserve economic state | reserve comparison/funding |
| `allocationClaimRoot` | EVM-local | successor keccak/ABI Merkle scheme | ordered entitlement members | Required proof verification |
| `evmEntitlementTableRoot` | EVM-local | descriptor-defined keccak/ABI table scheme | commitments/count/total | activation and opening verification |
| `evmEntitlementDeploymentIdentity` | deployment/forensic | EVM code/data/readback identity | deployed realization | activation validation, Gate E evidence |

Requested/Required mode, optional-proof status, signer/validator state, prover
run, proof time, transaction/block hashes, deployment transaction, RPC, gas,
filesystem, and runner/process are excluded from canonical entitlement, claim,
and table roots. A governed policy family may define their meaning, but mode
changes never alter those roots.

### Minimum direct EVM projection

| Candidate field | Layer | Guest derives | Solidity direct need | Disposition |
| --- | --- | --- | --- | --- |
| schema/profile identifier | EVM-local profile | yes | decoder rejects cross-profile bytes | **MUST BE PUBLIC** |
| `preparedOperationRoot` | EVM-local | yes | compare with prepared controller state | **MUST BE PUBLIC** |
| `reserveStateCommitment` | EVM-local | yes | compare with live reserve state | **MUST BE PUBLIC** |
| `allocationBindingRoot` | canonical | yes | admission statement-root comparison and persisted lineage | **MUST BE PUBLIC** |
| `realizedStatementRoot` | canonical | yes | no independent successor Solidity comparison | **MAY BE TRANSITIVELY BOUND** by allocation binding |
| `operationId` | EVM-local | yes | controller supplies/keys operation; prepared root binds it | **MAY BE TRANSITIVELY BOUND** |
| `entitlementRepresentationDescriptorRoot` | canonical | yes | activation selects/validates logical profile | **MUST BE PUBLIC** |
| `canonicalEntitlementSetRoot` | canonical | yes | Solidity neither computes nor compares it directly | **MAY BE TRANSITIVELY BOUND** |
| `evmEntitlementTableRoot` | EVM-local | yes | bind activation to deployed logical table | **MUST BE PUBLIC** |
| `entitlementCount` | derived scalar | yes | close/count accounting and table bounds | **MUST BE PUBLIC** |
| `entitlementTotal` | derived scalar | yes | funding, activation liability, conservation | **MUST BE PUBLIC** |
| `allocationClaimRoot` | EVM-local | yes | Required-mode claim proof verification | **MUST BE PUBLIC** |

The B1 minimum proposal, confirmed and frozen by the independent Clojure/Rust
B2 derivations, is nine ABI words / **288 bytes**:

```text
schema
preparedOperationRoot
reserveStateCommitment
allocationBindingRoot
entitlementRepresentationDescriptorRoot
evmEntitlementTableRoot
entitlementCount
entitlementTotal
allocationClaimRoot
```

The two scalar words are unsigned values encoded as zero-padded, big-endian
Solidity `uint256` words. The current allocation kernel constrains their source
values to its checked `u128` range; the public encoding itself remains the
standard 32-byte EVM integer representation. The schema word is
`keccak256(utf8("prf/source-bound-entitlement-public-values.v1"))`, fixed by the
profile and never supplied by the witness.

This retains direct on-chain substitution checks while omitting
`realizedStatementRoot`, `operationId`, and `canonicalEntitlementSetRoot`, each
of which remains independently derived and tested while transitively bound by
the public projection.

The proposed unique identities are:

```text
public-values schema: prf/source-bound-entitlement-public-values.v1
Rust type: SourceBoundEntitlementPublicValuesV1
SP1 program: source-bound-entitlement-sp1-program.v1
Solidity decoder/profile: PrfSourceBoundEntitlementVerifierAdapterV1
```

This is a new `source-bound-entitlement` family, not an extension of
`source-bound-v2`: it reconstructs previously trusted lineage and adds
beneficiary entitlements, a successor claim scheme, canonical entitlement
identity, and EVM representation binding. Its VKey and ELF are new identities
and it is distinct from `observed-run-roots-384.v1`,
`realized-allocation-execution.v1`, and the frozen 192-byte profile.

### B2 handoff and substitution properties

B2 inputs are the authenticated raw witnesses named above plus the closed
descriptor/opening vector. Its outputs are canonical artifacts and nine public
words. Clojure, Rust core, SP1 guest, and Solidity decoding/checks must agree.
Changes to claim, beneficiary, amount, ordinal, operation, application binding,
realized allocation, beneficiary profile, descriptor, commitment encoding, or
claim-root structure must reject. Changes only to physical shard sizing,
deployment addresses, transaction/block, prover run, gas/runtime, or Requested
optional-proof telemetry must leave canonical semantic identities unchanged.

Existing reusable machinery is `resolver-sim.hash.canonical/domain-hash` and
its `domain-tags` registry, the realized-statement kernel, the
`shortfall.allocation-binding` pattern, and the Rust canonical mirror. B2 adds
successor domains and closed-shape/schema registration through those mechanisms;
it does not create an ad-hoc hash codec.

### Stage B2 frozen pure derivation

The Clojure and Rust core implementations now independently implement the B1
graph without an SP1 entrypoint. The raw successor witness shape is closed to:

```text
realized-statement-input
reserve-binding-root
prepared-operation
economic-reserve-state
beneficiary-mapping
openings
```

Every output root, count, total, public word, and the 288-byte projection is
derived. No expected root or aggregate is accepted as witness authority. The
claim-to-beneficiary projection must equal the allocation context's canonical
`economic-owner-id` for that claim; a downstream witness cannot independently
remap a realized allocation to another beneficiary.
Positive realized rows are ordered by unsigned bytewise comparison of the PRF
canonical claim-identity encoding; zero rows do not become entitlements;
duplicate claim identities, duplicate mappings, duplicate openings, and
inexact mapping/opening coverage reject. Ordinals are assigned only after this
ordering.

`beneficiary-entitlement.v1`, `beneficiary-entitlement-set.v1`, and
`entitlement-representation-descriptor.v1` use registered PRF SHA-256 canonical
domains. The successor allocation claim leaves, individual entitlement
commitments, duplicate-last ordered Merkle nodes, and logical table summary use
their explicit EVM keccak domains and static 32-byte ABI words. The claim tree
and EVM commitment table are projections of the same ordered canonical set;
there is no independently populated second list of recipients or amounts.

The conformance bundle at
`etc/conformance/vectors/source-bound-entitlement-v1.json` exposes field-level
intermediate roots, ordered members, leaves, commitments, nine words, and the
full encoding. It includes reordering, rounding, alternate salts, beneficiary
substitution, and duplicate rejection. Runtime, chain observation, current
Requested/Required mode, signer state, and proof telemetry are absent from the
derivation input.

## Stage B3 successor guest

The successor guest is implemented at
`coprocessor/source-bound-entitlement-sp1-program/src/main.rs`. It is a thin
SP1 entrypoint: it reads one JSON byte vector, invokes the reviewed Rust core
derivation, and commits exactly the nine-word
`prf/source-bound-entitlement-public-values.v1` projection (288 bytes). The
guest input contains only the raw successor witness and explicit 32-byte
claim-keyed openings; derived roots and aggregate outputs are not input
authority. The beneficiary profile is read only from the authenticated
`prepared-operation` context, and the descriptor is fixed by core
construction.

The new program family is `source-bound-entitlement-sp1-program.v1`, distinct
from the historical source-bound V2 guest. Its qualified identity is:

```text
public-values schema: prf/source-bound-entitlement-public-values.v1
public-values length: 288 bytes
SP1 SDK: 6.3.1
proof system: Groth16
VKey: 0x003cb0ac41d9c5cd78fe461b5ba27dfb08cd74490c9526fa762e7e392ee7bac5
ELF SHA-256: 0x7e9cf1b3479a4b6fb8836b50b30b55d00736ac860eaba4230f057d4687bc0e39
```

SP1 6.3.1 light execution completes vectors A–E; all successful outputs match
Rust core and the 288-byte golden fixtures. The reordered witness is
byte-identical to A, alternate salts change only the EVM entitlement table
root word, and the authenticated profile variation executes successfully.
Vector F (duplicate claim) is rejected by the guest. Unknown top-level
derived-root fields halt with a nonzero guest exit code.

The initial 300-second stall was in `ProverClient::from_env()` construction in
the execute branch: with no `SP1_PROVER` override it initialized the CPU
prover node before local execution. Execute mode now uses
`ProverClient::builder().light().build()`, which does no proving setup. Host
diagnostics also check the SP1 report exit code; execute may return an `Ok`
result containing a nonzero guest exit code for a rejected witness. A Groth16
proof for vector A was generated, verified through the SP1 SDK against the
VKey derived from this ELF, checked byte-for-byte against Rust core and the
golden public values, and serialized as on-chain proof bytes. The fixture is
at `/opt/prf-b3-target/source-bound-entitlement-proof-v1.json` in the proving
environment; its source identifies bundle SHA-256
`0xa8a9310fbc439a7d9d21d60078cfce78f714cdaafeebd8d27c2b0a80e2310848`, vector
`A-minimum-realistic`, and witness SHA-256
`0x83b41d2bd9d6c81b3d89823b097be01209b0c2dd0cf3bd45b37340b2744be5c1`.
The serialized Groth16 proof is 356 bytes. No verifier-registry entry or
deployment identity has been activated.

The host accepts either one raw witness through `--input`, or a conformance
bundle and vector ID through `--vectors <bundle> --vector <id>`. Vector mode
uses the shared Rust materializer also used by the core conformance test and
checks guest/proof public values against the selected vector's golden bytes.
Generated proof fixtures record the bundle content hash, vector ID, and exact
materialized witness hash; filesystem paths remain excluded. Example:

```bash
cargo run --manifest-path coprocessor/sp1-script/Cargo.toml \
  --bin source-bound-entitlement-prove -- --prove \
  --vectors etc/conformance/vectors/source-bound-entitlement-v1.json \
  --vector A-minimum-realistic \
  --fixture /path/to/source-bound-entitlement-proof-v1.json
```

## Accounting and conservation

At every successful reserve transition:

```text
totalReserved + protectedCapital <= token.balanceOf(reserve)
```

The exact deltas are:

| Transition | Reserve custody | `totalReserved` | `protectedCapital` | Allocator custody | Outstanding liability | Beneficiary custody |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| reserve | unchanged | `+ amount` | unchanged | unchanged | unchanged | unchanged |
| cancel-and-release | unchanged | `- amount` | unchanged | unchanged | unchanged | unchanged |
| fund | `- amount` | `- amount` | unchanged | `+ amount` | not yet established | unchanged |
| activate | unchanged | unchanged | unchanged | unchanged | `= entitlementTotal` | unchanged |
| withdraw | unchanged | unchanged | unchanged | `- amount` | `- amount` | `+ amount` |
| close | unchanged | unchanged | unchanged | zero | zero | unchanged |

V1 reserve and allocator funding already check exact sender/receiver balance
deltas. V2 reserve funding does the same and clears the temporary allowance.
V1 claims check exact allocator/recipient deltas. V2 withdrawals must retain
those checks. Fee-on-transfer, rebasing, callback-driven balance changes, and
other unsupported token behavior fail closed and revert all state.

The current V2 allocator cannot express outstanding liability, claimed amount,
claimed count, or conservation through withdrawal. Those are missing Stage C/E
state, not facts implied by `Funded`.

## Irreversible cutpoints and retry rules

| Point | Meaning | Retry/cancellation rule |
| --- | --- | --- |
| Prepared operation | Identity frozen, zero capital | Initiator may abandon; fresh operation ID is required afterward. |
| Prepared assurance | Receipt and `(P,C)` frozen, zero capital | Stale ratification reverts without consuming assurance; exact retry is possible while `C` remains live. |
| Persistent reservation, if any | Capital excluded from availability but still in reserve custody | Fund or cancel-and-release exactly once. Snapshot remains immutable. |
| Reserve funding | Exact custody moves to allocator | Not itself final entitlement creation. In the preferred V2 transaction activation follows before commit. |
| Allocator activation | Entitlement liabilities become authoritative | Ordinary cancellation/refund and entitlement revocation are forbidden. This is the economic irreversibility cutpoint. |
| Entitlement consumption | One entitlement is spent | Cannot be retried successfully; a reverted transfer reverts consumption. |
| Operation closure | All count and amount liabilities discharged | Terminal; no sweep or reopen. |

Historical artifact immutability is distinct from economic irreversibility.
Prepared roots, admitted receipts, snapshots, observations, and cancelled
records can be immutable while capital is still releasable or refundable.

## Race model

- Persistent reserve cancellation first: terminal `CancelledReleased`; funding
  rejects.
- Persistent reserve funding first: terminal `Funded`; cancel-and-release
  rejects.
- Preferred atomic V2 ratification: competing transactions race on live reserve
  commitment, operation identity, assurance consumption, and allocator state;
  at most one commits.
- If `Funded` is ever persistent, cancel-and-refund first must make activation
  reject, and activation first must make refund reject.
- Identical withdrawals race on the consumed bitmap and claim replay guard;
  exactly one may commit.
- No withdrawal-cancellation race exists because no pending withdrawal exists.

## Consequence compatibility

The NOW design naturally preserves the future decomposition:

```text
authoritative cause
  -> policy-bound consequence
  -> concrete effect
  -> realized consequence
```

For reserve cancellation:

```text
canonical cause: reservation cancellation accepted for operation/root
canonical consequence: release reservation
canonical effect: totalReserved decreases by the retained amount; custody is unchanged
canonical realization: terminal CancelledReleased state and resulting totalReserved
```

For a persistent funded pre-activation cancellation, if retained:

```text
canonical cause: allocator cancellation accepted for operation/root
canonical consequence: refund funded capital
canonical effect: allocator decreases and recorded source/funder increases by the amount
canonical realization: terminal CancelledRefunded state and resulting accounting
```

The canonical cancellation/consequence identity binds the subject, expected
prior state, cancellation authority/policy, cause, consequence class, exact
economic effect, and resulting state. The original reserve/source snapshot is a
separate canonical fact referenced by the subject and is never rewritten.
Here, cancellation authority means the canonical policy, authorized role, or
governed decision identity that permits the transition. The transaction sender
is forensic unless that policy explicitly makes a particular address part of
the economic cause.

“Realized consequence” means a canonical semantic artifact for the successfully
completed economic transition. It is not a synonym for forensic evidence. For
example, releasing reservation amount `A` to resulting `totalReserved = R'` is
canonical realization content. Transaction and block hashes, timestamp, caller,
event/log position, observed before/after balances, gas/runtime, execution
attempts, and race losers are forensic evidence attached to that semantic
subject. They do not enter its canonical root unless a future policy makes a
specific field economically authoritative.

Cause and effect remain distinguishable in identity and documentation, while
each production transition stays atomic. No generic dispatcher, intermediate
transaction, appeal-bond change, or speculative state is needed. Later
Consequence extraction can represent these facts without changing today's
economic semantics. Making a generic `cancel` erase the source snapshot, reuse
the operation ID, or hide whether custody moved would make that extraction
difficult and is forbidden.

## Gate E mapping

The intended retained path is:

```text
deployment manifest
  -> deployed and read-back topology
  -> source-bound successor proof
  -> configured verifier
  -> PrfStatementAdmission receipt
  -> prepareSourceAssurance
  -> ratify
  -> reserve exact live amount
  -> allocator create/fund/activate atomically
  -> Requested withdrawal
  -> governed switch to Required
  -> Required withdrawal
  -> close and exact final accounting
```

The current Gate E script reaches deployed topology, preparation, reserve
deposit, manifest capture, and external prover invocation. Its final test does
not execute proof admission or any capital call. Gate E must therefore remain
open. Later work must preserve the manifest/read-back and real verifier progress,
replace the proof profile deliberately, and extend the same persistent Anvil
session through actual receipt, assurance, ratification, activation,
withdrawals, and balance/liability reconciliation.

The eventual run also preserves a separate forensic evidence chain:

```text
validated deployment / chain instance
  -> reserve observation
  -> proof generation
  -> proof verification
  -> admission
  -> assurance
  -> ratification
  -> funding
  -> activation
  -> entitlement representation deployment/read-back
  -> withdrawal
  -> closure
```

This DAG reuses the existing forensic evidence infrastructure and attaches
observed/validated roles to canonical subject roots. It does not define those
subjects. The same semantic allocation, entitlement, withdrawal realization,
and closure may be evidenced by different transactions, blocks, prover runs,
deployment observations, RPC providers, runners, or processes.

Failed fund/cancel racers, duplicate withdrawals, invalid optional proofs under
Requested, missing proofs under Required, wrong openings, and substitution
attempts remain useful conflict or failed-attempt nodes. They never become
successful semantic transitions and cannot create canonical realization roots.

## Concrete blockers and contradictions

1. The current source-bound guest treats allocation and statement roots as
   witness assertions rather than independently deriving their relationship.
2. The V2 allocator terminates at persistent `Funded` with no activation or
   cancel-and-refund path.
3. No on-chain governed projection exposes Requested/Required from the current
   chain configuration.
4. Neither the realized statement nor the current V2 public values commits a
   beneficiary population or entitlement representation.
5. `SourceBoundProRataRatificationControllerV2` does not re-check the prepared
   controller's state during `ratifyAndFund`. An initiator can mark preparation
   `Abandoned` after registration while the separate ratification state remains
   `Prepared`; the current downstream path can still proceed. `Abandoned` must
   become authoritative before it is described as cancellation.
6. The ratification ABI accepts `configurationRoot`, `configurationEpoch`, and
   `residualRecipient` after proof preparation. The reserve does not compare
   these values to decoded prepared-operation fields, and its V2 allocator does
   not retain configuration fields. Production activation inputs must be derived
   from or exactly checked against the prepared/proved identity.
7. Current Gate E does not execute admission or a capital transition and cannot
   serve as production E2E evidence yet.

These are V2 successor issues. None justifies changing a frozen V1 surface.

## Frozen V1 surfaces

The NOW tranche must not mutate or reinterpret:

- `ProductionProRataAllocatorV1` ABI, state enum, lifecycle, `claim`, `cancel`,
  exact transfer behavior, claim replay mapping, close conditions, or authority;
- `OnchainShortfallReserveV1` ABI, lock-first lifecycle, snapshot hash/domain,
  reservation deadline, active-operation mutex, cancellation/expiry behavior,
  or funding behavior;
- `realized-allocation-statement.v1`, its six canonical roots, SHA-256 intent,
  and canonical encoding;
- `realized-allocation-execution.v1`, its 384 byte schema, execution profile,
  operation binding, claim leaf/node/empty-root domains, ordered duplicate-last
  tree, recipient mapping, count/total/residual semantics, SP1 program identity,
  and conformance vectors;
- `AllocationBindingV1` artifact shape, derivational direction, canonical root,
  and non-authorizing meaning;
- the 192 byte source-bound V2 schema, `source-bound-v2-sp1-program.v2`, current
  VKey/ELF fixture identity, prepared/economic/observation domains, and existing
  receipts;
- `PrfStatementAdmission` and admission-receipt identity;
- sealed `PrfVerifierRegistry` snapshots and canonical roots;
- versioned `chain-configuration.v1` through V4 projections and roots; and
- existing protocol genesis, chain-instance genesis, configuration transition,
  decision, and change-identity domains.

New entitlement, beneficiary, claim-proof, public-values, program, verifier, and
configuration-policy semantics use successor identities and V2/new contracts.

## Stage B through E plan

### Stage B — entitlement derivation and proof binding

1. Define the successor beneficiary-aware entitlement and ordered set
   derivation without changing V1 statement/execution identities.
2. Define a versioned application-context/policy successor that commits the
   beneficiary profile and exact claim-to-beneficiary mapping.
3. Implement byte-identical Clojure and Rust derivation for the representation
   descriptor root, canonical entitlement-set root, EVM table root, count,
   total, and successor claim root.
4. Make the successor SP1 guest independently reconstruct the allocation,
   statement/binding relationship, beneficiary mapping, and all entitlement
   outputs from raw witness inputs.
5. Minimize the candidate public values, then freeze a uniquely named successor
   schema, program/version, VKey/ELF identity, decoder/profile identifier, and
   mutation/conformance vectors. Preserve the current 192 byte profile.

### Stage B acceptance gates

Stage B may begin implementation only after it can demonstrate independent
derivation of the realized allocation, realized statement root, application
binding root, beneficiary entitlement set, canonical entitlement-set root,
representation descriptor, EVM logical table root, count, total, and claim root
from authenticated/raw witness material. Required Clojure, Rust core, SP1, and
Solidity derivations must agree. Substitution tests must reject different
entitlement sets, logical tables, descriptors, beneficiaries, amounts,
operations, application bindings, realized statements, and claim roots.

Stage A semantic model: acceptable subject to these documentation
clarifications. Stage B hard requirements: successor guest authority derivation
and a unique minimized successor public-values profile. Stage C hard
requirement: prove that the deployed physical representation realizes the bound
logical `evmEntitlementTableRoot`.

### Stage C — Solidity entitlement representation

1. Add a V2 allocator successor with activation and liability accounting.
2. Implement `public-packed-v1` first as the conformance oracle.
3. Implement or conclusively reject, by benchmark, sharded
   `committed-immutable-table-v1`; validate code/data identity, root, count,
   total, operation, allocator, and chain before activation.
4. Use consumed bitmap plus semantic claim replay protection.
5. Differentially test both profiles and benchmark 10/100/1,000/10,000 rows.

### Stage D — cancellation-safe capital lifecycle

1. Preserve atomic reserve commitment comparison, exact ephemeral reservation,
   exact funding deltas, allowance clearing, and full rollback.
2. Extend orchestration so successful ratification reaches activation in the
   same transaction.
3. Add reserve cancel/fund ordering tests for persistent reservations and
   activation/refund ordering tests only if a persistent funded state remains.
4. If atomic activation proves impossible, implement exact source-recorded
   cancel-and-refund before permitting the split path.

### Stage E — one-step withdrawal and live proof mode

1. Add one V2 `withdraw` ABI with entitlement opening, optional proof, and caller
   minimum mode; no pending request object.
2. Enforce authoritative entitlement in both modes, optional non-blocking proof
   observation in Requested, and mandatory valid proof in Required.
3. Read present mode from the narrow projection of the current governed chain
   configuration; add no owner setter and no emergency lane.
4. Consume before exact transfer, update amount/count liabilities, and close
   only on complete discharge.
5. Add mode-switch, substitution, replay, reentrancy, hostile-token, race, and
   conservation tests, then extend Gate E through real admission, activation,
   both modes, and final accounting.

Beneficiary recovery machinery, payout adapters, Sew-specific changes, Kohaku,
staking/slashing, generic cancellation, and generic Consequence extraction are
outside these stages.

## Stage A completion answers

- **What is allocated?** A fixed available token quantity is deterministically
  divided across ordered eligible claims by the generic pro-rata primitive. Its
  output is the realized allocation; beneficiary-bound entitlements are the
  downstream execution representation. For Shortfall, realized total equals
  available capital, allocation residual is zero, and unfilled demand may
  remain positive outside allocator custody.
- **How is the set bound?** A successor guest derives the beneficiary-bound
  canonical set root, representation descriptor root, EVM table root, count,
  total, and claim root from the same realized allocation and emits them in a
  new public-values profile. The current 192 bytes cannot do this.
- **Who is the beneficiary?** A stable smart-account address by default, or an
  explicitly non-recoverable immutable address. It is not the current signer.
- **What does Requested trust?** The activated authoritative Solidity
  entitlement and its exact opening/consumption state.
- **What does Required add?** A valid proof under the operation's immutable
  claim-proof scheme and root.
- **What changes live?** Only current proof-enforcement mode, through governed
  chain configuration, subject to the caller's non-weakening minimum.
- **When is allocation economically irreversible?** On allocator activation,
  when immutable entitlement liabilities become authoritative.
- **What happens before then?** A persistent reservation can cancel-and-release;
  a persistent funded allocator must cancel-and-refund. The preferred V2 path
  makes reserve, fund, and activate atomic so failure rolls back.
- **What stays immutable after cancellation?** Operation identity, original
  snapshot/commitments, amount, cause, terminal state, admission evidence, and
  historical observations.
- **Can a later Consequence primitive represent cancellation?** Yes. Cause,
  consequence, concrete balance/accounting effect, and realization remain
  distinct without adding present intermediate states or transactions.
