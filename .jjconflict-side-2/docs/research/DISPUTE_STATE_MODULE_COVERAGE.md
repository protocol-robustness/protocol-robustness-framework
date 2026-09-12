# Dispute State & Resolution Module Coverage Review

**Audience:** First-time researcher. No prior codebase knowledge assumed.

**Date:** 2026-06-19

This document reviews coverage of two orthogonal aspects of the dispute
resolution system:

1. **Dispute state machine** — the set of states an escrow can be in, the
   allowed transitions between them, the guards that gate each transition,
   and which transitions are exercised by existing scenarios.
2. **Resolution module** — the mechanism that authorises a resolver to rule
   on a dispute, how different module types are configured, how escalation
   is wired, and which module configurations are covered by tests.

The review identifies gaps in both dimensions and assesses risk.

---

## 1. Dispute State Machine

### 1.1 States

The EscrowState enum (`types.clj:82`) defines six states:

| State | Class | Meaning |
|-------|-------|---------|
| `:none` | Initial | Pre-creation sentinel |
| `:pending` | Live | Escrow active; can be disputed, released, refunded, or cancelled |
| `:disputed` | Live | Dispute raised; awaiting resolution |
| `:released` | Terminal | Funds released to recipient |
| `:refunded` | Terminal | Funds refunded to sender |
| `:resolved` | Terminal | Accounting-settled (not reachable in production — see §1.3) |

### 1.2 Allowed transition graph

From `types.clj:84-97`:

```
:none ──→ :pending

:pending ──→ :disputed    (raise_dispute)
:pending ──→ :released    (release)
:pending ──→ :refunded    (sender_cancel, recipient_cancel)

:disputed ──→ :released   (execute_resolution is-release=true, auto-release)
:disputed ──→ :refunded   (execute_resolution is-release=false, auto_cancel_disputed)
:disputed ──→ :resolved   (execute_transition → :to-resolved; NOT production-reachable)

:released ──→  (terminal, no outgoing edges)
:refunded ──→  (terminal, no outgoing edges)
:resolved ──→  (terminal, no outgoing edges)
```

### 1.3 Transition coverage per edge

| Transition | Scenarios | Coverage |
|------------|-----------|----------|
| `:none → :pending` | ALL scenarios | ✅ Full |
| `:pending → :disputed` | Every scenario that calls `raise_dispute` (S02–S100+) | ✅ Full |
| `:pending → :released` | S01, S02, S05, S07, S08, S09, S10, S11, S12a/b, S16, S23, S25, S28, S30, S31, S34–S37, S50–S100+ | ✅ Well covered |
| `:pending → :refunded` | S06 (mutual cancel), **S-DR-055** (sender-cancel) | ✅ Covered |
| `:disputed → :released` | S02, S05, S07, S08, S09, S10, S11, S14, S15, S23, S25, S28, S30, S31, S34–S37, S43, S44, S45, S46a, S47a/b, S48, S49, S50–S100+, S-DR-001, S-DR-030, S-DR-031 | ✅ Well covered |
| `:disputed → :refunded` | S03, S04, S13, S17, S26, S27, S29, S32, S33, S52, S93, S94, S101–S107 | ✅ Covered |
| `:disputed → :resolved` | **NONE** | ❌ Uncovered |

**Gap: `:disputed → :resolved`** is defined in the state machine but never
exercised. Code comments (`types.clj:89-91`) acknowledge this:
> _`:resolved` — defined in enum and library; no production call site currently
> reaches it._

This is an intentional dead transition — the protocol resolves disputes directly
to `:released` or `:refunded`, and `:resolved` exists only for enum completeness.
**No action required.** Adding a scenario for this would test dead code.

### 1.4 Guard coverage

Each transition is gated by guards defined in `state_machine.clj:161-198` and
`resolution.clj`. The guard-checking functions mirror the Solidity contract
guards.

