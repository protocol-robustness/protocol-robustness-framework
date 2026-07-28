# Concepts Layer

An interpretation and authoring layer above protocol replay. Concepts
provide stakeholder-facing vocabulary, use-case grouping, scenario
discoverability, and report enrichment — without changing protocol
semantics.

## Architecture

```
Concept language                                (data/concepts/*.edn)
  ecommerce purchase
  event deposit
  spending account
        ↓
Concept metadata / templates / projections      (docs/concepts/)
        ↓
Scenario annotations                            (suites/*/scenarios/*.edn)
        ↓
Protocol adapter                                (src/resolver_sim/protocols/)
        ↓
Replay, invariants, traces, evidence            (resolution / evidence chain)
```

The protocol remains the source of truth. Concepts are an interpretation
layer that makes simulation results explainable to stakeholders.

## Directory Layout

| Path | Purpose |
|------|---------|
| `data/concepts/registry.edn` | Concept registry index |
| `data/concepts/*.edn` | Individual concept definitions |
| `docs/concepts/README.md` | This file |
| `docs/concepts/GLOSSARY.md` | General project glossary |
| `docs/concepts/RISK_TAXONOMY.md` | Risk taxonomy across concepts |
| `docs/concepts/scenario-annotation-guide.md` | How to annotate scenarios |
| `docs/concepts/sensitivity-provenance-chain.md` | Cryptographic verification of sensitivity classification provenance |
| `src/resolver_sim/concepts/registry.clj` | Registry loading + validation |
| `src/resolver_sim/concepts/reporting.clj` | Concept-aware report shape (stub) |

## Current Concepts

| Concept | Status | Stakeholder Question |
|---------|--------|----------------------|
| `:ecommerce/purchase` | Draft | Can a marketplace payment flow remain fair under strategic behaviour? |
| `:event/deposit` | Draft | Can deposited funds be released only when intended conditions are met? |
| `:spending-account/controlled-balance` | Draft | Can a user safely maintain a spendable balance with holds and withdrawals? |
| `:verifiable-assurance/forensic-confidence` | Draft | How can I verify a protocol outcome without trusting any single authority? |

## Using Concepts

Concepts are referenced by scenario annotation (`:concept/id` metadata
in scenario EDN files) or by report enrichment (`:concept/summary` in
report output). The concept registry is loaded by the concepts namespace
and used by reporting code to generate stakeholder-facing summaries.

## Phase 2 (Future)

- Scenario generation from concept templates.
- Concept DSL for defining scenario families.
- Concept-level policy assertions and invariants.
- Business/domain state machines.
