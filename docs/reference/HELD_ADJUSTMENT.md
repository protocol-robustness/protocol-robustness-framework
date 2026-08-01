# Held-adjustment primitive

`resolver-sim.accounting.held-adjustment` is the protocol-independent value
contract for a single held-custody adjustment.

It separates two representations of the same operation:

1. **Authorisation scope** — the economic custody operation that may be
   authorised.
2. **Recorded adjustment** — the resulting ledger/evidence record, which adds
   outcome and provenance fields.

The primitive is intentionally not a ledger, artifact, or parameter resolver.
It provides stable projections used by Sew adapters, replay, and assurance
verifiers so that those boundaries do not independently define the same scope.

## Namespaces

| Namespace | Responsibility |
|---|---|
| `resolver-sim.accounting.held-adjustment` | Held-adjustment scope and record projection. |
| `resolver-sim.assurance.parameter-attribution` | Structural validation and projection of the optional parameter attribution pair. |

## Scope versus record

### `held-adjustment-scope`

`project-held-adjustment-scope` returns the fields that define the custody
operation for force-authorisation and related-member scope hashes:

```clojure
{:authorization/id   "fa-42"
 :authorization/type :force-authorisation
 :held/direction     :out
 :token              :USDC
 :amount             100
 :held/account       :escrow-principal
 :held/position-id   [:held/position :USDC :escrow-principal 42]
 :owner/address      "0xRecipient"
 :held/reason        :force-authorised-release
 :held/workflow-id   42

 ;; Present only when the validated pair is present.
 :parameter/context  ...
 :parameter/address  ...}
```

` :held/position-id` is part of every new authorisation scope because it is a
custody-location boundary. Grants issued before position binding retain their
legacy preimage during execution and verification; newly issued grants include
it and therefore cannot be reused for a different custody position.

### `held-adjustment-record`

`build-held-adjustment` retains the complete adjustment record. In addition to
scope fields, typical records include:

```clojure
{:held-adjustment/id        "held-adjustment-7"
 :held/before               250
 :held/after                150
 :held/position-id          [:held/position :USDC :escrow-principal 42]
 :held/action               "finalize-released"
 :authorization/provenance  {...}}
```

Sew owns assignment of IDs and before/after balances. The primitive preserves
already-derived fields; it does not mutate a world, create an artifact, or
consume an authorisation.

## Public API

```clojure
(resolver-sim.accounting.held-adjustment/build-held-adjustment fields)
(resolver-sim.accounting.held-adjustment/held-adjustment-error adjustment)
(resolver-sim.accounting.held-adjustment/valid-held-adjustment? adjustment)
(resolver-sim.accounting.held-adjustment/project-held-adjustment-scope adjustment)
(resolver-sim.accounting.held-adjustment/parameter-attribution-error adjustment)
(resolver-sim.accounting.held-adjustment/reserved-adjustment-keys-present extra)
```

The final function detects reserved parameter keys inside an adapter metadata
carrier such as `:extra`. Those keys must be supplied at the top level of the
held-adjustment options map; otherwise an adapter could validate one operation
while recording another.

## Parameter attribution

Parameter attribution is an optional, all-or-nothing pair:

```clojure
{:parameter/context {...}
 :parameter/address {...}}
```

It says that a custody adjustment was applied under a parameter environment and
attributed to one parameter within that environment. It does **not** claim that
the parameter was economically correct, resolved, applicable, or consistent
with the adjusted amount.

### Presence rule

Both fields must be absent, or both must be present:

```clojure
;; Valid legacy/unattributed operation
{}

;; Valid attributed operation
{:parameter/context context
 :parameter/address address}
```

One-sided pairs produce one of:

- `:parameter-context-without-address`
- `:parameter-address-without-context`

Malformed forms produce:

- `:invalid-parameter-context`
- `:invalid-parameter-address`

### Parameter context

A context has a mandatory semantic type and uses exactly one of two locator
forms.

**Authoritative root form**:

```clojure
{:parameter-context/type    :protocol-parameters
 :parameter-context/root    "sha256:<64 lowercase hex characters>"
 :parameter-context/version 1
 :parameter-context/scope-id 42} ; optional
```

The root is validated using the project canonical SHA-256 reference predicate.
` :parameter-context/version` is the schema version of the context reference,
not a mutable revision number of the parameter set.

**Interim identifier form**:

```clojure
{:parameter-context/type :world-params
 :parameter-context/id   :sew/default-v1}
```

The ID form is explicitly non-cryptographic provenance metadata. A context may
not combine root/version and ID forms. Unknown context keys are rejected.

### Parameter address

An address uses exactly one form.

**Semantic ID**, optionally instance-scoped:

```clojure
{:parameter/id       :sew/escrow-principal
 :parameter/instance 42} ; optional
```

**Structural path**:

```clojure
{:parameter/path [:escrow :principal]}
```

A path must be non-empty and consists only of canonical scalar segments:
keywords, strings, or integers. Nested collections, maps, sets, functions, and
objects are rejected. Semantic IDs and paths cannot be combined.

## Hash and compatibility behavior

Parameter attribution is committed by existing evidence surfaces, not by a new
standalone attribution hash:

- v3 held-custody artifact hash preimages include the pair;
- force-authorisation scope hashes include the pair when present;
- related-claims member scope hashes include the same projected pair.

Legacy scope hashes remain unchanged when attribution is absent. Grants issued
before position binding also retain their original preimage; new grants bind
`:held/position-id`.

Closed-form artifact verification reports only valid classifications:

| Classification | Meaning |
|---|---|
| `:legacy-v2` | Valid v2 artifact; attribution is not authenticated by the v2 schema. |
| `:unattributed-v3` | Valid v3 artifact with no attribution pair. |
| `:attributed-v3` | Valid v3 artifact with a structurally valid, hash-bound pair. |
| `:invalid` | Unsupported schema, bad hash, invalid pair, or v2 artifact carrying provenance. |

Invalid artifacts are excluded from valid classification counts. A v2 artifact
with appended attribution does not authenticate that attribution.

## Ownership boundaries

The primitive does **not** own:

- world mutation;
- ledger/index maintenance;
- held-custody artifact construction or hashing;
- force-authorisation consumption;
- related-claims lifecycle or relationship membership;
- parameter resolution, parameter values, policy selection, or amount
  consistency.

Sew adapters (`add-held` and `sub-held`) use the primitive to construct and
apply the validated operation. Policy/application code remains responsible for
deciding when an adjustment should be parameter-attributed and for selecting a
stable context/address.
