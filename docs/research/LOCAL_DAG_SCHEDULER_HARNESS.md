# Local DAG scheduling experiment

This is a small deterministic **simulation**, measured in integer model
milliseconds. It tests scheduling mechanics and commit invariance on local
agent/research DAGs. It does not measure hardware throughput, model quality,
token-level inference, or a live tool service.

## Parallel research execution boundary

The research-only execution layer is intentionally separate from scheduler
semantics. `research-execution-cell.v1` identifies one policy × scoped case ×
repeat fixture with a workload and expected canonical root;
`research-execution-manifest.v1` freezes the complete Cartesian work set; and
`research-execution-cell-result.v1` records one completed cell. Runtime worker
assignment, submission order, retries, and executor concurrency are excluded
from those roots.

`resolver-sim.research.parallel-executor/execute-manifest` provides
`:sequential` and bounded `:thread-pool` modes. Both return ordered per-cell
completion/failure records, and `result-set` compares their runtime-independent
cell outcome projection. A worker failure stays as an explicit failed cell;
there is no shared mutable aggregate and no production scheduler integration.

`resolver-sim.research.result-store` is the current research-local
content-addressed result store. A completed rooted cell result is indexed both
by its result root and by its execution-cell root. Re-inserting identical
content is idempotent; a distinct valid result for an already-bound cell is
preserved by result root and marks the cell `:conflicting` rather than
overwriting the first binding. `missing-cells` treats missing and conflicting
cells as unresolved, which is the basis for later resumable execution.

Run the built-in three-regime experiment:

```sh
clojure -M -m resolver-sim.research.scheduler-harness
```

Add `--edn` to print the full rooted experiment, including the frozen research
definition's case/measure axes, one rooted result matrix per scheduler, and
non-authoritative pairwise comparisons to the static baseline. The default
output prints raw integer metrics and committed roots. To construct a different
controlled regime from the REPL, call `workload` with `:id`, `:cpu-scale`,
`:gpu-scale`, `:tool-scale`, `:transfer-bytes`, and `:false-predictions`, then call `run-policy`
or `experiment`. The same rooted workload object must be passed to every
scheduler for a within-case comparison.

Run the fixed-granularity parameter sweep:

```sh
clojure -M -m resolver-sim.research.scheduler-harness --sweep
```

`--sweep --edn` emits the full data. The default 36-coordinate factorial sweep
crosses injected prediction success (`0`, `333`, `667`, `1000` permille), GPU
bottleneck scale (`1`, `2`, `4`), and per-parent-edge transfer bytes (`0`,
`12`, `24`). Accuracy is exact over the three predictable children: 333 and
667 permille mean one and two successful injected predictions. This keeps
prediction behavior auditable rather than sampling a random outcome.

For every coordinate, the sweep runs the same fixed-granularity workload under
all five policies. It produces one rooted `research-analysis` subject per
policy, ten rooted pairwise `research-analysis-comparison` records, and a
separately rooted derived-region artifact. That artifact preserves exact
pairwise `:pairwise/delta-ms` values (left policy minus right policy), explicit
`:pairwise/outcome`, global all-policy outcomes under
`:global-region/policy-outcomes`, and the declared `{:large-ms 10}` derived
margin labels: `:large-win`, `:small-win`, `:tie`, `:small-loss`, and
`:large-loss`. Raw integer deltas are canonical; labels are downstream
presentation only.

`:policy-region-summary` groups global wins, ties, and losses for each policy.
`:policy-regions` additionally emits canonical records containing a policy,
global outcome, axis min/max bounds, exact coordinate list, and count. The
companion human-readable summaries explicitly warn that bounds need not form a
contiguous region. `:decision-boundaries` classifies each bounded region as an
exact rectangular subset of the tested grid or `:irregular`; it never emits a
threshold rule from min/max bounds alone. These are exact simulator observations on the frozen
scope—not generalized performance claims. The sweep validates that its
canonical committed result root agrees across all five policies at every
coordinate and aborts on a mismatch. Adaptive
granularity is deliberately excluded here; use the original experiment's
fixed/adaptive cases as its separate ablation.

