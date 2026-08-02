# F10 — Cascade Escalation Drain

**Class:** Liveness failure (capacity-limited arbitrator)
**Result:** Attack succeeded · 0 invariant violations
**git:** `31ba27b` (ethereum branch)

---

## Summary

Four buyers simultaneously raise disputes against a single arbitrator capped at two resolutions. The arbitrator processes the first two and stops. Escrows 2 and 3 remain permanently in `disputed` state with no resolution path. No invariant is violated — the arbitrator's capacity limit is not a protocol bug. But two sets of funds are permanently inaccessible without external governance intervention.

---

## Scenario Setup

**Actors:**

| Actor | Role |
|-------|------|
| `buyer-0` through `buyer-3` | Four distinct buyers, each with a separate escrow |
| `0xseller` | Shared counterparty |
| `CapacityLimitedArbitrator` | Arbitrator configured with `capacity=2` |

**Parameters:**

| Parameter | Value |
|-----------|-------|
| Escrows | 4 (distinct) |
| Escrow value | 100 tokens each |
| Arbitrator capacity | 2 resolutions maximum |

**Initial state:** All four escrows funded. `CapacityLimitedArbitrator` is the sole dispute resolver.

---

## Execution Steps

1. All four buyers confirm delivery then raise disputes simultaneously
2. All four escrows enter `disputed` state
3. `CapacityLimitedArbitrator` processes escrow-0: resolved ✓
4. `CapacityLimitedArbitrator` processes escrow-1: resolved ✓
5. Arbitrator reaches `capacity=2`; stops processing
6. Escrows 2 and 3 remain in `disputed` indefinitely
7. Simulation window expires with 2 of 4 disputes unresolved

---

## Observed Outcome

```
disputes_triggered=4
arbitrator_resolutions=2
still_disputed=2
invariant_violations=0
```

50% of funds are permanently locked. The `resolve()` function is technically callable on escrows 2 and 3 — the arbitrator simply refuses to call it after its capacity is reached.

---

## Security Implication

Any protocol with a single-arbitrator bottleneck can be overwhelmed by a coordinated dispute flood at negligible cost. An attacker needs only:
1. Create `N` escrows where `N > arbitrator_capacity`
2. Raise disputes on all simultaneously

The arbitrator processes up to its capacity and stops. Every additional escrow beyond the capacity results in permanent fund lockup with no on-chain signal. The protocol's `resolve()` entry point remains valid and callable — the failure is purely operational capacity exhaustion.

Real-world impact scales with arbitrator centralization. A protocol with one primary arbitrator and no fallback mechanism is fully exposed to this attack.

---

## Why Existing Tools Miss This

**Static analysis:** `resolve()` is callable on all escrows. No function has a vulnerability.

**Fuzz testing:** Tests individual transaction validity. Cannot simulate four concurrent actors raising disputes against a capacity-constrained arbitrator.

**Unit tests:** Test that an arbitrator can resolve a dispute. Never test what happens to escrow N+1 when the arbitrator is already at capacity.

**Manual audit:** The arbitrator's capacity limit is off-chain configuration. There is no Solidity code to audit that would surface this failure.

**Load testing:** Would surface this — but requires modeling concurrent multi-actor intent, not just throughput. Standard load tests don't model adversarial coordination.

---

## Reproducibility

```bash
# Run scenario F10 through replay
bb run:scenario f10-cascade-escalation-drain --run-root /tmp/prf-run

# Package portable evidence
bb evidence:pack /tmp/prf-run
```

Expected output:
```
S33  f10-cascade-escalation-drain  PASS  (0.06s)
1/1 passed · 0 invariant violations
```

[Raw JSON output →](../../../results/evidence/f10-run.json)
