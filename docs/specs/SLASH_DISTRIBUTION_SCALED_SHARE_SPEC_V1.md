# Slash distribution scaled-share specification v1

## Purpose and scope

`resolver-sim.economics.slash-distribution` is the implementation-independent
slash-to-allocation distribution engine. This document pins down the canonical
meaning of its `:rate-of-gross` award mechanism so that replay and forensic
verification can reproduce a distribution exactly, without relying on
interpretation of a loosely defined "rate" parameter.

Scope: the scaled-share arithmetic, the evidence contract bound to it, and the
conservation boundary of the engine. This document does **not** define refund,
custody, net-distributable, or liability-ordering concepts; those belong to
protocol adapters above this layer.

## Canonical meaning of `:rate-of-gross`

A rate-derived award amount is a **scaled, non-negative proportion of the
gross amount**:

```
amount = floor(gross-amount × rate / scale)
```

- `gross-amount` — the slash amount fed into the distribution. At this layer
  the calculation base is exactly `gross-amount`.
- `rate` — the resolved value of the award's `:parameter-key` from the
  parameter context.
- `scale` — an explicit, **policy-level** constant. It is not assumed to be
  10,000; each award may declare its own scale (see "Scale model").
- Rounding is integer **floor** (`:floor`).

### Admitted domain

Valid rates satisfy:

```
0 ≤ rate ≤ scale
scale > 0
```

Rates below zero and above scale are rejected by the engine with
`:violation/invalid-parameter-value` and `:violation/rate-out-of-range`
respectively. The rounding mode is restricted to `:floor`.

## Scale model

The scale is a policy attribute of each award amount spec, e.g.:

```clojure
{:method        :rate-of-gross
 :parameter-key :test.parameter/reward-rate
 :scale         10000
 :rounding      :floor}
```

Two policies may therefore use different scales for semantically similar
rates: `500/10000` and `50/100` yield the same amount for the same gross
amount. Both `rate` and `scale` are committed; comparison and hashing use the
exact committed pair, not a normalized fraction.

No fixed basis-point model is imposed at this layer. If the protocol surface
wants basis points, that is expressed as a policy choice (`:scale 10000`), not
as an engine assumption.

## Public primitive

`calculate-scaled-share` is the exact arithmetic primitive and the single
owner of the floor-division semantics:

```clojure
(calculate-scaled-share {:gross-amount 1000 :rate 500 :scale 10000 :rounding :floor})
;; => {:gross-amount 1000
;;     :rate 500
;;     :scale 10000
;;     :rounding :floor
;;     :numerator 500000
;;     :amount 50
;;     :rounding-remainder 0
;;     :classification :positive-award}
```

It returns `nil` outside the admitted domain; the engine reports specific
violations instead of classifying.

### Outcome classification

| Classification | Condition |
|---|---|
| `:zero-rate` | `rate = 0` |
| `:rounded-to-zero` | `rate > 0` but `amount = 0` |
| `:positive-award` | `0 < amount < gross-amount` |
| `:full-gross-award` | `amount = gross-amount` (i.e. `rate = scale`) |

`:zero-rate` and `:rounded-to-zero` both produce a zero transfer but are
semantically distinct: only the latter represents positive entitlement lost to
rounding. The engine preserves this distinction in its evidence (below).

### Integer-width note

`calculate-scaled-share` uses Clojure's unbounded integer arithmetic and does
not itself assert Solidity-equivalent checked-width semantics. Solidity parity
is a separate, named follow-up: a checked-width profile or a
`mulDiv`-equivalent primitive with boundary vectors near the target integer
maximum. This spec does not silently impose a width.

## Evidence contract

The distribution artifact commits the following:

- **Parameter provenance** — the full resolved parameter context
  (`:source-root` + `:values`) is committed at distribution level via
  `:distribution/parameter-context` and covered by `:distribution/hash`.
- **Per-award calculation binding** — each rate-derived award entry embeds a
  `:calculation` record with `:parameter-key`, `:parameter-value`, `:scale`,
  `:rounding`, `:gross-amount`, `:numerator`, `:amount`,
  `:rounding-remainder`, and `:calculation-classification`, enabling local
  recomputation of a single award without re-reading the policy.
- **Zero-outcome trace** — `:distribution/calculations` records **every**
  rate-derived calculation, including zero-rate and rounded-to-zero records
  that produce no transfer. Zero outcomes are therefore auditable without
  manufacturing zero-value transfers.
- **Aggregate summary** — `:distribution/summary` is derived exclusively from
  `:distribution/calculations`:

  ```
  :rate-derived-award-count
  :positive-rate-derived-award-count
  :zero-rate-count
  :rounded-to-zero-count
  :full-gross-award-count
  :total-rate-derived-award-amount
  :total-rounding-remainder
  :amount-by-parameter-key
  ```

  The effective aggregate rate is the derived ratio
  `total-rate-derived-award-amount / total eligible base`, not an average of
  individual rates.

Both `:distribution/calculations` and `:distribution/summary` are part of the
`:distribution/hash` projection.

## Conservation boundary

The engine performs a **complete** allocation: it has no residual or retained
category. The committed distribution equation is therefore

```
sum(final allocations) = gross-amount
```

with every allocation identity reconcilable as
`final = base − deductions + settlements`. Conservation violations expose the
involved categories — `:base-total`, `:deduction-total`, `:settlement-total`,
`:award-total`, and `:retained` (always `0` here) — so the equation's terms are
visible in verifier output.

## Verification

`verify-distribution` independently recomputes the artifact:

- consistency mode (artifact only): hash, ordering, base conservation, funding
  sums, source capacity, per-award calculation binding vs. the committed
  trace, and summary-vs-trace consistency;
- recomputation mode (policy + parameter context supplied): every stored value
  — including zero-outcome calculation records — is recomputed exactly and
  compared.

An auditor can therefore answer, for any committed reward: which parameter
determined it, which scale was active, whether the parameter was active for the
event, and whether the numeric amount recomputes exactly.

## Test coverage

`test/resolver_sim/economics/slash_distribution_test.clj` sections 20–22 cover
the primitive domain (zero rate, rounded-to-zero, full gross, remainder,
equivalent ratios, values beyond `Long/MAX_VALUE`), evidence binding,
zero-outcome classification, summary aggregation, and conservation-category
exposure.
