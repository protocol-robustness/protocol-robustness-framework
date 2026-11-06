# Pro-Rata provenance coverage v1

**Stage:** PRO-96 Stage A
**Inventory version:** `prf-pro-rata-provenance-coverage.v1`
**Captured:** 2026-10-21; digest correction reproduced 2026-10-23
**Purpose:** bounded inventory of the retained pro-rata evidence and the minimum evidence needed to support stronger claims.

This is an audit inventory, not a new evidence schema, canonical artifact,
qualification result, or authorization. The row labels below are document
anchors only. Claim qualification should reuse
`prf-claim-qualification-profile.v1` / `prf-claim-qualification.v1` and
existing PRF evidence validators. Statuses apply only to each row's stated
scope; there is no umbrella “fully proven” status.

## Status meanings

| Status | Meaning in this inventory |
|---|---|
| `verified` | Named retained evidence and a relevant existing check support the narrowly stated proposition within its stated trust boundary. |
| `invalid` | A relevant check found contradictory or malformed evidence for that proposition. |
| `not-reproduced` | Reproduction was required or attempted, but no matching independent reconstruction is retained. |
| `unverified` | Evidence is absent or does not cross the trust boundary required by the proposition. |

The existing claim-qualification implementation has the more detailed
`verified`, `invalid`, `blocked`, `not-run`, and `not-applicable` result
semantics. This inventory maps those results into the reporting vocabulary
above; it does not change validator semantics.

## Evidence baseline and identities

### PRF integration workspace

- Repository/workspace: `protocol-robustness-framework`,
  `/home/user/Code/.workspaces/agent-c-3`.
- Baseline working-copy change ID: `wnkkunskntstrwpuwsvsqqtzrxslsrtp`.
- Baseline commit ID: `00df07ca02c81c3433fb3c23b0047fdbd610976a`.
- Baseline parent/staging commit: `4992ef23fc3fb4885945f9d310b375dba955636c`
  (`agent-c-staging`, integrate PRO-68).
- The baseline working copy already contained untracked
  `requal-20261021/{ao-route-manifest-final.json,ao-route-manifest-verified.json,deployment-manifest.json,proof-fixture.json}` and
  `pro-rata-conformance/cache/test-failures`. They are retained as supplied;
  they are not silently treated as a complete qualification bundle.

### Composed local settlement package

The strongest self-contained composed test package is
`/home/user/Code/prf-evm/evidence/composed-pro-rata-v2-settlement/`.
Its manifest declares base source commit
`0dad4c2790a60ecca9a41c5d518f99522eab94ce`, local Foundry chain ID 31337,
proof profile `source-bound-entitlement-controlled-sp1-program.v1`, and
package-level limitation “no public-chain transaction.” The source checkout
currently inspected is `/home/user/Code/prf-evm`, Jujutsu commit
`43fe77be42780a993c0719c64f31a0684e3b6477` (tree
`7db4a1e8e2c9a439733747ec140a4daff8080a64`); its five untracked fixture files
are not part of the manifest's declared base commit.

Key retained digests (SHA-256):

| Artifact | Digest |
|---|---|
| `manifest.json` | `379ea68ae439d6b18ba322edaa92723d76d7a7d6344178473ef1cbd8b56e30bc` |
| `source-witness.json` | `b0d10ccd549ad86435f8e13381c83c91500dd0f5a9eaa07a017ca601cb31f87b` |
| `clojure-replay.json` | `048e86a26e49e5ec56756d6ca77755df8d2ee1f8555dc2548ba7170d0e6e75d9` |
| `rust-replay.json` | `da3b6ab9922ad05c1eafbe2cf49ac31dddaa03cca26c0d5ddf2c97cec0c36aa3` |
| `validation-result.json` | `37d4b15710c66aa9aa818b6d04b30d7511f492b626c756f399ad4a91c75f11b0` |
| `full-stage2-qualification.log` | `d22b00eaa642a605f1e33bd2accf6210dce9b08102157fcfaec2e683bffefadc` |
| `execution.log` | `3383b7932be7576790aed9da9f4d08d8685390cbed05a018b2c0f93553c8134c` |
| `adversarial-tests.log` | `d0ff70bcdddb0c9c81e6df49db1afddb4c751154d198c77396c790315b0fad80` |

`manifest.sha256` and a fresh SHA-256 calculation both yield the digest above for `manifest.json`; the manifest declares the same digest. Proof bytes are retained at
`test/fixtures/partial-fill-v3/proof.json` in that source checkout (the
manifest records the fixture digest). The run used real Groth16 proof
verification/admission in local EVM execution. The Clojure and Rust replay
projections match for the retained witness. The manifest records dependency
revisions, commands, pass counts and explicit limits.

