# ADR: Appeal-bond deployment profiles (Profile A / Profile B)

**Status:** ACCEPTED (composition + identity decision; Profile B wiring is a next-release candidate)
**Date:** 2026-09-26
**Scope:** Sew Next qualifyıcation, appeal-bond custody architecture, deployment orchestration.
**References:** [`SEW_NEXT_DEPLOYMENT_COMPOSITION.md`](SEW_NEXT_DEPLOYMENT_COMPOSITION.md)
(read-only composition freeze, Profile A baseline).

---

## 1. Decision

Both deployment profiles are officially supported. They are distinct compositions,
not "old vs new".

- **Profile A — `sew-dr-v2-inline-custody`**: `DecentralizedResolutionModule →
  ResolverIncentiveModule → inline appealBonds custody`. This is the **supported reference
  profile** — not deprecated, not incorrect, not a promotion target.
- **Profile B — `sew-dr-v2-bondledger`**: `DecentralizedResolutionModule →
  ResolverIncentiveModuleV2BondLedger → BondLedger`. This is the **intended next-release
  target**, described as *next-release candidate* while qualification is underway — not
  "experimental".

Profile identity MUST be explicit in every artifact (manifest, deployment evidence,
equivalence report, PRF claim). A single "Sew Next" identifier MUST NOT be overloaded
for both graphs.

---

## 2. Stable profile identities

| Profile        | ID                          | Composition (resolution → incentive → custody)            | Status |
| ---            | ---                         | ---                                                       | ---    |
| A (baseline)   | `sew-dr-v2-inline-custody`  | `DecentralizedResolutionModule → ResolverIncentiveModule → inline appealBonds` | supported, reference, not deprecated, not promotion target |
| B (next)       | `sew-dr-v2-bondledger`      | `DecentralizedResolutionModule → ResolverIncentiveModuleV2BondLedger → BondLedger` | next-release candidate |

The deployables allowlist already carries a bundle-level `buildProfile` field
(currently `"default"`). Profile identity should be expressed there and mirrored in the
manifest (`buildProfile` / `bundleVersion`), and as a `profile` tag in deploy-registry
records and the Profile B manifest (§9 of the parent task spec).

---

## 2b. Closure update (post-review, 2026-09-26)

This section records decisions that must be closed before A-2 and corrects inaccuracies in
the initial ADR.

### Corrected Profile-B deployment closure (14 distinct contract identities)

The earlier "11 artifacts" label was incorrect. The actual transitive deployment closure for
an independent Profile-B graph is 14 identities in three tiers:

| Tier | Artifacts | Count | Notes |
| --- | --- | --- | --- |
| Profile-specific | BondLedger, ResolverIncentiveModuleV2BondLedger, fresh DecentralizedResolutionModule, fresh EscrowVault (Profile-B binding) | 4 | new Profile-B instances / identities |
| Shared protocol (reused) | ResolverStakingModule, ResolverSlashingModule, InsurancePoolVault, BondTokenRegistry, DRMAdminFacet, PaymentCalculationLibrary | 6 | same bytecode identity as Profile A; bound in Profile-B manifest |
| Shared governance | TimelockController, EscrowGovernanceTimelock, SewToken, GovGovernor | 4 | same governance stack |
| Total | | 14 | |

PaymentCalculationLibrary is a public linked library with a separate bytecode identity that
A-2 must bind. CREATE2EscrowFactory enters the closure only if a fresh EscrowVault is created
through the factory (create2 path); if EscrowVault is deployed directly it is out of the
Profile-B bytecode closure.

### Independent Profile-B topology (frozen)
Profile B is a fresh, independent deployment profile. It does NOT mutate Profile A's DRM in
place. It owns: fresh DRM (DecentralizedResolutionModule, Profile-B instance),
ResolverIncentiveModuleV2BondLedger (facade), BondLedger, EscrowVault/DRM binding
(Profile-B), and a profile manifest, while reusing the same governance architecture where
appropriate. An A-to-B in-place migration, if desired later, is a separate migration
profile / qualification path.

### Deployment vs governance activation (two phases)
- Deployment phase: deploy fresh DRM, BondLedger, facade, Profile-B EscrowVault binding; wire
  shared staking/slashing/insurance/registry/admin-facet/payment-lib; emit all required
  activation operations.
- Governance activation phase: execute through the real Timelock/governance path:
  BondLedger.addAuthorizedCaller(facade), DRM.setIncentiveModule(facade), DRM.setAdminFacet,
  DRM.setBondTokenRegistry, register escrow, and required role grants. Final authority returns
  to the normal Sew governance stack.
