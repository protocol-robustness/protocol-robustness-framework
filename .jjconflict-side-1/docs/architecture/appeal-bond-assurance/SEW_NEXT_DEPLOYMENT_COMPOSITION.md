# Sew Next — Deployment Composition Freeze (Priority A-1)

> **Purpose.** Freeze the *exact canonical deployment topology* that subsequent Sew Next
> qualification must bind to. Before any build/source/runtime equivalence work (A-2),
> writing down the unambiguous graph of "which contract is wired where" is mandatory:
> changing the composition invalidates the useful part of bytecode/build evidence.
>
> This is a **read-only provenance/documentation artifact**. No Solidity, deployment
> wiring, Git refs, or JJ history were modified to produce it.
>
> Evidence anchors (all verified read-only below):
> - `config/deployable-allowlist.json` (25 entries)
> - `deployables/manifest.json` (25 artifacts, bytecode/ABI SHA-256)
> - `deploy-registry/base-sepolia/v1.x/2026-02-19.json` (historical V1 deployment)
> - `deploy/85_dr3_modules.ts`, `deploy/86_decentralized_resolution_module.ts` (Sept candidate wiring)
> - `contracts/modules/decentralized-resolution-module/**`, `contracts/shared/**`, `contracts/modules/DefaultResolutionModule.sol`

---

## 0. Scope definition: what "canonical" means here

The Sept candidate is defined by what the **deploy scripts 85/86 actually wire** and what
the **deployables manifest/allowlist** expose as build artifacts. Anything present in the
source tree but absent from those three surfaces is classified as **unwired / alternative**.

| Surface | Names it | What it asserts |
| --- | --- | --- |
| `deployables/manifest.json` (25) | build artifacts | what is compiled as a deployable |
| `config/deployable-allowlist.json` (25) | allowed deployables | what may be deployed |
| `deploy/85_*.ts`, `deploy/86_*.ts` | DRM + incentive topology | the actual wired graph |

**Confirmed consequence:** `shared/BondLedger`, `ResolverIncentiveModuleV2BondLedger`,
`DefaultResolutionModule`, `EscrowAppeals/IEscrowAppeal`, and `KlerosArbitrableProxy`
are **not** in the manifest/allowlist and are **not** wired by 85/86. They are
alternative / latent / review substrates (see §5).

---

## 1. Canonical deployment graph (Sept candidate)

```
Governance / authority
├── SewToken                         contracts/token/SewToken.sol              (voting token)
├── GovGovernor                      contracts/governance/GovGovernor.sol     (timelock control)
├── TimelockController (OZ)          node_modules/.../TimelockController.sol   (2-day execution lane)
├── EscrowGovernanceTimelock         contracts/admin/EscrowGovernanceTimelock.sol
├── DefaultReleaseStrategy           contracts/modules/DefaultReleaseStrategy.sol
├── DefaultCancellationStrategy      contracts/modules/DefaultCancellationStrategy.sol
├── EmergencyRecoveryProposal        contracts/governance/EmergencyRecoveryProposal.sol
└── GuardianOps                      contracts/ops/GuardianOps.sol

Core / infra
├── EscrowVault                      contracts/core/EscrowVault.sol           (disputeResolutionModule holder)
├── EscrowableERC20                  contracts/core/EscrowableERC20.sol
├── EscrowCreationPolicy             contracts/core/EscrowCreationPolicy.sol
├── BondCollector                    contracts/core/BondCollector.sol
├── CREATE2EscrowFactory             contracts/core/CREATE2EscrowFactory.sol
├── MultiL2ViewAggregator            contracts/core/MultiL2ViewAggregator.sol
├── ModuleSnapshotRegistry           contracts/core/ModuleSnapshotRegistry.sol
└── L2AddressRegistry                contracts/core/L2AddressRegistry.sol

Yield
└── AaveYieldModule                  contracts/modules/AaveYieldModule.sol     (sole canonical yield module)

DR v3 / Decentralized Resolution (85 → 86)
├── InsurancePoolVault               .../InsurancePoolVault.sol                (slash proceeds + protocol fees)
├── ResolverStakingModule            .../staking/ResolverStakingModule.sol
├── ResolverSlashingModule           .../slashing/ResolverSlashingModule.sol
├── BondTokenRegistry                .../bond/BondTokenRegistry.sol
├── DRMAdminFacet                    .../DRMAdminFacet.sol                    (governance admin surface)
├── PaymentCalculationLibrary        .../libraries/PaymentCalculationLibrary.sol (public, must be linked)
├── ResolverIncentiveModule          .../incentive/ResolverIncentiveModule.sol  ← canonical appeal-bond module
└── DecentralizedResolutionModule    .../DecentralizedResolutionModule.sol       ← canonical DRM (86)
```

### Wiring decisions that actually matter (verified in `deploy/86_decentralized_resolution_module.ts`)

