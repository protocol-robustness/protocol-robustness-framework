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

The candidate's `execution.status` records task execution only. Its embedded
reference report says whether the pinned verifier accepted the proof. The
candidate is not admitted into the canonical evidence graph, and it does not
create researcher acceptance, reward eligibility, or a payment obligation.
PRO-36 owns independent validation before graph admission. This local check
does not establish public-chain execution or finality.
