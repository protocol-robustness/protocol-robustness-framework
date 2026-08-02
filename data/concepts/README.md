# Concepts — Stakeholder-Facing Explanation Layer

Concepts map protocol-level mechanics to stakeholder-facing vocabulary.
They help answer: *what does this protocol outcome mean for the people
involved?*

## Scope

Concepts are a **stakeholder-facing explanation layer only**. They exist
solely to make protocol outputs interpretable by non-expert audiences.

Reusable concepts live in `data/concepts/`. Benchmark-local overlays live
under `benchmarks/concepts/` and may shadow global concept IDs only when
they explicitly declare `:concept/shadows-global? true`.

**Concepts must never affect:**
- Protocol execution (scenario running, dispute resolution, settlement)
- Evidence capture (trace collection, hashing, attestation)
- Canonical hashing or bundle root computation
- Protocol validity, correctness, or invariant checks
- Benchmark results, scoring, or claim evaluation
- Any registry or pipeline that feeds into the above

Concept enrichment is added **after** all protocol computation and
hashing are complete. It is a cosmetic overlay on reports.

### Non-Normative Status

Use-case concepts are **non-normative stakeholder-facing examples**.
Inclusion of a use-case indicates that a stakeholder problem can be mapped
to protocol concepts for examination; it does **not** assert:

- Implementation or production support in any Sew deployment
- Fitness for any particular purpose or domain
- Legal permissibility or regulatory classification
- Economic safety or correctness under adversarial conditions
- End-to-end testing or benchmark coverage

Each use-case file carries `:concept/maturity :illustrative` and
`:concept/support-status :not-asserted` to make this status
machine-readable. Support must be established separately through
referenced protocol capabilities, executable scenarios, benchmarks,
and deployment configuration.

## Maturity And Coverage

Two maturity tracks exist. **Evidential maturity** (below) is derived
from the benchmark catalogue and reflects actual protocol evidence.
**Conceptual maturity** is set per-concept via `:concept/maturity` and
indicates the confidence level of the stakeholder mapping itself:

| Conceptual Maturity | Meaning |
|---|---|
| `:illustrative` | Stakeholder framing and approximate mappings exist |
| `:mapping-reviewed` | Every referenced protocol keyword exists and mapping confidence is explicit |
| `:scenario-backed` | Material paths and failure modes reference executable scenarios |
| `:benchmark-backed` | Named benchmark claims and metrics support the described property |

Evidential maturity (derived from benchmark catalogue):

| Maturity | Meaning |
|---|---|
| `:defined` | Meaning and motivation are documented. |
| `:mapped` | Connected to a scenario or workload. |
| `:claimed` | Referenced by registered benchmark claims. |
| `:evaluated` | At least one referenced claim has a runnable evaluator. |
| `:benchmarked` | Included in an active benchmark whose required claims have runnable evaluators. |

Maturity is not a score and does not claim complete coverage across adapters,
workloads, or threat models.

## Concept Types

| Type | Layer | Directory | Description |
|------|-------|-----------|-------------|
| `:use-case` | `:stakeholder` | `use-case/` | Real-world scenarios (ecommerce, deposits, accounts) |
| `:decision-quality` | `:stakeholder` | `decision-quality/` | How the system decides contested outcomes |
| `:assurance` | `:stakeholder` | `assurance/` | How to verify protocol correctness independently |
| `:allocation` | `:stakeholder` | `allocation/` | How funds are allocated across claims (partial fill, shortfall, slash) |
| `:framework` | `:framework-explanation` | `framework/` | Framework-level explanation concepts (incentive, conservation, content roots, …) |
| `:mechanism` | `:protocol` | `mechanism/` | Protocol-mechanism concepts (e.g. deterministic pro-rata allocation) |
| `:security` | `:stakeholder` | `security/` | Security-relevant concepts (e.g. force-authorisation) |
| `:yield` | `:stakeholder` | `yield/` | Yield-bearing position concepts |

The `:concept/type` value determines the subdirectory a concept file lives in
(see `type-dir-map` in `scripts/concepts_validate.clj`).

