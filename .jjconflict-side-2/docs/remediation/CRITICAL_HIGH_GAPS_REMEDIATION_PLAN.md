# Critical and High Gaps Remediation Plan

## Purpose

Close the critical/high assurance gaps identified in the review of consensus,
boundary policy, provenance/authentication, pro-rata accounting, value at risk,
and bundle criteria.

No issue in the reviewed scope was confirmed at **critical** severity. The work
is ordered by the ability to accept forged or tampered state, then by the risk
of overstating a research/review claim, then by correctness and architecture
hardening.

## Priority rules

1. A validator or predicate must never report a tampered object as valid,
   runnable, reconciled, or authenticated.
2. An authorization decision must derive from verified, hash-bound facts rather
   than caller-supplied labels.
3. A certificate must not report consensus that cannot be recomputed from its
   declared, content-addressed inputs.
4. A property must not be labelled implemented/evaluated if the declared
   execution path cannot invoke it.
5. Every remediation package includes negative/tampering tests and an explicit
   compatibility decision.

## Delivery sequence

| Order | Work package | Priority | Dependency | Exit decision |
|---|---|---:|---|---|
| 0 | Add shared hash/provenance test fixtures | P0 | none | Provides trustworthy negative-test fixtures; no behaviour change. |
| 1 | Make bundle-root criteria content-verifying | P0 | WP0 | A tampered root cannot be reported runnable or structurally valid. |
| 2 | Make related-claims authentication verifiable | P0 | WP0 | No relationship is authenticated from mutable assurance labels. |
| 3 | Reconcile authoritative post-propagation positions | P0 | none | Post-application position tampering fails pro-rata reconciliation. |
| 4 | Repair and lock down temporal boundary semantics | P0 | none | Boundary-policy regression suite is passing and contracts are unambiguous. |
| 5 | Bind review certificates to members, reports, positions, and hashes | P1 | WP0 | A three-member certificate is independently recomputable. |
| 6 | Correct and wire folk-theorem evaluation | P1 | WP4 | Declared folk-theorem claims have one correct, evidence-backed execution path. |
| 7 | Enforce the framework/protocol-extension boundary | P1 | none | CI detects protocol extension coupling across the complete core source tree. |
| 8 | Rename and document the ownership boundary | P2 | WP7 | Terminology consistently describes framework/extension ownership. |

`value-at-risk` / `valid-amount` has no confirmed critical/high defect and is
not a blocker. Its test expansion is retained as follow-up work after P0/P1.
Likewise, no project-level `performance-sensitive` contract exists; creating
one should be a separately approved performance/SLO initiative, not a blocker
for this remediation plan.

---

## WP0 — Shared integrity and provenance test fixtures

**Priority:** P0 prerequisite

### Objective

Establish reusable fixtures and helpers for correctly formed content hashes,
intentionally stale hashes, configured governance identities, and malformed
provenance. This prevents every P0/P1 package from inventing subtly different
negative-test inputs.

### Expected files

- Add `test/resolver_sim/support/integrity_fixtures.clj`, or extend the
  repository's existing shared test support if one is already established.
- Reuse existing canonical hashing APIs; do not add a second serialization or
  hash implementation.

### Work

1. Provide small helpers to create a valid hashed map and a map whose payload
   has changed after hashing.
2. Provide fixtures for a restricted governance actor, a mismatching actor
   address, missing creator provenance, and a provenance record with stale
   committed content.
3. Make fixtures explicit about trust level: direct construction is suitable
   for validator negative tests, not for public-action authorization evidence.
4. Do not expose these test-only helpers through production namespaces.

### Acceptance criteria

- Tests in later work packages can create valid and stale objects without
  duplicating canonical-hash projections.
- Fixture use cannot cause production code to accept test keys or test trust
  roots.

---

## WP1 — Content-verifying bundle-root criteria

**Priority:** P0

### Problem

`runnable-bundle-root?` in `src/resolver_sim/run/criteria.clj` accepts a root
when `:bundle/id` equals `:bundle/hash`, but it does not recompute the hash over
the current bundle content. `structurally-valid?` consequently treats altered
persisted content as runnable.

### Expected files

- Update `src/resolver_sim/run/bundle_root.clj`
- Update `src/resolver_sim/run/criteria.clj`
- Update `test/resolver_sim/run/bundle_root_test.clj`
- Add/extend `test/resolver_sim/run/criteria_test.clj` if that namespace exists

### Design decisions

