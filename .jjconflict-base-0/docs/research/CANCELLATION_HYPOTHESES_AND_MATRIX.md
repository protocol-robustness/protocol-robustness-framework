# Cancellation Strategy-Profile Matrix and Hypotheses

## Actors and Strategy Sets

### Buyer (Sender)
| Strategy | Action | Description |
|---|---|---|
| `cancel-now` | `sender_cancel` | Immediately set AGREE_TO_CANCEL; if recipient also agrees, escrow refunded |
| `dispute-now` | `raise_dispute` | Raise dispute; enter resolution path (costly, uncertain) |
| `delay` | `wait` | Do nothing; escrow stays PENDING, counterparty may act |
| `no-op` | — | Never act; escrow sits indefinitely (if no auto-cancel configured) |

### Seller (Recipient)
| Strategy | Action | Description |
|---|---|---|
| `cancel-now` | `recipient_cancel` | Immediately set AGREE_TO_CANCEL; if sender also agrees, escrow refunded |
| `dispute-now` | `raise_dispute` | Raise dispute; enter resolution path |
| `delay` | `wait` | Do nothing; escrow stays PENDING |
| `no-op` | — | Never act |

### Resolver
| Strategy | Action | Description |
|---|---|---|
| `release` | `execute_resolution(isRelease=true)` | Rule for buyer; escrow released to seller |
| `refund` | `execute_resolution(isRelease=false)` | Rule for seller; escrow refunded to buyer |
| `escalate` | `escalate_dispute` | Pass decision to higher tier (griefing / cost-escalation) |
| `abstain` | — | Never resolve; dispute times out → auto-cancel refund |
| `no-op` | — | Not assigned to dispute; no action possible |

### Keeper (Auto-Cancel)
| Strategy | Description |
|---|---|
| `execute-timeout` | Fire `auto_cancel_disputed` when `block.timestamp >= dispute_raised + max_dispute_duration` |
| (deterministic — not a strategic agent) |

## Payoff Structure

Cancellation and dispute termination share the same final payout: buyer receives
`amount_after_fee`, seller receives `0`. The difference is in **cost, delay, and risk**:

| Outcome | Buyer Payoff | Seller Payoff | Notes |
|---|---|---|---|
| Mutual cancel | A - F (refund) | 0 | Fast, zero dispute cost, no resolver risk |
| Unilateral cancel (buyer) | A - F | 0 | Same as mutual, no counterparty consent needed |
| Dispute → release | Goods/services | A - F | Seller wins; buyer gets non-monetary value |
| Dispute → refund | A - F | 0 | Same payout as cancel, but with dispute costs |
| Dispute → timeout → auto-cancel | A - F | 0 | Same payout as cancel, but delayed by `max_dispute_duration` |
| Dispute → escalate → release/refund | Variant | Variant | Higher costs from escalation bonds/fees |

Where:
- A = escrow amount
- F = protocol fee (A × fee_bps / 10000)
- C_d = dispute cost (lost time, potential appeals, resolver stake effects)
- C_e = escalation cost (additional bonds)
- ρ = probability that resolver rules for buyer (refund)
  - ρ = 0 → certain release (seller always wins)
  - ρ = 1 → certain refund (buyer always wins)
  - 0 < ρ < 1 → uncertain dispute outcome

## Hypotheses

### H1: Honest mutual cancel dominates frivolous dispute in low-ambiguity cases

**Claim:** When both parties observe unambiguous evidence that the escrow should be
refunded (ρ ≈ 1), the expected utility of mutual cancel strictly exceeds the expected
utility of dispute.

