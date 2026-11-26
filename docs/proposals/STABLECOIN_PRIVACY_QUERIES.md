# Stablecoin privacy policy and storage queries

This proposal separates policy limits from observed storage state. The
implementation exposes offline, read-only commands over caller-supplied signed
artifacts. It does not connect to an issuer, wallet index, database, processor,
or deletion service.

## Questions and evidence

| User question | Query | Evidence needed | Meaning of a positive result |
|---|---|---|---|
| What retention limit applies to this operation now? | `privacy policy resolve` | Scoped policy binding, policy body, signed head observation, caller-supplied authority and observer trust policies, exact operation context, freshness inputs | A matching rule's configured maximum is valid relative to those supplied trust policies. The command does not authorize storage or another operation. |
| Is this event reported as stored? | `privacy query-event` | Event root, signed storage inventory, subject authorization, storage and subject trust policies, freshness inputs | A trusted-relative inventory reported a record at a specific sink and observation time. |
| What event data is reported for this wallet subject? | `privacy query-wallet` | Opaque subject reference, signed inventory snapshot, subject authorization, storage and subject trust policies, freshness inputs | A caller-authorized summary of entries in that snapshot. It is not a complete global inventory unless the source proves that scope. |
| Has this data been deleted everywhere? | Not yet answerable by this adapter | Fresh deletion receipts and complete coverage from every storage operator and processor | The current adapter cannot establish global deletion. |

The policy query reports `max-retention-seconds` for rules matching one exact
request context. The storage query reports the retention deadline asserted by
the inventory for an observed event. These values can differ: the former is a
policy bound; the latter is a storage source's report about a particular
record. A query response does not prove that deletion happened at the deadline.

## Offline snapshot contract

`stablecoin-privacy-storage-inventory.v1` is a signed snapshot for one opaque
subject reference. It contains an observation time, per-sink coverage
declarations, and records binding event root, data category, applied policy
root, sink, state, creation time, observation time, and retention deadline.
Every record sink must appear in the inventory's coverage declarations;
otherwise the inventory is invalid and queries remain unknown.
The signature uses the existing signed external decision contract with role
`:privacy-storage-observer` and a storage-inventory domain tag.

Query authorization is a short-lived signed envelope scoped to either one event
root or one opaque subject reference. The caller trust policy must bind the
signing key to that subject reference. This is a fixture-level verification
contract; an authenticated identity provider and a real data-subject access
process are not integrated.

The subject reference must come from an explicit keyed or opaque identity
mapping. Do not derive it by hashing a low-entropy wallet address. Query output
omits the subject reference. Event-level details are returned only after the
authorization envelope verifies relative to the supplied trust policy.

## Status semantics

* `:stored-observed` means a fresh valid signed inventory reports at least one
  matching `:stored` record.
* `:deleted-observed-in-covered-sinks` means the supplied inventory reports
  deletion for matching records in the sinks it covers. It does not prove no
  other copies exist.
* `:not-observed-in-covered-sinks` means no matching entry appeared in a fresh
  inventory whose declared coverage entries are complete. This is not a claim
  of global absence.
* `:unknown` means evidence is missing, stale, invalid, or incomplete.

The `:complete` coverage value is a signed source assertion. Without an
independently trusted and audited inventory provider, it is not proof that all
replicas, processors, backups, or exports were covered. Every query reports
trust as relative to caller-supplied policies and is marked unusable for
authorization.

`privacy query-event` and `privacy query-wallet` explicitly report that they do
not resolve current policy. Use `privacy policy resolve` for a specific current
scope and join the results in a researcher-facing UI only when issuer, asset,
jurisdiction, operation, data category, and subject scope match. A future
service should return both the historical policy bound to the event and the
currently resolved policy, with separate roots and currentness labels.

## Remaining integration work

1. Obtain an authorized policy bootstrap, key bindings, and external trust
   distribution; fixture trust roots remain caller claims.
2. Define the event creation hook and inventory API for each actual store and
   processor. Bind the exact event and policy roots into every receipt.
3. Establish audited coverage and deletion evidence, including replicas,
   backups, legal holds, processors, and retention exceptions.
4. Integrate an authenticated subject-to-opaque-reference service and a
   privacy-preserving wallet index. Wallet control alone may not establish the
   legal data subject.
5. Add a service/client that joins current policy resolution with event or
   wallet evidence, handles pagination and freshness, and fails closed on
   incomplete coverage.
6. Independently review and qualify the exact merged source tree before any
   staging or production use.

No storage, issuer, wallet identity, or deletion adapter is implemented here.
