# ADR-0003: Canonical Scenario Generation Boundary (Clojure vs Python)

- Status: **Accepted**
- Date: 2026-05-02
- Deciders: PRF maintainers
- Related: `docs/architecture/interface-contract.md`, `docs/architecture.md` *(note: `docs/architecture.md` was removed in the 2026-05-21 consolidation; the system diagram it contained is now in `docs/architecture/interface-contract.md` §System Diagram)*

---

## Context

The repository has both:

- Clojure protocol/replay model (`src/resolver_sim/*`) as execution authority
- Python bridge/orchestration (`python/sew_sim/*`) for gRPC-driven integration/live scenarios

Historically, adversarial scenario authoring and strategy experimentation in Python
created risk of semantic drift when scenario validity logic duplicated core protocol
rules outside the Clojure model.

We introduced Clojure-native generator modules (`src/resolver_sim/generators/*`) and
need a permanent architecture decision that prevents regression back to dual semantic
ownership.

---

## Decision

We define a strict ownership boundary:

1. **Clojure is canonical for scenario semantics.**
   - All protocol-stateful generation semantics, action validity rules, adversarial
     profile semantics, deterministic sequencing, and equilibrium evaluation hooks
     are owned by Clojure.

2. **Python is orchestration/adapter only.**
   - Python may orchestrate gRPC sessions, run integration/live harnesses, normalize
     wire payloads, and produce external reports.
   - Python must not become a second source of truth for protocol-stateful scenario
     semantics.

3. **Clojure-first change policy.**
   - New scenario semantics (actions, timing rules, adversarial families) must be
     implemented in `src/resolver_sim/generators/*` first.

---

## Rationale

- Preserves single-source-of-truth semantics aligned with replay/protocol engine.
- Reduces divergence bugs between generated traces and canonical replay behavior.
- Improves deterministic reproducibility and fixture promotion reliability.
- Keeps Python useful for integration without allowing model drift.

---

## Consequences

### Positive

- Cleaner architecture boundary and review expectations.
- Lower risk of hidden rule duplication.
- Better confidence that generated scenarios are replay-valid by construction.

### Trade-offs

- Some experimentation that was quicker in Python now requires Clojure changes.
- Cross-language contributors must understand the ownership rule to avoid rejected PRs.

---

## Enforcement Rules

For PRs touching scenario generation/adversarial semantics:

1. No protocol-stateful validity logic added in Python.
2. New semantics implemented in Clojure generator namespaces first.
3. Seed-determinism tests remain passing.
4. Replay/invariant tests for generated scenarios remain passing.
5. Interface docs and this ADR updated if boundary behavior changes.

---

## Scope of Canonical Clojure Ownership

- `src/resolver_sim/generators/actions.clj`
- `src/resolver_sim/generators/stateful.clj`
- `src/resolver_sim/generators/adversarial.clj`
- `src/resolver_sim/generators/scenario.clj`
- `src/resolver_sim/generators/equilibrium.clj`
- `src/resolver_sim/scenario/equilibrium.clj`

Python modules (e.g. `python/sew_sim/generator.py`) are non-canonical and must not
define independent protocol-stateful validity semantics.

---

## Review Checklist

- [ ] Canonical semantics changed in Clojure (not Python)
- [ ] Determinism preserved for seeded generation
- [ ] Replay compatibility preserved
- [ ] Cross-language contract docs updated when needed
