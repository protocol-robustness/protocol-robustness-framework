# Local DAG scheduling experiment

This is a small deterministic **simulation**, measured in integer model
milliseconds. It tests scheduling mechanics and commit invariance on local
agent/research DAGs. It does not measure hardware throughput, model quality,
token-level inference, or a live tool service.

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
