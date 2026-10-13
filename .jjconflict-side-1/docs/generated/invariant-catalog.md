# Invariant Catalog (Generated)

Source of truth: `src/resolver_sim/definitions/registry.clj` (`invariants`).

Definitions hash: `743884729`

| Invariant ID | Label | Default Severity | Class | Related Transitions | Related Scenario Families | Artifact Field(s) |
|---|---|---|---|---|---|---|
| `appeal-requires-prior-resolution` | Appeal requires prior resolution | `high` | `safety` | `escalate_dispute` | `scenario-deep-dive`, `deadline-boundary` | `metrics.invariant-results`, `metrics.invariant-violations` |
| `appeal-reversal-detectable` | Appeal reversal detectable | `medium` | `liveness` | `escalate_dispute`, `execute_resolution` | `scenario-deep-dive`, `theory-falsification` | `metrics.invariant-results`, `metrics.invariant-violations` |
| `challenge-bond-proportional` | Challenge bond proportional to escrow value | `medium` | `economic-safety` | `challenge-resolution`, `raise-dispute` | `economic-liveness`, `theory-falsification` | `metrics.invariant-results`, `metrics.invariant-violations` |
| `conservation` | Conservation | `high` | `safety` | `create_escrow`, `release`, `execute_resolution`, `execute_pending_settlement` | `scenario-deep-dive`, `economic-solvency` | `metrics.invariant-results`, `metrics.invariant-violations` |
| `evidence-on-state-change` | Evidence on state change | `high` | `safety` | `raise_dispute`, `submit_evidence`, `execute_resolution`, `escalate_dispute` | `scenario-deep-dive`, `deadline-boundary` | `metrics.invariant-results`, `metrics.invariant-violations` |
| `finality` | Finality | `medium` | `liveness` | `release`, `execute_resolution`, `execute_pending_settlement`, `automate_timed_actions` | `scenario-deep-dive`, `deadline-boundary` | `metrics.invariant-results`, `metrics.invariant-violations` |
| `finality-blocked-during-appeal` | Finality blocked during appeal | `high` | `safety` | `execute_pending_settlement` | `scenario-deep-dive`, `deadline-boundary` | `metrics.invariant-results`, `metrics.invariant-violations` |
| `no-duplicate-dispute` | No duplicate dispute | `high` | `safety` | `raise_dispute` | `scenario-deep-dive` | `metrics.invariant-results`, `metrics.invariant-violations` |
| `resolver-decision-attributable` | Resolver decision attributable | `medium` | `safety` | `execute_resolution`, `escalate_dispute` | `scenario-deep-dive`, `collusion` | `metrics.invariant-results`, `metrics.invariant-violations` |
| `resolver-stake-proportional` | Resolver stake proportional to escrow value | `medium` | `economic-safety` | `create_escrow`, `register_stake` | `economic-security`, `theory-falsification` | `metrics.invariant-results`, `metrics.invariant-violations` |
| `solvency` | Solvency | `high` | `safety` | `create_escrow`, `release`, `execute_resolution`, `execute_pending_settlement`, `automate_timed_actions` | `economic-solvency`, `threat-detected` | `metrics.invariant-results`, `metrics.invariant-violations` |

## Interpretation

- **Failure meaning:** a failed invariant indicates a protocol property violation in simulation outputs.
- **Related transitions/scenario families:** sourced from `definitions.registry/invariant-metadata`.
- **Artifact fields:** current replay/test artifacts expose aggregate and per-invariant outcome fields under metrics.
