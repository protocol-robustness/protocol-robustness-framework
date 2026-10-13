# Sew Protocol — Public Validation Experience (PVE)
v1.0 — The Canonical Evidence Landing Page

The PVE is the public-facing "Truth Surface" for a specific validation run. It is designed to convert technical simulation results into a high-trust, shareable experience that proves protocol robustness to researchers and the public alike.

> **Implementation status:** PVE is an aspirational design concept. The SPEDS story engine (`resolver-sim.notebook-support.speds.story`) and Clerk notebooks under `notebooks/` provide the underlying evidence rendering, but the full public landing page with run-specific layout, section design, and guided experience described here has not been implemented. See `PROTOCOL_EVIDENCE_DESIGN_SYSTEM.md` for current implementation status.

---

## 1. Information Architecture (Progressive Disclosure)

The page follows a "Scientific Funnel" to avoid overwhelming the 80% non-researcher audience while satisfying the 20% reviewers.

1.  **The Certificate (Hero):** Instant binary signal + Identity metadata.
2.  **The Pulse (High-Level Metrics):** Quantitative depth of the run.
3.  **The Map (Threat Coverage):** Spatial view of protocol robustness.
4.  **The Highlights (Narrative):** Causal stories of key deflections (using PEMS).
5.  **The Lab (Scenario Explorer):** Searchable registry of all 77+ scenarios.
6.  **The Proof (Verification Portal):** Deep technical instructions for replay.

---

## 2. Section Design

### A. The "Certificate of Robustness" (Hero Area)
- **Visual:** A high-contrast "Protocol Seal" in `#7ADDDC`.
- **Primary Text:** `VALIDATION RUN: 2026-05-23-NOMINAL`.
- **Metadata Strip:** `GIT_SHA: AE8F2C1`, `REPLAY_RATE: 100.0%`, `INVARIANTS: 42/42 HOLDING`.
- **Rationale:** Mimics a security audit certificate or a flight-readiness report.

### B. Threat Coverage Heatmap
- **Visual:** A 2D grid mapping **Threat Categories** vs **Severity**.
- **Interaction:** Hovering over a cell reveals the number of scenarios covering that vector.
- **Rationale:** Demonstrates "Scientific Exhaustiveness." It's not just "secure," it's "tested against these 15 specific categories."

### C. The "Deflection Gallery" (Interesting Findings)
- **Visual:** 3x PEMS-style animated loops showing the protocol's response to the most "interesting" or "dangerous" attacks from the run.
- **Rationale:** Provides narrative causality. "See how we stopped a resolver-bribery cartel."

### D. Replay Verification Portal (For Researchers)
- **UI Element:** A dark-terminal block with copy-paste commands.
- **Content:**
  - `git checkout AE8F2C1`
  - `make replay-evidence BUNDLE_ID=20260523`
  - Link to Signed Evidence Bundle (S3/IPFS).
- **Rationale:** Provides the "Proof of Work" that separates Sew from marketing-only projects.

---

## 3. Aesthetic & UI Principles

- **Color Protocol:** Strictly VENS (#7ADDDC, #FF9800, #03DAC6).
- **Typography:**
  - **Headlines:** Clean, wide sans-serif (e.g., Inter Bold).
  - **Data:** JetBrains Mono for all IDs and hashes.
- **Grid:** Subtle 8px underlay throughout the page to imply "Alignment and Order."
- **Social Preview (OG Image):** Automatically generated 1200x630 card showing the Hero Certificate + "Top Deflection" visual.

---

## 4. Mobile-First Considerations

- **Stacking:** Heatmaps collapse into "Category List" with bar indicators.
- **Gesture:** The Deflection Gallery uses a swipe-carousel (optimized for Farcaster habits).
- **Performance:** Animated loops (PEMS) are lazy-loaded to ensure fast "Time to Trust."

---

## 5. Candidate Layouts & Tradeoffs

### Layout 1: "The Long-Form Report"
- **Structure:** Vertical scrolling, text-heavy narrative interleaved with charts.
- **Tradeoff:** High credibility, but low engagement for non-researchers.

### Layout 2: "The Dashboard" (Recommended)
- **Structure:** Modular grid of "Technical Cards" (SPEDS).
- **Tradeoff:** High information density and visual impact. Perfect for screenshots.

### Layout 3: "The Explorer"
- **Structure:** Interactive state-machine diagram is the hero.
- **Tradeoff:** Excellent for "Protocol Nerds," but can be confusing for a general audience.

---

## 6. Social Share Strategy

- **The "Evidence Tweet" Pattern:**
  - *Image:* Frame 3 of the PEMS "Sentinel" Loop.
  - *Copy:* "Sew Protocol S26 Deflection: 1.2M attacks simulated. 100% solvency maintained. See the deterministic proof: [Link]"
- **The "Verification Link":** The URL should be permanent and versioned: `sew.dev/validate/20260523`.
