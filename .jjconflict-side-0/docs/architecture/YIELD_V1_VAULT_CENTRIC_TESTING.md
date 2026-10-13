# Architecture Note: Yield-v1 Vault-Centric Testing

## Overview

This note documents the approach for testing yield modules and vaults independently of the Sew escrow protocol. 

The original simulator was designed around the concept of a `workflow-id` representing a single Sew escrow. While this remains the primary identity for Sew-integrated scenarios, it proved restrictive for general yield module testing where multiple owners, shared liquidity pools, and market-wide shocks are the focus.

## Identity Models

| Context | Primary Identity | Description |
| :--- | :--- | :--- |
| **Sew Scenarios** | `workflow-id` | Maps to a specific escrow transfer. |
| **Yield-v1 Scenarios** | `owner-id`, `module-id`, `token` | Maps to a specific position in a vault. |

### Why not use `workflow-id` for Yield?
Vault-centric tests often involve "test subjects" that do not map to an escrow:
1.  **Multiple Owners:** Testing how a single shortfall ratio affects two different deposits in the same vault.
2.  **Market Shocks:** Testing how a change in APY or liquidity status affects all positions.
3.  **Governance Actions:** Testing recovery or pause/unpause logic.

Forcing these through a `workflow-id` (e.g., `[:sew/escrow 0]`) adds unnecessary boilerplate and conflates Sew financial finality with vault state.

## Implementation: The "Thin Runner" Approach

Instead of refactoring the entire replay kernel to support a generic resource-handle abstraction, we leverage the existing "Thin Runner" for `yield-v1`.

- **Replay Kernel:** `contract_model.replay/replay-yield-scenario` provides a sequential execution path that bypasses Sew-specific aliasing and batching logic.
- **Protocol Adapter:** `protocols.yield` (YieldProviderProtocol) focuses exclusively on yield operations (`yield_deposit`, `yield_accrue`, `yield_withdraw`, `set-yield-risk`).
- **Metrics Profile:** The `:yield-provider` metrics profile calculates aggregate vault-level statistics (total principal, total held, module state) which are not easily expressed in the escrow-centric profile.

## Decision Record

Decision: Path A — First-Class Yield-v1 Vault-Centric Testing
**Status:** Implemented.
**Rejected for now:** Full identity abstraction (`active-resources`, `save-resource-as`).
**Reason:** The `yield-v1` thin runner already supports the required test subjects (`owner-id`, `module-id`) with surgical patches to snapshots and metrics. A large refactor would introduce risk to stable Sew regression suites without immediate functional benefit. This began as a workaround for workflow-id leakage, but the resulting design is now the preferred boundary: Sew scenarios test escrow lifecycle behavior; yield-v1 scenarios test vault/module behavior directly.

## New Regression Coverage (Y01–Y04)

These scenarios validate the data-driven model and protect against previous implementation gaps:

| Scenario | Purpose | Primary Identity | Main Assertion | Regression Protected |
| :--- | :--- | :--- | :--- | :--- |
| **Y01** | Shared Vault Liquidity | `owner-id` | Multi-owner principal aggregation. | Balance overwriting in `sync-held`. |
| **Y02** | Shortfall/Partial Withdraw | `module-id` | Correct `unwinding` status and deferred amount. | Missing `world-snapshot` risk state. |
| **Y03** | Risk Override | `token` | `set-yield-risk` beats `liquidity-schedule`. | Y03 proves dynamic scenario events take precedence over scheduled market state when both exist.. |
| **Y04** | Recovery Lifecycle | `owner-id` | Full cycle: Shortfall -> Recovery -> Claim. | Missing `yield_recover_liquidity` action. |

## Deferred Work
- **Batching:** `yield-v1` currently runs in sequential mode. If high-concurrency yield testing is required, conflict domains for `[:yield/module module-id token]` will be added.
- **Unified Identity:** A later transition to a `Resource-based Handle` model may be considered if Sew and Yield logic must be tightly integrated in the same scenario without the "Thin Runner" bypass.

## Guardrails

Do not migrate yield-v1 scenarios back through Sew workflow events merely to reuse existing escrow lifecycle machinery
Do not introduce `workflow-id` into yield-v1 scenario JSON unless the scenario is explicitly testing Sew integration.
Do not implement `active-resources`, `created-resources`, or `save-resource-as` unless a new scenario proves the thin runner cannot express the required behavior.

## Integration Boundary

Sew-integrated yield scenarios may still create yield positions through a `workflow-id`, because the escrow lifecycle is the subject of the test.

However, once testing concerns move to provider behavior — liquidity ratios, APY schedules, shortfall, recovery, partial withdrawal, or vault-wide aggregation — the preferred test surface is yield-v1.

In other words:

- use Sew scenarios to test protected transfer lifecycle behavior;
- use yield-v1 scenarios to test provider/vault behavior;
- use integration scenarios only when the interaction between the two is the subject.