The fixed workload has 13 topologically ordered tasks, one final sink, two CPU
workers, one GPU worker, and one tool worker. Costs are declared per eligible
resource kind. Some tasks are CPU/GPU flexible; tool calls run on the tool
worker. Task results are canonical hashes of the task ID, canonical parent
roots, and logical piece count. The simulator validates the entire workload
root and the explicit prediction fixture before running.

The five policies share the task graph, costs, workers, canonical result
function, and commit check:

| Policy | Dispatch rule |
| --- | --- |
| `:static` | Each task stays on its fixed home worker. |
| `:work-stealing` | An idle worker takes eligible work from another queue in fixed donor order. |
| `:locality-stealing` | An idle worker selects eligible donor work using predicted transfer bytes, then execution cost. |
| `:resource-aware-stealing` | A non-speculative baseline places each ready task on the eligible worker minimizing estimated queue delay + dispatch/execution cost + known parent-transfer cost, with ordinary stealing thereafter. |
| `:speculative-stealing` | The locality policy may run a child while its predicted parent is still running. Ready authoritative work has priority. |

The speculative fixture gives children 3, 4, and 6 a predicted parent result;
the default injection makes child 4's prediction fail. The scheduler reads the
predicted root; `:injection/success?` records the controlled outcome for audit.
Every *started* speculative child requires a two millisecond verification job
on a CPU worker after its parent commits. The verifier compares the predicted
parent root to the canonical committed parent root. A mismatch discards all
work on that child and queues its ordinary execution. A match permits the
candidate to commit only after its deterministic task-result root is checked
against the actual committed parents. Speculative jobs cannot make external
side effects in this model.

Adaptive granularity is a separate Boolean ablation for **every** policy. On
task issue, a ready non-speculative task with at least two logical pieces and
enough work is split into two jobs only if two eligible workers are then idle.
One dispatch millisecond is charged per job, and each job pays any required
parent transfer. The logical task commits once all pieces finish, using the
same canonical result root as the unsplit task. This deliberately permits
granularity to lose when extra dispatch and transfer cost outweigh parallelism.

Accounting is explicit:

- `:makespan-ms` ends when the single sink commits. Consequently
  `:critical-path-latency-ms` is equal to makespan for this single-sink graph;
  `:critical-path-lower-bound-ms` separately reports the longest dependency
  path using each task's fastest declared resource cost divided by its maximum
  logical piece count, with no contention or transfer. It is intentionally a
  loose bound for both granularity settings.
- Utilization is busy worker ticks divided by total worker capacity, in
  permille. Per-kind utilization is also reported. Busy time includes
  speculative work, verification, and movement overhead.
- A steal is a job dispatched from another worker's queue. A failed steal is
  one idle search across donor queues that finds no eligible job. Identical
  empty queue states are searched only once until queue contents change.
- Accepted and discarded speculative work count executed worker ticks; task
  counts are separate. A discarded partial run is counted as wasted work.
  The harness separately records correct and failed declared predictions, plus
  `:speculative-discard-verification-failed`. A discard here means the
  declared commit predicate failed; unstarted work or an alternative
  authoritative execution is not relabelled as a failed prediction. Integer
  accepted, discarded, total-executed, and verification work are grouped in
  `:speculation/accounting`; any acceptance ratio is presentation-only and
  uses those integer numerator/denominator fields.
- Parent artifacts transfer when a job executes on a different worker from
  the parent result. The fixture declares bytes per parent edge; the model
  charges four bytes per millisecond. The final chunk's worker holds a split
  task's result.
- Resource interference is the number of GPU stall ticks when a heavy GPU job
  overlaps a heavy CPU job; in this simple shared-memory model the GPU stalls
  on odd ticks. This is an explicit model parameter, not a hardware estimate.
