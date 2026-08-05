# Conformance Framework — Normative Specification

Status: normative for `:conformance/core-version 1`.  This document defines the
protocol.  It uses the key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT,
SHOULD, SHOULD NOT, RECOMMENDED, MAY in accordance with RFC 2119.

The purpose of the framework is **procedural conformance**: it determines
whether declared procedures and bundled evidence support a permitted claim
label.  It does not determine whether the underlying research model, theorem,
contract, or policy is correct (see Threat Model, Non-goals).

## 1. Scope

The framework covers three supported profiles:

- `:sew-trace-equivalence.v1` — equivalence of a solidity execution trace against
  a reference implementation under a declared evaluation mode.
- `:research-benchmark-reproduction.v1` — reproduction of a research benchmark
  outcome, with baseline and reproduced lineage.
- `:evidence-package-admission.v1` — cryptographic admission of an evidence
  package (no replay, comparison, or reproduction).

A profile is a committed EDN/JSON document that declares its core schema,
schema validators, claim schema, claim classes, coverage dimensions, and
implementation references.  See `etc/conformance/profiles/`.

## 2. Profile lifecycle

A profile MUST progress through the following states in order.  A profile that
does not reach a state MUST NOT be treated as having reached it, and no claim
MUST be derived from a profile in an earlier state.

```
valid → satisfiable → executable → executed → reconciled → covered → claimable
```

- **valid**: the profile document loads, its core schema is a known schema
  version, and its declared implementations exist.
- **satisfiable**: the profile's preconditions can be met by the current
  environment (capabilities declared by registered implementations).
- **executable**: the profile can produce a plan.
- **executed**: the plan's steps have receipts.
- **reconciled**: the planned and observed steps agree (a reconciliation
  receipt exists).
- **covered**: the reconciliation is covered by a universe partition (every
  subject is included or explicitly excluded).
- **claimable**: a claim class is permitted for the evaluation mode and the
  claim is bound to the reconciliation and environment roots.

A claim MUST only be emitted when the profile is in the `claimable` state.

## 3. Canonical JSON rules

Canonical JSON is the cross-language root format.  A verifier MUST use exactly
these rules so roots are byte-identical across implementations:

1. JSON object keys MUST be sorted lexicographically (byte order).
2. Object keys MUST use the `namespace/name` form with a forward slash when the
   originating name is namespaced (e.g. `claim/class`).
3. Strings MUST be UTF-8 encoded.
4. No optional whitespace.
5. Floating point and integer forms MUST be serialized per JSON, with integers
   written without a fractional part.
6. `null` values MUST be preserved (keys with `null` values are kept).
7. Sets MUST NOT appear in a canonical preimage; arrays MUST be used and MUST
   preserve order.
8. The hashing algorithm is SHA-256 over the canonical UTF-8 bytes, hex-encoded
   lowercase.

A root MUST be presented in the form `sha256:<64 lowercase hex chars>` when it
is a portable content root.  The canonicalisation version is
`canonical-json-sha256.v1`.

## 4. Root domain separation

Every envelope has a content root.  Roots MUST be derived over the envelope's
**committed fields only**.  Informational fields MUST NOT enter any root.

- Reconciliation root: canonical-JSON root of the reconciliation envelope
  (plan root, step receipts, status, environment root, universe partition).
- Coverage root: canonical-JSON root of the coverage envelope.
- Subject identity root: canonical-JSON root of the identity envelope.
- Plan root: canonical-JSON root of the plan envelope.
- Registry root: canonical-JSON root of the sorted committed implementation
  registry entries.
- Environment root: canonical-JSON root of the environment's committed fields
  (profile root, registry root, schema catalog root, canonicalisation identity).
- Claim json-root: canonical-JSON root of the claim **parity core** (see 10).
- Bundle root: content root of the bundle's committed fields.

MUST NOT: different domains MUST NOT share a root preimage.  A reconciliation
preimage MUST NOT equal a coverage preimage merely because both are canonical
JSON; the preimages are the domain-specific envelopes and the envelopes are
structurally distinct.

## 5. Subject identity

A subject identity envelope binds:

- `subject/id` — a stable identifier for the subject.
- `subject/kind` — the kind of subject (e.g. trace, benchmark, package).
- `subject/canonical-root` — the content root of the subject artifact.
- `subject/domain-roots` — domain-specific roots (e.g. solidity sources).

