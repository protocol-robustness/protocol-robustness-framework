# ADR-0008: PRF Allocation Evidence Grammar (first instantiation: withdrawal ledger)

Status: Adopted (initial withdrawal instantiation complete; grammar reusable)

Date: 2026-08-07

Scope: a generic evidence grammar for *committed deterministic allocation* —
first realized as the yield withdrawal ledger, intended to generalize to
pro-rata allocation, liquidation queues, reward distribution, claims
processing, priority allocation, and solvency liabilities.

## 1. The grammar

A deterministic allocation claim composes seven layers, each carrying its own
content-addressed commitment where it exists:

```text
authoritative universe            (external anchor; NOT yet committed here)
        ↓ explicit eligibility/admission policy
complete admissible population    (missing layer — next boundary)
        ↓ request normalization
allocator request population      → request-set-root (multiplicity-preserving)
        ↓ economically-material order (FCFS) → request-order-root
        ↓ deterministic/versioned transform  → allocation-policy-root
committed outputs (ledger rows)   → rows + totals + ledger/hash
        ↓ cross-boundary conservation       → per-row + aggregate conservation
recomputable provenance           → run-root · state-cutpoint-root · basis-root
```

The general PRF claim being built toward:

```text
ledger = F(request-set, request-order, capacity, allocation-policy,
           parameters, state-cutpoint)
```

## 2. First instantiation: withdrawal ledger

`resolver-sim.yield.*` produces a content-addressed `:yield/withdrawal-ledger`
record per withdrawal (single or `withdraw-many` batch). It commits:

| Field | Commits | Schema |
|---|---|---|
| `:ledger/domain` | evidence domain/version | `withdrawal-ledger.v1` |
| `:ledger/run-root` | run · execution · scenario · params-root | `withdrawal-run-root.v1` |
| `:ledger/params-root` | world parameters at cutpoint | `withdrawal-params.v1` (via run-root) |
| `:ledger/state-cutpoint-root` | allocation-relevant state (positions, held, indices, risk) | `withdrawal-state-cutpoint.v1` |
| `:ledger/request-set-root` | principals + requested amounts, **multiplicity-preserving** | `withdrawal-request-set.v1` |
| `:ledger/request-order-root` | input order the allocator was obligated to use (order-preserving) | `withdrawal-request-order.v1` |
| `:ledger/allocation-policy-root` | allocator contract (mode, rounding, conservation) | `withdrawal-allocation-policy.v1` |
| `:ledger/basis-root` | compositional identity tying the six inputs to one cutpoint | `withdrawal-allocation-basis.v1` |
| `:ledger/hash` | canonical commitment over the whole record (fixed-point preimage + canonical bytes) | evidence-record |

Conservation is two-level: per-row `filledᵢ + deferredᵢ + haircutᵢ = requestedᵢ`
(within a **committed** tolerance), and aggregate `Σ rows = totals`. The
tolerance is a committed policy field (`:conservation {:mode
:absolute-smallest-unit :tolerance 2}`), so a verifier derives the permitted
error solely from committed evidence — amounts are integer smallest token
units.

## 3. Design principles (generalizable)

1. **Commitment faithfully represents what was supplied; validation judges
   admissibility.** The request-set root preserves multiplicity (`sort`, not
   `distinct`): a malformed duplicate population commits differently from the
   legitimate one and is rejected by validation. Deduplication before
   commitment would destroy evidence of the malformed input.
2. **Order is an input where economically material.** FCFS commits the input
   ordering separately (`request-order-root`), proving "this was the order the
   allocator was obligated to use", not merely the output row order. Pro-rata,
   where order should not affect economics, commits only the set.
3. **State is content-addressed, not labelled.** `state-cutpoint-root` is a
   state reference, not a timestamp/block/run id: two withdrawals at the same
   run/block but different state commit differently, and a ledger cannot be
   composed from state fragments taken at different cutpoints.
4. **Compositional identity is explicit.** `basis-root` binds
   state · population · order · capacity · policy · params to one cutpoint,
   preventing cross-state substitution (universe from X, capacity from Y,
   ordering from X, policy from Z) while constituent roots remain committed for
   inspection.
5. **Numerical contract is committed.** rounding rule and conservation
   tolerance are policy fields, not hidden verifier constants.
6. **Principal, not address.** the allocator models economic principals
   (`owner-id`); address/on-chain identity is a separate, later,
   versioned `principal-binding.v1` artifact (principal ↔ subject valid over a
   cutpoint/interval).

## 4. Boundaries explicitly not committed here

- **Input completeness.** The certificate proves *given* request population R,
  ordering O, policy P, and the other committed inputs, ledger L is valid. It
  does not yet prove R was exactly the complete admissible population at the
  cutpoint. An allocator can be fully deterministic, conserved, ordered,
  policy-bound, and committed while silently omitting a principal before R is
  constructed. Establishing `R = {x ∈ U_X | admissible(x, P, X)}` requires an
  authoritative external universe anchor (`U_X`) and an explicit
  eligibility/admission rule — the next layer.
- **Independent replay verifier.** The certificate is self-verifying; a
  separate verifier demonstrating `ledger = F(basis)` end-to-end is future
  work.
- **Chain identity binding.** owner-id ↔ address attribution is deferred.

## 5. Reuse decision

Do not extract framework code yet. Reuse the grammar when a second or third
domain (pro-rata, liquidation queue, reward distribution, claims) exhibits the
same shape; then name the shared primitive and migrate.
