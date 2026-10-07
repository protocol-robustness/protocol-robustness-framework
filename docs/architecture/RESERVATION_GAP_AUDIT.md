# Reservation gap audit

Inspection date: 2026-10-06. Companion checkout: `/home/user/Code/prf-evm`, HEAD
`0ddc5c91ca51a5804d02c79f107c559b81bf4811`. Findings describe the source inspected
in this checkout; older implementation-status prose is not evidence of a present
entry point. The audit covers ReserveV2, its two funding controllers, prepared
operation invalidation, and the immediate allocator custody boundary. V1 is read
only to distinguish its existing expiry behavior. No runtime implementation was
changed.

## Findings

### R1 — Legacy prepared allocator and chain are not enforced at execution

**Concrete correctness gap; close before treating the legacy source proof as a
commitment to the execution deployment.**

Legacy `prepareOperation` hashes the declared chain/reserve/token/allocator but
stores only its configuration and residual-recipient fields for execution
checks. It does not compare the declared chain with `block.chainid`.
The ratification controller checks deployment addresses only when
`successorEntitlementProfile(operationId)` is true.

The economic reserve commitment includes the prepared root and the actual
reserve/token, but does not independently include the actual allocator or live
chain. Consequently, binding an opaque prepared root does not enforce those
declared fields against the execution environment.

Two isolated reproductions succeeded:

- Declare allocator `0xBAD`, use the actual reserve and token, and construct a
  matching live reserve commitment: 90 is funded to the actual allocator.
- Declare `block.chainid + 1`, with the actual reserve/token/allocator and a
  matching live commitment: 90 is funded on the current chain.

The admission seam is a mock. The harness additionally recomputes the reserve
commitment from `ShortfallV2Commitments.EconomicReserveState` and requires it to
equal the live commitment. This demonstrates missing execution checks; it does
not claim an independently generated proof or a cross-chain replay attack.

Sources: [legacy preparation](/home/user/Code/prf-evm/src/applications/shortfall/SourceBoundProRataControllerV2.sol:85),
[conditional deployment checks](/home/user/Code/prf-evm/src/applications/shortfall/SourceBoundProRataRatificationControllerV2.sol:93),
[reserve commitment](/home/user/Code/prf-evm/src/applications/shortfall/OnchainShortfallReserveV2.sol:372).

The narrow correction is to validate the legacy chain, retain its decoded
deployment addresses, and enforce them for the legacy route as well. This can
preserve the current preimages and domains. No correction was applied during
this audit.

### R2 — Ordinary holds depend indefinitely on the application authority

**Liveness limit; expiry policy is absent.** Ordinary reservations retain only
state and amount. `None -> Active -> Released` preserves the amount and burns
the identity permanently, but there is no deadline, permissionless expiry,
authority recovery, or rotation. A reservation of all 90 available units was
still active after advancing time by ten years; a different caller could not
release it. An unavailable authority can therefore leave availability at zero
indefinitely.

These ordinary holds also have no settlement/funding transition keyed by
reservation ID. Both Shortfall funding routes spend the *unreserved* balance.
An ordinary hold is an exclusion marker, not an executable payment reservation.
Releasing it before a separate payment relinquishes exclusivity.

Prepared V2 operations hold zero capital, and Shortfall ratification uses an
ephemeral reservation inside one atomic transaction, so neither requires a
between-transaction reservation-expiry mechanism. V1's permissionless expiry
applies to its locked Shortfall operations, not ordinary reservations.

Sources: [ordinary holds](/home/user/Code/prf-evm/src/applications/shortfall/OnchainShortfallReserveV2.sol:140),
[V1 Shortfall expiry](/home/user/Code/prf-evm/src/applications/shortfall/OnchainShortfallReserveV1.sol:295).

If authority-managed indefinite holds are intended, document that liveness
dependency. If automatic recovery or settlement of the reserved obligation is
required, those semantics need a deliberate extension; no deadline or settlement
policy was invented here.

### R3 — Funding leaves persistent allocator custody without a refund route

**Concrete gap at the reservation-to-custody boundary against the NOW record.**
Both funding routes clear the ephemeral reserve hold and leave the allocator
`Funded`. The current allocator has activation for successor entitlements but
no cancel/refund, withdrawal, rescue, or closure entry point. Releasing an
ordinary reservation cannot recover capital already moved to the allocator.

This contradicts the NOW retry rule that persistent pre-activation `Funded`
custody must support cancel-and-refund, or that funding and activation should
commit atomically. It is a downstream liveness blocker, not a failure to clear
`totalReserved`. Previously described cancellation code is absent from this
inspected checkout.

