# Cancellation Artifact Map — source-backed inventory (M0)

Scope: answer, per candidate cancellation identity, what already exists in this
repository, what it means, and where genuine gaps are. Every claim cites a file
and line. Companion work: `src/resolver_sim/cancellation/statement_boundary.clj`
(M1 guard — operation/STATEMENT classification), and
`test/resolver_sim/cancellation/dispute_separation_test.clj` +
`test/resolver_sim/cancellation/admission_transplant_test.clj` (M2/M-follow-up
evidence), plus clean-room counterpart
`prf-clean-room/resources/exploration/cancellation-provisional-v1.edn`.

## Headline answers

* **Timeline (traced):** there are TWO unrelated cancellation surfaces.
  1. *Certified-decision window surface* — `assurance/canonical_force_authorisation.clj`
     (`cancellation-window.v1`, lifecycle classification, conflict keys,
     `:emit-terminal-cancellation-receipt` effects, contract-7 binding fields),
     consumed in production by `allocation/round_state.clj`. This is where
     three-member-certified cancellation decisions live.
  2. *Party-cancellation contract layer* — `cancellation/{operation,admission,
     ordinary-planner,party-command,party-preconditions,sew-escrow-snapshot,
     semantic}`. **Zero production callers**: nothing outside tests constructs
     operations or signs party commands (`grep` for `sign-envelope`,
     `verify-command`, `:execution :status :applied` writers → tests only).
     No orchestration wires this layer to world state.
* **Model disposition:** `cancellation.admission/admit` implements
  **retrospective evidence admission (model A)** — completeness requires
  `:execution :status :applied` (`operation.clj:30`) and admission verifies the
  already-applied statement (authority from an attached signed command,
  per-stage root+semantic validation, recomputation match). It must NOT be
  described as prospective execution authorization (model B); that model is
  proposed architecture only.
* **`canonical-cancellation.v1` does not exist** anywhere in this repository
  (`grep -rn "canonical-cancellation.v1"` → only a *test comment*,
  `test/resolver_sim/cancellation/role_substitution_probe.clj:275`, stating
  that `:record-party-agreement` is "never canonical-cancellation.v1").
  It is therefore neither a usable action identity nor an execution fact.
* The **actual operation identity** is `cancellation-operation.v1`
  (`src/resolver_sim/cancellation/operation.clj:8`). Per
  `docs/architecture/STABILITY_AFTER.md` ("Cancellation statement boundary"),
  its root is "a content-addressed, self-consistent statement only; it does
  not establish reference resolution, authority, admission, or authoritative
  commit."
* **Dispute remediation has no contract-layer implementation at all.**
  `grep -rn "dispute|remediat" src/resolver_sim/cancellation/
  test/resolver_sim/cancellation/` → zero hits. Dispute/timeout/auto-cancel
  behavior exists only on the simulation side
  (`data/fixtures/suites/cancellation-equilibrium-validation.edn`,
  `resolver-sim.stochastic.dispute`) — different layer, no rooted artifacts.
* **Doc drift:** `docs/architecture/STABILITY_AFTER.md` cited
  `cancellation/operation.clj:235-248` / `effective-decision-valid?` /
  `:authorized-by-override` — none exist. Row corrected in-place (now cites
  `cancellation/admission.clj:130,147` and `ordinary-planner.clj`);
  reconciliation done as part of this follow-up.

## Per-artifact inventory

| Candidate artifact | Exists? | Namespace / constructor | Schema & hash domain | Committed projection | Registered? | Status |
| --- | --- | --- | --- | --- | --- | --- |
| canonical-action.v1 | **No** | — | — | — | — | name appears nowhere; do not invent without M3 decision |
| canonical-cancellation.v1 | **No** | — | — | — | — | only referenced negatively in a test comment (see headline) |
| canonical-dispute-remediation.v1 | **No** | — | — | — | — | remediation logic absent from contract layer entirely |
| canonical-terminal-refund.v1 | **No** | refund exists as an *effect kind*, not a schema: `:effects/kind :refund-sender` inside `sew-party-cancellation-derived-effects.v1` (`cancellation/semantic.clj:14,16`; planner `ordinary_planner.clj:18-20`) | derived-effects domain `SEW_PARTY_CANCELLATION_DERIVED_EFFECTS_V1` | effects map + root | no registry entry | effect vocabulary only; not a standalone receipt/refund artifact |
| cancellation-operation.v1 | **Yes** | `resolver-sim.cancellation.operation` / `operation-root` (`operation.clj:34-36`) | `"cancellation-operation.v1"`; domain tag `CANCELLATION_OPERATION_V1` (`hash/canonical.clj:162`) | full statement incl. target snapshot/state-before/lifecycle-head, policy, evaluation+derived-effects, authorization, execution effects + state-after roots | **Not registered**: `results/test-artifacts/test-artifacts.json` (915 artifacts) contains zero `cancellation-operation*` entries | validated for shape/reference-syntax only; admission separately resolves/recomputes; execution status is asserted, never proven |
| cancellation-operation-admission.v1 | **Yes** | `resolver-sim.cancellation.admission` / `admit`, `admission-root` (`admission.clj:52-165,15`) | `"cancellation-operation-admission.v1"`; dedicated string domain `CANCELLATION_OPERATION_ADMISSION_V1` (`admission.clj:13`) | authority verification (ed25519 signed party command via `party-command/verify-command`), per-stage root+semantic validation, recomputed plan match, blocking reasons | **Not registered** in test-artifacts.json; also absent from intent/schema registries under `resources/`, `schemas/`, `etc/` | decision artifact; distinguishes `:state-after-integrity` (verified when resolved) from `:state-transition-binding` (`:reason :transition/unimplemented`, `admission.clj:104`) — admission ≠ execution |
| cancellation-commit-receipt.v1 | **No** | — | — | — | — | named in STABILITY_AFTER.md as *planned* boundary alongside commit facts |
| command-lineage cancel-and-terminate | **Yes (CC3, frozen)** | `resolver-sim.composition.command-lineage` (`command_lineage.clj`) | lineage-specific tags `PRF_COMMAND_LINEAGE_{COMBINATION,COMMAND,…}_V1` (file-local, deliberately outside central registry) | combination/command/concatenation chain roots | n/a (primitive, not evidence artifact) | terminal append rejects `:predecessor-terminal`; identical terminal replay idempotent (`:already-terminated`); "concatenation-chain-root … carries no semantic authority"; validity layers pairwise ≠ chain ≠ injectivity. This is interpretation A of cancel-and-terminate |

