# Sew Next qualification matrix (vs V1 corrected semantic reference)

This document is the **Sew Next qualification** surface: a read-only, evidence-backed
comparison of the Sew Next candidate against the **Sew V1 corrected semantic
reference** (`5f84818d`), classifying intentional divergences and identifying the
remaining gates before Sew Next can become the default/public implementation.

The first concrete successor-assurance component — the completed primitive-native
appeal-bond tranche — is used here as evidence for the successor architecture's
appeal/identity/conservation/disposition/atomicity semantics (see
[`README.md`](README.md), section *First primitive-native tranche — COMPLETE*).

> Scope discipline: this is a **qualification/analysis document**, not an audit
> opinion and not a claim that Sew Next is deployed, canonical, or proven secure.
> No Solidity, JJ history, or historical scenario bytes were modified to produce it.

## 1. Reference identities (frozen — do not reopen without new evidence)

| Identity | Revision | Purpose | Deployment meaning | Assurance role |
| --- | --- | --- | --- | --- |
| Sew V1 deployment source reference | `74176ab9ae13efe1ce9e4669e306ea73a72d6058` (tag `testnet/base-sepolia-v1` at `1904950a`, identical Solidity tree) | historical release source | release-bound; runtime equivalence not yet proven | historical regression/deployment target |
| Sew V1 corrected semantic reference | `5f84818df95d35edbd0a8c74565043fccd81ff15` | corrected appeal behavior (REFERENCE_BEHAVIOUR) | not the February deployment | appeal compatibility reference |
| Sew Next successor/model boundary | `4959328684a26ebfdbd9a1c467a07e43dc27647c` onward; merged at `f588fe022e325d27c877ab777558bcf63772a7f9` | readable successor / candidate architecture | not yet deployed/canonical | compatibility + primitive-native assurance |

Current JJ candidate ancestry for Sew Next is the September `main` line
(`96600050` (`aderyn report fixes`) → working copy `tmznvrqn` / `02d1fdf3`), which
continues the successor/readability programme (`reorg` subdirs, v1/v2 incentive
consolidation, yield cleanup, aderyn-fix hardening, governance-controlled recovery).

## 2. Method

Divergence is classified across the span `5f84818d` → current candidate
(47 commits) as:

```text
UNCHANGED
SECURITY-REPAIR / HARDENING
BEHAVIOR-PRESERVING-REFACTOR
ADDITIVE (wired or unwired)
MATERIAL-SEMANTIC
ALTERNATIVE-UNWIRED
```

Commit count is not used as a change-size metric; each class is grounded in the
commit diff and in deployment wiring (deploy scripts, `config/deployable-allowlist.json`,
`deploy-registry/`, `deployables/manifest.json`).

## 3. Divergence classification matrix

