# Evidence ↔ simulator invariant mapping

Reference Validation Suite v1 uses **evidence invariant IDs** (kebab-case strings in
`suites/reference-validation-v1/manifest.edn`). The Sew simulator uses **canonical
invariant IDs** (keywords in `resolver-sim.protocols.sew.invariants/canonical-ids`).

The authoritative mapping lives in
`src/resolver_sim/sim/reference_validation_evidence.clj`. CI calls
`verify-evidence-invariants!` after each simulator-backed replay:

1. Replay outcome must be `:pass` (all transition checks ran on successful steps).
2. Every mapped **world-level** canonical ID must hold on the final `:world`.
3. Mapped **transition-level** IDs are implied by (1).

| Evidence ID | Canonical simulator IDs |
|---|---|
| `active-escrow-module-snapshot-immutable` | `:module-snapshot-immutable` |
| `governance-forward-only` | `:module-snapshot-immutable`, `:escalation-level-monotonic` |
| `slashable-liability-preserved` | `:solvency`, `:bond-slash-bounded`, `:liability-slash-boundary`, `:conservation-of-funds` |
| `bounded-progress-under-load` | `:no-stale-automatable-escrows`, `:resolver-capacity` |
| `liability-gated-withdrawal` | `:no-withdrawal-during-dispute`, `:bond-liquidity`, `:held-delta-accounted` |
| `no-double-settlement` | `:single-resolution-payout-consistent`, `:cancellation-mutex`, `:pending-settlement-consistent`, `:terminal-states-unchanged` |
| `pull-first-value-flow` | `:settlement-principal-boundary`, `:settlement-yield-boundary`, `:liability-slash-boundary`, `:bond-boundary`, `:fee-boundary`, `:claimable-classification` |
| `escalation-layer-protection` | `:escalation-level-monotonic`, `:dispute-level-bounded`, `:dispute-resolution-path` |

Rejected illegal transitions are tracked as replay metrics (`:invalid-state-transitions`),
not as canonical invariant IDs. See `docs/ROBUSTNESS_FRAMEWORK.md`.
