# Common Dispute Resolution Standard (CDRS) v0.1

**Status**: Emerging / Experimental (Draft)  
**Objective**: A common trace and event format for adversarial testing and formal verification of decentralized dispute resolution protocols.

CDRS v0.1 defines a minimal, semi-structured language for describing dispute lifecycles, enabling cross-protocol comparison and standardized risk auditing. This repository acts as the first reference implementation for **CDRS-compatible** testing.

---

## 1. Canonical State Buckets
Protocols using the **CDRS draft trace format** should map their internal states to these five canonical buckets for auditability:

1.  **`IDLE`**: Pre-creation or uninitialized.
2.  **`ACTIVE`**: The agreement is live; no dispute exists.
3.  **`CHALLENGED`**: A dispute has been raised; awaiting a decision.
4.  **`RECONCILING`**: A decision has been proposed; window for appeal or challenge is open.
5.  **`SETTLED`**: Terminal state; funds distributed.

---

## 2. Standardized Event Schema
Every step in a **CDRS-shaped** execution trace MUST emit a JSON event with these top-level fields:

*   **`cdrs_version`**: MUST be "0.1".
*   **`event_type`**: The semantic name of the action (e.g., `DISPUTE_RAISED`).
*   **`step_type`**: Either `protocol-transition`, `economic-effect`, or `environmental-change`.
*   **`context_id`**: A unique identifier for the specific dispute/escrow (e.g., `wf0`).
*   **`actor`**: The address or ID of the party performing the action. Can be a human address, a protocol ID, or **`system`** for automated events (e.g., timeouts, oracles).
*   **`state_bucket`**: The canonical state after this step.
*   **`attributes`**: A flexible map of protocol-specific data (e.g., `stake_locked`, `resolution_outcome`).

---

## 3. The Solvency Standard
A **CDRS-compatible** execution trace must demonstrate **Full Economic Reconciliation**. 

**The CDRS-Invariant**:  
`TotalValueIn == TotalValueOut + TotalValueHeld`  
*Must hold at every step.*

---

## 4. Resolution Semantics
To enable standardized analysis, resolutions are categorized in the `final_state`:

### Outcomes:
*   `RELEASE`: Funds sent to the intended recipient.
*   `REFUND`: Funds returned to the original sender.
*   `SLASH`: Stake removed as a penalty.
*   `SPLIT`: Funds distributed between multiple parties.
*   `NO_OP`: No fund movement.

### Finality:
*   `FINAL`: No further appeals possible.
*   `APPEALABLE`: A settlement is proposed but can be challenged.
*   `REVERSED`: A prior decision was overturned by an appeal.
*   `STALLED`: Timeout reached without resolution.

### Integrity:
*   `FULLY_RECONCILED`: All invariants hold; accounting is balanced.
*   `ACCOUNTING_MISMATCH`: Mismatch between held funds and logically active escrows.
*   `MISSING_EFFECTS`: State transitioned without required economic side-effects.
*   `LEAKAGE`: Permanent liquidity loss detected.
