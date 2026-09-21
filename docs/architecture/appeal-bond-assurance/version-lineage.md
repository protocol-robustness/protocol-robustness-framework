# Appeal-bond semantic version lineage

This record tracks the model/reference boundary for appeal-bond and reversal-slash work. Historical and candidate identities are recorded separately; unresolved source/deployment bindings remain explicit `:unknown` values.

## Lineage records

| Stage | jj change ID | Solidity/reference ID | Status |
|---|---|---|---|
| Deployment-era Sew target | `sew-v1x-deployment-era.edn` | `8f7fa973...` documentation commit | Source/build commit and runtime codehash remain `:unknown` |
| July 31 corrected reference | `sew-v1x-appeal-bond-correction-2026-07-31.edn` | `5f84818d...` + `49593286...` | Historical correction / BondLedger review checkpoint |
| September candidate | `sew-sep-2026-appeal-candidate.edn` | `:working-copy` | Candidate compatibility/native work; not deployed |
| Reversal-slash closure | `TBD` | `TBD` | Blocked by DR-N-002 semantic closure |
| Canonical/deployment alignment | `TBD` | `TBD` | Must not begin before semantic closure |

## Required manifest for each committed stage

Each stage must record:

- parent jj change ID;
- resulting jj change ID;
- Solidity/reference change or release ID, if applicable;
- scenario corpus revision or scenario file hashes;
- focused tests and scenario outcomes;
- `bb validate` and `bb backstop` results;
- known unresolved failures and whether they are model, scenario, or infrastructure failures.

## Current semantic delta

The appeal-bond work has added or changed:

- authoritative bond custody and deterministic bond identity;
- refund, distribution, and forfeiture dispositions;
- per-bond claims and disposition lineage;
- authority-bound appeal handling;
- custody/conservation and evidence coverage;
- explicit experimental-only distribution-dependency fault injection;
- canonical-safe run finalization and replay propagation.

The July checkpoint is hash-bound by `docs/assurance/checkpoints/sew-v1x-appeal-bond-correction-2026-07-31.edn` and described by the external claim overlay at `docs/assurance/checkpoints/sew-v1x-appeal-bond-claim-overlay.edn`. The verifier intentionally reports current working-copy divergence for changed scenario bytes. The September candidate is tracked separately and is not a deployment assertion. No Solidity deployment/canonicalization mapping should be declared until source/deployment identity and reversal semantics are closed.

## Completion gate

Create the lineage IDs only after:

1. the pre-appeal baseline is identified;
2. appeal-bond tests pass without weakening expectations;
3. DR-N-002 reaches a valid pre-appeal checkpoint;
4. reversal execution is dedicated and non-fraud-specific;
5. custody, conservation, lineage, authorization, and deterministic replay pass;
6. `bb validate` and `bb backstop` complete successfully.
