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
(`96600050` → working copy `f01cfde0`), which continues the successor/readability
programme (`reorg` subdirs, v1/v2 incentive consolidation, yield cleanup,
aderyn-fix hardening, governance-controlled recovery).

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
| **BondLedger extraction** (`49593286`, `2533d0b0`) | `BondLedger.sol`, `IBondLedger.sol`, `ResolverIncentiveModuleV2BondLedger` facade, differential suite | ALTERNATIVE-UNWIRED (review substrate) | **not wired**: not in deploy scripts, allowlist, or registry; only `scripts/print-contract-sizes.ts`; no `AUTHORIZED_CALLER` grants | deploy/wiring grep |
| **Extracted appeal primitive** (PRF, not Solidity deployment) | reusable appeal-bond projection/query layer | candidate architecture | not wired into deployment (`BondLedger` unwired) | primitive-native PRF tranche (below) |
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
boundary). The BondLedger `V2BondLedger` facade is **not** the live incentive
module; the deployed/allowlisted module is `ResolverIncentiveModule`.

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
   the canonical (wired) `ResolverIncentiveModule` path; a formal Solidity
   equivalence/regression layer (e.g. `BondBehaviourCorrection`, `TraceEquivalence`)
   must run against the candidate and the reference.
4. **Multi-bond claim ambiguity** — Bond A / Bond B → same recipient → same asset
   aggregate withdrawal is unresolved (`REQUIRES DECISION`); not guessed via
   map-order/FIFO/LIFO.
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
| Current candidate | Working copy `f01cfde0`; parent `96600050` (`aderyn report fixes`) |
| Deployment/wiring evidence | `deploy/`, `config/deployable-allowlist.json`, `deploy-registry/`, `deployables/manifest.json` |
| Appeal tranche freeze | `docs/architecture/appeal-bond-assurance/README.md` |
| Compatibility divergences | `DR-C-003-denomination-decision.md`, `DR-C-005-forfeiture-provenance.md`, `version-lineage.md` |
| Solidity stability statement | `sew-protocol/docs/security/SOLIDITY_STABILITY_STATEMENT.md` |
