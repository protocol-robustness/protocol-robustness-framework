# Partial-fill strategic execution and replay v1

Status: bounded v1 core implemented in `resolver-sim.yield.strategic-execution`,
with solver integration in `analyze-replayed-recovery-game-profiles`. Existing
amount-only searches and caller-outcome evaluators retain their existing APIs.
The restrictions below are enforced. The bounded fixture and documentation
follow-up is complete; verification is recorded in the findings document.

## Interfaces and boundaries

Use a new namespace `resolver-sim.yield.strategic-execution`, requiring
`resolver-sim.yield.partial-fill`, `resolver-sim.hash.canonical` and the existing
signed-envelope verifier. Keep strategic-partial-fill's finite-game solvers and
utility arithmetic. The new namespace must not require strategic-partial-fill;
the existing namespace can require the new adapter without a dependency cycle.

Public functions:

| Function | Input | Output |
|---|---|---|
| `allocate-owned-rows` | authoritative allocation input | verified decision, payout by owner, evidence root |
| `compare-owned-transformation` | baseline input and proposed transformed rows | exact per-owner deltas or inconclusive rejection |
| `prepare-execution-model` | v1 execution model plus prepared recovery model | validated, bound execution model or violations |
| `replay-profile` | prepared execution model, joint plan, branch ID, trace, verification options | derived outcome, terminal state, roots or rejection |
| `verify-state-permission` | current state, action, resolved grant and trust policy | permitted or explicit rejection |

Return `:status :verified` only on complete verification. Rejections carry
`:status :inconclusive`, a stable `:reason`, and paths identifying the failed
profile, branch, round or submission. Never substitute a zero utility for a
failed execution. Cap profiles, branches, rounds, submissions, rows and resolved
evidence bytes before executing producers or resolving external evidence.

## Production allocation adapter

Allocation input contains `:available`, `:policy`, and a vector of owned rows:

```clojure
{:row/id :alice-yield-0 :owner/id :alice :entitlement/id :alice-right
 :key :realized-yield :owed 10 :weight 7 :cap 8
 :obligation-id :obligation-0 :source-position-id :position-0}
```

Require nonnegative integer quantities, unique row IDs and explicit owners.
Retain row identity, owner identity and entitlement identity separately. Do not
encode owners in ordinal positions. Require weight and effective cap explicitly
in this adapter; cap is an integer no greater than owed. Construct `:requested`
from rows. Dispatch to the appropriate `calculate-fulfillment-*` producer, then
call `verify-semantic-decision` with the exact authoritative input. Reject any
failed or unsupported result before computing payout.

Current producers key filled maps by unique `:key` row identity; principal-first
uses the literal `:principal`. Waterfall rows may share a priority `:bucket`,
which defaults to `:key` for backwards compatibility. `:fill-order` lists bucket
IDs. Requested, filled, deferred and row evidence retain each row key, so owners
remain independently attributable inside the shared bucket. The semantic verifier
and priority theorem aggregate by bucket while checking row-level conservation
and caps. Duplicate row keys remain invalid; duplicate waterfall fill-order
buckets are invalid. Other allocation modes reject a distinct `:bucket` field.
Pro-rata splits use unique child keys and explicit canonical obligation/source IDs.

Aggregate verified fills by owner. For every entitlement a split or merge must
preserve the sums of owed, weight and cap, and preserve owner and priority class.
Cross-owner merging is unsupported. Reject non-additive cap semantics rather
than guessing a distribution. A permutation preserves every row field and only
changes vector order. Exact gains compare the same owner's aggregate payouts.
Changing request, cap or priority is a different action requiring an explicit
permission; it is never admitted as an identity-preserving split.

Canonical tie behavior comes from the producer and authoritative IDs, not the
toy allocator's ordinal tie rule. Include both complete inputs and both verified
decisions in comparison evidence. Report the producer-backed model separately
from `:amount-only-ordinal-reference`.

## Execution model and liquidity

Use schema `partial-fill-strategic-execution.v1`, separate from the existing
`partial-fill-recovery-game.v2`. Bind its `:recovery-model/root` to
`recovery-game-model-root`. V2 remains a utility/assumptions model; its round
liquidity fields are not sufficient to define replay transitions.

The execution model specifies one pool, initial cash, opening entitlements,
fixed settlement policy, ordered round IDs, per-branch exogenous deposits and
the mapping from each finite action ID to a complete contingent plan. Plans
declare exactly which observation/state selects each action. V1 supports public,
deterministic transaction ordering only; unsupported private information,
sequencer choice or unresolved concurrent ordering returns inconclusive.
Alternative possible orders must be explicit branches with declared probability
or separate scenario analyses, not caller-selected outcomes.

