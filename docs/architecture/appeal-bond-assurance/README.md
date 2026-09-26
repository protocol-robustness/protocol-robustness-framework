# Appeal-bond assurance layers

This directory defines the evidence boundary for replacing embedded appeal-bond
custody with the `ResolverIncentiveModule -> BondLedger` architecture. A passing
Solidity unit suite alone is insufficient: the candidate must preserve the
canonical Sew semantic projection under the supported asset domain.

> **Composition / wiring note.** The canonical deployment wiring for the current
> September candidate uses `DecentralizedResolutionModule` with **`ResolverIncentiveModule`**
> as the incentive module (inline appeal-bond custody). The `ResolverIncentiveModule -> BondLedger`
> architecture described here is the **intended successor** — `shared/BondLedger` and the
> `ResolverIncentiveModuleV2BondLedger` facade are present in the tree but **not wired** into the
> deploy scripts, allowlist, or manifest. The frozen topology is recorded in
> [`SEW_NEXT_DEPLOYMENT_COMPOSITION.md`](SEW_NEXT_DEPLOYMENT_COMPOSITION.md).
> This distinction matters so PRF evidence is clearly attributed to the wired (canonical) graph.
>
> **Profiles.** Two supported compositions are separated under explicit profile identities
> (see [`DECISION-appeal-bond-profiles.md`](DECISION-appeal-bond-profiles.md)):
> Profile A `sew-dr-v2-inline-custody` (supported reference baseline, inline appealBonds) and
> Profile B `sew-dr-v2-bondledger` (intended next-release candidate, BondLedger-backed facade).
> Profile B is wired by an additive deploy script and does not replace or deprecate Profile A.

## Layer A — legacy behavioural equivalence

The established scenario corpus remains immutable migration evidence:

| Scenario | Scope |
| --- | --- |
| `DR-C-001` | ordinary escalation; per-funder scaling |
| `DR-C-002` | slash-appeal lifecycle; separate mechanism |
| `DR-C-003` | ordinary escalation reversal/refund |
| `DR-C-004` | ordinary escalation failed-appeal resolver distribution |
| `DR-C-005` | ordinary escalation forfeiture/reserve accounting |
| `DR-C-006` | ordinary escalation settlement failure atomicity |
| `DR-N-002` | reversal-slash appeal rejection; separate mechanism |

Layer A is run through the Sew policy adapter. A scenario is not rewritten just
to fit the primitive. Any non-equivalence must have a Layer C decision record.

## Layer B — primitive-native assurance

Primitive-native cases use the authoritative appeal-bond projection, not
aggregate ledgers or ad-hoc scenario counters.

| Family | Required property |
| --- | --- |
| `AP-ID-*` | bond ID binds workflow, dispute, ordinal, and posting operation; rounds isolate |
| `AP-DISP-*` | open bonds have zero terminal dispositions; disposed bonds have exactly one |
| `AP-CONS-*` | `posted = fee + custodied`; `custodied = allocation + remaining custody` |
| `AP-CLAIM-*` | entitlement is immutable; `allocated = claimable + claimed` |
| `AP-CAUSE-*` | every terminal disposition has a typed, non-empty cause root |
| `AP-ATOM-*` | rejected settlement/resolution preserves appeal-bond authoritative-state root |
| `AP-REPLAY-*` | duplicate posting/settlement/claim attempts are rejected without mutation |
| `AP-POLICY-*` | bond obligation uses the posting-time policy snapshot, not later parameters |

Layer B intentionally covers semantics unavailable in the former aggregate
model, including exact per-bond lineage and distinction between disposition and
claim payment.

## Layer C — intentional divergence records

Each record states the old behavior, candidate behavior, reason, affected
invariant, migration consequence, and replacement scenario. No behavior is
silently adapted to make a legacy scenario pass.

## Gate

Canonicalization requires all applicable Layer A scenarios, all Layer B cases,
and zero unresolved Layer C decisions. The resulting comparison artifact must
include the reference/candidate identities, corpus root, projection version,
accepted/rejected transition counts, and scenario/transition-level mismatches.

---

## First primitive-native tranche — COMPLETE (2026-09)

This section freezes the first primitive-native appeal-bond assurance tranche.
It is scoped to the reusable appeal primitive / BondLedger review semantics and
does **not** assert Solidity deployment readiness or global assurance.

**Status: `COMPLETE`** — all six scenarios PASS with genuinely evaluated
primitive-native metrics/evidence, not merely because execution completed.

