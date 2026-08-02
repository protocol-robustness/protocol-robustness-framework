# F3 — Governance Sandwich

**Class:** Governance failure
**Result:** Attack succeeded · 0 invariant violations
**git:** `31ba27b` (ethereum branch)

---

## Summary

A governance actor with legitimate authority rotates the authorized resolver mid-dispute, replacing the honest resolver with a malicious one. The malicious resolver then resolves the in-flight dispute in the attacker's favour. Every individual transaction — dispute creation, governance rotation, and resolution — is fully authorized by the protocol. The attack is undetectable from per-function analysis.

---

## Scenario Setup

**Actors:**

| Actor | Role |
|-------|------|
| `0xbuyer` | Honest buyer who raises the dispute |
| `0xseller` | Contract counterparty |
| `0xhonest-resolver` | Authorized resolver at dispute creation |
| `0xmalicious` | Resolver installed by governance after dispute opens |
| `governance-agent` | Holds authority to rotate resolvers; queues rotation after 2-tick timelock |

**Parameters:**

| Parameter | Value |
|-----------|-------|
| Escrow value | 500 tokens |
| Timelock duration | 2 simulation ticks |

**Initial state:** Escrow in `funded` state. Honest resolver authorized.

---

## Execution Steps

1. Buyer creates escrow (500 tokens); seller confirms delivery
2. Buyer disputes delivery — escrow moves to `disputed`; `0xhonest-resolver` is the authorized resolver
3. Governance agent queues a resolver rotation (2-tick timelock)
4. Timelock expires — `0xmalicious` becomes the authorized resolver mid-dispute
5. Malicious resolver submits a resolution in the seller's favour
6. Resolution executes; funds released to seller

---

## Observed Outcome

```
rotations=1
malicious_resolutions_succeeded=1
final_state=released
invariant_violations=0
```

The buyer raised a dispute with `0xhonest-resolver` as the adjudicator. By the time resolution executed, the protocol's authorized resolver was `0xmalicious`. The outcome was changed via a governance action the buyer had no visibility into at contract creation.

---

## Security Implication

Protocols that allow mid-dispute resolver rotation give governance the power to retroactively alter dispute outcomes — a critical trust assumption violation for any escrow system.

The attack does not require breaking any access control. It requires:
1. Control of governance (or collusion with a governance key holder)
2. Timing the rotation within an active dispute window

This is a governance privilege escalation that is structurally indistinguishable from a legitimate resolver upgrade.

---

## Why Existing Tools Miss This

**Static analysis (Slither, Mythril):** The rotation function is correctly access-controlled and individually valid. There is no single function with a vulnerability.

**Fuzz testing (Echidna, Foundry):** Generates random inputs to single entry points. Cannot model the intent-ordered sequence: open dispute → queue rotation → rotation fires → malicious resolver resolves.

**Unit tests:** Test governance rotation or dispute resolution in isolation. The interaction between these two valid state transitions is never composed in a single test.

**Manual audit:** Per-function review confirms authorization is correct at each step. The attack is an emergent property of the interaction — invisible to any analysis shorter than a full scenario simulation.

---

## Reproducibility

```bash
# Run scenario F3 through replay
bb run:scenario f3-governance-sandwich --run-root /tmp/prf-run

# Package portable evidence
bb evidence:pack /tmp/prf-run
```

Expected output:
```
S26  f3-governance-sandwich  PASS  (0.05s)
1/1 passed · 0 invariant violations
```

[Raw JSON output →](../../../results/evidence/f3-run.json)