- `:committed-root-agreement` is one only when that run's sink root matches
  the independently recomputed canonical reference. The experiment also checks
  exact equality of sink roots across all five policies for every case and
  granularity setting; any mismatch throws.

The experiment freezes a `research-definition.v1` with six cases (three
regimes × fixed/adaptive) and integer measure axes. Each policy has a distinct
rooted implementation subject and result matrix. Measures use exact units:
milliseconds, bytes, permille, or counts. The matrix's zero requirements encode
nonnegative observed counters, while root agreement requires one; these are
basic domain checks, not performance targets. Raw metrics remain in each
`scheduler-run.v1`. `compare-reproductions` records expected differences
between policy matrices without assigning authority or claiming superiority.

## Repeat-scoped policy-analysis compilation

`research-execution-cell.v1` declares one policy, case, measure scope, and
repeat fixture. An execution manifest expands the frozen definition into the
complete policy × case × repeat set; its cells can run sequentially or through
the bounded executor without changing their semantic identities. Valid
committed cell results are retained by content root in the result store.

The analysis compiler consumes a frozen definition, manifest, result store,
one policy-subject root, and one declared repeat fixture to construct one
rooted `research-execution-policy-analysis.v1` artifact. It does not rank
policies and does not aggregate or average repeats. Its coordinate matrix is
dense: every declared case × measure coordinate is present. A completed cell
provides the exact integer observation; an absent cell is `:missing`; a
conflicting result binding is `:not-comparable`. If an executor report is
provided, a cell with an execution failure is explicitly recorded as
`:failed` in `:analysis/cell-statuses`; its coordinate remains `:missing` so
comparison consumers cannot treat it as an observation.

Failure receipts are intentionally not placed in the committed-result store:
that store admits only validated deterministic-commit results. This preserves
the distinction between a missing result, a failed attempt, and contradictory
result roots, without silently omitting any declared experiment cell.

## Controlled distributed-execution model

The research executor additionally supports `:distributed`. This mode is a
deterministic local simulation of semantically distributed workers; it does
not open network connections, register production workers, or assert anything
about deployed infrastructure. `:sequential` and `:thread-pool` retain their
existing local meanings. Host thread count and diagnostic verbosity are
runtime-only properties. Topology, availability, placement, transfer, retry,
and failure behavior are rooted research inputs.

### Distributed experiment profile

`distributed-experiment-profile.v1` contains sorted site, worker, link, and
dependency-replica declarations. Each worker declares its site, resource
capabilities, and availability. Each site and directed link declares
availability. A dependency result is available at the site where its producing
task completed and at any explicitly declared replica sites; it is not assumed
to exist on every worker.

Version 1 declares a symmetric integer distance relation. It validates
non-negativity, zero identity distance, and matching reverse links. It does
not claim triangle inequality, geographic units, or a linear/metric vector
space. Link distance, latency, and transfer quantity remain distinct:

```text
transfer time (integer ms)
= base link latency
+ link distance × declared distance cost per unit
+ ceil(transfer bytes / bandwidth bytes per ms)
```

A same-site transfer has zero distance, time, and moved bytes. Changing site or
worker availability, membership, capability, link availability, distance,
latency, bandwidth, distance cost, or dependency replicas changes the profile
root. Because the profile root is included in distributed execution cells,
those substitutions also change the cell and manifest roots.

### Explicit scheduling policy

`distributed-scheduler-policy.v1` is separate from executor implementation and
runtime configuration. It binds the selection rule, lexical worker-ID
tie-break, remote permission, retry limit and eligibility, home-worker
fallback, and transfer timeout. Version 1 provides:

| Policy | Selection semantics |
| --- | --- |
| `:local-only` | Execute on the workload's declared home worker. |
| `:distributed-work-stealing` | Select the eligible worker with earliest declared availability, ignoring transfer cost; ties use worker ID. |
| `:distributed-distance-aware` | Minimize deterministic finish estimate including dependency transfer; ties use transfer cost and worker ID. |

