(ns resolver-sim.financial.taxonomies
  "General financial lifecycle taxonomies for protocol state machines.

   These are pure data definitions — keyword vectors, ordinal mappings,
   and lifecycle semantics. They make no assumptions about any specific
   protocol's world state shape.

   **Chain finality** — state permanence (blockchain consensus).
   **Financial finality** — obligation permanence (economic outcome stability).
   They are explicitly NOT the same.

   **Loss lifecycle** — a financial loss mode exists when a protocol, escrow,
   module, vault, or settlement path can no longer satisfy the value obligations
   implied by prior commitments. Shortfall does NOT imply realized user loss.

   **Solvency** — can the protocol prove, from verifiable state commitments,
   that assets are sufficient to meet obligations?

   For protocol-specific classifiers that read world state to classify against
   these taxonomies, see the Sew reference implementation:
     resolver-sim.protocols.sew.financial.finality
     resolver-sim.protocols.sew.financial.loss
     resolver-sim.protocols.sew.financial.solvency"

  (:refer-clojure :exclude [phase]))

;; ── Chain finality ────────────────────────────────────────────────────────────

(def chain-phases
  "Ordered phases of blockchain consensus finality."
  [:pending :confirmed :safe :final])

(defn chain-phase-ordinal
  "Numeric ordinal for comparing chain-finality phases."
  [phase]
  (case phase
    :pending 0
    :confirmed 1
    :safe 2
    :final 3))

;; ── Financial finality ────────────────────────────────────────────────────────

(def financial-phases
  "Ordered phases of financial finality.

   Phase definitions:
   :provisional         — outcome not yet determined (e.g. escrow open)
   :challengeable       — resolution recorded but appeal/challenge window open
   :recoverable         — settlement executed but positions still recoverable
                          (yield shortfall recovery, slashing appeal)
   :finalizing          — all gates closing, last claimable amounts settling
   :financially-final   — all economic outcome gates closed

   Note: :provisional precedes :challengeable — they have the same ordinal
   when no resolution has been recorded (e.g. an escrow in pending state)."
  [:provisional :challengeable :recoverable :finalizing :financially-final])

(defn financial-phase-ordinal
  "Numeric ordinal for comparing financial finality phases."
  [phase]
  (case phase
    :provisional 0
    :challengeable 1
    :recoverable 2
    :finalizing 3
    :financially-final 4))

;; ── Loss lifecycle ────────────────────────────────────────────────────────────

(def loss-statuses
  "Ordered lifecycle states for financial loss classification.

   :normal                 — no obligations at risk
   :loss-risk              — active risk, no obligations yet impaired
   :loss-pending-finality  — obligations impaired, finality not yet reached
   :loss-realized          — obligations impaired, financial finality reached
   :loss-irrecoverable     — obligations permanently unmet, no recovery path"
  [:normal :loss-risk :loss-pending-finality :loss-realized :loss-irrecoverable])

(defn loss-status-ordinal
  "Numeric ordinal for comparing loss lifecycle statuses."
  [status]
  (case status
    :normal 0
    :loss-risk 1
    :loss-pending-finality 2
    :loss-realized 3
    :loss-irrecoverable 4))

;; ── Solvency tiers ────────────────────────────────────────────────────────────

(def solvency-tiers
  "Ordered tiers of cryptographic solvency assurance (low to high).

   :insolvent              — formal solvency fails (liabilities > assets)
   :proof-invalid          — cryptographic proof exists but fails validation
   :proof-state-mismatch   — proof exists but references different state
   :unproven               — accounting says solvent, no cryptographic proof
   :solvent                — formal solvency holds from state alone"
  [:insolvent :proof-invalid :proof-state-mismatch :unproven :solvent])

(defn solvency-tier-ordinal
  "Numeric ordinal for comparing solvency tiers."
  [tier]
  (case tier
    :insolvent 0
    :proof-invalid 1
    :proof-state-mismatch 2
    :unproven 3
    :solvent 4))
