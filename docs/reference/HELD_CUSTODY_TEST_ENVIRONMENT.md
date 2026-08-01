# Held-custody test environment

## Purpose

This document defines a reusable test environment for held adjustments,
parameter attribution, force-authorisation, related-claims consumption, custody
artifacts, and replay.

The environment must make the trust level of each test explicit. In particular,
a test that directly constructs a world or a grant record is useful for local
invariant testing, but it is not evidence that a public Sew action can produce
or safely consume that record. A restricted Sew governance grant is authentic
runtime authorisation, but it is not itself researcher-consensus authorisation.

## Versioned environment profile

The environment is selected by a hashable capability/configuration artifact,
not merely by a support namespace. Its profile hash is referenced from the
existing run or execution manifest; run seeds, transcripts, outcomes, and
evidence roots are deliberately not profile fields.

```clojure
{:artifact/type    :test-environment-profile
 :artifact/version 1
 :profile/id       :held-custody-force-auth-v1
 :profile/trust-level :consensus-authenticated-public

 :protocol {:protocol/id :sew
            :snapshot/id ...
            :snapshot/root ...}
 :construction {:world/mode :public-actions
                :action/entrypoint :resolver-sim.protocols.sew/apply-action
                :deterministic? true}
 :governance {:governance/mode :restricted
              :governance/identity ...
              :authorization/assurance :address-bound}
 :consensus {:consensus/type :researcher-force-authorisation
             :policy/root ...
             :review-round/root ...
             :membership/root ...
             :threshold ...
             :signature/scheme :ed25519}
 :custody {:primitive :held-adjustment
           :ledger/origin :zero
           :ledger/complete? true
           :artifact/schemas [2 3]}
 :parameter-attribution {:verification :structural
                         :resolution :not-modelled
                         :value-check :not-modelled
                         :amount-check :not-modelled}
 :profile/checks [...]
 :profile/limitations [...]
 :profile/hash ...}
```

The profile builder must derive its assurance level from validation. A supplied
`:profile/trust-level :consensus-authenticated-public` is invalid unless the
consensus roots, threshold, canonical membership, signatures, public Sew
bindings, and required checks all validate. Test signing private keys are
fixture material only and must never occur in this artifact.

## Assurance levels

| Level | Meaning |
|---|---|
| `:mechanism-synthetic` | Direct records or world construction; verifier/local-guard coverage only. |
| `:public-address-bound` | Restricted configured governance identity reaches Sew `apply-action`; grant is runtime authenticated. |
| `:consensus-authenticated-public` | Signed researcher consensus is validated and bound into the public Sew authorisation and execution. |
| `:scenario-evidence` | Runner-produced replayable scenario evidence additionally validates the selected profile. |

## Current test environment audit

The repository already has the necessary building blocks:

| Layer | Existing facility | Strength | Limitation |
|---|---|---|---|
| Core unit tests | `clojure.test` under `test/` and `protocols_src/test/` | Fast and direct | Held-custody setup is repeated across namespaces. |
| Protocol fixtures | `resolver-sim.protocols.sew.snapshot-fixtures` | Canonical module snapshots | No shared held-custody / force-authorisation fixture package. |
| Protocol actions | `resolver-sim.protocols.sew/apply-action` | Exercises actor resolution, governance gates, action validation, and lifecycle wiring | Several existing tests use direct record construction for narrow invariant coverage. |
| Accounting/replay | `add-held`, `sub-held`, custody artifact builders, `replay-held-adjustment-state` | Tests canonical ledger and derived views | Callers must choose whether a history is complete and zero-origin. |
| Invariants | `check-all`, custody closed-form checks, lifecycle and related-claims closure checks | Detects cross-record drift | Not every focused test runs all applicable checks. |
| Generative tests | `test.check` in `properties_test.clj` | Existing sequence/invariant convention | No generator currently focuses on held-adjustment provenance and authorisation transitions. |
| Scenario/benchmark runners | `scripts/run_sew_tests.clj`, `scripts/test.sh`, benchmark/scenario suites | Production-shaped replay and evidence paths | Too slow and broad for every provenance regression. |

