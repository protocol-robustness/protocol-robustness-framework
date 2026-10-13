# Investigation Dispatcher V0

V0 reconstructs the retained deployment-bound local pro-rata evidence and
produces a bounded investigation work plan. It runs the existing offline
validator before selecting actions. It accepts raw bundle inputs rather than
a caller's verification report or asserted verified graph.

```bash
clojure -M:with-sew -m resolver-sim.evidence.investigation-dispatcher plan \
  /path/to/prf-withdrawal-independent-replay \
  /path/to/new-investigation-output

# Run in a fresh process against the same retained evidence.
clojure -M:with-sew -m resolver-sim.evidence.investigation-dispatcher verify \
  /path/to/prf-withdrawal-independent-replay \
  /path/to/new-investigation-output/investigation-plan.edn
```

The output directory must have an existing parent and must not exist itself.
`investigation-plan.edn` is a diagnostic work record, not a new canonical
evidence schema or admission record. Its `plan-root`, input inventory,
graph-input identity, gap identities and task identities use PRF's existing
`evidence-record` hash intent. They do not replace any source, deployment,
operation or proof identity. The source-bundle reference is labelled declared
because its payload is missing from this retained bundle.

Every task binds the reconstructed input inventory, graph node hashes,
operation/configuration context, originating issue and action definition.
Issue order is deterministic; reconstruction follows its prerequisite tasks.
The API accepts only `:max-tasks` (default 32, range 1–100). Excess tasks remain
explicitly deferred. Execution attempts and retries are both zero.

| Issue | Planned action | Limitation |
|---|---|---|
| Missing source or observation | Request evidence | No acquisition occurs |
| Unverified claim | Request independent validation | Blocked until a qualified validator is available |
| Conflicting evidence | Request conflict review | No observation is selected as authoritative |
| Required attestation | Request policy-qualified attestation | Requires policy and attestor authority |
| Optional research or reward artifact | Record explicit exclusion | Never an evidence-closure prerequisite |
| Unsupported issue/profile/protocol | Manual review | No guessed runner or fallback |

The one named runner is the existing deployment-bound forensic reconstruction
command. It is listed as a proposed follow-up after unresolved evidence tasks;
V0 does not execute it. The SEW v1 protocol validator remains in the existing
Python dispatcher. This separate V0 entry point supports only the local Gate E
successor bundle profile and blocks declared protocol descriptors requiring
other routing. The generic Python reproduction runner expects a different run
bundle and is not selected for this fixture.

`verify` compares the complete saved work plan with a fresh reconstruction.
A successful comparison means that planning is reproducible. The separately
reported evidence validity and investigation status may still be false and
incomplete. Editing a task to `complete`, changing a runner, dropping a conflict,
or asserting proof validity fails this comparison. A source artifact change
requires a new plan; V0 does not mutate or migrate an existing case.

The retained 35/21/14 bundle produces evidence requests and blocked conflict/
validation tasks. In particular, Carol's recorded transfer has a non-withdraw
outer-call context, so investigation remains incomplete. Cryptographic proof
verification, authenticated runtime/block observations and source payload
acquisition remain evidence requirements.

Mutable case/event history, execution, retries, evidence ingestion, distributed
delegation, researcher acceptance, reward obligations and payments are deferred
to later versions. V0 grants none of those authorities.

Run the focused tests from the checkout:

```bash
clojure -M:test:with-sew -e '
  (require (quote resolver-sim.evidence.investigation-dispatcher-test)
           (quote resolver-sim.evidence.deployment-bound-test)
           (quote resolver-sim.evidence.forensic-graph-test)
           (quote resolver-sim.evidence.node-test))
  (let [r (clojure.test/run-tests
            (quote resolver-sim.evidence.investigation-dispatcher-test)
            (quote resolver-sim.evidence.deployment-bound-test)
            (quote resolver-sim.evidence.forensic-graph-test)
            (quote resolver-sim.evidence.node-test))]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))'
```