## What "Consensus" Means Here

In this project "consensus" refers to **contested-outcome confidence** or
**decision quality** — the set of properties that determine whether a
disputed outcome can be trusted. It does *not* refer to blockchain
validator consensus (proof-of-stake, BFT, fork choice, etc.).

The six decision-quality concepts answer: who decided (authority), what they
knew (evidence), when it became binding (finality), how to challenge it
(escalation), whether progress was made (liveness), and why the outcome
should be accepted (legitimacy).

## File Layout

```
data/concepts/
├── README.md
├── registry.edn                          ← index of all concepts
├── use-case/
│   ├── ecommerce.edn                     ← marketplace purchase (includes fixed-price variant)
│   ├── event_deposits.edn                ← conditional deposits
│   ├── controlled_escrow_balance.edn     ← controlled escrow balance
│   ├── dispute_appeal_escalation.edn     ← appeal and escalation lifecycle
│   ├── resolver_participation_bonding.edn← resolver registration, bonding, capacity
│   ├── yield_bearing_escrow.edn          ← yield accrual, shortfall, recovery
│   ├── governance_rule_transition.edn    ← rule changes during active escrows
│   └── goal_contingent_pooled_escrow.edn ← pooled contributions toward a goal
├── decision-quality/
│   ├── authority.edn                     ← who decides
│   ├── evidence.edn                      ← what facts available
│   ├── finality.edn                      ← when binding
│   ├── escalation.edn                    ← how to challenge
│   ├── liveness.edn                      ← timeliness of process
│   ├── legitimacy.edn                    ← why accept outcome
│   ├── solvency.edn                      ← solvency status classification
│   ├── proposed_content.edn              ← proposed content
│   └── proposed_content_root.edn         ← proposed content root
├── assurance/
│   ├── verifiable_assurance.edn          ← forensic confidence
│   ├── attestation.edn                   ← attestation verification
│   ├── evidence_chain.edn                ← forensic evidence chain
│   └── evidence_backed_classification.edn← evidence-backed sensitivity classification
├── allocation/
│   ├── partial_fill.edn                  ← partial fill allocation
│   ├── shortfall.edn                     ← shortfall detection and recording
│   ├── pro_rata_fairness.edn             ← pro-rata fairness in allocation
│   ├── redistribution_fairness.edn       ← redistribution fairness
│   ├── deferred_claim.edn                ← deferred claim recovery
│   ├── slash_obligation.edn              ← slash obligation allocation
│   ├── slash_distribution.edn            ← slash distribution
│   ├── bounty_payable.edn                ← bounty payable
│   ├── bounty_payable_reserve.edn        ← bounty payable reserve
│   ├── deferred_class.edn                ← deferred position classification
│   └── fractional_allocation.edn         ← fractional remainder
├── framework/
│   ├── incentive.edn                     ← incentive (umbrella)
│   ├── incentive_compatibility.edn       ← incentive compatibility
│   ├── conservation.edn                  ← conservation
│   ├── confidence.edn                    ← confidence
│   ├── configuration.edn                 ← configuration
│   ├── content_root.edn                  ← content root
│   ├── registry_entry_hash.edn           ← registry entry hash
│   ├── research_command.edn              ← research command
│   ├── research_assignment.edn           ← research assignment
│   ├── held_custody.edn                  ← held custody
│   ├── review_certificate.edn            ← review certificate
│   ├── review_member_canonical_indices.edn← review-member canonical indices
│   ├── procedure_execution_witness.edn   ← procedure execution witness
│   ├── core_protocol_boundary.edn        ← framework–protocol extension ownership boundary
│   └── semantic_commitments.edn          ← semantic commitments
├── mechanism/
│   └── pro_rata_allocation.edn           ← deterministic pro-rata allocation
├── security/
│   └── force_authorisation.edn           ← force-authorisation
└── yield/
    └── yield_bearing.edn                 ← yield-bearing position
```

`incentive/` (empty) is reserved for future incentive-type concepts; the current
incentive concepts are `:framework`-typed under `framework/`.

## File Structure

