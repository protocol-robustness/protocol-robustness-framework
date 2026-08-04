# PRIORITY_ORDER_SPEC_V1

Status: Draft V1

## 1. Purpose

`PRIORITY_ORDER_SPEC_V1` defines the `priority-order.v1` primitive: a generic,
evidence-backed conversion of a set of **subjects** into an explicit, verifiable
sequence of **priority classes**.

The primitive answers one question:

> Which subjects must be considered before which other subjects, and which
> subjects have equal priority?

It deliberately does **not** decide how value is allocated within a priority
class. That remains the responsibility of a separate allocation primitive such
as pro-rata, equal-share, weighted allocation, or first-satisfied.

The central design principle:

> Priority is modeled as a **relation over subjects**, not as a side effect of
> sorting and not as an allocation method.

The principal output is therefore:

```text
an evidence-backed ordered partition of a subject set
```

That formulation supports strict priority, equal priority, deterministic
execution, and clean composition with pro-rata or other allocation policies.

------

## 2. Related Specs

This spec composes existing evidence, hashing, and allocation specs:

- `docs/specs/evidence/CANONICAL_HASH_SPEC_V1.md`
- `HASH_INTENT_REGISTRY_SPEC_V1.md`
- `INTENT_REGISTRY_SPEC_V1.md`
- `PRO_RATA_PROPORTIONAL_MATH_SPEC.md`
- `PRO_RATA_TEST_VECTORS.md`

The `priority-order.v1` artifact commits its content hash through the registered
`:priority-order-v1` hash intent (`PRIORITY_ORDER_V1` domain tag) in
`resolver-sim.hash.canonical`.

------

## 3. Core Model

Priority is best represented as an ordered collection of equivalence classes:

```clojure
{:priority-order/version :priority-order.v1

 :subjects
 [{:subject/id :claim/a
   :subject/kind :claim}
  {:subject/id :claim/b
   :subject/kind :claim}
  {:subject/id :claim/c
   :subject/kind :claim}]

 :priority-classes
 [{:priority/rank 0
   :members [:claim/a :claim/b]}

  {:priority/rank 1
   :members [:claim/c]}]

 :comparison-basis
 {:method :declared-tier
  :parameter-root "..."}

 :tie-policy :equal-priority

 :unclassified-policy :reject}
```

This means:

- `:claim/a` and `:claim/b` have equal priority.
- Both must be considered before `:claim/c`.
- No ordering exists between members of the same class.
- A downstream allocation policy decides how members of a class share available
  capacity.

### 3.1 Why Priority Classes

A flat ordered list silently introduces ordering between subjects that may
actually have equal rights. For example:

```clojure
[:claim/a :claim/b :claim/c]
```

cannot distinguish between:

1. `a` strictly preceding `b`, and
2. `a` and `b` having equal priority but requiring deterministic serialization.

Priority classes preserve the economically and institutionally meaningful
relation:

```clojure
[#{:claim/a :claim/b}
 #{:claim/c}]
```

Canonical vectors may be used inside each class for hashing, but that canonical
order MUST NOT acquire priority semantics.

------

## 4. Primitive Boundary

The primitive owns:

- subject membership;
- strict precedence between classes;
- equal-priority membership;
- the basis on which priority was determined;
- handling of unclassified subjects;
- canonical representation;
- integrity and reproducibility checks.

The primitive does **not** own:

- available liquidity or capacity;
- claim amounts;
- partial-fill arithmetic;
- pro-rata weighting;
- custody mutation;
- settlement execution;
- temporal scheduling.

This gives a clean composition:

```text
eligible subjects
      ↓
priority-order
      ↓
ordered priority classes
      ↓
allocation policy per class
      ↓
settlement or custody transition
```

------

## 5. Construction API

```clojure
(build-priority-order
 {:subjects subjects
  :classifier classify-subject
  :comparison-basis basis
  :tie-policy :equal-priority
  :unclassified-policy :reject})
```

The classifier returns a priority key rather than a final rank:

```clojure
(fn [subject]
  {:priority/tier 1
   :priority/reason :secured-claim})
```

The builder SHALL:

1. validate every subject;
2. classify each subject exactly once;
3. group equal priority keys;
4. order the groups using the declared comparator;
5. assign canonical dense ranks;
6. emit a content-addressed artifact.

Unclassified subjects (`nil` classifier result) are handled by
`:unclassified-policy`:

- `:reject` (default) — construction fails;
- `:highest-priority` — unclassified subjects form the first class;
- `:lowest-priority` — unclassified subjects form the last class.

The `:comparator` direction (`:ascending` default, `:descending`) flips the
declared class order. `:tie-policy :equal-priority` is the only supported tie
policy; a different tie policy SHALL be rejected.

Local identifiers are envelope metadata, not canonical content. An optional
`:priority/id` (or a general `:metadata` map) is attached to the artifact
envelope and SHALL be excluded from the canonical body, preimage, content hash,
integrity roots, equality, and derived claims.

------

## 6. Priority Methods (Extension Registry)

The initial generic registry:

```clojure
:declared-tier
:timestamp
:deadline
:severity
:stake-class
:security-interest
:governance-rank
:dependency-depth
```

Domain-specific methods are extension-backed rather than added as branches in
the generic primitive. Each extension SHALL provide:

- subject input schema;
- priority-key derivation;
- comparison contract;
- verification function;
- parameter projection;
- evidence projection.

Each registered method declares:

- `:method/name` — registry key (also the `:comparison-basis :method`);
- `:method/field` — the priority-key field that drives comparison;
- `:method/description`;
- `:method/validate-key-fn`;
- `:method/group-key-fn`;
- `:method/compare-keys-fn`;
- `:method/comparison-contract`;
- `:method/parameter-projection`;
- `:method/evidence-projection`.

Methods are registered with `register-method!` and resolved by
`resolve-method`.

------

## 7. Comparison Contract

The comparison method declares its algebraic contract:

```clojure
{:comparison-contract
 {:relation :total-preorder
  :reflexive? true
  :transitive? true
  :total-between-classes? true
  :ties-permitted? true}}
```

A **total preorder** is the useful default:

- subjects may tie;
- priority classes are totally ordered;
- every classified subject belongs to exactly one class.

More advanced policies may support a partial order, but they SHOULD produce a
different primitive, such as `priority-dag.v1`. A partial order MUST NOT be
silently linearized into a total order.

------

## 8. Required Invariants

A valid `priority-order.v1` establishes:

### 8.1 Membership Completeness

Every declared subject appears in exactly one priority class.

```clojure
subjects = union(priority-class.members)
```

### 8.2 No Duplicate Membership

No subject appears in more than one class.

### 8.3 Dense Ranks

Ranks are canonical and consecutive:

```clojure
0, 1, 2, ... n-1
```

Ranks are a projection of class order, not independently supplied authority.

### 8.4 Non-Empty Classes

Every priority class contains at least one subject.

### 8.5 Stable Equality

Subjects classified with the same priority key appear in the same class.

### 8.6 Comparator Consistency

The declared comparator MUST NOT produce cycles or contradictory results.

### 8.7 Canonical Tie Representation

Members within an equal-priority class are canonically sorted for hashing,
while explicitly carrying no precedence semantics.

### 8.8 Basis Commitment

The method, parameters, source roots, and relevant subject projections used to
derive priority are committed into the artifact hash.

------

## 9. Evidence Artifact

```clojure
{:artifact/kind :priority-order
 :artifact/version :priority-order.v1

 :subject-set-root "..."
 :comparison-basis-root "..."
 :priority-classes-root "..."

 :priority-classes
 [{:priority/rank 0
   :priority/key {:tier 1}
   :members [:claim/a :claim/b]
   :members-root "..."}]

 :derivation
 {:method :declared-tier
  :comparator :ascending
  :unclassified-policy :reject}

 :artifact/preimage "..."
 :artifact/content-hash "..."}
```

The artifact additionally carries `:subjects`, `:subject-priority-keys`
(per-subject classifier results, normalized to canonical-safe data),
`:comparison-basis`, `:comparison-contract`, `:tie-policy`, and
`:unclassified-policy` so the artifact is self-contained. Optional envelope
metadata (`:artifact/metadata`, including any `:priority/id`) is excluded from
the canonical commitment.