- For deterministic local qualification, execute the real Timelock path (or the documented
  slow-lane) rather than bypassing where practical. No temporary deployer privilege unless an
  existing documented Sew bootstrap mechanism requires it.

### Forfeiture economics: EQUIVALENT
Both profiles retain forfeited value in a forfeitedBondReserve. Profile A:
ResolverIncentiveModule.forfeitedBondReserve[token] (+ totalBondsForfeited). Profile B:
BondLedger.forfeitedBondReserve[asset]; FORFEIT_DESTINATION (0x..dEaD) is a nominal
allocation recipient only - BondLedger._settleBond FORFEIT branch routes to the reserve and
never transfers to the recipient. No value is burned. No Solidity change required. Do not
treat the dead-address constant as an economic burn.

### Claim attribution: DIVERGENT (recorded, not silent)
- Profile A: aggregate-only per (escrow, workflow, recipient/token); no per-bond consumed
  attribution may be inferred (canonical).
- Profile B: bond-addressed claim(bondId, recipient) / claimFor(bondId, recipient);
  authoritative per-bond attribution exists and can be qualified later.
- Economic amount semantics = expected equivalent; attribution/provenance diverges;
  interface/trace diverges. Do NOT add AP-CLAIM-002 yet.

### Revised four-dimensional equivalence contract
1. economic equivalence
2. state/effect equivalence
3. attribution/provenance equivalence
4. trace/interface equivalence
Do not collapse claim attribution into ordinary trace differences. Record the
no-double-settlement distinction separately: Profiles A and B may each enforce it via
different primitive guards (bond.distributed vs BondLedger PENDING / _requirePositionOperator)
even where outer system-level replay behavior differs.

### Profile-B manifest/allowlist strategy
A Profile-B profile-specific allowlist (profile-id = sew-dr-v2-bondledger, buildProfile)
emits its own manifest.json; the Profile-A allowlist/manifest is NOT modified or
reinterpreted. The Profile-B manifest binds: profile ID, source revision, build configuration,
artifact hashes, constructor inputs, deployment addresses, runtime codehashes, governance
activation operations, and post-activation binding reads. This task defines the boundary
only - populating runtime/codehash evidence is A-2/A-4.

---

## 3. Profile A — supported baseline (frozen)

Verified from `deploy/85_dr3_modules.ts` + `deploy/86_decentralized_resolution_module.ts`
and the Solidity sources:

```
DecentralizedResolutionModule
  incentiveModule = ResolverIncentiveModule
  appeal-bonds: inline appealBonds[escrow][workflow][round], bond.distributed guard
supporting graph (shared)
  ResolverStakingModule, ResolverSlashingModule, InsurancePoolVault,
  BondTokenRegistry, DRMAdminFacet, PaymentCalculationLibrary,
  EscrowVault binding, governance/timelock stack
```

- Terminal disposition: `DRM.recordResolution` (higher round) →
  `ResolverIncentiveModule.distributeAppealBond`, guarded by
  `require(!bond.distributed, 'Bond already distributed')`.
- Existing deployment-script provenance (`deploy/85`, `deploy/86`) is preserved unchanged.
- Profile A is the reference baseline for all differential/equivalence work.

---

## 4. Profile B — next-release target (defined from actual code)

### 4.1 Contracts

- **`BondLedger`** (`contracts/shared/BondLedger.sol`), interface `IBondLedger`.
- **`ResolverIncentiveModuleV2BondLedger`** (`.../incentive/ResolverIncentiveModuleV2BondLedger.sol`)
  — `abstract` no; it *extends* `ResolverIncentiveModule`, so it inherits resolver
  tracking, metrics, and events, and overrides the custody/settlement path to delegate to
  `BondLedger`.

### 4.2 Constructors / setter / authority (from source)

`BondLedger`:
```solidity
constructor(address admin) {
    _grantRole(DEFAULT_ADMIN_ROLE, admin);
    _grantRole(ROLE_TIMELOCK, admin);
}
function addAuthorizedCaller(address caller) external onlyRole(ROLE_TIMELOCK)   // grants AUTHORIZED_CALLER
function removeAuthorizedCaller(address caller) external onlyRole(ROLE_TIMELOCK)
modifier onlyAuthorized  // postBond / settleBond / claimFor are onlyAuthorized
modifier _requirePositionOperator // settleBond requires msg.sender == position.operator
```

