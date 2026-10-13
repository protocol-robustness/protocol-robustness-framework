# Sew Protocol — Evidence Motion System (PEMS)
v1.0 — Kinetic Proof of Robustness

PEMS is a motion design framework for generating short-form animated loops from protocol simulation traces. It is designed to maximize information density while maintaining the technical credibility of deterministic evidence.

> **Implementation status:** PEMS is an aspirational design concept. The static SPEDS primitives it builds upon (V-RES, V-ACT, V-FLO) are implemented in `resolver-sim.notebook-support.speds.core`, but the motion/animation layer described here has not been implemented. See `PROTOCOL_EVIDENCE_DESIGN_SYSTEM.md` for current implementation status.

---

## 1. Core Motion Principles

### A. Causal Timing (Event-Led)
- **Do not use linear easing.** Use "Impact Easing" (sudden stops and sharp starts).
- **The "Intercept Freeze":** When a protocol guard blocks an attack, freeze the entire frame for 500ms to allow the viewer to register the "Why."

### B. Logical Continuity
- Elements must never "pop" into existence. They must emerge from an actor node or a timeline transition.
- **Trace Persistence:** When an adversarial path is rejected, it should leave a faint "ghost" line in Orange to show the attempted trajectory.

### C. Data Pacing (The 3-8s Rule)
- **Phase 1 (The Normal):** 1s of baseline protocol activity (Teal).
- **Phase 2 (The Incident):** 1.5s of adversarial injection (Orange).
- **Phase 3 (The Intercept):** 0.5s of guard activation (White-Teal Flash).
- **Phase 4 (The Verification):** 2s of invariant check and deterministic hash generation.

---

## 2. Loop Structures

### L1: The "Sentinel" Loop (Attack Deflection)
- **Pattern:** Baseline → Attack → Intercept → Finalize → Loop.
- **Narrative:** "The shield that never sleeps."
- **Focus:** The collision between the Orange Attack Overlay (V-ATK) and the Teal Response Marker (V-RES).

### L2: The "Ladder" Loop (Escalation)
- **Pattern:** Dispute → L0 Fail → L1 Escalate → Kleros Resolve.
- **Narrative:** "Deterministic hierarchy."
- **Focus:** The step-up motion of the Escalation Timeline (V-TIM).

### L3: The "Solvency Pulse" (Economic Safety)
- **Pattern:** Yield Accrual → Volatility Shake → Withdrawal Finalization.
- **Narrative:** "Resilience under pressure."
- **Focus:** The "Pulse" of the Flow Lines (V-FLO) and the persistent "OK" of the Invariant Marker (V-INV).

---

## 3. Scene Sequencing & "Camera" Logic

### The "Macro-to-Micro" Zoom
1. **Start:** Macro view of the full Protocol State Machine.
2. **Zoom:** Snap-zoom to the specific Escrow where an attack is detected.
3. **Action:** Visualize the interception.
4. **Pull-back:** Return to macro view to show that the *rest* of the protocol remained isolated and safe.

### The "Scan-line" Pan
- A vertical "Verification Beam" moves across the frame, turning unverified (gray) elements into verified (teal) ones, ending with the Replay Badge (V-RPY) flickering into life.

---

## 4. Storyboard Example: "Scenario S26 — The Sandwich Intercept"

1.  **[0.0s - 1.0s]:** Two Teal Flow Lines (`Principal` + `Yield`) moving smoothly into a `PENDING` state.
2.  **[1.0s - 2.5s]:** An Orange "Hazard" Overlay (V-ATK) pulses aggressively over the `Execute` transition. Text: `MALICIOUS REORG DETECTED`.
3.  **[2.5s - 3.0s]:** A thick Teal "Shield" wall (V-RES) slams down. The Orange flow shatters into particles. **FRAME FREEZE.**
4.  **[3.0s - 5.0s]:** An `INV-SOLV: OK` badge appears. A SHA-256 hash scrolls at the bottom.
5.  **[5.0s]:** Smooth wipe back to [0.0s].

---

## 5. Visual Signature (Recognizability)

To ensure the loop is instantly recognized as "Sew Protocol Evidence":
- **The "CRT" Vignette:** Subtle scanlines and slight corner darkening to imply a laboratory monitor.
- **Technical Overlays:** Persistent corner text showing `SIM_MODE: ADVERSARIAL` and `ENGINE: CLOJURE-gRPC`.
- **The Palette:** Exclusively utilize the VENS hex codes (#7ADDDC, #FF9800, #03DAC6).

---

## 6. Rendering Strategy

- **Format:** MP4 (for X/Farcaster) or High-quality GIF (for Discord/Telegram).
- **Resolution:** 1080x1080 (1:1) for social feeds or 1920x1080 for LinkedIn.
- **FPS:** 30fps (Technical standard) or 60fps (for "Ultra-Smooth" proof-of-work).
- **Bitrate:** High enough to prevent color banding in the deep navy gradients.

---

## 7. Attention Hierarchy (Motion-First)

1.  **Movement:** The viewer's eye follows the fastest-moving element (usually the Attack or the Intercept).
2.  **Color Shift:** Rapid change from Teal to Orange triggers an "Alert" response.
3.  **Stability:** The stationary Invariant Badge provides the "Safety" anchor.
