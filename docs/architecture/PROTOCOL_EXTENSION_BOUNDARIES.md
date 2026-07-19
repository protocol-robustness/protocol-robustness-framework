# Protocol extension boundaries

PRF ships a protocol-neutral core. Protocol implementations are extensions:
core code depends on `resolver-sim.protocols.protocol` interfaces and on
`resolver-sim.protocols.registry`; an extension may depend on core but core
must not import its implementation namespaces.

## Layout

- `src/` contains the PRF core, generic package/evidence APIs, yield support,
  and protocol-neutral reference adapters.
- `protocols_src/` contains Sew implementation code and Sew-only resources or
  tools. It is available only through `:with-sew` (or an alias that includes it).
- `config/architecture/protocol-boundaries.edn` is the machine-readable source
  boundary used by architecture tests.

## Extension registration

An extension registers an adapter symbol with
`resolver-sim.protocols.registry/register-extension!`. Registration does not
load the adapter. `get-protocol` resolves the registered adapter only when a
caller explicitly requests that protocol. Core callers should handle an absent
extension as an unavailable capability rather than assuming Sew is installed.

## Verification

Core-only CI must run:

```bash
clojure -T:build uberjar :variant prf
./examples/prf-core-reference/run.sh
clojure -M:reference-validation --suite-root suites/yield-reference-v1 --protocol yield
```

Sew CI must explicitly opt in to `:with-sew`. New direct imports of
`resolver-sim.protocols.sew` in a core source root are rejected by
`core-distribution-boundary-test`.

## Completed Sew-only moves

The following operational utilities now live in `protocols_src/` and are
therefore absent from `target/prf.jar`: telemetry seeding, Sew fixture sync,
the interactive resolution REPL, reorg-check research scaffolding, and Sew
pro-rata test vectors. Their namespace names are unchanged for `:with-sew`
callers.

A Sew runtime can explicitly register itself by requiring
`resolver-sim.protocols.sew.extension`. That bootstrap is intentionally not
required by the core distribution.