An identity envelope MUST carry its schema version.  Two identities with the
same `subject/id` MUST NOT have different `subject/canonical-root`; a verifier
MUST reject such a set as `inconsistent-canonical-root`.  An `id` MUST NOT be
substituted for a root: IDs identify, roots commit.  Claims MUST bind roots,
never IDs alone.

## 6. Environment and registry commitments

- The **environment** binds a snapshot of everything the verification depended
  on: profile root, implementation-registry root, schema catalog root, claim
  policy catalog root, canonicalisation identity.
- The **registry** is the committed set of registered implementations.  The
  registry root MUST be invariant to registration order and process state; it
  MUST be a function of the sorted entries only.
- A bundle MUST carry its own environment envelope.  A verifier MUST use the
  **bundled** environment snapshot; it MUST NOT substitute the verifier's
  current registry or environment state.
- Environment roots are split into **committed** (enter roots) and
  **informational** (do not enter roots).  An environment receipt MUST record
  both but MUST distinguish them.

## 7. Plan and receipt reconciliation

- A plan is a deterministic ordered set of steps.  Each step declares its
  required and produced artifacts.
- A receipt attests that a step ran and produces the declared artifact root.
- Reconciliation MUST compare planned and observed steps: the set of planned
  step ids MUST equal the set of observed step ids, each observed step MUST
  produce the artifacts the plan requires of it, and a receipt MUST be bound to
  the plan root.
- A reconciliation is **passed** only when the planned-vs-observed comparison
  succeeds.  A passed reconciliation MUST NOT claim anything about outcomes
  beyond procedural agreement.
- Reconciliation status, environment root, plan root, and the universe
  partition MUST be committed fields of the reconciliation envelope.

## 8. Universe partition and exclusions

- A coverage receipt partitions the subject universe into an included set and
  an explicit exclusion set.
- Every subject in the universe MUST be in exactly one of the included set or
  the exclusion set, otherwise the partition is invalid and MUST NOT be
  claimable.
- Exclusions MUST be recorded by `subject/id` with their subject-set root.
- Coverage is **complete** only when every required subject is validated,
  executed, and compared (as applicable to the profile), or explicitly
  excluded.
- A claim MUST NOT be emitted unless the coverage envelope reports
  `coverage/complete? true`.

## 9. Evaluation modes and claim classes

Evaluation modes:

- `:attested` — execution was run and observed; the claim class is `attested`.
- `:reproduce` — execution was reproduced against a reference; the claim class
  is `reproduced`.
- `:candidate` — execution was run under a candidate implementation; the claim
  class is `candidate-compatible` (or `accepted-divergence` on declared
  divergence).
- `:compare` — comparison of two implementations.
- `:not-evaluated` — no evaluation; MUST NOT produce a permitted claim.

MUST NOT: a claim class MUST be **derived** from the evaluation mode and
outcome, never authored by a caller.  The claim class MUST be permitted for the
mode; a request for a disallowed class MUST fail closed (throw or reject).

## 10. Claim derivation and parity core

- A claim MUST be derived **independently from the bundled evidence only** —
  never from the supplied claim.
- The claim parity core is exactly the fields:
  `evaluation/mode`, `claim/class`, `claim/status`, `reconciliation/root`,
  `environment/root`.
- The claim `json-root` MUST be the canonical-JSON root of the **parity core**.
  Informational metadata (`claim/scope`, `claim/does-not-establish`) MUST NOT
  enter the parity core.
- A verifier MUST compare the supplied claim against the derived claim using
  the parity core.  A mismatch MUST reject the bundle
  (`derived-claim-mismatch`).
- A claim MUST bind the reconciliation root (proof the declared plan was
  followed), not merely the plan fingerprint.
- Every claim MUST carry `claim/scope :procedural-conformance` and
  `claim/does-not-establish` listing model correctness, economic safety,
  absence of undiscovered bugs, and truth of all research interpretations.

## 11. Bundle closure

A conformance bundle MUST contain everything required to verify a claim:

`bundle/schema-version`, `profile`, `environment`, `subject-identities`,
`plan`, `validation-receipts`, `capability-receipts`, `execution-receipts`,
`reconciliation`, `coverage`, `exclusions`, `claim`.

