# Fixed-chunk boundary audit

## Boundary status

The local canonical path is:

```text
derive fixed chunks -> register -> claim -> authorize closed execution
-> shared bounded scheduler -> descriptor-order normalization
-> benchmark-detached-chunk-result.v1 -> verified completion
-> terminalize -> reconcile/reduce/publish
```

## Cross-process field classification

### Fixed chunk descriptor

Semantic/content-addressed:

- `:chunk/id`
- `:run-plan/root`
- `:execution-plan/root`
- `:chunk/expected-input-root`
- `:chunk/expected-work-root`
- `:chunk/expected-sensitivity-root`
- ordered `:chunk/execution-ids`
- `:chunk/work-items` (closed plan-entry data; must remain canonical data only)

No functions, atoms, futures, executors, or whole unrestricted plan are present in the derived descriptor.

### Claim

Semantic fields are copied from the registered descriptor. Operational fields are:

- `:run-id`
- `:lease/token`
- `:lease/expires-at`
- `:fence`

The claim map is data-only. The local coordinator's atom-backed state is not part of the claim and must not cross a process boundary.

### Closed execution input

The intended cross-process representation is the resolved ordered execution-entry vector. It must contain canonical descriptors, execution IDs, ordinals, and rooted input material references/identities. It must not contain callbacks, atoms, executor/future objects, or unrestricted plan state.

Current local execution additionally resolves filesystem-backed sources and protocol adapters. Those are local-only implementation details and identify the remaining remote material-distribution seam.

### Detached result

`benchmark-detached-chunk-result.v1` is data-only and content-addressed:

- chunk, run-plan, execution-plan, input, work, and sensitivity roots;
- exact ordered execution IDs;
- aligned staged artifact-manifest roots;
- derived `:chunk/result-root`;
- derived detached-result root.

Lease, fence, worker, thread, JVM, completion timing, and retry identity are excluded.

### Completion request/result

Completion requests contain run/chunk identity, lease token, fence, and detached result. Tokens/fences are operational; the detached manifest is semantic. Completion results contain outcome/reason/root data only. No local mutable coordinator state or executor object is exposed.

## Remaining remote material boundary

Before a remote worker can execute, a future implementation must distribute and independently verify:

```text
claimed chunk
  + immutable executable distribution identity
  + exact protocol/source provenance
  + exact resolved input material
  -> remote execute-chunk!
```

The executable distribution identity must remain distinct from Git/jj source provenance. Sensitivity remains precommitted input to claim/placement authorization; requester preference cannot widen it. At-least-once physical execution is compatible with semantic result identity because completion is fenced and idempotent.

No transport, placement engine, container/runtime choice, telemetry, or incentive mechanism is selected by this audit.