### Persistent local Gate E records

`/home/user/Code/prf-evm/evidence/gate-e-20261010-current-00d90/` retains
funding, activation, withdrawal and execution-DAG records. The operation
completed local withdrawals at blocks 43–46 and closed with 70/3 claimed,
zero outstanding liability and zero allocator custody. The captured records
are provider-relative RPC observations, not consensus-authenticated inclusion
or public-chain finality. The refund branch is separately retained in
`gate-e-20261010-refund-00d90/`.

| Persistent record | SHA-256 |
|---|---|
| Current run `funding-forensic.json` | `0ea4677375311dcfe5cc97267425e1c5ecf0884750a37d577c6fc548ce3799d8` |
| Current run `activation-forensic.json` | `587684b9217ef26cd1800e40bf858b169f057efeff8da647501379562b2f74a5` |
| Current run `withdrawal-forensic.json` | `fd68b5467aa08f334e86faee7bcbdf2a3697570e315bd7f7a7a43b7afd9481e9` |
| Current run `execution-dag.json` | `ee367543b1e7f27fd438571ac1fc8a66312d56b830e00a88b0838144a3c2938b` |
| Refund run `refund-forensic.json` | `4c7a088906d0c5168485983842cdca32fb5b6c4eea6987a59372c5ab39ef4eff` |

The retained forensic work found incorrect function/hash associations in Forge
broadcast metadata for one run. The records preserve the discrepancy and use
actual RPC transaction calldata, destinations, receipt block hashes, and
transaction indices to reconstruct execution order. Treat the original
broadcast association as a producer assertion, not as the authoritative
transaction linkage.

### `agent-c-3` requalification snapshot

`examples/assured-operations/pro-rata-conformance/artifacts/requal-20261021/`
contains only deployment, proof fixture, and two route-manifest files. Both
route manifests have the same SHA-256
`75fa9e2380b40a8a068f2c4194ed364c1d5ad357c7cd4d73e485a54791cd9c62`; the
deployment manifest digest is
`dc92b77201de8465029d160112330dc8bccb655066a2d6faae232440ed20509b`, and the
proof-fixture digest is
`2000ba2b33c1a2be8ec7a98c04c909e2e10f16035a579ff37d69a325f399d8c5`.
The route binds local chain ID 31337, fork block 5, operation ID
`0x0a0a…0a0a`, prepared root
`0x7ae5ac6a…1c108f6d`, VKey
`0x0072f7ae…bc957f01`, and ELF SHA-256
`0x6c9e82f1…ec95cb45`. It reports allocator balance 90 and reserve balance 10.

This directory has no run log, source-state record, pre/post inventory,
transaction receipt bundle, final evidence index, withdrawal record, or
closure accounting. It therefore supports inspection of the retained
deployment/proof/route fields only; it is **not** full settlement evidence and
its reported balances must not be described as paid withdrawals.

## Claim-scoped coverage matrix