1. Keep hash-preimage ownership in `bundle_root.clj`; criteria must call a
   public, single-source-of-truth recomputation function rather than duplicate
   the projection.
2. Require both `:bundle/id` and `:bundle/hash` to equal the recomputed hash.
3. Verify `:overview/hash` using its documented canonical projection if it is
   an integrity commitment. Otherwise remove it from criteria claims and
   document it as advisory metadata.
4. Keep `bundle-status` as a classification only. It must not imply integrity
   verification.
5. Preserve existing readable legacy roots only through an explicit profile or
   version branch. A legacy root must not receive the strengthened
   `:runnable? true` claim unless its version defines a verifiable projection.

### Implementation steps

1. Extract or expose a pure `recompute-bundle-hash` function from
   `bundle_root.clj`.
2. Add a criteria error such as `:bundle-content-hash-mismatch`, including
   declared and recomputed values but not sensitive content.
3. Have `runnable-bundle-root?` fail closed on malformed bundle content or
   failed hash recomputation.
4. Ensure `structurally-valid?` and any public verification entry point use the
   content-verifying criterion.
5. Search callers of `runnable-bundle-root?` and `bundle-status`; correct any
   wording or elevation that treats classification as integrity validation.

### Required tests

- Fresh builder output remains runnable.
- Mutate each integrity-relevant top-level field independently: execution
  summary, run request, registry snapshot/reference, overview, and protocol
  state witness where present.
- Mismatch `:bundle/id`, `:bundle/hash`, and both together while retaining an
  old matching pair.
- Missing/malformed hash and malformed content fail without throwing.
- Valid legacy compatibility behaviour is explicit and cannot be classified as
  content-verified by accident.

### Exit criteria

A byte/content-equivalent canonical bundle is required for `:runnable? true`.
Every payload mutation causes `:runnable? false` with a stable error code.

---

## WP2 — Verified related-claims authentication and provenance boundary

**Priority:** P0

### Problem

`authenticated-related-claims?` in
`protocols_src/resolver_sim/protocols/sew/related_claims.clj` checks mutable
assurance labels but does not recompute the relationship hash or validate the
claimed provenance/address binding.

### Expected files

- Update `protocols_src/resolver_sim/protocols/sew/related_claims.clj`
- Update `protocols_src/test/resolver_sim/protocols/sew/related_claims_test.clj`
- Review direct callers of related-claims authentication
- Review `protocols_src/resolver_sim/protocols/sew/resolution.clj` and
  `protocols_src/resolver_sim/protocols/sew/accounting.clj` for the documented
  lower-level provenance boundary

### Design decisions

1. Distinguish **structural classification** from **authenticated validity**.
   A map with `:address-bound` labels is not authenticated merely because those
   labels are present.
2. An authenticated predicate must validate all facts it claims: record hash,
   canonical creator-provenance projection, restricted governance mode, and
   configured actor-address binding.
3. If full validation requires world/registry context, use an API such as
   `authenticated-related-claims? [world relationship]`; do not retain a
   context-free predicate with an authentication name.
4. Direct low-level mutation functions must either be private/internal or take
   a validated provenance capability produced by the public dispatcher. A
   non-`nil` arbitrary map is not sufficient authorization.

### Implementation steps

1. Identify the V2 canonical hash projection and add a pure relationship
   verifier returning structured reasons.
2. Verify that the declared relationship hash recomputes from the full,
   intended V2 projection.
3. Verify creator provenance against the committed projection and the active
   restricted-governance configuration/address in the supplied world/context.
4. Make the exported authenticated predicate delegate to that verifier.
5. Replace direct boolean-only internal uses with structured validation where a
   reason must be reported to callers.
6. Mark lower-level functions as internal, narrow their visibility, or replace
   their map parameter with a validated authorization token/capability.

### Required tests

- Valid restricted governance-produced relationship authenticates.
- Forged top-level and nested `:address-bound` labels fail.
- Stale relationship hash fails after member, semantics, or provenance edits.
- Missing/mismatched actor address fails.
- Legacy, open, role-declared, and direct-builder records remain readable but
  never authenticate.
- A direct low-level caller cannot authorize an exceptional adjustment or
  appeal merely by supplying an arbitrary non-`nil` map.

### Exit criteria

No public authorization decision can be obtained from caller-supplied
classification labels. Authentication is explicitly context-bound, hash-bound,
and test-proven against tampering.

---

## WP3 — Post-application pro-rata position reconciliation

**Priority:** P0

### Problem

