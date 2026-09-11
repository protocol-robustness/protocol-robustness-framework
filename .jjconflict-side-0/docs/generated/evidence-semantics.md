# Evidence Semantics Reference (Generated)

Source of truth: `src/resolver_sim/definitions/registry.clj`, `src/resolver_sim/scenario/outcome_semantics.clj`.

Definitions hash: `743884729`

## Statuses

| Status ID | Label | Story Family Mapping |
|---|---|---|
| `falsified` | Falsified by this replay | `theory-falsification` |
| `inconclusive` | Inconclusive: evidence incomplete or invalid | `scenario-deep-dive` |
| `not-applicable` | Not applicable | `scenario-deep-dive` |
| `not-evaluated` | Not evaluated: no theory block | `scenario-deep-dive` |
| `not-falsified` | Not falsified in this replay | `scenario-deep-dive` |

## Severities

| Severity | Rank |
|---|---:|
| `critical` | 4 |
| `high` | 3 |
| `low` | 1 |
| `medium` | 2 |

## Purposes

| Purpose | Label | Default Story Family | SPEDS Kind |
|---|---|---|---|
| `adversarial-robustness` | Adversarial Robustness | `threat-detected` | liveness_risk |
| `regression` | Regression | `scenario-deep-dive` | regression |
| `theory-falsification` | Theory Falsification | `theory-falsification` | expected_negative |
| `unclassified` | Unclassified (v1.0) | `scenario-deep-dive` | inconclusive_result |

## Confidence Levels

| Level | Score |
|---|---:|
| `high` | 0.9 |
| `low` | 0.3 |
| `medium` | 0.6 |

## Story Families

| Story Family | Label |
|---|---|
| `collusion` | Collusion |
| `deadline-boundary` | Deadline boundary |
| `deflection` | Deflection |
| `economic-solvency` | Economic solvency |
| `scenario-deep-dive` | Scenario deep dive |
| `theory-falsification` | Theory falsification |
| `threat-detected` | Threat detected |
