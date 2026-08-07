# Accounting & the Ledger — High-Level Overview

This document describes, at a high level, how value movement is **accounted for**
and how a canonical **ledger** is maintained and verified in this repository.

## Scope and architecture summary

There are **two accounting domains** in the repository, and they are **not** at the
same architectural depth:

- **Held custody accounting** follows the *full* ledger → live index → independent
  replay → content-addressed evidence → closed-form verification architecture
  described below. This is the core escrow/vault ledger that tracks all
  protocol-held funds.
- **Yield accounting** is a **second, separate accounting domain with its own
  position and conservation model.** It does **not** (in this source) maintain a
  canonical append-only ledger or an independent replay path with content-addressed
  evidence. It uses a share-price position model and a lineage conservation check.
  It is described in its own section at the end.

This document therefore describes the complete ledger architecture for **held
custody**, and treats yield accounting as a distinct domain rather than a second
instance of the same stack.

### Layer roles (held custody)

| Layer | Role | Example |
|-------|------|---------|
| **Canonical ledger** | The authoritative record of every custody movement | `:held-adjustments` (append-only vector) |
| **Primitives** | Functions that *make* ledger entries safely | `add-held` / `sub-held` / `record-fee` / claimable writes |
| **Live index** | The canonical **runtime projection** the protocol reads/writes during a run | `:held-ledger/index` + `:total-held` / `:held/positions` |
| **Replay** | An independent re-derivation of the same projection from the ledger | `replay-held-adjustment-state` |
| **Artifacts** | Content-addressed, hash-bound records derived from ledger entries | held-custody artifacts, summaries |
| **Verification** | Closed-form checks + differential tests that compare live vs. replay | `held-custody-closed-form-checks` |

**Terminology note.** The word *authoritative* is reserved for the **canonical
ledger**. The live index is the **canonical runtime projection** — the shape the
protocol actually operates on — and the top-level aliases (`:total-held`,
`:held/positions`) are **compatibility projections always regenerated from the
index**. The derivation hierarchy is unambiguous:

```
ledger (authoritative history)
   │  derives
live index (canonical runtime projection)
   │  derives
compatibility aliases (always regenerated from the index)
```

---

## 1. The canonical ledger (held adjustments)

Every movement of protocol-held funds is recorded as a **held adjustment**, an
append-only entry. The ledger is **canonical**: it is the source of truth from
which all balances, artifacts, and reports are derived.

A held adjustment carries the economic and provenance fields needed to reconstruct
every consequence of the movement, most importantly:

- `:held/direction` — `:in` (funds added to custody) or `:out` (funds removed).
- `:token` and `:amount` — what moved, and how much (always a non-negative integer).
- `:held/before` / `:held/after` — the token balance immediately before and after.
- `:held/account` — the custody bucket (e.g. `:escrow-principal`, `:yield-custody`,
  `:appeal-bond`, `:resolver-slash-custody`).
- `:held/position-id` — a vector identifying the exact custody position.
- `:owner/address` — forensic owner attribution where required.
- `:held/reason` / `:held/action` — why the movement happened and the logical action.
- `:held/workflow-id` — the escrow workflow the entry belongs to.
- `:authorization/provenance` / `:parameter/context` / `:parameter/address` —
  authorization and parameter attribution, when present.

Adjustments are strictly append-only and each receives an id of the form
`held-adjustment-0`, `held-adjustment-1`, ... (see §8 for what these ids do and do
not prove).

### Entry-point primitives

`protocols_src/resolver_sim/protocols/sew/accounting.clj` exposes the write surface:

- `add-held` — credit custody (funds enter the pool).
- `sub-held` — debit custody (funds leave); rejects underflow.
- `record-fee` / `withdraw-fees` — monotonic protocol fee accumulation and withdrawal.
- `record-released` / `record-refunded` — cumulative payout counters.
- `record-claimable-v2` / `withdraw-escrow` — pull-settlement entitlements.