`check-pro-rata-accounting-reconciles` reports
`:position-after-hash-valid`, but no mismatch is emitted. The invariant does
not compare stored application `:position-after-hash` values with authoritative
current positions.

### Expected files

- Update `src/resolver_sim/yield/invariants.clj`
- Confirm/reuse `canonical-hash-safe` from
  `src/resolver_sim/yield/modules/liquid_lending.clj`
- Update `test/resolver_sim/yield/pro_rata_accounting_test.clj`

### Implementation steps

1. Define the exact position projection covered by `:position-after-hash` and
   make it public/documented if not already stable.
2. For every application participant, load the authoritative current position
   from `[:yield/positions participant-id]`.
3. Recompute its canonical hash and compare it with the committed
   `:position-after-hash` from the application participant record.
4. Emit a structured `:position-after-hash-mismatch` with propagation ID,
   participant ID, expected hash, and observed hash. Do not include mutable
   position payload unnecessarily.
5. Decide and document closed/removed-position semantics: either a committed
   closure record must validate equivalently, or its absence must fail
   reconciliation.
6. Ensure the existing check map and relevant accounting categories consume the
   new reason.

### Required tests

- Untouched valid propagation reconciles.
- Tamper post-application deferred amount, status, origin/lineage, obligation
  identity, and cumulative fulfilment independently.
- Missing current position and valid closed-position history have explicit,
  tested outcomes.
- Application-record hash and current-position hash failures remain separately
  identifiable.

### Exit criteria

Every material mutation of an authoritative post-application position makes
`check-pro-rata-accounting-reconciles` fail and marks
`:position-after-hash-valid` as `:fail`.

---

## WP4 — Boundary-policy semantics and temporal regression repair

**Priority:** P0

### Problem

The temporal boundary suite has failures for `Instant` handling and Sew
appeal-window guard identity. The boundary policy must be stable because it
governs deadline and earliest-execution behavior.

### Expected files

- Inspect/update `src/resolver_sim/contract_model/replay/temporal.clj`
- Inspect/update the Sew temporal/action integration that emits guard context
- Update `test/resolver_sim/contract_model/replay_temporal_test.clj`
- Update only the relevant conceptual documentation if the canonical guard
  identity intentionally changes

### Implementation steps

1. Reproduce and isolate the three current failures before changing semantics.
2. Define the accepted time domain at the boundary: numeric epoch seconds,
   `Instant`, or both. Normalize at one entry point and reject unsupported
   types with a stable reason.
3. Preserve the formal policy semantics:
   - `:before`: permitted only when `event-time < deadline`;
   - `:at-or-after`: permitted only when `event-time >= deadline`.
4. Decide whether guard context identifies the generic enforcement mechanism,
   the protocol rule, or both. Prefer both when consumers require generic
   classification and protocol-specific diagnostics.
5. Add a small table-driven test matrix for before, exact-boundary, and after
   values across every supported input time representation.

### Required tests

- `:before` accepts strictly earlier and rejects equality/later.
- `:at-or-after` rejects earlier and accepts equality/later.
- Numeric and `Instant` representations have the same result if both are
  supported.
- Sew appeal/evidence/settlement/timelock paths emit the documented rule and
  decision context.

### Exit criteria

The temporal suite passes, boundary outcomes are unambiguous at equality, and
all guard-context identifiers have a documented consumer contract.

---

## WP5 — Recomputable, membership-bound review certificates

**Priority:** P1

### Problem

Certificates currently count three submitted records rather than proving a
three-member review cell. Theorem/conclusion consensus is grouped by ID rather
than content hash, and loaded certificate validation cannot recompute consensus
from referenced inputs.

### Expected files

- Update `src/resolver_sim/benchmark/review/three_member_certificate.clj`
- Update `src/resolver_sim/benchmark/researcher_position.clj`
- Inspect/update review-round and canonical-index validators as needed
- Update `test/resolver_sim/benchmark/review/three_member_certificate_test.clj`
- Update `test/resolver_sim/benchmark/researcher_integration_test.clj`
- Update `data/concepts/framework/review_certificate.edn` only if the schema or
  documented capability changes

### Design decisions

1. A certificate is a claim about a specific review round, set of members,
   content root, report set, position set, and target content hashes.
2. A target identity is at least `[kind id hash]`. If product semantics require
   only one current hash per ID, reject multiple hashes for the same
   `[kind id]` within a review cell instead of silently splitting them.
