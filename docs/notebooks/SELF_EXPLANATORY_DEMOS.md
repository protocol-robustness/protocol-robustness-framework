# Self-Explanatory Demonstrations, Backed by Clean-Room Machinery

The clean-room principle is approved. This revision changes the unit of abstraction:
from "a clean notebook" to **a self-contained demonstration**.

## The problem

`notebooks/not_admitted.clj` is 2,411 lines, 19 sections, 45 `clerk/html` blocks
(42 the same dark-box style), 72 top-level `def`s, ~21 required namespaces. The old
proposal treated this as a *maintainability* problem: extract machinery, test claims,
reduce boilerplate.

The real problem is a *communication* problem. The notebook is structured around
verification surfaces ("Evidence Chain Ordering", "Grounded Amount", "Researcher
Consensus") — a taxonomy that only makes sense if you already understand the framework.
A visitor must pay a vocabulary tax before learning anything.

> **Target:** a self-explanatory demonstration backed by trustworthy machinery.
> The machinery must be correct and tested; the demonstration must communicate
> without prerequisite knowledge.

These are different requirements and must not be conflated.

## 1. The demo is the primitive

A **demo** is not a notebook and not a step. It is a single, self-contained object:

```
demo
├── scenario              what exists before the intervention
├── expected outcome      the verdict we commit to
├── mutation / action     the one thing that changes
├── observed outcome      the verdict after the change
├── explanation           one sentence, ordinary language
└── evidence              the technical proof (progressively disclosed)
```

A notebook, a static web page, a terminal command, a test, a video transcript, and an
assurance-lab interaction are all **views of the demo** — not different definitions of it.

```clojure
{:demo/id :admission/tampered-amount
 :demo/question "Can an amount be changed after the result was verified?"
 :demo/baseline  {:label "Held amount" :value 4925 :verdict :admitted}
 :demo/action    {:label "Change held amount" :from 4925 :to 5000}
 :demo/outcome   {:verdict :not-admitted}
 :demo/expect    {:baseline :admitted :after-action :not-admitted}
 :demo/explanation
 "The amount changed, so the evidence no longer matches what was verified."
 :demo/evidence  {:verifier-result ... :violations [...] :artifacts [...]}}
```

Code layout follows the demo, not Clerk:

```
resolver_sim/demos/not_admitted/
  scenario.clj     build the before-state (Clerk-free)
  demo.clj         the demo model: question, baseline, action, outcome, explanation
  assertions.clj   deterministic expectations: baseline and after-action verdicts
resolver_sim/notebook_support/
  views.clj        shared renderers for every surface
```

The distinction is intentional: this code exists because *someone needs to understand
something*, not because Clerk needs support.

## 2. One demo proves one idea

The old "step registry" risked reproducing the current notebook in a cleaner
architecture — nineteen clean steps are still nineteen things to understand.

Constraint for visitor-facing demos:

> **One demo = one question, one intervention, one visible consequence.**

Candidate demos for "not admitted":

**Demo A — Tamper with evidence.** *Can evidence be changed after the result was
produced?*
```
Baseline                        ✓ ADMITTED
Change: escrow amount 1000 → 1100
Same check again                ✕ NOT ADMITTED
```
The explanation: the evidence no longer matches what was committed.

**Demo B — Reorder the chain.** *Does the same evidence in a different order mean the
same thing?*
```
A → B → C   ✓ ADMITTED
A → C → B   ✕ NOT ADMITTED
```
Only after a `Why?` click do you mention the ordering commitment.

**Demo C — Remove required evidence.** *Can an incomplete result still pass?*
```
Complete evidence   ✓ ADMITTED
Remove settlement evidence   ✕ NOT ADMITTED
```

Each is understandable in seconds. That beats sections named after verification
surfaces.

## 3. The default view hides almost all framework vocabulary

Primary labels on the first surface should be ordinary language. These are NOT primary
labels:

evidence contract · canonical root · commitment · invariant · admission surface ·
fixed point · grounded amount · lifecycle invariant

They are useful once a technically interested visitor drills in — not before.

The first surface is a story:

```
WHAT WE'RE TESTING
Can this result be changed without detection?

ORIGINAL
✓ Accepted

CHANGE
Amount: £10m → £12m

SAME CHECK AGAIN
✕ Rejected

WHY
The evidence no longer matches what was originally committed.

[ Show technical proof ▸ ]
```

Under "technical proof" you show roots, artifacts, hashes, checks, exact violation
identifiers, provenance — the framework vocabulary lives there, where it earns its
place. Progressive disclosure is more important to the visitor experience than any
code extraction.

## 4. Surfaces, ranked for the visitor

The terminal walkthrough stays — it is useful for developers, CI, and live technical
walkthroughs — but it is not the headline.

```
1. Immediate interactive/static demonstration   (the punchline in 10–20 s)
2. Leave-behind page                           (static export / transcript)
3. Technical evidence                          (progressive disclosure)
4. CLI / developer interface                   (bb demo:..., CI, JSON)
```

A visitor must not need a terminal to get the point. The ideal live demo:

```
┌─────────────────────────────────────────────────────┐
│ Can a verified result be changed?                   │
│                                                     │
│ Original result                                     │
│ £4,925 held                                  ✓      │
│                                                     │
│ [ Change amount to £5,000 ]                         │
│                                                     │
│ Verification                                        │
│ NOT ADMITTED                                 ✕      │
│                                                     │
│ The amount changed, so the evidence no longer       │
│ matches the result that was verified.               │
│                                                     │
│ Technical proof ▸                                   │
└─────────────────────────────────────────────────────┘
```

This communicates more value in fifteen seconds than a technically excellent notebook
walkthrough does in five minutes.

## 5. Trustworthy machinery (kept from the original proposal)

The tested computational core remains the right direction.

- **"Narrative may not silently drift away from executable truth."** Every displayed
  verdict is recomputable and asserted; a regression that flips a verdict is a failing
  test, not a silent lie.
- `:demo/expect` (`{:baseline :admitted :after-action :not-admitted}`) replaces the
  step-level `:step/assert`. Assertions live on the demo outcome, not on a rendering
  step — the deeper verifier output sits underneath and is checked separately.
- Determinism: demos re-run identically; a diff against a pinned transcript catches
  drift.
- Existing gates extend: `bb test:notebooks` proves notebooks compile; demo assertions
  prove the demonstration tells the truth. `--json` output feeds CI.

## 6. Revised migration order

Phase 0 must decide what the demonstration *is*, before touching machinery.

1. **Choose the three clearest demonstrations** (e.g., A/B/C above). Ignore notebook
   section boundaries entirely.
2. **Build the tiny demo model and its tested computation** for those cases. No broad
   extraction; only what the demos need.
3. **Build one extremely simple visitor-facing renderer.** Question → baseline →
   action → verdict → why → technical details.
4. **Prove the pattern on an outsider.** If someone needs an explanation of the
   framework first, simplify again.
5. **Then extract / migrate the rest of `not_admitted.clj`** — at this point the
   clean-room architecture serves a proven presentation model instead of preserving an
   old notebook structure.

This also reduces migration risk: we don't spend effort cleanly extracting six sections
that may ultimately disappear from the visitor-facing material.

## 7. Acceptance criteria

Engineering criteria stay (tested core, deterministic assertions, CI integration, no
duplicated rendering) but they are no longer the headline. The headline bar is:

- A new visitor can explain what changed and why it was rejected after seeing the demo
  once.
- The first meaningful verdict appears without scrolling through architecture or
  definitions.
- No knowledge of PRF-specific vocabulary is required to understand the initial
  demonstration.
- Each visitor-facing demo illustrates one property only.
- Every technical term on the first screen is either unnecessary or explained in-place.
- A complete demo runs in under 60 seconds; the main before/after moment takes 10–20
  seconds.
- Technical provenance is available but progressively disclosed, not required reading.
- The same underlying computation powers the visual demo, the automated assertion, and
  the technical inspection.

## 8. First implementation slice

Not the Phase 1 extraction of all chain/admission sections. Instead:

> **One complete "valid → single mutation → not admitted" demonstration.** Ordinary
> language throughout, no PRF terminology required, backed by the real verifier and a
> deterministic assertion.

If that is compelling, generalize the clean-room architecture around it. If it is not,
we learn that before investing in the broader extraction.

### Status: Demo A (tamper with the amount) and Demo B (reorder the chain) are built

The demos are implemented under `resolver-sim.demos`, each self-contained:

| File | Role |
|------|------|
| `demos/not_admitted/{scenario,demo,assertions,cli}.clj` | Demo A: one escrow deposit, evidence derived from the real ledger; tampering with the recorded amount makes exactly one check fail |
| `demos/reorder_chain/{scenario,demo,assertions,cli}.clj` | Demo B: three evidence items in a committed order; reordering makes the chain reject them |
| `notebook_support/demo_views.clj` | shared visitor-facing renderers (hiccup + plain text), Clerk-free |
| `notebooks/demo_not_admitted.clj`, `notebooks/demo_reorder_chain.clj` | the Clerk pages |
| `test/resolver_sim/demos/**` | tests pinning both outcomes (14 tests / 40 assertions) |

Surfaces, all driven by the one computation each demo's `demo/run`:

- `bb demo:not-admitted`, `bb demo:reorder-chain` — the terminal walkthroughs (`--json`, `--check`)
- `bb demo:test` — both assertion suites
- the two Clerk pages

Both demos run the real verifier on the identical input shape before and after the
intervention, so the narrative cannot silently drift from the executable truth.
