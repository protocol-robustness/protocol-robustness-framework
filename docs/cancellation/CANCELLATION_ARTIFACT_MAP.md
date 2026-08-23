# Cancellation Artifact Map — source-backed inventory (M0)

Scope: answer, per candidate cancellation identity, what already exists in this
repository, what it means, and where genuine gaps are. Every claim cites a file
and line. Companion work: `src/resolver_sim/cancellation/action_boundary.clj`
(M1 guard) and `test/resolver_sim/cancellation/dispute_separation_test.clj`
(M2 evidence), plus clean-room counterpart
`prf-clean-room/resources/exploration/cancellation-provisional-v1.edn`.

## Headline answers

* **`canonical-cancellation.v1` does not exist** anywhere in this repository
  (`grep -rn "canonical-cancellation.v1"` → only a *test comment*,
  `test/resolver_sim/cancellation/role_substitution_probe.clj:275`, stating
  that `:record-party-agreement` is "never canonical-cancellation.v1").
  It is therefore neither a usable action identity nor an execution fact.
* The **actual operation identity** is `cancellation-operation.v1`
  (`src/resolver_sim/cancellation/operation.clj:8`) — and it is an
  *executed-statement record*: completeness requires `:execution :status
  :applied`, ordinary authorization kind, and ten qualified SHA-256 references
  (`operation.clj:11-33`). Per `docs/architecture/STABILITY_AFTER.md`
  ("Cancellation statement boundary"), its root is "a content-addressed,
  self-consistent statement only; it does not establish reference resolution,
  authority, admission, or authoritative commit."
* **Dispute remediation has no contract-layer implementation at all.**
  `grep -rn "dispute|remediat" src/resolver_sim/cancellation/ test/resolver_sim/cancellation/`
  → zero hits. Dispute/timeout/auto-cancel behavior exists only on the
  simulation side (`data/fixtures/suites/cancellation-equilibrium-validation.edn`,
  `resolver-sim.stochastic.dispute`) — different layer, no rooted artifacts.
* **Doc drift:** `docs/architecture/STABILITY_AFTER.md:135-137` cites
  `cancellation/operation.clj:235-248` with an `effective-decision-valid?`
  helper; the real file is 37 lines and contains no such var. Treat that row's
  line citation as stale.

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

* Hash-intent registry: cancellation domains live in `domain-tags`
  (`hash/canonical.clj:161-166`: binding/operation/attempt/execution/
  evaluation-inputs/derivation) but the *admission* string domain bypasses the
  keyword registry by design (`admission.clj:13`).
* Artifact/evidence registry: `results/test-artifacts/test-artifacts.json`
  holds 915 entries, all simulation event-evidence; **neither
  cancellation-operation.v1 nor -admission.v1 appears** → genuine gap.
* Aggregate/check tooling (`checks/ns-defs.edn`, conformance runners): no
  cancellation-operation/admission classification found.

## Genuine gaps (prioritized)

1. **GAP-A (registry):** register both contract artifacts in the artifact
   registry / aggregate classification so tooling can see them without
   upgrading assurance (admission must stay distinguishable from operation,
   committed transition, receipt). [M4]
2. **GAP-B (transition):** `:state-transition-binding` remains
   `:reason :transition/unimplemented` (`admission.clj:104`,
   probe test `check1-state-transition-binding-reports-unimplemented`). No
   verified cancellation transition exists yet. [M6]
3. **GAP-C (dispute remediation):** no rooted dispute preconditions, timeout,
   or governance-authorized remediation path. Ordinary path already refuses
   disputed state structurally (`sew_escrow_snapshot.clj:22-23`
   `:snapshot/not-pending`; pinned by `dispute_separation_test.clj`), so the
   two cases cannot silently merge today — but the remediation side of the
   boundary simply does not exist yet. [M2]
4. **GAP-D (doc drift):** STABILITY_AFTER.md cancellation rows cite stale
   symbols/line numbers. [housekeeping]
5. **GAP-E (consumer guard):** prior to `action_boundary.clj`, nothing stopped
   a consumer from treating a generically-valid composition-sequence vector
   (purpose `:canonical-cancellation/action`) as a cancellation action. Now
   closed at the consumer layer; generic V1 untouched. [M1]
