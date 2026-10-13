# Pro-Rata Evaluation API

## Purpose

The pro-rata API separates deterministic economic evaluation from evidence and persistence. This gives notebooks, benchmarks, protocol adapters, and tests a reusable interface without coupling them to artifact directories, registries, evidence policies, or runner state.

## API layers

| Layer | API | Effects | Responsibility |
|---|---|---|---|
| Mathematical primitive | `allocate-pro-rata` | None, except an optional observer | One weighted allocation pass |
| Redistribution primitive | `allocate-pro-rata-with-redistribution` | None, except an optional observer | Capped multi-pass allocation |
| Canonical evaluation | `evaluate-pro-rata-allocation` | None, except an optional observer | Projection, allocation, validation, and in-memory result package |
| Evidence construction | `build-pro-rata-evidence-node` | None | Build an evidence node from explicit execution context |
| PRF execution and persistence | `execute-pro-rata-allocation!` | Filesystem / registries / evidence DAG | Persist and register a verified execution |

The first three APIs are implemented in `resolver-sim.economics.payoffs`. The final two describe the intended PRF orchestration boundary; persistence must not become an optional branch of the pure evaluator.

## Canonical evaluator

```clojure
(require '[resolver-sim.economics.payoffs :as payoffs])

(payoffs/evaluate-pro-rata-allocation
 {:schema-version 1
  :allocation/id :allocation/example-1
  :amount 100
  :unit {:asset :test-token :decimals 0}
  :participants [{:id :a :weight 40}
                 {:id :b :weight 60}]
  :policy {:rounding :floor-with-largest-remainder
           :cap-treatment :unallocated
           :tie-break :input-order}})
```

`evaluate-pro-rata-allocation` is deterministic for identical semantic input. It:

1. builds the registered pro-rata projection artifact with the full executable policy;
2. allocates from that validated projection, dispatching direct or redistribution allocation from `:cap-treatment`;
3. evaluates allocation checks;
4. returns content-addressed, in-memory projection and result packages.

It does **not** write files, register artifacts, emit evidence nodes, or require world/action provenance.

### Request fields

| Field | Required | Meaning |
|---|---:|---|
| `:schema-version` | No | Canonical request version; defaults to `1` |
| `:allocation/id` | No | Caller-visible allocation identity; included in the evaluation result |
| `:amount` | Yes | Integer amount to allocate |
| `:participants` | Yes | Vector of normalized `{:id ... :weight ... :cap ...}` data |
| `:policy` | No | `:algorithm`, `:rounding`, `:cap-treatment`, and `:tie-break` |
| `:use-case` | No | Caller-declared domain label committed through projection source data; this is not the registered artifact intent |
| `:unit` | Yes | Allocation unit committed directly into projection constraints |
| `:source`, `:metadata` | No | Canonical projection context |
| `:on-progress`, `:progress-atom` | No | Runtime-only observer configuration |

The canonical evaluator rejects function values. `:id-fn`, `:weight-fn`, and `:cap-fn` remain available only on the low-level allocation primitives.

### Result shape

```clojure
{:allocation/id :allocation/example-1
 :projection {:artifact/type :pro-rata/projection
              :artifact/hash "..."
              :artifact/value {...}}
 :allocation {:allocations [...]
              :total-requested 100
              :total-allocated 100
              :total-unmet 0
              :remainder 0
              :policy {...}}
 :validation {:status :passed
              :checks [...]}
 :result {:artifact/type :pro-rata/allocation-result
          :artifact/hash "..."
          :artifact/value {...}}}
```

The `:result` package is an in-memory evaluation artifact. It is distinct from `build-pro-rata-allocation-result-artifact`, which is a provenance-bearing PRF artifact and requires world/action context.

## Validation semantics

The evaluator keeps calculation and validation separate:

- Invalid request shape or unsupported policy throws before allocation.
- A calculated allocation is always returned when calculation succeeds.
- `:validation/status` is `:passed` unless a check reports `:failed`.
- Failed validation describes a calculated result that violates a declared property; it does not silently repair that result.

Current checks cover conservation, allocation bounds, allocation completeness, and deterministic replay. The replay check is a same-process repeat-execution guard, not an independent rounding proof. Exact quota-based weight proportionality is not yet implemented and is reported as `:not-evaluated` rather than passed. Validation therefore separates check correctness from coverage: it may report `:status :passed` with `:coverage-status :partial`, plus evaluated and not-evaluated check counts.

Partial-fill closed-form checks remain in `resolver-sim.yield.partial-fill`. They validate settlement decisions after allocation and must not mutate allocation progress or economics results.

## Progress observers

Progress is an operational observation mechanism, not semantic allocation input. Attach an observer with `:on-progress`:

```clojure
(payoffs/evaluate-pro-rata-allocation
 {:amount 100
  :unit :abstract-claim
  :participants [{:id :a :weight 40} {:id :b :weight 60}]
  :on-progress #(println %)} )
```

For notebook compatibility, adapt an atom:

```clojure
(def progress (payoffs/make-pro-rata-progress-atom))

(payoffs/evaluate-pro-rata-allocation
 {:amount 100
  :unit :abstract-claim
  :participants [{:id :a :weight 40} {:id :b :weight 60}]
  :on-progress (payoffs/progress-atom-observer progress)})
```