Every primitive funnels through a single private mutator, `adjust-held`, which
performs **write-time** validation and enforcement: input validation, reason-derived
position policy, ownership-attribution requirements, underflow guards, then builds
the adjustment, writes the live index, appends to the ledger, and appends a
content-addressed artifact.

> **Where the proof boundary sits.** Most of the *enforcement* described above
> happens at **write time** in the trusted path. Whether it is independently
> *re-verifiable* from the artifacts is a separate question, addressed honestly in
> §7.

---

## 2. The five-dimensional held-ledger index

Balances are projected along **five dimensions** (see
`src/resolver_sim/accounting/held_ledger_index.clj`). They are **not** all the same
kind of quantity:

| Dimension | Key type | Kind | Semantics |
|-----------|----------|------|-----------|
| `:by-token` | token keyword | **stock** (balance) | total held per token (≥ 0) |
| `:by-position` | position-id vector | **stock** (balance) | total held per custody position (≥ 0) |
| `:by-account` | account keyword | **stock** (balance) | total held per custody bucket (≥ 0) |
| `:by-workflow` | workflow id | **stock** (balance) | total held per escrow workflow (≥ 0) |
| `:by-owner` | address string | **flow** (attribution) | **net custody-flow attribution** per owner address; may be negative |

The four **stock** dimensions are alternate decompositions of the same closing
custody balance. `:by-owner` is **flow attribution, not a custody-balance
partition**: an owner may receive flow from multiple positions, and funds move
between owners via settlement, so the same unit of held value can be attributed to
several owners over its lifetime. Negative values indicate net outflow from an
address (e.g. escrow released or refunded). It is **not** intended to reconcile
mathematically with any single stock dimension; it answers "who caused how much
cumulative flow," not "who holds what balance now."

The index is a *derived projection*, never an independent store. Because it is
derived, it can be rebuilt from the ledger, which is what makes verification
possible.

---

## 3. Live path vs. replay path (differential verification)

The design deliberately maintains **two independent implementations** of the same
projection, so one can catch bugs in the other:

- **Live path** — `update-ledger-index` mutates the running projection during a
  protocol run. The existing index is authoritative over the top-level aliases; the
  aliases are regenerated from the index after each step, so they cannot drift.
- **Replay path** — `replay-held-adjustment-state`
  (`src/resolver_sim/assurance/custody.clj`) rebuilds the identical projection from
  scratch by reducing over the ledger. It **fails closed** on any before/after
  mismatch, underflow, missing token, or negative amount, and enforces the
  **zero-origin contract** (see §4).

Both paths produce maps that **must satisfy the same shared Malli schema**
(`held-ledger-index-schema`, `held-custody-state-schema`). Semantic equivalence of
the live and replay implementations is asserted by **differential tests**.

> The ledger is the truth; both the live index and the replay output are derived.
> If they disagree, the ledger is still the reference against which the
> implementation is judged.

---

## 4. Zero-origin and replay modes

The replay path is a **genesis replay**: it starts from a zero opening and requires
that, for every token, the first adjustment's `:held/before` equal the running value
(0). A ledger whose first adjustment for some token is non-zero is **not
independently reconstructable** without a committed opening state.

This is deliberate and safer than silently accepting arbitrary opening state, but it
has an important architectural consequence:

> **A truncated or imported ledger can never establish its own opening state by
> replay alone.**

There are exactly two supported modes:

- **Genesis replay** — opening state is zero; the ledger must be zero-origin. This
  is the mode currently implemented (`held-history-zero-origin?` guards it).
- **Checkpoint replay** — opening state plus an explicit commitment/proof is
  supplied by the caller. This is the mode that would be required if the ledger is
  ever pruned, snapshotted, or imported. It is **not** currently part of the replay
  contract.

If future pruning/snapshotting is introduced, it must be expressed as an explicit
checkpoint-mode opening state + commitment, otherwise the meaning of "replay
verified" would be silently weakened.

