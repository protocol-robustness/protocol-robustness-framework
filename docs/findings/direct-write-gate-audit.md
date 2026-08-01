# Held-Custody Ledger Audit & Direct-Write Gate — Findings

Reference: audit of the held-custody ledger path (`add-held` / `sub-held` /
`adjust-held` / `update-ledger-index`), the `not_admitted.clj` notebook held-custody
sections, and the static direct-write gate (`scripts/scenarios/check_direct_writes.clj`).

## Production correctness

The audit found the ledger/index mutation logic to be internally consistent with the
documented invariants and the accompanying test suite. In particular:

- `add-held` and `sub-held` route through `adjust-held`; every relevant validation
  (input sanity, underflow, reason-position policy, address scope, force-authorisation
  guard) completes **before** `update-ledger-index` is invoked.
- Indexing is consistent across token / position / account / owner / workflow
  dimensions; the top-level aliases `:total-held` and `:held/positions` are re-derived
  from the index, so index/alias divergence is not produced by the live path.
- `sub-held` cannot underflow (aggregate and per-position guards).
- Consumed force-authorisations cannot be reused.
- Each successful operation appends exactly one held adjustment and one custody
  artifact with deterministic identifiers and predecessor linkage.

The above describes internal consistency with the documented invariants and test
suite; it is not a formal proof.

## Ledger/artifact adjacency

- Chain integrity is guaranteed by sequence identifiers (`held-adjustment-N`, with `N`
  derived from the adjustment count) plus `:held/previous-artifact-hash`.
- The closed-form verifier (`resolver-sim.assurance.custody/held-custody-closed-form-checks`)
  enforces hash integrity, local delta, non-negative-after, predecessor continuity, and
  sequence replay. Tamper detection is demonstrated in the notebook.

## Live-vs-replay equivalence

Live-vs-replay equivalence (under the documented zero-origin reconstruction
assumptions) is admitted. The live `update-ledger-index` path and the independent
`replay-held-adjustment-state` reconstruction agree on complete, zero-origin histories;
the notebook now exercises this directly. Non-zero-origin / imported or partial
histories are a documented, design-intended exception surfaced by
`final-held-summary` (`:reconstruction-valid? false` / `:missing-opening-state`).

## Direct-write gate (resolved)

The static direct-write gate now passes cleanly (557 files scanned, 10 allowlisted
functions, private-mutator + escape-hatch checks clean; previously 4 violations). It
preserves the distinction between the canonical mutator and completeness-clearing
escape hatches rather than broad-allowlisting:

- **Private-mutator guarantee:** the repository gate verifies that
  `update-ledger-index` is declared private and has exactly one detected direct caller,
  `adjust-held`. This is a static detection of direct source references; it does not
  prove that indirect or dynamic invocation cannot occur.
- **Escape-hatch guarantee:** the gate statically verifies that each classified escape
  hatch (`adversarial-accrue`, `apply-pro-rata-propagation`) contains the required
  completeness-clearing operation (`:held-adjustments/complete?`), while the relevant
  behavioural suites verify the production paths. This is a source-pattern containment
  check, not a full control-flow proof, and these entries are not generic authorised
  direct-write locations.
- Allowlisting is exact-var based and each entry carries a `:behaviour` and a
  `:justification`; matching never uses namespace or loose source patterns.
- Negative coverage confirms that a brand-new direct write, an additional
  private-mutator caller, and a falsely classified (non-clearing) escape hatch all
  remain rejected.

The gate is a repository-level structural safeguard complementing, rather than
replacing, behavioural and invariant testing.

## Classification

- **Admitted (executable evidence + tests):** `add-held` (including lifecycle,
  authorised/exceptional, and position/account sub-categories), `sub-held`,
  `:held-adjustments` creation/ordering, adjacency / predecessor linkage, ledger/index
  consistency, live-vs-replay equivalence (under zero-origin assumptions), rejected /
  fail-closed mutations, and force-authorisation consumption/rejection.
- **`claim-deferred` (not broadened):** force-authorisation `revoked`/`not-active`
  status and `related-claims` linkage. These remain deferred until demonstrated directly
  in the notebook; the admitted classification was not extended to cover them.

## Key files

- `protocols_src/resolver_sim/protocols/sew/accounting.clj` — corrected `update-ledger-index`
  doc comment (index is authoritative; seed is inert).
- `scripts/scenarios/check_direct_writes.clj` — gate rework (canonical vs escape-hatch,
  exact-var annotated allowlist, private-mutator + completeness-clearing verification).
- `test/resolver_sim/scripts/check_direct_writes_test.clj` — new negative / invariant
  regression coverage.
- `notebooks/not_admitted.clj` — §10 live-vs-replay block, §10 allowlist note, §17
  `claim-deferred` note, §19 summary.