The current setup is authentic for the main public single-claim
force-authorisation flow and, after the related-claims public action addition,
for member-scoped force authorisations. Those tests establish
`:public-address-bound`, not researcher consensus. It is incomplete as a *test
environment* because fixture construction, provenance values, negative
mutations, consensus fixtures, and observation bundles are not centralized.

## Design principles

1. **Public path first.** A test claiming command-level behaviour must begin at
   `apply-action`, not by inserting an authorisation record.
2. **Synthetic data is labelled.** Directly constructed records are allowed
   only in `:mechanism` tests that name the bypass and test a verifier or local
   guard.
3. **One canonical observation bundle.** Every held-custody integration test
   should be able to inspect the adjustment, artifact, replay state, ledger
   index, authorisation record, consumption entry, and invariant results.
4. **Fresh immutable worlds.** Builders return new values; no global atom,
   wall clock, random UUID, live map, or filesystem state participates in a
   standard test.
5. **Deterministic time and identities.** Test time, account addresses,
   governance identity, workflow IDs, and parameter roots are declared in the
   fixture, not inferred from process state.
6. **Separate structural provenance from policy correctness.** The environment
   tests pair validity, persistence, hash binding, scope binding, replay, and
   tamper rejection. It does not claim parameter resolution or amount
   correctness.

## Proposed namespace

Add a test-only support namespace:

```clojure
resolver-sim.protocols.sew.held-custody-test-env
```

Suggested location:

```text
protocols_src/test/resolver_sim/protocols/sew/held_custody_test_env.clj
```

It must only be present on the `:test` classpath and must not be imported by
production namespaces.

### Public support API

```clojure
;; Fixed identities and deterministic contexts
(test-context)
(governance-context)
(execution-context)

;; Canonical fixture values
(parameter-fixture opts)
(parameter-root fixture)
(authoritative-parameter-context root)
(interim-parameter-context id)
(parameter-address id)

;; World construction -- public builders retain their action transcript.
(public-empty-held-world opts)
(public-escrow-world opts)
(public-disputed-world opts)
(public-related-disputed-world opts)

;; Mechanism-only builders are deliberately named as synthetic.
(synthetic-held-world opts)
(synthetic-disputed-world opts)

;; Public action adapters
(grant-force-authorisation world opts)
(grant-related-claims world opts)
(grant-related-claims-force-authorisation world opts)
(execute-force-authorisation world opts)

;; Observation and verification
(held-observation world)
(verify-held-observation observation)

;; Test-only corruption helpers
(tamper-adjustment world adjustment-id f)
(tamper-artifact world adjustment-id f)
(tamper-authorisation world auth-id f)
(tamper-consumption world auth-id member-scope-hash f)
```

The support API must return data and ordinary Sew action results. It must not
wrap failures in test assertions; test namespaces retain control of their
expected outcome. `parameter-fixture` produces a deterministic canonical EDN
parameter snapshot and `parameter-root` hashes that snapshot with the project's
canonical reference rules; it must not hash an arbitrary label. This makes an
authoritative context an authentic content commitment without claiming
parameter resolution or amount correctness. Public builders retain an action
transcript proving how each state was reached; synthetic builders may not be
used to claim public-path coverage.

## Canonical fixture shape

`held-observation` should return a stable inspection map:

```clojure
{:world/before world-before
 :world/after world
 :actions/transcript transcript
 :governance/provenance governance-provenance
 :authorization/assurance authorization-assurance
 :held/adjustments (:held-adjustments world)
 :held/artifacts (:held-artifacts world)
 :held/index (:held-ledger/index world)
 :held/total (:total-held world)
 :held/positions (:held/positions world)
 :force/authorisations (:force-authorisations world)
 :force/consumed (:force-authorisations/consumed world)
 :force/consumption-records (:force-authorisations/consumption-records world)
 :consumption/receipts consumption-receipts
 :consensus/checks consensus-checks
 :authorization/checks authorisation-checks
 :related-claims/checks related-claims-checks
 :replay/result (custody/replay-held-adjustment-state adjustments)
 :replay/error nil-or-ex-data
 :custody/checks closed-form-checks-or-ex-data
 :invariants (inv/check-all world)}
```

For an incomplete ledger, the fixture must return the replay opening state
explicitly rather than silently replaying from zero. For a complete ledger it
must set:

```clojure
[:params :held-adjustments/complete?] true
```

