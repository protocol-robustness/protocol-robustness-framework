# Trace-Equivalence Harness — Maturity & Upgradeability Contract

Status: Phase 0 (claim correctness and coverage). Companion to
`EQUIVALENCE_ATTESTATION.md` and `etc/trace-solidity-manifest.edn`.

## Vocabulary (load-bearing)

These terms must not be used interchangeably:

| Term | Meaning |
|---|---|
| Fixture validation | A fixture is structurally valid (schema) and semantically valid (action/state rules). |
| Fixture sync integrity | A copied fixture is byte-identical (SHA-256) to its Clojure source. **Not** equivalence. |
| Contract replay | The fixture was executed against live contracts by a Forge test and produced a replay receipt. |
| Invariant evaluation | The compiled invariant profile was applied and its equations evaluated during replay. |
| Equivalence attestation | The reviewer-facing claim document, built from replay receipts. |
| Attestation reproduction | Re-deriving the attestation from the bound commits (Phase 3 `--mode reproduce`). |
| Candidate compatibility | Testing a proposed contract/simulator change against an unbound fixture set (Phase 3 `--mode candidate`). |

**`equivalence verified`** is reserved for a trace that:
1. passed schema and semantic validation;
2. was replayed against an identified contract (replay receipt exists);
3. had the identified invariant profile resolved **and applied** (observable);
4. passed the required invariants;
5. has its replay receipt included in the attestation.

Sync integrity alone never establishes `equivalence verified`.

## Phase 0 contract

### Per-trace replay receipts

`TraceEquivalenceTest.sol` emits one receipt per fully replayed fixture under
`<sew-repo>/out/receipts/`. A receipt binds:

- `trace_id` (scenario id) and `fixture_path`;
- `fixture_hash` (keccak256 of the fixture bytes on disk — verified by reconcile);
- `replay_spec_id` (negotiated `cdrs.<v>.schema.<v>.profile.<v>.harness.<v>`);
- `profile_id`, `profile_version`, `profile_applied`, `invariant_evaluations`;
- `replay_status`;
- `extension_resolution_root` (Phase 0 built-in frozen snapshot).

The harness **fails closed** when a v0.2 fixture's profile is not both resolved
and applied with at least one invariant evaluation ("profile-inert" detection).

### Set reconciliation gate

`scripts/reconcile.py` enforces:

```
manifest included traces
   == fixtures selected for replay
   == Forge replay receipts
   == attestation per-trace results
manifest excluded/byte-synced traces
   == explicit, machine-readable records
```

It rejects: included-without-receipt, replayed-but-unclassified fixture,
duplicate fixture paths, receipt/fixture hash mismatch, `:forge-wired` inventory
drift, profile-inert receipts, and receipts for byte-synced-only traces.
Duplicate `trace_id`s are advisory only (the corpus legitimately reuses
scenario ids, e.g. the byte-identical sew-001/ref-005 mirrors).

### Negative tests are non-vacuous

Negative fixtures (n01–n07) carry an invariant profile and must replay fully
then fail on a **semantic** assertion. The tests assert the revert is a forge
assertion abort (selector `0xeeaa9e6f`) whose message contains ` mismatch`, so a
fixture that reverts at the profile gate no longer counts as a passing negative
test.

## Extension-model alignment (reserved in Phase 0)

The harness is a closed-world consumer of the framework extension model.
Executable capabilities (trace actions, invariant profiles, state projections)
will share a common extension descriptor and be resolved into a frozen,
content-addressed snapshot before replay (Phase 1), bound into receipts and the
attestation (Phase 3–4). Fixture specs, schemas, role assignments, the manifest,
and harness versions remain negotiated data/protocol artifacts, not extensions.

Phase 0 reserves the shape:

```clojure
:extension-resolution
{:root "sha256:..."
 :entries [{:extension/id :trace/action.resolve :extension/version "1" :extension/kind :trace/action}
           {:extension/id :trace/profile.equivalence-v1 :extension/version "1" :extension/kind :trace/invariant-profile}
           {:extension/id :trace/projection.sew-v2 :extension/version "2" :extension/kind :trace/state-projection}]}
```

The current capability-aware built-in root is
`sha256:091280b11d9fb3ae220517a7e8e3e4f23a985d650f89ea168a07b71863779e3d`
(the SHA-256 of the canonical serialisation
`id|version|kind|supported-fixture-specs` over the three built-in entries).
The harness recomputes this root and rejects drift.