Initial entitlement balance equals the authoritative model amount. Each branch
has a complete deposit schedule, with unique event IDs and nonnegative amounts.
Deposit events are credits, not repeated snapshots; an event can be consumed
once. Remaining cash carries forward. Existing v2 liquidity snapshots and
recovery-event amounts must not also be credited. Migration requires the caller
to provide the explicit credit schedule; reject ambiguous legacy conversion.

For each step:

1. Resolve scheduled deposits once; compute current available cash.
2. Derive submission order and expected actions from the fixed plans, branch and
   current state. Trace-supplied inputs cannot choose these values.
3. Check ownership, residual entitlement and state-dependent permission.
4. Derive authoritative allocation rows and liquidity, run production allocation,
   and independently verify the supplied trace decision and produced decision.
5. Debit cash and entitlement balances by verified fills; preserve deferred
   claims as residual entitlement. Record payments at the current round.
6. Recompute the post-state root and compare it with the trace's claimed root.

Enforce `opening cash + deposits = payments + closing cash` at each boundary,
and `opening entitlement = cumulative payments + residual` for each entitlement.
Haircuts and entitlement minting are unsupported in v1. Reject negative balances,
out-of-order rounds, skipped required actions, extra actions and duplicate credit
events. Failed submissions leave settlement state unchanged; attempted fees may
only come from the explicitly bound fee schedule.

Retry identity is `(entitlement ID, submission ID)`, not entitlement ID alone.
Exact retries return their prior result without another payment; a changed retry
payload is rejected. A new authorized submission can consume a residual balance
in a later round. This resolves the ambiguity in v2's
`:single-settlement-per-entitlement`: that label cannot forbid all later partial
settlements while also modeling recovery of deferred claims. V1 declares its own
`:single-consumption-per-submission` rule explicitly.

## Trace, derived utility and evidence

Each profile/branch trace carries schema version, model root, execution-model
root, initial-state root and ordered steps. Each step carries round, submission
ID, action ID, pre-state root, decision, permission source root and post-state
root. Round inputs and resulting balances are assertions checked against the
derived state, not authoritative data supplied by the trace author.

Derive payment amounts/times and expected-unrecovered residuals from replay.
Define delay-rounds as the sum, over rounds before the terminal horizon, of one
when the player's closing residual is positive. Fees, penalties and externality
charges must be derived from explicit bound schedules or rejected as unsupported;
zero values require explicit zero schedules. Continuation values and utility
coefficients continue to come from the bound recovery model. Feed the resulting
outcome into existing exact utility evaluation. Caller-supplied terminal payments
cannot override derived outcomes.

Use `hc/domain-hash :projection-artifact` with versioned `:artifact/kind` tags
for state, trace and execution evidence. Hash canonical data, never `pr-str`.
Exclude signatures and evidence roots from their own preimages. The evidence
root binds recovery model root, execution model root, initial-state root,
ordered player/action domains, complete joint-plan table and every branch's
trace root. Reject missing, extra or duplicate profiles/branches before replay;
verify branch probabilities match the model and sum to 10000 basis points.

A content root establishes binding, not signer authority. Keep separate fields
for replay verification, resolved permissions and authenticated assumptions.
`:outcomes-authenticated?` remains false unless a distinct trusted authority signs
the complete execution evidence root; model signatures alone cannot set it.

## State-dependent permissions and feasible games

A grant binds game/execution-model root, player, entitlement, action ID, amount
limit, cap limit, priority class, round and pre-state root. Resolve its source
from a bounded local root-to-artifact map or explicit resolver. Recompute its
root and verify an Ed25519 envelope under a distinct protocol-permission role
and caller-provided trust policy. Reject unresolved roots, wrong signer role,
revoked keys, stale state roots and conflicting grants. Existing static
player/action signatures do not authorize these grants.

Recheck the grant at the actual execution state. Enforce entitlement ownership
and residual bounds even when a trusted grant asserts larger amounts. Splits
and merges transfer residual rights without creating rights. Withholding
liquidity needs a separately tracked provider-owned uncredited balance; since
v1 models only fixed deposits, withholding is unsupported rather than treated
as arbitrary subtraction from shared cash.

Ordinary finite Nash analysis requires a complete Cartesian product of feasible
strategy plans. If any joint profile is infeasible, return inconclusive with
the profile/action/reason and do not drop it or insert zero utility. A later
generalized-game or extensive-form analyzer may support coupled feasibility.
Complete contingent plans still yield only a normal-form Nash result; no
subgame-perfect claim follows without information-set and continuation checks.

