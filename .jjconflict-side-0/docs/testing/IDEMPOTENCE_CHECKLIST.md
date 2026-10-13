# Idempotence Checklist (Sew Protocol Model)

This checklist tracks operations that should be idempotent (or replay-idempotent), current status, and hardening plan.

## Two idempotence models

Do not conflate these — they solve different problems:

| Model | When it applies | Mechanism | Typical outcome on duplicate |
|---|---|---|---|
| **Business-logic** | Deterministic scenarios without `event-id` | State guards in lifecycle/resolution | **Reject** (`:no-resolution-to-challenge`, terminal-state guards, etc.) |
| **Replay-boundary** | External log / reorg replay with `event-id` | `contract-model.idempotency/apply-once` in `sew/dispatch-action` | **No-op** (`:extra {:idempotency :no-op-duplicate}`) |

Deterministic invariant scenarios (S46-style) rely on business-logic idempotence.  
External log replay should supply optional `event-id` (and `hop-id` for escalate/challenge) to activate replay-boundary dedupe.

Reference scenarios: `S64_replay-event-id-dedupe.json`, `S65_spe-fork-event-id-inheritance.json`, `S116_superseded-pending-repeated-keeper.json`, `S117_challenge-resolution-dedupe.json`.

Per-action dedupe key reference: `docs/replay/REPLAY_SENSITIVE_ACTIONS.md`.

---

## Current checklist

| Surface | Expected idempotence contract | Status | Notes |
|---|---|---|---|
| `clear-claimable-v2-kind` | Repeated cleanup is a no-op; no negatives; no nil claimant keys | PASS | Implemented via `dissoc` in v2 ledger |
| `clear-pending-settlement` | Repeated replacement clears stale principal effects and does not derive from legacy map | PASS | Uses `clear-stale-settlement-principal` → `clear-claimable-v2-kind` |
| `archive-pending-on-escalation` | Pending cancel clears stale principal; yield domain untouched | PASS | Same helper as pending replacement |
| `execute-pending-settlement` | Finalize once; subsequent calls rejected | PASS | Terminal + pending guards |
| `execute-fraud-slash` | Execute once; subsequent calls rejected | PASS | Status guards (`:executed`/`:appealed`/`:reversed`) |
| `unfreeze-resolver` | Multiple calls converge to same state | PASS | Idempotent state overwrite |
| `rotate-dispute-resolver` | Same target rotation should not create duplicate audit events | PASS | Business-logic `:idempotent?` flag; replay dedupe when `event-id` present |
| `escalate_dispute` / `challenge_resolution` | Replay-idempotent under duplicate ingestion (same logical tx) | PASS* | *When `event-id` present; otherwise business guards apply |
| `replay-sensitive-actions` (8+ actions) | Replay dedupe when `event-id` present | PASS* | See `sew/replay-sensitive-actions` |
| superseded pending fallback | Repeated keeper execution over superseded history cannot re-finalize | PASS | Guarded by terminal/pending logic; verified by `checklist-superseded-pending-single-finalization` test + S116 fixture |
| SPE fork continuations (`cont-events`) | Main-line tail replayed after deviation | PASS | Uses `:world-checkpoints` + normalized replay events; `:event-identity :inherit-from-main-trace` |
| `force-reversal-slash` | Duplicate call returns world unchanged; no compound slash | PASS | Guarded by `:slash-by-context` check at `resolution.clj:323`; verified by `force-reversal-slash-idempotent` test + DR-P-002 scenario |
| cross-layer (business + replay) | Replay-boundary dedup takes precedence over business-logic guard on duplicate | PASS | Verified by `checklist-cross-layer-idempotence` test in `idempotence_checklist_test.clj` |

---

## Shared helper module

Namespace: `resolver-sim.contract-model.idempotency` (protocol-agnostic)

- `apply-once` — apply `apply-fn` at most once per `op-key`; records keys in `:idempotency/applied`
- `applied?` / `mark-applied` / `ensure-not-duplicate`

Dedupe op-key shape (built in `sew/dedupe-op-key`):

```clojure
[:sew :replay-dedupe action agent workflow-id slash-id hop-scope event-id]
```

Suggested patterns from the original checklist still apply:

- `[:rotate workflow-id new-resolver at-time]`
- `[:escalate workflow-id level caller event-id]`
- `[:challenge workflow-id level caller event-id]`
- `[:settle workflow-id pending-hash]`

`hop-scope` is explicit `hop-id` when provided, otherwise current dispute level for escalate/challenge.

---

## Replay params (interface contract)

Optional event params (kebab-case internal, snake_case on wire):

| Param | Purpose |
|---|---|
| `event-id` / `event_id` | Logical transaction identifier; gates replay-boundary dedupe |
| `hop-id` / `hop_id` | Escalation hop scope when the same `event-id` spans multiple levels |

See `docs/architecture/interface-contract.md` for wire mapping.

---

## Fork replay (SPE counterfactuals)

Counterfactual fork replay (`subgame-counterfactual/expand-strategic-tree`):

- Fork world: full state from `:world-checkpoints` at decision `:seq` (not lean trace snapshots)
- Continuation events: normalized via `replay/trace-entry->replay-event`
- Event identity: `:event-identity :inherit-from-main-trace` (continuations keep main-line `:params`)
- Idempotency: `:idempotency-state :inherit-checkpoint` (only pre-fork `:idempotency/applied` keys)

Stale continuation rejections are tagged `:fork/stale-continuation` (business guards, not dedupe).

---

## Phased plan (updated)

1. ~~**Replay-boundary dedupe**~~ — implemented for `replay-sensitive-actions` when `event-id` present.
2. ~~**Fork world completeness**~~ — `:world-checkpoints` on replay results; SPE uses checkpoints.
3. ~~**Audit-grade trace consistency**~~ — exported traces surface `:extra {:idempotency ...}` via `attributes.idempotency` and `metadata.idempotency`.
4. ~~**Policy enforcement**~~ — optional `:require-event-id?` replay flag (`:external-log-replay-flags` preset) for external-log ingestion paths.
5. ~~**Superseded-pending regression** — explicit fixture for repeated keeper calls over superseded history.~~