`ResolverIncentiveModuleV2BondLedger`:
```solidity
constructor(address initialOwner, address initialLibrary, address bondLedgerAddr)  // extends ResolverIncentiveModule(initialOwner, initialLibrary)
    if (bondLedgerAddr == address(0)) revert BondLedgerRequired();
    bondLedger = IBondLedger(bondLedgerAddr); // immutable

recordAppealBond(...) onlyEscrowContract
   bondId = keccak256(abi.encode(escrowContract, workflowId, round))
   if ETH: bondLedger.postBond{value}(bondId, escrow, escalatedBy, escalatedBy, address(0), amount, ctx, 0)
   else:   safeTransferFrom(depositor→facade); safeIncreaseAllowance(facade→bondLedger);
           bondLedger.postBond(bondId, escrow, escalatedBy, address(this), token, amount, ctx, 0)

distributeAppealBond(...) onlyEscrowOrResolutionModule
   pos = bondLedger.getBond(bondId); require(pos.principal > 0, 'No bond recorded')
   bondLedger.settleBond(bondId, allocs, kind(REFUND|FORFEIT|RESOLVER_PAYOUT), causeType, causeRoot)

claimBondRefundLedger / claimPaymentLedger
   loop rounds 1..2: bondLedger.getClaimable(bondId, msg.sender) → bondLedger.claimFor(bondId, msg.sender)

forfeitAppealBondLedger(…) onlyEscrowContract → settleBond FORFEIT
onDisputeFinalized(…) onlyEscrowOrResolutionModule → settleBond FORFEIT (DISPUTE_FINALIZED cleanup)
```

### 4.3 Authority / authorization relationships

| Relationship | Who grants | Role on target |
| --- | --- | --- |
| `BondLedger.addAuthorizedCaller(facade)` | `BondLedger.admin` (governance) | `AUTHORIZED_CALLER` on `BondLedger` → **facade** |
| facade → `bondLedger.postBond/settleBond/claimFor` | via `AUTHORIZED_CALLER` (above) + `_requirePositionOperator` (operator == facade that posted) | — |
| `DRM.setIncentiveModule(facade)` | DRM admin `ROLE_TIMELOCK` | facade becomes DRM incentive module |
| `DRM.recordResolution → facade.distributeAppealBond` | facade implements `onlyEscrowOrResolutionModule` | — |
| `EscrowVault` → `recordAppealBond/forfeitAppealBondLedger` | facade `onlyEscrowContract` | — |
| `BondLedger.addAuthorizedCaller/removeAuthorizedCaller` | `ROLE_TIMELOCK` (governance) | — |

### 4.4 Duplicate-disposition / no-double-consumption mechanism (Profile B)

BondLedger's `_settleBond` requires `pos.status == PENDING` (a second settle reverts
`InvalidStatus()`) and `_requirePositionOperator` gates settle to the posting facade.
`claim` consumes `_claimable[bondId][recipient]` and deletes it (a second claim of the
same amount reverts `NothingClaimable`). Security position lineage is immutable once
posted (a second `postBond` on an existing bond reverts `InvalidStatus`).

### 4.5 Deployment ordering

```
0. shared baseline already deployed (85/86 Profile A components are reusable)
1. deploy BondLedger(admin=governance)
2. deploy PaymentCalculationLibrary (reused, if not present)
3. deploy ResolverIncentiveModuleV2BondLedger(owner, paymentLib, bondLedger)
4. BondLedger.addAuthorizedCaller(facade)          [ROLE_TIMELOCK]
5. DRM.setIncentiveModule(facade)                  [ROLE_TIMELOCK]  (on the FRESH Profile-B DRM only - never Profile A's DRM)
6. register the Profile-B EscrowVault binding on the fresh DRM
7. read-back: BondLedger.authorizedCaller(facade), DRM.incentiveModule()==facade, facade.bondLedger()==BondLedger
```

---

## 5. Shared vs profile-specific components

| Component | Shared (identical) | Profile-specific |
| --- | --- | --- |
| `DecentralizedResolutionModule` | — | fresh Profile-B instance (Profile A uses its own DRM) |
| `ResolverStakingModule` | ✔ shared | — |
| `ResolverSlashingModule` | ✔ shared | — |
| `InsurancePoolVault` | ✔ shared | — |
| `BondTokenRegistry` | ✔ shared | — |
| `DRMAdminFacet` | ✔ shared | — |
| `PaymentCalculationLibrary` | ✔ shared | — |
| `EscrowVault` | — | Profile-B binding (dedicated vault or explicit reuse; fresh legacy-core vault is a separate qualification path) |
| Incentive module | — | A=`ResolverIncentiveModule` , B=`ResolverIncentiveModuleV2BondLedger` |
| Bond custody | — | A=inline `appealBonds`, B=`BondLedger` (+ `IBondLedger`) |