## Phase 1 — negotiated compatibility

- **Layered identity.** `fixture-spec` = `{:cdrs-version :schema-version}`;
  `replay-spec` = fixture-spec + `:invariant-profile {:id :version}` +
  `:harness-version`. The manifest `:replay-spec/id` is
  `cdrs-0.2.schema-2.profile-1.harness-1` (derived from the fields).
- **Supported-combination registry.** The exact combinations
  `(0.1, 1)` and `(0.2, 2)` are enumerated in the harness, `reconcile.py`,
  `trace-solidity-verify`, and this manifest. Unknown, missing, or
  invalid-combination inputs **fail closed**; key-presence autodetection is
  removed and legacy v0.1 replay is an explicit handler (`cdrs_version: "0.1"`).
- **Extension-resolved support.** Each resolved extension declares the
  fixture-specs it supports; a replay is only valid if every resolved
  extension covers the negotiated fixture-spec. The resolution root encodes
  those support sets, so capability drift changes the root and fails closed.
- **Rejection tests.** `test_version_reject_{unknown_cdrs,missing_cdrs,
  invalid_combo,unsupported_profile}` prove the harness rejects unsupported
  combinations before any action is interpreted.

## Version-bump ownership (preview)

| Change | Version/binding to update |
|---|---|
| Fixture JSON structure changes | `schema-version` |
| Meaning of an existing field/action changes | `cdrs-version` |
| New/changed equivalence assertion | `invariant-profile.version` |
| Harness protocol/dispatch semantics change | `harness-version` |
| Manifest representation changes | manifest version |
| Contract implementation change (no trace-language change) | contract commit + re-attestation |
| Simulator implementation change (no trace-language change) | simulator commit + re-attestation |
| New action with new trace semantics | normally CDRS + schema |
| New role identifier only | schema or role-registry version |

A contract upgrade forces regeneration, replay, and re-attestation — it does
NOT automatically bump CDRS or schema version.

## Commands

```bash
# Emit receipts (also run by the attestation generator):
cd <sew-repo> && mkdir -p out/receipts && forge test --match-contract TraceEquivalenceTest

# Reconciliation gate (execution evidence vs manifest) with explicit claim class:
python3 scripts/reconcile.py --sew-repo <sew-repo> [--mode attested|reproduce|candidate|compare]

# Receipt-derived attestation:
python3 etc/generate-equivalence-attestation.py --sew-repo <sew-repo>
```

## Phase G1 — complete the first profile (delivered)

- **Generic validation contract** (`conformance.validation`): stable result shape
  (`:validation/id|kind|version|status|issues|subject-root|implementation-root`),
  a CLOSED validator registry with `resolve-validator`, and layer rules
  (every required validator resolves; duplicates rejected; required layers
  cannot be skipped; results bind the subject root).
- **Trace-domain validators** (`resolver-sim.trace.conformance.validators` +
  `vocabulary`): `:trace-fixture-v2-schema` (structural, mirrors
  `etc/conformance/schemas/trace-fixture-v2.schema.json`) and
  `:trace-fixture-v2-semantics` (action/role/version/alias rules). The committed
  v2 JSON Schema is at `etc/conformance/schemas/trace-fixture-v2.schema.json`.
- **Observed capability satisfaction** (`conformance.capability`): a capability
  is satisfied only by a SUCCESSFUL receipt for the current subject set
  (declared → resolved → exercised); stale declarations or bypassed validators
  cannot satisfy it.
- **Execution plan** (`conformance.plan`): deterministic, content-addressed,
  topologically validated, complete against the profile's boundaries and
  capabilities; closed step vocabulary.
- **Per-trace coverage** (`conformance.coverage`) + **coverage-bound claims**
  (`conformance.claim/claim-with-coverage`): claims are never emitted from
  aggregate success while individual subject coverage is incomplete.
- **Typed outcomes** (`conformance.outcome`) and **implementation registry with
  completeness proofs** (`conformance.registry`, active/experimental/deprecated/
  orphaned classification).
- **Adoption**: `trace_export` fails closed on conformance validation;
  `clojure -M:conformance-validate` emits per-source conformance receipts;
  `reconcile.py` derives observed capabilities + coverage from receipts and
  emits an explicit claim bound to a plan fingerprint. The attested claim for
  the contract-replayed subject set (10, with 8 explicit exclusions) now passes
  with coverage complete.

## Phase G2a — execution fidelity (delivered)

