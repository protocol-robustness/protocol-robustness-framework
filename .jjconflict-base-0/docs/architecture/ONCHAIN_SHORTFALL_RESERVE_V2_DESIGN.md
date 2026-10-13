# Shortest Shortfall Reservation V2 — Optimistic Assurance Design

Status: **ASSURANCE MODEL IMPLEMENTED — ASSURANCE CLOSURE PENDING**

V1 is the conservative baseline: `lockAvailableFor` obtains exclusive control of
all currently allocable reserve liquidity, then source assurance and allocator
work proceed under an immutable, bounded reservation lease. V2 asks whether the
expensive assurance work can happen before exclusivity without weakening V1's
economic or forensic guarantees.

## Current implementation boundary

### Shortest Shortfall Reservation V2

#### Assurance model

- ✓ `PreparedOperationV2`
- ✓ `EconomicReserveStateV2`
- ✓ `availabilityRevision` / ABA resistance
- ✓ 192-byte `SourceBoundPublicValuesV2`
- ✓ separate Solidity V2 decoder
- ✓ separate V2 SP1 guest
- ✓ distinct V2 fixture and program namespaces

#### Assurance closure

- ✓ V2 guest builds
- ✓ Rust/SP1 host proof generation and committed-public-values equality assertion
- ✓ concrete V2 program/ELF identity (`source-bound-v2-sp1-program.v2` plus pinned ELF hash)
- □ concrete deployed verifier identity
- ✓ canonical Rust/SP1 proof fixture pinned
- △ Rust ↔ SP1 equality is green; Solidity fixture consumption/equality remains open
- □ complete substitution vectors

#### Capital path

- □ intentionally not started

The V2 guest commits only the exact 192-byte V2 public-values statement. Debug,
provenance, and reproduction metadata belong in fixtures and surrounding evidence,
not in SP1 public values.

Implemented independently:

- versioned `PreparedOperationV2` canonical root structure;
- versioned `EconomicReserveStateV2` canonical commitment structure;
- monotonic `availabilityRevision` ABA defense;
- six-word, 192-byte `SourceBoundPublicValuesV2` encoding;
- isolated Solidity V2 decoder with strict V2 schema and width validation;
- no V2 controller, reserve, ratification, or capital-movement path.

Not yet established:

- V2 witness derivation from the production semantic fixture;
- V2 SP1 program, ELF, and verifier identity;
- SP1 ↔ Rust public-values equality;
- final review/freeze of every non-transferability field in `PreparedOperationV2`.

The next milestone is closure of the Rust ↔ SP1 ↔ Solidity assurance triangle, not V2 execution.

## Non-negotiable V1 guarantees

A V2 implementation must preserve all of the following:

- the proved economic reserve state is the state ratified on-chain;
- the allocated amount is authoritative under explicitly chosen availability
  semantics;
- proof identity remains bound to the operation, reserve, token, allocator,
  configuration, verifier/program, and chain;
- authoritative observation metadata remains available for audit and replay;
- stale or invalid assurance cannot reserve or fund capital;
- failed or abandoned preparation cannot create a reservation or global
  Shortfall exclusion;
- the V1 lock-first path remains available as a conservative fallback.

## Proposed lifecycle

```text
prepareOperation
  -> freeze operation/configuration identity (zero capital reserved)
  -> observe candidate economic state E
  -> derive allocation and realized statement
  -> prove/admit E
  -> atomic ratify E against live state E'
  -> create authoritative observation snapshot S
  -> reserve exact amount
  -> create allocator operation
  -> fund allocator
```

`Prepared` is not `Reserved`. Multiple prepared operations may coexist only if
admission and ratification prevent one operation from consuming another's
identity or assurance.

## Two commitments, not one overloaded hash

The proof should commit to an economic/state commitment, not to metadata that
is created only during ratification:

```text
P = H(
  PREPARED_OPERATION_V2,
  complete frozen operation and configuration identity
)

C = H(
  RESERVE_STATE_V2,
  P,
  grossBalance,
  reservedBefore,
  protectedCapital,
  availableAmount,
  availabilityRevision
)
```

`P` is the canonical prepared-operation root. It must include the complete
frozen identity rather than independently threading identity fields through every
later commitment. At minimum it covers operation ID, initiator, reserve, token,
allocator, chain, configuration root and epoch, source and execution verifier
identities, program key and ELF hash, statement and public-values schemas, policy
roots, and residual recipient.

`availabilityRevision` is a monotonic reserve-controlled revision. It advances
on every reserve mutation relevant to Shortfall availability, including ordinary
reservation create/release/consume, protected-capital changes, Shortfall funding
or return, and other reserve withdrawals. It prevents ABA reuse:

