# Native account-level accounting execution

This research-only executor computes a frozen accounting partition itself.
Unlike the older Anvil/accounting envelope, its run assurance binds the
canonical accounting partial, not an unrelated EVM action. It does not allocate
capital, accept claims, authorize withdrawals, or change pro-rata arithmetic.

## Account and partition boundaries

`native-accounting-work/account-requests` deterministically groups a validated
source request by `:accounting/subject-root` into separate existing
`accounting-request.v1` artifacts. The source subject is the declared account
identity; no inference about its owner, custody, authority or economic truth is
made. Domain, cutpoint, definition, policy and exact source rows are retained.
Equal category names on different accounts therefore do not merge balances.

Each account request can have multiple existing `accounting-work-unit.v1`
partitions. A native work envelope must identify exactly one unit in its frozen
exact-coverage plan, and that unit must contain sources for exactly one account.
The executor supports only the unchanged sum-by-category V1 reduction contract.

```text
frozen source request
  → deterministic per-account requests
  → per-account exact-coverage partition plans
  → bound native runner work
  → actual integer computation + same-implementation recomputation
  → accounting partial + run assurance
  → existing completeness check and deterministic reconciliation
```

Missing partitions, unexpected account/request contributions, source overlap
and conflicting partials remain errors. An exact partial delivery replay is
idempotent. Arrival order cannot affect the reconciled result. This slice does
not introduce a cross-account aggregate artifact or a batch-level completeness
gate; those are subsequent multi-runner orchestration work.

## New envelopes, reused semantics

The native branch adds four explicit schemas without modifying distributed,
Anvil, accounting or run-assurance V1 meanings:

- `research-native-execution-environment.v1`: declared Clojure/JVM versions and
  checked-integer arithmetic, using the existing environment-root reference.
- `research-native-accounting-work.v1`: frozen job, assignment, opted-in runner,
  execution/assurance policies, declared environment, dependency manifest,
  resolved closure, implementation, account request, plan and partition.
- `research-native-accounting-run.v1`: actual computed values, account identity,
  work/job/assignment references, semantic partial root and observed runtime.
- `research-native-accounting-validation.v1`: exact work/run/result binding and
  successful same-implementation arithmetic recomputation.

`research-run-assurance-evidence.v1` references the native run and validation
roots through its existing generic fields. No caller-supplied expected values
or validation flags enter execution. The work envelope is closed and validated;
result admission rederives the complete execution bundle from the authoritative
dispatch intent before admitting a partial.

The required dependency manifest binds three exact artifacts:
`:accounting/code` (`:clojure-model`), `:accounting/runtime`
(`:runtime-toolchain`) and `:accounting/sources` (`:dataset-fixture`). The code
identity hashes the accounting engine and native adapter source files. This is
a minimal source-artifact identity, not a hermetic transitive JVM/toolchain
closure or cryptographic verification of loaded binaries. Runtime versions are
observed and must match the declared environment. Source files are resolved
operationally relative to the repository root; host paths are not canonical.

## Semantic identity and forensic identity

The unchanged canonical partial root excludes runner and execution evidence.
Two assignments of the same work unit may compute the same semantic partial
while retaining different native run, validation and assurance roots.
`accounting-partial-result.v1` still carries attributable execution evidence;
distinct forensic envelopes are not interchangeable delivery duplicates.
Admission of multiple corroborative partials for one semantic unit remains the
existing higher-level assurance/reconciliation concern, not an automatic vote
or a first-result-wins policy in this adapter.

Native execution uses existing typed generic forensic evidence nodes for the
run, validation, assurance, attributable partial and canonical partial.
The durable runner retains these nodes alongside its existing acceptance,
execution, delivery and acknowledgement history. No separate accounting audit
log is introduced.

## Runner durability and cancellation

`runner-transport/dispatch-native-accounting!` transports the already-rooted
native work envelope through the existing job route. `/v1/accounting` admits
the returned verified bundle and updates the existing accounting context and
partial store atomically with result ingestion.

The shared durable computation path persists completion before result delivery.
A restarted runner redelivers that exact bundle without calling the executor
again. An interrupted executing attempt remains outcome-unknown, not completed.
Endpoint, authentication secret, journal filename and delivery timing are
runtime/forensic details, not arithmetic inputs.

The current native operation is atomic at the transport safe cutpoints:
observed cancellation before computation prevents execution; cancellation after
completion preserves the canonical bundle and suppresses subsequent delivery
under the existing policy. There is no forced interruption inside a fold.
The transport supplies control gating and history; the standalone arithmetic
validator is not evidence of independently observed network cancellation.
Cancellation observed while a completed native result is pending or unreachable
also persists delivery suppression: recovery cannot resurrect its delivery.
Completed execution evidence remains available for reconstruction.

## Assurance limits and next work

This is observed native computation with same-implementation recomputation,
not independent-implementation corroboration, remote-host integrity, attestation,
proof or economic authorization. A produced run-assurance artifact is
`execution-result-only`; it does not claim all multi-run or extra invariant
policy requirements have been satisfied. This branch rejects required proof
generation and sealed-to-environment execution rather than fabricating either.
Readable-private declarations do not provide confidential host isolation.

The reference fixture has two accounts sharing `:accounting/bond`: one has
30 − 5 = 25 and the other 9. Splitting the first account into two partitions
reconciles to 25 in either order with exact coverage. These are research source
rows, not validated production appeal-bond liabilities.

