# GAP-F Decision — Future of the Party-Cancellation Contract Layer (Surface B)

Status: **DECIDED for design purposes** (architecture decision record).
Implementation: **none yet** — this document governs sequencing; M3/M5/M6/M7
stay deferred until its prerequisites are met.
Supersedes: nothing. Complements: `docs/cancellation/CANCELLATION_ARTIFACT_MAP.md`,
`docs/architecture/STABILITY_AFTER.md`, CC3 (`composition/command_lineage.clj`).

## 1. Decision

**Choice 2 — add a prospective flow, implemented additively.** Party
cancellation is treated as part of the intended protocol, so Surface B gains a
signed-intent → prospective-authorization stage **in front of** the existing
retrospective machinery. The existing artifacts keep their current meaning,
names, domains, and roots:

```
signed party-cancellation intent          NEW artifact/flow (to be specified)
   ↓
prospective authorization/admission       NEW permit surface (NOT
   ↓                                      cancellation-operation-admission.v1)
fenced state transition                   transition kernel (M6 scope)
   ↓
cancellation-operation.v1                 EXISTING applied-statement artifact
   ↓                                      (unchanged semantics/roots)
retrospective statement verification      EXISTING admission/admit
   ↓                                      (unchanged semantics/roots)
commit / terminal receipt                 receipt boundary (planned; see
                                          STABILITY_AFTER.md "statement
                                          boundary" section)
```

Explicit non-decisions preserved:

* `cancellation-operation-admission.v1` is **never reinterpreted as the
  prospective permit**. It remains retrospective verification of an applied
  statement. Its domain string, root derivation, and blocking-reason
  vocabulary stay stable.
* `cancellation-operation.v1` stays an applied-statement artifact. No intent or
  permit may be shoehorned into it.

## 2. Why Choice 2

* The ordinary path already enforces pending-state eligibility structurally
  (`sew_escrow_snapshot.clj` `:snapshot/not-pending`) and has complete rooted
  semantic artifacts (policy/preconditions/evaluation/derived-effects/
  execution-effects) plus ed25519 command machinery — the natural substrate
  for a signed intent.
* Retiring (Choice 3) would discard working retrospective verifiers that the
  clean-room exploration and transplant tests exercise through the public
  entry point.
* Retrospective-only (Choice 1) would leave "who may cause this transition?"
  unanswered at the layer where state actually changes, forcing ad-hoc
  authorization elsewhere later.

## 3. Cross-surface coordination with Surface A (mandatory before any
state-changing implementation)

Surface A = certified decisions over allocation rounds
(`assurance/canonical_force_authorisation.clj`, consumed by
`allocation/round_state.clj`). Before Surface B becomes state-changing, the
following must be specified and tested:

| Requirement | Existing anchor | Open work |
| --- | --- | --- |
| Shared conflict key | `cfa/cancellation-conflict-key` already keys cancellation vs the irreversible cutpoint over one target | define the same key derivation for party-cancellation targets; prove both surfaces collide on `(target-id …)` equality where they should |
| Authoritative state-before root | Surface B binds `:target :state-before-root` + snapshot root; Surface A binds target/state evidence roots (`cancellation-binding-fields`) | designate ONE authoritative pre-state root per target per attempt; forbid mixing |
| Serialization / fence / version | cfa lifecycle cutpoint + window classification; Surface B carries `:lifecycle-head-root` and snapshot fence | ONE serialization seam owns each conflict key; **no state mutation on either surface may commit without owning the current conflict-key fence** (store-issued fence mandatory at commit; post-transition registration insufficient). Full reservation machine incl. stale-permit handling: `PROSPECTIVE_FLOW_CONTRACT.md` §4 |
| Precedence between certified and party cancellation | cfa windows classify states incl. dispute/timeout regimes | specify precedence table (e.g., certified decision supersedes pending party agreement within the same window; disputed state excludes ordinary party path entirely — already structural via `:snapshot/not-pending`) |
| Terminal idempotency | CC3 pattern: terminal append rejected (`:predecessor-terminal`), identical replay recognized (`:already-terminated`) | equivalent recognition at escrow level: second terminal transition of any surface is either rejected or acknowledged-as-already-applied, never re-applied |
| Single effect application | both surfaces must route terminal effects through ONE refund/terminal kernel call site | kernel extraction is part of M6; two valid paths must not double-apply |
| Separate authority proofs | Surface A: three-member certificate; Surface B: ed25519 party command | authority artifacts remain distinct even when effects converge in the shared kernel |

## 4. Consequences

* Prospective permit gets its OWN schema version, hash domain (registered in
  `hash/canonical.clj domain-tags`), and JSON Schema at first emission — never
  a reuse of admission's domain string.
* `action_boundary`/`statement_boundary` vocabulary stays as-is: it classifies
  applied statements only.
* The clean-room remains authoritative for generic composition semantics only;
  production cancellation-action schemas, if introduced by the prospective
  flow, are defined here first and exported to the clean room last (M7).
* Until the prospective flow exists, GAP-F wiring stays closed: Surface B
  remains library/evidence-verification code with zero state-changing callers,
  enforced by the inventory tests added alongside this decision.

## 5. Verification hooks

* `test/resolver_sim/cancellation/statement_boundary_test.clj` — closed-shape
  envelope + assurance vocabulary.
* `test/resolver_sim/cancellation/admission_transplant_test.clj` — public-entry
  rejection of transplanted agreements/effects.
* `test/resolver_sim/cancellation/dispute_separation_test.clj` — pending-vs-
  disputed structural separation.
* Future: cross-surface coordination suite under
  `test/resolver_sim/cancellation/cross_surface_test.clj` (first M6 deliverable).

## 6. First follow-on design artifact

The stages-1–2 contract draft (signed intent → prospective permit), with
per-primitive reuse decisions and the full binding table, lives in
`docs/architecture/PROSPECTIVE_FLOW_CONTRACT.md` (design only; no
implementation, no state mutation).
