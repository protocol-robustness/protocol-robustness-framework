# Concepts Layer

An interpretation and authoring layer above protocol replay. Concepts
provide stakeholder-facing vocabulary, use-case grouping, scenario
discoverability, and report enrichment — without changing protocol
semantics.

## Architecture

```
Stakeholder concept definition                  (data/concepts/)
        ↓
Canonical registry: owner + applicability        (data/concepts/registry.edn)
        ↓
Shared mappings + selected protocol mappings     (data/concept-mappings/)
        ↓
Scenario annotations / report enrichment        (suites/*/scenarios/, reports)
        ↓
Protocol adapters, replay, evidence               (src/resolver_sim/protocols/)
```

The protocol remains the source of truth. Concepts are an interpretation
layer that makes simulation results explainable to stakeholders.

## Directory Layout

| Path | Purpose |
|------|---------|
| `data/concepts/registry.edn` | Canonical concept index, semantic owner, and protocol applicability |
| `data/concepts/{allocation,assurance,decision-quality,framework,...}/*.edn` | Individual concept definitions grouped by semantic domain |
| `data/concept-mappings/{shared,prf,sew-v1}.edn` | Shared mappings plus protocol-specific mappings from concept roles, entities, actions, and outcomes to protocol vocabulary |
| `docs/conceptual/README.md` | This file |
| `docs/conceptual/incentive-property-semantics.md` | Incentive property → concept ownership / coverage matrix |
| `docs/conceptual/GLOSSARY.md` | General project glossary |
| `docs/conceptual/RISK_TAXONOMY.md` | Risk taxonomy across concepts |
| `docs/conceptual/scenario-annotation-guide.md` | How to annotate scenarios |
| `docs/conceptual/sensitivity-provenance-chain.md` | Cryptographic verification of sensitivity classification provenance |
| `src/resolver_sim/concepts/registry.clj` | Registry loading + validation |
| `src/resolver_sim/concepts/reporting.clj` | Concept-aware report shape (stub) |

## Current Concepts

The full catalogue is indexed by `data/concepts/registry.edn`.
Each registry entry declares `:concept/owner` (`:shared`, `:protocol/sew-v1`,
or `:protocol/prf`) separately from `:concept/protocols`, which lists every
protocol context where the concept applies. A concept may be owned by one
protocol and still be applicable to another; applicability does not imply
ownership. Directories remain organized by semantic domain rather than by
protocol, so shared concepts have one canonical definition and ID.

Concept definitions hold stakeholder meaning and narrative descriptions.
Protocol mapping sets hold `:maps-to` labels and mapping qualifications, keyed
by concept ID, mapping category, and mapping ID. The shared set is the common
baseline; SEW and PRF sets contain protocol-owned mappings or overrides.
`concepts-for-protocol` combines the shared baseline with the selected protocol
set. The ordinary registry result retains the owner's mapping for existing
consumers. Keep mappings out of concept definitions so shared concepts can
have distinct SEW and PRF interpretations without duplicating their identity
or stakeholder explanation.
Select concepts most relevant to the incentive-property layer:

| Concept | Status | Stakeholder Question |
|---------|--------|----------------------|
| `:ecommerce/purchase` | Draft | Can a marketplace payment flow remain fair under strategic behaviour? |
| `:event/deposit` | Draft | Can deposited funds be released only when intended conditions are met? |
| `:spending-account/controlled-balance` | Draft | Can a user safely maintain a spendable balance with holds and withdrawals? |
| `:verifiable-assurance/forensic-confidence` | Draft | How can I verify a protocol outcome without trusting any single authority? |
| `:concept/conservation` | Draft | Is the quantity fully accounted for? |
| `:framework/incentive` | Draft | Does the protocol provide correct incentives for participants to behave honestly, and how is this verified? |
| `:framework/incentive-compatibility` | Draft | Does any participant gain by behaving dishonestly instead of honestly? |

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
