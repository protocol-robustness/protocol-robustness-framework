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
- Independent-implementation reproduction: deferred; genuinely different implementation.
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