---

## 5. Conservation & invariants

The accounting is backed by conservation rules that verify no value is created or
lost:

- **Ledger conservation** — replay from a zero opening must exactly equal the
  observed closing balances; per-token `opening + in = closing + out`.
- **Sub-held / position underflow guards** — you cannot debit more than is held.
- **Reason-policy position integrity** — every `:held/reason` maps to a fixed
  custody account and scope; an entry cannot place funds in a policy-violating
  bucket. *(Currently enforced at write time only — see §7.)*
- **Yield lineage conservation** (`src/resolver_sim/yield/conservation.clj`) — a
  deferred-yield position's committed origin obligation must equal the sum of its
  disposition buckets:
  `original = fulfilled + active-deferred + reversed + written-down`.
  In-flight intermediate ("closed-deferred") amounts are deliberately excluded to
  avoid double counting across generations.
- **Write-down fidelity** — terminal settlement evidence must report a
  `:finalize/write-down` equal to the ledger-derived negative-yield write-down.

---

## 6. Content-addressed artifacts & evidence

Ledger entries are surfaced to downstream consumers as **content-addressed
artifacts** so they can be independently re-verified:

- **Held-custody artifacts** (`build-held-custody-artifact`) — a stable, hashed
  projection of each adjustment (`:artifact/hash` over the committable payload).
  Each artifact binds its own `:held-adjustment/id`, so an artifact's identity and
  the ledger entry it came from are connected by that id.
- **Artifact chain** — each artifact records `:held/previous-artifact-hash`, forming
  an ordered hash chain (`:predecessor-continuity`).
- **Held-custody summary** (`build-held-custody-summary`) — a versioned,
  content-addressed auditor-facing report. It aggregates the ledger, index,
  artifacts, attribution posture, completeness, reconciliation, and closed-form
  failure counts with triage. It records `:adjustment-count`, `:artifact-count`,
  `:adjustment-sequence-range`, and `:artifact-chain-head` (the last artifact hash),
  and a token-level `:reconciliation-valid?` comparing replayed closing to the
  observed closing balance.

All artifacts use intent-tagged, canonical hashing so verification is deterministic.

---

## 7. Closed-form verification — and the assurance boundary

`held-custody-closed-form-checks` in `src/resolver_sim/assurance/custody.clj` runs a
deterministic battery over the **artifact surface** (it does not re-run the
protocol):

| Check | What it verifies |
|-------|------------------|
| `:held-custody/hash-integrity` | artifact hash matches recomputation over its payload |
| `:held-custody/artifact-schema` | artifact schema version is supported |
| `:held-custody/parameter-attribution` | **shape** of attribution is structurally valid |
| `:held-custody/local-delta` | `after == before ± amount` per entry |
| `:held-custody/non-negative-after` | no balance goes negative |
| `:held-custody/predecessor-continuity` | artifact hash chain is unbroken |
| `:held-custody/sequence-replay` | replay over the chain reproduces every before/after |

### What the closed-form battery does **not** independently prove

The document must not imply a stronger proof boundary than the checks establish.
The following are **verification gaps** — properties that are enforced on the
trusted write path (or not at all) but are **not** re-established by the independent
closed-form verifier:

1. **Ledger ↔ artifact bijection and ordering (P0).**
   `:predecessor-continuity` proves the artifacts chain together; `:sequence-replay`
   proves that chain is internally coherent. Neither proves that the artifacts are
   an **exact, ordered, one-to-one image of the canonical ledger**. A valid artifact
   chain could, in principle, omit a ledger entry, include an extra artifact, or
   contain the right count but with one artifact bound to a different adjustment.
   The summary records counts but does not commit a shared ledger root against which
   artifact count/order is reconciled. **Gap:** an explicit
   `:held-custody/ledger-artifact-bijection` and `:held-custody/ledger-artifact-order`
   check, ideally with each artifact committing an immutable adjustment identity and
   the summary committing both a ledger root and an artifact-sequence root.

