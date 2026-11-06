# Luna handoff: stablecoin privacy policy

Design: [Stablecoin privacy policy and sensitivity](STABLECOIN_PRIVACY_POLICY.md).
Status: partially implemented in a pending, offline candidate. L1 has draft
concepts and a binding inventory. L2 and L4 include rooted requests, signed
classification/evidence checks, restrictive multi-policy evaluation, and
recomputable receipts. L3 has a fixture-input resolver for signed scope
bindings and fresh signed head observations; authenticated production trust and
configuration integration remain blocked. L5 adds inspect/evaluate/verify CLI
commands and a synthetic reproducible example. L6 adds a CAS-fenced
in-memory-only simulated effect. None of these establishes production policy
authority, live currentness, or operation authorization. Do not treat passing
tests as policy approval or qualification. Work only in the isolated
candidate. Use workspace commit identity and JJ workflow. Do not push without
the user's instruction. Each task can be a separate commit.

| Task | Current status | Remaining boundary |
|---|---|---|
| L1 Concepts and binding seam inventory | Partial | Concepts and inventory exist; no authorized production binding seam or trusted head source is established. |
| L2 Immutable policy and evaluation contracts | Partial | Rooted policy/request/receipt checks exist; authority approval and full proposed schema/registrations remain. |
| L3 Trusted policy resolution | Partial | Signed fixture binding/head resolver validates exact authority references, a bounded caller-supplied checkpoint, single-step continuity, and freshness relative to supplied trust policies. External trust anchoring, durable checkpoint history, and production configuration binding remain unqualified. |
| L4 Privacy evaluator and Sentinel composition | Partial | Exact report/evidence checks, deny-overrides, all-policy permit, and Sentinel composition exist; real authority and actual data-view integration remain. |
| L5 Offline commands and synthetic stablecoin example | Partial, offline | Inspect/evaluate/verify and aggregate allow/deny fixtures exist. Private-identity export denial and policy-version historical verification are not implemented or demonstrated. |
| L6 Enforcement boundary qualification | Simulated, bounded | In-memory CAS boundary fences one process instance and records equivocation in its local state. Restart, cross-process, and durable replay guarantees are not implemented; no live disclosure, storage, issuer, or token adapter exists. |

Read the linked design before each task. Fixture keys and policy owners are
synthetic; do not claim real authority, regulatory compliance, or live currentness.
Use existing repository validation and canonical registration conventions.

## L1 — Concepts and binding seam inventory

Dependencies: none. Own new concept files, their registry/mapping entries, and
an inventory note beside this handoff. Do not edit runtime code.

Inspect `data/concepts/registry.edn`, `data/concept-mappings/`,
`data/concepts/assurance/evidence_backed_classification.edn`, configuration and
authority resolution, and domain-qualified asset identity. Add draft concepts
for stablecoin context, privacy policy, and current policy resolution. Record
exact reusable functions, schemas, signing roles, head stores, registration
files, and the supported configuration binding seam. Distinguish classification,
policy authority, observed currentness, and enforcement.

Acceptance: concept validator passes; every related concept resolves; mappings
point to real mechanisms or explicitly describe proposed capability. The note
identifies how an authorized binding becomes active and how a current observation
is trusted. If a new configuration version is needed, write that focused proposal
and leave activation blocked; do not invent a new authority store.

## L2 — Immutable privacy policy and evaluation contracts

Dependencies: L1's identity/binding decisions. Own
`src/resolver_sim/privacy/policy.clj`, focused contract tests, and required
schema/artifact/hash registry additions. Follow the repository's naming rules if
the inventory identifies a more appropriate existing namespace.

Implement canonical policy validation/rooting and request/receipt contracts from
the design. Validate finite vocabularies, validity intervals, scope, retention
units, duplicate/ambiguous rules, and explicit authority references. Reuse the
existing canonical artifact/hash/signature contracts; do not implement crypto.