- **Planned-versus-observed reconciliation** (`conformance.reconciliation`):
  `reconcile` proves the executed receipts correspond exactly to the plan —
  missing, duplicate, unexpected, wrong-subject, and failed-prerequisite
  dependency violations are all detected; skips allowed only when the plan
  models `:skippable?`. The claim binds `:reconciliation/root`, not merely the
  plan fingerprint.
- **Universe / inclusion / exclusion commitments** (`conformance.coverage`):
  `universe-split` enforces included ∩ excluded = ∅ and included ∪ excluded =
  universe, binding `:universe/root`, `:included-subject-set/root`, and
  `:exclusion-set/root` independently. Structured exclusions carry class
  (`:unsupported-capability` / `:out-of-profile` / `:known-implementation-divergence`
  / `:fixture-invalid` / `:not-selected` / `:superseded`), reason, profile root,
  evidence root, and claim effect.
- **Coverage- and reconciliation-bound claims** (`claim-with-evidence`): a claim
  is emitted only when coverage is complete AND the reconciliation passed.
- **Adoption**: `clojure -M:conformance-reconcile` maps the trace pipeline's
  conformance + replay receipts onto the plan steps (by subject id) and emits
  `results/conformance/trace-reconciliation.json`; `reconcile.py` reads it and
  binds the root into the claim. Current result: **pass** — 10 included / 8
  excluded, 0 violations.

## Phase G3a — benchmark reproduction spike (delivered)

- Committed `research-benchmark-reproduction.v1` profile (fixture-contract
  `:research-scenario.v1`, validators, transformations, capability
  requirements, comparison policy `:exact-outcome-reproduction.v1`).
- `resolver-sim.benchmark.conformance.reproduction` — registers the first
  NON-trace validators into the SAME closed registry and implements exact
  outcome-root reproduction (recompute the committed outcome hash and compare).
  **Imports no trace-domain namespace.**
- Deterministic tests: exact root reproduction, closed-registry registration,
  tampered-root rejection, capability receipt.
- Trace-shaped assumptions captured in `etc/conformance/adoptions/
  research-benchmark-reproduction.v1.edn` for G4 (subject coverage, comparison
  policy, role/action = trace-domain, receipt-graph/multidimensional-coverage/
  symmetric-comparison = premature).

## Phase G3b.1 — identity and registry integrity (delivered)

- **Subject identity binding** (`conformance.identity`): first-class
  `subject-identity` (canonical root + domain roots + identity policy + profile
  root). `validate-identities` rejects same-ID-with-inconsistent-canonical-root,
  multiple kinds per ID, receipts whose domain root is not linked to the
  identity, profile-root mismatch, and included/excluded records binding
  different roots for the same ID.
- **Deterministic implementation registry root** (`conformance.registry`):
  `registry-root` is order- and process-independent; duplicate IDs rejected
  (idempotent re-registration allowed), kind mismatches rejected via
  `required-implementations-ok?`, experimental implementations cannot satisfy an
  attested profile unless explicitly allowed. Validator registration now mirrors
  into the implementation registry, and validation receipts + reconciliation
  results bind `:implementation-registry/root`.
- **Two-stage profile validation** (`conformance.profile`): generic core
  `validate-profile` + `validate-profile-domain` dispatched by `:profile/kind`;
  trace and benchmark domain validators registered (fixture-contract,
  domain-contract, comparison-policy coherence).

## Phase G3b.2/3b.3 — benchmark reproduction lineage + honest failures (delivered)

- **Reproduction lineage** (`reproduction-lineage`): binds baseline/reproduced
  lineage fields (scenario/implementation/run/case-set/outcome roots), a
  `must-match`/`may-differ` policy, and `:comparison-result :equal|:diverged|
  :not-evaluated`. `reproduction-claim` binds the lineage root and both run
  identities, and is suppressed unless the comparison is equal AND the
  conclusion is `:established`.
- **Artifact derivation vs execution reproduction**: `:outcome-root-
  recomputation` (recompute an outcome hash) is distinct from
  `:independent-run-production`/`:benchmark-execution` — recomputation never
  counts as execution reproduction.
- **Honest failure matrix**: malformed scenario (schema reject), mismatched
  outcome root, incomplete case set (must-match divergence), inconclusive
  conclusion (claim suppressed) — each reaches a typed outcome without emitting
  a stronger claim.

## Phase G5a-lite — envelope versioning (delivered)

