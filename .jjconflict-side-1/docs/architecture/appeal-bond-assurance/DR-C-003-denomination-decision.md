# Layer C candidate — DR-C-003 refund denomination

**Status:** resolved — denomination fixture error. Layer A may proceed after regenerating this scenario evidence.

## Observed input and policy

`DR-C-003` declares:

```text
escrow amount      = 5,000
resolver fee       = 150 bps
appeal bond policy = 500 bps
expected refund    = 1,000,000
```

The ordinary escalation policy computes from `amount-after-fee`:

```text
amount-after-fee = 5,000 - 75 = 4,925
appeal bond      = floor(4,925 * 500 / 10,000) = 246
```

There is no declared decimal conversion or absolute `appeal-bond-amount` that
explains `1,000,000` in the scenario.

## Required decision

One of the following must be evidenced explicitly:

1. **Denomination fixture error.** Preserve the stated bps policy and correct
   the expected value after documenting the integer unit convention.
2. **Absolute-bond policy.** Retain the one-million expected refund and set the
   posting-time policy to an explicit absolute appeal-bond amount.
3. **Historical gross/net divergence.** If the old implementation refunded a
   different amount, record old and candidate accounting equations, affected
   invariant, supported migration scope, and a replacement scenario.

Decision: retain the stated bps policy and use the modeled integer-unit result, `246`, as the expected refund. The scenario expectation has been updated accordingly. The canonical
BondLedger accounting equation remains:

```text
posted amount = posting fee + custodied amount
terminal allocations = custodied amount
```


## Legacy metric adapter note

`DR-C-003` also declares `bond_distributed = true` while its substantive
requirement is a refund.  The PRF adapter therefore defines this legacy metric
as **a terminal bond disposition exists**.  It is not an alias for the
primitive disposition kind `:distribution`; the normalized projection retains
`:refund`, `:distribution`, and `:forfeiture` as distinct facts.
