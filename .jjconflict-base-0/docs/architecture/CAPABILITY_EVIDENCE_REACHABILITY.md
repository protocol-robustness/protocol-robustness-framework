# Capability-Aware Evidence — Reachability Audit (design input, no changes)

Status: audit notes for the recommended next slice. Answers the seven
reachability questions with source citations. Conclusion first:

> The benchmark boundary ALREADY binds and anti-downgrade-verifies composition
> provenance; everything BELOW it (per-record evidence payloads and
> trace-conformance dispatch) does not. The deficiency is therefore primarily
> a MISSING VERIFICATION/DISPATCH LINK plus a missing per-record producer
> field — not merely absent fields on `make-evidence-record`. A separate
> rooted capability-context artifact is justified for those lower layers;
> `make-evidence-record` itself must not gain capability fields yet.

## Q1 — Does current evidence already bind a semantic-composition root that identifies capabilities?

**At the benchmark boundary: yes.** Scenario input snapshots committed via
`input_set_root` carry `:semantic-composition/root`; extraction is documented
as "the independent authoritative discriminator" (`benchmark/verify.clj:41-61`).
Finalization/completion projections carry `semantic_composition_root`, and
Phase 2C requires cross-artifact consistency (`verify.clj:319-326`) with
anti-downgrade: "evidence under composition A cannot pass as evidence under
composition B" (`verify.clj:324-326`). Since
`composition/semantic.clj:16` derives action/state-region modules from
`:requested-capabilities`, the root transitively identifies capabilities.

**At individual evidence records: no.** `make-evidence-record`
(`util/evidence.clj:65-77`) commits only artifact-kind/time/step/before/after/
action/result/attribution; no composition or capability field exists, and no
producer writes `:semantic-composition-root` onto result rows (grep over
`benchmark/packs`, runner: zero hits outside verify/run_benchmark readers).

## Q2 — Does read-back verify that root against the authoritative composition?

Yes at finalization verification: `input-set-composition-root` + declared-
authoritative discriminator (`verify.clj:63-80`) feed `expected-final-ref`,
and the `semantic-composition-root` consistency check gates the bundle.
NO at record read-back: `trace/conformance/validators.clj` dispatches on CDRS
structure/semantics only (schema version, scenario-id, fee_bps, action/role/
alias rules) — composition identity is absent from dispatch.

## Q3 — Can an adapter-specific capability be removed/replaced/transplanted without changing verified evidence?

Split answer:
* Inside a composition-authoritative benchmark run: NO at the boundary — any
  capability change alters `semantic-composition/root`, hence final_ref;
  Phase 2C detects substitution.
* Outside that harness (extension paths, raw evidence chains): YES,
  undetectably — records carry no composition binding to break, and nothing
  below finalization re-checks capability identity.

## Q4 — Are :forbidden / :forbidden-authorized distinguished by the schema-dispatched verifier?

No. Zero occurrences of either keyword in `trace/conformance/` or the generic
conformance package. Authorization-dimension vocabulary exists only in the
clean-room authorisation-usability family and in cfa decision taxonomy — not
in fixture dispatch.

## Q5 — Does the SEW aggregate held-credit adapter reach the same evidence path as general-purpose scenarios?

The extension (`extensions/held-custody/src/prf/extensions/held_custody/*`)
contains ZERO references to semantic-composition or composition roots. It
participates in world-state invariant checking like other state mutators, but
the adapter itself contributes no composition provenance; whether its runs are
composition-bound depends solely on the outer harness. Same path, but without
adapter-side binding.

## Q6 — Is the deficiency missing evidence fields, or a missing verification/dispatch link?

Both, ordered:
1. **Missing verification/dispatch link** — record-level read-back never asks
   "which composition/capability produced this?" even when a root is available
   upstream.
2. **Missing per-record producer field** — `:semantic-composition-root` is
   expected by verify but emitted by nobody.
Adding (2) without (1) would create unverified decoration; adding (1) without
(2) has nothing to check. Hence the joint fix belongs behind one rooted
context artifact.

## Recommendation

Proceed with a SEPARATE rooted capability-context artifact, schema shaped by
the existing extension-manifest and verifier-registry contracts:

```clojure
{:capability-context/schema "capability-context.v1"
 :composition/root …        ; from the authoritative composition
 :capability/id …           ; [kind id] reference vocabulary
 :capability/version …
 :verifier-registry/root …  ; which verifier may judge this context
 :authorization-basis/root …}
```

Do NOT touch `make-evidence-record`'s projection in this slice: doing so would
change existing record hashes, bundle roots, fixtures, and replay evidence.
Introduction order when implemented: register domain tag → emit context
artifact from the authoritative composition → extend dispatch validators to
require it per capability-gated evidence kind → then, and only then, consider
a reference field on records.

## Clean-room triage dispositions recorded here for continuity

* Delegation/deployability language: no defect, no work.
* Branch-intolerant lineage conservation: documented limitation; before any
  algorithm change, add a REALISTIC branching fixture demonstrating a
  supported flow where origin 100 splits into branches 60+40 that currently
  verdicts `:ambiguous-descendant`. A branch-aware checker additionally needs
  graph identity, cycle rejection, double-count protection, archived/deferred
  treatment, deterministic traversal.
* Multiple not-admitted notebooks: defer consolidation (different layers).
* Golden-fixture classification drift: tracked separately in
  `docs/reproducibility/golden-fixture-drift-task.md`.