```text
state A @ revision 41 -> state B @ revision 42 -> state A @ revision 43
```

External ERC-20 transfers into the reserve cannot advance the revision, but they
change `grossBalance` and therefore invalidate `C`. V2 must either restrict
rebasing/callback-heavy/exotic token behavior or define a separate accounting
adapter for tokens that can change reserve balance without a normal transfer
observation. The reference model assumes a standard, non-rebasing ERC-20 where
outsiders can transfer in but cannot withdraw from the reserve.

The ratification transaction recomputes `C'` from live state and requires:

```text
C' == C
```

Only after that check does the reserve allocate `snapshotId` and
`createdAtBlock`, producing an authoritative observation artifact:

```text
S = H(domain, C, snapshotId, createdAtBlock)
```

The proof binds to `P` and `C`; the forensic observation binds `S` to `C`.
`C` does not assert when the state was authoritatively observed. It asserts the
exact economic state eligible for ratification. `S` records when that state was
actually observed and accepted on-chain. The current V1 `reserveSnapshotHash`
meaning is not changed by this proposal; V2 would use versioned commitment and
observation surfaces.

## Operation identity prerequisite

The operation identity and all admission-critical configuration must be frozen
before proving. At minimum, preparation must bind:

- operation ID and initiating authority;
- reserve, token, allocator, and chain;
- configuration root and epoch;
- source and execution verifier identities;
- program key, ELF hash, statement schema, and public-values schema;
- policy roots and any residual-recipient identity used by the allocator.

A proof for an unregistered or mutable operation ID must not be reusable by a
front-runner, another initiator, or a different allocator/configuration.

## Ratification semantics

The first design candidate is exact equality: any relevant reserve change makes
ratification revert. This preserves V1's exact-current-availability semantics
and avoids silently granting later deposits to an earlier proof.

The following are therefore expected to invalidate `C`:

- deposits or withdrawals changing gross balance;
- ordinary reservation creation or release;
- protected-capital changes, if those become mutable in a successor;
- allocator, token, reserve, operation, or policy identity changes;
- any other input included in the canonical economic commitment.

The alternative predicate `currentAvailable >= provedAmount` is explicitly not a
mere optimization. It changes the economic rule and requires a separate policy
decision about later inflows and competing operations.

## Atomic ratification requirements

The ratification entry point must, in one transaction:

1. verify the prepared identity and admitted source assurance;
2. recompute and compare the live economic commitment;
3. create the authoritative observation metadata;
4. establish the exact reservation;
5. create the allocator operation;
6. fund the allocator;
7. clear temporary reservation accounting.

Any failure reverts all of these effects. No stale proof may leave behind a
reservation, allocator operation, or active Shortfall mutex.

After `C' == C`, ratification must enter an explicit ephemeral `Ratifying`
state and reserve the exact amount before any external allocator call. The entry
point is non-reentrant and all subsequent steps are in one transaction. This
reservation protects intra-transaction integrity across external calls; unlike
V1's lease, it is not cross-transaction concurrency control. On any failure the
entire transaction reverts, including the temporary reservation, allocator
creation, funding, and assurance consumption.

The resulting exclusivity interval is the ratification transaction rather than
the proof-production interval.

## Threat model and required scenarios

Before implementation, model and test:

- unsolicited deposits, withdrawals, and dust transfers;
- ordinary reservation races;
- competing prepared operations;
- proof admission replay under another operation/configuration;
- proof failure, timeout, and retry;
- ratification transaction races;
- chain reorg or stale observation assumptions;
- cancellation and expiry of prepared operations;
- allocator creation/funding failure;
- exact equality invalidation churn and griefing cost.

Prepared state must have no capital reservation and no global Shortfall mutex.
Abandonment is therefore dead metadata rather than a liquidity/liveness fault;
cleanup may be provided for storage hygiene but does not need V1-style expiry.
Preparation must nevertheless prevent operation-ID squatting: registration must
be authorized by the initiator encoded in `P` and use a unique operation-ID
namespace or nonce.

## Assurance and retry policy

Source assurance is one-shot at the controller/application layer even if an
admission receipt is reusable as a verification artifact. Ratification must
consume an assurance bound to `P`, `C`, the allocation-binding root, and the
realized statement root. A stale `C` fails before consumption. If allocator
creation or funding fails, the transaction reverts and the assurance remains
unconsumed, allowing retry while the same candidate remains live.

The initial implementation should prefer one candidate per prepared operation:

```text
Prepared -> AssurancePrepared -> Ratified
```

If ratification becomes stale, the operation is stale/cancelled and retry uses a
fresh operation ID. An attempt nonce is a later option only if operational
experience demonstrates that fresh operation creation is too expensive; adding
retry machinery without that evidence would complicate forensic replay.