This is intentionally a small policy set. There is no policy DSL, random
optimizer, or learned scheduler. Policy parameters are semantic and rooted;
diagnostic verbosity and simulator host details are not.

### Dependency placement and typed failures

A selected task attempt constructs rooted dependency-set and placement
projections before execution. These identify dependency roots and source sites,
the selected worker/site, and selected transfer paths. The failure taxonomy is:

| Cause | Exact research meaning |
| --- | --- |
| `:distribution-dependency-failure` | The selected task attempt cannot obtain one or more declared dependencies because no usable declared source/path exists under the frozen profile and execution state. |
| `:remote-worker-unreachable` | The selected worker or its declared site is unavailable. |
| `:transfer-timeout` | The deterministic declared transfer time exceeds the rooted policy timeout. |
| `:distribution-preflight-mismatch` | The observed profile root does not equal the profile root bound by the execution cell. |

The first category does not mean a task exception, wrong output, speculative
prediction failure, verification failure, or application-level distribution
failure. It is independent of the identically spelled experimental category in
contract/replay appeal-bond tests; no schema, code, or semantics are shared.

`distributed-execution-failure-receipt.v1` is a rooted non-result artifact. It
binds the workload, profile, policy, manifest/cell when invoked through the
executor, deterministic attempt, task, worker/site, dependency set, placement,
transfer path where relevant, and typed cause. Exception text, stack traces,
timestamps, host, process, and thread details are runtime diagnostics and are
excluded from the receipt root.

A failed attempt never satisfies a cell result and is stored separately from
the content-addressed result store. With an eligible retry policy, history may
therefore be:

```text
attempt A -> rooted non-result failure receipt
attempt B -> rooted successful attempt -> committed result R
```

The cell's final semantic state is complete with `R`; attempt A remains in its
forensic history and is not a conflicting semantic result.

### Forensic evidence and provenance

Canonical evidence follows the projection pattern rather than treating logs as
authority:

```text
frozen workload + rooted profile + rooted policy
  -> dependency set + placement projection
  -> rooted attempt or typed failure receipt
  -> deterministic semantic commit
  -> cell result
  -> analysis/comparison
```

Artifacts retain separate roots for workload, profile, policy, dependency set,
placement, attempt/failure, scheduler run, cell result, and semantic result.
They form a discoverable evidence DAG instead of one opaque experiment hash.
Consequently a result from experiment A cannot satisfy a cell from experiment
B merely because output bytes match. Historical receipts do not change when a
worker reconnects; changed availability belongs to a new rooted profile or
attempt context.

Runtime scheduling and physical execution details may vary, but successful
distributed commits are checked against the same canonical workload result as
local execution. Placement, makespan, transfer, and failure/retry evidence may
change; task semantics may not.

### Initial distance sensitivity

The fixed 12-byte dependency fixture produced these controlled integer model
times for ordinary distributed work stealing:

| Declared distance | Makespan (model ms) |
| ---: | ---: |
| 0 | 63 |
| 1 | 67 |
| 2 | 71 |
| 4 | 80 |
| 8 | 86 |

The matching local-only fixture is 81 model ms. Thus ordinary remote stealing
is beneficial through distance 4 on this tested grid and harmful at distance 8;
this is an exact fixture observation, not a generalized threshold or hardware
claim. Every successful row commits the same canonical workload root.

### Future remote-runner assurance model

The remote-runner seam targets consequential computation under the principle
**trust the calculation, not the runner**. Runners may be faulty, malicious,
misconfigured, pseudonymous, or anonymous. The present implementation is a
forensic execution model and provenance-ready simulator; it is not a
tamper-proof remote execution system.

The intended flow is:

```text
canonical job
+ rooted execution environment
+ explicit execution/eligibility policy
  -> eligible opted-in runner assignment
  -> forensic attempt and result
  -> optional independent repeated runs
  -> non-authoritative corroboration view
  -> separate primitive validation or proof
```

