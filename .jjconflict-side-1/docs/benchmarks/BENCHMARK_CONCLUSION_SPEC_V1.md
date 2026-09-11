# Benchmark conclusion projection v1

## Purpose

`benchmark/conclusion.json` is the compact, deterministic conclusion
of one finalized benchmark run. It is a projection of the benchmark evidence;
it does not introduce a new evaluator, infer protocol-wide truth, or replace
per-claim results.

The artifact is written before benchmark inventory and registry finalization.
It is therefore immutable once a completed run is published.

## Status dimensions

Command completion and benchmark conclusion are distinct:

| Field | Meaning |
|---|---|
| `command_status` | Whether orchestration, required writes, registry finalization, and validation completed. |
| `outcome` | What the executed benchmark evidence supports: `pass`, `fail`, or `inconclusive`. |
| `benchmark_status` | The manifest lifecycle status such as `active` or `experimental`. |

An experimental benchmark may produce a valid `pass` outcome for its declared,
bounded claims. That outcome must not be presented as an unrestricted protocol
safety claim.

## Schema

```json
{
  "schema_version": "benchmark-conclusion.v1",
  "run_id": "benchmark-...",
  "benchmark": {
    "id": "benchmark/force-authorisation-custody-v1",
    "status": "experimental",
    "manifest_source": "resource:benchmarks/packs/sew/..."
  },
  "command_status": "completed",
  "outcome": "pass",
  "reason": "all-scenarios-and-required-claims-passed",
  "scenarios": {"total": 2, "passed": 2, "failed": 0},
  "claims": {"required": 3, "passed": 3, "failed": 0,
             "inconclusive": 0, "not_exercised": 0},
  "invariants": {"total": 24, "passed": 24, "failed": 0},
  "evidence": {"path": "benchmark/evidence.edn", "sha256": "..."},
  "scope": {
    "statement": "Declared benchmark claims passed for the executed inputs.",
    "does_not_establish": ["unexercised claims", "protocol-wide safety"]
  }
}
```

## Classification rules

1. `fail` when any executed scenario fails, a required claim fails, or any
   invariant result fails.
2. `inconclusive` when required claim evaluation is missing, empty,
   `inconclusive`, `not-exercised`, or `not-implemented`.
3. `pass` only when all executed scenarios, required claims, and recorded
   invariant results pass.
4. The conclusion must list aggregate counts, the evidence path and hash, and
   scope limits. It must not embed sensitive final-world data or raw event
   payloads.

## X, therefore Y conclusion definitions

The canonical "X, therefore Y" definition artifact is `research-conclusion.v1`
(`src/resolver_sim/benchmark/research_conclusion.clj`). It records an inference
as a content-addressed artifact:

```text
X = :conclusion/premise   ; what was established
∴  = :conclusion/inference :therefore   ; the inference step (pinned)
Y = :conclusion/result    ; what follows
```

A conclusion also commits what it does NOT conclude
(`:conclusion/qualifications`), its bounded `:conclusion/scope`, which
falsifiers remain untested (`:conclusion/falsifiers`, sharing the theorem
falsifier vocabulary `:observed | :not-observed | :untested`), and its
`:conclusion/supporting-theorem-hashes`.

### Definition rules

- `build-conclusion` requires `:conclusion/premise {:x ...}` and
  `:conclusion/result {:y ...}`; inference is always `:therefore`.
- Status vocabulary: `:established` (default) | `:qualified` | `:tentative`
  | `:contested` | `:withdrawn`.
- `conclusion-valid?` / `validate-conclusion` recompute `:conclusion/hash`
  (domain `research-conclusion`) and reject structural or hash mismatch.
- `validate-conclusion` additionally enforces: falsifier shape, well-formed
  `sha256:` supporting-theorem references, and the overreach guard — an
  `:established` conclusion with no qualifications and no scope does not
  validate.
- `conclusion-overreaches?` flags an `:established` conclusion with no
  qualifications and no scope — a finding must not overstate beyond its
  committed parameter domain.
- `verify-conclusion-support` applies the transitive commitment rule: every
  supporting theorem must resolve through a theorem resolver to a theorem whose
  own `:theorem/hash` recomputes to the claimed hash — missing or substituted
  theorems fail.
- `conclusion-collective-hash` derives the `:conclusion-root` used in
  outcome-hashes, so a researcher can challenge one finding without disputing
  the whole outcome.

### Related inference machinery

- Theorem inference rules (`research_theorem_outcome.clj`):
  `:deductive-rule`, `:counterexample-refutation`, `:statistical-inference`.
- `:implies` predicates in scenario theory and theory validation
  (`theory.clj`, `theory_validation.clj`, `research_benchmark_model.clj`), used
  to express conditional expectations over metrics/state.

## Registry contract

The benchmark registry records this artifact as:

```text
id: benchmark.conclusion
kind: benchmark.conclusion
schema_version: benchmark-conclusion.v1
importance: CORE
```

The benchmark evidence file is also a `CORE` artifact. Both paths are relative
to the declared benchmark run root. Registered suites supply benchmark
membership and deterministic execution planning; they do not themselves create
canonical top-level bundles.