Events include phase and completion information. Redistribution additionally reports `:phase :redistributing` and `:redistribution-pass`. The observed allocation is the same projection-derived allocation returned by the evaluator; attaching an observer does not trigger a second operational allocation.

Observer identity and emitted events are excluded from projection and result hashes. Attaching a UI, logger, channel bridge, or atom must not change allocation identity. Exceptions raised by function observers are ignored so observability cannot invalidate a mathematical allocation.

## Proposed finalized outcome contract

Redistribution allocation records must distinguish pool-level conservation from
participant entitlement semantics. A participant weight, request, or cap does
not by itself establish an entitlement; protocol adapters must explicitly
supply an entitlement interpretation before `:final-unmet-entitlement` is
meaningful.

```clojure
{:participants
 [{:id :claimant-a
   :initial-request 50
   :initial-allocation 30
   :effective-cap 30
   :redistributed-in 0
   :final-allocation 30
   :final-entitlement nil
   :final-unmet-entitlement nil}]
 :allocation-summary
 {:available 100
  :allocated 100
  :unallocated-residual 0
  :residual-reason :none
  :entitlement-model nil}}
```

### Field decision table

| Field | Scope | Timing | Meaning | Conservation role |
|---|---|---|---|---|
| `:id` | Participant | Input / final | Stable, unique participant identity | Join key only; not numeric |
| `:initial-request` | Participant | Before first-pass constraints | Algorithmic request or quota target; diagnostic unless an entitlement model explicitly says otherwise | Not inherently a conservation term |
| `:initial-allocation` | Participant | Initial pass | Value actually allocated during the first allocation pass | `final-allocation = initial-allocation + redistributed-in` |
| `:effective-cap` | Participant | Final policy input | Cumulative maximum final allocation permitted by the active policy | Bounds `:final-allocation`; residual capacity is derived, not stored |
| `:redistributed-in` | Participant | Final | Additional allocation received after the initial pass | Together with `:initial-allocation`, reconstructs final allocation |
| `:final-allocation` | Participant | Final | Cumulative allocation after all passes | Sum across participants equals `:allocation-summary/:allocated` |
| `:final-entitlement` | Participant | Final, only when declared by the mechanism | Mechanism-declared entitlement amount | Equals allocation plus unmet entitlement; otherwise `nil` |
| `:final-unmet-entitlement` | Participant | Final, only when declared by the mechanism | Unsatisfied portion of declared entitlement | Must be `nil` when `:final-entitlement` is `nil` |
| `:available` | Pool | Input | Value available to allocate | `available = allocated + unallocated-residual` |
| `:allocated` | Pool | Final | Sum of all final participant allocations | Sum of participant `:final-allocation` |
| `:unallocated-residual` | Pool | Final | Value still outside all participant allocations | Completes pool conservation; never assigned to a participant merely for reporting |
| `:residual-reason` | Pool | Final | Canonical primary reason for residual value: `:none`, `:rounding`, `:capacity-exhausted`, or `:no-eligible-participants` | Diagnostic only; precedence is defined below |
| `:entitlement-model` | Policy provenance | Final summary | Declares how entitlement interpretation was established, e.g. `{:type :declared-claim :adapter :sew/shortfall-claims-v1}` | Required when participant entitlement fields are populated; otherwise `nil` |

### Conservation rules

Pool conservation always applies:

```text
available = allocated + unallocated-residual
```

Participant entitlement conservation applies only when `:entitlement-model` is
explicitly declared:

```text
final-entitlement = final-allocation + final-unmet-entitlement
```

The finalized contract must enforce:

```text
allocated = sum(participant.final-allocation)
available = allocated + unallocated-residual
final-allocation = initial-allocation + redistributed-in
0 <= initial-allocation, redistributed-in, final-allocation, unallocated-residual
final-allocation <= effective-cap  (when a cap is present)
```

When an entitlement model is declared, `final-unmet-entitlement >= 0` and the
entitlement equation applies. When it is not declared, both
`:final-entitlement` and `:final-unmet-entitlement` must be `nil`.

Residual reason is canonical: a zero residual requires `:residual-reason :none`;
a positive residual requires a non-`:none` reason. When several conditions
apply, precedence is `:capacity-exhausted`, then `:no-eligible-participants`,
then `:rounding`.

Generic weighted allocation must not manufacture participant entitlement fields
from a weight, first-pass quota, cap, or temporary pass-local `:unmet` value.

## Evidence and persistence boundary

The evaluator intentionally does not offer flags such as `:emit-evidence?`, `:persist?`, or `:register-artifact?`. Evidence creation requires contextual data that does not belong in the generic economics layer, including parent hashes, runner identity, evidence policy, artifact location, and provenance.

A future PRF orchestration API may be named `execute-pro-rata-allocation!`. It should consume the completed pure evaluation package, require explicit execution context, persist the provenance-bearing result artifact, and emit/register evidence separately.

## Layering rule

`resolver-sim.economics.payoffs` remains protocol-agnostic. Yield partial-fill code may adapt its rows and policies into allocation primitives, but generic economics must not depend on yield, Sew, filesystem, or evidence-DAG namespaces. See [Generic Economics Layering](GENERIC_ECONOMICS_LAYERING.md).
