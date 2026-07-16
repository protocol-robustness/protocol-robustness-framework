# Benchmark conclusion projection v1

## Purpose

`manifest/benchmark-conclusion.json` is the compact, deterministic conclusion
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
    "id": "benchmark/sew-force-authorisation-custody-v1",
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

## Registry contract

The benchmark registry records this artifact as:

```text
id: manifest.benchmark-conclusion
kind: benchmark.conclusion
schema_version: benchmark-conclusion.v1
importance: CORE
depends_on: benchmark.evidence
```

The benchmark evidence file is also a `CORE` artifact. Both paths are relative
to the declared benchmark run root.
