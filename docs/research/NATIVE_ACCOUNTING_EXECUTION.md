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

## Independent implementation comparison

`accounting-implementation-comparison/compare-implementations` accepts two work/result pairs,
validates both executions through the existing fixed-validation path, then
derives `research-accounting-implementation-comparison.v1` from those artifacts.
It does not accept caller-supplied expected values or constituent roots. The
comparison binds both work, execution, assurance, semantic-partial,
implementation, dependency-closure, request and partition roots. Its generic
forensic evidence node has both execution branches as parents.

An exact comparison is made only when both executions bind the same accounting
request and partition, reduction contract, resolved dependency closure and
execution environment. Otherwise the result is `:not-comparable`, with explicit
reasons and no numeric delta. `:equal` means the independently derived category
maps match; `:different` retains per-category left/right values as a witness.
This is evidence of implementation-level result agreement, not proof that
either implementation or the underlying accounting basis is correct. The
current arithmetic domain is Clojure's arbitrary-precision integers, so the
two supported implementations share an exact integer domain and have no
checked-long overflow boundary. A future implementation with a different
overflow/domain contract must declare that semantic difference and remain
`:not-comparable` unless an explicit common-domain rule is established.

The qualification tests run both implementations freshly on the same frozen
account request/partition: the canonical partial and reconciled accounting
result roots agree, while implementation, execution, and run-assurance roots
differ. A changed account request remains `:not-comparable`, not a numeric
disagreement. The distinctness asserted here is artifact-level only; the
implementations share the Clojure/JVM runtime and this does not establish
organizational, intellectual, machine, or participant independence.
The comparison implementation is deliberately outside
`native_accounting_work.clj`, whose source root is part of the accounting
implementation identity; adding assurance logic therefore does not change the
accounting algorithm's identity.

The frozen assurance statement for this fixture is:

```text
implementation-artifact distinctness  established
fresh execution identity distinctness established
exact result equivalence               established
different human researchers            unknown
different organizations                unknown
different machines                     not implied
intellectual independence              not implied
```

The two executions are separate fresh computations, but currently run under
the same local Clojure/JVM environment. “Independent” here is limited to the
identified implementation artifacts and execution instances above.

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

### Cross-host cancellation and durable recovery qualification

The opt-in `live-cross-host-accounting-cancellation-and-same-journal-recovery`
test exercises the current runner bundle across an SSH tunnel. Its three
cutpoints are deliberately distinct:

```text
cancellation observed before dispatch
  → runner restart with the same journal
  → no execution evidence and no accounting result

atomic partition execution persisted, before result post
  → cancellation observed over the tunnel
  → runner restart with the same journal
  → completed execution retained, delivery suppressed

coordinator durably ingests partition, before HTTP acknowledgement
  → remote runner process killed
  → same runner journal restarted
  → identical result redelivered and recognized as already recorded
```

### Performance pilot (measurement only)

`test/resolver_sim/research/native_accounting_performance.clj` is an opt-in
measurement driver. It compares direct serial execution, partitioned execution
through local runner/coordinator HTTP endpoints, and (when the existing SSH
tunnel variables are present) a split local/remote-runner execution. It checks
canonical result-root equality for every sample. Timings are ephemeral
`System.nanoTime` observations, not rooted experiment inputs or performance
claims. No scheduler policy or accounting identity changes are made.

Example local pilot:

```sh
clojure -M:test -m resolver-sim.research.native-accounting-performance 1 16 1,2,4 3
```

Arguments are comma-separated account counts, source rows per account,
partition counts per account, and repetitions. One discarded warm-up is run for
each coordinate. With the remote runner prestarted and the established SSH
forwards active, set `PRF_REMOTE_ACCOUNTING_RUNNER_URL` and
`PRF_REMOTE_ACCOUNTING_COORDINATOR_PORT` to include cross-host measurements.
Remote runner startup is excluded; local coordinator/runner startup, dispatch,
execution, result delivery, and reconciliation are separately timed. Preserve
the emitted EDN as runtime measurement evidence if a pilot needs later review;
do not treat it as a canonical result artifact.