| Area / change | Solidity scope | Classification | Deployed/canonical impact | Assurance evidence |
| --- | --- | --- | --- | --- |
| File reorg into subdirs (`ce786459`) | 25 files moved (`analytics/`, `bond/`, `incentive/`, `interfaces/`, `libraries/`, `slashing/`, `staking/`) | BEHAVIOR-PRESERVING-REFACTOR | none (import paths only; +33/−33) | diff stat |
| v1/v2 ResolverIncentive consolidation (`82c7160f`) | `ResolverIncentiveModuleV1` → `ResolverIncentiveModule` (1690 ln), delete `V2`, v1 removed from PaymentLibrary | BEHAVIOR-PRESERVING-REFACTOR (rename+merge), **wired** | canonical incentive module is `ResolverIncentiveModule` (`deploy/85`, `86`) | diff + deploy scripts |
| Legacy yield module removal (`09f4118b`, `7dbbf82b`, `ba95ca8a`) | remove `YieldOps`, legacy yield gen/dist libraries | BEHAVIOR-PRESERVING-REFACTOR (dead-path removal); `ba95ca8a` also rewires AaveYieldModule to AccessControl/slow-lane (security) | `AaveYieldModule` remains the sole canonical yield module | diff + manifest |
| Yield protocol fees (`2cf205e7`/`093d7f4d`) | fee accounting moved from `YieldOps.handleYield` (module push) to core `EscrowSettlement` (pull `withdrawFees`, `YieldProtocolFeeCollected`); per-token exposure caps + events | **MATERIAL-SEMANTIC** (fee flow/timing) + SECURITY (exposure caps) | changes fee collection flow and surface; `MODULE_VERSION` 2.5.2→2.5.4 | diff + docs `PROTOCOL_FEES.md` |
| Aave / aderyn security repairs (`5d304e65`, `f7d04ed2`, `26cda2b9`, `96600050`, `495defad`) | principal validation, CEI ordering, recorded aToken vs live config, supply-delta accounting, address/access-control hardening, governance-controlled recovery (whitelist + timelock) | SECURITY-REPAIR / HARDENING | behavior hardening; recovery now governance-gated | commit diffs + `docs/security/ADERYN_FINDINGS.md` |
| `DefaultResolutionModule` | single-resolver, no-escalation `IResolutionModule` with `setResolver`/`ResolverUpdated` | ADDITIVE — not wired into canonical candidate deploy scripts; testnet/manual + governance role lists only | no deployment impact in candidate path | deploy scripts vs testnet scripts; allowlist |
| Kleros handoff (`IKlerosHandoffResolutionModule`, `KlerosArbitrableProxy`) | escalation arbitration handoff capability | ADDITIVE-UNWIRED | latent; not in deploy scripts/allowlist | grep deploy path |
| **BondLedger extraction** (`49593286`, `2533d0b0`) | `BondLedger.sol`, `IBondLedger.sol`, `ResolverIncentiveModuleV2BondLedger` facade, differential suite | ALTERNATIVE-UNWIRED (review substrate) → now a **separately-wired, non-canonical Profile-B** deployment profile (`deploy/87_bondledger_profile.ts` + `config/deployable-allowlist-bondledger.json`, locally read-back validated, see §5d) | **not the canonical default**; not wired by 85/86 (Profile A); `AUTHORIZED_CALLER` grant on `BondLedger` still governance-pending (TimelockController path) | deploy/wiring grep + ADR `DECISION-appeal-bond-profiles.md` + §5d |
| **Extracted appeal primitive** (PRF, not Solidity deployment) | reusable appeal-bond projection/query layer | candidate architecture | not part of the canonical Profile-A deployment (Profile-B BondLedger wiring is separate, see §5d/ADR) | primitive-native PRF tranche (below) |
| Clojure `/ PRF` drift (C002/C003/C006/N002) | non-Solidity; documented separately | descendant drift | n/a | `README.md` historical-isolation note |

### Canonical appeal-bond terminal disposition in the candidate

The ordinary escalation appeal-bond terminal path is unchanged in structure and
lives in the **wired** modules:

```text
DecentralizedResolutionModule.recordResolution
  → ResolverIncentiveModule.distributeAppealBond        (refund / resolver-payout / forfeit)
DecentralizedResolutionModule.finalizeDispute / closeByMutualAgreement
  → ResolverIncentiveModule.onDisputeFinalized          (leftover-bond forfeiture)
```

This is the production path that the completed PRF appeal tranche exercises
(`execute_resolution` → `apply-resolution-transition` →
`settle-appeal-bond-on-resolution`, with the transition receipt at the replay
boundary). In the **canonical Profile-A default**, the BondLedger `V2BondLedger`
facade is **not** the live incentive module; the canonical/allowlisted module is
`ResolverIncentiveModule`. The facade + `BondLedger` are live only within the
explicitly-selected, separately-wired Profile-B profile (see §5d / ADR).

## 4. The completed appeal tranche as successor-assurance evidence

The first primitive-native tranche ([`README.md`](README.md)) is `COMPLETE`, all
six scenarios PASS with genuinely evaluated metrics:

| Scenario | Property | Result |
| --- | --- | --- |
| `AP-CONS-001` | per-bond conservation via validated projection | PASS |
| `AP-ATOM-001` | reverted-op atomicity via external transition receipt (`pre-root == post-root`, cause `:distribution-dependency-failure`) | PASS |
| `AP-DISP-001` | exactly one terminal disposition; system/workflow-level resolution idempotence | PASS |
| `AP-REPLAY-001` | replayed terminal op no duplicate effect (system-level idempotence, **not** primitive `:already-disposed`) | PASS |
| `AP-CLAIM-001` | per-bond `allocated = claimable + claimed`; workflow-finality prerequisite | PASS |
| `AP-ID-001` | distinct round-bound bond IDs at coexistence checkpoint; cross-bond isolation | PASS |

Candidate suite root `716e4cb8490a51042e5490c0634e70462d393a8fee024ed4d8efcda5d70d2901`
is frozen and unchanged in this task.

This tranche is the first concrete **successor-assurance** evidence: it proves the
successor query/projection layer (validated appeal-bond projection → scoped
fail-closed query → primitive-native assertion) holds identity, conservation,
single-terminal-disposition, entitlement, and reverted-op atomicity that the
historical aggregate DRM model could not express directly. It does not, by itself,
prove Solidity equivalence or deployment readiness.

## 5. Remaining gates before Sew Next is default/public

