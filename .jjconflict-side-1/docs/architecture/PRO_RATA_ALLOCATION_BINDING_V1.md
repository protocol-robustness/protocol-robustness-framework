# AllocationBindingV1

## Status and provenance

**Status:** recovered architecture record.

This record freezes the previously approved `AllocationBindingV1` semantic
contract. It was reconstructed from the approved project design thread after
the original checked-in record could not be located in the active or known
sibling workspaces. It is **not** claimed to be a byte-for-byte recovery of a
missing original, and no original checksum is asserted.

## Purpose and boundary

```text
pro-rata allocation
  = determines who gets what

application context
  = identifies what that allocation is for

AllocationBindingV1
  = canonically binds a validated allocation result to that context

proof/admission
  = later binds allocation/application statements to execution or source-state assurance

application execution
  = later consumes an admitted statement
```

`AllocationBindingV1` is a domain-neutral framework capability. Shortfall is a
consumer, not the primitive's identity. A proof-protected withdrawal flow is
another intended consumer.

The binding is a historical semantic commitment. It does not answer whether a
verifier, deployment, configuration, reserve state, or application action is
authorized now.

## Canonical artifact shape

A canonical `AllocationBindingV1` contains:

```text
schema/version
mechanism-id
mechanism-version
request-root
policy-root
allocation-result-root
application-context-intent
application-context-root
```

The final two fields identify a canonical application-context artifact without
putting application-specific fields into the reusable allocation primitive.
The context intent makes the claimed interpretation explicit. If an intent is
optional in the adopted canonical-hash convention, absence has exactly one
canonical representation: absent is not an empty string, empty keyword, or
unknown value.

Canonical hashing, intent registration, domain separation, root encoding, and
schema validation use established PRF conventions.

## Derivational construction

The normal construction API is derivational:

```clojure
(build-allocation-binding-v1
  validated-allocation-result
  application-context)
```

It does not accept independently caller-supplied mechanism, request, policy,
or allocation-result lineage. The binding may repeat those fields for
inspection and verification, but they are derived from the authoritative
primitive result only after primitive validation succeeds.

The required runtime-only projection is:

```clojure
(derive-allocation-binding-source-v1 authoritative-allocation-result)
;; => {:mechanism/id ...
;;     :mechanism/version ...
;;     :request/root ...
;;     :policy/root ...
;;     :allocation-result/root ...}
```

This projection is a derived view, not a new canonical artifact, identity, or
truth source. It must not use a caller-asserted `:validated?` flag. Validation
is an execution fact established by the authoritative primitive validator.

## Validated primitive-result boundary

The binding layer reuses the authoritative pro-rata primitive validator. It
does not implement a second allocator or result validator.

A validated result has already established, as applicable:

- supported primitive/result schema and mechanism version;
- canonical request and allocation-result roots;
- request and policy lineage;
- deterministic allocation;
- conservation and cap constraints;
- rounding, tie-break, residual, and redistribution witness semantics.

### Binding lineage provenance

| Binding field | Source | Derivation | Validation basis |
|---|---|---|---|
| `mechanism/id` | result `[:mechanism :id]` | direct | authoritative primitive validation and result root |
| `mechanism/version` | result `[:mechanism :version]` | direct | authoritative primitive validation and result root |
| `request/root` | result `:request/hash` | direct | independently recomputed from canonical request by the authoritative validator |
| `policy/root` | allocation policy fields in result | canonical derivation via `allocation-policy-root` | derive only after authoritative primitive validation |
| `allocation-result/root` | result `:allocation/hash` | direct | independently recomputed by the authoritative validator |

If a future primitive version cannot provide one of these fields directly or
canonically from validated data, the primitive's authoritative artifact
semantics must be extended first. The binding adapter must not synthesize the
missing lineage.

## Directional commitments

The graph is deliberately acyclic:

```text
request ───────┐
policy ────────┤
               ↓
       allocation result

application context
               │
               ↓
       AllocationBindingV1
               │
               ↓
    future realized statement
               │
               ↓
     future proof/admission
```

An application context must not commit back to an `AllocationBindingV1` root.
The binding contains no proof, SP1, admission, verifier, deployment, reserve,
or execution-authority fields.

## Generic validity versus application compatibility

Generic binding validation establishes only that:

- the allocation result was valid and its lineage agrees with the binding;
- the binding schema, mechanism identity/version, and context intent are supported;
- the application context artifact/root is canonical; and
- the binding commits to that exact context root.

Generic validation must not learn Shortfall or withdrawal execution semantics.
Application validators separately determine context compatibility with a
concrete subject:

```text
validate-shortfall-allocation-context
  Does this context refer to this Shortfall operation/state?

validate-withdrawal-allocation-context
  Does this context refer to this withdrawal batch?
```

A generically valid binding with a context for the wrong application instance
is context-incompatible, not retroactively binding-invalid. Use distinct
failure categories where the framework error model permits:

```text
primitive-result-invalid
binding-invalid
application-context-invalid
application-context-incompatible
```

## Constituents, construction, and authority

The allocation result and application context are **constituents**: independently
identifiable artifacts used to construct the binding. They are not consumed.

`AllocationBindingV1` is the **consequence** of construction. It grants no
present-tense execution authority. Later proof admission or application
execution may consume capabilities, but that is outside this contract.

Therefore keep these operations distinct:

```text
construct:
validated result + context → AllocationBindingV1

constrain:
binding + application state/policy → admissible or rejected
```

## Portability and conformance

The same validated primitive result must support at least:

```text
Result R + ShortfallContext S  → Binding A
Result R + WithdrawalContext W → Binding B

A != B
```

Both bindings can be generically valid. Their respective application validators
determine compatibility with a Shortfall operation or withdrawal batch.

The standalone conformance package has three stages:

```text
A. Primitive conformance
request + policy → canonical allocation result → primitive validation

B. Portable binding conformance
validated result + application context → AllocationBindingV1 → generic validation

C. Application conformance
validated binding + concrete application subject → context compatibility validation
```

An adopter may stop at stage B without adopting Shortfall, SP1, Sew, Solidity,
Deployment Ledger machinery, or proof admission.

## Explicit deferrals

This record does not authorize or define:

- a `realizedStatementRoot` bridge;
- V1/V2 source-bound public-values changes;
- SP1 programs or proof generation;
- Solidity contracts;
- Shortfall reserve, controller, assurance, or funding semantics;
- claims/activation/close lifecycle;
- deployment activation, governance, verifier registry state, or present-tense authority.

The next separate review boundary is:

```text
AllocationBindingV1
→ realizedStatementRoot
→ V2 source-bound public values
```
