# Framework Boundaries

This document is the canonical boundary guide for contributors.

Terminology used throughout:

- **framework substrate** — protocol-agnostic replay and adapter infrastructure
- **adapter** — a protocol-specific implementation of the framework interfaces
- **primary protocol model** — the Sew protocol model; the primary validation target in this repo
- **Sew adapter** — `protocols/sew.clj` + `protocols/sew/*`; the adapter code that wires the Sew protocol model into the framework interfaces; also the main worked example of the adapter pattern
- **research track** — exploratory modules not part of stable framework API

## 1) What is reusable today (framework substrate)

Reusable today:

- deterministic replay orchestration (`contract_model/*`)
- adapter contracts (`protocols/protocol.clj`)
- shared adapter flow-control wrappers (`protocols/common/*`)
- scenario-analysis substrate (`scenario/*`, core reporting plumbing)
- accounting-evidence output contracts (including `:funds-ledger-view` contract shape)

These should remain protocol-agnostic and adapter-oriented.

## 2) What is Sew-specific (primary protocol model + Sew adapter)

The Sew protocol model is the primary validation target in this repository.
The Sew adapter (`protocols/sew.clj` + `protocols/sew/*`) is the adapter code
that wires it into the framework, and the main worked example of the adapter
pattern.

- `protocols/sew.clj`
- `protocols/sew/*`
- Sew-integrated yield/accounting interpretation modules
- Sew-oriented providers under `generators/sew/*`, `io/sew/*`

Sew semantics are not assumed to be universal protocol semantics.

## 3) What an adapter must implement

At minimum, implement the required simulation adapter contract methods in
`protocols/protocol.clj`:

- protocol id
- world initialization
- execution-context construction
- action dispatch
- single-step and transition invariants
- world snapshot projection for replay
- id resolution helpers

## 4) What an adapter may optionally implement

Optional extensions:

- economic model hooks
- analysis/projection module hooks
- adapter-specific reports
- `:funds-ledger-view` producer

## 5) `:funds-ledger-view` guarantees

` :funds-ledger-view` is an adapter-facing accounting evidence contract.

### Framework-defined guarantees

- stable output contract shape
- explicit conservation verdict (`holds?`)
- explicit drift summary (`drift-total`, `drift-by-token`)
- read-only semantics (projection only; no state mutation)

### Adapter-defined semantics

- exact meaning of each bucket in that protocol world
- mapping from protocol world model into buckets
- protocol-specific liability/claimability interpretation

### Required fields (contract level)

- `:as-of-block-time`
- `:by-token`
- `:global`
- `:conservation`

### Typical buckets (adapter-mapped)

- deposited
- locked
- claimable
- released
- fees
- bonds
- slashed
- externalized
- drift

These labels are an adapter-facing accounting vocabulary; they do **not** imply
identical economic meaning across protocols.

## 6) What the framework deliberately does not generalize (yet)

Do not prematurely generalize:

- claimability calculation
- bond exposure calculation
- escrow solvency semantics
- protocol liability semantics
- payout/dispute economic interpretation

## 7) Research track boundary

Research-track modules are valuable but not part of stable framework API claims.

Typical locations:

- exploratory `sim/*`
- `sim/adversarial/*`
- `research/sew/*`

## 8) Priority policy

Current priority is **Scope 1 + carefully bounded Scope 2**:

1. Stabilize and document framework boundaries.
2. Keep the Sew adapter explicit as the primary protocol model and main adapter example.
3. Promote `:funds-ledger-view` as documented adapter-facing capability.
4. Extract only post-projection reconciliation utilities.

Delay full multi-protocol provider extraction until a second real adapter or
clear contributor demand exists.

---

## Research vs Stable Boundary

> Merged from `docs/architecture/RESEARCH_BOUNDARY.md`.

### Stable framework (reusable public-good substrate)

Includes deterministic replay infrastructure, adapter contracts, shared orchestration helpers, protocol-agnostic reporting/reconciliation mechanics, and shell wiring.

Typical namespaces: `contract_model/*`, `protocols/protocol.clj`, `protocols/common/*`, reusable portions of `scenario/*`, `time/*`, `db/*`, `io/*`, `server/*`.

### Reference implementation (Sew)

Includes the complete Sew adapter and semantics.

Typical namespaces: `protocols/sew/*`, Sew-backed providers under `generators/sew/*`, `io/sew/*`, Sew-integrated yield/accounting modules.

### Exploratory research

Includes phase studies, hypothesis sweeps, and adversarial exploration.

Typical namespaces: exploratory `sim/*`, `sim/adversarial/*`, research-oriented Python scripts under `python/*`.

### Claiming guidance

- For **framework** claims, cite only stable framework modules.
- For **protocol-semantic** claims, cite Sew reference modules.
- For **exploratory** claims, mark results as research/evidence, not framework API.

### CDRS theory “claim” vs protocol “claimable”

CDRS `:theory` uses **claim** for a falsifiable hypothesis (`:claim-id`, optional `:claim` text).
Protocol replay uses **claimable** for withdrawal rights and **`*-boundary` invariants**
(principal/yield/fee/bond caps). Those accounting checks are unrelated to theory
falsification unless explicitly wired into `:falsifies-if` or expectations.

See `docs/CDRS-v1.1-THEORY-SCHEMA.md` — **Claim boundaries**.

### Accounting-specific boundary

Safe to generalize: reconciliation mechanics, aggregation mechanics, drift/conservation reporting contracts.

Do not prematurely generalize: escrow/dispute/claimability semantics, resolver/bond semantics, payout economics, protocol-specific solvency meaning.