- `conformance.envelope` known schema versions; unknown-version rejection.
- Envelopes carry their schema version in the canonical preimage
  (reconciliation, subject identity, validation receipt, reproduction lineage).
- Golden canonical roots pinned (reconciliation, coverage, identity, registry,
  plan) with drift detection; versioned constants committed.

## Phase G4-close, G5b, G5c, G6 (delivered)

- **G4-close**: committed final classifications
  (`etc/conformance/adoptions/final-classifications.edn`) with adopters,
  generic-namespace, and `:decision-status :promoted` for the shared
  abstractions. `dependency-boundary-test` machine-checks that no generic
  conformance namespace imports a trace/benchmark namespace.
- **G5b — hermetic verification**:
  - `profile-satisfiable?` (installation-level, subject-independent) and
    `profile-executable?` (subject-set/mode preflight; never executes
    validators/handlers) implement the lifecycle `valid → satisfiable →
    executable → …`.
  - `conformance.environment`: hermetic environment receipt with
    committed/informational split; `:environment/root` bound into plans,
    reconciliation, coverage, and evidence-bound claims. Only committed fields
    enter the root.
  - Committed production registry snapshot (`etc/conformance/registry.v1.edn`)
    + `registry/reconcile-registry-snapshot` (missing/unexpected/mismatched/
    status) — an unexpected namespace registration cannot silently alter the
    production root.
- **G5c — portable verification**: `conformance.bundle` build/inspect/verify.
  `verify-bundle` is read-only, recomputes embedded reconciliation/plan roots,
  checks root agreement across envelopes, and derives the claim INDEPENDENTLY
  from bundled evidence. Tests prove: removing the supplied claim yields the
  identical derived claim; tampering the supplied claim is rejected; tampering
  the reconciliation is rejected; unsupported bundle versions fail closed.
- **G6 — third profile**: `evidence-package-admission.v1` proves conformance
  with NO replay, comparison, or reproduction (structural validation, content
  root, reference closure, signature presence, policy admission). Claims are
  kept separate (integrity ≠ closure ≠ authenticity ≠ admissibility).
  `validate-layers` now permits multiple distinct validators of the same kind
  (rejects duplicate validator ids), enabling multi-validator profiles.

## G6b, G7, G7b, G8 — admission assurance, independent verification, adversarial invariants, public surface (delivered)

- **G6b — authenticity exceeds signature presence** (`conformance.crypto`):
  closed algorithm registry (ed25519 via Java), fail-closed signature
  verification receipt separating cryptographic validity, signer authorisation,
  key status, domain separation, and preimage binding. `cryptographically-valid?
  ≠ authorised? ≠ admission`. Evidence-package admission now requires a passing
  signature receipt, not signature presence. Exact reference closure
  (missing/duplicate/unexpected/unresolved) and a first-class admission decision
  receipt mechanically derivable from prerequisite claims + policy root
  (cannot emit `:admit` when a prerequisite is missing, non-claimable, or bound
  to another package root). Honest failure matrix (forged root, wrong preimage,
  unauthorised/revoked key, unknown algorithm, policy reject, inconclusive).
- **G7 — independent verification**: `scripts/bundle_verify.py` is a read-only
  second implementation. Envelope roots (reconciliation, plan, coverage,
  identity, environment, universe) were migrated to **canonical-JSON sha256
  roots** so bundles are offline-verifiable from their serialized form and
  reproducible byte-for-byte in Python. Committed parity fixtures
  (`etc/conformance/fixtures/`) for valid + tampered-claim + tampered-reconciliation.
  Cross-language parity test proves both implementations derive the **identical
  claim root** and both reject tampered evidence.
- **G7b — adversarial invariants**: claim monotonicity, no-laundering-through-
  bundle, identity substitution resistance, environment binding (committed vs
  informational), registry order invariance, version non-confusion, and a
  stable issue-code envelope (`conformance.issue`, classes/severity).
- **G8 — public surface**: minimal read-only CLI
  (`clojure -M:conformance-cli bundle verify|inspect, claim derive, profile
  validate`) with deterministic machine JSON; verifier minimality enforced by
  the dependency-boundary test (no generic conformance namespace imports a
  trace/benchmark/evidence-package namespace).

**Strong completion criterion met for the committed fixtures:** a bundle
produced by one process verifies offline in the second implementation under the
identified environment; no removal, addition, substitution, or relabelling of
evidence produces a stronger claim.

## G9 — Core maturation and externalisation (delivered)