These must clear before Sew Next is presented as the default/public implementation:

1. **Solidity deployment wiring** — BondLedger, `V2BondLedger`, `DefaultResolutionModule`,
   and Kleros handoff are unwired/not canonical-deployed. Wiring and a live-address
   verification of `EscrowVault.disputeResolutionModule` are required.
2. **Runtime bytecode ↔ build equivalence** — still `NOT YET BOUND` for the V1
   deployment reference; the same must be established for any promoted Sew Next path.
3. **Solidity equivalence proof** — the PRF success uses the successor projection and
   the canonical (wired) `ResolverIncentiveModule` path. The formal Solidity
   equivalence/regression layer now runs and passes on the A-2-bound working copy
   (`BondLedgerDifferential 11/11`, `TraceEquivalence 38/38`, `BondBehaviourCorrection
   15/15`; see section 5c). The on-chain A-4 read-back of Profile B is now validated on a
   deterministic local hardhat deployment (see section 5d); remaining is the historical
   binding of the February reference runtime and live/persistent Profile-B runtime codehash.
4. **Multi-bond claim consumption** — Bond A / Bond B → same recipient → same
   asset aggregate withdrawal is **canonically aggregate-only** (Solidity
   `ResolverIncentiveModule` pools per `(escrow, workflow, recipient)`); encoded
   in PRF as `query-bond-claim-attribution` (fail-closed). No per-bond
   FIFO/LIFO/latest/map-order rule exists or is inferred.
5. **Adversarial/economic breadth** — the tranche is small by design; broader
   adversarial and economic coverage remains.
6. **Historical compatibility divergences** — `DR-C-003` denomination
   (1,000,000 vs 246), `DR-C-004` resolver `:distribution`, `DR-C-005` forfeiture
   lifecycle, `DR-N-002` lineage remain open; documented in the Layer C decision
   records (`DR-C-003-denomination-decision.md`, `DR-C-005-forfeiture-provenance.md`,
   `version-lineage.md`).
7. **Yield fee / material-semantic divergences** — the yield fee flow change is a
   material semantic divergence from the reference; it must be treated as an
   intended divergence with its own decision record, not silently folded into
   "compatibility".

## 5b. A-2 Profile-B build identity (bound deployable bundle)

Step A-2 binds every Profile-B canonical artifact to a reproducible build identity. The
deployable bundle for Profile B is now produced and its deterministic root recorded:

```text
allowlist: config/deployable-allowlist-bondledger.json (buildProfile: sew-dr-v2-bondledger)
manifest:  deployables-bondledger/manifest.json
bundleRoot: f92f6f56d9cc2f293d49b002ae7918dc5e846cded293fec92c3f5970a1f5ea07
bundleVersion: 0.1.0
packageName: @sew/deployables-bondledger
build.artifactSource: forge
build.compiler: solc 0.8.37+commit.f401782d
build.settings: evmVersion osaka / optimizerRuns 200 / viaIR true
source.commit: tmznvrqn (jj change id, current working copy), commitSource jj, dirty true
```

14 closure artifacts are bound by creation/runtime bytecode SHA-256, ABI SHA-256 and file
SHA-256 (4 profile-specific, 6 shared protocol, 4 shared governance). Key hashes:

```text
BondLedger                    creation d709fa785a..  runtime 0b177d12b85a..
ResolverIncentiveModuleV2BondLedger
                              creation 7124b9a01a62.. runtime 8e919f951da3..
DecentralizedResolutionModule creation 18897ef088b9.. runtime 93ce3db1e802..
EscrowVault                   creation d279a181b139.. runtime 4f2d3e5e8dfc.. (has link refs)
PaymentCalculationLibrary     creation 9213fde6136f.. runtime b73836adb4cf..
TimelockController (OZ)       creation 8ff9443a9518.. runtime c77b0218ed36..
```

The manifest/machinery lives in sew-protocol: `scripts/export-deployables.ts` now accepts an
explicit positional allowlist path + export dir (required for non-default profiles) and
passes the loaded allowlist into validation. `deployables/` and `deployables-bondledger/` are
gitignored; the allowlist + export tooling are tracked.

**Runtime codehash status (see section 5d):** deterministic A-2 codehash is now bound for the 9
static Profile-B closure contracts via `scripts/bind-runtime-codehash.ts`, with no network
needed. The 5 address-dependent contracts (immutables/linked lib) still need a persistent-network
read-back (baseSepolia) or constructor-arg patching. A deterministic local Profile-B deployment
now exists and its addresses + bindings were read back (section 5d); A-2 additionally records
source/build/bytecode identity.