Profile B is deployed as a fresh, independent graph. It reuses every shared supporting
component unchanged (staking/slashing/insurance/registry/admin-facet/payment-lib), but owns
its own DRM instance, its own EscrowVault/DRM binding, plus the incentive module and the
custody surface. It does NOT mutate Profile A's deployed DRM or EscrowVault.

---

## 6. Governance coherence

Both profiles use the **same** Sew governance stack:

```text
TimelockController (2-day lane), EscrowGovernanceTimelock, SewToken, GovGovernor
```

- Profile B does **not** introduce parallel governance.
- `BondLedger` is governed through `ROLE_TIMELOCK = keccak256('ROLE_TIMELOCK')` (same
  role constant as the modules), and constructor `admin` should be the TimelockController
  (or the governance signer that then transfers ownership): `admin` receives
  `DEFAULT_ADMIN_ROLE` + `ROLE_TIMELOCK`.
- So the governance binding is: `TimelockController` holds `ROLE_TIMELOCK` on `BondLedger`
  and on the facade, exactly as it does on the DRM and other modules.
- Any profile-specific governance setting (e.g. who may `addAuthorizedCaller`) is explicit
  and namespaced by component/profile in the manifest / registry tags.

**Design decision (RESOLVED):** `BondLedger` `admin` is the `TimelockController` directly,
consistent with the rest of the stack. The `deploy/87_bondledger_profile.ts` orchestration
uses `admin = TimelockController` for the BondLedger constructor and keeps all
activation operations on the governance path (see section 2b).

---

## 7. Equivalence contract (Profile A ↔ Profile B)

### Economic equivalence (for equivalent inputs)
Same bond principal, posting fee, beneficiary, refund / resolver-payout / forfeiture
amount.

### Semantic / state equivalence
Equivalent bond lifecycle, terminal disposition, custody conservation, entitlement
creation, claim availability, no-double-consumption, failure atomicity, round identity,
resolver payout/refund semantics.

### Trace / interface equivalence (tracked separately, NOT required to match)
revert vs idempotent no-op, error selector/string, events, internal call path, storage
representation. A trace/interface divergence does **not** imply semantic incompatibility —
it is classified explicitly. State/economic equivalence is never weakened because
implementation structure differs.

---

## 8. Frozen PRF appeal claims → both profiles

| Property | Profile A mechanism | Profile B mechanism | Expected semantic equivalence | Solidity evidence |
| --- | --- | --- | --- | --- |
| AP-CONS (posted = fee + custodied) | inline `appealBonds[*.amount]` custody, `_requirePayoutToken`, metrics | `BondLedger.postBond(principal)` custody + facade metrics | same conservation equation | `ResolverIncentiveModule.distributeAppealBond` vs facade `recordAppealBond` |
| AP-DISP (no duplicate terminal effect) | `bond.distributed` guard (`require(!bond.distributed,'Bond already distributed')`) | BondLedger `_settleBond` requires `PENDING` (`InvalidStatus`) + `_requirePositionOperator` | a terminal bond cannot create a 2nd custody/allocation/entitlement effect | `ResolverIncentiveModule` line 1425 vs `BondLedger._settleBond` |
| AP-REPLAY (no duplicate disposition/entitlement/custody) | `bond.distributed` + amount cleared | `_settleBond` PENDING guard + position-operator binding | replay cannot duplicate effect | same pair |
| AP-ATOM (rejected settlement preserves root) | `distributeAppealBond` reverts atomically; PRF receipt pre-root==post-root | `settleBond` reverts atomically on invalid state/cause; PRF receipt pre-root==post-root | equal appeal-bond pre/post semantic roots on revert | PRF AP-ATOM + facade settle-revert tests |
| AP-CLAIM (allocated = claimable + claimed) | inline `claimableBondRefunds` + claim | `BondLedger._claimable` + `claimFor`/`claim` | entitlement immutable; allocation provenance survives | `claimBondRefund` vs facade `claimBondRefundLedger` |
| AP-ID (same workflow/funder, distinct ordinals → distinct bond IDs) | bond keyed by `(escrow, workflow, round)` in mapping | `bondId = keccak256(abi.encode(escrow, workflow, round))` | distinct authoritative bond identities per round | `appealBonds[escrow][workflow][round]` vs `BondLedger._positions[bondId]` |

