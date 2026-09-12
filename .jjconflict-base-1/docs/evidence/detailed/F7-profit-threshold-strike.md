# F7 — Profit-Threshold Strike

**Class:** Economic failure (rational resolver withdrawal)
**Result:** Attack succeeded · 0 invariant violations
**git:** `31ba27b` (ethereum branch)

---

## Summary

A resolver with a minimum profit threshold refuses to service an escrow where the protocol fee is below its cost floor. The dispute remains permanently open with zero resolutions. No on-chain error is raised. The failure is entirely off-chain: a rational economic actor declines to participate, and the protocol has no mechanism to detect or respond to this.

---

## Scenario Setup

**Actors:**

| Actor | Role |
|-------|------|
| `0xbuyer` | Creates escrow; raises dispute |
| `0xseller` | Contract counterparty |
| `ProfitThresholdResolver` | Resolver with a configurable minimum profit requirement |

**Parameters:**

| Parameter | Value |
|-----------|-------|
| Escrow value | 100 tokens |
| `fee_bps` | 100 (1%) |
| Protocol fee | 1 token |
| `min_profit` (resolver) | 5 tokens |

The protocol fee (1 token) is below the resolver's cost floor (5 tokens). The resolver refuses to act.

**Initial state:** Escrow in `funded` state. `ProfitThresholdResolver` is the authorized resolver.

---

## Execution Steps

1. Buyer creates escrow (100 tokens) with `fee_bps=100`
2. Seller confirms; escrow is `funded`
3. Buyer raises dispute — escrow moves to `disputed`
4. Protocol calls resolver; resolver checks: `fee=1 < min_profit=5`
5. Resolver logs refusal internally — takes **no on-chain action**
6. Simulation window expires; dispute remains open

---

## Observed Outcome

```
resolutions_executed=0
refusals=1
final_state=disputed
invariant_violations=0
```

The escrow is permanently stuck in `disputed`. No revert. No error. The resolver's refusal is invisible to the protocol.

---

## Security Implication

Rational resolvers will not service small escrows at a loss. Any protocol that relies on voluntary resolver participation has a silent liveness failure for any escrow below the resolver's cost floor.

This creates two attack surfaces:

1. **Griefing:** An attacker creates many small disputed escrows to exhaust resolver attention, forcing protocol governance to intervene.
2. **Structural exclusion:** Any legitimate small-value escrow is de facto unresolvable under a rational resolver regime — no attack required.

The problem cannot be solved by the protocol alone: the resolver's economic threshold is off-chain state.

---

## Why Existing Tools Miss This

**Static analysis:** All on-chain functions are callable. There is no reachable code path that causes the failure.

**Fuzz testing:** The `resolve()` function is never called — the failure is a non-action. Fuzzers cannot model agent abstention.

**Unit tests:** Test that `resolve()` works correctly when called. Never test what happens when a rational agent declines to call it.

**Manual audit:** The resolver's cost model is off-chain. No audit of the Solidity contracts can surface a failure that depends on an agent's economic incentive structure.

---

## Reproducibility

```bash
# Run scenario F7 through replay
bb run:scenario f7-profit-threshold-strike --run-root /tmp/prf-run

# Package portable evidence
bb evidence:pack /tmp/prf-run
```

Expected output:
```
S30  f7-profit-threshold-strike  PASS  (0.04s)
1/1 passed · 0 invariant violations
```

[Raw JSON output →](../../../results/evidence/f7-run.json)