The content hash is computed over the artifact body via the
`:priority-order-v1` hash intent (`PRIORITY_ORDER_V1` domain tag). The
`:artifact/preimage` is the exact `pr-str` serialization of the body, and the
`:artifact/content-hash` is its content address; the body is hashed before the
envelope is attached, so body and preimage can never disagree.

A verifier independently recomputes:

- subject-set membership;
- each subject's priority key;
- grouping into equivalence classes;
- ordering between classes;
- dense ranks;
- class and whole-order roots;
- the artifact content hash.

The verifier (`priority-order-violations`) never invokes the builder's
classifier; it reconstructs the partition from the committed per-subject keys.

------

## 10. Query Operations

```clojure
(priority-rank order subject-id)          ;; => 0
(equal-priority? order :claim/a :claim/b) ;; => true
(higher-priority? order :claim/a :claim/c) ;; => true
(compare-priority order :claim/a :claim/c) ;; => :higher
(priority-class order subject-id)         ;; => {:priority/rank 0 ...}
(next-priority-class order 0)             ;; => {:priority/rank 1 ...}
```

`compare-priority` returns semantic results rather than integers:

```clojure
:higher
:equal
:lower
:unclassified
```

For a future partial-order primitive it could additionally return
`:incomparable`.

------

## 11. Deterministic Serialization

When equal-priority subjects must be executed sequentially for operational
reasons, introduce a distinct serialization policy in the composition layer:

```clojure
{:execution-order
 {:method :canonical-subject-id
  :semantics :serialization-only}}
```

This order MUST NOT alter:

- entitlement;
- eligibility;
- allocation weight;
- precedence;
- economic priority.

That distinction prevents implementation order from becoming accidental policy.

------

## 12. Composition with Allocation

The important composition rule is:

> Priority determines when a class becomes eligible; allocation determines how
> capacity is distributed among members of that class.

Composition is a separate concern from the primitive. The composition layer
(`resolver-sim.ordering.priority-composition`) consumes a validated priority
artifact and never redefines or mutates priority classes. The primitive itself
MUST NOT depend on liquidity, claim-amount, accounting, or allocation
implementation namespaces; the composition layer may depend on both the
primitive and registered allocation mechanisms.

```clojure
(apply-priority-allocation
 {:priority-order order
  :available-capacity 100
  :demand-by-subject
  {:claim/a 80
   :claim/b 80
   :claim/c 20}
  :within-class-policy
  {:method :pro-rata}})
```

Normative result shape:

```clojure
{:allocations
 {:claim/a 50
  :claim/b 50
  :claim/c 0}

 :exhausted-at-rank 0
 :partially-satisfied-class 0}
```

The priority primitive proves that `a` and `b` belong to the first class. The
pro-rata primitive proves why they each receive 50. Neither primitive absorbs
the other's semantics.

Within-class allocation policies are extension-backed through a registry
(`:pro-rata`, delegating to the `resolver-sim.pro-rata.allocation` mechanism,
and `:first-satisfied`). A within-class policy is a capacity-allocation policy,
never a priority semantic.

Deterministic derived diagnostics (`:unmet`, `:capacity-after`) live under
`:allocation/diagnostics` and are excluded from the normative parity contract.

------

## 13. High-Value Derived Claims

The primitive supports evidence-backed claims:

```clojure
{:claim/kind :priority-completeness
 :holds? true}

{:claim/kind :equal-priority
 :subjects [:claim/a :claim/b]
 :holds? true}

{:claim/kind :strict-precedence
 :higher :claim/a
 :lower :claim/c
 :holds? true}

{:claim/kind :priority-policy-reproduction
 :holds? true}
```

These can be referenced by settlement, allocation, benchmark, or governance
artifacts without duplicating the priority logic.

------

## 14. Namespace

Use:

```clojure
:priority-order.v1
```

for the artifact and:

```clojure
resolver-sim.ordering.priority
```

for the primitive, with composition (allocation, serialization) in:

```clojure
resolver-sim.ordering.priority-composition
```

The `ordering` root is preferred over `resolver-sim.allocation.priority` so the
primitive remains independent of economics and may also be used for evidence
review, research questions, workflow execution, governance proposals,
dependency resolution, and remediation queues.