## 5c. Reference vs candidate semantic equivalence (differential evidence)

Step 4 assesses whether the Profile-B BondLedger candidate is semantically equivalent to the
Profile-A reference behavior. The authoritative harness is `BondLedgerDifferential.t.sol`,
which deploys two identical stacks that differ only in the incentive module (Profile A:
inline-custody `ResolverIncentiveModule`; Profile B: `ResolverIncentiveModuleV2BondLedger`
facade over `BondLedger`) and runs identical scenarios, asserting equal economic/state metrics.

Forge results on the A-2-bound working copy (`solc 0.8.37, evmVersion osaka, runs 200,
viaIR true`):

```text
BondLedgerDifferential.t.sol   11/11  pass   refund, claims, forfeiture reserve,
                                              failed-appeal resolver allocations, odd-split
                                              remainder, finalize cleanup, eth bond,
                                              fee-bearing net principal, double-resolution
                                              idempotence, invalid-authority rejection,
                                              + rooted forensic lineage on BondLedger
TraceEquivalence.t.sol         38/38  pass   duplicate-trace vs inline equivalence, v2
                                              lifecycle, negative/version-reject guards,
                                              invariants
BondBehaviourCorrection.t.sol  15/15  pass   corrected appeal refund/payout/forfeit
                                              accounting, no double distribution/resolver
                                              payout, authority + event guards
```

Mapping the frozen PRF appeal claims to both implementation mechanisms and the Solidity
evidence that demonstrates expected equivalence:

| Property | Profile A mechanism | Profile B mechanism | Solidity evidence |
| --- | --- | --- | --- |
| AP-CONS (per-bond conservation) | `ResolverIncentiveModule` inline custody | `BondLedger` per-bond settlement | `test_diff_oddSplit_remainderRecipient`, `test_diff_successfulAppeal_refund` |
| AP-DISP (no duplicate terminal effect) | bond distributed guard | `BondLedger` terminal/consumed state | `test_diff_doubleResolution_idempotent` |
| AP-REPLAY (replay cannot duplicate effect) | resolution/system-level idempotence | `BondLedger` settlement idempotence | `test_diff_doubleResolution_idempotent`, TraceEquivalence v2 lifecycle |
| AP-ATOM (reverted-op atomicity) | distribution failure reverts resolution | BondLedger atomic settlement | `test_no_silent_noop_distributionFailureRevertsResolution` |
| AP-CLAIM (allocated = claimable + claimed) | aggregate recipient/token claim | bond-addressed `claimFor` (attribution diverges) | `test_diff_claims_moveFunds`; aggregation is canonical Profile A |
| AP-ID (distinct round-bound bond IDs) | distinct bond IDs in DRM | `BondLedger` bond identity | `test_bondLedgerOutcome_hasRootedForensicLineage` |

Classification discipline:

- Economic and state/effect equivalence across the differential corpus is established
  (equal claimable, reservable, allocations, conservation).
- Attribution/provenance **diverges by design**: Profile A is aggregate-only per
  (escrow, workflow, recipient); Profile B is bond-addressed. This is the recorded
  claim-attribution divergence, not a trace-only difference.
- Trace/interface divergence is expected and not treated as semantic incompatibility
  (e.g. revert vs idempotent no-op, different events/internal call path).

These Forge runs are evidence that the **current working-copy source** (bound at A-2) is
self-consistent; they do not claim the historical February deployment passed this corpus.

## 5d. A-4 status: on-chain Profile-B deployment read-back (VALIDATED — deterministic local hardhat)

A-4 requires a real Profile-B deployment and reading critical bindings back from chain
(`EscrowVault.disputeResolutionModule`, DRM `incentiveModule`, `facade.bondLedger()`,
`BondLedger` `AUTHORIZED_CALLER`, governance `ROLE_TIMELOCK`), producing a Profile-B manifest
with addresses and runtime codehashes.

**Resolved blocker.** The previous attempt against the local `hardhat` network failed during
hardhat-deploy dependency resolution with `RangeError: Maximum call stack size exceeded` at
`recurseDependencies`. The root cause was a **pre-existing deploy-script dependency cycle**
through umbrella/grouping tags:

```text
85_dr3_modules.ts  depends on `governance` (umbrella tag incl. 95_finalize_governance)
95_finalize_governance.ts depends on `dr3`/`dr3-modules`/`governance` (incl. itself + 85)

cycle: 85 -> 95 -> 85  and  85 -> 95 -> 86 -> 85;  self-cycle: 95 depends on tag `governance`
```