| Claim / subject | Evidence source and root | Trust boundary | Existing independent verifier | Status | Gap / assumption | Next action |
|---|---|---|---|---|---|---|
| The bounded fixture contains requests Alice 50, Bob 30, Carol 20 and the declared 70 available units. | Composed `source-witness.json`; witness SHA-256 above; source root in `manifest.json`. | Integrity of supplied fixture only; no evidence the external request/assets population is complete or authentic. | `scripts/verify-composed-pro-rata-v2.py` validates the retained witness and its derived package. | `unverified` for real-world source truth/completeness. | A self-consistent hash proves bytes, not that all real requests/obligations were supplied. | Stage B: bind an authenticated, bounded source snapshot, unique source IDs, cutoff/watermark, ordering, deduplication, amendments and completeness evidence. Negative-test omission, fabrication, duplicate and out-of-window inputs. |
| The declared largest-remainder pro-rata policy derives 35/21/14 from that fixture and 70 available. | Composed witness plus Clojure and Rust replay outputs; roots in composed `manifest.json`. | Deterministic semantic reproduction of the declared fixture and policy. | Clojure/Rust replay comparison and `scripts/verify-composed-pro-rata-v2.py`; retained full Stage 2 log. | `verified` for the declared fixture. | Does not authenticate source facts, legal fairness, or inputs outside the fixture. | Preserve roots and policy identity when adding authenticated source ingestion; add omission/rounding/concurrent-state cases to Stage B. |
| The proof binds the declared operation, reserve snapshot, entitlement roots and public values, and verifies under the named SP1 identity. | Composed manifest, `test/fixtures/partial-fill-v3/proof.json`, proof fixture digest and public values; VKey/ELF/schema recorded in manifest. | Local Groth16 verifier and configured registry; proof profile is V1 with V2 allocator and V3 reserve lifecycle. | Local EVM real verifier/admission test; Clojure/Rust replay; exact proof fixture check in the composed validator. | `verified` for this local proof and exact fixture. | Not proof of external source truth, public-chain verifier deployment, or independent third-party implementation. | Stage B: retain a single proof/input/public-values manifest and independently rerun proof verification from original witness in a clean environment. Keep PRO-32 reference-verifier ownership separate. |
| Reserve, prepared-operation and funding commitments match the local operation that was funded. | Composed manifest, funding observations, operation/reserve roots; persistent Gate E funding forensic record. | Foundry call-boundary state in composed case; provider-relative block/RPC observation in persistent local case. | Composed validator and the existing deployment-bound receipt / chain-runtime validators. | `verified` within each stated local scope. | RPC records do not establish consensus-authenticated inclusion; fixture authority is local. | Stage B: replay funding from exact tx/receipt/log/state data and detect wrong operation, root, code identity, source call, replay and internally re-pinned bundle substitution. |
| Activated entitlements paid the committed beneficiaries exactly once and all funded liabilities closed; restricted funds were refunded to the fixture beneficiary. | Composed transfer observations, state snapshots, accounting journal and reconciliation; persistent Gate E withdrawal and refund forensic records. | Composed evidence is Foundry call-boundary output; persistent Gate E uses mined local transactions observed through RPC. Fixture policy specifies beneficiary mapping. | `scripts/verify-composed-pro-rata-v2.py`; existing allocator and deployment-bound forensic validators; `PRO-31` chain/runtime checks for the local observation. | `verified` for composed fixture semantics and provider-relative local settlement; `unverified` for externally authenticated beneficiary/legal ownership. | Composed logs are not individual mined receipts. RPC source is not consensus. Legal ownership and source completeness are absent. | Stage B: require one receipt per action, actual calldata/event emitter/index, state at consistent block boundaries, per-claim liability/custody reconciliation, exact refund, and replay/recipient/omitted-payment negatives. |
| The retained `requal-20261021` route is a complete end-to-end settlement. | Current workspace `artifacts/requal-20261021/*`; route hashes above. | Four files only; chain ID/fork block are declared fields. | No retained run verifier output or final evidence index for this requalification directory. | `unverified` (not full settlement); no contradictory evidence established. | No run log/source state/receipt set/withdrawal or closure evidence. Route copies are byte-identical. | Do not elevate this bundle. Attach a complete source-state, run log, final evidence index, mined-action records and closure reconciliation in a later qualified run. |
| The local transactions belong to the recorded chain/runtime and block ancestry. | Persistent Gate E forensic records and execution DAG; local chain instance and receipt block fields. | RPC/provider-relative local Anvil observations. PRO-31's accepted scope is provider-relative identity/ancestry, not consensus finality. | Existing `deployment-bound` record validation, runtime/source checks and chain fingerprint checks; PRO-31 implementation. | `verified` only as provider-relative local observation. | Single observation source can present a self-consistent false history; local chain is not a public network. | Stage B: use trusted chain/runtime pins from an independent source and retain reorg/replacement checks. Stage C: authenticated header/inclusion verification under independently selected trust context (PRO-79). |
| Settlement has public-chain inclusion and finality. | No public-chain transaction/evidence is present in the reviewed package. | No public chain or independently authenticated consensus header supplied. | No qualifying verifier run. | `unverified`. | No public deployment, receipts, inclusion proofs or finality evidence. | Stage C only after separate deployment/operations authorization; verify authenticated inclusion, ancestry, finality/freshness and reorg behavior. |

## Minimum missing-evidence contract

These are acceptance conditions for later stages, not new canonical data
identities. Use existing PRF claim-qualification profiles, deployment-bound
receipt validators, evidence DAG nodes, chain/runtime validators and proof
verification. A producer's `verified` field, a hash by itself, or a generated
summary never substitutes for executing the selected verifier.

### Stage B — authenticated inputs and complete locally mined settlement

For every required claim, the portable case must retain and bind:

1. Exact repository/workspace revisions, clean/dirty state, source inventory,
   dependency/tool versions, command/exit status, proof guest ELF/VKey, witness,
   proof and public values.
2. Authenticated source snapshot and declared completeness boundary: source
   identity, unique records, event/request IDs, cutoff, ordering, dedupe,
   amendments/cancellations, asset/reserve observations and source authority.
