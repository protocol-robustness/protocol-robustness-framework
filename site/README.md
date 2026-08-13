# PRF Product Site

A static product/demo site for the Protocol Robustness Framework. It makes PRF
understandable to a newcomer — **Understand → Believe → Inspect → Verify** —
without ever letting the polished public story diverge from what the framework
actually computes.

## Architecture

```
            ONE EXECUTABLE TRUTH
                      |
                      v
                  PRF / Clojure
                      |
        +-------------+-------------+
        |                           |
        v                           v
  public-demo.v1 JSON          Clerk notebook
  (site/generated/...)         (not_admitted — the deep evidence notebook)
        |                           |
        v                           v
  Product demo                Assurance / inspection
  Next.js App Router          (deep-link, not iframe)
        |                           |
        +-------------+-------------+
                      |
                      v
             same evidence
```

Three ownership rules:

- **PRF owns the result.** The frontend never recomputes protocol semantics
  (it does not decide that editing a recorded amount causes the same check to
  fail). It renders the projected artifact.
- **The website owns the explanation.** Copy, layout and demo narrative live
  here and simplify what PRF proved — never strengthen it.
- **Clerk owns deep inspection.** The demo links out to the executable
  notebook; it does not embed it.

Public demonstrations exposed on this site use explicit, versioned
`public-demo.v1` artifacts. The amount-tampering admission walkthrough is
intentionally **not** a public framework demo: it is a notebook-only,
user-supplied example in `notebooks/demo_not_admitted.clj`.
## Prerequisites

- Node 22+ and `pnpm` (the repo uses `pnpm@11.9.0`; `site/pnpm-lock.yaml` is the
  single lockfile).
- For generation: Clojure CLI + Babashka (see repo root).

## Development commands

From `site/`:

| Command               | What it does                                   |
|-----------------------|------------------------------------------------|
| `pnpm install`        | Install dependencies (one lockfile)            |
| `pnpm dev`            | Local dev server                               |
| `pnpm build`          | Static export to `site/out/`                   |
| `pnpm typecheck`      | TypeScript check without a full build          |

From the repo root (generation and integrity):

| Command                 | What it does                                   |
|-------------------------|------------------------------------------------|
| `bb test:notebooks`     | Load notebook-only examples and all other notebooks |
| `bb demo:test`          | Executable framework demo assertion suites and public projections |
| `bb demo:public-build`  | Regenerate `site/generated/demos/*.json`       |
| `bb demo:public-check`  | Regenerate + diff; exit 1 on drift             |
| `bb demo:public-lab`    | Build the deep Clerk notebooks into `site/out/lab/` |
| `scripts/verify_site_links.sh` | Assert every demo's proof link resolves in the composed export |
| `scripts/test_public_demo_drift.sh` | Assert corrupt/stale/missing artifacts fail the drift check |
| `pnpm --dir site verify:demos` | Assert cross-field/provenance consistency of the artifacts |

## public-demo.v1 compatibility policy

`public-demo.v1` is a real boundary, not an internal convenience. Three rules:

1. **Existing v1 fields retain their meaning.** Never reinterpret an existing
   field; consumers depend on current semantics.
2. **New optional fields may be additive.** Adding an optional explanatory or
   provenance field is allowed within v1.
3. **Meaning changes or structurally-different formerly-optional evidence
   require `public-demo.v2`.** If a field's meaning changes, or evidence that
   was optional becomes structurally required, bump the version.

The version discriminator is the `schema` field. Both the Clojure producer
(`resolver-sim.demos.public.*`, `schema-id "public-demo.v1"`) and the TypeScript
validator (`site/lib/validate-public-demo.ts`) assert the same value, and the
CI `verify:demos` script rejects anything that does not match.

## Provenance and conservation (P0)

Every artifact carries a `source` block binding the page's facts to **one**
executable result:

```
"source" {
  "result-root" <committed hash, == evidence.committed-hash>
  "input-root"  <input identity: request-hash / ledger-root / scenario id>
}
```

The shared validator (`resolver-sim.demos.public.validate`) plus the build-time
TypeScript validator and `verify:demos` enforce cross-field consistency:

- narrative demos: a rejected outcome must be backed by a failing check, and
  the declared failed-checks must equal the failing evidence exactly — a page
  can never present a VERIFIED treatment from evidence that says FAIL;