`research-execution-environment.v1` freezes the executable-environment identity
independently of runner identity. Anvil is the first supported declaration kind.
It binds the Anvil version, chain ID, genesis or fork source identity and block,
known address/code-root pairs, initial state commitment, and declared stable
configuration. It does not start Anvil or assert that these declarations were
observed on a real process.

`research-run-evidence.v1` provides future cutpoints for:

```text
environment-before-root
action-input-root
environment-after-root
semantic-result-root
```

The current action commitment must equal the canonical workload root. A future
Anvil adapter must derive the before/after commitments from canonical state
projections and bind actual tool/code/config observations. That is the point at
which storage edits, balance changes, substituted bytecode, changed chain
parameters, fork substitution, input substitution, and execution-state edits
can be detected by replay or verification. Current `:declared-simulation`
evidence only provides the structural binding and makes no cryptographic or
interference-prevention claim. Stdout and stderr remain diagnostics.

`runner-participation.v1` independently declares a runner reference,
disclosure mode (`:identified`, `:pseudonymous`, or `:anonymous`), site,
capabilities, supported environment kinds, accepted job classes, availability,
opt-in, and opaque qualification references. A runner can be capable and
online but not opted in, or opted in but policy-ineligible. Anonymous runners
do not require a real-world identity field; a later qualification proof can be
referenced without changing this distinction. No credential, experience, or
reputation assertion is created here.

`research-execution-policy.v1` constrains runner selection separately from
placement. It binds required capabilities, allowed disclosure modes, opaque
qualification references, supported environment kinds, opt-in requirements,
public attribution mode, execution objective, run count, required matching
count, repeat-runner policy, and explicit distinctness dimensions. Supported
objectives are `:fastest` and `:corroborated`; `:lowest-cost` remains rejected
because no research cost model exists.

Distinctness is exact:

- `:runner-identity` compares canonical runner references;
- `:site` compares declared site IDs;
- different worker IDs alone establish neither runner nor site independence;
- no claim of organizational or real-world independence is made.

`research-execution-job.v1` binds workload, environment, distributed profile,
distributed placement policy, and execution policy roots. Its independent run
slots can be stolen by idle participating runners. Selection occurs only when
the runner opted in, is available, accepts the job class, supports the
environment, satisfies capability/disclosure/qualification-reference filters,
and does not violate existing runner/site assignments. An ineligible runner is
excluded before placement; this is not an execution failure receipt.

Every assignment and completed run retains separate runner, environment, job,
profile, policy, placement/attempt, failure, distributed-run, and semantic
result roots. Two runners can therefore return the same semantic result while
retaining different forensic evidence roots. Internal runner attribution is
always retained. Policy can omit it from the published-result projection
without deleting forensic provenance.

The current corroboration view only groups constituent run-evidence roots by
semantic result root. It is deliberately non-authoritative and creates no
accepted-result artifact. Matching runners do not establish mathematical
correctness, a proof, claim acceptance, general incentive compatibility, or
application authority. This is repeated execution, not voting, consensus, or
Byzantine fault tolerance.

This structure remains compatible with a later pro-rata path:

```text
canonical request + allocation policy
  -> distributed execution job and repeated forensic runs
  -> corroboration
  -> independent primitive validation/proof
  -> AllocationBindingV1
  -> later admission/application
```

The distributed layer does not redefine or implement pro-rata arithmetic.
Execution provenance, primitive correctness, cryptographic proof, and
application authorization remain separate. Likewise, stable attempt, result,
and future corroborated-result roots can be downstream incentive targets, but
correctness, eligibility, incentive qualification, and payment authorization
remain distinct. No economic fields are present in these artifacts.

### Dependency, implementation, validation, and proof foundations

Remote-run assurance composes with the existing job and run evidence; it does
not revise their V1 identities. `research-dependency-manifest.v1` declares the
exact artifact and provenance roots for each dependency. Supported classes are
PRF framework, PRF extension, registry snapshot, Clojure model, scenario
corpus, Solidity source/build, deployed EVM contract, Anvil/Foundry,
proof-program/verifier, runtime/toolchain, local AI model, dataset/fixture, and
hermetic environment. Class metadata can carry such facts as a logical version
or chain/address/code/state basis, but symbolic names never replace the
artifact root.

