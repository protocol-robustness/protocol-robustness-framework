# File-Based Partial-Fill Profile V0

Status: bounded research/model profile. It is not a payment executor, a
production settlement protocol, or distributed consensus.

## Ordering and recovery

The journal begins with an operation record, followed by immutable source
records. A submission binds those source roots and the operation, entitlement,
beneficiary, amount, asset, policy and idempotency identity. Workers can record
preparation results concurrently, but each preparation binds the exact frozen
operation/source snapshot and submission. Admission requires at least one
preparation; all preparations for a submission must agree on the result root.
The authority deterministically selects among agreeing preparations, so worker
completion order does not choose priority.
Forensic submission nodes link to every recorded source root, and preparation
nodes attribute the action to the worker actor root with the `:worker` role.

A single local ordering authority serializes journal appends with an
operating-system file lock. The durable append position determines submission
arrival order; the authority assigns admission sequence numbers. Commit effects
are applied to the modeled liability projection strictly in admission order.
Completion is recorded only when every submission is admitted and committed.
There is no wall-clock ordering claim and no multi-host leader election.

The journal is newline-delimited EDN. Each record binds its sequence, previous
record root, authority root, kind and payload. Submission, admission and commit
payloads are shape-checked during replay; commits bind before/after economic
projection roots and the exact admitted submission. Each record is appended
as one newline-delimited value (there is no atomic replace step) and forced
through `FileChannel.force(true)`. Opening a ledger also forces the parent
directory entry. On restart, an unterminated final fragment is truncated to
the last complete line. If the journal has no complete lines, it is
reinitialized only when the fragment is an exact prefix of the expected
genesis record for the supplied operation and authority; unrelated bytes fail
closed. Blank complete lines are malformed. A malformed complete line or any
complete record with bad shape, sequence, authority, chain root, semantic
binding, frozen snapshot or projection fails closed with a reason code.
Non-map record values and non-map event payloads fail with
`:partial-fill-file/malformed-record`; unexpected replay validation errors are
wrapped as `:partial-fill-file/invalid-record`.
Retrying an already recorded
source, submission, preparation, admission, commit or completion is
idempotent. A crash during an append can leave a torn tail; recovery discards
only that incomplete tail. A crash after a forced append but before
acknowledgement replays the record and makes the retry a no-op.

| Boundary | Recovery behavior |
| --- | --- |
| Before source/submission/preparation/admission/commit/completion append | The prior journal and economic projection remain authoritative; retry is allowed. |
| During any append | An unterminated tail is truncated; the transition has no effect and can be retried. |
| After a complete forced append, before acknowledgement | Replay restores the transition; the same identity returns `:duplicate`. |
| After commit, before later commits | Paid and remaining amounts reflect only the durable commit prefix; commits continue in admission order. |
| After completion | The terminal projection is replayed; new transitions are rejected and duplicate completion returns its root. |

Worker preparation records commit to worker, submission, frozen snapshot and
result roots. The profile does not execute worker code or prove that a result
was computed correctly. Agreement among workers is only a consistency check;
worker authentication, cache isolation and independent uncached reproduction
are separate qualification work.

These guarantees depend on the host filesystem and operating system honoring
file locks and force operations. They do not establish hardware power-loss
durability, safe multi-host shared-filesystem behavior, or Byzantine fault
tolerance. A process crash/reopen test is not a power-cut test.

## Economic model

The operation artifact binds operation, asset and policy roots, beneficiary
roots, entitlement amounts, claimable total, restricted refund and funded
total. A submission must match the operation, entitlement, beneficiary, asset,
policy and amount ceiling. Admission reserves entitlement capacity. Serial
commit moves the modeled amount from remaining to paid. After every prefix,
paid plus remaining claimable liability equals the original claimable total;
the restricted refund remains outside claimable balances. A rejected action
does not append a record or alter the projection.

`commit!` only records a modeled liability update. It does not transfer tokens,
verify observed balances, authorize payment, or prove settlement. Forensic
nodes preserve journal causality and history identity; terminal projection
roots express economic state and can be equal across different execution
orders.

## Reproduction

Run the focused profile tests with:

```sh
clojure -M:test -e '(require (quote clojure.test) (quote resolver-sim.research.partial-fill-file-profile-test)) (let [r (clojure.test/run-tests (quote resolver-sim.research.partial-fill-file-profile-test))] (System/exit (+ (:fail r) (:error r))))'
```

The tests exercise all six 35/21/14 claim orders with a separate restricted
10-unit refund, distinct history roots, parallel versus serial worker
preparation under one frozen snapshot and fixed admission order, conflicting
and stale preparation rejection, cross-operation and wrong-beneficiary
substitution, overdraw rejection, concurrent submissions through independent
local handles, recovery checks across source, submission, preparation,
admission, commit and completion, idempotent post-reopen retries, terminal
completion, no-write rejection, and diagnosed fail-closed handling of stale
authority and complete corruption.

## Integration restoration (2026-10-29)

The file-based model, tests and this qualification were restored onto PRF
integration after the pro-rata/research-execution refactor. The current
forensic graph requires typed actor roles; the model records preparation as
`:worker`, so `:worker` is included in the accepted actor-role vocabulary.
This is an evidence-graph compatibility change only; allocation, claim and
canonical roots are unchanged.

On the restored integration tree, the focused profile suite passes 9 tests / 333
assertions. The combined forensic-graph and profile suites pass 22 tests / 391
assertions. The framework runner printed PASS for 109 tests / 397 assertions
and a clean leak check, but that invocation was interrupted after its summary;
it is recorded here as an observed pass summary, not a clean command exit.

This PRF profile models file ordering, concurrent preparation and journal
recovery. It does not implement EVM withdrawal proof-mode semantics. The
separate `prf-evm` candidate `d32c302d` latches Requested/Required mode at
activation and is not part of this PRF integration revision. EVM claim
qualification must use whichever allocator revision is actually integrated;
the file model must not be described as proving the EVM withdrawal gate.

## Review findings follow-up (2026-10-29)

The follow-up candidate fixes live forensic-node tracking and makes graph
validation fail when a journal event lacks its forensic node. Recovery now
reinitializes an incomplete genesis only if its bytes are a prefix of the
expected operation/authority genesis; malformed complete blank lines fail
closed. Regression coverage includes each live transition, omitted-node
detection, torn-genesis recovery, and blank-line rejection. The focused profile
suite passes 12 tests / 368 assertions; the combined forensic-graph and profile
suites pass 25 tests / 426 assertions. This follow-up candidate still requires
a fresh independent review and staging admission.