and use a zero-origin sequence.

## Test tiers

### Tier 0 — pure value contracts

**Namespaces:**

- `resolver-sim.assurance.parameter-attribution`
- `resolver-sim.accounting.held-adjustment`
- `resolver-sim.assurance.custody`
- `resolver-sim.assurance.force-authorisation`

**Purpose:** Validate canonical forms, exclusive alternatives, projections,
hash preimages, and compatibility behavior without a Sew world.

Required cases:

- absent, valid root, valid interim, semantic-ID, and path attribution;
- one-sided, mixed-form, invalid-root, nested-path, and unknown-key rejection;
- legacy scope projection parity when provenance and position binding are
  absent;
- new scope changes for changed position, context, address, or instance;
- v2/v3 artifact classification and malformed-artifact rejection.

### Tier 1 — accounting adapter tests

**Entry points:** `add-held`, `sub-held`.

**Purpose:** Test admission, ledger append, index update, artifact construction,
and direct replay equivalence.

Required cases:

- ordinary in/out adjustment with no provenance;
- attributed in/out adjustment with both context forms and both address forms;
- `:extra` reserved-key rejection;
- artifact context/address tampering;
- replay rejection of malformed adjustment provenance;
- index equivalence while only provenance changes;
- position underflow and token underflow.

### Tier 2 — public runtime single-claim force-authorisation

**Entry points:** `grant-force-authorisation`,
`execute-force-authorised-action`.

**Purpose:** Exercise governance authentication, action parameter validation,
scope construction, execution, finalization, held adjustment, and consumption.

Required cases:

- grant/execution with no provenance;
- provenance A at grant and A at execution succeeds;
- changed context, changed address, removed pair, added pair, and changed
  position each reject before custody mutation;
- expired, revoked, wrong workflow, wrong direction, and duplicate consumption
  reject;
- legacy pre-position grant executes against its original preimage while a new
  grant is position-bound;
- correct governance role plus configured address succeeds with a hash-bound
  `:authorization/assurance :address-bound` classification;
- correct role/wrong address, correct address/wrong role, missing identity, and
  unresolved actor reject;
- legacy or open governance modes are explicitly classified as weaker
  `:legacy`/`:mechanism` fixtures, never as public address-bound assurance;
- arbitrary resolved actors may execute an already scope-locked permit only if
  that remains the explicit execution policy.

### Tier 3 — public related-claims force-authorisation

**Entry points:** `grant-related-claims`,
`grant-related-claims-force-authorisation`,
`execute-force-authorised-action`.

**Purpose:** Test relationship → member scopes → member executions → immutable
consumption records → terminal completion.

Required cases:

- two-member grant, one member consumed, then both members consumed;
- duplicate member attempt rejected;
- member scope substitution rejected;
- reordering records does not alter outcome;
- record/adjustment provenance insertion, deletion, or substitution rejected;
- changed relationship hash, grant member set, or stored scope hash rejected;
- an inactive relationship or non-disputed member cannot be granted;
- creator provenance has address-bound governance assurance;
- relationship V2 hash, frozen member identities, creator provenance, grant
  relationship ID/hash, consumption records, linked adjustments, and
  related-member hashes are independently recomputed;
- creator-provenance and membership substitution reject, not only a changed
  relationship hash.

### Tier 4 — researcher-consensus force-authorisation

This is the required tier for `:consensus-authenticated-public`. It uses the
existing `resolver-sim.benchmark.researcher-force-authorisation` validators and
`resolver-sim.benchmark.force-authorised-execution-evidence` profile builder.

The test flow is:

```text
frozen review-round membership
→ canonical member indices
→ deterministic test-key Ed25519 decisions
→ threshold evaluation
→ researcher force authorisation
→ reservation and manifest
→ public Sew grant scope binding
→ public Sew execution
→ consumption receipt
→ held adjustment and custody artifact
→ force-authorised execution evidence-profile recomputation
```

Required cases:

- threshold approval with dissent;
- declined authorisation;
- wrong signer, substituted public key, invalid signature, wrong policy, wrong
  review round, wrong request root, and wrong target;
- expiry and inclusive time-boundary behavior;
- researcher authorisation to Sew grant scope equality;
- reservation, manifest, and consumption-receipt correlation with the resulting
  held adjustment and artifact;
