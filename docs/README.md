# Documentation Index

Primary project framing and status live in the root `../README.md`.

## Start here by task

| Goal | Document |
|---|---|
| System overview (narrative) | `SYSTEM_OVERVIEW.md` |
| System overview (technical / architecture) | `architecture/ARCHITECTURE.md` |
| Set up and run locally | `quickstart/README.md` |
| Run validation suite | `testing/RUNNING_TESTS.md` |
| Understand adapter / framework boundaries | `architecture/framework-boundaries.md` |
| Scenario index and protocol properties | `scenarios.md` |
| Protocol design findings index | `findings/` |
| Evidence for external reviewers | `evidence/RESEARCHER_EVIDENCE_PACK.md` |
| CLI and Babashka task reference | `reference/usage.md` |
| Machine-readable schema catalog | `../schemas/README.md` |
| Capability and coverage status | `overview/CAPABILITY_STATUS.md` |
| Local services and persistence | `operations/LOCAL_SERVICES.md` |
| Entry-point execution and assurance capabilities | `operations/ENTRY_POINT_CAPABILITY_MATRIX.md` |
| Generated-document workflow | `reference/GENERATED_DOCUMENTS.md` |
| Registry ownership, validation, and change rules | `reference/REGISTRIES.md` |
| Runnable and visual examples | `../examples/README.md` |

## Specifications

### Core attestation & evidence

| Document | Description |
|---|---|
| `specs/ATTESTATION_SPEC_V1.md` | Attestation record schema, self-referential hash, typed references |
| `specs/ATTESTATION_RESOLVER_SPEC_V1.md` | Typed reference format (`attestation:sha256:`), resolution pipeline |
| `specs/ATTESTATION_BUNDLE_SPEC_V1.md` | Portable offline verification bundle format |
| `specs/ATTESTATION_QUORUM_SPEC_V1.md` | Multi-attestor quorum reports |
| `specs/ATTESTOR_REGISTRY_SPEC_V1.md` | Attestor identity registration and key authorization |
| `specs/ATTESTOR_ACCOUNTABILITY_SPEC_V1.md` | Attestor accountability and slashing conditions |
| `specs/CLAIMS_SPEC_V1.md` | Claim result schema and evidence references |
| `specs/CLAIM_DEFINITION_REGISTRY_SPEC_V1.md` | Registered claim definitions with canonical hashes |
| `specs/EVIDENCE_COMMITMENT_ROOT_SPEC_V1.md` | Post-hoc DAG anchor for execution + evidence chain |
| `specs/DAG_NODE_VALIDATION_SPEC_V1.md` | Evidence node validation rules and checks |
| `specs/RUN_BUNDLE_ROOT_SPEC_V1.md` | Bundle root manifest format |
| `specs/RUN_OVERVIEW_SPEC_V1.md` | Run overview record format |
| `specs/RUN_REQUEST_SPEC_V1.md` | Run request schema and runner selection |
| `specs/SENSITIVITY_SENTINEL_SPEC_V1.md` | Sensitivity gating for bundle export |
| `specs/BUNDLE_VERIFICATION_SPEC.md` | Bundle verification guide |
| `specs/evidence/` (7 documents) | Chain of custody, evidence record, chain spec, ink, DAG spec, event evidence, evidence status |

### Hashing & intents

| Document | Description |
|---|---|
| `specs/INTENT_DSL_SPEC_V1.md` | Structured semantic intent DSL for hashing |
| `specs/INTENT_REGISTRY_SPEC_V1.md` | Registered hash intent definitions |
| `specs/HASH_INTENT_REGISTRY_SPEC_V1.md` | Hash intent-to-kind mapping |
| `specs/ARTIFACT_KIND_REGISTRY_SPEC_V1.md` | Registered artifact kinds and their hash intents |

### Pro-rata & projections

| Document | Description |
|---|---|
| `specs/PROJECTION_PRORATA_SPEC_V1.md` | Pro-rata projection and allocation |
| `specs/PROJECTION_ARTIFACT_SPEC_V1.md` | Projection artifact schema |
| `specs/PROJECTION_DEFINITION_REGISTRY_SPEC_V1.md` | Registered projection definitions |
| `specs/MECHANISM_PERSISTENCE_SPEC_V1.md` | Mechanism persistence layer |
| `specs/PRO_RATA_PROPORTIONAL_MATH_SPEC.md` | Proportional math specification |
| `specs/PRO_RATA_TEST_VECTORS.md` | Pro-rata test vector documentation |

### Slash distribution

| Document | Description |
|---|---|
| `specs/SLASH_DISTRIBUTION_SCALED_SHARE_SPEC_V1.md` | `:rate-of-gross` scaled-share semantics, evidence contract, conservation boundary |

