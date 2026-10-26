# Qualification attempt aborted: out of memory

This is an incomplete local attempt. It is retained to explain why the run
directory has source inventories and a prover input but no proof fixture or
final evidence manifest.

- Scope: local Anvil, chain ID 31337; no public-chain execution.
- Prover: built from the pinned coprocessor source resolved by
  `scripts/resolve-prf-coprocessor.sh`, with the locked SP1 6.3.1 dependencies.
- Completed before termination: source-state capture, deployment, and
  `source-bound-v2-input.json` generation.
- Not completed: Groth16 proof generation, verifier checks, Gate E test,
  assured-operation conformance tests, post-run source inventories, or final
  evidence manifest.
- Failure evidence: the host kernel log records
  `oom_reaper: reaped process ... (source-bound-v2)` at 2026-10-25 18:07 UTC.

This attempt is not a qualification result. No retry is included in this run.
A later environment inspection found about 10 GiB available memory and 6.1 GB
free workspace volume; the exact peak memory required by this Groth16 proving
path and a supported low-memory parallelism control have not been established.
Do not blindly retry on this host or treat a partially generated artifact as
evidence. A retry needs a fresh artifact directory and a measured memory plan
or a host with sufficient headroom, followed by the complete verification and
settlement gates. No retained artifacts from this attempt should be used as
proof or settlement evidence.
