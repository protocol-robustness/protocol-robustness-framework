# World-Structure Workbench: State Structure

## Purpose

The workbench makes a protocol world inspectable without confusing the world,
the records used to justify it, or a view of either.

## Three layers

| Layer | Meaning | May decide protocol facts? |
|---|---|---|
| **State model** | The versioned transition rules, invariants, time semantics, and schemas that define admissible worlds. | Yes, when executed by the selected protocol/replay implementation. |
| **World instance** | One concrete state at a transition boundary, identified by its scenario/run, sequence, and applicable time. | Yes, within its model and execution context. |
| **Representation** | A derived projection, table, timeline, graph, card, or diagnostic over models, worlds, and evidence. | No; it is explanatory unless an explicit artifact contract says otherwise. |

A representation must name its source identities, selection rules, and status. It
must never be used as a substitute for replaying the model or verifying its
source artifacts.

## Authority and completeness

Authority is scoped, not global. A claim is authoritative only when its model
version, world/transition boundary, input and evidence roots, execution identity,
and verification status are available and valid for that claim. The workbench
must present `not-measured`, unavailable, and failed verification distinctly;
absence is not zero, success, or completeness.

Completeness is likewise declared: the relevant universe (worlds, scenarios,
evidence, fields, and time range), inclusion/exclusion policy, and coverage
result are part of the answer. A complete projection of a limited corpus is not
a complete statement about the protocol or market.

## Two lineages

**Transition lineage** is the causal chain: prior world + accepted action +
authorisation, time, and transition rules → next world. It is the lineage used to
establish what happened and whether it was permitted.

**Representation lineage** is the derivation chain: source worlds/evidence +
selector + transformation/version → output artifact. It establishes how a view
was produced, not that its source transition was valid. Keep the two chains
separate, while allowing representations to reference transition and evidence
roots.

## Time

Every state-bearing or state-selecting record must identify its temporal
coordinate: at minimum transition sequence and simulated clock, with the clock
basis/version where relevant. Equal timestamps do not imply equal order; sequence
or an explicit ordering rule breaks ties. Time-based views must preserve the
source coordinate and show unknown time as `not-measured`, rather than inventing
an interpolation. A timeline is a representation unless its artifact contract
explicitly grants it authority.

## Custody boundary

The workbench reports custody from the world and custody/accounting evidence; it
does not infer ownership, solvency, availability, or settlement finality from a
visual balance alone. Keep held assets, obligations/claimables, reservations,
external balances, and derived exposure in separately labelled domains. Cross-domain
claims require an explicit reconciliation or invariant result and its scope.

## Registrations

Registrations are executable/semantic dependencies, not display metadata.
Models, protocol adapters, transition identifiers, schemas, projection definitions,
validators, hash intents, and authority policies must resolve through their
versioned registries or be reported as unresolved. A rendered label cannot supply
a missing registration, and a registration does not by itself attest that a
particular run used it.

## Risk and VaR boundary

`risk-projection.v1` is an evidence-backed, scenario-separated representation of
`escrow/total-held`: rows retain evidence provenance, scenario-local deltas,
sequence, event time when trace-joined, and measured/not-measured coverage. Its
chain verification may be verified while world-transition recomputation remains
`not-measured`; those are distinct claims.

The existing `value-at-risk-timeline` is explicitly non-authoritative. The VaR
claim begins only at `scenario-distribution.v1` and `var-projection.v1`, where
the scenario universe, weighting model, outcome, coverage, and quantile method
are committed. Current VaR artifacts are empirical corpus quantiles (uniform
weights over measured scenarios), **not** a probability-weighted market forecast.
No cross-scenario sum, worst observed scenario, timeline chart, or risk card is
VaR absent that explicit distribution and VaR projection.

## Non-goals

This workbench is not:

- a new consensus, ledger, or source of protocol authority;
- a replacement for replay, transition validation, or evidence verification;
- a market-probability model, forecast, or capital/solvency decision engine;
- a tool for silently completing missing history, timestamps, registrations, or
  custody attribution; or
- a guarantee that a representation is complete outside its declared scope.
