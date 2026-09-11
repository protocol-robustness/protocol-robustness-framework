# Use of Funds — Accounting View

This document defines the canonical **use-of-funds** framing for the Protocol Robustness Framework implementation and its reusable accounting shape.

## Purpose

Provide a clear, read-only answer to:

- where protocol value currently sits,
- how value moved,
- whether conservation currently holds,
- what drift remains (if any).

## Core buckets

Per token:

- `held`: currently custodied in protocol accounting
- `released`: cumulatively finalized to recipient side
- `refunded`: cumulatively finalized to sender side
- `withdrawn`: cumulatively withdrawn via claimable withdrawals
- `bond-posted`: cumulative posted appeal/challenge bonds
- `bond-slashed`: cumulative slashed bond amount

Global custody overlays:

- `claimable-total`
- `bond-locked-total`
- `bond-fees-total`
- `bond-distribution-total`
- `retained-slash-reserves`

## Conservation identity (token level)

For each token, the invariant expectation is:

`held + released + refunded = escrow-deposited + bond-deposited`

Where:

- `escrow-deposited` is the sum of escrow `amount-after-fee` for that token,
- `bond-deposited` is cumulative posted bonds for that token.

## Conservation identity (system level)

At system level, conservation is checked by summing the token equations:

`Σ(held + released + refunded) = Σ(escrow-deposited + bond-deposited)`

This is the primary invariant used to derive drift.

## Inflow / outflow interpretation

- Inflows (model accounting):
  - escrow `amount-after-fee` deposits
  - posted appeal/challenge bonds
- Outflow-like buckets (still accounted in-system):
  - released
  - refunded
  - withdrawn (claimable withdrawals)

`withdrawn` is a custody movement metric for analysis and does not replace
the core conservation equation above.

## Exclusions and assumptions

- External chain balances are not used in this projection.
- Off-model token behaviors beyond represented scenario semantics are excluded.
- Protocol-specific bucket semantics (especially bond lifecycle details) remain
  adapter-specific; only the output contract shape is reusable.

## Drift

Drift is reported as:

- `drift-by-token[token] = accounted - deposited`
- `drift-total = Σ drift-by-token`

Interpretation:

- `0`: fully reconciled under current model accounting
- non-zero: explicit mismatch requiring investigation

## Read-only projection contract

Sew exposes this via `io-projection` target:

- `:funds-ledger-view`

The projection is read-only and intended for analysis/reporting. It does not mutate session or world state.

## Contract-level vs computation-level generalisation

- **Output-contract level:** high generalisation intent (adapter-facing capability).
- **Computation/producer level:** currently Sew-specific.

In other words, `:funds-ledger-view` is treated as framework-facing contract
surface, while current production of that view is owned by the Sew reference
adapter.

## Quick check: "funds-lost = 0"

For the canonical projection, treat **funds-lost = 0** as:

- `conservation.holds? == true`, and
- `conservation.drift-total == 0`

### Fast path in runtime/session summaries

`resolver-sim.server.session/session-summary` exposes:

- `:funds-conservation-holds?`
- `:funds-drift-total`

So a quick operator check is:

- `:funds-conservation-holds?` is `true`
- `:funds-drift-total` is `0`

### Fast path from projection output

When inspecting `:funds-ledger-view` directly:

- `[:conservation :holds?]`
- `[:conservation :drift-total]`

If `holds?` is true and `drift-total` is 0, accounting shows no modeled fund loss.
