# Generated Documentation

Some repository documents are derived from executable definitions. Do not hand-edit a generated document unless its generator explicitly supports it.

## Generated surfaces

| Output | Source of truth | Regenerate | Verify only |
|---|---|---|---|
| `docs/scenarios.md` S01–S23 table | `src/resolver_sim/protocols/sew/invariant_scenarios/doc_summaries.clj` | `bb docs:scenarios` | Review the resulting diff. |
| `docs/protocols/sew/STATE_MACHINE_GENERATED.md` | Sew state-machine definitions | `make state-machine-docs-generate` | `make state-machine-docs-check` |
| `docs/protocols/sew/TRANSITION_COVERAGE_GENERATED.md` | Sew state-machine definitions and `scenarios/edn/*.edn` coverage | `make state-machine-docs-generate` | `make state-machine-docs-check` |
| Core generated docs | Their registered core-doc sources | `make core-generated-docs-generate` | `make core-generated-docs-check` |
| Semantic registry output (`docs/overview/SEMANTIC_VOCAB.md`, `docs/overview/SEMANTIC_REGISTRY.edn`) | `src/resolver_sim/definitions/registry.clj` | `make semantic-registry-generate` | `make semantic-registry-check` |

Concept EDN files under `data/concepts/` are hand-edited (not generated); structural,
registry, and mapping validation runs via `make concepts-check` (the `concepts_validate.clj`
script).

## Full checks

```bash
# Framework-only generated-doc checks (includes concept EDN validation)
make docs-as-code-check-framework

# Sew generated-doc checks
make docs-as-code-check-sew

# All registered generated-doc checks
make docs-as-code-check
```

## Contribution rules

1. Edit the source definition, not a generated output.
2. Run the relevant generator.
3. Review and commit the changed generated output when it is tracked in the repository.
4. Run the corresponding `*-check` target before submitting a change.
5. Do not commit local run output under `results/` unless a workflow explicitly asks for a curated artifact.

Golden reports and trace fixtures are related generated test data, not general documentation. Use `bb regenerate-goldens`, `bb fixtures:sync`, or `bb fixtures:generate-traces` only when the behavioral change is intended; review those diffs as carefully as source changes.