- A bundle MUST be **closed**: a verifier MUST resolve every receipt from the
  bundle.  A verifier MUST NOT resolve missing receipts by running domain code
  and MUST NOT regenerate or repair anything.
- A bundle MUST carry a schema version.  A verifier MUST reject an unsupported
  schema version with a typed `unsupported-bundle-version` result; it MUST NOT
  guess or attempt partial verification.
- The supplied claim's `json-root`, when present, MUST equal the canonical root
  of the derived parity core, otherwise `claim-json-root-mismatch`.
- Removing evidence MUST NOT strengthen a claim (claim monotonicity): fewer or
  weaker evidence MUST NOT yield a stronger claim class.
- Adding unexpected evidence MUST NOT improve claimability: every receipt in
  the bundle MUST be covered by the plan, the profile, or the admission policy,
  otherwise the bundle MUST be rejected.

## 12. Cryptographic admission

- Signatures MUST use a closed algorithm registry.  For `:conformance/core-version 1`
  the only supported algorithm is `ed25519`.
- A signature-verification receipt MUST separate:
  1. **cryptographic validity** — the signature verifies under the public key
     over the exact canonical preimage;
  2. **signer authorisation** — the signer is authorised by the applicable
     trust policy for the artifact kind;
  3. **admission** — the package is admitted by policy.
- MUST fail closed on: wrong preimage, unauthorised signer, revoked key,
  expired key, not-yet-valid key, unknown algorithm, unresolved signer, and
  domain mismatch (`signature/domain`).
- A signature MUST NOT cross artifact domains: the signature domain MUST be
  bound to the artifact kind.
- Artifact integrity MUST NOT imply authenticity, and authenticity MUST NOT
  imply admission.  Each is an independent gate.
- An admission decision receipt MUST be mechanically derivable from the
  prerequisite claims and the policy root; it MUST NOT be authorable
  independently of its prerequisites.

## 13. Verifier behavior

- A verifier MUST be read-only: it MUST NOT mutate the bundle, its inputs, or
  its environment.
- A verifier MUST report a machine classification: status
  (`pass | rejected | unsupported-version`), claimability, and a set of typed
  issue codes.  Issue codes are stable identifiers; issue text is
  non-normative.
- A verifier MUST implement the normative rules above without importing domain
  execution code (verifier minimality).
- A verifier MUST distinguish `pass` (claimable) from `pass`-without-claimable
  only by the claimability flag; a `pass` status with `claimable? false` is a
  contradiction and MUST NOT occur.
- A verifier SHOULD expose the independent claim root it derived.
- Different implementations MUST agree on the classification of every case in
  the conformance corpus, and MUST derive identical roots for every valid case.

## 14. Required fail-closed conditions

A verifier MUST reject (never produce a claimable result) when any of the
following hold:

1. Unsupported bundle schema version.
2. Unsupported profile version or unknown profile id.
3. Reconciliation status is not `pass`.
4. Coverage is not complete.
5. Universe partition is not a valid partition (overlap or omission).
6. Supplied claim parity core differs from the derived claim parity core.
7. Supplied claim json-root differs from the canonical root of the derived
   parity core.
8. Reconciliation/environment/plan roots disagree across envelopes.
9. Any identity substitution detected.
10. Any signature fails cryptographic validity or signer authorisation.
11. The bundle references receipts or artifacts not present in the bundle.
12. The evaluation mode or claim class is unknown or not permitted.

## 15. Non-goals (normative)

The framework MUST NOT be represented as establishing:

- correctness of the underlying research model;
- correctness of authorised signer judgment;
- secrecy of bundled evidence;
- liveness of external evidence sources;
- resistance to compromise of all trusted implementations and keys;
- universal equivalence across unsupported domains.

---

## Implementation notes (informative)

The following are current implementation details, NOT normative requirements.

| Concern | Clojure | Python |
|---|---|---|
| Root hashing | `resolver-sim.conformance.canonical` | `scripts/bundle_verify.py` |
| Bundle verifier | `resolver-sim.conformance.bundle` | `scripts/bundle_verify.py` |
| Signatures | `resolver-sim.conformance.crypto` (JDK Ed25519) | n/a (vectors only) |
| Corpus | `etc/conformance/corpus/` + `corpus-test` | `bundle_verify.py` |
| CLI | `clojure -M:conformance-cli bundle verify ...` | `python3 scripts/bundle_verify.py <bundle>` |
