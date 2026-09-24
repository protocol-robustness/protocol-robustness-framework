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

## Scenario lineage findings

The currently established path history is:

| Scenario | First known revision | July checkpoint | Current finding |
|---|---|---|---|
| `DR-C-001` | `cf89cee28703` (2026-06-29) | present at `633f1955` | exact first semantic introduction requires further path-level diff |
| `DR-C-002` | `cf89cee28703` (2026-06-29) | present at `633f1955` | current timing/sequence hash differs from July checkpoint |
| `DR-C-003` | `633f19557f1a` (2026-07-31) | initial committed correction scenario | current compatibility/denomination descendant differs |
| `DR-C-004` | `633f19557f1a` (2026-07-31) | initial committed correction scenario | current successor classification requires review |
| `DR-C-005` | `633f19557f1a` (2026-07-31) | initial committed correction scenario | current causal forfeiture path remains incomplete |
| `DR-C-006` | `633f19557f1a` (2026-07-31) | initial committed correction scenario | current explicit fault-injection descendant differs |
| `DR-N-002` | `cf89cee28703` (2026-06-29) | July hash bound | modified in `b3594fff`, `d54a23c0`, and `12916800`; no further path diff after `12916800` was observed through the September checkpoint; semantic classification of those historical edits remains `UNKNOWN / REQUIRES DECISION` |

`DR-N-002` therefore must not be described as unchanged merely because its current scenario text was not edited in the September working copy. Its current hash is `327415a8c599b318b0f04ab00ac9c131c5e8e45310a364a4f5ae618f721e895d`, while the July checkpoint hash is `d2fe6e2a5be08eb1e9d78a12a26188eaeab836130b8e9c740084c26c9cebf6e2`.

## Completion gate

Create the lineage IDs only after:

1. the pre-appeal baseline is identified;
2. appeal-bond tests pass without weakening expectations;
3. DR-N-002 reaches a valid pre-appeal checkpoint;
4. reversal execution is dedicated and non-fraud-specific;
5. custody, conservation, lineage, authorization, and deterministic replay pass;
6. `bb validate` and `bb backstop` complete successfully.
