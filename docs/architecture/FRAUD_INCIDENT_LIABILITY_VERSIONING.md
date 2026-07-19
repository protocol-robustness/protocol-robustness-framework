# Fraud incident and liability versioning

## Current contract: `fraud-incident.v1`

`fraud-incident.v1` is an immutable, independently addressable declaration of an
alleged common event. It records the declared incident, affected workflows,
related-claim references, evidence references, rationale, declarer, temporal
context, and a canonical content hash.

A `:fraud-group` slash proposal carries a typed `fraud-incident-ref.v1`:

```clojure
{:schema-version "fraud-incident-ref.v1"
 :incident-id "dr-pr-002"
 :incident-hash "..."}
```

The proposal resolves that exact record and fails closed on missing, mismatched,
ineligible, or incompatible incident references. The reference then propagates
into the allocation input, allocation result, and slash evidence.

This binding establishes provenance only:

```text
immutable incident declaration
→ hash-bound fraud-group proposal
→ immutable stake snapshot
→ pro-rata allocation and execution evidence
```

It does **not** make incident membership an authorization decision. In v1,
governance still declares the proposed liable resolvers and amount on the slash
proposal. Allocation only calculates how that declared obligation is met from
available stake.

`related-claims` remains an audit/reporting vocabulary. It is not the authority
for incident identity or liability authorization.

## Future extension: incident-authorized liability

A future `fraud-incident.v2` or separate, versioned incident-liability policy
may add an explicit closed authorization scope:

```clojure
:incident/liability-scope
{:resolver-ids [...]
 :workflow-ids [...]
 :authorization-mode :closed}
```

Only that future contract may cause a slash proposal to reject a resolver or
workflow because it is absent from the incident's authorized scope. It must not
be inferred from v1's evidentiary membership fields.

Before implementing that extension, specify and test these independent policy
questions:

1. Is incident membership evidentiary, authorizing, or separately typed for
   each member class?
2. Which authority may amend membership or liability scope, and what evidence
   or timelock is required?
3. Does an amendment create a new immutable incident version, a successor
   record, or a separately hash-bound policy revision?
4. May a slash cover a strict subset of authorized resolver/workflow
   liabilities, and how is that subset committed and reviewed?
5. How do appeals affect incident-level findings versus resolver-level slash
   allocations and execution stays?

The extension should define its own schema version, canonical hash domain,
reference type, transition authority, and verifier rules. It must preserve v1
interpretability: a v1 incident reference never claims incident-authorized
liability.