- package-level recomputation through
  `verify-package-force-authorised-execution`.

### Tier 5 — replay and evidence verification

**Purpose:** Verify persisted worlds independently from their mutation path.

For each Tier 2–4 happy-path world, create independent copies and tamper only:

- adjustment context;
- adjustment address;
- artifact context;
- artifact address;
- artifact hash;
- grant scope;
- grant scope hash;
- consumption record context/address;
- relationship hash;
- member scope hash.

Expected outcomes are precise:

| Surface | Primary expected failure |
|---|---|
| Adjustment pair | replay rejects before index reconstruction |
| Artifact pair/hash | custody closed-form verification fails |
| Grant scope/hash | force lifecycle invariant fails |
| Consumption record | related-claims scope-closure invariant fails |
| Relationship/member set | lifecycle and scope-closure invariants fail |

### Tier 6 — generated state-machine tests

Add focused `test.check` generators for bounded sequences:

```text
create → dispute → create relationship → authorise relationship
→ consensus decision → grant → execute member 0 → execute member 1
```

Generate optional valid provenance, derivation inputs for positions, ordering,
time advancement, revocation, expiry, and one selected mutation. Sew derives
position IDs; generators must not supply arbitrary position IDs.

After every accepted step, assert the applicable world and transition
invariants. A rejected public action must compare an explicit economic-state
projection, not necessarily the entire world: a permitted diagnostic or audit
delta may be recorded separately. Generators must emit a seed and shrunk trace
in the failure message. They should not generate arbitrary invalid world maps;
invalidity belongs in Tier 5 mutation tests.

### Tier 7 — scenario and benchmark acceptance

Use existing runner infrastructure for release-facing evidence:

```sh
bb test:unit
bb test:invariants
bb test:suites
```

The held-custody environment should contribute a compact scenario suite, not
replace existing broad suites. Each scenario must use public actions and emit a
replayable evidence bundle. Benchmarks remain separate evidence products, not
unit test fixtures.

## Test naming and classification

Use name prefixes or metadata so reviewers can distinguish assurance level:

```clojure
(deftest ^:pure parameter-address-alternatives-are-exclusive ...)
(deftest ^:adapter add-held-rejects-provenance-in-extra ...)
(deftest ^:public force-authorisation-binds-position-and-provenance ...)
(deftest ^:replay tampered-address-rejects-replay ...)
(deftest ^:adversarial related-consumption-record-cannot-be-relabeled ...)
```

A direct world-map fixture must say `synthetic` or `mechanism` in its test name
and must not be used as evidence for public command reachability.

## Execution profile

| Command | Intended use |
|---|---|
| Focused namespace invocation | edit-loop feedback for one contract boundary |
| `bb test:unit` | merge gate for unit, adapter, public-action, and property tiers |
| `bb test:invariants` | scenario/replay invariant gate |
| `bb test:suites` | canonical fixture and evidence-suite gate |
| `bb test` | release-level aggregate gate |

The test environment should use the repository's existing artifact-locking and
noop-capture conventions rather than starting servers or writing shared paths.

## Implementation sequence

1. Define and validate `test-environment-profile.v1`; derive its assurance
   level and hash it without test private keys.
2. Add `held-custody-test-env` with deterministic identities, real content-root
   parameter fixtures, public-action adapters, transcripts, and
   `held-observation`.
3. Migrate repeated setup from `accounting_test.clj` and
   `force_authorisation_test.clj` incrementally; do not rewrite focused
   assertions.
4. Add the Tier 4 deterministic-key consensus chain and bind its verified
   authorisation to public Sew grant/execution scope.
5. Add the Tier 5 parameter-provenance and consensus tamper matrices.
6. Add a bounded Tier 6 property generator and seed reporting.
7. Register only fast tests in the unit runner; retain bundle/scenario coverage
   in existing scenario and suite targets.

## Non-goals

This environment does not:

- resolve parameters or validate parameter values;
- prove economic policy selection or amount consistency;
- model external token balances beyond existing Sew abstractions;
- replace scenario, benchmark, or forensic evidence runners;
- make `with-bounty` a held-custody flow. Bounty-plan tests remain separate and
  test its own payable/backing/receipt contract.
