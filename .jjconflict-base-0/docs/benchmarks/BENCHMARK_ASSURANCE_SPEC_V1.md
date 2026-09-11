# Benchmark assurance and finalization v1

## Purpose

A canonical benchmark bundle separates exhaustive inventory, semantic assurance,
cryptographic finalization, and terminal lifecycle state.

```text
benchmark/assertions/conservation.json
  benchmark-conservation.v1
benchmark/assertions/benchmark-assurance.json
  benchmark-assurance.v1
benchmark/finalization.json
  benchmark-finalization.v1
completion.json
  benchmark-completion.v1
```

## Authorities

- Sew execution owns the canonical `:conservation-of-funds` invariant result.
- `benchmark-conservation.v1` aggregates and hash-references those execution results.
- `benchmark-assurance.v1` asserts required artifact presence and binds conservation.
- Finalization commits to finalized hashes without becoming part of the registry it commits to.

## Conservation statuses

`pass`, `fail`, `not-exercised`, and `incomplete` are derived from the expected
execution set and the referenced invariant results. Missing or malformed expected
evidence is `incomplete`, never `not-exercised`.

## Input-set commitment

`benchmark-assurance.v1` contains an `input_set` and a domain-separated
`input_set_root`. Each input entry has a stable logical ID, source kind,
root-relative snapshot path, and SHA-256 hash. For the current bundled benchmark
contract, the required input set is:

- the benchmark definition snapshot (`benchmark/definition.edn`);
- the frozen execution plan (`benchmark/execution-plan.edn`); and
- one immutable scenario-input snapshot for every planned child execution under
  `benchmark/executions/<execution-id>/input/`.

The root is calculated over a canonically path-sorted projection of each entry's
`logical_id`, `source_kind`, `path`, and `sha256`. It commits to replay inputs,
not to derived conclusions, assertions, registry records, or lifecycle markers;
those are bound by the finalization chain separately.

This v1 contract is intentionally limited to inputs persisted in the bundle.
It is **not** a transitive external-pack commitment yet: caller-supplied packs,
concept/scoring definitions, configuration, and referenced data must first be
recursively resolved and snapshotted before external benchmark packs can be
represented as reproducible canonical bundles. Accordingly, canonical
`run-benchmark --run-root` rejects filesystem manifest paths for now; use a
registered benchmark ID or a bundled `classpath:` manifest.

## Finalization and completion

`final_ref` is a domain-separated hash over the benchmark ID, run ID, assurance
hash, conclusion hash, registry hash, validation hash, and `input_set_root`.
It excludes itself. `completion.json` is written last and repeats the finalization
hash, `final_ref`, registry commitments, and `input_set_root`.

## Verification

```bash
java -jar target/prf-runner-sew-0.1.0-uber.jar \
  verify-benchmark --run-root <completed-root>
```

Verification is read-only. It checks terminal hashes, recomputes the input-set
root, verifies that every committed input is a contained on-disk file with the
recorded hash, verifies assurance-to-conservation binding, and recalculates
conservation from hashed execution summaries.

Caller-supplied external benchmark packs and optional `:funds-ledger-view`
reconciliation remain future work.