- **Maturity record** (`etc/conformance/maturity.edn`): core declared
  `:feature-complete` at `:conformance/core-version 1` with the three profiles,
  seven supported guarantees, and the four deferred features plus their
  promotion triggers committed.
- **Normative specification** (`docs/conformance/SPECIFICATION.md`): profile
  lifecycle (valid → satisfiable → executable → executed → reconciled →
  covered → claimable), canonical JSON rules, root domain separation, subject
  identity, environment/registry commitments, plan/receipt reconciliation,
  universe partition, claim derivation + parity core, bundle closure,
  cryptographic admission, verifier behavior, and 12 required fail-closed
  conditions — in MUST/MUST NOT/SHOULD/MAY form, with implementation details
  separated as informative.
- **Threat model** (`docs/conformance/THREAT_MODEL.md`): eight adversaries,
  ten protected properties, attack→mechanism table, and explicit non-goals.
- **Claim scope** (`conformance.claim/claim-scope-metadata`): every claim now
  carries `:claim/scope :procedural-conformance` and
  `:claim/does-not-establish`; the parity core is the 5 semantic fields and
  excludes informational metadata so cross-language roots are unchanged.
- **Canonicalisation/verification defect fixed**: `verify-bundle` now
  normalizes the portable JSON string form (reconciliation status, claim
  mode/class/status) so a round-tripped bundle verifies identically to its
  in-memory form — previously bundles reported `:pass` with `claimable? false`
  offline.  The bundle environment envelope, plan, and reconciliation now bind
  the SAME environment root.
- **Canonicalisation + crypto vectors** (`etc/conformance/vectors/`): committed
  preimages (exact canonical bytes), claim/environment/registry roots, and an
  ed25519 vector set (public key, test-only private key, valid signature, and
  the six decision classes); `vectors-test` locks them.
- **Implementation-neutral corpus** (`etc/conformance/corpus/`): manifest +
  cases across valid, invalid/{claim,reconciliation,version,environment,
  identity,schema}; `corpus-test` asserts every case's expected classification
  AND that Clojure and Python agree on every bundle case.
- **Release artifact** (`etc/conformance/release.v1.edn`, generated by
  `scripts/gen_release.clj`): binds source revision, profiles, registry,
  schemas, issues, corpus, vectors, and both verifier artifacts under a single
  reproducible release root; `release-test` proves reproducibility.
- **Historical-verification tests**: old bundle verifies under its committed
  environment; live registry pollution does not rewrite it; later key
  revocation does not silently rewrite history unless the policy explicitly
  makes it retrospective (`:key/status-effective-at`); new verifier derives the
  same old claim root; unsupported historical canonicalisation yields a typed
  `unsupported-canonicalisation` non-claimable result.
- **Compatibility policy** (`docs/conformance/VERSIONING.md`): every change
  class is defined (compatible / minor / envelope / profile / breaking core)
  with a classification table and the fail-closed rules.

**Strong completion criterion met for the committed fixtures:** a bundle
produced by one process verifies offline in the second implementation under the
identified environment; no removal, addition, substitution, or relabelling of
evidence produces a stronger claim.

## G9c — External assurance apparatus (delivered; clean-room pending)

- **Third-language verifier** (`scripts/verify3.mjs`): zero-dependency Node
  verifier implementing the specification independently; vector gate,
  `scripts/corpus_gate.mjs` public-corpus gate, and the stable minimal result
  shape.
- **Clean-room package** (`docs/conformance/CLEAN_ROOM_PACKAGE.md`): the exact
  allowed/forbidden input set for an independent implementer, with the nine
  acceptance gates and an independence attestation requirement.
- **Private holdout corpus** (`etc/conformance/holdout/`, 21 cases): kept out
  of the public corpus root and the release artifact; `scripts/holdout_gate.mjs`
  drives all three verifiers (one batch JVM for Clojure).
- **Protected-property mutation testing** (`scripts/mutation_test.mjs`):
  9 security-relevant mutations, each mapped to a protected property; **9/9
  killed**, every property has an effective test.
- **Differential fuzzing** (`scripts/differential_fuzz.py`): serialization/
  normalization boundary (key order, whitespace, Unicode escapes, numbers,
  nesting, empty collections, extra fields, malformed roots); **20 accept with
  identical root, 7 reject, 0 disagreement, 0 crash**.
- **Resource safety** (`docs/conformance/RESOURCE_SAFETY.md` +
  `resource-safety-test`): bundle size, nesting depth, receipt count, issue
  count, duplicate-key and malformed-input limits → typed rejections, uniform
  across verifiers.