- liquidity: `sum(requested)`, `sum(allocated)`, `sum(shortfall)`, pool,
  allocation totals, and conservation must all reconcile.

This makes it impossible for a future projection bug to assemble individually
correct facts from different executions into a single "valid" page. The P0
mutation tests (`test/resolver_sim/demos/public/provenance_test.clj`) prove the
splice classes are rejected.

## Generation flow

1. An executable framework demo produces a result for its own domain (for
   example, current-head or reordered evidence).
2. Its `resolver-sim.demos.public.*` projector copies presentation-safe facts
   into a `public-demo.v1` artifact and fails closed if required evidence is
   absent.
3. `bb demo:public-build` serialises the artifact deterministically to
   `site/generated/demos/<id>.json`.
4. The corresponding `site/app/demos/<id>/page.tsx` imports the JSON and runs
   `assertPublicDemo` at build time.

The frontend renders framework-supplied facts exactly as supplied; it never
calculates protocol outcomes. Notebook-only examples are rendered by Clerk and
are not exported as framework product demos.

## Publication flow

`.github/workflows/publish-site.yml` runs the whole pipeline on push to main:

```
PRF tests (bb demo:test)
  -> bb demo:public-build
  -> bb demo:public-check        (fail on drift)
  -> pnpm build                  (site/out/)
  -> bb demo:public-lab          (build the executable Clerk notebooks into site/out/lab/)
  -> GitHub Pages
```

The result is one static tree:

```
site/out/
├── index.html
├── demos/

│   ├── liquidity-shortfall/
│   └── reordered-evidence/
├── lab/                # static Clerk build — the executable notebooks
│   ├── index.html
│   └── notebooks/
│       ├── not_admitted/
│       └── pro_rata_allocation_result/
└── _next/
```

The demo pages deep-link into `lab/notebooks/<name>/`, so the "Inspect
executable notebook" step resolves to the actual rendered notebook. `pnpm build`
wipes `out/`, so the lab is composed *after* the site build.

GitHub Pages is the initial host. S3 + CloudFront is the natural later
destination for heavy historical run/evidence archives; curated demo assets stay
small and co-located.

## The demos

Three public demos tell a coherent story about protocol robustness without
requiring any framework vocabulary:

| Demo | Question | Executable source | Shape |
|------|----------|-------------------|-------|
| Blocked Decision | Can a verified result be changed? | `demo_not_admitted` (custody verifier) | narrative (baseline → change → outcome) |
| Liquidity Shortfall | $100 of requests vs $70 of liquidity? | `pro-rata.allocation/allocate` (real allocation engine) | allocation (pool → per-request → conservation) |
| Reordered Evidence | Does evidence order matter? | `reorder_chain` (chain verifier) | narrative |

Each is a projection of an executable result: the frontend never recomputes the
protocol outcome, and the committed artifact carries the exact committed hash
for its evidence.

## Adding the next demo

1. **Executable side:** add/extend a PRF demo namespace (like
   `src/resolver_sim/demos/not_admitted/` or `liquidity_shortfall/`) with
   scenario → demo model → assertions → CLI, and run `bb demo:test`.
2. **Projection:** add a `public/*.clj` namespace projecting that model into
   `public-demo.v1` (fail closed, deterministic, no strengthened evidence).
   Register it in `scripts/demos/export_public_demo.clj`'s `projectors`.
3. **Generate:** `bb demo:public-build --id <demo-id>` writes
   `site/generated/demos/<demo-id>.json`; commit it.
4. **Site:** add `site/app/demos/<demo-id>/page.tsx` importing the JSON,
   calling `assertPublicDemo`, and composing the reusable components
   (`DemoShell`, `StateCards`, `EvidencePanel`, `TechnicalProofCTA`).
5. **Drift:** `bb demo:public-check` in CI fails if the committed artifact ever
   stops matching PRF output.

## Clerk boundary

The demo page shows a **preview image** of the notebook and a **deep-link** to
`/lab/notebooks/<name>/` — the substantive evidence notebook rendered by the
static Clerk build. Each demo's `source.notebook` names its inspection target:
`not_admitted` (evidence-chain ordering, invariant-based admission, held-ledger,
custody gates) and `pro_rata_allocation_result` (pro-rata allocation artifacts).
The thin `demo_*` notebooks are the demo surface; the deep notebooks are where
the inspection happens.

The lab is built with `bb demo:public-lab` and composed after `pnpm build`. The
preview is not the proof; the executable notebook is.