| Scenario | Claim | Semantic path exercised | Evidence / query used | Protection layer (where relevant) | Result |
| --- | --- | --- | --- | --- | --- |
| `AP-CONS-001` | per-bond conservation `posted = fee + custodied`; `terminal allocations = custodied`; `remaining custody = 0` when disposed | ordinary posting → challenge → resolution → disposition | validated `appeal-bond-projection` scoped query (`bond_conservation_holds`, `remaining_custody_after_disposition`, `terminal_allocations_equal_custody`) | n/a | **PASS** |
| `AP-ATOM-001` | reverted terminal operation leaves appeal-bond semantic state unchanged | `execute_resolution` at seq 4 with injected `:distribution-dependency-failure` | external transition receipt (explained below); appeal-bond pre/post semantic roots | transition-evidence root comparison | **PASS** |
| `AP-DISP-001` | exactly one terminal disposition; second attempt produces no duplicate effect; history immutable | higher-round `execute_resolution` (accepted) → replayed `execute_resolution` (idempotent no-op) | scoped projection (`terminal_disposition_count` = 1, `second_terminal_operation_no_duplicate_effect`, `historical_disposition_unchanged`) | **system/workflow-level** resolution idempotence (second op applied with equal roots) | **PASS** |
| `AP-REPLAY-001` | replayed terminal operation rejected / no duplicate effect; one disposition, one entitlement | same ordinary path (seq 4 accepted → seq 5 replay) | scoped projection (`first_terminal_operation_accepted`, `replay_no_duplicate_effect`, `disposition_count` = 1, `no_duplicate_entitlement`) | **system/workflow-level** ordinary-resolution idempotence, not a primitive-level `:already-disposed` guard | **PASS** |
| `AP-CLAIM-001` | allocation survives claim consumption; `allocated = claimable + claimed`; full claim leaves `claimable = 0`, `claimed = allocated` | pending settlement → `withdraw_escrow` after legitimate workflow finality | per-bond entitlement projection (`allocated` / `claimable` / `claimed`), disposition/claim immutability | workflow finality prerequisite (see § claim semantics) | **PASS** |
| `AP-ID-001` | same workflow + same funder + distinct appeal ordinals → distinct bond IDs, settled one does not mutate the other | post first bond (ordinal 0) → higher-round resolution → post second bond (ordinal 1), observed at earliest coexistence checkpoint | scoped projection identity selector over retained world-checkpoints, grouping by `(workflow/id, bond/funder)` and distinguishing by `:bond/id` + `:appeal/ordinal` | scoped bond-projection identity query | **PASS** |

### Disposition/replay layer distinction (AP-DISP / AP-REPLAY)

The observed protection is **system/workflow-level** rather than primitive-level
**duplicate-write** prevention. In the passing runs:

- seq 4 `execute_resolution` is `:applied` with appeal-bond `pre-root != post-root`
  (the first terminal operation genuinely mutates the bond and produces one disposition);
- seq 5 replayed `execute_resolution` is `:applied` with `pre-root == post-root`
  (an **idempotent no-op**: accepted but zero semantic bond change; exactly one
disposition and one entitlement remain).

The `claim-to` for `AP-REPLAY-001` records `:replay-protection-layer :ordinary-resolution-idempotence`.
These scenarios do **not** claim that the lower-level appeal-bond writer rejects a
duplicate terminal write with `:already-disposed`; that specific guarantee is not
exercised by a production scenario and would require a legitimate primitive harness.

### Transition-receipt closure (AP-ATOM)

AP-ATOM now verifies atomic reverted-operation behavior from external transition
evidence at the replay boundary (`apply-action-with-evidence` / `process-step`):

```clojure
{:transition-receipt/version 1
 :operation/type :scenario.transition/execute_resolution
 :operation/seq 4
 :execution/status :reverted
 :execution/cause {:type :distribution-dependency-failure :root <operation-id>}
 :appeal-bond {:pre-root <sha256> :post-root <sha256>
               :pre-status :valid :post-status :valid
               :pre-errors nil :post-errors nil}}
```

Confirmed properties:

- the expected `execute_resolution` operation is observed;
- `status = :reverted`;
- `cause = :distribution-dependency-failure`;
- both appeal-bond semantic roots are `:valid`;
- `pre-root == post-root` (state unchanged, hence atomic).

The `:execution/status` remains **runner/execution evidence** — it is never inferred
from unchanged protocol state. The appeal-bond roots are derived deterministically
from the **validated** appeal-bond projection via the registered
`:appeal-bond-semantic-state` canonical intent. There is **no authoritative
`state-mutated?` boolean**; mutation is derived from `pre-root == post-root` (or
`!=`). The receipt is external to Sew protocol world state and does not mutate it
to record a reverted operation.

### Claim semantics (AP-CLAIM)

Recorded interpretation of the authoritative per-bond entitlement fields:

