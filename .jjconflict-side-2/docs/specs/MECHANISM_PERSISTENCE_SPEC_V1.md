# Mechanism Persistence Spec V1

This spec defines the researcher-facing mechanism persistence layer for PRF.
It is derived from forensic run artifacts and does not change canonical
evidence DAG semantics.

## Purpose

The mechanism persistence layer answers a narrow question:

for each scenario and each versioned mechanism definition, what is the
observed status of the mechanism, what evidence supported that result, and
what derivation path produced the classification?

The output is derived analysis data. It is not the bundle root, not the
canonical evidence DAG, and not a replacement for evidence verification.

## Required Artifacts

Implementations SHOULD be able to read:

- a forensic run directory containing `evidence-dag-inventory.json`
- `claims/` result files
- `attestations/` result files
- a versioned mechanism map, such as `benchmarks/mechanisms/shortfall-v1.edn`
- an optional benchmark definition that provides scenario ordering

## Artifact Schemas

### Mechanism map

`mechanism-map.v1` describes the versioned mechanism definitions and their
scenario applicability.

Required fields:

- `schema-version`
- `mechanism-map/id`
- `mechanism-map/version`
- `mechanisms`
- `scenario-applicability`

Each mechanism entry SHOULD declare:

- `mechanism/id`
- `mechanism/name`
- `mechanism/domain`
- `mechanism/version`
- `concept/ids`
- `required-sources`

### Derived outputs

- `mechanism-persistence-index.v1`
- `mechanism-persistence-summary.v1`
- `mechanism-scenario-matrix.v1`

Each derived artifact MUST include `schema-version`.

## Example Shapes

### Index

```json
{
  "schema-version": "mechanism-persistence-index.v1",
  "mechanism-map/version": "mechanism-map.shortfall.v1",
  "episodes": [
    {
      "episode/id": "scenario-1:mechanism/pro-rata-fairness",
      "scenario/id": "scenario-1",
      "mechanism/id": "mechanism/pro-rata-fairness",
      "status": "passed",
      "applicability": "required",
      "episode/path": ["node-a"]
    }
  ]
}
```

### Summary

```json
{
  "schema-version": "mechanism-persistence-summary.v1",
  "episode-count": 1,
  "status-counts": {"passed": 1}
}
```

### Matrix

```json
{
  "schema-version": "mechanism-scenario-matrix.v1",
  "cells": {
    "scenario-1|mechanism/pro-rata-fairness": {
      "status": "passed",
      "applicability": "required"
    }
  }
}
```

## Status Enum

The derived status enum is:

- `passed`
- `failed`
- `not-exercised`
- `not-applicable`
- `inconclusive`
- `evidence-missing`
- `invalid-index`

`applicability` is separate from `status`.

- `applicability` describes whether the mechanism is required, optional,
  or not applicable for the scenario.
- `status` is the derived result for the evidence that was actually found.

## Required-Source Semantics

Each `required-sources` group MUST be satisfied in full.

If a group lists multiple IDs, every listed ID must resolve for the group to be
satisfied.

If attestations are listed in the mechanism map, they are required evidence.
If they are not listed, they are not required for classification.

## Canonicality Boundary

The mechanism persistence layer MAY reference:

- `:scenario/id`
- `:scenario/path`
- `:episode/path`
- `:mechanism-map/version`
- `:schema-version`

It MUST NOT overwrite or reinterpret the canonical evidence DAG root, bundle
root, or replay result semantics.

## Validation Notes

If parent-edge validation is in scope, derived episodes SHOULD include an
`episode/path` derived from the evidence DAG ancestry.

If no evidence is found for a required mechanism group, the derived status
SHOULD be `evidence-missing`.

If a scenario does not exercise a mechanism, the derived status SHOULD be
`not-exercised`.

## Example Run

```bash
python3 -m scripts.forensic.mechanism_persistence <run-dir> \
  --mechanism-map benchmarks/mechanisms/shortfall-v1.edn \
  --benchmark benchmarks/packs/prf-core/shortfall-allocation-v0.edn
```