Sources: [legacy funding](/home/user/Code/prf-evm/src/applications/pro-rata/ProductionProRataAllocatorV2.sol:75),
[successor funding and activation](/home/user/Code/prf-evm/src/applications/pro-rata/ProductionProRataAllocatorV2.sol:134),
[NOW cutpoints](PROTECTED_CAPITAL_PRO_RATA_V2_SEMANTICS.md#irreversible-cutpoints-and-retry-rules).
No lifecycle implementation was added.

## Six requested checks

| Area | Result |
|---|---|
| Expiry / liveness | R2 for persistent ordinary holds; R3 after funding. Prepared operations reserve zero capital; ephemeral ratification rolls back on failure. |
| Explicit invalidation | Ordinary release is terminal and increments revision. Both controllers recheck `Prepared`, then finalize it atomically with funding; abandonment now prevents funding. No independent reserve-wide invalidation/bump command exists. |
| Commitment scope | Domain, prepared root, actual reserve/token, gross, reserved, protected, available and revision are committed. Reserve/release ABA is rejected. R1 remains on the legacy route; successor preparation checks chain and its controller checks actual deployment addresses. |
| Every capital outflow | Exactly two reserve outflow paths: `ratifyAndFund` and `ratifyAndFundEntitlement`. They transfer only current availability to the immutable allocator, require exact sender/receiver deltas and zero remaining allowance, and revert atomically on failure. Ordinary create/release, preparation and activation do not transfer reserve custody. No other reserve transfer, sweep, arbitrary call, or approval surface was found. |
| Token assumptions | Requires a trusted, truthful ERC-20 with exact transfers, no rebasing or callback-driven balance changes. Delta checks reject false/short transfers; they cannot certify token honesty or prevent a later external rebase/burn. V2 `deposit` lacks V1's reentrancy modifier; no exploit under the supported-token assumptions was established. |
| Stateful invariants | Temporary sequence fuzzing passed for supported-token operations. Existing checked-in reserve tests are example tests, with no reservation invariant handler or `testFuzz` sequence. Bootstrap deposits can leave the protected floor unfunded; allocable-state invariants must distinguish that state. |

Explicit invalidation is scoped. `abandonPreparedOperation` is authoritative
through the configured controllers; ReserveV2 treats the prepared root as opaque
and initially trusts its application authority directly. Production controller
binding is therefore a required deployment condition. Abandonment does not bump
the economic revision because it holds zero capital and affects one operation.

There is no zero-capital reserve-wide invalidation command. Reserve/release can
advance revision, but needs positive availability, consumes an ID, and emits
real reservation effects. A dedicated epoch/bump is a missing capability only
if the application requires administrative invalidation without an economic
change; it is not needed to make the current reserve/release ABA protection work.

Deposits and direct token donations change gross balance and therefore the
commitment, although deposits do not increment revision. Under the supported
token model, all contract-controlled decreases go through funding and increment
revision, so a later restored balance cannot revive an earlier funded-state
commitment. A token capable of external burns/rebases defeats that assumption.

## Invariants and evidence

At stable transaction boundaries in an allocable reserve:

```text
totalReserved = sum(amount of Active ordinary reservations)
gross >= totalReserved + protectedCapital
available = gross - totalReserved - protectedCapital
allocator allowance = 0
ratifying = false
released reservation IDs remain terminal
```

During funding, `totalReserved` additionally includes the ephemeral amount; the
sum invariant is a transaction-boundary invariant. A partially funded bootstrap
can have `gross < protectedCapital`: `deposit(5)` with a floor of 10 succeeds,
while `economicState` and new allocations fail closed. The NOW phrase "every
successful reserve transition" is too broad if it includes those bootstrap
deposits. This is a specification qualification, not a demonstrated outflow.

The temporary model interleaved creation, release, deposits, both funding routes
and over-reservation rejection. After each action it checked all eight modelled
reservation states and retained amounts, active sum, floor, custody totals,
allowance, revision, snapshot counter and cleared ratification flag. It ran 256
fuzz cases with up to 64 actions each. This is bounded evidence with a test token,
not a proof about arbitrary token implementations or malicious allocator code.

Command and result:

```text
forge test --root /tmp/prf-reservation-audit-2bjfgnhr/build-root \
  --match-contract ReservationGapAudit --offline -vv
10 passed; 0 failed; 0 skipped
```

The tests that reproduce R1 pass when the incorrect deployment is accepted;
their green status records the gap and is not a production conformance result.
The same suite confirms ordinary-hold persistence, abandonment rejection,
bootstrap behavior, both funding routes preserving ordinary holds and the floor,
deposit freshness, and revision protection after reservation/release.

Retained local evidence:

- [Audit tests](/tmp/prf-reservation-audit-2bjfgnhr/build-root/test/ReservationGapAudit.t.sol)
- [Copied-source fingerprints](/tmp/prf-reservation-audit-2bjfgnhr/build-root/source-hashes.json)
- [Run log](/tmp/prf-reservation-gap-audit-20261006.log)

The temporary compilation uses exact copied companion sources with a minimal
ERC-20 interface and test token. Real proof admission, arbitrary-token testing,
and a persistent invariant runner were not added. No Clojure source, registry,
canonical identity, Solidity implementation, SP1 program or public values changed.