- `allocated` — the entitlement amount committed to the recipient by the bond's
terminal disposition (immutable evidence);
- `claimable` — the accounted entitlement subject to **workflow finality** before
it can be withdrawn;
- `claimed` — the portion already consumed by withdrawal.

Before claim: `allocated = claimable`, `claimed = 0`. After a legitimate
transfer/finality transition (`execute_pending_settlement`), `withdraw_escrow`
consumes entitlement. The scenario proves:

```text
allocated = claimable + claimed
```

and, for full consumption, `claimable = 0`, `claimed = allocated`. The historical
`allocated` and the disposition/allocation identity are **unchanged** — claim
consumption moves entitlement from `claimable` to `claimed` without rewriting
historical disposition evidence. Multi-bond claim attribution (Bond A / Bond B →
same recipient → same asset) is **resolved as aggregate-only** by Solidity
inspection and encoded in PRF — see [Multi-bond claim attribution —
RESOLVED](#multi-bond-claim-attribution--resolved-aggregate-only-encoded-in-prf).

### Identity semantics (AP-ID)

AP-ID observes the proof at the **earliest unambiguous coexistence point** — the
first retained checkpoint where a repeated `(workflow/id, bond/funder)` bond group
appears (immediately after the second challenge posts its bond), **without**
driving through unrelated final settlement (the seq-6 `withdraw_escrow` is
expected `:rejected`).

The identity proof is established by a fail-closed scoped selector over retained
world-checkpoints. This selector is **assurance-framework behavior**, not part of
the candidate scenario's semantic claim:

- it groups validated appeal-bond records by `(workflow/id, bond/funder)` and
  returns exactly one unambiguous repeated-bond group; zero results or multiple
distinct repeated-bond groups fail closed to no value;
- it no longer depends on world-checkpoint index `6` or the funder address
  `0xchallenger`;
- bond A vs bond B are identified by `:bond/id` and `:appeal/ordinal` (`0` vs `1`).

Observed properties at the earliest coexistence point:

- same workflow `0`, same funder `0xchallenger`;
- appeal ordinal `0` (bond A) vs appeal ordinal `1` (bond B);
- `bond_a_id_distinct_from_b true`;
- `bond_b_open_at_coexistence true` — the second bond coexists and is open;
- cross-bond isolation: operating on one bond's lifecycle does not mutate the
other's semantic projection.

### Qualified properties (qualification boundary)

The six qualified properties, recorded so later work cannot accidentally overstate
or understate them:

```text
AP-CONS
Per-bond accounting conserves posted amount through fee, custody,
terminal allocation and remaining custody.

AP-DISP
After terminal disposition, subsequent execution cannot create a
second terminal appeal-bond semantic effect.

AP-REPLAY
Replay cannot duplicate disposition, entitlement or custody effect.

AP-ATOM
The expected distribution-dependency failure reverts atomically over
the appeal-bond semantic state, demonstrated by valid equal pre/post
semantic roots.

AP-CLAIM
For a single bond, allocation/disposition provenance survives a
legitimate claim transition and allocated = claimable + claimed.

AP-ID
Bonds for the same workflow/funder at different appeal ordinals have
distinct authoritative identities, established at their earliest
unambiguous coexistence point.
```

### AP-DISP / AP-REPLAY layer distinction

Neither AP-DISP nor AP-REPLAY is recorded as proving that the second terminal
operation is **rejected**. The observed semantics are:

```text
first execution:
  applied, semantic mutation

second/replayed execution:
  applied/no-op at the appeal-bond semantic layer
  equal pre/post appeal-bond roots
```

The qualified property is **semantic idempotence / no duplicate effect**, not a
lower-level `:already-disposed` duplicate-write rejection. Any summary of the
qualification result must preserve this distinction (see the
[Disposition/replay layer distinction](#dispositionreplay-layer-distinction-ap-disp--ap-replay)
section above).

### Multi-bond claim attribution — RESOLVED (aggregate-only), encoded in PRF

```text
Bond A -> recipient R -> asset T
Bond B -> recipient R -> asset T

R performs an aggregate claim/withdrawal for T.
```

Solidity inspection (`sew-protocol`) resolves the semantic boundary. In the
**canonical, deployed `ResolverIncentiveModule`**, claim consumption is
**aggregate-only per `(escrowContract, workflowId, recipient)`**:

- refunds credit a single workflow-level pool —
  `claimableBondRefunds[escrow][workflow][recipient] += amount` — and
  `claimBondRefund` reads the whole pool, transfers it, and `delete`s the entry;
  there is **no round/bond key on the claimable**;
- resolver payments are identical — `claimablePayments[escrow][workflow][sender]`
  is a single balance deleted whole on `claimPayment`;
- the per-round `appealBonds[escrow][workflow][round]` records exist for
  **posting/disposition**, not for independently-consumed per-round claimable.

The **alternative** `BondLedger` + `V2BondLedger` (unwired review substrate)
keeps `_claimable[bondId][recipient]` bond-specific internally, but its
`claimBondRefundLedger`/`claimPaymentLedger` expose an **aggregate claim** that
iterates fixed rounds 1..2 and emits a single workflow-level `BondRefundClaimed`;
the caller cannot select a bond or claim a partial amount.

Therefore the canonical module **cannot authoritatively attribute** an aggregate
withdrawal to Bond A versus Bond B, and PRF must not infer FIFO, LIFO, latest,
map order, or any other per-bond consumption rule (none is protocol semantics).

This is now **encoded in PRF** as a fail-closed read-only query
`appeal-bond-projection/query-bond-claim-attribution`, which classifies a
`(workflow/id, recipient)` pair as `:single-bond` (per-bond attribution
authoritative), `:aggregate-multi-bond` (consumption aggregate-only, not
bond-attributable), or `:none`. A per-bond AP-CLAIM-002 would test semantics the
canonical module does **not** have, so it is deliberately **not** added. This is a
resolved semantic boundary, **not** a failure of `AP-CLAIM-001` (single-bond).

### Candidate suite identity (frozen)

Candidate suite root:

```text
716e4cb8490a51042e5490c0634e70462d393a8fee024ed4d8efcda5d70d2901
```

All candidate scenario hashes in `scenarios/edn/appeal-primitive/manifest.edn`
match the working-copy bytes (verified), so the candidate root above is current
and unchanged. No candidate bytes were changed in this freeze.

### Historical isolation (unchanged)

Historical July roots remain exactly:

```text
corpus:     e9e20bd245eb26191b620a3731c19112b5fdc6f268818739b20b3dd0c206b206
overlay:    b1751e6d473a7f438023f1678e1412bc47c356a8b23e89d7d31d1e3108c7e267
checkpoint: e95e52883fd1fe5c542f5589f4d8ce81a2a995f732623ac7ccad2f39bc0c53ce
```

Historical verification is `PROVENANCE-INCOMPLETE` (roots match; only known
unresolved provenance fields remain), and worktree comparison is `MISMATCH` where
expected September descendant drift exists. Neither is a failure of the frozen
historical checkpoint; the July checkpoint verifies independently of worktree drift.

### Validation evidence

```text
bb validate                        → VALIDATION PASSED
SPEDS                              → 66 tests / 265 assertions / 0 failures
appeal_bond_projection_test        → 9 tests / 26 assertions / 0 failures
analysis selector tests            → 3 tests / 6 assertions / 0 failures
checkpoint_verification_test       → 3 tests / 9 assertions / 0 failures
bb lint                            → 0 errors
```

All six primitive-native scenarios pass. `analysis` selector tests cover the
fail-closed AP-ID identity selector (earliest coexistence, zero-result and
multiple-group ambiguity fail closed).

Unrelated repository warnings were not resolved by this tranche and are not
claimed to be.

### Known issues (unrelated, out of scope)

`src/resolver_sim/evidence/attestation_resolver.clj` previously contained a
**pre-existing** unresolved alias reference: the namespace required
`[resolver-sim.evidence.attestation :as att]` but line 83 called
`ar/find-attestation`. This was not introduced by the appeal work. It has since
been resolved in a tiny cleanup change: the `ar` alias is now required from
`resolver-sim.evidence.attestation-registry` (where `find-attestation` actually
lives), and the unused `att` require was removed. `attestation-resolver-test`
passes (19 tests / 37 assertions / 0 failures).

### Remaining out-of-scope areas

This tranche is complete, but the appeal primitive is **not** deployment-ready or
globally assured. Separately tracked remaining areas:

- Solidity deployment wiring;
- runtime/deployed bytecode↔build equivalence;
- broader adversarial/economic coverage;
- canonical multi-bond claim consumption is aggregate-only (resolved; see
  [Multi-bond claim attribution](#multi-bond-claim-attribution--resolved-aggregate-only-encoded-in-prf));
- historical compatibility divergences (e.g. DR-C-003 denomination, DR-C-004
  resolver `:distribution`, DR-C-005 forfeiture lifecycle, DR-N-002 lineage).

The Sew Next qualification matrix (comparing the candidate against the V1
corrected reference `5f84818d`, with this tranche as the first successor-assurance
component) is recorded in [`SEW_NEXT_QUALIFICATION.md`](SEW_NEXT_QUALIFICATION.md).