Each concept file is a single EDN map with these keys:

| Key | Purpose |
|-----|---------|
| `:concept/id` | Unique keyword identifier (matches registry) |
| `:concept/type` | One of the concept types above; determines the file's subdirectory |
| `:concept/layer` | `:stakeholder` or `:framework-explanation` |
| `:concept/name` | Human-readable name |
| `:concept/summary` | One-paragraph explanation |
| `:concept/stakeholder-question` | The question this concept answers |
| `:concept/protocols` | Set of applicable protocol keywords |
| `:concept/roles` | Map of role keywords to role maps |
| `:concept/entities` | Map of entity keywords to entity maps |
| `:concept/actions` | Map of action keywords to action maps |
| `:concept/outcomes` | Map of outcome keywords to outcome maps |
| `:concept/failure-modes` | Vector of failure mode maps |
| `:concept/metrics` | Map of metric keyword to metric definition |
| `:concept/assumptions` | Vector of assumption strings |
| `:concept/out-of-scope` | Vector of out-of-scope statements |
| `:concept/related` | Vector of related concept keyword IDs (may be extended to typed maps in future) |
| `:concept/maturity` | Conceptual maturity (`:illustrative`, `:mapping-reviewed`, `:scenario-backed`, `:benchmark-backed`) |
| `:concept/support-status` | `:not-asserted` — use-case files do not assert implementation support |
| `:concept/known-gaps` | Vector of known mapping gaps or incomplete outcomes |
| `:concept/evidence` | Map with `:scenarios`, `:benchmarks`, `:claims` keyed to sets |

Each role, entity, action, and outcome map may contain:

| Key | Purpose |
|-----|---------|
| `:maps-to` | Vector of `:protocol.*` keyword mappings |
| `:mapping/confidence` | `:approximate` if mapping is not 1:1 |
| `:mapping/note` | Explanation of an approximate mapping |
| `:description` | Human-readable description |

## Mapping Vocabulary

Concept layer `:maps-to` values use a consistent keyword taxonomy:

| Namespace | Used In | Example |
|-----------|---------|---------|
| `:protocol.actor/*` | `:concept/roles` | `:protocol.actor/sender`, `:protocol.actor/resolver` |
| `:protocol.role/*` | `:concept/roles` (assurance only) | `:protocol.role/verifier` |
| `:protocol.entity/*` | `:concept/entities` | `:protocol.entity/escrow`, `:protocol.entity/evidence` |
| `:protocol.action/*` | `:concept/actions` | `:protocol.action/create-escrow`, `:protocol.action/release` |
| `:protocol.outcome/*` | `:concept/outcomes` | `:protocol.outcome/released`, `:protocol.outcome/stuck` |

These keywords are ad-hoc stakeholder-facing labels, not internal protocol
 type definitions. They bridge between concept language and protocol
 mechanics without requiring exact correspondence to source types.

### Capability Validation Boundary

A mapping label is not, by itself, an implementation-support claim. Protocol
adapters expose capabilities in configuration- and state-dependent ways, so
callers that need support verification must supply the exact capability labels
for the selected adapter/version/configuration to
`resolver-sim.concepts.registry/capability-validation-errors`. The function
returns structured errors for mappings outside that declared surface.

Reports therefore include `:concept/not-claimed`: use-case mappings are
illustrative unless separate scenario, benchmark, capability, and deployment
evidence establishes support.

## Validation

```bash
make concepts-check          # clojure scripts/concepts_validate.clj
```

Runs as part of the docs-as-code pipeline via `make docs-as-code-check`.

Checks that:
- Every EDN file under `data/concepts/` parses correctly
- Required concept keys are present
- Registry entries reference existing files
- File concept IDs match registry IDs
- Concept IDs are unique in the registry
- `:concept/related` references resolve to registered concept IDs
- Protocols are known (`:protocol/sew-v1`, `:protocol/prf`)
- Files live in the subdirectory matching their `:concept/type`
- `:maps-to` values use keyword (not string) form with recognized namespaces
- `:concept/known-gaps` and `:concept/evidence` are not yet validated by this script