3. Policy version/root, reviewer/decision authority, funding authority, and
   beneficiary-to-destination authorization. Pseudonymous beneficiaries are
   acceptable; a wallet address alone does not establish legal identity.
4. Every submitted action's actual transaction hash, sender where observable,
   calldata/selector, receipt status, event emitter/topics/data/index, block
   hash/parent/number/transaction index, deployed code hash, and pre/post
   relevant storage and balances. Preserve RPC/provider identity and raw
   responses; separate `eth_call`, Foundry assertions, submitted transactions,
   mined receipts and later state reads.
5. Independent reconstruction from original source bytes through policy,
   allocation, liabilities/refund partition, proof public values, reserve/fund,
   activation, each payout/refund and closure. Check conservation at each
   cutpoint, not just terminal zero.
6. A deterministic, read-only qualification report bound to the exact claim
   subject/cutpoint/source roots, selected verifier profile and independently
   sourced verifier revisions. Missing checks remain blocked/unverified.

Required negative cases: omitted, fabricated, duplicated and out-of-window
source records; unauthorized policy/beneficiary/funding authority; wrong
operation/reserve/proof/public-values roots; changed payout mapping; wrong or
missing transaction/event/operation linkage; omitted, duplicate, excessive or
wrong-recipient payout; false closure with remaining liability; stale assurance;
reorg/replaced receipt; and a substituted bundle whose internal hashes are
recomputed. A local RPC replay may establish provider-relative correspondence,
but must not claim public-chain inclusion/finality.

### Stage C — separately authorized public-chain qualification

Only after a separate deployment/operations approval: repeat the bounded case
on the selected public chain; pin chain/genesis, contract/runtime identities,
configuration/verifier registry and source cut; authenticate receipt inclusion
against independently trusted headers; apply the named safe/finalized policy;
retain freshness/reorg/replacement behavior and rerun independent accounting
reconstruction. Provider agreement alone is not consensus finality. If the
source or trust anchor cannot be independently authenticated, leave the
affected claim `unverified`.

## Reuse and scope boundaries

| Existing work | Reuse in this effort | Boundary / smallest remaining adapter |
|---|---|---|
| PRO-77, `src/resolver_sim/benchmark/claim_qualification.clj`, and `docs/evidence/CLAIM_QUALIFICATION_V0.md` | Exact claim subject, cutpoint, source-root and validator-revision composition; fail-closed required-check results. | Add a Pro-Rata profile/runner that feeds original artifact bodies and existing results into this composer; no second evidence graph or qualification schema. |
| `src/resolver_sim/evidence/deployment_bound.clj` `validate-records`; PRO-28–32 and PRO-56 | Existing local deployment/receipt, runtime and accounting checks; preserve actual transaction linkage and known Forge metadata discrepancy. | Stage A inventories; Stage B adds only missing claim-to-operation/source bindings and independently retained trust pins. PRO-56 remains In Progress. |
| PRO-31 / runtime and local-chain fingerprint checks | Local chain ID/genesis/runtime identity and provider-relative block ancestry checks. | Does not authenticate consensus or finality. Use PRO-79 for optional trusted header/inclusion verification. |
| PRO-36 / `src/resolver_sim/evidence/forensic_graph.clj` | Independent admission/validation before any evidence graph gap is promoted. | A graph or producer assertion does not prove authenticity. PRO-36 remains Todo. |
| PRO-41 and `scripts/verify-composed-pro-rata-v2.py` in `prf-evm` | Source-derived economic semantics and cross-language reconstruction. | Reuse outputs; do not create another allocation calculation. |
| PRO-78 portable fixture/source bundle | Portable reproduction inputs and source bundle constraints. | Keep source package separate from mutable evidence or build outputs. |
| PRO-21 proof identity and PRO-32 proof reference verification | Program identity and proof verification. | Pro-Rata evidence may consume their result; do not duplicate a proof verifier or mark RPC/chain claims verified from proof success. |

## Reproduction commands and non-reproduction boundary

The following commands are documented in the composed package README and its
retained logs. They are reproduction instructions, **not run during this Stage
A inventory**:

