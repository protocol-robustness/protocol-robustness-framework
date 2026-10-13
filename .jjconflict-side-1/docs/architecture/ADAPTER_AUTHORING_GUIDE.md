# Adapter Authoring Guide

This guide explains how to add protocol adapters while preserving a clear
boundary between reusable framework infrastructure and protocol-owned semantics.

Canonical terminology:
- framework substrate
- adapter / Sew adapter
- primary protocol model (Sew — the main validation target in this repo)
- research track

See also: `docs/architecture/framework-boundaries.md`

## Design principle

Generalize infrastructure, not protocol semantics.

- Generalize: replay orchestration, scenario execution, invariant plumbing,
  temporal/state tooling, reconciliation/reporting contracts.
- Keep adapter-owned: escrow/dispute/claimability/payout semantics, authority
  model, resolver/bond logic, settlement economics.

## 1) Minimum implementation

Implement `SimulationAdapter` in `src/resolver_sim/protocols/protocol.clj`:

- `protocol-id`
- `init-world`
- `build-execution-context`
- `dispatch-action`
- `check-invariants-single`
- `check-invariants-transition`
- `world-snapshot`
- `resolve-id-alias`
- `created-id`
- `open-entities`

This is sufficient for deterministic replay + invariant-aware scenario runs.

## 2) Optional implementation layers

### `EconomicModel` (optional)

Use when the adapter needs adversarial/economic metrics and advisory outputs.

### `AnalysisModule` (optional)

Use when the adapter needs trace projection, reporting transforms, and
theoretical/mechanism evaluation integration.

## 3) `:funds-ledger-view` contract (optional)

If implementing `AnalysisModule/io-projection` target `:funds-ledger-view`:

- output contract shape is reusable,
- accounting semantics remain adapter-specific,
- target must be read-only (no world/session mutation).

See:
- `docs/overview/USE_OF_FUNDS.md`
- `docs/architecture/ADR-0004-cross-protocol-funds-ledger-extraction.md`

## 4) Namespace placement conventions

- Adapter contracts and registry: `resolver_sim/protocols/*`
- Shared adapter orchestration helpers: `resolver_sim/protocols/common/*`
- Protocol implementation: `resolver_sim/protocols/<protocol>/*`
- Experimental studies: `resolver_sim/research/*` (or clearly marked `sim/*`)

## 5) Build a minimal adapter

Use `src/resolver_sim/protocols/dummy.clj` as the smallest in-repository
`SimulationAdapter` example and `protocols/sew/*` as the complete worked
implementation. Start with a namespace under `src/resolver_sim/protocols/<name>/`
and implement every `SimulationAdapter` method from
`src/resolver_sim/protocols/protocol.clj`.

A minimal adapter has to provide all of the following behaviors:

1. Return a stable, versioned ID from `protocol-id`.
2. Build a serializable initial world from a scenario in `init-world`.
3. Return an opaque replay context in `build-execution-context`.
4. Apply one action in `dispatch-action`, returning `{:ok ... :world ...}` and a
   keyword `:error` for rejected actions.
5. Check both single-state and transition invariants without mutating either
   input world.
6. Produce a lean serializable trace state in `world-snapshot`.
7. Define valid actions, ID alias handling, created-entity extraction, open
   entities, and protocol-specific state projection.

Keep the adapter deterministic: make time, randomness, and external inputs
explicit scenario or context values. Do not perform I/O from adapter methods.

## 6) Wire and prove the adapter

The built-in protocol registry is `src/resolver_sim/protocols/registry.clj`.
Add the new adapter using the registry's existing symbol-based convention; do
not introduce an alternate registry path. Then add:

- one minimal executable scenario under the existing scenario conventions;
- a focused adapter test covering one accepted and one rejected action;
- an invariant test covering both `check-invariants-single` and
  `check-invariants-transition`;
- a replay test invoking the public
  `resolver-sim.contract-model.replay/replay-with-protocol` API.

Use this acceptance sequence:

```bash
# Framework-only source checks
clojure -M:lint/core
clojure -M:fmt/check

# Focused test namespace, replacing the placeholder
clojure -M:test -e "(require 'your.adapter-test) (clojure.test/run-tests 'your.adapter-test)"

# Registry and broader framework validation
bb scenario-registry:validate
bb test:framework
```

Add `EconomicModel` only when the protocol needs adversarial metrics or payoff
analysis. Add `AnalysisModule` only for projections, theoretical validation,
or reference-model comparison. These optional layers must not become hidden
requirements for deterministic replay.

## 7) Sew adapter — worked example

The Sew adapter is the main worked example of how to implement the framework interfaces:

- complete protocol semantics in `protocols/sew/*`,
- framework-compatible projections/reporting exposed through adapter interfaces,
- reusable contracts without claiming universal protocol semantics.

Before considering an adapter evidence-ready, document its protocol status,
scenario coverage, and any Solidity mapping limitations in the capability and
parity documentation. See `docs/overview/CAPABILITY_STATUS.md` and
`docs/architecture/protocol-parity.md`.