### CLI & tooling

| Document | Description |
|---|---|
| `specs/PRF_CLI_ARCHITECTURE_V1.md` | JAR-first CLI, command registry, dispatch, bb parity |
| `specs/COMMANDS.md` | Registered backstop command tiers |

### Forensic bundle workflow

| Document | Description |
|---|---|
| `forensic/POLICY_DEFINITIONS_SPEC_V1.md` | Evidence, execution, and output policy EDN definitions |
| `forensic/FORENSIC_ATTESTATIONS_SPEC_V1.md` | Attestation records in forensic run bundles |
| `forensic/FORENSIC_CLAIMS_SPEC_V1.md` | Claim result records in forensic run bundles |
| `forensic/CAPABILITIES.md` | Workflow scope and repository layout |
| `forensic/TRUST_MODEL.md` | Enforced versus recorded controls and assurance limits |
| `forensic/BUNDLE_WORKFLOW.md` | Run, verify, export, and reproduce command reference |
| `forensic/FORENSIC_PREFLIGHT_SPEC_V1.md` | Preflight validation for forensic runs |
| `forensic/FORENSIC_HARDENING.md` | Production hardening and configuration |
| `forensic/PRODUCTION_READINESS.md` | Production readiness checklist |
| `forensic/PRODUCTION_GAPS.md` | Identified production gaps |
| `forensic/RUNNER_CONSENSUS_SPEC_V1.md` | Multi-runner consensus protocol |
| `forensic/RUNNER_IDENTITY_SPEC_V1.md` | Runner identity and attestation |
| `forensic/RUNNER_MAILBOX_SPEC_V1.md` | Runner mailbox protocol |
| `forensic/RUNNER_MAILBOX_VALIDATION_SPEC_V1.md` | Mailbox message validation |
| `forensic/RUNNER_MESSAGE_SPEC_V1.md` | Runner message format |

### Traceability

| Document | Description |
|---|---|
| `specs/SOLIDITY_SHADOW_REGISTRY_SPEC_V1.md` | Simulation↔Solidity mapping with documented differences |

## Architecture

| Document | Description |
|---|---|
| `architecture/ARCHITECTURE.md` | Layering rules, namespace map, generalisation matrix |
| `architecture/SYSTEM_CONTEXT_AND_RUNTIME_TOPOLOGY.md` | Execution surfaces, runtime boundaries, artifacts, CI, and external integrations |
| `architecture/BENCHMARK_EXECUTION_ARCHITECTURE.md` | Benchmark manifests, replay, claims, bundles, signing, reporting, and portability |
| `architecture/ARTIFACT_LIFECYCLE_ARCHITECTURE.md` | Source baselines, generated output, evidence finalization, and CI artifact retention |
| `architecture/CLAIMS_AND_REGISTRY_ARCHITECTURE.md` | Claim semantics, evaluator paths, dependencies, registry boundaries, and auditability |
| `architecture/ADAPTER_AUTHORING_GUIDE.md` | How to implement a protocol adapter |
| `architecture/DECISION_FRAMEWORK.md` | Confidence-level calibration for reviewers |
| `architecture/framework-boundaries.md` | Framework / adapter / Sew / research track boundaries |
| `architecture/interface-contract.md` | Python/Clojure gRPC bridge interface contract |
| `architecture/ADR-0003-canonical-scenario-generation-boundary.md` | ADR |
| `architecture/ADR-0004-cross-protocol-funds-ledger-extraction.md` | ADR |
| `architecture/ADR-0005-framework-extension-packages.md` | ADR: framework extension packages and economics capabilities (Proposed) |
| `architecture/EVIDENCE_CHAIN_ARCHITECTURE.md` | Evidence chain architecture |
| `architecture/EVIDENCE_DAG_ARCHITECTURE.md` | Evidence DAG architecture (lifecycle, topology, validation, persistence) |
| `architecture/REPLAY_ENGINE_ARCHITECTURE.md` | Replay engine and dual-engine architecture (deterministic replay + Monte Carlo) |
| `architecture/EVIDENCE_CHAIN_REMEDIATION.md` | Evidence chain remediation plan |
| `architecture/EVIDENCE_REGISTRY.md` | Evidence registry design |
| `architecture/FORENSIC_EVIDENCE.md` | Forensic evidence pipeline |
| `architecture/RESOLVER_OVERFLOW_AND_FORCE_AUTHORISATION.md` | Resolver overflow and force-authorisation lifecycle |
| `architecture/HELD_CUSTODY_ACCOUNTING_AND_FORCE_AUTHORISATION.md` | Append-only held-custody ledger, final-held reporting, settlement, and force-authorisation consumption |
| `architecture/YIELD_AND_SNAPSHOT_MODULES.md` | Yield and snapshot module architecture |
| `architecture/YIELD_V1_VAULT_CENTRIC_TESTING.md` | Yield V1 vault-centric testing approach |
| `architecture/GENERIC_ECONOMICS_LAYERING.md` | Generic economics layering model |
| `architecture/PRO_RATA_EVALUATION_API.md` | Pure pro-rata evaluation, progress observers, and evidence boundary |
| `architecture/protocol-parity.md` | Protocol parity tracking |
| `architecture/ORACLE_FIXTURE_EXHAUSTION.md` | Oracle fixture exhaustion analysis |
| `architecture/AAVE_DIAGNOSTIC_ARCHITECTURE.md` | Aave diagnostic architecture |
| `architecture/RNG_REVIEW.md` | Random number generator review |