`research-dependency-policy.v1` declares required classes and permitted
visibility/acquisition modes. `resolved-research-dependency-set.v1` binds every
manifest entry to immutable acquisition evidence. Resolution is idempotent:
the same manifest, policy, and resolution evidence reproduce the same root.

Dependency visibility and acquisition are paired explicitly:

| Visibility | Acquisition mode | Current meaning |
| --- | --- | --- |
| `:public` | `:public-by-root` | Public content identity; no download occurs here. |
| `:runner-readable-private` | `:authenticated-private` | Private acquisition evidence without requiring a public URI or plaintext. |
| `:sealed-to-execution-environment` | `:attestation-gated-sealed` | Declaration-only encrypted-bundle/environment binding for a future attested key-release flow. |

An ordinary readable-private resolution cannot satisfy a sealed dependency.
No confidential-computing or attestation claim is made. The intended future
flow remains encrypted bundle → attested environment → policy-gated key release
→ execution without runner plaintext access.

`research-implementation-identity.v1` binds an algorithm/mechanism ID, exact
source or artifact root, language/runtime family, semantic version, and exact
resolved dependency closure. Distinct roots mean only **artifact-level
implementation distinctness**; they do not prove intellectual,
organizational, or failure independence. Runner identity, implementation
identity, prover identity, and verifier identity remain separate.

`research-execution-assurance-policy.v1` composes the existing execution and
dependency policies. It independently declares minimum distinct runner roots,
site IDs, and implementation roots. For example, three required runs may
require three runner identities but only two implementation identities. None
of these dimensions implies another.

Canonical execution/prover/verifier participant sets are sorted by stable
identity root before dense indices are assigned. These indices are nominal,
root-bound lookup coordinates only. Arrival, registration, assignment,
completion, and queue order cannot alter the participant set/root. No speed,
distance, eligibility, reputation, or reward-weight meaning is attached, and
the governed researcher axis remains unchanged.

The framework-fixed validation floor cannot be replaced or disabled by policy.
It covers supported schema/version, canonical root recomputation, job,
assignment and run-slot binding, dependency declaration/resolution binding,
environment identity, action/input binding, result canonicalization and job
provenance, and current control-state consistency. Policy can only add rooted
validation requirements such as replay, invariant suites, independent
implementation, proof generation, verifier-registry selection, qualification,
or stronger evidence profiles.

`research-fixed-validation-receipt.v1` records those fixed checks.
`research-run-assurance-evidence.v1` then composes the existing run evidence,
resolved dependency closure, selected implementation identity, and validation
receipt. Its semantic status is `:execution-result-only`. Where a canonical
primitive validator exists, that independent validator must pass before any
downstream code represents the result as that validated primitive.

`research-proof-generation-receipt.v1` provides a first-class, unverified
proof-generation seam. It binds the assured job, resolved dependencies, run
assurance/result, proof system, program, public-values input, externally rooted
proof artifact, prover participation, environment, and verifier-registry root.
Raw proof bytes are not embedded. The execution runner need not be the prover,
and neither is the verifier.

The existing generic verifier-registry root projection remains the authority
binding for verifier/program/VKey selection. It identifies which proof
mechanism may verify an already-selected computation; it does not choose or
redefine the computation. Cross-job and cross-dependency proof receipt reuse is
rejected before verification.

Proof generation is not proof verification. Proof verification is not
corroboration. Neither forensic provenance nor corroboration establishes
primitive validity, and none establishes application or direct-consumption
authority. Present-tense admission policy must make those later decisions;
there is no `:directly-consumable?` field.

Content-addressed result insertion remains idempotent. Repeating an immutable
dependency resolution reproduces its root. Duplicate run-evidence or proof
receipt roots count once toward any assurance threshold. No corresponding
claim is made for economic or application consumption.