3. Exact replication must compare all documented roots. The former
   `:execution/realised-parameter-set-root` field was removed from the active
   production contract (declared-and-consumed-but-never-produced); it should be
   reintroduced only together with a producer, per the
   allocation-proofs determination. Exact replication compares content-root,
   model-root, model-instance-root, plan-root, parameter-domain-root,
   sampling-policy-root, generated-case-set-root, and evaluation-policy-root.
4. Standalone validation needs resolvable, content-addressed report and
   position bodies. Storing only their hash strings is insufficient for the
   advertised independently recomputable validation.

### Implementation steps

1. Strengthen preconditions to require:
   - exactly three distinct researcher IDs;
   - exact equality of report IDs, position IDs, review-round member IDs, and
     canonical-index member IDs;
   - one report and position per member;
   - equality of review-round/report/position content roots;
   - equality of each member's report and position outcome hashes;
   - valid report/position content hashes.
2. Replace the `some` report lookup with a validated index keyed by researcher
   ID and reject duplicates before construction.
3. Require target hashes in researcher positions; validate their shape and
   bind them to the corresponding outcome/target manifest where that material
   is available.
4. Group theorem and conclusion consensus by content identity, or reject hash
   conflicts as above.
5. Add realised parameter set roots to exact-replication comparison.
6. Define a versioned certificate-input reference block containing paths/URIs
   and hashes, or provide a resolver-based validator API.
7. Make `validate-certificate` validate referenced artifacts, recompute all
   execution/dimension/item consensus, compare it to the stored body, then
   verify the certificate hash.
8. Version the certificate schema if the persisted, authoritative structure
   changes. Legacy certificates can remain readable but must report
   `:legacy-not-recomputable` rather than fully validated consensus.

### Required tests

- Duplicate researcher IDs, unauthorised IDs, duplicate reports, duplicate
  positions, and missing one-to-one joins reject.
- Mismatched content roots and outcome hashes reject.
- Different theorem/conclusion hashes under the same ID reject or produce the
  documented distinct identities; they must never be unanimous for one
  unlabeled item.
- Missing/malformed target hashes reject.
- Differing realised parameter roots are not `:exact-replication`.
- Mutating persisted consensus while recomputing the certificate self-hash
  still fails semantic validation.
- A fully valid certificate built from referenced inputs passes independent
  recomputation.

### Exit criteria

A certificate cannot claim three-member independent consensus unless its
member set, source artifacts, scope, outcome bindings, and item content hashes
all validate and the recorded result recomputes exactly.

---

## WP6 — Correct and integrate folk-theorem cooperation evaluation

**Priority:** P1

### Problem

The evaluator exists only in the multi-epoch report path, while ordinary trace
validation marks the declared concept unsupported. Its documented cooperation
threshold also differs from the implementation delegated to grim-trigger
stability, and equality is incorrectly excluded.

### Expected files

- Update `src/resolver_sim/sim/stochastic_equilibrium.clj`
- Update `src/resolver_sim/benchmark/game_theory_validation.clj`
- Update the relevant trace/property dispatcher
- Update `test/resolver_sim/sim/stochastic_equilibrium_test.clj`
- Add integration coverage for the declared evaluation route
- Update `docs/conceptual/incentive-property-semantics.md`

### Implementation steps

1. Select the formal economic condition to support. Obtain a review/owner
   decision before changing formula semantics; do not silently equate the folk
   theorem condition with the separate grim-trigger approximation.
2. Implement the selected formula directly with explicit denominator and
   insufficient-data handling.
3. Make equality satisfy `>=` if that is the adopted formal condition.
4. Require a declared multi-epoch evidence input for this concept. A
   single-trace evaluator should return a clearly scoped `:inconclusive` reason
   rather than implying coverage.
5. Wire the concept through one canonical dispatcher/execution path, including
   evidence provenance and registry classification.
6. Change the implementation/wiring registry only after the integration test
   proves that a declared claim reaches the evaluator.

### Required tests

- Values strictly inside, exactly on, and outside the chosen threshold.
- A discriminating test that would differ under the old and adopted formulas.
- Zero/negative utility or zero denominator returns the documented
  inconclusive/fail result.
- A declared ordinary trace reports the appropriate unsupported scope or
  triggers the canonical multi-epoch evaluator—never a misleading pass.
- Full dispatched execution produces the expected evidence and status.

### Exit criteria

The advertised formula, implementation, tests, and dispatcher all agree. No
catalogue entry describes the property as wired/evaluated before that path is
verified.

---