## Testing and validation

| Document | Description |
|---|---|
| `testing/RUNNING_TESTS.md` | Canonical test entrypoints and baseline |
| `testing/TEST_SUITE.md` | Full suite coverage and structure |
| `testing/TRAJECTORIES.md` | Trajectory technical reference |
| `testing/ADDING_GAME_THEORETIC_VALIDATION.md` | Contributor guide for new bounded game-theoretic checks |
| `testing/CLOSED_FORM_GAME_THEORETIC_VALIDATION.md` | Closed-form validation (algebraic and game-theoretic scope) |
| `testing/CANCELLATION_SHIPPING_GATES.md` | Cancellation scenario shipping gates |
| `testing/CANCELLATION_GAME_THEORY_GAP_CHECKLIST.md` | Cancellation game theory gap checklist |
| `testing/IDEMPOTENCE_CHECKLIST.md` | Idempotence verification checklist |
| `testing/SIMULATOR_TO_SOLIDITY_INVARIANT_MAPPING.md` | Invariant mapping between simulator and Solidity |
| `testing/subgame-validation.md` | Subgame-perfect equilibrium validation |
| `scenarios.md` | Scenario index, evidence classification, protocol properties |
| `concepts/CDRS-v1.1-THEORY-SCHEMA.md` | CDRS theory schema (scenario classification) |

## Evidence and research

| Document | Description |
|---|---|
| `evidence/README.md` | Evidence directory entry point |
| `evidence/RESEARCHER_EVIDENCE_PACK.md` | ≤15-minute reproducibility pack for external reviewers |
| `evidence/EVIDENCE_DAG_OVERVIEW.md` | Evidence DAG structure and policy-filtered output |
| `evidence/EVIDENCE_CHAIN_PRODUCTION_PLAN.md` | Evidence chain production plan |
| `evidence/investigation_guide_S19.md` | Investigation guide for scenario S19 |
| `evidence/S80_MOSTLY_LIQUID_END_TO_END_DEMO.md` | S80 end-to-end demo walkthrough |
| `evidence/detailed/` | Per-failure-class evidence with raw JSON traces |

## Security and threat model

| Document | Description |
|---|---|
| `security-model.md` | Simulation threat model and security assumptions |
| `research/FORCE_AUTHORISATION_AND_CUSTODY_THREAT_MODEL.md` | Bounded force-authorisation, custody, and forensic-evidence threat model |
| `requirements/sybil-mitigation-roadmap.md` | Sybil ring mitigation requirements |

## Protocol-specific reference

### Sew (dispute resolution / escrow / yield)

| Document | Description |
|---|---|
| `protocols/sew/STATE_MACHINE_GENERATED.md` | Sew protocol state machine (canonical) |
| `protocols/sew/TRANSITION_COVERAGE_GENERATED.md` | Sew transition coverage (canonical) |
| `protocols/sew/sew_action_identifiers.md` | Sew action identifier reference |
| `overview/REUSABLE_COMPONENTS.md` | Adapter/harness/fixture reuse guide |
| `overview/OUTCOME_MODEL.md` | Cross-protocol outcome model and migration plan |
| `overview/USE_OF_FUNDS.md` | Use-of-funds accounting contract |
| `overview/CAPABILITY_STATUS.md` | Current capability, coverage, parity, and limitation matrix |
| `overview/SEMANTIC_VOCAB.md` | Semantic vocabulary registry |
| `overview/SEMANTIC_REGISTRY.edn` | Semantic registry EDN data |
| `yield/YIELD_INVARIANTS.md` | Yield-general invariant catalog |
| `yield/YIELD_BEARING_INVARIANTS.md` | Yield-bearing asset invariants |

