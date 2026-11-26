# Stablecoin privacy CLI example

This example exercises local policy inspection, offline evaluation, and
independent receipt reconstruction. All principals and data are synthetic.
The classifier uses the public RFC 8032 test-vector key; it is not a credential
and must never be used for real authority. The policy is intentionally
unapproved. An `allow` result remains `:usable? false`, `:not-executed`,
authority `:unverified`, and currentness `:unknown`.

Generate repeatable input bundles in a temporary directory:

```sh
clojure -Srepro -M:test -e '(load-file "examples/use-cases/stablecoin-privacy/fixtures.clj") (stablecoin-privacy-fixtures/-main "/tmp/prf-stablecoin-privacy")'
```

Inspect and evaluate the permitted public aggregate:

```sh
clojure -Srepro -M:cli privacy policy inspect --input /tmp/prf-stablecoin-privacy/policy.edn
clojure -Srepro -M:cli privacy evaluate --input /tmp/prf-stablecoin-privacy/allow-evaluate.edn
clojure -Srepro -M:cli privacy stage-output --input /tmp/prf-stablecoin-privacy/allow-evaluate.edn
```

The query commands require additional signed inputs from a storage inventory
provider and a subject authorization source:

```sh
clojure -Srepro -M:cli privacy policy resolve --input /path/to/policy-resolution.edn
clojure -Srepro -M:cli privacy query-event --input /path/to/event-query.edn
clojure -Srepro -M:cli privacy query-wallet --input /path/to/wallet-query.edn
```

`privacy policy resolve` reports the configured retention bound for the exact
policy scope and caller-supplied trust context. `query-event` and `query-wallet`
report storage observations from the supplied signed snapshot; they do not
connect to a live store. Their absence status is limited to the inventory's
declared coverage. See
[`../../../docs/proposals/STABLECOIN_PRIVACY_QUERIES.md`](../../../docs/proposals/STABLECOIN_PRIVACY_QUERIES.md)
for the input contract and limitations.

Reconstruct the saved allow receipt and the denied unregistered-recipient
case. The verifier recomputes each evaluation from its inputs:

```sh
clojure -Srepro -M:cli privacy verify --input /tmp/prf-stablecoin-privacy/allow.edn
clojure -Srepro -M:cli privacy evaluate --input /tmp/prf-stablecoin-privacy/deny-evaluate.edn
clojure -Srepro -M:cli privacy verify --input /tmp/prf-stablecoin-privacy/deny.edn
```

The evaluate command exits 0 for `:allow`, 1 for `:deny`, and 2 for
`:indeterminate`. `privacy verify` exits nonzero if the retained receipt does
not exactly match reconstruction. None of these commands verifies the
provenance of a caller's trust-policy configuration or authorizes a real
operation.

`privacy evaluate` emits the full rooted receipt for controlled evidence
handling. Use `privacy stage-output` for a user-facing stage summary. It reports
fixed statuses for request validation, classification, evidence, policy,
Sentinel, authority, currentness, and execution. It omits request data, stable
roots, rule identifiers, detailed reasons, and parser/verifier exception
messages. It is not rooted evidence, cannot replace the retained evaluation,
and does not authorize a disclosure. Even when a root is omitted, do not treat
other deterministic summaries as anonymous if their context can identify a
researcher or artifact.