## Reorg and finality boundary

Ordinary reorg handling requires no special V2 protocol machinery. Preparation
and proof bind to chain ID; ratification recomputes canonical contract state;
`S.createdAtBlock` is the ratification block. Consumers apply their existing
confirmation/finality policy. A proof from an orphaned candidate observation
cannot ratify unless the canonical state still produces the same `C`.

## Availability revision mutation policy

The V2 reserve successor must advance `availabilityRevision` exactly once for
each successful reserve-controlled mutation that can change Shortfall
availability. The revision must not advance on reverted transactions.

| Mutation | Advances revision | Reason |
|---|---:|---|
| ordinary reservation create | yes | reduces allocable availability |
| ordinary reservation release/consume | yes | changes allocable accounting |
| prepared-operation registration | no | reserves zero capital |
| V2 ratification reservation | yes | establishes the authoritative allocation |
| allocator funding | yes | transfers the reserved capital out |
| allocator return/refund | yes | changes reserve custody |
| protected-capital update | yes | changes allocable availability |
| reserve-controlled withdrawal | yes | changes reserve custody |
| unsolicited ERC-20 deposit | no revision hook | `grossBalance` changes and invalidates `C` |
| reverted mutation | no | no committed state change |

The reference V2 token policy is standard, non-rebasing, non-fee-on-transfer
ERC-20 behavior. Rebasing, callback-driven balance changes, and tokens that can
withdraw reserve custody without a reserve-controlled call are unsupported until
a separate accounting adapter is specified.

## Ratification interface and failure contract

The V2 controller is the only orchestration entry point. It owns operation
lifecycle and frozen identity; Assurance V2 owns proof identity and one-shot
consumption; Reserve V2 owns live commitment comparison, authoritative
observation, custody, and allocator-facing funding; the allocator owns later
claim distribution.

The initial retry rule is one candidate economic commitment per prepared
operation ID. A stale commitment abandons the prepared operation and uses a new
operation ID; V2.0 has no attempt nonce.

The intended call order is:

```text
ControllerV2.ratifyAndFund
  -> require Prepared
  -> set ephemeral Ratifying state
  -> consume assurance for exact (operationId, P, C)
  -> ReserveV2.ratifyAndFund
  -> set Funded
```

`Ratifying` is never a persistent transaction-boundary state. Any revert leaves
the operation `Prepared`, leaves assurance unconsumed, creates no allocator
operation, moves no capital, and leaves no reserve reservation.

Reserve atomic ratification is the only V2 capital entry point. After `C' == C`
and before external calls it must advance `availabilityRevision` once, enter
non-reentrant ephemeral ratification accounting, and retain that one revision
increment on success. All temporary effects revert on failure. Successful V2
ratification/funding advances the revision exactly once.

| Failure | Assurance consumed after tx | Reservation after tx | Allocator operation | Capital moved | Controller state |
|---|---:|---:|---:|---:|---|
| stale commitment | no | no | no | no | Prepared |
| assurance mismatch/replay | no | no | no | no | Prepared |
| allocator create failure/malformed result | no | no | no | no | Prepared |
| allocator funding/token transfer failure | no | no | no | no | Prepared |
| rejected reentrancy | no | no | no | no | Prepared |
| success | yes | no persistent reservation | Funded | exact amount | Funded |

Before implementation, use configurable allocator, token, assurance, and
reentrancy mocks to assert every row. The reserve must be the allocator-facing
funding authority; no `ratify`/`fundLater` split is permitted.

## Next assurance milestone

Before introducing a V2 reserve or controller, extend the shared Rust/Solidity
golden vector into the V2 SP1 witness path. The fixture must
assert exact roots and 192-byte public values, plus mutation sensitivity for
operation ID, initiator, chain, reserve, token, allocator, configuration,
verifier/program identity, policy identity, gross balance, protected capital,
availability, and availability revision. Include an ABA pair with identical
economic values at two different revisions and require distinct commitments.

The resulting three-way check is:

```text
canonical semantic fixture
      ├── Rust core
      ├── V2 SP1 witness
      └── Solidity commitment/decoder
            ↓
      identical roots and public values
```

This milestone still has no ability to reserve or move capital.

## Explicitly deferred

This proposal does not authorize changes to:

- `OnchainShortfallReserveV1`;
- the V1 `reserveSnapshotHash` semantics;
- the current source-bound public-values schema;
- current availability economics;
- concurrent funded allocator semantics;
- the V1 bounded cancellation/expiry lifecycle.

Implementation should use a versioned successor contract, schemas, and proof
bindings after the economic commitment and ratification semantics are reviewed.
