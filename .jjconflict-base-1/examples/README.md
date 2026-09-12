# Examples

Examples are small, educational artifacts. They are not a substitute for the canonical validation suites or benchmark evidence.

## Yield shortfall demo

`demos/yield-shortfall-partial-fill/demo.edn` is a playback definition for the deterministic `Y02_vault-shortfall-partial-withdraw` scenario. It demonstrates a partial withdrawal while the yield module has a liquidity shortfall.

The underlying scenario can be run from the repository root:

```bash
clojure -M:run -- --invariants --protocol yield-v1 \
  --scenario scenarios/Y02_vault-shortfall-partial-withdraw.json
```

Expected generated artifacts include `results/test-artifacts/test-summary.json` and `results/test-artifacts/test-artifacts.json`. Treat all `results/` output as local generated data.

## SPEDS galleries

`speds/component_gallery.clj` and `speds/dynamic_component_gallery.clj` are Clerk-oriented visual component examples for the Protocol Evidence Design System (SPEDS). They are examples, not production notebooks.

To work with Clerk-based material, use the project Clerk alias:

```bash
clojure -X:clerk
```

Move or adapt a gallery into `notebooks/` only when it becomes a maintained user-facing notebook. Validate related visual narrative coverage with `bb test:speds`.

## Example maturity

| Location | Intended use | Status |
|---|---|---|
| `demos/yield-shortfall-partial-fill/` | Reproducible demonstration input | Experimental; depends on the yield-v1 scenario path. |
| `speds/` | Visual component exploration and regression aid | Experimental; not a production report surface. |

For canonical behavior and evidence, use `docs/testing/RUNNING_TESTS.md`, `docs/benchmarks/`, and `docs/evidence/`.