## Implemented integration and verification

The distinct `analyze-replayed-recovery-game-profiles` entry point in the
existing strategic namespace validates budgets and table shape, calls replay
for every profile/branch, evaluates derived utilities, then delegates to existing
pure or two-player mixed solvers. Mark `:settlement-replayed? true` and
`:state-dependent-permissions-verified? true` only after all traces succeed.
Existing caller-outcome APIs retain their current assurance fields and continue
to reject `:require-validated-execution? true`; do not route them to replay by
looking for a loosely shaped optional trace field.

The implementation includes the owned-row verifier and transformations,
execution-model validation, transition engine and permission resolution,
complete table/evidence binding, and solver integration. Unsupported cases
described above are explicitly rejected.

Bounded regression and documentation work is complete. Waterfall supports
multiple owner rows per priority bucket while retaining unique row identities;
tests cover independent owner payouts, priority ordering, row conservation,
semantic reconstruction and rejection of invalid fill-order buckets.

Fixtures cover owner-preserving permutations, weighted/capped rows, the canonical
split-gain witness, and invalid producer decisions. Replay fixtures cover deposit
replay, exact/changed retries,
partial recovery over two rounds, excessive entitlement consumption, stale or
wrong-role grants, root mutation, missing branches, and a profile whose
feasibility depends on the other player's action. Tests assert rejection occurs before
utility evaluation and that both pure and mixed adapters preserve all assurance
fields. See the [findings and verification record](../findings/partial-fill-strategic-validation-gaps.md)
and the [runnable API example](../research/PARTIAL_FILL_REPLAY_API.md).

## Implemented v1 wire shape

Execution models use `:recovery-model/root`, `:initial-cash`, `:policy`,
`:ordering :fixed-public-order`, `:cost-schedule :zero`, `:submission-order`
(a vector of submission IDs), and `:plans {player {choice-id [step ...]}}`.
A step contains `:round/index`, `:submission/id`, `:action` (`:claim-now` or
`:defer`) and `:row` in the owned-row shape above. Defer uses zero owed and cap.
Each branch is `{:branch/id id :probability-bps n :deposits [...]}`; each deposit
contains `:event/id`, `:round/index` and `:amount`. Deterministic branches use
`:deterministic`. Stochastic branch IDs/probabilities and total deposited recovery
must match the recovery model's outcomes. Plans are fixed sequences, not general
state-contingent programs; unsupported timing or information semantics need a
later schema. Empty plans explicitly represent making no submissions.

Trace schema is `partial-fill-execution-trace.v1`, with `:actions` (joint choice
map), `:branch/id`, `:execution/root`, `:initial-state/root`, and `:steps`.
Each trace step asserts round, submission ID, action, pre/post state roots,
`:permission/root` and exact production `:decision`. Optional terminal-state root
is checked. A retry reuses its original decision and makes no payment; changing
the payload except its round is rejected. Deposits are applied before that round's
first action. Permissions are needed on each new submission, not cached retries.

Grant schema is `partial-fill-state-permission.v1`; it binds `:execution/root`,
`:player/id`, `:entitlement/id`, `:action`, `:round/index`, `:pre-state/root`,
`:priority` (the claim key), `:amount-limit`, `:cap-limit` and
`:permission/status :permitted`. Sign with `permission-domain` and trust keys
under `permission-role` exported by the new namespace. The source root includes
the signed envelope, using `content-root :partial-fill-state-permission.v1`.
Supply `:permission-sources {root signed-grant}` and `:permission-trust-policy`.
No network resolver is used in v1.

Solver game input is `{:actions {player [choice ...]}
:profiles [{:actions {player choice} :traces-by-id {branch-id trace}}]}`.
Options include `:max-profiles` and `:max-replays` (both default 10000), and
`:mixed? true` for the existing two-player solver. The replay adapter itself
satisfies `:require-validated-execution?`; the caller-outcome adapter still rejects
that requirement. `:require-authenticated-assumptions?` on replay requires a valid
`:assumption-trust-policy`; static player/action grants do not replace replay's
separately signed state permissions. Replay verification does not authenticate
the economic truth of the fixed liquidity schedule.

Hard bounds are 100 players, 1000 entitlements, 1000 rounds, 100 branches, 1000
deposits per branch, 1000 submissions per trace, 1000 rows per allocation and
10000 plan steps per model. Quantities and sums must fit production long inputs.
Roots use the repository's canonical committable projection, including exact
float and ratio representations and canonical ordering of credited-event sets.
