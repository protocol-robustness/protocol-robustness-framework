# ADR-0004: Cross-Protocol Use-of-Funds Ledger Extraction (Scope 2)

Status: Draft (Scope 1 completed; Scope 2 pending)

Date: 2026-05-18

## Context

The repository now exposes a Sew implementation of a read-only funds ledger via
`io-projection` target `:funds-ledger-view`.

Current shape is intentionally reusable, but computation is Sew-scoped in:

- `src/resolver_sim/protocols/sew/projection.clj`

This ADR defines a **Scope-2 extraction** design: create shared helpers and
adapter hook points, without forcing full multi-adapter implementation yet.

## Scope-1 Completion Note (implemented)

Contract-only extraction is complete:

- reusable `:funds-ledger-view` output shape is documented in core protocol
  interface docs (`protocols/protocol.clj`, `AnalysisModule/io-projection`),
- Sew remains the concrete implementation,
- no shared computation module or cross-adapter behavior migration performed.

## Decision

Introduce a protocol-agnostic funds-ledger helper module and adapter-side
mapping contract.

### New shared module (proposed)

`src/resolver_sim/scenario/funds_ledger.clj`

Responsibilities:

- construct ledger output from normalized adapter-provided inputs,
- aggregate token/global buckets,
- compute drift summaries from conservation results,
- enforce stable output shape.

### Adapter contract (proposed)

Each adapter provides a mapping function that returns normalized inputs:

```clj
{:as-of-block-time nat-int
 :token-buckets
   {token {:held nat-int
           :released nat-int
           :refunded nat-int
           :withdrawn nat-int
           :bond-posted nat-int
           :bond-slashed nat-int}}
 :global-buckets
   {:claimable-total nat-int
    :bond-locked-total nat-int
    :bond-fees-total nat-int
    :bond-distribution-total nat-int
    :retained-slash-reserves nat-int}
 :conservation
   {:holds? boolean
    :violations vector
    :drift-by-token {token int}
    :drift-total int}}
```

The shared module validates/normalizes and emits final `:funds-ledger-view`.

## Why this design

1. Keeps protocol semantics protocol-scoped (no false generalization).
2. Reuses one stable output contract across adapters.
3. Minimizes churn in existing Sew logic.
4. Allows progressive onboarding of additional adapters.

## Non-goals

- No immediate extraction of all accounting semantics into core framework.
- No requirement that every adapter implement funds-ledger now.
- No protocol-level behavior changes.

## Migration Plan

### Step 1 — Add shared helper (no behavior change)

- Create `scenario/funds_ledger.clj` with pure constructors/validators.
- Add unit tests for contract shape and drift aggregation behavior.

### Step 2 — Wire Sew through shared helper

- Keep Sew-specific collection logic in `protocols/sew/projection.clj`.
- Replace final assembly with shared helper call.
- Ensure `:funds-ledger-view` output is byte-for-byte compatible where possible.

### Step 3 — Add optional adapter guidance

- Update `docs/overview/REUSABLE_COMPONENTS.md` to reference shared helper.
- Add short adapter onboarding checklist for funds-ledger support.

### Step 4 — Optional second adapter pilot

- Implement mapping in `protocols/dummy.clj` (or future adapter) as pilot.
- Validate shape compatibility and document semantic differences.

## Impacted Files (planned)

- New: `src/resolver_sim/scenario/funds_ledger.clj`
- Update: `src/resolver_sim/protocols/sew/projection.clj`
- Update: `src/resolver_sim/protocols/dummy.clj` (optional pilot)
- New tests: `test/resolver_sim/scenario/funds_ledger_test.clj`
- Update docs: `docs/overview/REUSABLE_COMPONENTS.md`, `docs/overview/USE_OF_FUNDS.md`

## Risks

1. **Semantic drift risk**: adapters may map unlike concepts into same bucket names.
   - Mitigation: adapter-side explicit mapping docs + validation checks.

2. **Contract creep**: pressure to over-generalize niche Sew semantics.
   - Mitigation: keep contract minimal and accounting-centric.

3. **Compatibility risk**: changing payload structure could break downstream consumers.
   - Mitigation: preserve existing keys; only additive changes.

## Rollback Strategy

- If extraction causes regressions, keep shared helper unused and preserve Sew-local
  assembly path until mappings are stabilized.

## Effort Estimate

Scope-2 extraction total: **2–4 engineering days**

- Shared helper + tests: 0.75–1.5 days
- Sew migration + compatibility pass: 0.5–1 day
- Docs + optional pilot adapter: 0.75–1.5 days

## Acceptance Criteria

1. `:funds-ledger-view` remains available with stable shape.
2. Sew outputs unchanged or intentionally documented when changed.
3. Shared helper has unit tests for shape and drift aggregation.
4. Docs clearly separate reusable contract vs adapter-specific semantics.