Same storage is NOT required; equivalence is at the state/effect level.

---

## 9. A-2 scope (bind every Profile B canonical artifact — closure: 14 identities)

The A-2 build/source/runtime identity must bind the full transitive deployment closure (see
§2b):

*Profile-specific (new Profile-B instances/identity):*
1. `BondLedger`
2. `ResolverIncentiveModuleV2BondLedger`
3. `DecentralizedResolutionModule` (fresh Profile-B instance)
4. `EscrowVault` (fresh Profile-B instance / binding)

*Shared protocol (reused, bound in Profile-B manifest):*
5. `ResolverStakingModule`
6. `ResolverSlashingModule`
7. `InsurancePoolVault`
8. `BondTokenRegistry`
9. `DRMAdminFacet`
10. `PaymentCalculationLibrary` (public linked library — separate bytecode identity)

*Shared governance:*
11. `TimelockController`
12. `EscrowGovernanceTimelock`
13. `SewToken`
14. `GovGovernor`

For each: source revision, compiler version (repo: solc 0.8.37, viaIR=true, runs=10,
evmVersion=cancun), optimizer settings, library links, constructor args, creation
bytecode hash, runtime bytecode hash, ABI hash, deployment address, runtime codehash.

Profile B is the main next-release build identity. Profile A retains its existing
honest provenance status; do **not** spend equivalent effort reconstructing unrecoverable
historical build data for Profile A.

---

## 10. Profile B manifest

The Profile B manifest must be explicit about identity and include: profile id/version,
source revision, build configuration, contract identities+addresses, constructor/config
inputs, runtime codehashes, governance bindings, module bindings, `EscrowVault.disputeResolutionModule`,
DRM incentive-module address, `BondLedger` authorization/binding. Critical bindings are
**read back from the deployed chain**, not trusted from deploy-script output.

---

## 11. Equivalence matrix / classifications

Classifications distinguish:
`EQUIVALENT`, `EQUIVALENT_EFFECT / TRACE_DIVERGENT`, `INTENTIONAL_DIVERGENCE`, `UNRESOLVED`.
Matrix production follows once Profile B build identity is bound.

---

## 12. Promotion gate

Profile B may be called **next-release candidate** while qualification runs, but not the
promoted/default deployment until all hold:
1. deployment topology frozen;
2. deterministic build identity bound;
3. live bindings read back;
4. appeal semantic equivalence established;
5. known divergences documented;
6. critical invariant/backstop tests passing.

---

## 13. Remaining design decisions before Profile B wiring can be implemented

1. `BondLedger` `admin` identity: RESOLVED to `TimelockController` directly (see section 2b
   and section 6). The deploy/87 orchestration passes `admin = TimelockController` to the
   BondLedger constructor; final authority stays with the governance stack.
2. Profile B facade placement: RESOLVED to a fresh, independent Profile-B graph with its own
   DRM + EscrowVault/DRM binding and a separate manifest (see section 2b). No in-place
   mutation of Profile A's deployed DRM. A future A-to-B in-place migration is a separate
   migration profile / qualification path.
3. Where Profile B lives in the allowlist: add `BondLedger`, `IBondLedger`,
   `ResolverIncentiveModuleV2BondLedger`, plus shared DR v3 artifacts, all tagged with
   profile `sew-dr-v2-bondledger`; keep Profile A allowlist as the reference baseline.
4. Forfeiture policy: RESOLVED EQUIVALENT. Both profiles retain forfeited value in a
   forfeitedBondReserve; the FORFEIT_DESTINATION dead-address constant (0x…dEaD) is a nominal
   allocation recipient only and never receives a transfer. No value is burned, no Solidity
   change is required (see section 2b).
5. Profile B exports a separate `manifest.json` (profile-tagged) so both profiles yield
   independently identifiable manifests.

---

## 14. Provenance

- No Solidity, Git refs, deployment wiring (existing scripts 85/86), or JJ history were
  modified by this ADR.
- Historical July corpus/overlay/checkpoint roots and the frozen candidate suite root
  (`716e4cb8…`) are outside the scope of this decision.
- Profile B orchestration (additive deploy script) is delivered separately and must not
  alter Profile A's provenance.
