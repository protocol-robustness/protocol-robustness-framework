# Sew Protocol — Farcaster "Validation Story" Specification
v1.0 — Reusable 4-Frame Narrative Format

This specification defines a mobile-first, high-density evidence format for Farcaster (and other swipeable social platforms). It transforms complex protocol simulations into a "Technical Graphic Novel" that builds trust through deterministic proof.

---

## 1. Design Rationale
- **Trust via Transparency:** Use technical UI elements (monospaced text, terminal borders) to signal "this is code/math" rather than "this is a slide."
- **Standalone Value:** Each frame must answer "What happened?" even if the user never swipes.
- **The "Screenshot Test":** Every frame should look like a mission control monitor from an elite lab.

---

## 2. Canonical Structure: "The Deflection Arc"
Ideal for demonstrating resistance to specific exploits (e.g., S26 Governance Sandwich).

| Frame | Category | Visual Focus | Narrative Goal |
| :--- | :--- | :--- | :--- |
| **1** | **The Threat** | Large orange alert ID + Attack Schematic. | Establish the high stakes: "Scenario S26: 100M Liquid Reorg." |
| **2** | **The Breach Attempt** | Adversarial event timeline (Orange pulse). | Show the attacker's move: "Malicious injection at block 14.2M." |
| **3** | **The Mitigation** | State-machine guard triggering (Teal shield). | Prove the defense: "Invariant `G04` triggered. Extraction rejected." |
| **4** | **The Proof** | Deterministic hash + Final balance sheet. | Close with finality: "100.0% Solvency. Replay verified." |

---

## 3. Alternate Structures

### A. The "Economic Stress" Arc (Yield/Incentives)
1. **Scenario:** "10-Year High-Decay Modeling."
2. **Pressure:** Sankey flow showing extreme yield volatility.
3. **Response:** Bond-slashing heatmap showing resolver alignment.
4. **Equilibrium:** Regression curve proving subgame-perfect stability.

### B. The "Liveness Gauntlet" Arc (Governance/Timing)
1. **Scenario:** "48-Hour L1 Gas Spike (500 Gwei)."
2. **Pressure:** Timing diagram showing t-1ms appeal race conditions.
3. **Response:** Replay trace showing Kleros backstop fail-safe activation.
4. **Result:** Immutable snapshot verification for in-flight escrows.

---

## 4. Layout Templates (Wireframes)

### Template: "The Monitor"
- **Header (Top 10%):** Monospaced `PROTOCOL_STATUS: NOMINAL` strip.
- **Center (60%):** The primary visualization (Heatmap, Timeline, or Flow).
- **Metadata (Left Sidebar 20%):** Small-text IDs, Git SHA, Block height.
- **Narrative (Bottom 10%):** 1-sentence plain-English punchline.

### Template: "The Evidence Bundle"
- **Left (50%):** High-contrast "ATTACK" vs "DEFENSE" side-by-side.
- **Right Top (25%):** Deterministic Replay hash.
- **Right Bottom (25%):** Invariant Status: `PASSED` in Material Teal.

---

## 5. Visual Density & Readability

### Mobile Guidance
- **Max 15 words per frame.**
- **Minimum 18pt font** for narrative punchlines.
- **Primary Color (#7ADDDC)** for lines and structural elements.
- **Material Orange (#FF9800)** for the "Evil" actor/action.

### Density Strategy
- Use **Micro-UI:** Small status dots, thin borders, and block-stamps to imply a deeper system exists behind the frame. This creates curiosity to click the "Full Evidence Page."

---

## 6. Interaction & Motion

### Transition Recommendations
- **Slide-in (Right to Left):** Standard swipe.
- **The "Flash-Cut":** On the transition from Frame 2 (Attack) to Frame 3 (Defense), use a brief white-teal flash to signal the protocol's instantaneous response.

### Animation (If Video/GIF)
- **Pulse:** Subtle 1Hz pulse on active orange attack vectors.
- **Terminal Typing:** Animate the "Evidence Hash" being computed on Frame 4.

---

## 7. Information Hierarchy
1. **The Hero Signal:** (e.g., "ATTACK DEFLECTED") - Instant recognition.
2. **The Visual Proof:** (The chart/trace) - Technical evidence.
3. **The Narrative Link:** (The punchline) - The "So what?"
4. **The Verification Data:** (Hash/Git SHA) - Credibility for researchers.

---

## 8. Summary of Framing Errors

| **BAD (Marketing)** | **GOOD (Evidence)** |
| :--- | :--- |
| "Our protocol is unhackable." | "G04 Invariant held during 12% drift simulation." |
| Pie chart of "Total Volume." | Sankey of "Yield -> Claimable" safety buffers. |
| Generic "Security" icon. | Snippet of the actual Solidity guard being triggered. |
| "Join us on Discord!" | "View full deterministic replay bundle [Link]." |
