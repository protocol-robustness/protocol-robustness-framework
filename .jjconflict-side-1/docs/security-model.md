# Security Model

## What This Tests

The simulator tests **emergent failures** — failures that arise from the interaction of multiple valid operations, not from any single buggy function.

This is the gap between existing tools and real-world protocol failures:

| Tool | What it catches | What it misses |
|------|----------------|----------------|
| Slither, Mythril | Individual function vulnerabilities | Multi-actor interaction bugs |
| Echidna, Foundry fuzz | Single-function invariant violations | Time-ordered multi-step attacks |
| Unit tests | Specified happy paths | Unspecified actor orderings |
| **This simulator** | Multi-actor, time-ordered emergent failures | — |

---

## The Three Failure Classes

### 1. Liveness Failures

The protocol becomes unable to resolve disputes within the time window.

**Root causes:**
- Capacity exhaustion (resolver throughput < dispute rate)
- Rational resolver withdrawal (fee < resolver cost floor)
- Escalation cascade (disputes re-enter the queue at higher levels)

**Consequences:**
- Funds locked in `disputed` state indefinitely
- No on-chain error raised (the protocol is not broken — it is overloaded)
- Users have no recourse without governance intervention

**Scenarios:** F1 (liveness extraction), F7 (profit-threshold strike), F10 (cascade escalation drain)

---

### 2. Economic Failures

The protocol incentive structure can be exploited for financial gain or to impose costs on other parties.

**Root causes:**
- Missing escalation bonds (escalation is free, resolution is not)
- Fee amplification through multi-level escalation
- Cartel control of escalation tiers

**Consequences:**
- Honest parties forced to absorb multi-level resolution costs
- Rational parties withdraw from the protocol
- Escalation mechanism provides false safety guarantees

**Scenarios:** F4 (escalation loop amplification), F6 (resolver cartel), F8 (appeal fee amplification), F9 (sub-threshold misresolution)

---

### 3. Governance Failures

A governance actor with legitimate authority uses that authority in a sequence that produces an outcome no individual party could have consented to.

**Root causes:**
- Mid-dispute resolver rotation (governance changes the resolver after a dispute opens)
- Resolver identity not snapshotted at dispute open time

**Consequences:**
- Dispute outcomes retroactively altered
- Trust assumption violated: parties accepted a specific resolver at contract creation

**Scenarios:** F3 (governance sandwich)

---

## What Is Not Tested

This simulator does **not** test:

- **Reentrancy** — tested by Foundry/Echidna; not a state-machine-level concern
- **Integer overflow/underflow** — tested by static analysis
- **Front-running of individual transactions** (except F2, which specifically models appeal window races)
- **Cross-contract dependencies** — out of scope for this model
- **Flash loan attacks** — not yet modelled (planned)

---

## Invariant Coverage

31 invariants are checked after every state transition. A violation halts the scenario immediately:

| Category | Invariants |
|----------|-----------|
| **Solvency** | Contract balance ≥ sum of all escrow amounts; no negative balances |
| **State integrity** | No double-finalization; all state transitions follow the valid FSM |
| **Authorization** | Only authorized resolvers can resolve; authority unchanged after resolution |
| **Accounting** | Fee deductions correct; refund amounts correct |
| **Escalation** | Escalation level bounded; pending settlement cleared on escalation |
| **Concurrency** | agree_to_cancel not set on disputed escrow |

The invariants are implemented in `src/resolver_sim/protocols/sew/invariants.clj`.

---

## What a Passing Run Means

`33/33 scenarios passed` means:

1. All 23 protocol correctness scenarios behave as specified
2. All 10 Ethereum failure mode scenarios were **reproduced** (the attack works as described) **without triggering any protocol invariant violation** — demonstrating that the failures are structural (not bugs) and that the protocol correctly handles each step individually

A scenario **failing** would mean either:
- A protocol correctness scenario produced an unexpected outcome (regression)
- An F-scenario invariant violation — the attack corrupted state in a way the protocol should prevent

---

## Why Static Analysis Cannot Find These Failures

The F-scenarios all share a common structure:

1. Each individual transaction in the attack is **valid and authorized**
2. The failure emerges from the **sequence and timing** of those transactions
3. The failure requires **at least two distinct actors** with coordinated intent
4. Some failures require **modelling time** (deadlines, block ordering, windows)

Static analysis operates on individual functions. It cannot model:
- Agent intent across transactions
- Ordering of concurrent operations
- Rational economic behaviour (e.g., resolver refusing below-cost-floor disputes)
- Capacity constraints across concurrent sessions

This is not a limitation of any specific tool — it is a fundamental limitation of per-function analysis applied to multi-actor protocols.
