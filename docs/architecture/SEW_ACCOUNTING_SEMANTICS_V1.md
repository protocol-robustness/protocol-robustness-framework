# Sew Accounting Semantics V1

## Status and scope

This record defines the target accounting semantics for Sew's corrected
conservation invariant and the future canonical accounting projection. It is
the authority for the meanings of accounting buckets introduced by that work.

It does not change the current runtime world shape, `:funds-ledger-view`, held
custody ledger, settlement behavior, or solvency results. In particular,
existing `:total-released` and `:total-refunded` counters are historical
allocation counters; they are not, without a separately specified mapping,
proof that value has left protocol custody.

This record is limited to model accounting. It neither proves an on-chain
balance nor specifies external asset valuation.

## Three distinct assertions

The following assertions must remain separate.

| Assertion | Question | Required evidence |
|---|---|---|
| Conservation / allocation accounting | Did modeled value movements preserve each asset? | Complete accounting history and an internally reconciled accounting state |
| Custody reconciliation | Does represented protocol-controlled custody equal internally represented custody? | Accounting state plus a custody observation where the boundary requires one |
| Solvency | Are recoverable assets sufficient for outstanding liabilities? | Liability state, recoverable asset state, and the applicable recovery/valuation policy |

Passing one assertion does not imply another. A complete, conserving ledger can
be insolvent; an externally funded balance can conceal an internally
inconsistent accounting history.

## Per-asset conservation identity

For each canonical asset `a`, conservation is:

```text
cumulative-external-inflows[a]
  = cumulative-external-outflows[a]
  + current-internally-held[a]
```

This identity is evaluated per asset. Amounts from different assets must never
net, offset, or be converted by this invariant. Amounts use raw integer asset
units; display decimals and exchange-rate valuation are outside this identity.

`cumulative-external-inflows` contains value entering the modeled protocol
boundary. `cumulative-external-outflows` contains only value that has actually
crossed out of that boundary. Moving value among internal buckets is not an
external flow.

## Current internally held buckets

`current-internally-held[a]` is the sum of explicit, mutually exclusive
custody buckets for asset `a`:

| Bucket | Meaning |
|---|---|
| `:active-principal` | Escrow principal still held for an unsettled or otherwise live obligation. |
| `:release-claimable` | Value allocated to a release recipient and still held pending withdrawal. |
| `:refund-claimable` | Value allocated to a refund recipient and still held pending withdrawal. |
| `:accrued-protocol-fees` | Earned protocol fees that remain in protocol custody pending fee withdrawal or another authorized disposition. |
| `:bond-obligations` | Appeal, challenge, or other modeled bond value that remains held pending its terminal disposition. |
| `:yield-custody` | Modeled yield or deferred-yield value remaining in custody. |
| `:explicit-reserves` | A policy-defined reserve held in custody with an explicit reason and disposition rule. |
| `:other-modeled-custody` | A closed, versioned extension bucket with explicit inclusion and disposition semantics. |

The future projection must reject an unknown bucket rather than silently omit
it. `:other-modeled-custody` is not a catch-all for unclassified state: every
member requires an explicitly registered subtype or reason.

The buckets are accounting classifications, not recipient ownership claims and
not necessarily economic liabilities. Their total is the current modelled
custody stock used by the conservation identity.

## External outflow buckets

External outflows are historical, cumulative, and disjoint:

| Bucket | Meaning |
|---|---|
| `:release-withdrawals` | Release claimables actually transferred out of protocol custody. |
| `:refund-withdrawals` | Refund claimables actually transferred out of protocol custody. |
| `:fee-withdrawals` | Accrued protocol fees actually transferred out of protocol custody. |
| `:authorized-other-outflows` | An explicitly authorized, policy-classified transfer out of custody not covered above. |

An allocation or a claimable creation is not an external outflow. It only
changes the classification of current internally held value.

## Required transition meanings

The following state transitions preserve the identity without changing its
left-hand side:

```text
external funding       -> active-principal              (external inflow)
active-principal       -> release-claimable             (internal reclassification)
active-principal       -> refund-claimable              (internal reclassification)
active-principal       -> accrued-protocol-fees         (internal reclassification)
release-claimable      -> release-withdrawals           (external outflow)
refund-claimable       -> refund-withdrawals            (external outflow)
accrued-protocol-fees  -> fee-withdrawals               (external outflow)
```

The labels describe accounting semantics, not a requirement that the current
implementation immediately materialize a second mutable ledger. Later work may
derive these effects from authoritative held adjustments and settlement
transitions.

## Counter interpretation

The terms `released`, `refunded`, and `withdrawn` must not be used
interchangeably:

| Term | Meaning under this record |
|---|---|
| allocated | A terminal decision assigned value to a beneficiary or sender. No custody conclusion follows. |
| claimable | The assigned value remains within protocol custody and is available for a pull withdrawal. |
| withdrawn | The value crossed the protocol custody boundary and is an external outflow. |

Until each existing counter is mapped to one of these meanings by the canonical
projection work, it must not be used as a term in the V1 conservation identity.

## Completeness requirement

A conservation pass requires both a complete accounting boundary and a balanced
identity. Partial, imported, malformed, or selectively supplied history is not
positive assurance. It must produce `:not-evaluated` or a failure classification,
never a pass.

Completeness must eventually identify the covered history and missing sources in
committed accounting data. The existing held-adjustment completeness declaration
is relevant custody evidence but is not alone sufficient to establish complete
Sew accounting, because claimable, fee, yield, and other in-scope sources also
need coverage.

## Boundaries retained for later work

This record intentionally does not yet define canonical asset identity,
projection/root shape, cutpoint binding, asset observation provenance, valuation,
or proof programs. Those follow this semantic boundary; they must use these
bucket meanings rather than reinterpreting legacy counters.
