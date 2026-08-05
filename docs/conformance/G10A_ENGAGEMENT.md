# G10a — Independent Verifier Engagement (contract-like assignment)

This document is the external assignment given to the independent contributor.
It is deliberately contract-like: deliverables, boundaries, and acceptance
gates are explicit.  The clean-room character of the exercise depends on the
boundary being enforced; the contributor must attest what they accessed.

Frozen boundary: cleanroom inputs root `5cc48112…`
(`etc/conformance/cleanroom/inputs.edn`).  Private holdout root
`06b4ca43…` — the holdout cases are **not** released to the contributor; only
the root is attested.

## Scope

Implement a conforming verifier from the normative specification and the public
corpus, derive fixed cryptographic roots, pass a private adversarial holdout
corpus, and document specification ambiguities.  No protocol execution code and
no Clojure knowledge are required.

This is a paid verification bounty (see Recruitment positioning).

## What the contributor receives

1. `docs/conformance/SPECIFICATION.md` — the normative protocol.
2. `docs/conformance/VERSIONING.md` — compatibility and versioning policy.
3. `docs/conformance/RESOURCE_SAFETY.md` — resource limits.
4. The schema catalog (envelope schema version strings).
5. `etc/conformance/vectors/` — canonicalisation and cryptographic vectors.
6. `etc/conformance/corpus/` — public corpus manifest and cases.
7. `etc/conformance/release.v1.edn` — the release descriptor.
8. The required machine-result shape (below).

The exact file set is frozen by `cleanroom-inputs-root 5cc48112…`.

## What the contributor does NOT receive (until the clean-room result is frozen)

- Clojure verifier source (`src/resolver_sim/conformance/*.clj`).
- Python verifier source (`scripts/bundle_verify.py`).
- Node verifier source (`scripts/verify3.mjs`).
- Private holdout cases (`etc/conformance/holdout/`).
- Current implementation algorithms or helper libraries
  (`resolver-sim.hash.canonical`, etc.).
- Any explanation of current algorithms beyond the normative specification.

Existing verifier sources MAY be opened only after the clean-room result is
frozen (see Independence declaration).

## Required machine-result shape

Every input MUST produce exactly this shape:

```json
{
  "status": "pass",
  "outcome_class": "verified",
  "claimable": true,
  "derived_claim_root": "...",
  "issue_codes": []
}
```

`status` ∈ `pass | rejected | unsupported-version`.  Output MUST be
deterministic across repeated runs.

## Milestones

### M1 — Specification implementation
- Parses and verifies the entire public corpus.
- Derives every committed vector root exactly.
- Derives the same parity-core claim roots as the reference bundles.
- Emits the stable result shape.
- Rejects unsupported canonicalisation and envelope/bundle versions with a
  typed `unsupported-*` result.
- Operates with NO PRF execution code.

### M2 — Ambiguity submission
- Records every behaviour not determinable from the specification, using the
  entry format in `AMBIGUITY_LOG.md`.
- Does NOT infer behaviour from existing verifier output beyond the public
  corpus contract.

### M3 — Holdout gate
- Runs against the private 21-case holdout corpus (provided by the sponsor at
  this milestone).
- Every disagreement is classified by the adjudicator as an implementation
  defect, a specification ambiguity, or a corpus defect.

### M4 — Reproducible artifact
- Source root and executable artifact root are committed.
- Build is reproducible from declared dependencies.

### M5 — Independence declaration
- The contributor states exactly what materials were accessed and when.
- Existing verifier sources may be opened only after the clean-room result is
  frozen at M3/M4.

## Completion result

On success the sponsor updates the assurance artifact (never the protocol
release):

```edn
{:assurance/verdict :assurance-complete
 :clean-room-verifier/root ...
 :clean-room/attestation-root ...
 :holdout/status :pass
 :ambiguities/resolved-root ...
 :real-adoption/status :pending}
```

## Bounty terms

- The terms MUST NOT require any particular internal architecture or language.
- The terms MUST NOT require using, reading, or matching the existing
  verifier sources.
- Suggested languages: Rust, Go, TypeScript.

## Recruitment positioning

> Implement a verifier from a normative specification and public corpus, derive
> fixed cryptographic roots, pass a private adversarial holdout corpus, and
> document specification ambiguities.  No protocol execution or Clojure
> knowledge is required.

Strong candidate backgrounds: protocol assurance and formal methods;
cryptographic protocol implementation; reproducible-build or supply-chain
verification; standards and interoperability testing; Rust/Go/TypeScript
security tooling.  Some unfamiliarity with the domain improves the
independence test.

## Adjudication

One adjudicator (nominated by the maintainer before recruitment) resolves all
verifier/spec/corpus disagreements, per `GOVERNANCE.md`.  Ambiguity
resolutions change version classification as follows:

| Resolution mechanism | Version class (VERSIONING.md) |
|---|---|
| Normative clarification | `new minor version` when it only clarifies; `breaking core version` if it changes roots or verdicts |
| Additional vector | `compatible` (new vector) |
| Additional corpus case | `compatible` (new case) |
| Implementation-defined rule | `new minor version` (new normative text) |
| New version | per `VERSIONING.md` classification table |

A clarification that alters existing roots or verdicts MUST be released under
a new version, never silently under the existing release root.