### Proposed Solidity upgrades

| Document | Description |
|---|---|
| `proposed-solidity-upgrades-in-next-release/` (8 documents) | Planned Solidity changes for next release |

## Concepts and findings

| Document | Description |
|---|---|
| `concepts/README.md` | Concept layer entry point |
| `concepts/GLOSSARY.md` | General glossary |
| `concepts/RISK_TAXONOMY.md` | Risk taxonomy by concept |
| `concepts/scenario-annotation-guide.md` | Guide for annotating scenarios with concepts |
| `concepts/protocol-alignment.md` | Protocol status convention (`:protocol/current`, `:protocol/proposed`, `:solidity/*`) |
| `concepts/ATTRIBUTION_GUIDE.md` | Attribution system: `with-attribution` usage patterns, context tracking |
| `concepts/CDRS-v1.1-THEORY-SCHEMA.md` | CDRS theory schema (scenario classification) |
| `findings/` | Protocol design findings, each classified with `:protocol/status` |
| `findings/S103_findings.md` | Findings from scenario S103 |

## Usage, build, and reference

| Document | Description |
|---|---|
| `quickstart/README.md` | Setup and first run |
| `reference/usage.md` | CLI, Babashka task, and test-runner reference |
| `reference/build.md` | Build guide |
| `reference/REPL_GUIDE.md` | REPL usage guide |
| `reference/logging.md` | Logging conventions |
| `reference/CODEBASE_INDEX.md` | Codebase file index |
| `reference/scenario-run-report.md` | Scenario run report format |
| `reference/GENERATED_DOCUMENTS.md` | Generated-document sources, regeneration, and checks |
| `reference/REGISTRIES.md` | Operational catalogue of protocol, command, scenario, benchmark, semantic, and evidence registries |
| `../schemas/README.md` | Machine-readable schema catalog |
| `operations/LOCAL_SERVICES.md` | Local XTDB and forensic-runner operational guidance |
| `operations/XTDB_USAGE.md` | Current XTDB read/write paths, time semantics, offline behavior, and replay-boundary review |

## Notebooks

The repository ships 31 interactive Clerk notebooks under `notebooks/`.
See `data/notebooks.edn` for the full registry with descriptions and maturity levels.

## Framework invariants

| Document | Description |
|---|---|
| `framework/evidence-invariant-mapping.md` | Evidence-to-invariant mapping |
| `framework/invariant-parity.md` | Invariant parity between simulation and Solidity |
| `framework/SOLIDITY_EQUIVALENCE_CORE_V1.md` | `solidity-equivalence-core-v1` cross-implementation invariant profile |
| `framework/patch-fraud-slash-accounting.md` | Fraud/slash accounting patch |
| `framework/temporal-rules.md` | Temporal rule definitions |

## Replay and determinism

| Document | Description |
|---|---|
| `replay/` (3 documents) | Replay engine and determinism documentation |
| `ROBUSTNESS_FRAMEWORK.md` | Adversarial validation, deterministic replay, simulation architecture |

## Generated artifacts

| Document | Description |
|---|---|
| `generated/` (6 documents) | Auto-generated reports and cross-reference tables |
| `generated/robustness-edge-case.generated.md` | Generated edge-case robustness report |

## Research and proposals

| Document | Description |
|---|---|
| `research/` (6 documents) | Research notes and analyses |
| `researcher/` (2 documents) | Researcher workflow guides |
| `prompts/` (1 document) | Agent prompts |
| `proposals/` (1 document) | Design proposals |
| `plain-language-explanations/` (2 documents) | Plain-language explanations of technical concepts |

## Visual documentation

| Document | Description |
|---|---|
| `visual/` (8 documents) | Diagrams, charts, and visual explanations |

## Supplementary reference

| Document | Description |
|---|---|
| `language/` (1 document) | Language-level conventions |
| `hashing/` (1 document) | Hashing deep-dive |
| `benchmarks/` (4 documents) | Benchmark definitions and reports |
| `conceptual/` (1 document) | Architecture strengths conceptual overview |

## Documentation status convention

- **Canonical**: primary source of truth for a topic.
- **Companion**: practical or narrowed companion to a canonical doc.
- **Generated**: derived from identified source files; regenerate rather than edit directly. See `reference/GENERATED_DOCUMENTS.md`.
- **Experimental**: describes a research, proposal, or unstable interface; not production or comprehensive-assurance evidence.
- **Archived**: historical context only; do not use for current implementation decisions.

## Historical and superseded

Archived docs are in `archive/`. They document original reasoning and are preserved
for reproducibility but must not be treated as current specification.
