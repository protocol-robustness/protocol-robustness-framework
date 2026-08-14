# Research command and incentive-analysis artifacts

## Status and boundary

`research-command.v2` is the authoritative format for new researcher-authored
commands. `research-command.v1` remains readable only for compatibility; it is
not a migration target and its `:command/include` representation is never
silently reinterpreted as v2.

This document describes immutable, content-addressed artifacts. It does **not**
introduce an execution runner, pack runner, or publication path. A future shared
benchmark lifecycle must validate and publish these artifacts inside its normal
content registry, package index, finalization, and completion boundary.

## Artifact chain

```text
research-command.v2
  ├─ incentive-model.v1
  ├─ incentive-deviation-domain.v1
  └─ requested typed analysis scopes
        ↓
research-assignment.v1 binds the exact command root
        ↓
benchmark-outcome.v1 binds each requested output root
        ↓
research-analysis-closure.v1 verifies the complete relation
```

The command commits argv, environment, runner, input, and output roots, and
typed `:command/includes` scope references. The assignment adds an immutable
request/runner-plan provenance binding; reassignment is a new artifact/root.
The closure verifies concrete command/model/domain/assignment/manifest objects;
it does not trust a caller-provided root or `:verified?` boolean.

## Incentive model and deviation domain

`incentive-model.v1` commits the subject, participant roles, payoff
interpretation, reward/penalty/cost maps, referenced policy roots, and evaluator
semantics root. Changing any of those semantic fields changes its root.

`incentive-deviation-domain.v1` commits the same subject and the exact model
root, plus baseline strategy, participant set, deviation set, coalition scope,
constraints, and evaluation method. The currently supported method is
`:observed-single-trace`.

That method produces `:evidence/observed-single-trace`. A passing observed
single trace means only that no tracked adversarial attempt succeeded and no
tracked funds loss occurred in that trace. It does **not** prove that no
participant can profitably deviate over the declared domain, and it cannot set
`:research-analysis/general-ic-proven?` to true. Counterfactual or exhaustive
methods must be added as separately versioned, supported methods before they
can support stronger claims.

## Library lifecycle

A caller constructs the following immutable values:

1. `research-command/build-command` with `:schema-version "research-command.v2"`;
2. `incentive-model/build-model`;
3. `incentive-deviation-domain/build-domain`, referring to that model root;
4. `research-assignment/build-assignment`, referring to the command root;
5. an existing `benchmark-outcome.v1` manifest whose requested output roots are
   complete; and
6. `research-analysis-closure/verify-closure` to derive the validation report.

A valid closure commits the command, model, deviation-domain, assignment,
outcome, and requested output roots. Missing requested outputs, mismatched
model/domain subjects, command/assignment mismatch, malformed artifacts, and
unsupported methods are invalid and fail closed.

## Reproduction and future lifecycle integration

Reproduction must reload the persisted command, model, domain, assignment, and
canonical benchmark inputs, execute through the shared benchmark lifecycle, and
compare **semantic roots**. This repository does not yet expose that run/replay
command because pack/suite execution is outside this artifact-only change.

The future publication integration point is one verification step before the
existing canonical package finalization. It should store the concrete artifacts
in the content registry, invoke `verify-closure`, then commit the derived
closure root into the package index/finalization. A later multi-verifier layer
may derive `verified`, `inconclusive`, `incompatible`, or `disputed` from signed
attestations; no such status is declared by this artifact.