| Decision | Value | Source |
| --- | --- | --- |
| Canonical DRM | `DecentralizedResolutionModule` (NOT `DefaultResolutionModule`, NOT `V2BondLedger` facade) | `86` `deploy('DecentralizedResolutionModule', args:[deployer])` |
| DRM admin surface | `DRMAdminFacet` (set via `drm.setAdminFacet`) | `86` step 2 |
| Bond token registry | `BondTokenRegistry` (`drm.setBondTokenRegistry`) | `86` step 4 |
| **Incentive module** | **`ResolverIncentiveModule`** (`drm.setIncentiveModule`) | `86` step 5 |
| Escrow binding | `EscrowVault` registered on DRM (`drm.registerEscrowContract`) | `86` step 6 |
| Governance roles | `ROLE_TIMELOCK` on DRM → `TimelockController` (+ `EscrowGovernanceTimelock`); `ROLE_TIMELOCK` on `DRMAdminFacet` → Timelock | `86` steps 3,7 |

### Appeal-bond terminal disposition path (canonical Solidity semantics)

```
DecentralizedResolutionModule.recordResolution(workflowId, escrowContract, resolver, outcome, resolutionTime)
  [onlyEscrowContract]
  └─ if (currentRound > 0 && priorDecision != NONE)
       incentiveModule.distributeAppealBond(workflowId, escrowContract, priorRound, outcomeFlipped)
         require(round < 2, 'Invalid round - no higher round')     // 0/1 escalation
         AppealBondRecord bond = appealBonds[escrow][workflow][round+1]
         require(bond.amount > 0, 'No bond recorded')
         require(!bond.distributed, 'Bond already distributed')    ← primitive duplicate-disposition guard
         bond.distributed = true; bond.amount = 0
         if (outcomeFlipped) _refundBond(...)        // appeal succeeded → refund depositor
         else                _payBondToResolvers(...) // appeal failed → pay prior-round resolvers
```

This is the authoritative ordinary terminal disposition. The PRF model represents the same
transition as `execute_resolution → apply-resolution-transition → settle-appeal-bond-on-resolution
→ record-bond-disposition-by-id`. The `resolve_appeal` / `slash` path is a separate slash-appeal
semantics (`:slash-not-found`) and is **not** the ordinary appeal-bond terminal path.

### Why `ResolverIncentiveModule` is the canonical appeal-bond module

- It is the only incentive module wired by 85/86 (`drm.incentiveModule`).
- It keeps appeal-bond custody inline (`appealBonds[escrow][workflow][round]`) with the
  `bond.distributed` guard and DR v2 metrics (`getV2Metrics`).
- It is a build artifact in `deployables/manifest.json` (contractName `ResolverIncentiveModule`).
- The `V2BondLedger` facade *extends* it, which is why the BondLedger-backed variant is a
  drop-in **alternative** rather than a different canonical base.

---

## 2. Composition identity resolution

Per canonical contract, the fields a future A-2 build/source/runtime checkpoint must populate:

```text
contract name
source revision (Sew Next ancestor)
compiler version + optimizer settings + viaIR
constructor/config inputs
creation bytecode + hash
runtime bytecode + hash
deployed address (if any)
runtime codehash
links (CREATE2EscrowFactory has link references; PaymentCalculationLibrary is a public linked library)
```

The deployables manifest already carries `creationBytecodeSha256`, `runtimeBytecodeSha256`,
`abiSha256`, `fileSha256`, `sourcePath`, and link-reference flags for all 25 artifacts —
this is the fingerprint substrate A-2 will build on.

---

## 3. Historical V1 deployment is a separate reference

The Feb 19 base-sepolia-v1 deployment (`deploy-registry/base-sepolia/v1.x/2026-02-19.json`)
deployed the **older core-only surface** (14/15 contracts: `ModuleSnapshotRegistry`, `YieldOps`,
`DisputeOps`, `SettlementOps`, `CreateOps`, `BondCollector`, `EscrowGovernanceTimelock`, `SewToken`,
`TimelockController`, `DefaultReleaseStrategy`, `GuardianSafe`, `GovGovernor`, `L2AddressRegistry`,
`EscrowVault`, `AaveYieldModule`). It does **not** include the DR v3 resolution set (85/86) because
that is the Sept candidate work.

- **Sew V1 deployment source reference**: `74176ab9…` (tag `testnet/base-sepolia-v1` at `1904950a`,
  identical Solidity tree).
- **Sew V1 corrected semantic reference**: `5f84818d…` (corrected appeal behavior, not Feb deploy).
- **Sew Next**: `49593286…` onward (merged `f588fe02…`); current Sept `main` line.

---

## 4. Canonical candidate graph — frozen decisions

| Component | Chosen implementation | Alternative(s) present but **unwired** |
| --- | --- | --- |
| Dispute resolution module | `DecentralizedResolutionModule` | `DefaultResolutionModule` |
| Resolver incentive / appeal-bond module | `ResolverIncentiveModule` | `ResolverIncentiveModuleV2BondLedger` |
| Bond custody ledger | inline in `ResolverIncentiveModule` | `shared/BondLedger` (+ `IBondLedger`) |
| Appeal contracts | (Sept appeal hardening present in tree) | `EscrowAppeals` / `IEscrowAppeal` — unwired |
| Kleros handoff | latent capability in DRM (`IKlerosHandoffResolutionModule`) | `KlerosArbitrableProxy` — unwired |
| Yield module | `AaveYieldModule` | legacy yield removed |

