# Registry Catalogue

Registries are authoritative indexes or semantic catalogues used to resolve identities, runtime entry points, evaluation semantics, and artifact relationships. They are not interchangeable: each registry has its own source of truth, validation path, and stability boundary.

## Registry map

| Registry | Source / implementation | Resolves | Validation | Notes |
|---|---|---|---|---|
| Protocol registry | `src/resolver_sim/protocols/registry.clj` | Protocol ID → lazily resolved adapter | Adapter tests and entrypoint loading | Current IDs: `sew-v1`, `yield-v1`, `dummy`; default is `sew-v1`. |
| Command registry | `resources/prf/commands/registry.edn`, `src/resolver_sim/cli/registry.clj` | CLI path → command ID/metadata | `bb commands:validate` | Checks CLI path uniqueness, dispatch coverage, and Babashka-wrapper parity. |
| Scenario and suite registry | `src/resolver_sim/scenario/suites.clj`, scenario files | Suite ID → scenario paths | `bb scenario-registry:validate` | Functional suites and benchmark pack suites are deliberately distinct groupings. |
| Benchmark catalogue | `benchmarks/registry.edn`, `benchmarks/packs/*/registry.edn` | Pack/domain → benchmark manifest | `java -jar prf.jar benchmark validate` (PRF CLI; no bb wrapper) | Pack lifecycle status is metadata; it is not a run result. |
| Benchmark claim catalogue | `benchmarks/claim-registry.edn` | Benchmark claim ID → description, concepts, invariants, evaluator ID | Benchmark resource validation plus evaluator tests | Declarative benchmark-facing catalogue. |
| Benchmark evaluator registry | `src/resolver_sim/benchmark/claims.clj` | Claim ID → runnable scenario/benchmark evaluator | Benchmark claim tests and active-benchmark checks | Evaluator ID conventionally equals claim ID. |
| Passive intent registry | `resolver-sim.definitions.passive-registries/intent-registry` | Semantic intent definitions | Startup hard-fail validation | Cross-validated against runtime hash intents. |
| Passive projection registry | `resolver-sim.definitions.passive-registries/projection-definition-registry` | Projection definitions, dependencies, and claim references | Startup hard-fail validation | Projection claim references must resolve to passive claim definitions. |
| Passive claim-definition registry | `resolver-sim.definitions.passive-registries/claim-definition-registry` | Evidence-node claim semantics, evaluator entry, dependencies, canonical hashes | Startup hard-fail validation | Used by `resolver-sim.claims.engine`; distinct from benchmark claim catalogue. |
| Attestor registry | `resolver-sim.definitions.passive-registries/attestor-registry` | Attestor identity, status, verification method, and keys | Startup hard-fail validation | See `ATTESTOR_REGISTRY_SPEC_V1.md`. |
| Execution registry | `resolver-sim.definitions.passive-registries/execution-registry` | Registered execution modes/entry points and dependencies | Startup hard-fail validation | Strict mode resolves executable entry points. |
| Evidence-policy registry | `resolver-sim.definitions.passive-registries/evidence-policy-registry` | Named evidence policies | Startup hard-fail validation | Governs policy metadata, not protocol semantics. |
| Runtime hash-intent/domain-tag registries | `src/resolver_sim/hash/canonical.clj` | Hash projection and domain-separation rules | Startup validation and intent-alignment checks | Executable counterpart to passive semantic intent definitions. |
| Evidence/artifact registry | `resolver-sim.evidence.chain` | Run artifact/evidence hashes and dependency links | Evidence-chain validation/reconciliation | Per-run generated output; not source-controlled registry data. |
| Benchmark run history | `src/resolver_sim/benchmark/registry.clj` | Local benchmark-run history | Best-effort write; no central validation | Stored under `~/.protocol-robustness/evidence/history.edn`; local convenience data, not published evidence. |

## Passive registry startup contract

`resolver-sim.definitions.passive-registries` validates the intent, projection, claim-definition, attestor, execution, evidence-policy, hash-projection, and domain-tag registries during startup.

Validation is intentionally hard-fail: an invalid passive registry prevents normal startup unless test-only validation controls are explicitly used. Validation includes required fields, canonical hashes, duplicate IDs, dependency cycles, resolvable references, and selected registry-specific rules.

## Claim registry boundary

There are two claim registry layers:

1. **Passive claim definitions** are hash-bound semantic definitions for evidence-node claim evaluation.
2. **Benchmark claim catalogue/evaluator registry** declares and executes workload-oriented benchmark claims.

The benchmark claim provenance bridge records either a passive definition hash or `:benchmark-catalogue-only` in benchmark claim results. Do not assume a matching identifier means the two layers are already unified. See `docs/architecture/CLAIMS_AND_REGISTRY_ARCHITECTURE.md`.

## Change rules

| Change | Required follow-up |
|---|---|
| Add or alter a protocol ID | Update adapter implementation, registry mapping, entrypoint tests, and architecture/adapter documentation. |
| Add a CLI command | Add registry metadata, dispatch handler, Babashka parity where applicable, then run `bb commands:validate`. |
| Add a scenario suite | Register it through existing suite conventions and run `bb scenario-registry:validate`. |
| Add a benchmark | Add manifest/pack registry/catalogue resources, evaluator coverage, and run `java -jar prf.jar benchmark validate`. |
| Add a benchmark claim | Add declarative catalogue data, evaluator implementation/tests, and lifecycle mapping. Add passive provenance only when its evidence-node semantics are defined. |
| Change a passive registry | Update semantic definitions and hashes as required; preserve IDs for unchanged meaning; run startup/registry validation. |
| Add an attestor/key | Follow the attestor registry specification and validation rules; never store private keys in the repository. |
| Change runtime hash intent | Update passive intent alignment, specifications, compatibility/migration documentation, and tests. |

## Review checklist

Before treating a registry-backed result as evidence, verify:

1. Which registry owns the identifier.
2. Whether the registry is source-controlled, generated per run, or local-only.
3. Whether a validator ran and what it validates.
4. Whether the result records the registry version/hash required for reproduction.
5. Whether a claimed relationship is explicit rather than inferred from matching names.

## Related documents

- `docs/architecture/CLAIMS_AND_REGISTRY_ARCHITECTURE.md`
- `docs/architecture/EVIDENCE_CHAIN_ARCHITECTURE.md`
- `docs/specs/CLAIM_DEFINITION_REGISTRY_SPEC_V1.md`
- `docs/specs/ATTESTOR_REGISTRY_SPEC_V1.md`
- `docs/specs/INTENT_REGISTRY_SPEC_V1.md`
- `docs/specs/PROJECTION_DEFINITION_REGISTRY_SPEC_V1.md`
- `docs/specs/HASH_INTENT_REGISTRY_SPEC_V1.md`
- `docs/specs/execution/EXECUTION_REGISTRY_SPEC_V1.md`
