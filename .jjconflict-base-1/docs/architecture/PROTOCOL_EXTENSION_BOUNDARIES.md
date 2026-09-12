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
  boundary used by architecture tests. Its core scope is the complete `src/`
  production tree—the same production source root copied into `prf.jar`—not a
  manually selected set of namespaces.

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

Sew CI must explicitly opt in to `:with-sew`. The architecture test reads every
Clojure form under `src/` and rejects dependencies on every configured extension
namespace prefix. It detects namespace declaration dependencies (`:require`,
`:use`, and `:import`) plus literal use of `require`, `requiring-resolve`,
`resolve`, `find-ns`, `the-ns`, and `(symbol "extension.namespace" ...)`.
Comments and documentation prose are not treated as dependencies.

## Reviewed extension bridges

`protocol-boundaries.edn` contains exact, file-and-target entries under
`:approved/extension-dependencies`. These are not broad directory or namespace
waivers: adding a different extension namespace or var, including to an already
listed file, fails the architecture test.

The entries are current compatibility bridges that cannot be removed in this
scope: the optional adapter registry, the Sew fixture/research-model hooks, and
Sew-only command, scenario, reference-validation, and benchmark paths. In
particular, `resolver-sim.benchmark.runner` dynamically resolves Sew replay and
invariant functions; it is explicitly classified as an adapter bridge pending a
registry capability that exposes those operations protocol-neutrally.

This guard therefore protects the full packaged production tree from **new or
changed unreviewed extension coupling**. It cannot make the listed legacy
bridges protocol-neutral; each remains an intentional, reviewable limitation
until its source is moved behind `resolver-sim.protocols.registry` or a generic
adapter interface.

## Completed Sew-only moves

The following operational utilities now live in `protocols_src/` and are
therefore absent from `target/prf.jar`: telemetry seeding, Sew fixture sync,
the interactive resolution REPL, reorg-check research scaffolding, and Sew
pro-rata test vectors. Their namespace names are unchanged for `:with-sew`
callers.

A Sew runtime can explicitly register itself by requiring
`resolver-sim.protocols.sew.extension`. That bootstrap is intentionally not
required by the core distribution.