| Guard | Used by | Scenarios exercising rejection | Coverage |
|-------|---------|-------------------------------|----------|
| `:participant?` | `raise_dispute` | S08 (non-participant rejected) | ✅ |
| `:authorized-resolver?` | `execute_resolution` | S07 (unauthorized), S15 (wrong module), S43 (auth recovery) | ✅ |
| `:state-pending` / `:state-disputed` | Various | S08 (state machine gauntlet) | ✅ |
| `:no-resolution-to-appeal` | `escalate_dispute` | S23, S32 | ✅ |
| `:appeal-window-expired` | `escalate_dispute`, `challenge_resolution` | S28 (late escalation), S73 (late challenge) | ✅ |
| `:appeal-window-not-expired` | `execute_pending_settlement` | S-DR-040 (premature settlement), S32, S36, S51s, S74 | ✅ |
| `:pending-exists` | `execute_pending_settlement` | S05, S10, S-DR-042 (no pending) | ✅ |
| `:escalation-not-configured` | `escalate_dispute`, `challenge_resolution` | S77 (no escalation resolvers configured) | ⚠️ Only S77 for escalate, no scenario for challenge |
| `:resolver-capacity-exceeded` | `raise_dispute` | S62-resolver-capacity | ✅ |
| `:final-round?` | `escalate_dispute` | S20, S31 (max level guard) | ✅ |
| `:max-dispute-duration` / timeout | `auto_cancel_disputed` | S04, S17, S24, S55, S60, S94 | ✅ |

**Gap: `challenge-resolution` with `nil escalation-fn`** — The
`:escalation-not-configured` path is tested for `escalate_dispute` (S77) but
never for `challenge_resolution`. Both dispatch to `escalation-fn` and both
return the same error. **Low risk** — the code path is identical.

### 1.5 Edge: `automate_timed_actions` never called as a named action

The `automate-timed-actions` function (`resolution.clj:632-660`) multiplexes
between:
1. Execute pending settlement (if appeal window expired)
2. Auto-release escrow (if auto-release time reached)
3. Auto-cancel escrow (if `max-dispute-duration` elapsed)

Individual branches are tested via direct keeper calls (`execute_pending_settlement`,
`release`, `auto_cancel_disputed`), but the multiplexer itself is never invoked
with action name `"automate_timed_actions"`. This is because scenarios call the
specific keeper actions directly for clarity and determinism.

**Risk: Low** — the multiplexer is simple priority logic. A regression here
would also be caught by the direct calls since the multiplexer dispatches to
the same functions.

---

## 2. Resolution Module Coverage

### 2.1 Module architecture

The resolution-authorisation chain has three priority levels
(`authority.clj:75-112`):

```
Priority 1: custom-resolver (in escrow settings)
  → exclusive: if set, only this address may resolve

Priority 2: resolution-module (in module snapshot)
  → consult resolution-module-fn; if !authorized, fall through to Priority 3

Priority 3: dispute-resolver (on EscrowTransfer)
  → caller must equal et.disputeResolver
```

### 2.2 Module configuration modes

The `build-execution-context` function (`sew.clj:754-775`) creates different
combinations of `resolution-module-fn` and `escalation-fn` based on protocol
params:

| Mode | `resolution-module` param | `escalation-resolvers` param | `rm-fn` | `esc-fn` | Tested by |
|------|--------------------------|------------------------------|---------|----------|-----------|
| **A. Direct resolver** | nil | nil | nil | nil | dr3 preset: S01, S02, S03, S04, S06, S07, S08, S09, S10, S11, S16, S17, S22, S24, S43, S57, S64, S70, S80 |
| **B. Single-address module** | `"0x..."` | nil | `make-default-resolution-module` | nil | dr3-module preset: S14, S15 |
| **C. Kleros escalation** | `"0xkleros-proxy"` | `{:0 "0xl0" :1 "0xl1" :2 "0xl2"}` | nil | Kleros escalation fn | kleros/kleros-appeal presets: S18, S19, S20, S21, S23, S26–S33, S41, S42, S44, S46a/b, S47a/b, S48, S49, S62, S101–S107 |
| **D. Module + Kleros (both set)** | `"0x..."` | `{:0 "0xl0" ...}` | (overridden by kleros logic) | Kleros escalation fn | ❌ **NOT TESTED** |

