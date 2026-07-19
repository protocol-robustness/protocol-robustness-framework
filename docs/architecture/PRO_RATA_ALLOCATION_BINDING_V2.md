# Pro-rata allocation binding v2

## Purpose and scope

`resolver-sim.pro-rata.*` is a domain-neutral deterministic integer allocation
mechanism. It allocates a constrained quantity across weighted, capped rows and
persists mathematical witnesses for independent validation.

It is **not** a generic settlement, propagation, accounting, or state-transition
framework. Domain code selects accounts, interprets shortfall, owns lifecycle
semantics, and applies authoritative state changes.

```text
generic allocation
→ domain evidence adapter
→ domain decision / propagation
→ domain application and accounting
→ authoritative domain state
```

This document describes the allocation-bound shared-withdrawal contract used by
new canonical evidence. It also records the distinct, presentation-only SEW
pro-rata slash adapter boundary.

## Mechanism request and result

The public allocator is:

```clojure
(resolver-sim.pro-rata.allocation/allocate request)
```

The supported request/result schemas are:

```text
pro-rata-allocation-request.v1
pro-rata-allocation-result.v1
```

A result identifies the mechanism explicitly:

```clojure
{:mechanism {:id :mechanism/pro-rata-allocation
             :version 1}
 :allocation/id ...
 :allocation/hash ...}
```

Rows have a unique allocation `:row/id`. Repeated participants, obligations, or
economically equal rows remain valid when their row identities differ. The
mechanism canonically orders rows by row ID; domain presentation order is not a
mechanism input or guarantee.

## Capped redistribution and witnesses

For `:redistribute-cap-excess`, allocation follows active-set semantics:

```text
calculate quota over active rows
→ commit cap-constrained rows
→ remove committed rows
→ recompute availability and active weights
→ perform integer rounding only for the final active group
```

The persisted allocation result includes, as applicable:

```text
:declared-cap
:effective-cap
:initial-quota
:effective-quota
:floor-allocation
:fractional-remainder
:remainder-rank
:remainder-unit-awarded?
:allocation/rounds
:allocated
:unmet
:allocated-total
:unallocated-residual
:allocation/hash
```

Quotas and fractional remainders use integer numerator/denominator witnesses.
No floating-point evidence is needed for quota or remainder comparison.

The allocation hash commits to the complete normative result, including rows,
witnesses, active-set rounds, policy fields, totals, and residual metadata.

The former capped redistribution implementation remains private historical
diagnostic infrastructure. It is not an alternative supported implementation:
corrected active-set allocation intentionally differs from it for some capped
fixtures.

## Mechanism evidence envelope

A domain adapter can construct a complete hash-bound envelope:

```text
pro-rata-mechanism-evidence.v1
```

The envelope contains:

```clojure
{:evidence/id ...
 :evidence/hash ...
 :mechanism {:id :mechanism/pro-rata-allocation :version 1}
 :mechanism/result <complete pro-rata-allocation-result.v1>
 :mechanism/result-hash <allocation hash>
 :mechanism/validation-results [...]}
```

` :mechanism/validation-results` contains envelope-local structural validation
summaries, including allocation-hash integrity, cap compliance, quota bounds,
round-trace coherence, and canonical remainder assignment where exercised.
These summaries are **not** claim-engine result artifacts, independently
persisted claim records, package-registered claim evidence, or evidence-DAG
nodes.

The mechanism envelope is currently a **hash-bound domain-carried witness**. It
is not yet an independently registered package or evidence-DAG node.

## Shared-withdrawal authority chain

For a canonical pro-rata shared withdrawal, the authoritative chain is:

```text
canonical withdrawal rows
→ pro-rata allocation result and mechanism envelope
→ partial-fill decision
→ pro-rata propagation v2
→ application v2
→ policy-owned accounting entries and authoritative state
```

### Decision authority

The partial-fill decision is the current authoritative holder of the complete
mechanism envelope. It carries allocation rows and the complete mechanism result
needed to explain the domain translation.

The decision is hash-identified by:

```text
:decision/id
:decision/hash
```

### Propagation v2

`pro-rata-propagation.v2` binds its source decision and allocation through an
exact reference containing:

```text
allocation ID and hash
mechanism ID and version
mechanism-evidence ID and hash
source decision ID and hash
```

The propagation builder derives this reference from the validated source
decision; it does not accept an unrelated caller-supplied allocation reference.

The binding validator proves a row-level translation using stable obligation,
participant, and source-position identity. It rejects missing, extra, duplicate,
or ownership-swapped rows, fulfilled/deferred mismatches, and aggregate
translation mismatches.

### Historical propagation v1

`pro-rata-propagation.v1` remains readable under its historical assurance
contract. It does not contain an allocation binding, so v2 allocation-binding
claims do not retroactively apply to it.

### Application v2

Application v2 does not duplicate the complete allocation reference. Instead it
commits to the exact propagation ID/hash:

```text
application
→ propagation ID/hash
→ decision ID/hash
→ allocation ID/hash
→ complete mechanism result
```

This transitive binding avoids multiple copies of the same allocation authority.

### Accounting ownership

Accounting remains domain-owned. It verifies that the committed propagation
participants and committed shared-withdrawal policy produce the expected source
debit, participant withdrawal credits, deferred entitlement state, and final
balances.

Accounting does not bypass propagation by independently interpreting raw
mechanism allocation rows. This preserves layer authority:

```text
allocation validates mathematics
propagation validates domain translation
application validates committed state change
accounting validates financial implementation of committed propagation policy
```

## SEW pro-rata slash adapter

SEW slash allocation consumes the same public allocation API:

```text
liable parties
→ generic canonical mechanism rows
→ pro-rata allocation result/envelope
→ historical SEW presentation allocation
```

Canonical mechanism row order can differ from SEW's historical liable-party
presentation order. The adapter preserves resolver-to-allocation ownership while
retaining the complete envelope/reference.

Legacy SEW projection artifacts and claims remain projection-scoped. They do not
prove the complete mechanism witness properties unless that witness envelope is
present. In particular, do not infer cap, quota, canonical remainder, or
round-trace validation from legacy projection evidence alone.

SEW currently demonstrates deterministic pro-rata slash allocation and evidence
translation. It does **not** implement authoritative SEW slash propagation,
stake debits, slash destination credits, unmet slash lifecycle state, or
state mutation.

## Explicit deferrals

The following are intentionally outside this contract:

- standalone mechanism evidence nodes;
- mechanism claim-result evidence nodes;
- package-index or evidence-DAG integration for standalone mechanism evidence;
- operational later-liquidity / round-two shared-withdrawal execution;
- successor deferred-position creation and multi-round lifecycle application;
- authoritative SEW slash propagation, stake debits, and account mutation;
- mechanism registry or runtime mechanism selection;
- principal-first or waterfall extraction;
- generic settlement, propagation, accounting, or deferred-position abstractions;
- dynamic extension or plugin loading.

These omissions must not be inferred as supported from the presence of a
mechanism envelope or a successful first-round shared-withdrawal application.