2. **Reason/account policy parity (P1).**
   The write path enforces reason-derived position policy, but the closed-form
   battery has no `:held-custody/reason-position-policy` check that recomputes the
   allowed account/position shape from `:held/reason`. As written, the architecture
   proves "the trusted write path prevents an invalid reason/account combination,"
   **not** "an independent verifier can prove no invalid combination exists."

3. **Required-attribution vs. attribution-shape (P1).**
   The verifier's `:held-custody/parameter-attribution` check establishes that
   attribution **shape** is valid. It does not establish that the movement
   **required** attribution (per reason policy) and that the **required owner was
   actually committed**. These are distinct assertions. **Gap:** distinguish
   `:held-custody/attribution-shape` (valid shape when present) from
   `:held-custody/required-attribution` (economically meaningful: the movement
   demanded attribution, and it was committed), plus optional semantic binding where
   the owner is derivable from the position/workflow.

Until items 1–3 are implemented, the closed-form battery verifies **artifact-chain
integrity**, not **ledger completeness**. This should be treated as the primary
reason the accounting is not yet audit-ready on its own.

---

## 8. Adjustment IDs and sequence semantics

Each adjustment receives an id derived from the running ledger length
(`held-adjustment-0`, `held-adjustment-1`, ...), and replay sorts by numeric order
(lexical ordering would mis-sort `held-adjustment-10` before `held-adjustment-2`).

Be explicit about what these ids prove and do **not** prove:

- They are **not** an independent integrity mechanism. Continuity/order come from
  the **artifact predecessor chain** and the replay reduction, not from the numeric
  id itself.
- The document does **not** currently claim independent verification of: unique
  adjustment ids, contiguity, artifact uniqueness, adjustment-id ↔ artifact
  identity, or the absence of duplicate replay. If the id is intended as part of the
  accounting identity (rather than display metadata), those properties should be
  checked independently; if not, they should be described as display metadata only.

---

## 9. Yield accounting (position level)

`src/resolver_sim/yield/accounting.clj` handles yield-bearing positions using a
**share-price model** (module-agnostic; applies to Aave liquidity indices,
ERC-4626 share prices, etc.):

- At deposit: `shares = principal / entry-index`.
- Current value: `current-value = shares × current-share-price`.
- Unrealized yield: `current-value − principal`, floored to token precision
  (signed under mark-to-market loss mode, unsigned otherwise).
- `realize-yield` crystallizes unrealized yield into `:realized-yield`.
- `apply-liquidity-stress` computes shortfall/haircuts under liquidity failure
  modes (`:shortfall`, `:haircut`, `:partial-liquidity`), using separate
  yield/principal availability ratios.
- `claim-deferred` allows recovery of deferred funds once availability improves.

As noted in the scope section, yield accounting is a **separate domain**: it has its
own position and conservation model, but this source does **not** demonstrate that it
maintains a canonical append-only ledger, independent live/replay implementations,
or content-addressed evidence equivalent to the held-custody stack. Do not read the
held-custody architecture into the yield section.

---

## Key files

| Concern | Location |
|---------|----------|
| Core held-custody accounting & ledger primitives | `protocols_src/resolver_sim/protocols/sew/accounting.clj` |
| Shared held-ledger index schema | `src/resolver_sim/accounting/held_ledger_index.clj` |
| Held-adjustment value projection | `src/resolver_sim/accounting/held_adjustment.clj` |
| Replay, artifacts, closed-form checks, summaries | `src/resolver_sim/assurance/custody.clj` |
| Position-level yield accounting | `src/resolver_sim/yield/accounting.clj` |
| Yield lineage conservation | `src/resolver_sim/yield/conservation.clj` |
| Accounting tests | `protocols_src/test/.../sew/accounting_test.clj`, `test/resolver_sim/accounting/held_ledger_index_test.clj`, `test/resolver_sim/assurance/custody_summary_test.clj` |
