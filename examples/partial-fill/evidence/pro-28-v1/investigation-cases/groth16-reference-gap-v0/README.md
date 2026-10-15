# PRO-28 Groth16 reference-check investigation

This V0 case turns the reconstructed `cryptographicVerification: not verified`
gap into one bounded local task. It reads the raw PRO-28 execution bundle,
reconstructs the report again, and requires it to equal the retained report
whose SHA-256 is pinned in `case.json` before starting the proof check.

Run from the repository root:

```bash
python3 scripts/forensic/investigation_dispatcher.py \
  examples/partial-fill/evidence/pro-28-v1/investigation-cases/groth16-reference-gap-v0/case.json \
  --output-dir examples/partial-fill/evidence/pro-28-v1/investigation-cases/groth16-reference-gap-v0
```

The only supported action is `sp1-groth16-reference-check.v1`. The runner
checks the captured Foundry 1.7.1 commit, verifies local `anvil` and `cast`
binary hashes, launches the existing direct SP1 reference checker on disposable
local Anvil with fixed resource limits and a timeout, and retains the result as
content-addressed candidate evidence. The case root hashes sorted compact UTF-8
JSON for the complete case request; the task root hashes that case root plus
the gap, operation, deployment manifest digest, report digest, proof and public
value identities. These dispatcher roots are execution bindings, not PRF
canonical evidence roots.

The output directory also contains `CASE_ID/journal/events.jsonl`, an
append-only, hash-chained execution history, and `state.json`, a rebuildable
projection. Each invocation replays and validates the journal under an
exclusive lock. A task left at `running` by a process stop is recorded as
interrupted and may resume because this allowlisted verifier is explicitly
local and read-only. A completed task returns its existing content-addressed
result without executing again. Retryable tool/provider failures require an
explicit `--retry` and remain bounded by three attempts and a 600-second
case budget; invalid proof results are terminal.

An independent process can replay the retained history without executing or
mutating the task:

```bash
python3 scripts/forensic/investigation_dispatcher.py CASE.json \
  --output-dir CASE_OUTPUT --verify-journal
```

This reconstructs the task identity from the case and raw bundle, checks the
entire event hash chain and the projection checkpoint, and prints the replayed
status plus history/projection roots. The event chain detects accidental or
unauthorized edits but is not a signature or an authenticated PRF evidence
root.

For example, an operator retry is explicit:

```bash
python3 scripts/forensic/investigation_dispatcher.py CASE.json \
  --output-dir CASE_OUTPUT --retry
```

Use `--cancel-reason "operator reason"` to append a cancellation record without
running the verifier. Use `--metadata-event FILE.json` to append one closed
event with `kind` (`hypothesis` or `evidence-request`), `id`, `status` (`open`,
`blocked`, `conflicted`, or `resolved`) and an immutable `artifactRoot`.
Metadata states are investigation coordination projections; they do not
validate a hypothesis or close a forensic gap. Replaying or editing them never
changes canonical evidence or admits a finding. Journal corruption or a
definition/input mismatch blocks resumption instead of rewriting history.

The candidate's `execution.status` records task execution only. Its embedded
reference report says whether the pinned verifier accepted the proof. The
candidate is not admitted into the canonical evidence graph, and it does not
create researcher acceptance, reward eligibility, or a payment obligation.
PRO-36 owns independent validation before graph admission. This local check
does not establish public-chain execution or finality.