- **Ambiguity log** (`docs/conformance/AMBIGUITY_LOG.md`): seeded with the five
  real ambiguities found during externalisation, each resolved through the
  approved mechanisms.
- **Assurance report** (`docs/conformance/ASSURANCE_REPORT.md`): evaluated
  properties, gate results, defects found, independence achieved (honestly
  marked NOT clean-room: same author), residual risks, and the completion
  gates that remain.
- **External-assurance release** (`etc/conformance/assurance.v1.edn`):
  references `release.v1.edn`, verifier artifact roots, public/holdout corpus
  roots, mutation/fuzz/resource-safety/ambiguity-log roots; verdict
  `:assurance-partial-awaiting-clean-room`.
- **Governance** (`docs/conformance/GOVERNANCE.md` + change-proposal template):
  any protocol change requires a classified proposal meeting the six review
  requirements; frozen features stay behind their promotion triggers.

### Real defects the G9c gates exposed (all fixed, each with a regression case)
- status `pass` with `claimable? false` on JSON round-tripped bundles (all
  three verifiers);
- Python missing the reconciliation-vs-coverage environment-root check;
- `unexpected-receipt` (spec §11) unimplemented everywhere;
- Clojure `contains?` on a JSON round-tripped `authorised-kinds` vector;
- Clojure `update-in` materializing an absent `:claim` as `{}`;
- Clojure never checking plan-vs-reconciliation environment roots;
- Clojure accepting a reconciliation with no root;
- JS claim-core comparison order-sensitive to key order.

### Remaining completion gates (human-external, not author-executable)
1. An independent contributor's clean-room verifier passing corpus + holdout.
2. A human-written ambiguity log resolved through governance.
3. Adoption on one real externally consumed bundle (EF review packet, a
   published benchmark conclusion, or the Sew trace-equivalence attestation).

**Next:** recruit the clean-room implementer against
`docs/conformance/CLEAN_ROOM_PACKAGE.md`; apply the release to one real PRF
assurance bundle.

**Remaining deferred:** full benchmark subject universe, named coverage
dimensions, receipt DAGs, symmetric comparison, and schema migration stay
`:premature` behind their committed promotion triggers; G5 migration utilities
deferred until a real v2 exists.

## Cross-implementation conformance framework

The trace-equivalence harness is the **first concrete profile**
(`:sew-trace-equivalence.v1`) of a generic cross-implementation conformance
pipeline. The shared primitives live in `resolver-sim.conformance.*`; trace
equivalence adopts them without renaming or restructuring the existing system.

| Primitive | Namespace | Purpose |
|---|---|---|
| Claim/verdict taxonomy | `conformance.claim` | `:attested` / `:reproduced` / `:candidate-compatible` / `:accepted-divergence` / `:not-evaluated` are first-class machine states. `claim-result` refuses to emit a class a mode may not claim; wording is always derived from the class, never from an exit code. |
| Capability compatibility gate | `conformance.capability` | Structured `compatible-capabilities?` result (missing / version-conflict / satisfied). Unsupported combinations are "not executable" before replay. |
| Derivation receipts | `conformance.derivation` | Generic boundary receipts (`input/root → output/root` under a named transformation) linked into chains, so "invalid at export" is distinguishable from "stale sync copy" from "unsupported at replay". |
| Conformance profile | `conformance.profile` | Committed descriptor unifying fixture contract, vocabulary registry, actions, roles, projections, invariants, comparison policy, required components, verdict policy. Identifies registered executable implementations by id only. |

**Committed data (single source of truth):** `etc/conformance/claims.edn` and
`etc/conformance/profiles/sew-trace-equivalence.v1.edn`. Non-Clojure tooling
reads them via `scripts/edn.py` (minimal EDN reader); the authoritative
interpretation lives in the Clojure namespaces.

**Adoption in the trace pipeline:** `reconcile.py` takes `--mode` and emits an
explicit `claim` block (never implied by the exit code). It also runs the
capability gate against the committed profile. The gate currently reports
`:semantic-validation` as **missing** — that is the Phase-2 semantic validator,
and the honest state is surfaced rather than claimed.

**Guardrails (not built):** no dynamic plugin loader, no universal fixture
schema, no generic action execution language, no single registry that erases
action/role/profile distinctions, no repository-independent build orchestrator,
and no umbrella attestation artifact until receipts and profiles are stable.