This is unrelated to Profile B — the same cycle would recur on any fresh network with no saved
deployments, and is short-circuited on persistent networks where earlier artifacts already
exist. Two surgical deploy-script fixes break the cycle without changing Solidity or the
intended Profile-B topology, plus a new read-only graph validator:

1. `deploy/85_dr3_modules.ts`: `func.dependencies = ['timelock', 'token']` (precise phases;
   was `['core', 'module-management', 'governance']`).
2. `deploy/87_bondledger_profile.ts`: `func.dependencies = ['decentralized-resolution-module']`
   (was `['dr3', 'decentralized-resolution-module']`).
3. `deploy/95_finalize_governance.ts`: deps trimmed to precise phase tags only
   (`aave-yield-module`, `guardian`, `escrow`, `module-management`,
   `decentralized-resolution-module`); removed umbrella `dr3`/`dr3-modules`/`governance`/`core`.
4. New `scripts/validate-deploy-graph.ts`: read-only DFS validator (parses `func.tags`/`func.dependencies`,
   expands dependency tags to carrier scripts, `visiting`/`visited` sets) —
   **`RESULT: OK — deploy dependency graph is acyclic`** (only benign warning: `05_create2_factory`
   depends on tag `vault` carried by no script — dangling, outside the Profile-B closure).

**Long-term convention (recorded):** precise phase tags may be dependencies; umbrella/grouping
tags (`dr3`, `governance`, `core`, `all`, ...) are CLI selection only and must NOT appear in
`func.dependencies`.

Two additional fresh-local-only constructor/access-control defects were repaired in the deploy
scripts while getting the Profile-B path to complete (no Solidity changed):

- `deploy/85_dr3_modules.ts`: `DRMAdminFacet` deploy `args`/`registerDeployment`
  `constructorArgs` corrected from `[timelockAddr, stableToken]` to `[]` — the facet has a
  zero-arg (implicit) constructor (verified: no `constructor` in `DRMAdminFacet.sol`/
  `DRMStorageBase.sol`; foundry uses `new DRMAdminFacet()`).
- `deploy/86_decentralized_resolution_module.ts`: step 7 (seeding DRMAdminFacet's own
  direct-call role registry) made best-effort/non-fatal. Because `DRMAdminFacet` grants
  `DEFAULT_ADMIN_ROLE` to no one at construction, its own registry cannot be seeded after
  deployment; governance flows through DRM delegation (where TimelockController already holds
  `ROLE_TIMELOCK`), so the script now logs a note and skips instead of reverting.
- `deploy/87_bondledger_profile.ts`: fresh Profile-B DRM now bootstraps `setAdminFacet(shared
  facet)` (was missing — the delegated `setIncentiveModule` would revert `AdminFacetNotSet`)
  and grants the deployer temporary `ROLE_TIMELOCK` on the fresh DRM to drive the delegated
  setters, mirroring the deploy/86 documented bootstrap. `setBondTokenRegistry(shared)` is
  also wired to match the documented activation ops.

**Resulting fresh-local Profile-B deployment completed and read-back captured**
(`DEPLOY_PROFILE=sew-dr-v2-bondledger` + `hardhat deploy --network hardhat --tags
sew-dr-v2-bondledger`):

```text
Profile-B DRM   (DecentralizedResolutionModuleProfileB): 0x59b670e9fA9D0A427751Af201D676719a970857b
BondLedger:                                           0x4ed7c70F96B99c776995fB64377f0d4aB3B0e1C1
Facade (ResolverIncentiveModuleV2BondLedger):         0x322813Fd9A801c5507c9de605d63CEA4f2CE6c44
Fresh EscrowVault (EscrowVaultProfileB):              0x7a2088a1bFc9d81c55368AE168C2C02570cB814F

readback {
  profile: "sew-dr-v2-bondledger",
  drm: "0x59b670e9fA9D0A427751Af201D676719a970857b",
  bondLedger: "0x4ed7c70F96B99c776995fB64377f0d4aB3B0e1C1",
  facade: "0x322813Fd9A801c5507c9de605d63CEA4f2CE6c44",
  "facade.bondLedger()": "0x4ed7c70F96B99c776995fB64377f0d4aB3B0e1C1",
  "ledger.authorizedCaller(facade)": true,
  "drm.incentiveModule()": "0x322813Fd9A801c5507c9de605d63CEA4f2CE6c44",
  "drm.registeredEscrow(escrowVault)": true,
  "ledger.ROLE_TIMELOCK(timelock)": true
}

codehash (keccak256 of unlinked on-chain runtime, local hardhat build — cancun/runs 10):
  DRM(Profile-B)                        0x5ea78fa0a91d08a3c8479ff4b866bde5511a0aa804121101f9bc36933f2af0d3
  BondLedger                            0x9571eab2842432a446575a456dadb9f03fef3efca1ec2961f92a7f0817476dcf
  Facade(V2BondLedger)                  0x6a5a111b16410b86822bee3fd3cede494d048411e00e951cb9e66a72260bb1ae
```

