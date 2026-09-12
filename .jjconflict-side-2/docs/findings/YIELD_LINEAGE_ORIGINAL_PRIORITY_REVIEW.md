# Yield Lineage-Original Priority Review

## Classification

- **Status:** `:review/open-assurance-gaps` (gap 5 resolved by M1 lineage verifier)
- **Kind:** custody-transition and queue-ordering assurance
- **Scope:** repeated partial-fill deferral of yield-backed positions

## Purpose

Repeated partial settlement must not silently refresh a claimant's deposit
priority. Otherwise a deferred claimant can be reordered behind newer deposits,
or a malformed successor can queue-jump competing positions.

This review treats two concepts separately:

| Concept | Meaning |
|---|---|
| `lineage-original` | Stable identity of the original yield-backed position from which deferred descendants derive. |
| `lineage-original-priority` | Immutable canonical priority assigned to that original position and inherited by every deferred descendant. |

Lineage establishes ancestry. It is not itself an ordering value unless the
position identifier is explicitly defined as a canonical ordering key.

## Current implementation evidence

The liquid-lending module already implements important parts of this contract:

- Deposits assign `:original-priority` from a per-`[module-id token]`
  `:yield/deposit-seq` counter. Lower values are older.
- A first deferred successor records an immutable
  `:deferred/original-position` reference (identity, queue domain, obligation,
  and priority). Its lineage root authenticates that reference, rather than a
  mutable base-position snapshot; it also records predecessor hash, parent
  identity, propagation identity, transition hash, and inherited priority.
- A later successor inherits the existing deferred lineage root and the prior
  deferred position's `:position/original-priority`; it does not receive a new
  deposit sequence.
- Deferred rounds increase monotonically and closed deferred records are
  write-once in `:deferred-position-history`. A successful `claim-deferred`
  closes and archives the active deferred record, preserving its origin/root,
  and rejects a reclaimed amount that differs from the lineage outstanding
  amount.
- Application preconditions reject a changed original priority before a
  propagation can be applied.

Relevant code and current focused tests:

- `src/resolver_sim/yield/modules/liquid_lending.clj`
- `src/resolver_sim/yield/invariants.clj`
- `test/resolver_sim/yield/deferred_class_test.clj`
- `docs/conceptual/settlement-lifecycle.md`, §3.6

## Required semantics

For every deferred successor:

1. The first child records the base/original position identity.
2. Later descendants preserve that original identity, rather than replacing it
   with their immediate parent as the origin.
3. Every descendant inherits the original priority unchanged.
4. Deferral sequence, event time, or application order cannot replace original
   priority.
5. Priority comparison is deterministic inside an explicitly defined queue
   domain—at minimum the same token and yield module, and the same liquidity
   pool where pools are distinct.
6. Equal primary priorities require a stable canonical tie-breaker.
7. A lineage is single-origin unless multi-origin merge semantics are explicitly
   represented and independently verified.

A preferred canonical ordering projection is:

```clojure
{:lineage/original-position-id ...
 :lineage/original-priority
 {:deposit-sequence ...
  :position-id ...}}
```

The representation may differ, but descendants must not supply a replacement
priority.

## Assurance contract

A lineage verifier should establish:

| Check | Required result |
|---|---|
| Origin presence | Every deferred position has a valid original reference. |
| Origin stability | All descendants in one lineage resolve to the same original. |
| Priority inheritance | Descendant priority equals the committed original priority. |
| No priority reset | Deferral sequence/time cannot replace original priority. |
| Predecessor continuity | Each child links to the transition and predecessor that created it. |
| Amount conservation | Original amount reconciles with settled, outstanding deferred, reversed, and written-down amounts. |
| Deterministic ordering | Replay yields the same service order for competing lineages. |
| No silent merging | Merges are forbidden, or multi-origin lineage is explicit and verified. |

Missing lineage fields must not pass by matching absence. As with execution
scope, `nil = nil` is not valid lineage continuity.

## Ownership boundary

| Layer | Responsibility |
|---|---|
| Yield/Sew transition layer | Creates deferred positions and assigns inherited lineage identity and priority. |
| PRF core | Replays lineage transitions and verifies inheritance, ordering, and conservation. |
| Benchmark pack | Contains projections and lineage witnesses; cannot assign priority or mutate live yield positions. |
| Review packet | Packages lineage evidence and verification results without redefining priority semantics. |

Lineage priority is evidence about a custody transition. It is not a new
held-custody index partition.

## Open assurance gaps

The current implementation/test evidence does **not yet demonstrate** all of
the following:

1. A competing older/newer position scenario across two partial-fill cycles,
   followed by renewed liquidity, proving that the older lineage is serviced
   first.
2. An explicit, position-identity-based tie-breaker contract and dedicated equal-priority regression. The canonical secondary ordering is documented at `secondary-position-id` / `compare-queue-entries` (liquid_lending.clj): equal primary priorities break by the stringified content-addressed lineage root (deferred) or lineage-origin hash (base) — deterministic and stable across rounds, not the raw owner id. The dedicated equal-priority regression (`equal-primary-priorities-use-deterministic-owner-tie-break`) pins the ordering.
3. Deterministic replay after shuffled input ordering.
4. Cross-pool positions being incomparable rather than globally ordered.
5. ~~Full amount conservation across multiple descendants, reversals, and write-downs~~ **Resolved by M1:** `:yield/withdrawal-lineage-conservation` proves Σ realized-fill across a lineage + terminal outstanding = original requested (round-chain continuity, no cumulative overfill, position/decision reconciliation), exercised by the `Y14_round-two-shared-liquidity` scenario and `lineage_conservation_test.clj`.
6. Explicit single-origin/multi-origin merge policy validation.

Status: **resolved in part.** Gap 2 is closed (canonical tie-breaker documented + regression pinned), gap 5 is closed by the lineage-conservation invariant, gap 3 is covered by the shuffled-round-two determinism test; the remaining gaps (1, 4, 6) stay open.

## Required regression coverage

Add a focused scenario:

```text
older position deposited
→ newer competing position deposited
→ older position partially filled
→ deferred child created
→ another partial-fill cycle
→ liquidity becomes available
→ older lineage retains original ordering
```

Also add tests for tampered priority, incorrect origin, missing lineage fields,
equal-primary-priority tie-breaking, a full fill without a deferred child,
multiple-descendant conservation, shuffled replay, and cross-pool
incomparability.

## Command and comparison integration

Do not add a `:yield-lineage` command include until a versioned lineage evidence
definition and validator exist. Once they do:

- command semantics declare whether lineage assurance is requested;
- `outcome-complete-for-command?` requires a lineage evidence root;
- `exact-claim-scope?` compares a lineage-policy/definition root;
- `identical-outcome?` separately compares realized lineage evidence.

The policy/definition root belongs to claim scope. The realized lineage
witness/root belongs to output completeness and outcome comparison.