### 2.3 Module configuration coverage gaps

**Status: All module-configuration gaps below are covered by S-DR-050 through S-DR-054.**

#### Gap 1: Mode D — resolution-module and escalation-resolvers both set (✅ S-DR-050)

When **both** `resolution-module` and `escalation-resolvers` are provided in
protocol params, the `build-execution-context` creates an `escalation-fn` from
the level map but the `rm-fn` is nil (because `level-map` is non-nil, the
`when` clause at line 761 is skipped).

At dispatch time (`sew.clj:252-256`), the effective resolution module is
constructed from `resolution-level-map`:

```clojure
effective-rm-fn (or (when resolution-level-map
                      (auth/make-kleros-module resolution-level-map
                                               #(t/dispute-level world %)))
                    resolution-module-fn)
```

So when both params are provided, the kleros module (built from escalation-
resolvers) is used as the resolution module for L0. The `resolution-module`
address is **ignored** — it's only used as a pass-through to the module
snapshot for Priority 2 check in `authorized-resolver?`.

No scenario explicitly tests this combined configuration. The trust boundary
between the two params is not validated:
- What happens if `resolution-module` is a valid address but `escalation-resolvers`
  has a different set of addresses?
- Can a resolver authorised by `resolution-module` resolve L0 while a different
  escalation resolver handles L1?

**Risk: Medium** — while the dispatch logic (`effective-rm-fn`) correctly
prefers the kleros module when escalation-resolvers are present, the
interaction between the two params could cause confusion: a researcher might
assume both params are active simultaneously.

#### Gap 2: Empty string `resolution-module` fallthrough

If protocol-params has `"resolution-module": ""` (empty string):

1. `build-execution-context` line 761: `(not= rm-addr "")` → `rm-fn` is nil
2. `authorized-resolver?` line 106: `(not= "" (:resolution-module snap))` → skip
   to Priority 3

This means an empty string is silently treated as "no module" rather than
producing an error. If a caller passes `""` expecting module authorisation,
the resolver silently falls through to the escrow's `dispute-resolver`. This
is a **configuration validation gap**, not a runtime bug, since the fallthrough
behaviour is correct.

**No new scenario needed** — this is a schema/validation concern.

#### Gap 3: Module with `false`/`nil` authorisation return (✅ S-DR-053)

From `authorized-resolver?` lines 104-108:

```clojure
(and (some? resolution-module-fn)
     (some? (:resolution-module snap))
     (not= "" (:resolution-module snap)))
(or (:authorized? (resolution-module-fn workflow-id caller))
    (= caller (:dispute-resolver et)))
```

When the resolution-module returns `{:authorized? false}`, execution falls
through to Priority 3 (the `dispute-resolver` check). This means a module
rejection is non-fatal — the resolver can still rule if they happen to match
the escrow's `dispute-resolver`. This is by design (mirrors Solidity), but
it means a module that returns `false` for everyone (e.g. missing level in
`make-kleros-module`) could still pass if the caller is `:dispute-resolver`.
No scenario tests this fallthrough behaviour with a module returning false.

**Risk: Low** — this mirrors the EVM contract behaviour exactly.

#### Gap 4: `escalation-fn` not configured per workflow

The `escalation-fn` is constructed once per scenario from `escalation-resolvers`,
not per workflow. If a scenario creates multiple escrows, they all share the
same escalation configuration. No scenario tests per-workflow escalation
configuration differences within a single run.

**Risk: Low** — all escrows in a single scenario naturally share protocol
params.

### 2.4 Module type coverage summary