## WP7 — Complete framework/protocol-extension boundary enforcement

**Priority:** P1

### Problem

The existing architecture test uses a manually enumerated subset of source
paths and one textual Sew namespace pattern. It cannot establish the claimed
protocol-independent framework boundary.

### Expected files

- Update `config/architecture/protocol-boundaries.edn`
- Update `test/resolver_sim/architecture/core_distribution_boundary_test.clj`
- Inspect build configuration and JAR packaging tests
- Update `docs/architecture/PROTOCOL_EXTENSION_BOUNDARIES.md`

### Implementation steps

1. Define the authoritative core scope as all production namespaces packaged in
   `prf.jar`, excluding only explicitly documented adapters/interfaces.
2. Generate source-file/namespace coverage from that scope; do not maintain an
   allowlist of selected files.
3. Detect namespace dependencies rather than only source-text spelling. At a
   minimum detect `require`, `use`, `import`, and runtime namespace resolution
   APIs; where dynamic resolution is necessary, require a documented adapter
   registry boundary.
4. Make extension namespace prefixes configuration-driven and reject all of
   them in core, not only Sew.
5. Add a distribution-level test proving `prf.jar` excludes extension source,
   resources, and extension-only command registrations.
6. Explicitly classify `benchmark/runner` dynamic Sew resolution as an adapter
   mechanism or move it out of core if it is protocol-specific.

### Required tests

- A direct extension require in any `src/` namespace fails.
- An extension dependency through each supported resolution mechanism fails or
  requires an explicit adapter exemption.
- A new unlisted core file is scanned automatically.
- `prf.jar` build/package inspection passes without Sew source/corpus or
  Sew-only command registrations.

### Exit criteria

CI verifies the stated framework/extension dependency direction across the
whole packaged core, and every dynamic extension bridge is explicit and
reviewable.

---

## WP8 — Rename the architectural ownership concept

**Priority:** P2

### Objective

Replace `core-protocol-boundary` terminology that can imply a “core protocol”
with terminology that accurately describes framework, adapter, and accounting
ownership.

### Proposed canonical identifier

```clojure
:framework/protocol-extension-boundary
```

Alternative if the three layers must be explicit:

```clojure
:framework/core-adapter-accounting-boundary
```

### Expected files

- Rename/update `data/concepts/framework/core_protocol_boundary.edn`
- Update `data/concepts/registry.edn`
- Update references in `data/concepts/framework/held_custody.edn`
- Update references in `data/concepts/yield/yield_bearing.edn`
- Update architecture and generated-concept documentation

### Implementation steps

1. Select one canonical ID and display name.
2. Decide whether prior IDs require a registry alias for persisted references;
   if yes, make the alias read-only and document its sunset/migration policy.
3. Update all source references, docs, concept registry entries, and generated
   output in the same change.
4. Verify links, concept-registry validation, and generated docs.

### Exit criteria

No public documentation characterizes this as a protocol-domain concept. The
name consistently expresses a framework-to-extension ownership boundary.

---

## Follow-up, not a critical/high blocker

### Value at risk

`valid-amount?` correctly limits v1 to non-negative scenario-native integers
and the derived observation revalidates the selected value. Expand tests for
selector traversal, duplicate coordinates, large integers, key normalization,
and persisted-observation tampering.

### Performance-sensitive operations

No PRF performance-sensitive API/claim was found. Before adding benchmark
budgets, identify public per-call operations that need a latency or allocation
contract and define hardware-independent regression methodology.

### Revealing / misalignment / author-neutral

No separate high-risk implementation gap was confirmed beyond the provenance
and related-claims authentication work in WP2. Preserve the distinction between
structural evidence, runtime address-bound authorization, and cryptographic or
research-consensus authenticity in all resulting docs and API names.

---

## Verification gates

Run these gates after each affected package, then run the aggregate gates after
P0 and P1.

| Gate | Required after |
|---|---|
| Focused namespace tests including new negative/tampering cases | Every work package |
| Full yield suite | WP3 |
| Temporal replay and Sew timing suites | WP4 |
| Review-certificate/researcher integration suites | WP5 |
| Stochastic equilibrium plus dispatcher/integration suite | WP6 |
| Architecture and distribution/JAR tests | WP7 and WP8 |
| Canonical scenario/benchmark verification smoke from an unrelated working directory | P0 completion and P1 completion |

Do not mark a capability as authenticated, content-verified, reconciled,
independently recomputable, or wired until its corresponding exit criterion and
negative test matrix pass.
