# Dispute Mechanism Attack Surface — Research Report

**Audience:** Protocol engineers, security researchers.
**Date:** 2026-06-19
**Last updated:** 2026-06-19 (post-Solidity assessment)

---

## Finding 1 — Challenge-Bond-Exceeds-Escrow-Value

### Status
**False positive** — simulation framework artifact, not a protocol issue.

### Correction
The 100-unit default bond exists only in `payoffs.clj:55` (the simulation
framework). The Solidity contracts have no equivalent hardcoded challenge
bond:
- `BaseEscrow.raiseDispute()` requires no bond — disputing is free
- `DefaultResolutionModule.getRequiredAppealBond()` returns `(0, address(0))` — no appeal bond
- `DecentralizedResolutionModule.getRequiredAppealBond()` uses governance-
  configurable `escalationCostConfig`, starting at 0.01 ether — no hardcoded
  "100" default
- The protocol has `minDisputeEscrowValue` (`BaseEscrow` line 196, checked
  at line 744) as the correct guard against dust escrows

The simulation framework's `calculate-challenge-bond-amount` fallback of 100
is an arbitrary default that does not correspond to any real protocol
parameter. This finding should be corrected by either removing the default
or aligning it with the protocol's `minDisputeEscrowValue`.

### Action
Fix `payoffs.clj:55` — either remove the 100-unit default (require explicit
configuration) or align it with the Solidity contract's minimum escrow value
guard.

---

## Finding 2 — Slash-Succeeds-Victim-Not-Made-Whole

### Status
**Contingent vulnerability** — real code flaw in `_distributeSlashedFunds`
(`toCounterParty = 0`), but mitigated by context for currently-enabled slash
types. Becomes critical if fraud slashing is enabled without adding a
victim-compensation tranche.

### Solidity ground truth
`ResolverSlashingModuleV1._distributeSlashedFunds()` (line 964-967):
```
distribution.toInsurancePool = (amount * 5000) / BASIS_POINTS;  // 50%
distribution.toProtocol = (amount * 3000) / BASIS_POINTS;       // 30%
distribution.toCounterParty = 0;                                 // 0%
distribution.toSlashProposer = 0;
```
Remaining 20% goes to `retainedSlashReserves` (unallocated).

### Why it's mitigated today
- **Timeout slashes** (TIMEOUT_ACCEPT/TIMEOUT_RESOLVE): Resolver failed to
  act; escrow auto-cancels → sender gets funds back via normal escrow
  settlement path. No victim needs compensation.
- **Reversal slashes**: Resolver's decision was overturned on appeal.
  The appeal process corrects the escrow outcome, making the correct party
  whole. The slash is purely punitive.
- **Fraud slashes** (`slashForFraud`, line 646): **Not yet enabled** —
  guarded by `slashConfig.fraudSlashBps == 0`.

### When it becomes critical
If fraud slashing is enabled in the future without adding a
`toCounterParty` distribution, the victim of proven fraud would lose their
escrow AND receive nothing from the slash proceeds. A `toCounterParty`
tranche should be added to `_distributeSlashedFunds` before fraud slashing
is turned on.

### Action
Add `toCounterParty` tranche to `_distributeSlashedFunds` as a prerequisite
for enabling fraud slashing. The framework scenario S-DR-081 stands as a
pre-validation test for this fix.

---

## Finding 3 — Stake-to-Escrow-Ratio-Unbounded

### Status
**Genuine design gap** — the staking module defines capacity constraints
that are never enforced in the critical assignment path.

### Solidity ground truth
The staking module defines:
- `CAPACITY_MULTIPLIER = 4` and `MAX_ESCROW_PER_L0_CASE = 2000e18`
  (`ResolverStakingModuleV1` lines 96-97)
- `getMaxEscrowPerCase()` (line 852): `maxEscrow = min(bond * 4, 2000e18)`
  — caps the maximum escrow value a resolver can handle based on their
  bonded stake

**But this function is never called in any critical path:**
- `DecentralizedResolutionModule.initializeDispute()` (line 379) checks
  resolver capacity (max concurrent disputes) but **never** checks escrow
  value vs stake
- `BaseEscrow.createEscrow()` → `CreateOps.computeEscrowCreation()` never
  queries stake capacity
- `ResolverStakingModuleV1.onResolverAssigned()` (line 604) locks a minimum
  amount of stake but does not enforce a ratio to the escrow value

A resolver staking 100 USDC **can** be assigned to a 1,000,000 USDC escrow.
The maximum penalty (`MAX_SLASH_PER_OFFENSE = 5000 bps = 50%`) caps at
50 USDC — a rounding error relative to the escrow value, providing no
economic deterrent against malicious resolution.

### Fix
Call `getMaxEscrowPerCase()` in `DecentralizedResolutionModule.initializeDispute()`:
```solidity
uint256 maxEscrow = stakingModule.getMaxEscrowPerCase(resolver);
if (escrowValue > maxEscrow) revert InsufficientResolverStake(...);
```

### Action
This is the highest-priority protocol change. The framework's
`:resolver-stake-proportional` invariant (already added to `invariants.clj`)
flags escrows where stake is insufficient, and can be used to validate the
fix.

---

## Finding 4 — Escalation-Bond-Cost-Griefing

### Status
**False positive** — per-address escalation counting exists in the simulation
(`resolution.clj:572-576`) but its real-world impact depends on the
governance-configurable bond parameters, not a hardcoded default. The
underlying concern (asymmetric cost for honest watchdogs) is a parameter
governance question, not a protocol bug.

---

## Finding 5 — Evidence-Cannot-Prove-Vulnerable-Transition

### Status
**Observability gap** — the simulation evidence artifacts do not surface
economic context (bond amounts, escrow values, claimable balances). This
gap is independent of the protocol and exists purely in the framework.
The `evidence-summary.json` artifact has been extended with economic
context fields.

---

## Ranked Findings (Post-Solidity Assessment)

| Rank | Finding | Status | Risk | Action Required |
|------|---------|--------|------|-----------------|
| 1 | Stake-to-escrow ratio unbounded | **Genuine design gap** | **High** | Protocol fix: enforce `getMaxEscrowPerCase()` in `initializeDispute()` |
| 2 | Slash victim not made whole | **Contingent vulnerability** | Medium (latent) | Add `toCounterParty` before enabling fraud slashing |
| 3 | Evidence context incomplete | Observability gap | Low | Framework improvement (done) |
| — | Challenge bond default | False positive | None | Fix `payoffs.clj` default |
| — | Escalation bond griefing | False positive | None | Parameter governance question |

## Recommended Next Steps

1. **Protocol fix (highest priority):** Call `getMaxEscrowPerCase()` in
   `DecentralizedResolutionModule.initializeDispute()` to enforce stake-
   proportional escrow limits
2. **Latent fix (before fraud slashing enablement):** Add `toCounterParty`
   distribution in `_distributeSlashedFunds()`
3. **Simulation fix:** Remove the arbitrary 100-unit default from
   `calculate-challenge-bond-amount` in `payoffs.clj`
4. **Validate with scenarios:** S-DR-082 (stake-to-escrow) and S-DR-081
   (slash victim repair) should pass after protocol fixes are deployed
