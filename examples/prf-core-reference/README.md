# PRF core reference: bounded transfer

This is a deliberately small, protocol-neutral adapter proving the PRF core
workflow without Sew semantics. The adapter lives at
`src/resolver_sim/reference/bounded_transfer.clj`, not `protocols_src/`.

It implements a two-party bounded transfer:

- an offer names a sender, receiver, and maximum amount;
- acceptance succeeds only for a positive amount at or below that bound and
the sender balance;
- each package commits the policy closure and a SHA-256 digest over canonical
EDN; and
- a semantic rejection is still a valid, verifiable package.

## Run

```bash
clojure -T:build uberjar :variant prf
./examples/prf-core-reference/run.sh
```

`run.sh` invokes the adapter only as:

```bash
java -cp target/prf.jar clojure.main -m resolver-sim.reference.bounded-transfer ...
```

It does not use `:with-sew`, `protocols_src/`, Sew resources, claims, or
policies. It writes temporary packages to `actual/`, verifies their integrity
and semantic replay, then compares them with the committed canonical expected
packages:

```text
PACKAGED :bounded-transfer/pass accepted .../actual/pass.package.edn
PACKAGED :bounded-transfer/over-bound semantic-failure .../actual/fail.package.edn
VERIFIED .../actual/pass.package.edn
VERIFIED .../actual/fail.package.edn
PASS prf-core-reference: accepted and semantic-failure packages verified with target/prf.jar only
```

The expected failure is intentional: it accepts `7` after an offer bounded at
`4`, producing `:semantic-failure` with the `:within-offered-bound` violation.