### Future seams and incentive boundary

Physical remote hosts, network protocols, durable attempt stores, remote
attestation, and production orchestration remain deferred. A later adapter may
realize the same rooted profile/policy semantics on real hosts, but must not
reinterpret historical simulator artifacts.

Actual remote Anvil support additionally requires isolated process lifecycle,
canonical state projection at declared cutpoints, observed tool/version and
bytecode checks, action submission, output capture, replay verification, and a
typed environment-verification failure artifact. Environment mismatch causes
are not added to the existing distribution-failure V1 taxonomy because no such
verification currently executes; adding them later should use a new schema or
separate environment failure receipt rather than silently widening V1.

Research-only incentive policies may later consume rooted execution evidence
as separate downstream subjects. They must preserve `result validity !=
incentive eligibility != payment authority`. This slice adds no payments,
staking, slashing, production worker economics, or scheduler-specific bounty
system, and imports no pro-rata allocation arithmetic.

## Semantic parallelism design spike

Scheduling changes runtime ownership only. A task input/result is a
**constituent**; CPU, GPU, tool, and transfer capacity are **consumed** runtime
resources; the rooted downstream task result is a **consequence**. Stealing a
task therefore does not consume or transfer semantic ownership of its input.

The one concrete safe parallelization operation already declared by this model
is split-task recombination: independent logical pieces may run in any order,
then recombine through `scheduler-task-result.v1`, which hashes the fixed task
ID, canonical parent roots, and declared piece count. Its obligations are:

| Property | Status | Consequence |
| --- | --- | --- |
| Deterministic recombination | Declared and tested | Different placement/order preserves the task root. |
| Associative/commutative piece reduction | Not declared | Pieces cannot be arbitrarily reduced or cached as partial semantic values. |
| Idempotence | Not declared | Duplicate execution requires deterministic commit suppression; it is not semantic reuse. |

No metric/vector/weight interpretation is assigned to the nominal,
root-bound researcher axis or to research-analysis coordinates. In particular,
this experiment does not use dot products, norms, projections, or matrix
operations on them. A future algebraic acceleration must name a concrete
operation, its declared properties, a counterexample when a property is absent,
and a root-equality test for permitted reordering/splitting.

## Hardware-calibration seam

Model milliseconds are controlled fixture costs, not physical benchmark
results. The workload cost map is the calibration seam: a later, separate
measurement adapter may provide rooted integer observations for CPU-heavy,
GPU-heavy, transfer-heavy, verification, tool, and speculative task classes,
then construct a new rooted workload configuration. It must not retroactively
reinterpret an existing sweep root or turn this simulator into a live runtime.

The experiment's comparison lineage is [HEFT](https://disco.ethz.ch/courses/fs14/seminar/paper/Jochen/4.pdf),
[StarPU](https://starpu.gitlabpages.inria.fr/features.html),
[Parla](https://github.com/ut-parla/Parla.py), and
[Specx](https://arxiv.org/abs/2308.15964) for heterogeneous task placement;
[Parsl](https://arxiv.org/abs/1905.02158) and
[Colmena](https://doi.org/10.1177/10943420241288242) for dynamic research
workflows; [LLMCompiler](https://arxiv.org/abs/2312.04511),
[Parrot](https://www.usenix.org/conference/osdi24/presentation/lin-chaofan),
and [Teola](https://arxiv.org/abs/2407.00326) for LLM application dataflow;
and [Speculative Actions](https://arxiv.org/abs/2510.04371),
[Sherlock](https://arxiv.org/abs/2511.00330), and
[B-PASTE](https://arxiv.org/abs/2604.16469) for agent speculation. The
prototype establishes no novelty claim against those systems.

Focused checks:

```sh
clojure -M:test -e '(load-file "test/resolver_sim/research/scheduler_harness_test.clj") (let [r (clojure.test/run-tests (find-ns (symbol (str "resolver-sim.research." "scheduler-harness-test"))))] (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))'
```
