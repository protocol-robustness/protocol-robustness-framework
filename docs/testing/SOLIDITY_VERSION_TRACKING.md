# Solidity Version Tracking

This document records how the Sew simulator model (`resolver-sim`) relates to the
sew-protocol Solidity contracts over time, and how to tell which Solidity revision any
given model trace / scenario / benchmark refers to.

## Model model: one model, pinned by provenance

The simulator is a **single evolving model** (not forked into "old" and "new" variants).
The old model was changed in place. Version fidelity is carried by **provenance metadata**
on trace fixtures and scenarios, not by maintaining a second model codebase.

Each trace fixture records:
- `generated_by` — the simulator that produced it (e.g. `resolver-sim/cdrs-0.2`).
- `sew_protocol_revision` — the sew-protocol commit or tag the expected behavior targets.
- `sew_protocol_branch` — the sew-protocol branch (`main` or `appeal-primitive`).

These fields are stamped by `scripts/trace-solidity-sync.py` and required by
`scripts/trace-solidity-verify`.

## Version timeline

| Phase | sew-protocol branch | tag | Solidity change | Model status |
|---|---|---|---|---|
| Stable baseline | `main` | `solidity-stable-2026-10` | Long period with no contract changes, then minor fixes only. | Most model testing has run here. Scenarios/traces generated against this are the thoroughly-tested reference. |
| appeal-primitive | `appeal-primitive` | `appeal-primitive-v1-2026-10` | Comprehensive change: same overall architecture, but **contract additions** and some **function/event renames**. | Current default. Traces/scenarios must be re-validated against this revision and stamped with the tag. |

## How to tell which Solidity revision a result applies to

1. **Trace fixtures** — read `sew_protocol_revision` / `sew_protocol_branch` from the fixture JSON.
2. **Scenario fixtures** — carry a `sew_revision` field (see per-version organization below).
3. **Benchmark results** — manifests record the `sew_protocol_revision` they were run against.
4. **Invariant mapping** — `docs/testing/SIMULATOR_TO_SOLIDITY_INVARIANT_MAPPING.md` states the
   target revision in its Version provenance block.

## Separation of scenarios / results

Scenario fixtures and benchmark results should be organized by the Solidity revision they
were validated against. Where a natural directory grouping exists this follows the existing
`test/foundry/traces/v2/` pattern; otherwise each fixture carries a `sew_revision` field.

## Stability assumption

Because the contract surface was stable for a long period, most historical model testing is
valid against `solidity-stable-2026-10`. The appeal-primitive change introduces new contract
surface and renames, so:
- Scenarios exercising unchanged core behavior are inherited (re-validated against appeal-primitive).
- Scenarios exercising added/renamed surface must be regenerated and re-stamped for `appeal-primitive-v1-2026-10`.

## Ongoing gate

- `scripts/trace-solidity-verify` fails if a trace fixture lacks provenance, so syncing
  always records the target Solidity revision.
- When the sew-protocol default branch advances, re-tag the new tip and update the
  Version provenance table above before regenerating traces.