---

## 5. Unwired / alternative classification (verified)

Profile B (`sew-dr-v2-bondledger`) is now a **separately-wired deployment profile** in
`deploy/87_bondledger_profile.ts`, validated locally (deterministic hardhat read-back, see
`SEW_NEXT_QUALIFICATION.md` §5d). It deploys a fresh DRM + `ResolverIncentiveModuleV2BondLedger`
+ `BondLedger`, distinct from the canonical 85/86 Profile-A graph. The table below remains the
Profile-A classification; Profile-B components are annotated with their Profile-B status.

| Contract | Present? | In Profile-A allowlist? | Wired by 85/86? | Classification |
| --- | --- | --- | --- | --- |
| `shared/BondLedger`, `IBondLedger` | yes | **no** | no | ALTERNATIVE-UNWIRED (review substrate) |
| `ResolverIncentiveModuleV2BondLedger` | yes | **no** | no | ALTERNATIVE-UNWIRED (BondLedger-backed facade) |
| `DefaultResolutionModule` | yes | **no** | no (governance role lists in some scripts only) | ADDITIVE-UNWIRED |
| `EscrowAppeals` / `IEscrowAppeal` | yes | **no** | no | ALTERNATIVE-UNWIRED (Sept appeal hardening) |
| `KlerosArbitrableProxy` | yes | **no** | no | ADDITIVE-UNWIRED (latent handoff) |

**Consequence for qualification:** the canonical `deploy/85/86` Profile-A graph remains the
`ResolverIncentiveModule` inline-custody composition; PRF evidence for appeal-bond semantics
attaches to it. Profile B is a separately-logged deployment profile (fresh DRM ->
`V2BondLedger` facade -> `BondLedger`) that is locally deployable and read-back-validated;
its BondLedger-backed path is only "the implementation" within that explicitly selected
Profile-B profile, not the canonical default.

**Profile-B closure (as locally validated, `SEW_NEXT_QUALIFICATION.md` §5d):** the fresh
Profile-B graph now also includes a **fresh `EscrowVault`** (with a fresh
`ModuleSnapshotRegistry` prerequisite, since the current `EscrowVault` constructor requires
non-empty module-management code, and a freshly deployed `EscrowVaultAccountingLibrary` to
satisfy its library link). On the deterministic local run both previously-open bindings are now
satisfied and read back from chain: `BondLedger.authorizedCaller(facade) == true` (via the real
local `TimelockController` path, no deployer bypass) and `drm.registeredEscrow(vault) == true`.
Only the persistent-network (baseSepolia) `runtime codehash` binding under the A-2 forge build
remains outstanding for full A-4 (Task 3).

---

## 6. Open items before A-2 (not blocking the freeze)

1. `DefaultResolutionModule` appears in governance script role lists — confirm whether any
   live escrow selects it as its `disputeResolver` (unlikely for the canonical candidate; verify
   at live-read-back time, A-5).
2. `EscrowAppeals` / `IEscrowAppeal` are Sept appeal-hardening in-tree but unbound to a wired
   deployment surface — confirm their intended role before counting them in qualification.
3. A-2 must assert compiler/optimizer/viaIR per artifact; the manifest provides bytecode hashes
   but not the compiler-input binding. That is the very next substantive gate.

---

## 7. Reproducibility / verification references

- `config/deployable-allowlist.json` — 25 allowed deployables (schemaVersion 1, forge, profile default).
- `deployables/manifest.json` — 25 build artifacts with `creationBytecodeSha256` / `runtimeBytecodeSha256` / `abiSha256` / `fileSha256` / link flags.
- `deploy/85_dr3_modules.ts` — DR v3 modules (Insurance, Staking, Slashing, BondRegistry, AdminFacet, PaymentLib, Incentive).
- `deploy/86_decentralized_resolution_module.ts` — DRM deploy + admin-facet bootstrap + registry/incentive/escrow wiring + role grants.
- `deploy-registry/base-sepolia/v1.x/2026-02-19.json` — historical V1 deployment (distinct reference; compiler solc 0.8.20, optimizerRuns=10, viaIR=true).
- `contracts/modules/decentralized-resolution-module/incentive/ResolverIncentiveModule.sol` — `distributeAppealBond` (line 1410) + `bond.distributed` guard.
- `contracts/modules/decentralized-resolution-module/DecentralizedResolutionModule.sol` — `recordResolution` (line 677) → `distributeAppealBond` (line 711).

---

## 8. Status

- **Composition freeze: DONE (this document).**
- Wiring: already exercised by existing candidate publish scripts; the graph above is the frozen intent.
- Next gate: **A-2 — source/build/runtime identity** per canonical contract (the highest-value
  substantive gate per the qualification plan).