The initial pilot used one account, 16 source rows, three measured repetitions,
and partition counts 1/2/4. Median end-to-end times were:

| Mode | Partitions | Median |
| --- | ---: | ---: |
| Direct serial | 1 | 23.7 ms |
| Partitioned local HTTP | 1 | 166.9 ms |
| Partitioned local HTTP | 2 | 277.0 ms |
| Partitioned local HTTP | 4 | 397.0 ms |
| SSH-tunnel split local/remote | 2 | 702.7 ms |
| SSH-tunnel split local/remote | 4 | 1,894.4 ms |

All modes produced the same canonical accounting result root within each
repetition. At this tiny workload, transport and per-work-unit assurance
overhead dominate; partitioning is slower, and this does not identify a
break-even grain. The remote participant was the previously qualified runner
reference `sha256:93ee43c12ef8ff7940c754045607d15ab3f29e48e99fab08d2905cbbe03336f7`
on `ubuntu@16.170.12.190`; its service was prestarted and stopped after the
pilot. The split used one local and one remote runner; at four partitions the
three remote partitions were sent sequentially to that single remote runner.
This was SSH-tunnel execution only—not public-network, failure-tolerance,
multi-remote-runner scaling, or hardware-independent qualification. The next
useful measurement is a targeted larger-source-count sweep with repeated
samples and recorded machine conditions; avoid extrapolating these small-case
ratios.

The last case distinguishes resuming persisted execution from replacement
execution: the recovered journal retains one execution start and the exact
execution root, while the coordinator retains one semantic ingestion and the
redelivery is acknowledged as `:already-recorded`. The local execution,
remote partial and coordinator-reconciled result have matching semantic roots.
Test-only coordinator
events record one new-result recognition and one duplicate recognition. The
coordinator makes two response attempts: the first targets the killed runner
connection, and the retry response is received. The live test passed 31
assertions with zero failures/errors. The local native-accounting process
suite passed 8 tests / 38 assertions; changed test namespaces lint cleanly.

The current staged remote content set was 82 manifest files plus
`src/resolver_sim/definitions/passive_registries.clj`, identified for this
qualification by the sorted per-file SHA-256 content-set hash:

```text
e8a6c0c15122b751c9a3ff717989e1ed063e0c6e1ec744408e6cbd4a323e7908
```

This is staging/qualification provenance, not a semantic implementation root.
The earlier `f3486534…` and `132cc6a8…` hashes continue to identify their own
historical source bundles. The existing `prf-runner-lab` checkout was not
used or modified. The temporary SSH tunnel and remote runner processes were
stopped after the test. This qualification does not establish public-network
reachability, cross-host crash recovery beyond same-journal process restart,
cross-host cancellation during arbitrary computation, or scaling/performance.

The distributed accounting qualification is frozen at this boundary:

| Capability | Status |
| --- | --- |
| Native accounting execution | Complete |
| Exact partition reconciliation | Complete |
| Local process durability | Complete |
| Cross-host execution and canonical equivalence | Complete |
| Cross-host pre-execution cancellation | Complete |
| Cross-host post-computation cancellation | Complete |
| Same-journal remote recovery | Complete |
| Duplicate delivery / idempotent ingestion | Complete |
| Minimal staged qualification bundle | Complete |
| Artifact-level implementation comparison | Complete |
| Public-network reachability | Not qualified |
| Arbitrary network partitions | Not qualified |
| Different-host journal recovery | Not qualified |
| Performance / scaling | Not qualified |
| Hermetic transitive source closure | Not qualified |

The next performance study should compare serial execution, partitioned local
execution and SSH-tunneled cross-host execution over the same rooted accounting
requests. It should report useful accounting time separately from runner
startup, dispatch, result transfer, durable journal writes, and reconciliation;
vary partition size/count and account count; and identify break-even regions
without changing scheduler policy or canonical identities. The current timing
fixtures establish correctness only and are not performance evidence.

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