Authenticated bindings that hold: `facade.bondLedger() == BondLedger`, `drm.incentiveModule()
== facade`, `ledger.authorizedCaller(facade) == true`, `drm.registeredEscrow(vault) == true`,
governance `ROLE_TIMELOCK` granted to TimelockController. The tight load-bearing assertions in
deploy/87 (facade bound to ledger; DRM incentive == facade) pass.

Two previously-outstanding fields are now **satisfied** in this deterministic local run:

- `ledger.authorizedCaller(facade) == true`: on the local `hardhat` network the deployer is the
  temporary `TimelockController` admin, so deploy/87 drives the **real TimelockController path**
  (grant admin `PROPOSER_ROLE` → `schedule` `BondLedger.addAuthorizedCaller(facade)` → advance
  the clock past the delay → `execute`). There is no deployer-level bypass; final authority stays
  with the governance stack. Off-local the script emits rather than bypasses.
- `drm.registeredEscrow(escrowVault) == true`: when no vault arrives via
  `PROFILE_B_ESCROW_VAULT` / `PROFILE_B_REUSE_VAULT=true`, deploy/87 now deploys a **fresh
  Profile-B `EscrowVault`** (with a fresh `ModuleSnapshotRegistry` prerequisite, since the
  current `EscrowVault` constructor requires non-empty module-management code) linked against a
  freshly deployed `EscrowVaultAccountingLibrary`, registers it on the Profile-B DRM, and
  asserts `registeredEscrowContracts(vault) == true`.

**Runtime codehash advancement (A-4 Task 3, no live deployment / no gas):** for every Profile-B
closure contract whose deployed runtime has NO library link references and NO immutables, the
on-chain runtime codehash is a pure function of the compiled bytecode
(`codehash = keccak256(bytes(deployedBytecode.object))`), independent of network/address/args.
A new read-only tool `sew-protocol/scripts/bind-runtime-codehash.ts` computes this from the A-2
forge build (`out/`) and verifies `out/` matches the manifest `runtimeBytecodeSha256` for all 14
closure artifacts (confirmed). Deterministic A-2 runtime codehashes now bound (9 contracts):

```text
BondLedger                        0x6cab00879ae422f4e12af0017cfe866966a06f97f6998824480aa0927ebf62b9
DecentralizedResolutionModule     0xecead10b8c86e9c2f338cc7e457df322087f3363d83aed662af255230a5ecbd8
ResolverStakingModule             0xbd69fd3c46addd22842f35b36f7fdb98dc5a34a7344ee34ab7c3040365c2e7ba
ResolverSlashingModule            0x436ea982f36813162d931c9625f1adaa67c9210413c86998cc3a4d12478889bf
BondTokenRegistry                 0x453487a92cae5a9e50975ef9e7d0d5a31bf33f3eec9f3c280a3cdf0286891447
DRMAdminFacet                     0x995876b7f7a634f99dfa8459bfa88689984f991d5165e10a1f9236275ea1e0d9
PaymentCalculationLibrary         0x9a89943c3bebbedf4de326f8df91454a1b0657b88d161d3475af44c7d9ce900d
TimelockController                0x89c0c46844401dc8593204726c810a058fc38c2b17220a8b43e1b4578cdda70c
EscrowGovernanceTimelock          0xb3a6b317474585a0bc36c1347e81269fb35963a2f49a45284354c5f26f7de6e3
```

A baseSepolia Profile-B deployment compiled with the same A-2 build settings MUST produce these
exact `eth_getCode` hashes for those contracts. The remaining five closure contracts still carry
immutables and/or a linked library and are `ADDRESS_DEPENDENT` (code filled at construction from
constructor args / linked-library address): `ResolverIncentiveModuleV2BondLedger` (1 immutable),
`EscrowVault` (1 immutable + 1 linked lib), `InsurancePoolVault` (1 immutable), `SewToken`
(7 immutables), `GovGovernor` (8 immutables). Those still require a persistent-network read-back
(or deterministic immutable/link patching from the exact constructor args) to bind a live codehash.
Note the manifest `runtimeBytecodeSha256` is SHA-256 over the normalized bytecode string (ASCII),
NOT the on-chain codehash — the tool reports both; do not conflate them.

Status for A-4:

```text
Profile-B graph deploy + read-back wiring (deploy/87):  COMPLETED — deterministic local hardhat
   DRM <-> facade <-> BondLedger bindings read back from chain and asserted.
   escrow vault binding: SATISFIED (fresh Profile-B vault, registered on DRM).
   BondLedger facade authorization: SATISFIED via real TimelockController path (local).

On-chain runtime codehash binding (A-2 forge build): PARTIALLY CLOSED — deterministic
   codehash bound for 9 static closure contracts (scripts/bind-runtime-codehash.ts), no live
   deployment needed. The previously address-dependent 5 (Facade, EscrowVault, InsurancePoolVault,
   SewToken, GovGovernor) are now bound via a real local anvil deployment against the
   deployment-pipeline (hardhat) build; see table below. GovGovernor + EscrowGovernanceTimelock
   are deployed by the separate governance tag and were not part of the sew-dr-v2-bondledger tag.

A-2 bundleRoot source/build/bytecode identity: BOUND (f92f6f56..., see section 5b)
```

**Local anvil deployment runtime read-back (A-4 Task 3, deterministic local chain).** The
Profile-B closure was deployed end-to-end onto a local anvil node started with a raised
`--code-size-limit 49152` (48 KiB), because three closure contracts exceed the real EIP-170
24 KiB limit (`EscrowVault` 38,069 B, `DecentralizedResolutionModule` 32,130 B,
`ResolverIncentiveModuleV2BondLedger` 25,369 B) and would be rejected by a default anvil
(which enforces EIP-170). This confirms the Profile-B closure needs a raised code-size limit to
deploy on a real chain; the 24 KiB over-limit contracts are a known EIP-170 blocker.
**Deployment decision:** there will be no real-network (baseSepolia/mainnet) Profile-B deploy
until Ethereum raises the 24 KiB EIP-170 contract-size limit in an upcoming release. The
over-limit contracts will not be split for EIP-170; until the limit is raised, the local anvil
deployment at `--code-size-limit 49152` is the standing qualification vehicle, and these
codehashes are the deployment-pipeline runtime binding. The read-back drives the real local
TimelockController path (`ledger.authorizedCaller(facade) == true`) and asserts the fresh
Profile-B EscrowVault is registered (`drm.registeredEscrow(vault) == true`).

`keccak256(eth_getCode)` runtime codehashes read back from the anvil chain
(`scripts/readback-anvil-codehash.cjs`, read-only):

```text
BondLedger                        0x9571eab2842432a446575a456dadb9f03fef3efca1ec2961f92a7f0817476dcf
BondTokenRegistry                 0x3e236a6c17da308d8d11f20fc4adfd7be9e103898da051fdbfa9a0455a239c4d
DecentralizedResolutionModule     0x5ea78fa0a91d08a3c8479ff4b866bde5511a0aa804121101f9bc36933f2af0d3
DRMAdminFacet                     0x5afd9e5a276a7da457e4f14dc27998bdaf84b2a03ea54735359b2a89896e6dce
EscrowVaultAccountingLibrary      0x6cf93193d29a45190944b5942547327b18aad386b7c19c2b825800f624f24b54
EscrowVaultProfileB               0x52a3d4d972da5c3d2e02eb0a55dfa77be4d0973c9624ac60be33ed97d65c9e51
InsurancePoolVault                0x710aa079507668473081ca6c49e723723cbe7e84834b92effbdf1cd8e1ba2fed
ModuleSnapshotRegistryProfileB    0xaddeeb50484098ef559f466a432c7a2fca483444bdd5333a05ca83f7b0978d3c
PaymentCalculationLibrary         0x1a81bb1b8c6fd42d7c187437d35d10c098478c5738a7c47a60ff77020c52e95c
ResolverIncentiveModule           0x7ec49b5b3631cdf33baf71acab2ebe1d22b13a6a8fc9736b1325a9d7ef3f017c
ResolverIncentiveModuleV2BondLedger 0x6a5a111b16410b86822bee3fd3cede494d048411e00e951cb9e66a72260bb1ae
ResolverSlashingModule            0x3bc5abf84c06acbd8445c91918dd71e19c80cc738f9a74f5cbf7fbc11c49697c
ResolverStakingModule             0x283f9ef02cf518389c8e26225797a0a78553a2fd04b730d3d3f5af047d4e7ba8
SewToken                          0xa18bad68265ad778947eae04b4fc6e1d46612f62e2ac98e016cda4351086382a
TimelockController                0x4ca85edb43352cee6bb38d7ef780a2f2ba90d3a3cf15ace962c4d58cfd9e7fab
```