**Rationale:** Cancel yields (A - F) to buyer, 0 to seller — same as dispute refund.
But dispute adds:
- Delay cost (max_dispute_duration + appeal windows)
- Resolver stake risk (resolver may be slashed on timeout, affecting future service)
- Escalation risk (if either party appeals, costs compound)
- Reputation cost (dispute filing may affect buyer's future credibility)

**Formal:**
```
E[U_cancel] = (A - F, 0)
E[U_dispute_refund] = (A - F - C_d, -C_d)
E[U_cancel] > E[U_dispute_refund]  when C_d > 0
```

**Testable via counterfactual:** For scenario S06 (mutual cancel), generate a deviation
where buyer/seller raises dispute instead of canceling. Compute regret.

### H2: Strategic delay-to-timeout is not economically dominant over honest settlement

**Claim:** A party who delays action to force a dispute timeout does not receive a
strictly higher payoff than from honest mutual cancel.

**Rationale:** Timeout leads to the same refund outcome as mutual cancel (buyer gets A-F,
seller gets 0), but:
- Delay costs the counterparty time (opportunity cost of locked funds)
- The delaying party bears no additional cost from the timeout itself
- However, if the counterparty can also act (e.g., raise dispute), delay may trigger
  worse outcomes for the delayer

**Edge case — griefing:** A seller who knows the escrow should be refunded may delay
simply to lock the buyer's funds longer. This is not "economically dominant" in the
sense of higher monetary payoff (same payout), but may be a non-monetary preference.

**Testable via counterfactual:** For scenario S06, generate a deviation where the second
party delays instead of completing the mutual cancel. Compute whether regret ≤ 0.

### H3: Unilateral cancellation policies do not introduce extortion equilibria

**Claim:** When a buyer-only unilateral cancellation strategy is enabled
(`BuyerOnlyCancellationStrategy`), the buyer cannot profitably threaten cancellation
to extract concessions beyond the protocol's baseline.

**Rationale:**
- Unilateral cancel gives buyer A - F (same as mutual cancel or dispute refund)
- The buyer cannot receive MORE than A - F from any protocol outcome
- Therefore, any "concession" the seller could give is bounded by what the seller
  would receive in the release outcome (A - F)
- Seller's threat point: if buyer cancels, seller gets 0
- Seller's alternative: raise dispute (costly, uncertain) or accept cancel
- The only extortion risk is if seller values the escrow outcome at >0 and buyer
  can delay cancel to impose cost

**Testable via scenario:** Create a scenario with `BuyerOnlyCancellationStrategy`,
where buyer sets AGREE_TO_CANCEL, then tests whether the buyer could profitably
delay completing the cancel to extract side payments.

## Strategy-Profile Matrix

### Normal-form game (mutual consent, no strategy enabled)

```
Buyer ↓ / Seller →    cancel-now    dispute-now    delay    no-op
─────────────────────────────────────────────────────────────────────
cancel-now           (A-F, 0)       (pending)      pending   pending
dispute-now          (pending)      (dispute)      dispute   dispute
delay                pending        dispute        pending   pending
no-op                pending        dispute        pending   pending
```

Where:
- **`(A-F, 0)`** = REFUNDED terminal state (mutual cancel completes)
- **`(pending)`** = PENDING state (one party agreed, waiting for counterparty)
- **`(dispute)`** = DISPUTED state (dispute raised, enters resolution game)

### Resolver subgame (once dispute is raised)

```
Resolver →           release        refund         escalate   abstain
────────────────────────────────────────────────────────────────────────
(ρ = probability)    ρ              (1-ρ)          rare       rare
Buyer payoff         goods          A-F            varies     A-F (timeout)
Seller payoff        A-F            0              varies     0
```

## Evidence tiers for each hypothesis

| Hypothesis | Current evidence | Target evidence | Method |
|---|---|---|---|
| H1 | Single-trace proxy (S06 passes) | Multi-trace deviation-tested | Counterfactual: cancel → dispute deviation |
| H2 | None | Single-trace proxy + deviation test | Counterfactual: delay deviation on cancel path |
| H3 | None (no scenario with BuyrOnlyStrategy exists) | Single-trace proxy | Create scenario with unilateral strategy enabled |

## Required deviation bundles

From checklist item 3 (counterfactual deviation bundles):

| Baseline trace | Deviation | Expected result |
|---|---|---|
| S06: sender_cancel → recipient_cancel | sender_cancel → raise_dispute | Buyer should be no better off (dispute cost) |
| S06: sender_cancel → recipient_cancel | recipient_cancel → raise_dispute (if recipient has standing) | Seller should be no better off |
| S06: sender_cancel → recipient_cancel | sender_cancel → delay | Buyer's funds stay locked; no profit |
| S06: sender_cancel → recipient_cancel | recipient_cancel → delay | Seller gains nothing extra |
| S04: dispute → auto-cancel | dispute → sender_cancel (before timeout) | Sender should be no better off (same payout, less delay) |
| S22: cancel → dispute | dispute → cancel (reverse order) | Mutual cancel avoids dispute costs; should be preferred |