| Module type | `rm-fn` | `esc-fn` | Scenarios |
|-------------|---------|----------|-----------|
| None (direct resolver) | nil | nil | ✅ dr3, ieo, timeout presets |
| Single-address module | `make-default-resolution-module` | nil | ✅ dr3-module (S14, S15) |
| Kleros escalation | nil (built at dispatch) | Kleros escalate | ✅ kleros, kleros-appeal (S18–S33, S41–S49, S62, S101–S107) |
| Module + Kleros both set | nil (overridden) | Kleros escalate | ✅ S-DR-050 |

### 2.5 Escalation level coverage

| Escalation path | Scenarios | Coverage |
|----------------|-----------|----------|
| L0 resolve, no escalation | S01, S02, S03, S04, S05, S06, S07, S08, S09, S10, S11, S13, S14, S15, S16, S17, S22, S24, S25, S34, S35, S36, S37, S50–S100+, S-DR-001–S-DR-044 | ✅ Full |
| L0 → L1 escalation (single level) | S21, S26, S29, S30, S32, S33, S44, S46b, S48, S49, S101, S103, S105, S106, S107 | ✅ Covered |
| L0 → L1 → L2 (max level) | S27, S31, S103 | ✅ Covered |
| Attempt escalation beyond L2 | S20, S31 | ✅ Rejected |
| Escalation without resolution-module | S77 | ✅ Rejected |
| Escalation after appeal window | S28 | ✅ Rejected |
| Premature settlement during appeal | S32, S36, S-DR-040 | ✅ Rejected |

---

## 3. Summary of gaps

| Gap | Location | Risk | Status |
|-----|----------|------|--------|
| `:disputed → :resolved` untested | `state_machine.clj:306-309` | **None** — dead transition | Documented as intentional |
| `rotate-dispute-resolver` never tested | `resolution.clj:28-64` | **Medium** | ✅ S-DR-060 (happy path), S-DR-062 (rejected guards) |
| `challenge-resolution` with nil `escalation-fn` | `resolution.clj:558` | **Low** | ✅ S-DR-051 |
| Mode D (module + Kleros both set) | `sew.clj:754-775` | **Medium** | ✅ S-DR-050 |
| Module false fallthrough | `authority.clj:104-108` | **Low** | ✅ S-DR-053 |
| Missing escalation level | `sew.clj:763-769` | **Low** | ✅ S-DR-054 |
| Custom resolver bypasses module | `authority.clj:91-94` | **Low** | ✅ S-DR-052 |
| `:pending → :refunded` thin coverage | `state_machine.clj` | **Low** | ✅ S-DR-055 |
| `submit-evidence` on non-disputed escrow | `resolution.clj:200` | **Low** | ✅ S-DR-056 |
| Empty-string custom-resolver | `authority.clj:92-93`, `lifecycle.clj:312` | **Low** | ✅ S-DR-070 + guard added in lifecycle.clj |
| `automate_timed_actions` multiplexer | `resolution.clj:632-660` | **Low** — trivial priority logic | Monitor if multiplexer changes |

---

## 4. Recommendations implemented

| Scenario | Gap | File |
|----------|-----|------|
| S-DR-050 | Mode D (module + Kleros both set) | ✅ |
| S-DR-051 | challenge-resolution with nil escalation-fn | ✅ |
| S-DR-052 | Custom resolver bypasses resolution module | ✅ |
| S-DR-053 | Module false fallthrough to dispute-resolver | ✅ |
| S-DR-054 | Missing escalation-resolvers level | ✅ |
| S-DR-060 | rotate-dispute-resolver governance override | ✅ |
| S-DR-062 | rotate-dispute-resolver rejection guards | ✅ |
| S-DR-063 | slash-appeal lifecycle (propose→appeal→upheld→reversed) | ✅ |
| S-DR-064 | slash-appeal lifecycle (propose→appeal→rejected→executed) | ✅ |
| S-DR-070 | empty-string custom-resolver rejected at creation | ✅ + lifecycle guard |

### Remaining gaps in DISPUTE_RESOLUTION_COVERAGE.md

- `:evidence-deadline` — no evidence-window-duration param (coverage gap)
- `:resolver-response-deadline` — no resolver-response-window param
- `:expected-value-appeal` — no game-theoretic EV model
