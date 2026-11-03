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
```

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