The native path now has an explicitly identified second implementation for
qualification comparisons. It groups source rows by category before folding,
while preserving the same declared sum semantics. Assurance counts
implementation identity roots independently from runner identities; distinct
artifact roots establish artifact-level implementation distinctness only, not
organizational independence. Incentive and withdrawal/custodian/bond policies
may later consume rooted evidence; this executor establishes no payment or
withdrawal entitlement.

`native-accounting-batch/dispatch-and-reconcile!` composes existing partition
bundles for one frozen request/plan. It validates exact work-unit and
assignment coverage before dispatch, submits assigned work concurrently to
runtime endpoints, sorts outcomes by work-unit root, and forms the existing
reconciliation only when the coordinator has admitted every valid partition.
It adds no batch schema or aggregation semantics. A failed/unreachable runner
leaves the batch incomplete; no zero partial is created. Replaying a completed
batch recognizes the existing execution evidence and leaves the canonical
accounting result unchanged.

Focused tests:

```sh
clojure -M:test -e '(require (quote resolver-sim.research.native-accounting-work-test)) (let [{:keys [fail error]} (clojure.test/run-tests (quote resolver-sim.research.native-accounting-work-test))] (shutdown-agents) (when (pos? (+ fail error)) (System/exit 1)))'
```

Validation for this slice: 79 tests / 374 assertions across the native accounting,
remote-execution, execution-assurance/control, semantic-accounting, accounting
control-integration, generic forensic graph and runner reconstruction suites,
plus ten selected local HTTP/reproduction transport regressions; zero failures
or errors. Changed implementation/test namespaces lint cleanly. Linting the
canonical hashing namespace additionally reports its existing `utf8-bytes`
redefinition warning. This is the local execution slice's checkpoint, not
full-repository or real cross-host accounting qualification.

## Literal process-loss qualification

`native_accounting_process_test.clj` reuses the existing runner and coordinator
child-JVM fixtures, HTTP delivery, journal recovery and generic forensic
reconstruction. It adds no production control hooks, schemas or evidence log.
The children are terminated with `Process.destroyForcibly` (SIGKILL on this
Linux qualification host), not merely recreated as Clojure objects.

The tested cutpoints are:

- Before the accounting safe cutpoint: persisted accepted work remains eligible
  for redispatch, with one execution start after restart.
- During accounting computation: a child-only instrumentation wrapper performs
  an actual prefix fold, then blocks before obtaining the full partition result.
  Restart classifies the lost executing attempt as outcome-unknown; no completed
  evidence exists until a fresh retry completes.
- After completed accounting evidence is persisted, before result delivery:
  restart redelivers the exact saved bundle. An arithmetic blocking hook on the
  restarted child would prevent readiness if recovery tried recomputation.
- Coordinator killed after durable ingestion, before acknowledgement: the
  runner records an unreachable result post, then redelivers after recovery.
  Two delivery attempts yield one result entry and one admitted accounting
  contribution; acknowledgement reports `:already-recorded`.
- Cancellation before computation, followed by kill/restart: cancellation
  persists, no execution starts and no accounting partial is manufactured.
- Cancellation after persisted computation, followed by kill before the blocked
  delivery handler returns: evidence persists while result delivery remains
  suppressed across restart. Completed work is not erased or recomputed.

The coordinator's post-persistence barrier and the runner's arithmetic markers
are runtime-only test orchestration, not canonical result identity or authority.
Persisted bundles and the existing forensic graph establish accounting lineage.

The account/partition identity regression separately checks:

```text
same account request + one versus two execution partitions
  → different partition-plan roots
  → unchanged account identity
  → unchanged canonical accounting-result root
```

Transport assignment/partition identity, accounting account identity and final
semantic result identity are distinct. This qualification does not establish
arbitrary asynchronous interruption safety, confidentiality, or performance/
scaling benefit.

Qualification checkpoint: the native accounting process suite, including its
live-host case, passes seven tests / 46 assertions with zero failures/errors.
This includes five literal process-loss/cancellation scenarios, partition-
identity regression, and local-versus-cross-host reconciliation of the same
two-partition request. The semantic/accounting/execution-control focused set
passes 32 tests / 163 assertions, including exact batch coverage,
provenance-substitution checks, and matching semantic results from distinct
implementation identities. The native work suite alone passes 11 tests / 71
assertions. Changed namespaces lint with zero errors or warnings.
Full-repository validation was not run.

The opt-in live test uses `PRF_REMOTE_ACCOUNTING_RUNNER_URL` and
`PRF_REMOTE_ACCOUNTING_COORDINATOR_PORT`. It compares the local serial result,
local partitioned result and coordinator-reconciled distributed result only
after exact partition coverage is admitted; an unreachable runner leaves the
aggregate incomplete. The successful cross-host run used a fresh runner journal
and SSH forwarding to a separate host; no real network scheduler or production
worker service was involved.

Still deferred are source adapters from authoritative appeal-bond lifecycle,
withdrawal-ledger, custodian-position, and incentive evidence; cross-host
word-span work stealing; larger multi-account batches; and scaling/performance
measurements. Generic integer rows or category labels are not substitutes for
validating those domain-specific source projections. No incentive payout,
withdrawal authorization, custody authority, or bond liability is created here.

Run the process qualification (child JVM startup makes it slower than the local
unit suite):

```sh
clojure -M:test -e '(require (quote resolver-sim.research.native-accounting-process-test)) (let [{:keys [fail error]} (clojure.test/run-tests (quote resolver-sim.research.native-accounting-process-test))] (shutdown-agents) (when (pos? (+ fail error)) (System/exit 1)))'
```
