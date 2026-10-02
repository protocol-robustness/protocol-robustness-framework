# First real-engine reproduction

This integration reproduces the existing exact coordinate-interaction analysis,
not the frozen affine qualification computation. Reproduction is evidence, not
proof of a general claim or claim acceptance.

## Candidate audit

- `coordinate-interaction/difference-of-differences`: two rooted axis declarations,
  explicit levels and four scoped observations; computes a rooted structured
  interaction with exact integer arithmetic. Deterministic, no external services;
  authoritative fixture is the existing semantic-accounting test (10, 13, 14,
  25 → interaction 8). No expected output argument. Selected: smallest real engine.
- `accounting-basis-comparison/compare-bases`: two canonical accounting requests
  and partition plans; freshly reduces accounting source rows and computes exact
  category deltas. Deterministic, no external services; existing accounting tests
  supply authoritative results. No expected output argument. Larger replay bundle.
- `research-analysis-comparison/compare-analyses`: two recorded analyses; computes
  a structured coordinate comparison deterministically without an expected comparison.
  Replays comparison only, not the computations behind those analyses; input-root
  validation is weaker than a full source-artifact reconstruction.
- Analysis output construction/verification: operates on supplied results, not a
  replayable computation. Excluded. Authority functions remain unchanged.

## Narrow adapter and identities

`interaction-reproduction/build-input` embeds only the four required engine arguments
in `reproduction-input.v1`, with computation kind
`:research/integer-difference-of-differences-v1`. Unknown keys, including expected
results and runtime metadata, are rejected. Embedded axis/observation data is needed
for replay; unresolved references alone would not be executable.

The concrete framework gap was scalar-only qualification validation. The new adapter
uses the existing reproduction domains/envelopes with structured interaction results
and engine-specific validators. Qualification artifacts, APIs and roots are untouched;
their execution validator intentionally does not accept structured engine executions.

`implementation` binds actual engine source-file SHA-256, algorithm identity,
Clojure/JVM family, semantic version and a supplied resolved dependency-set root.
`execute` checks the source identity and takes exactly input and implementation.
It invokes the existing engine, never comparison or expected output. Structured
results remain intact. Exceptions become canonical failed executions without messages,
paths or runtime timestamps. A different version/closure identity changes execution
identity, even if result identity agrees; this is not independent implementation.

The implementation factory currently reads the repository-relative source file;
execution therefore requires that source resource in its operational working directory.
Paths never enter the artifacts. The adapter binds the closure reference but does not
resolve/attest its contents or pin/observe the JVM binary. Observation source roots are
references under the existing engine semantics, not independently verified source bodies.

## Comparison and evidence

Only `compare-execution` receives the authoritative result, after execution.
Comparable outputs must share method, axis roots, levels, observation roots,
analysis roots and arithmetic semantics and have numeric interaction statuses.
Full structured equality means `:reproduced`; materially different rooted output
in the same scope means `:counterexample-found`. The complete fresh result/root
is retained as the witness. Incompatible schemas/scopes, missing or not-comparable
engine outcomes remain `:not-comparable`; execution exceptions yield `:failed`.
Root validation detects mutation, not truthfulness of an authoritative declaration.

`evidence-node` rechecks the comparison and uses the existing generic forensic
node builder with the evidence as subject and input, implementation, execution and
authoritative result as causal roots. This is an attachment point for claim evidence,
not a second claim-authority path. No claim is created, verified or accepted here.

The evidence ladder remains:

- Source projection: inspect/project a supplied authoritative result.
- Same-implementation reproduction: freshly rerun canonical input through identified code.
- Independent-implementation reproduction: a separately written interaction reducer
  now computes equivalent results using row contrasts (see below).
- Counterexample: retain a fresh materially disagreeing computation, never vote it away.

Runner, assignment, host, transport, timing and completion order are outside the
execution API and canonical artifacts. They may be attached as forensic evidence.
The new path adds no distributed transport, proving, incentives or acceptance logic.

## Durable runner dispatch

The same replay bundle can be dispatched as existing whole-job runner work. Its
work envelope binds the job, assignment, opted-in runner, reproduction input and
implementation identity. It contains no original result. The coordinator checks a
returned execution against its persisted dispatch intent before admitting it;
repeated admissions are idempotent per assignment and reproduction execution.
This retains separate forensic assignments even when they produce the same
semantic reproduction-execution root.

The runner journal retains accepted work, attempt state and the completed
reproduction execution. It writes the completed execution before making the result
post. On restart, pending or unreachable result delivery re-posts the stored
execution; an interrupted pre-completion attempt follows the existing fresh-retry
rule. For reproduction work, a process found in `:executing` after restart is
recorded as `:outcome-unknown` (not completed); the next valid delivery starts a
new attempt. The authoritative result is supplied only to the caller-side comparison
after the execution is retrieved. Thus local and remote execution roots match
while runner, assignment and delivery evidence remain separate.