Related non-identities (for M5 bookkeeping): `with-claimant-options` /
`related-claims` appear in this repo only as force-authorisation action-name
strings (`composition/semantic.clj:64`) and unrelated yield/workflow code —
there is **no cancellation wrapper/scope construct** to bind today.

## Registration status summary (M4 input)

* **Registry ownership resolved:** `results/test-artifacts/test-artifacts.json`
  (schema `test-artifacts.v1.2`; consumed by
  `validation.integration.artifact-registry` → validation-root.v1) is a
  **run-generated evidence index** of artifacts produced by test runs — not a
  hand-maintained contract registry. Contracts appear there only when a run
  actually emits them; hand-registering operation/admission there would be a
  category error. The authoritative homes today are:
  - **hash-intent/domain registration** — already satisfied: `domain-tags`
    (`hash/canonical.clj:161-166`) registers the six cancellation domains;
    admission's string domain (`admission.clj:13`) bypasses keywords by
    design; prefix-free tag validation guards ambiguity
    (`hash/canonical.clj:2893+`).
  - **def-inventory** — `checks/ns-defs.edn` regenerated via
    `bb check:defs --generate`; all eight cancellation namespaces now listed.
  - **JSON Schemas** (`schemas/*.json`) — none exist for operation/admission.
    That is the correct state while the layer is library-only and unwired;
    schema registration belongs with first real emission/export (M7-era), not
    before the timeline decision lands.
* Aggregate/check tooling has no cancellation-operation/admission
  classification — acceptable for an unwired layer; revisit with M6/M7.

## Consumer guard disposition (M1 follow-up #3)

`grep ':canonical-cancellation/action|composition-sequence'` over production
src shows only `composition/v1.clj` (the generic V1 validator, deliberately
untouched). No production consumer feeds composition-shaped maps into a
cancellation-action role, so `cancellation/statement_boundary.clj` ships as a
**tested defensive utility**: it is the designated stage-2 boundary for the
first consumer that appears, classified as OPERATION/STATEMENT acceptance with
`:assurance :structural-shape-only` and explicit `non-claims` (no execution /
authority / admissibility / transition / state-change asserted). It makes no
prospective-authorization claim whatsoever.

## Transplant evidence upgrade (follow-up #4)

`test/resolver_sim/cancellation/admission_transplant_test.clj` now proves
rejection through the PUBLIC entry point `admission/admit`, beyond root
inequality:
* edited agreement content served under the original snapshot root →
  `:snapshot/invalid-artifact` blocking reason;
* execution-effects bound to another state's derived-effects root →
  `:operation/execution-effects-mismatch` from recomputation matching;
* honest control context admits.

## Genuine gaps (prioritized)

1. **GAP-A (registry):** RESOLVED for now — ownership established (run-index
   vs intent registry vs def-inventory; see Registration status). Schema
   registration in `schemas/` deferred until first real artifact emission.
2. **GAP-B (transition):** `:state-transition-binding` remains
   `:reason :transition/unimplemented` (`admission.clj:104`,
   probe test `check1-state-transition-binding-reports-unimplemented`). No
   verified cancellation transition exists yet. [M6 — deferred pending
   timeline/architecture decision]
3. **GAP-C (dispute remediation):** no rooted dispute preconditions, timeout,
   or governance-authorized remediation path. Ordinary path already refuses
   disputed state structurally (`sew_escrow_snapshot.clj:22-23`
   `:snapshot/not-pending`; pinned by `dispute_separation_test.clj`, and now
   additionally at admission level by `admission_transplant_test.clj`
   controls), so the two cases cannot silently merge today — but the
   remediation side of the boundary simply does not exist yet. [M2]
4. **GAP-D (doc drift):** RESOLVED — STABILITY_AFTER.md classification row
   corrected with live citations; statement-boundary prose verified accurate.
5. **GAP-E (consumer guard):** prior to `statement_boundary.clj`, nothing
   stopped a consumer from treating a generically-valid composition-sequence
   vector (purpose `:canonical-cancellation/action`) as a cancellation action.
   Closed as a tested defensive utility at the consumer layer; generic V1
   untouched; no current production consumer exists to integrate into.
6. **GAP-F (wiring/timeline):** RESOLVED-BY-DECISION — see
   `docs/architecture/SURFACE_B_TIMELINE_DECISION.md` (Choice 2: additive
   prospective flow; existing retrospective artifacts keep stable meanings;
   cross-surface coordination with Surface A specified as mandatory
   prerequisites). Implementation remains deferred; Surface B stays unwired
   until the prospective flow exists.
