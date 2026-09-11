# Scenario Contract Reference (Generated)

Source of truth: `src/resolver_sim/scenario/schema_profile.clj`, `schemas/scenario-v1.json`, replay validation rules.

## Supported Schema Versions

- `1.0`
- `1.1`

Enriched/default reporting version: `1.1`

## Required Fields by Version Profile

| Version | Required fields |
|---|---|
| `1.1` | `id`, `title`, `purpose`, `scenario-author` |

## Purpose-specific Validation Constraints

| Purpose | Requires theory block | Requires theory OR expectations |
|---|---|---|
| `adversarial-robustness` | no | yes |
| `theory-falsification` | yes | no |

## JSON Scenario v1 Required Top-level Fields

- `schema_version
- `scenario_id
- `seed
- `agents
- `events

## JSON Scenario v1 Top-level Properties

| Property |
|---|
| `agents` |
| `events` |
| `initial_block_time` |
| `protocol_params` |
| `scenario_id` |
| `schema_version` |
| `seed` |

## Notes

- Event sequence must be contiguous (`0..n-1`) and monotonic in time.
- Event agents must exist in the scenario `agents` array.
- Unknown metric references in expectations/theory are rejected by replay validation.
- `theory-falsification` requires `:theory`; `adversarial-robustness` requires theory or non-trivial expectations.
