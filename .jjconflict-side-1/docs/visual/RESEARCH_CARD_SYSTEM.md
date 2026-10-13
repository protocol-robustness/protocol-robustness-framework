# Sew Protocol — Research Card System (RCS)
v1.0 — Visual Artifacts for Technical Findings

RCS is a modular design framework for creating compact, shareable "Research Cards" that summarize protocol validation results. Each card is an independent unit of evidence, cryptographically linked to a deterministic simulation trace.

> **Implementation status:** RCS is an aspirational design concept. The SPEDS V-FRAME component (`speds.core/v-frame`) implements the evidence container concept described here, but the card anatomy, layout variants, and research-card-specific features have not been implemented. See `PROTOCOL_EVIDENCE_DESIGN_SYSTEM.md` for current implementation status.

---

## 1. Card Anatomy (Information Hierarchy)

Each RCS card is composed of four distinct horizontal bands designed for high-speed scanning:

### A. The Signal Header (5%)
- **Component:** Semantic Category Label + Scenario ID.
- **Visual:** Monospaced text on a color-coded pill.
- **Example:** `[RESEARCH_DISCOVERY] S26`

### B. The Narrative Hero (65%)
- **Component:** The technical punchline + Visual proof.
- **Narrative:** 1 sentence of technical observation (e.g., "Invariant G04 intercepted a 12% reorg attempt").
- **Visual:** A SPEDS-compliant diagram or a PEMS-style animated loop.
- **Grid:** 8px micro-grid background.

### C. The Context Strip (15%)
- **Component:** Threat Tags + Confidence/Severity Scores.
- **Visual:** Small-text metadata tokens.
- **Example:** `TAG: REORG_RESILIENCE | CONFIDENCE: EXHAUSTIVE [||||]`

### D. The Evidence Footer (15%)
- **Component:** Replay Badge + Link.
- **Visual:** Truncated SHA-256 hash + "Verify Replay" CTA.
- **Example:** `TRACE_ID: 8f2a...1b9c | REPLAY: VERIFIED`

---

## 2. Semantic Categories & Visual Logic

| Category | Primary Token | Palette | Rationale |
| :--- | :--- | :--- | :--- |
| **Discovery** | `[THREAT_IDENTIFIED]` | `#FF9800` (Orange) | An adversarial vector found during search. |
| **Validation** | `[INVARIANT_HOLDING]` | `#03DAC6` (Teal) | A protocol rule proven robust under stress. |
| **Mitigation** | `[DEFENSE_VERIFIED]` | `#7ADDDC` (Cyan) | A code-level guard successfully intercepted an attack. |
| **Analysis** | `[MECHANISM_STABILITY]`| `#004D59` (Dark) | Observation on economic equilibrium or game theory. |

---

## 3. Severity & Confidence Language

To maintain technical credibility, avoid binary "Safe/Unsafe" language. Use the following:

- **Severity (Discovery):** `CRITICAL`, `HIGH`, `MODERATE`, `LOW`.
- **Confidence (Validation):**
  - `FORMAL`: Mathematically proven.
  - `EXHAUSTIVE`: Full Monte Carlo / state-space coverage.
  - `PROBABILISTIC`: Statistical stability verified.
  - `INDICATIVE`: Edge-case observation (requires more search).

---

## 4. Layout Variants

### V1: The "Sentinel" (GIF/Video)
- Optimized for X and Discord.
- Features a PEMS loop in the Hero area.
- High motion on the "Intercept" frame.

### V2: The "Evidence Block" (Static)
- Optimized for technical reports and Farcaster frames.
- Features a side-by-side SPEDS comparison of "Attacker Intent" vs "Protocol Response."

### V3: The "Benchmarker" (Comparison)
- Shows `BEFORE HARDENING` vs `AFTER HARDENING` results.
- Uses the `#EF4444` (Red) to `#03DAC6` (Teal) delta to show improvement.

---

## 5. Narrative Chaining (The "Deep-Dive" Set)

When sharing a complex finding, chain 3 cards in a carousel:
1. **Card 1 (Discovery):** The vulnerability identified during an adversarial sweep.
2. **Card 2 (Mitigation):** The protocol hardening logic implemented to deflect it.
3. **Card 3 (Validation):** The final verification card proving 100% replay match.

---

## 6. Implementation Principles

- **Precision-First:** If a line is drawn, it must represent a real data point from the Clojure trace.
- **No Decoration:** Eliminate drop shadows, rounded corners, or glass effects. Technical credibility comes from "unadorned data."
- **Typography:** Headlines in **Inter Bold**, data in **JetBrains Mono**.
- **Signature:** Every card must have a visible `TRACE_HASH` to act as a citation.

---

## 7. Rationale: Why RCS?

RCS replaces the "Marketing Post" with a "Scientific Post." Instead of asking for trust, Sew Protocol **exports proof** in a format that social platforms can digest. Each card is a "micro-audit" that is verifiable by any researcher with access to the repo.
