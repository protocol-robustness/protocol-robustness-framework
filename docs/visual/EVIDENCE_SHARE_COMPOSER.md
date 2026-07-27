# Sew Protocol — Evidence Share Composer (ESC)
v1.0 — Scientific Communication Workflow

The ESC is a specialized workflow for protocol researchers to transform signed simulation evidence into narrative-driven public artifacts. It prioritizes researcher control and evidence integrity, ensuring the bridge from lab to public is technically verifiable.

> **Implementation status:** ESC is an aspirational design concept. The SPEDS story engine (`resolver-sim.notebook-support.speds.story`) and findings/issues tooling (`speds.findings`, `speds.issues`) support basic narrative generation from artifacts, but the auto-layout, provenance binding, and publication workflow described here have not been implemented. See `PROTOCOL_EVIDENCE_DESIGN_SYSTEM.md` for current implementation status.

---

## 1. The UX Flow: "Lab to Public"

### Step 1: Discover (Intelligence Extraction)
- **Action:** Researcher loads a locally-signed evidence bundle.
- **System Task:** Heuristics scan the run for "Anomalous Interest":
  - **Invariant Near-Misses:** Cases where state came within 5% of a boundary.
  - **Escalation Depth:** Traces that reached Tier 2 or Kleros.
  - **Adversarial Novelty:** First-time coverage of a specific threat tag.
- **Output:** A ranked list of "Candidate Stories."

### Step 2: Compose (Narrative Selection)
- **Action:** Researcher selects a Candidate Story (e.g., "S26: Reorg Deflection").
- **Action:** Researcher chooses a "Narrative Template" (from the Farcaster Specification):
  - *The Deflection Arc*
  - *The Economic Stress Arc*
  - *The Liveness Gauntlet*
- **System Task:** Auto-populates the 4-frame text fields with technical metadata (Git SHA, Block ID).

### Step 3: Generate (Visual Engine)
- **Action:** System renders assets based on SPEDS/PEMS primitives:
  - **GIFs:** Causal loops of the specific trace interception.
  - **Social Cards:** High-res PNGs of the Mission Control hero.
  - **Farcaster Frames:** Swipeable set with interactive "Replay" links.
- **Output:** A "Share Preview" gallery.

### Step 4: Seal & Publish (Provenance Binding)
- **Action:** Researcher reviews and approves the narrative.
- **System Task:** The "Share Bundle" is cryptographically hashed and linked to the original signed simulation bundle.
- **Action:** "Publish to PVE" (Public Validation Experience).

---

## 4. Narrative Intelligence (AI-Assisted)

To lower the burden on researchers, the ESC includes a "Narrative Assistant":
- **Finding Extraction:** "AI detected that in Scenario S42, the Resolver Cartel was slashed 14 blocks earlier than expected due to the new liquidity-guard."
- **Caption Generation:** Suggests scientific, high-signal captions:
  - *Draft:* "Invariant G04 (Solvency) held under 12% drift."
- **Trace Selection:** Automatically crops the 2D state-machine view to the most active "Conflict Zone" in the trace.

---

## 5. Auto-Layout Logic

The system follows a "Density Responsive" logic:
- **Low Density (Mobile):** Primary Flow (V-FLO) + Result (V-INV).
- **High Density (Desktop/Notebook):** Full Timeline (V-TIM) + Metadata (V-RPY) + Multiple Actors (V-ACT).

---

## 6. Provenance & Integrity

Every generated asset includes a "Signature Anchor":
- **The Anchor:** A small QR code or 12-char hash (`8f2a...1b9c`) in the corner.
- **The Link:** Clicking the asset on the PVE page opens the "Raw Evidence Drawer" showing the exact Clojure trace and the cryptographic signature of the researcher.

---

## 7. Interaction Model: "The Scientific Peer-Review"

The ESC is not just about posting; it's about **Proof**.
- **The "Verify" CTA:** Every Farcaster frame has a `[Verify Replay]` button that deep-links into the `notebooks/workbench_v2.clj` view for that specific scenario.
- **Side-by-Side Benchmarks:** System generates "Comparison Cards" showing `Previous Run Performance` vs `Current Run Performance`.

---

## 8. Summary of Workflow Rationale

| **Feature** | **Dashboard Export (Standard)** | **ESC Workflow (Scientific)** |
| :--- | :--- | :--- |
| **Goal** | Export a picture of a chart. | Communicate a causal protocol defense. |
| **Trust** | Visual only. | Cryptographically linked to simulation code. |
| **Narrative** | "Everything is green." | "They tried to break X, but Y prevented it." |
| **Control** | Static export. | Iterative narrative refinement by researcher. |