Acceptance: deterministic roots across map ordering; semantic mutation changes
identity; malformed/unknown fields and negative retention fail; receipt binds
every authorization-relevant request field. Focused tests cover these failures.
No real credentials or personal data appear in fixtures or receipts.

## L3 — Trusted policy resolution

Dependencies: L1 binding seam and L2. Own `src/resolver_sim/privacy/resolution.clj`,
focused tests, and the approved binding adapter. Do not modify risk policy V4.

Resolve only from the trusted observed head through retained bindings and policy
bodies. Reuse authority/signature checks identified in L1. Require exact issuer,
asset, jurisdiction, operation, validity, and trust context. Implement historical
versus current-observed reporting; use injected time and observation inputs.

Acceptance: reject substituted caller policy, wrong issuer/domain/jurisdiction,
missing bodies, hash mismatch, invalid authority, expired policy and stale head
observation. Historical replay remains reproducible after a head update without
claiming present applicability. If binding activation is unavailable, provide a
fixture adapter and explicitly report the production integration as blocked.

## L4 — Privacy evaluator and Sentinel composition

Dependencies: L2 and L3. Own `src/resolver_sim/privacy/evaluation.clj` and focused
tests; reuse Sentinel and canonical report provenance without changing defaults.

Implement pure explicit-rule matching: default deny, deny overrides, all applicable
policies must permit, and all required evidence must verify. Bind recipient,
purpose, view, sink and retention. Compose with existing sensitivity gates and
signed remote decision verification. Preserve sensitivity floors and overrides.
Return deterministic receipts with separate status dimensions.

Acceptance: cover every row of the design's acceptance table relevant to pure
evaluation. Include private identity/public transfer distinction, unknown scan
status, Sentinel denial, missing remote signature, and attempted reuse with a
changed recipient, purpose, or artifact. An allow without qualified currentness
remains offline evaluation and cannot become live authorization.

## L5 — Offline commands and synthetic stablecoin example

Dependencies: L4. Own command handler/registry/help entries and
`examples/use-cases/stablecoin-privacy/`. Inspect current command conventions
before choosing syntax; proposed actions are `privacy policy inspect`,
`privacy evaluate`, and `privacy verify`.

Expose inspect, evaluate and independently verify using pinned local bundles.
The current example demonstrates an allowed aggregate and a denied unregistered
recipient. It does not yet demonstrate private-identity export denial or a policy
version update with historical verification; L5 remains partial until those
scenarios have executable checks. Historical verification must use the exact
retained policy and caller trust context bound to that historical receipt. It
must never silently substitute the current policy or current trust context.
Keep sensitive diagnostics private and use stable exit codes. Output exact policy
roots, separate statuses, reasons and `not-executed`.

Acceptance: documented commands run against synthetic fixtures; negative cases
return failure; tampering with policy, recipient, view or receipt is detected;
public output excludes private fixture fields. Link the example and design from
the appropriate existing documentation indices.

## L6 — Enforcement boundary qualification and handoff

Dependencies: L5. Own integration qualification fixtures/tests and delivery notes.
Keep V0 offline; implement a deterministic simulated operation boundary that
rechecks the active head immediately before its simulated effect. If packaged
as an extension, obey ADR-0005 and the versioned core API; request reviewed API
additions rather than importing core internals from extension runtime source.

Acceptance: head advance between evaluation and simulated use blocks the effect;
denied/indeterminate/unsigned/replayed or context-substituted decisions cannot
produce it. Document which adapters would be required to protect real exports,
issuer operations and retention. Run focused suites and required registry/package
checks, record exact commands/results, changed files and remaining external trust
requirements. No live deployment or fabricated governance approval is included.

## Execution order

L1 → L2 → L3 → L4 → L5 → L6. Finish each task's acceptance checks and report its
limitations before marking it complete. Do not infer policy authority from a
fixture signature or infer deployment readiness from passing offline tests.