Cancellation is checked before the deterministic computation begins. This small
computation is atomic; cancellation observed after it completes leaves its rooted
execution in the journal and suppresses result posting under the existing
cancel-before-delivery behavior. The transport remains at-least-once and
coordinator ingestion is idempotent. These tests use local HTTP and durable
journals; they do not assert performance or multi-host behavior. The process-kill
qualification forcibly terminates runner JVMs before execution and after the
durable `:executing` transition, then restarts them against the same journal.
Coordinator crash after result persistence but before acknowledgement is still
covered by existing in-process restart/idempotence tests, not a literal
coordinator-process kill test.

## Live multi-host qualification

`multi-host-reproduction-preserves-semantic-roots` is an opt-in integration
test for a runner on a separate host. It dispatches the same rooted work bundle
used by local runner tests and checks the returned input, implementation,
execution root, `:reproduced` comparison, and coordinator admission against a
fresh local execution. Runner identity and assignment remain forensic context;
they do not alter the semantic execution root.

The test uses SSH local/reverse forwarding on loopback, not a public listener:

```sh
# From the coordinator machine; keep this session open.
ssh -o ExitOnForwardFailure=yes -N \
  -L 127.0.0.1:39106:127.0.0.1:39105 \
  -R 127.0.0.1:39107:127.0.0.1:39107 ubuntu@<runner-host>

# On the runner host, from the synchronized PRF checkout.
clojure -M:test -m resolver-sim.research.remote-runner-fixture \
  durable-reproduction-test-secret 39107 /tmp/prf-multihost-runner-state.edn

# On the coordinator machine, from the PRF checkout.
PRF_REMOTE_RUNNER_URL=http://127.0.0.1:39106 \
PRF_REMOTE_COORDINATOR_PORT=39107 \
clojure -M:test -e "(require 'clojure.test 'resolver-sim.research.reproduction-runner-test) (clojure.test/test-vars [#'resolver-sim.research.reproduction-runner-test/multi-host-reproduction-preserves-semantic-roots])"
```

The fixed loopback ports and test secret are runtime-only lab settings, not
canonical inputs. Stop the runner and forwarding session after the test. This
qualifies cross-host dispatch over an authenticated SSH tunnel; it does not yet
qualify direct public network reachability, multi-host cancellation, failure
recovery across hosts, or independent implementation reproduction.

## Dependency-closed interaction reproduction

`interaction-reproduction/build-dependency-closed-input` establishes the next
reproduction tier for a small deterministic scheduler-analysis fixture. Its
input contains the declared research-definition source, selected two-by-two
parameter axes, the makespan measure, static policy and a repeat descriptor—
never the four expected observations or their expected interaction. Execution
freezes that source before generating any cases.

Each fresh execution resolves its declared dependency closure, regenerates the
four execution cells, stores the new cell results, compiles the policy analysis,
derives four source-bound observations, and only then runs exact
difference-of-differences. The reproduction execution retains roots for the
dependency manifest/set, upstream execution manifest, compiled analysis, four
cell results and four observations. These are provenance for the fresh
computation, not caller-supplied results.

Comparison remains strictly downstream: a prior authoritative interaction may
be provided only after the fresh execution returns. Two executions with equal
roots are same-implementation reproduction, not independent-implementation
corroboration. Changing any declared research source input changes the replay
input and dependency/implementation closure identities. Runner, host, timing,
assignment and completion order remain forensic runtime context.

## Artifact-distinct interaction implementation

`interaction-contrast/compute` is a separately written reducer: it computes the
upper-row contrast minus the lower-row contrast, rather than calling the original
four-term reducer. `interaction-reproduction/contrast-implementation` binds its
source bytes through `research-implementation-identity.v1`. The existing replay
input, execution, comparison and runner work schemas are unchanged. Runner
admission recognizes the two explicit implementations, not arbitrary source roots.

For equal inputs, both reducers produce the same canonical interaction result.
Their implementation and reproduction-execution roots differ; comparison may
still be `:reproduced`. Expected results remain available only to comparison.
Tests disable the original reducer while executing the alternative, check signed
and arbitrary-precision integer examples, retain disagreement witnesses, and
dispatch the alternative through the existing local HTTP runner substrate.

This establishes **artifact-level independent implementation of the interaction
reduction only**. It does not establish organizational independence, independent
upstream observations, or a second implementation of the dependency-closed
scheduler pipeline. Version-only changes are not independent implementations.
Arithmetic uses Clojure checked integer operations, as does the original engine:
bounded-long intermediate overflow can fail, and equivalent formulas can have
different overflow boundaries. Arbitrary-precision inputs avoid that limitation;
no universal bounded-long equivalence or performance advantage is claimed.

Source-projection evidence remains distinct. Reproduction adds evidence nodes;
it does not modify verification, claim derivation, acceptance or researcher
positions, and establishes neither proof nor application authority.
