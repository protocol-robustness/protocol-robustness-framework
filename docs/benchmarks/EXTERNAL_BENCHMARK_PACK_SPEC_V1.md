# External benchmark-pack contract v1

## Status

**Design contract; not enabled by the canonical runner yet.**

Canonical `run-benchmark --run-root` currently accepts registered benchmark IDs
and bundled `classpath:` manifests only. It rejects filesystem manifest paths
until this contract is implemented end to end.

## Goal

A caller can supply a benchmark pack to the full Sew runner without granting it
implicit access to repository-relative files or producing an incomplete input
commitment. Every executable or claim-relevant input must be resolved before
replay, copied below the exact run root, and committed by `input_set_root`.

## Pack boundary

A pack is rooted at the directory containing its entry manifest. Its manifest
is supplied explicitly:

```bash
java -jar prf-runner-sew-0.1.0-uber.jar \
  run-benchmark /absolute/path/to/pack/benchmark.edn \
  --run-root /absolute/path/to/run
```

Relative invocation is permitted only after resolving the manifest to a
canonical filesystem path. Every pack-relative reference is resolved from the
entry manifest's parent directory, never from the process working directory.

No declared input may resolve outside the pack root. Symlink resolution must be
performed before the containment check. Absolute paths, `..` escapes, and URI
schemes other than an explicitly supported pack-local file reference are
rejected for external packs.

## Required manifest shape

External manifests use an explicit input declaration rather than relying on
repository suite keywords or directory discovery:

```clojure
{:benchmark/schema-version "external-benchmark-pack.v1"
 :benchmark/id "example/custody-v1"
 :benchmark/protocol :protocol/sew
 :benchmark/scenarios
 [{:scenario/id "custody-basic"
   :scenario/file "scenarios/custody-basic.edn"}
  {:scenario/id "custody-expiry"
   :scenario/file "scenarios/custody-expiry.edn"}]
 :benchmark/claims [...]
 :benchmark/concepts ["concepts/custody.edn"]
 :benchmark/scoring {:file "scoring/claims.edn"}
 :benchmark/data [{:id "parameters" :file "data/parameters.edn"}]
 :benchmark/config {:file "config/replay.edn"}}
```

The external schema must reject unknown dependency-bearing fields. Optional
metadata fields may be ignored only when they contain no file or URI reference.

` :benchmark/scenario-suite` and `:scenario-suites` are not valid external-pack
execution inputs. They are repository/bundled registry mechanisms. An external
pack enumerates its scenarios directly, allowing the full planned execution set
to be frozen without discovery.

## Dependency closure

The resolver creates a deterministic input plan before any run-root mutation.
Each plan entry is:

```json
{
  "logical_id": "scenario/custody-basic",
  "source_kind": "external-pack-scenario",
  "origin": "scenarios/custody-basic.edn",
  "pack_relative_path": "scenarios/custody-basic.edn",
  "sha256": "sha256:...",
  "bytes": 1234
}
```

Required entries are:

1. entry benchmark manifest;
2. every declared scenario file;
3. every declared concept, scoring, data, and configuration input;
4. every recursively declared include/import supported by the relevant input
   schema.

Unsupported includes/imports fail closed. A directory is never an input;
directory traversal, glob expansion, latest-run selection, and mtime discovery
are forbidden.

The resolver rejects duplicate logical IDs, duplicate canonical paths assigned
to incompatible roles, path-prefix collisions in snapshot destinations, missing
files, non-regular files, and duplicate scenario IDs.

## Snapshot layout

Before replay, the canonical runner copies the input plan byte-for-byte:

```text
<run-root>/
  inputs/
    benchmark/definition.edn
    scenarios/<sha256-prefix>-<basename>.edn
    concepts/<sha256-prefix>-<basename>.edn
    scoring/<sha256-prefix>-<basename>.edn
    data/<sha256-prefix>-<basename>.edn
    config/<sha256-prefix>-<basename>.edn
  benchmark/
    definition.edn
    execution-plan.edn
```

`benchmark/definition.edn` is the frozen entry-manifest projection. Input
provenance records the original pack-relative reference, never an uncontrolled
absolute host path in public output.

Legacy replay code requiring a path receives the immutable snapshot path, not
the caller's original file path.

## Commitment and finalization

`benchmark-assurance.v1.input_set` includes every resolved input-plan entry and
the frozen execution plan. The canonical domain-separated `input_set_root` is
calculated from sorted:

```text
logical_id, source_kind, path, sha256
```

The verifier must:

1. require the expected logical input set;
2. verify every snapshot path is root-relative and contained;
3. verify every hash;
4. recompute `input_set_root`;
5. verify planned execution descriptors reference the corresponding scenario
   snapshot hash; and
6. reject an external-pack bundle whose input closure is incomplete.

`input_set_root` remains an input commitment. Derived artifacts remain committed
through the artifact registry, benchmark finalization, `final_ref`, and terminal
`completion.json`.

## Sensitivity

The external manifest and every snapshot are scanned under the selected
sensitivity profile before registry finalization. Public reports may identify a
logical input and policy category but must not reproduce secret values or
absolute caller paths.

## Implementation gates

1. Implement a pure resolver and unit tests for containment, symlinks,
   duplicates, invalid fields, and stable plan ordering.
2. Add byte-preserving snapshot writes and provenance records.
3. Execute external scenarios from snapshots through the shared execution
   kernel; remove all CWD-relative loading.
4. Include the full closure in assurance and verifier reconstruction.
5. Add source and built-JAR external-CWD acceptance tests using a disposable
   external fixture pack.
6. Only then remove the canonical filesystem-manifest quarantine.
