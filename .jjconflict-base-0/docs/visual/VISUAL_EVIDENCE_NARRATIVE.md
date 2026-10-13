# Sew Protocol — Visual Evidence Narrative System (VENS)
v1.0 — "Mission Control" Specification

This document defines the visual and narrative framework for presenting protocol robustness, adversarial simulations, and deterministic evidence. It is designed to bridge the gap between deep protocol engineering and high-signal public communication.

> **Implementation status:** The SPEDS primitives referenced in this document (V-ACT, V-FLO, V-INV, V-RES, V-RPY, V-FRAME) are implemented in `resolver-sim.notebook-support.speds.core`. The VENS-specific concepts (Event Pulse, Incentive Funnel, Boundary Stress) are aspirational design concepts not yet reflected in code. See `PROTOCOL_EVIDENCE_DESIGN_SYSTEM.md` for current implementation status.

---

## 1. Visual Design Principles

### A. Data-Ink Maximization (Tufte-Aligned)
- **Eliminate Decoration:** No rounded corners on internal data panels, no drop shadows, no "glassmorphism" for the sake of it.
- **Micro-Grids:** Use subtle 4px or 8px grid backgrounds to imply technical precision and alignment.
- **Direct Labeling:** Avoid legends. Place labels directly on the event line or transition curve they describe.

### B. The "Observatory" Aesthetic
- **High Information Density:** Prefer many small, coordinated views over single large charts.
- **Terminal Typography:** Use monospaced fonts for IDs, hashes, and state labels. Use high-legibility sans-serif for narrative.
- **Layered Reality:** Use "overlays" (borders and thin lines) to show constraints (e.g., a thin red line representing an invariant boundary).

### C. Narrative Causality
- **Don't just show state; show the *response*:** Every visualization of an attack must show the "Protocol Counter-Measure" (e.g., the slash, the pause, the rejection).
- **Time-Series Priority:** Because protocols are state machines, the "Timeline" is the primary narrative spine.

---

## 2. Color Usage Guidance

Leveraging the provided protocol palette to communicate trust and technical depth.

| Role | Color | Hex | Usage Context |
| :--- | :--- | :--- | :--- |
| **Primary System** | Light Cyan/Teal | `#7ADDDC` | Canonical state, honest resolvers, active transfers. |
| **Success/Refund** | Material Teal | `#03DAC6` | Successful refunds, honest finalization, invariant holds. |
| **Structural/Deep** | Dark Teal | `#004D59` | Borders, secondary labels, "stable" background layers. |
| **Adversarial/Alert** | Material Orange | `#FF9800` | Attack initiation, cancel requests, disputed state, caution. |
| **Failure/Violation** | Emergency Red | `#EF4444` | Invariant breach (rare), hard gate failure. |
| **Background** | Midnight Slate | `#020617` | The base canvas for high-contrast viewing. |
| **Surface** | Deep Navy | `#0F172A` | Internal card/panel backgrounds. |

---

## 3. Reusable Visual Primitives

### P1: The "Event Pulse" (Timeline)
- A horizontal line with tick marks.
- **Normal tick:** Small teal dot.
- **Adversarial Tick:** Large orange triangle.
- **Mitigation Tick:** Teal square with a "Shield" icon.

### P2: The "Incentive Funnel" (Sankey/Flow)
- Visualizing fund movement.
- **Width** = Principal volume.
- **Branching** = Dispute splits (Resolution % vs Slash %).
- Use `#004D59` for the main flow, `#FF9800` for the "Adversarial Pressure" branch.

### P3: The "Boundary Stress" (Range Frame)
- A visualization of a constraint (e.g., the 24h appeal window).
- Show the "Current Time" cursor.
- Highlight the $t-1$ and $t+1$ zones in `#03DAC6` and `#FF9800` respectively.

---

## 4. Narrative Structure (The "Evidence Arc")

To build trust, evidence should be presented in this order:

1.  **The Threat Model:** "We assumed an L1-reorg attack targeting the appeal window." (Highlight the orange risk).
2.  **The Adversarial Trace:** "The attacker attempted to inject a resolution at block 14.2M." (Show the orange event pulse).
3.  **The Protocol Response:** "The state-machine guard at `Escrow.sol:412` triggered." (Show the teal mitigation shield).
4.  **The Invariant Proof:** "Final balance sheet check: Solvency = 100.00%." (Show the Material Teal verification badge).

---

## 5. Emotional Pacing

- **Start with Tension:** Large numbers of "Simulation Cycles" and "Threat Vectors" to show the scale of the challenge.
- **Drill into Crisis:** Highlight a specific scenario (e.g., S26) where things looked "bad" (orange signals).
- **Resolve with Rigor:** Conclude with the deterministic hash and the "Invariant Verification: GREEN."

---

## 6. Social-Native Storytelling ("Screenshot Rules")

- **The "Big Text" Rule:** For social media, the most important metric (e.g., "1.2M ATTACKS DEFLECTED") must be 24pt+ and center-screen.
- **The "Side-by-Side":** Show the *Attacker's Intent* vs the *Protocol Result*.
- **Citations:** Every screenshot should have a small "Deterministic Bundle: [Hash]" footer to prove it's not a mock-up.

---

## 7. Examples: Good vs. Bad Framing

| Concept | **BAD (Analytics Panel)** | **GOOD (Evidence Narrative)** |
| :--- | :--- | :--- |
| **Disputes** | Pie chart of "Win/Loss" | Waterfall of "Escalation Layers" until Kleros. |
| **Security** | "Status: Secure" | "14/14 Invariants Held under 12% MEV Drift." |
| **Yield** | Sparkline of APR | Flow diagram showing "Yield Accrual -> Claimable" safety. |
| **Attacks** | List of "Prevented Hacks" | Replay trace of a "Governance Sandwich" attempt. |

---

## 8. Implementation Strategy (Clerk)

- Use `clerk/vl` (Vega-Lite) for high-density heatmaps using the Teal/Orange palette.
- Use `clerk/html` for custom "Mission Control" status strips.
- Use `clerk/sync` to allow users to "Scrub" the timeline and see state transitions live.