```sh
cd /home/user/Code/prf-evm
PARTIAL_FILL_STAGE=2 forge test --offline \
  --match-path test/PartialFillV3Controlled.t.sol \
  --match-test test_QualificationWithdrawsAndClosesExactly -vv
PARTIAL_FILL_STAGE=2 forge test --offline \
  --match-path test/PartialFillV3Controlled.t.sol \
  --match-test 'test_(RejectUnauthorizedRecipientSubstitution|RejectProofForAnotherOperationAndPolicyMutation|RejectChangedAllocationRepresentation|RejectOverWithdrawalAndDuplicateConsumption|RejectUnauthorizedAndRouteBypass|RejectRatificationAndFundingReplay|RejectProofPublicValueSubstitution|RejectStaleReserveWithoutChangingState|RejectFundingMoreThanCommittedCapital|RejectUnauthorizedAndPrematureProtectedFinalization|RecoveryWithFreshV3Proof)' -vv
python3 scripts/verify-composed-pro-rata-v2.py
```

The retained log reports 1 positive pass, 10 negative passes plus fresh-proof
recovery, and an all-Stage-2 suite of 14 passing tests. These are recorded
results; this Stage A document did not rerun them. Local persistent Gate E
records are already retained as listed above; public chain and source-authority
reproduction are not performed here.

## Review and progression

This correction is prepared in repository `protocol-robustness-framework`, workspace `/home/user/Code/.symphony-workspaces/prf-pilot-coding-pro-rata/PRO-96`, from baseline JJ commit `84fd02f2dc58ca64d444cd687a80427c00a820d6` (`agent-c-staging`). The initial working-copy change ID was `prsoyyvz...`; the candidate identity and document digest are recorded in the Linear workpad after commit.

Automated artifact/fixture validation is not an independent human review. The first independent review (Marc Griffiths, Linear comment dated 2026-10-23) rejected candidate `cc43ef9cf8fc55aa6cc7e533df58b8f421ba0825` because its manifest digest was wrong and lines 3–5 had trailing whitespace. The digest mismatch was reproduced in this workspace and corrected here; the reviewer must recheck this candidate before acceptance. Independent outcome for this corrected matrix: **pending re-review**. Keep PRO-96 In Progress until the required correction and focused document checks pass and the handoff is recorded. PRO-95 Stage B is not complete by
this document; public-chain work is neither authorized nor performed.

Correction reproduction (no live source or public-chain interaction):

```sh
cd /home/user/Code/prf-evm
sha256sum evidence/composed-pro-rata-v2-settlement/manifest.json
cat evidence/composed-pro-rata-v2-settlement/manifest.sha256
cd /home/user/Code/.symphony-workspaces/prf-pilot-coding-pro-rata/PRO-96
python3 -c 'from pathlib import Path; p=Path("docs/allocation-proofs/pro-rata-provenance-coverage-v1.md"); bad=[n for n,line in enumerate(p.read_text().splitlines(),1) if line.rstrip()!=line]; print(bad); raise SystemExit(bool(bad))'
```

The first two commands both returned `379ea68ae439d6b18ba322edaa92723d76d7a7d6344178473ef1cbd8b56e30bc`; the original candidate recorded `13a9e43fdfdb2989d488a5ec7f3821f46ce75167803f69ee69a6c15ea74a11af`. Stage A package tests were not rerun; the retained commands and pass counts above remain historical package results.

Append the corrected Stage A JJ change ID/commit, its parent staging commit, exact workspace, document digest, reviewer identity and pass/fail findings to PRO-96 in Linear. Do not include unrelated pre-existing qualification artifacts in the Stage A change.

## Follow-up artifact identity check (2026-10-23)

The PRF-EVM composed-settlement package has since advanced beyond the snapshot
above. Its current `manifest.json` SHA-256 is
`753ce78c324bc6793366ecc5c868952baedec6bc2727fe096e9be93e03010564`; the
adjacent `manifest.sha256` agrees. This is a later package identity and does
not rewrite the captured digest `379ea68a...` above. The current manifest
declares source base Git commit
`46a6910a8849b45e63a69c11f1741e837df5f64e`.

A temporary sparse projection of the 34 manifest-listed files, using files
from that pinned source commit and retained package files matching their
declared hashes, passed `scripts/verify-composed-pro-rata-v2.py` with status
`PASS`. The retained result is at
`/tmp/pro96-composed-pinned-46a6910a/verification.stdout.json`. This checks the
declared package identity and local fixture reconstruction; it does not
authenticate external source facts or establish public-chain execution or
finality.

Running the same verifier against the active `/home/user/Code/prf-evm` tree
fails because `src/core/PrfChainInstanceGenesis.sol` differs from the hash
declared for the pinned base revision (`f3a5564a...` in the active tree versus
`f3659316...` at the pinned revision). The active checkout therefore is not a
substitute for the package's declared source snapshot. The verifier must run
against the pinned files when reproducing this retained package.
