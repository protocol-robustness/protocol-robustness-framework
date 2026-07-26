# PROJECTION_PRORATA_SPEC_V1

Status: Draft V1

## 1. Purpose

`PROJECTION_PRORATA_SPEC_V1` defines the required semantic flow for projection-based pro-rata allocation.

It is a coordination spec for future implementation work. It does not by itself require existing runtime code to call the passive registries.

The exact flow is:

```text
world
  → registered intent
  → registered projection definition
  → projection artifact
  → allocation
  → claims
  → evidence node
```

Any implementation that skips the registered intent, registered projection definition, or projection artifact is not projection-based pro-rata under this spec.

------

## 2. Related Specs

This spec composes existing registry and evidence specs:

- `INTENT_DSL_SPEC_V1.md`
- `INTENT_REGISTRY_SPEC_V1.md`
- `PROJECTION_DEFINITION_REGISTRY_SPEC_V1.md`
- `PROJECTION_ARTIFACT_SPEC_V1.md`
- `CLAIM_DEFINITION_REGISTRY_SPEC_V1.md`
- `docs/specs/evidence/EVIDENCE_NODE_SPEC_V1.md`
- `docs/specs/evidence/CANONICAL_HASH_SPEC_V1.md`

------

## 3. Design Principles

### 3.1 Registry First

Pro-rata semantics SHALL be declared in registries before runtime code depends on them.

Runtime allocation code SHALL NOT be the only source of semantic meaning.

------

### 3.2 Projection Before Allocation

Allocation SHALL consume a projection artifact, not raw world state.

Protocol-specific world state MAY be adapted into a projection artifact.

Protocol-agnostic allocation helpers SHOULD receive only the canonical allocation frame derived from that artifact.

------

### 3.3 Deterministic Integer Accounting

Allocation SHALL use deterministic integer arithmetic.

Rounding and remainder policy SHALL be declared by the projection definition.

------

### 3.4 Evidence Bearing

Every projection-based pro-rata allocation SHALL be able to emit an evidence node that links:

- source world identity
- registered intent
- registered projection definition
- projection artifact
- allocation result
- evaluated claims

------

## 4. Flow Contract

### 4.1 Step 1: World

Input is protocol world state.

The world MAY contain protocol-specific runtime structures.

The world SHALL NOT be passed directly to allocation under this spec.

The world identity SHOULD be recorded as a canonical hash when a supported world hash intent exists.

Example source reference:

```clojure
{:source/type :world-state
 :world-hash "sha256:..."
 :world-paths [[:slash-obligations]
               [:resolver-states]
               [:balances]
               [:staking]
               [:risk-config]
               [:accounting]]}
```

------

### 4.2 Step 2: Registered Intent

The allocation intent SHALL be registered in the Intent Registry.

The intent SHALL describe:

- operation type
- purpose
- protocol scope
- required inputs
- required constraints
- output shape
- extension policy

Canonical intent shape:

```clojure
{:id :pro-rata/slash-obligation-allocation
 :version 1
 :intent/type :pro-rata/allocation
 :intent/purpose :slash-obligation-allocation
 :scope {:protocols #{:sew}
         :domains #{:economic-allocation}
         :modules #{:slashing}}
 :inputs #{:obligations
           :weights
           :caps
           :balances
           :eligible-participants}
 :constraints #{:conservation
                :non-negative
                :allocation-completeness
                :rounding-bounded}
 :output {:type :allocation-vector
          :unit :wei
          :rounding :floor-with-largest-remainder}
 :input-policy :exact
 :constraint-policy :require-all
 :extensions-policy {:allowed? true
                     :require-namespaced-keys? true}}
```

TODO(agent): Align the concrete registry entry with the passive Intent Registry when pro-rata entries are added to code.

------

### 4.3 Step 3: Registered Projection Definition

The projection definition SHALL be registered in the Projection Definition Registry.

It SHALL define how to derive the allocation frame from the world.

Required definition concerns:

- participant extraction
- eligibility extraction
- weight extraction
- cap extraction, if applicable
- total obligation extraction
- rounding policy
- remainder policy
- ordering policy
- constraint set
- required claims

Canonical projection definition shape:

```clojure
{:id :projection/pro-rata-slash-obligation
 :version 1
 :projection-type :pro-rata-allocation

 :intent-types #{:pro-rata/allocation}
 :intent-purposes #{:slash-obligation-allocation}

 :source {:type :world-state}

 :include-paths
 [[:slash-obligations]
  [:resolver-states]
  [:balances]
  [:staking]
  [:risk-config]
  [:accounting]]

 :exclude-paths
 [[:runtime]
  [:debug]
  [:telemetry]
  [:yield-modules :* :ops]]

 :transforms
 [{:from :set :to :sorted-vector}
  {:from :instant :to :iso-8601-string}
  {:from :function :to {:type :structured-marker
                        :value {:type :fn}}}]

 :output {:type :allocation-frame
          :unit :wei
          :rounding :floor-with-largest-remainder
          :remainder-policy :unallocated
          :ordering-policy :input-order
          :required-keys #{:participants
                           :eligible-participants
                           :weights
                           :caps
                           :total-obligation
                           :constraints}}

 :claims
 [{:claim-id :projection-deterministic
   :required? true}
 {:claim-id :projection-canonical-safe
   :required? true}
  {:claim-id :allocation-complete
   :required? true}
  {:claim-id :non-negative
   :required? true}
  {:claim-id :conservation
   :required? true}
  {:claim-id :rounding-bounded
   :required? true}
  {:claim-id :ordering-independent
   :required? true}]

 :canonical-hash "sha256:..."}
```

------

### 4.4 Step 4: Projection Artifact

The projection artifact SHALL be derived from:

- world state
- registered intent
- registered projection definition

It SHALL contain only canonical-safe values.

Canonical projection artifact shape:

```clojure
{:schema-version 1
 :projection-id "projection-pro-rata-slash-obligation-..."
 :projection-type :pro-rata-allocation
 :projection-version 1
 :intent {:id :pro-rata/slash-obligation-allocation
          :version 1}
 :projection-definition-hash "sha256:..."
 :source {:world-hash "sha256:..."
          :input-hashes []
          :evidence-node-hash nil}
 :projection {:participants [...]
              :eligible-participants [...]
              :weights {...}
              :caps {...}
              :total-obligation 0
              :constraints {:unit :wei
                            :rounding :floor-with-largest-remainder
                            :remainder-policy :unallocated
                            :ordering-policy :input-order}}
 :summary {:participant-count 0
           :eligible-count 0
           :total-weight 0
           :total-obligation 0}
 :claims [{:claim-id :projection-deterministic}
          {:claim-id :projection-canonical-safe}]
 :metadata {}
 :projection-hash "sha256:..."}
```

The allocation step SHALL use `:projection`, not the original world.

------

### 4.5 Step 5: Allocation

Allocation consumes the projection artifact's allocation frame.

Allocation input shape:

```clojure
{:amount 0
 :items [{:id :participant/a
          :weight 0
          :cap nil}]
 :rounding :floor-with-largest-remainder
 :remainder-policy :unallocated
 :ordering-policy :input-order}
```

Allocation output shape:

```clojure
{:allocations [{:id :participant/a
                :allocated 0
                :unmet 0
                :weight 0
                :cap nil}]
 :total-requested 0
 :total-allocated 0
 :total-unmet 0
 :remainder 0
 :policy {:rounding :floor-with-largest-remainder
          :remainder-policy :unallocated
          :ordering-policy :input-order
          :total-weight 0}}
```

Current implementation note:

- `resolver-sim.economics.payoffs/allocate-pro-rata` is the protocol-agnostic allocation helper.
- It currently supports `:rounding :floor` and `:rounding :floor-with-largest-remainder`.
- It currently supports `:remainder-policy :unallocated`.
- It currently supports `:ordering-policy :input-order`.

Future pro-rata projection work SHOULD adapt projection artifacts into this generic input shape before considering additional allocation policies.

------

### 4.6 Step 6: Claims

Claims SHALL be evaluated after allocation.

Required claim categories:

- projection determinism
- projection canonical safety
- allocation completeness
- non-negative allocation
- conservation
- bounded rounding / remainder behavior
- ordering independence

Claim result shape:

```clojure
{:claim-id :conservation
 :claim-definition-hash "sha256:..."
 :status :pass
 :inputs {:projection-hash "sha256:..."
          :allocation-hash "sha256:..."}
 :observed {:total-requested 0
            :total-allocated 0
            :total-unmet 0
            :remainder 0}}
```

Claim definitions SHALL be registered before runtime code treats claim IDs as normative.

------

### 4.7 Step 7: Evidence Node

An evidence node SHALL record the allocation execution and its semantic lineage.

Evidence node result shape:

```clojure
{:result/type :pro-rata-allocation
 :projection {:projection-hash "sha256:..."
              :projection-definition-hash "sha256:..."
              :summary {:participant-count 0
                       :eligible-count 0
                       :total-weight 0
                       :total-obligation 0}}
 :pro-rata {:intent {:id :pro-rata/slash-obligation-allocation
                     :version 1}
            :projection-hash "sha256:..."
            :allocation-hash "sha256:..."
            :claims [{:claim-id :projection-deterministic
                      :claim-definition-hash "sha256:..."
                      :holds? true
                      :violations []
                      :status :pass
                      :claim-result-hash "sha256:..."}]
            :summary {:claim-count 7
                     :passed-count 7
                     :failed-count 0
                     :holds? true
                     :total-requested 0
                     :total-allocated 0
                     :total-unmet 0
                     :remainder 0
                     :policy {:rounding :floor-with-largest-remainder
                              :remainder-policy :unallocated
                              :ordering-policy :input-order
                              :total-weight 0}}}
 :status :pass}
```

Evidence payload shape:

```clojure
{:evidence/type :projection-prorata
 :projection {:projection-hash "sha256:..."
              :projection-definition-hash "sha256:..."
              :summary {...}}
 :pro-rata {:intent {:id :pro-rata/slash-obligation-allocation
                     :version 1}
            :projection-hash "sha256:..."
            :allocation-hash "sha256:..."
            :claims [...]
            :summary {...}}
 :parent-hashes [...]}
```

The evidence node SHALL make the full chain auditable:

```text
world hash
  → intent hash
  → projection definition hash
  → projection artifact hash
  → allocation hash
  → claim result hashes
  → evidence node hash
```

------

## 5. Invalid Flows

The following flows are invalid under this spec:

```text
world
  → allocation
```

```text
world
  → unregistered projection function
  → allocation
```

```text
world
  → projection artifact
  → allocation
```

The third flow is invalid because it lacks a registered intent and registered projection definition.

------

## 6. Hashing Requirements

Projection-based pro-rata implementations SHALL compute or preserve hashes for:

- world source identity
- intent registry entry
- projection definition
- projection artifact
- allocation result
- claim results
- evidence node

Hash inputs SHALL exclude self-hash fields.

Hash inputs SHALL use canonical-safe values.

Domain tags SHALL be declared through registered hash intents before new hash surfaces are enforced at runtime.

TODO(agent): Add any missing hash intents for allocation results and claim results before enforcing allocation/evidence hashes.

------

## 7. Runtime Adoption Rule

This spec is a target for future code changes.

Phase order SHOULD be:

1. Register pro-rata intent, projection definition, claim definitions, and any missing hash intents.
2. Add validators for the pro-rata registry entries.
3. Emit projection artifacts without changing allocation semantics.
4. Adapt allocation to consume projection artifacts.
5. Emit claims.
6. Emit evidence nodes linking the full chain.
7. Only then hard-fail runtime code on missing registry/projection/evidence links.

Runtime enforcement SHALL NOT precede registry data and validation.

------

## 8. Audit Checklist

For a projection-based pro-rata allocation, an auditor SHALL be able to answer:

- Which world hash was used?
- Which registered intent was used?
- Which registered projection definition was used?
- Which projection artifact was allocated from?
- Which allocation policy was applied?
- Which claims were evaluated?
- Which evidence node recorded the result?
- Can each hash in the chain be recomputed?

------

## 9. Projection Artifact Model (Proposed)

The current projection artifact captures only the ex-ante allocation frame (basis, participants, weights, caps, policy). The allocation outcome — what was actually fulfilled, deferred, haircut, waived, or remaining — is absent.

Proposed split into two artifact types:

### 9.1 Projection Frame (`:projection-frame`)

Ex-ante: "This is the rule/input frame we are committing to."

Keeps the current projection artifact shape unchanged:

```clojure
{:schema-version ...
 :projection-id ...
 :projection-type ...
 :projection-version ...
 :intent ...
 :projection-definition-id ...
 :projection-definition-hash ...
 :source ...
 :projection ...
 :summary ...
 :claims ...
 :metadata ...
 :projection-hash ...}
```

A `:projection-frame` is computed at allocation time from the allocation input and registered definitions. It commits to the allocation basis — who is eligible, at what weight, under what caps and policy — **without** recording the actual allocation result.

### 9.2 Projection Result (`:projection-result`)

Ex-post: "This is what the allocation produced."

Suggested shape:

```clojure
{:schema-version             "projection-result.v1"
 :projection-result-id       "<derived-id>"
 :projection-result-type     :pro-rata-allocation-result
 :projection-result-version  1

 :projection-frame-id        "<projection-id>"
 :projection-frame-hash      "<projection-hash>"

 :projection-definition-id   :projection/pro-rata-slash-obligation
 :projection-definition-hash "<hash>"

 :source
 {:type              :allocation-result
  :world-before-hash "<world-before-hash>"
  :world-after-hash  "<world-after-hash>"
  :action-hash       "<action-hash>"
  :action-hash-at    "<action-hash-at>"
  :evidence-record-hash "<evidence-record-hash-or-node-hash>"
  :basis             :slash-obligation
  :cap-field         :stake
  :slash-policy      :pro-rata}

 :allocation-result
 {:allocations      {...}
  :total-allocated  ...
  :total-unmet      ...
  :remainder        ...}

 :shortfall-outcome
 {:deferred  {...}
  :haircut   {...}
  :waived    {...}
  :fulfilled {...}
  :shortfall-basis ...}

 :invariant-links
 [{:id :shortfall-fidelity
   :status :pass
   :evidence-hash "<hash>"}
  {:id :allocation-complete
   :status :pass
   :claim-result-hash "<hash>"}]

 :claims
 [{:claim-id :allocation-complete
   :claim-definition-hash "<hash>"
   :claim-result-hash "<hash>"}
  ...]

 :metadata {...}

 :projection-result-hash "<hash>"}
```

### 9.3 Separation Rationale

| Aspect | `:projection-frame` | `:projection-result` |
|---|---|---|
| When computed | At allocation input time | After allocation executes |
| Semantics | Ex-ante commitment to rules | Ex-post record of outcome |
| Contains | Weights, caps, policy, definitions | Allocated amounts, deferred, haircut, waived |
| World-state linkage | Optional `:world-before-hash` | `:world-before-hash`, `:world-after-hash`, `:action-hash`, `:action-hash-at` |
| Dependencies | Intent registry, projection-definition registry | Frame artifact, claim results, evidence node |
| Changed by | Policy/definition changes | Execution of allocation against liquidity |
| Identity-intent | `:projection-frame` (current `:projection-artifact`) | `:projection-result` (new) |

### 9.4 Forensic Chain

The split produces a complete, auditable provenance chain:

```
world-before
  → projection-frame      (ex-ante: this is the rule)
  → allocate-pro-rata     (computation)
  → projection-result     (ex-post: this is what happened)
  → world-after
  → evidence record       (envelope with before/after hashes)
  → claim results         (invariant verification)
```

Each link references its predecessor by hash, forming a content-addressed chain:

```
projection-frame    ───  :projection-hash
projection-result   │──  :projection-frame-hash  → frame
                    │──  :source/:world-before-hash → world state
                    │──  :source/:action-hash-at → action context
                    └──  :claims/:claim-result-hash → claim results
evidence-record     ───  links frame, result, world hashes, action-hash-at
```

### 9.5 Migration Path

1. Add `:projection-frame` and `:projection-result` hash intents to `hash-intents` with domain tags `PROJECTION_FRAME_V1` and `PROJECTION_RESULT_V1`.
2. Keep `:projection-artifact` intent as an alias for `:projection-frame` during migration (both resolve to the same projection function).
3. Update `build-projection-artifact` to produce the `:projection-frame` shape (minimal change — already correct).
4. Add `build-projection-result` that consumes a frame artifact, the allocation output from `allocate-pro-rata`, and the world/action context.
5. Wire `build-projection-result` into the Sew slashing resolution and evidence pipelines.
6. Update artifact-kind registry (`ARTIFACT_KIND_REGISTRY_SPEC_V1.md`) to add projections and result kinds.
7. Deprecate `:projection-artifact` intent name in favor of `:projection-frame`.

### 9.6 Open Questions

- Should `:projection-result` include the full allocation-per-participant breakdown or only aggregate shortfall categories? (Suggested: full breakdown in `:allocation-result/:allocations`, shortfall summary in `:shortfall-outcome`.)
- Should `:projection-result` reference the evidence node hash or the action-hash-at directly? The proposed shape includes both `:action-hash-at` and room for `:evidence-record-hash`.
- Should `:deferred`, `:haircut`, `:waived` be per-participant buckets or aggregate totals? (Suggested: aggregate in `:shortfall-outcome`, per-participant detail in evidence record.)
