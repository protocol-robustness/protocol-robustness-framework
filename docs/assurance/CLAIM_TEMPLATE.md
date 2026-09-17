# Assurance Claim Template

Use this template before presenting a PRF result as assurance evidence.

## Claim

- Claim:
- Subject and version:
- Protocol/adaptor:
- Claim owner:

## Model and Policy

- State and successor rules:
- Admission policy and authority:
- Economic equations and assumptions:
- Game-theoretic model, if applicable:
- Game-theoretic calibration, search bounds, and coverage, if applicable:

## Evidence

- Workload and scenario identifiers:
- Invariants and closed-form checks:
- Closed-form model/version and required branch coverage:
- Independent reconstruction/verifier:
- Cross-implementation or deployment scope:
- Evidence/artifact roots:

## Boundaries

- Covered cases:
- Omitted cases:
- Unexercised branches:
- Operational dependencies:
- Residual risks:

## Publication Decision

Select exactly one classification and explain why:

| Classification | Meaning | Minimum publication statement |
| --- | --- | --- |
| Implementation evidence | Code or a runnable example exists | State the implemented behavior; do not imply a general assurance claim |
| Bounded research evidence | A declared model and workload produced reproducible results | State assumptions, workload, coverage limits, and residual risks |
| Claimable assurance evidence | An admission policy accepts the complete, rooted evidence package for the stated claim | State the authority, verifier, policy, evidence roots, and exact claim scope |

Before publication, confirm that every field above is populated or explicitly
marked not applicable with its reason. Do not use “SPE”, “secure”, “fair”, or
“production-ready” without defining the exact scope that supports the term.