These anvil codehashes are bound to the **deployment-pipeline (hardhat-deploy) build** — solc
0.8.37, viaIR, `cancun` EVM, `runs` from `SOLC_RUNS` (default 10) — i.e. the same build a
baseSepolia hardhat-deploy run would use. They resolve the address/immutable/linked-library
code for the 5 previously address-dependent contracts, but are a **different build identity**
from the A-2 forge deterministic binding above (`osaka`, `runs 200`). Do not conflate the two:
- A-2 forge codehashes (the 9 static ones above) = `keccak256(bytes(deployedBytecode.object))`
  of the A-2 `out/` build.
- Anvil/hardhat codehashes (this table) = `keccak256(eth_getCode)` of the deployment-pipeline
  build actually deployed by hardhat-deploy.
```

## 6. Provenance preservation

The following remain unchanged by this qualification work:

```text
July historical corpus:     e9e20bd245eb26191b620a3731c19112b5fdc6f268818739b20b3dd0c206b206
July historical overlay:    b1751e6d473a7f438023f1678e1412bc47c356a8b23e89d7d31d1e3108c7e267
July historical checkpoint: e95e52883fd1fe5c542f5589f4d8ce81a2a995f732623ac7ccad2f39bc0c53ce
Primitive candidate suite:  716e4cb8490a51042e5490c0634e70462d393a8fee024ed4d8efcda5d70d2901
```

No Solidity, no candidate scenario bytes, and no historical scenario bytes were
modified to produce this matrix.

## 7. Known limitations / unresolved provenance

- **Runtime bytecode ↔ build/source equivalence** for the V1 deployment reference
  and for any Sew Next path: `UNKNOWN / NOT YET BOUND`.
- **Profile-B on-chain runtime codehash on baseSepolia (A-4 Task 3)**: **partially closed.**
  `scripts/bind-runtime-codehash.ts` (section 5d) deterministically binds the A-2 runtime
  codehash for the 9 static closure contracts (no link/immutables) from the forge `out/` build
  (osaka / runs 200 / viaIR) — a live read-back would confirm those same values. Still outstanding:
  the 5 `ADDRESS_DEPENDENT` contracts (Facade, EscrowVault, InsurancePoolVault, SewToken,
  GovGovernor), whose deployed code is filled at construction, require a persistent-network
  read-back (or constructor-arg immutable/link patching). The local `hardhat` codehashes are a
  different (cancun/runs 10) build and are NOT the A-2 binding.
- **Compiler/optimizer input for historical baselines**: `UNKNOWN / NOT YET BOUND`;
  current `foundry.toml` (solc 0.8.37, optimizer 200, via-IR) is a current-config
  fact, not a historical binding.
- A few line attributions overlap across divergent lineages (yield/security work
  was merged across multiple JJ lines); classifications above reflect each commit's
  own diff plus end-state wiring.
- The canonical deployed set is inferred from deploy scripts + allowlist; a
  read-only on-chain check of `EscrowVault.disputeResolutionModule` (offline here)
  would be the authoritative confirmation.

## 8. Reproducibility references

| Item | Location / binding |
| --- | --- |
| V1 corrected reference | `5f84818df95d35edbd0a8c74565043fccd81ff15` (Change `prkkryowwknxzptpxlwyuzomospnopqk`) |
| BondLedger review intro | `4959328684a26ebfdbd9a1c467a07e43dc27647c`; merged `f588fe022e325d27c877ab777558bcf63772a7f9` |
| Current candidate | Working copy `tmznvrqn` (02d1fdf3); parent `96600050` (`aderyn report fixes`) |
| Profile-B deployable bundle (A-2) | `config/deployable-allowlist-bondledger.json` + `deployables-bondledger/manifest.json`; bundleRoot `f92f6f56d9cc2f293d49b002ae7918dc5e846cded293fec92c3f5970a1f5ea07`; source anchor `tmznvrqn` |
| Appeal tranche freeze | `docs/architecture/appeal-bond-assurance/README.md` |
| Compatibility divergences | `DR-C-003-denomination-decision.md`, `DR-C-005-forfeiture-provenance.md`, `version-lineage.md` |
| Solidity stability statement | `sew-protocol/docs/security/SOLIDITY_STABILITY_STATEMENT.md` |
| Profile-B fresh-local deployment read-back (A-4) | `hardhat deploy --network hardhat --tags sew-dr-v2-bondledger` (`DEPLOY_PROFILE=sew-dr-v2-bondledger`); addresses + bindings in section 5d |
| Deploy-graph validator | `sew-protocol/scripts/validate-deploy-graph.ts` (DFS, `visiting`/`visited`) — `RESULT: OK — acyclic` |
