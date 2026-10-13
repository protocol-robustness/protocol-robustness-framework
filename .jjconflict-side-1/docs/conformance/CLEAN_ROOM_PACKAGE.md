# G9c / G10a — Clean-Room Verifier Challenge

An independent implementer builds a conforming verifier from the normative
specification and the public corpus, without importing PRF execution code.

The engagement contract is `G10A_ENGAGEMENT.md` (milestones, deliverables,
bounty terms, adjudication).  The exact input file set is frozen by
`etc/conformance/cleanroom/inputs.edn`:

- cleanroom inputs root `5cc48112b817ad776cd7524aaa0820d132ba8b693d49edd4265a3c2c1bdcbed0`
- private holdout root `06b4ca43fa91c45be6d6325f569c6d1f58990820f3fa4e376661df53a1be54db`
  (the holdout **cases** are not released to the contributor; only this root is
  attested).

## Purpose

The completion criterion for the conformance protocol:

> An independent implementer can build a conforming verifier from the normative
> specification and public test corpus, derive the same roots and claims for
> historical bundles, and reject every committed adversarial case without
> importing PRF execution code.

This proves the protocol is sufficiently specified to be implemented
independently, not merely that two current programs happen to agree.

## What the implementer is given

Only these inputs:

1. `docs/conformance/SPECIFICATION.md` — the normative protocol.
2. `docs/conformance/VERSIONING.md` — compatibility and versioning policy.
3. `docs/conformance/THREAT_MODEL.md` — adversaries and protected properties.
4. The public schema catalog:
   `src/resolver_sim/conformance/envelope.clj` (schema version names only) —
   the committed envelope schema version strings.
5. `etc/conformance/vectors/canonical-roots.json` and
   `etc/conformance/vectors/crypto.json` — canonical preimages, roots, and the
   Ed25519 vector set.
6. `etc/conformance/corpus/manifest.json` and every case under
   `etc/conformance/corpus/` — the implementation-neutral corpus.
7. `etc/conformance/release.v1.edn` — the release descriptor (the stable
   release root to verify).

These are packaged as the released artifact set.  No verifier source is
included.

## What the implementer is NOT given

- `src/resolver_sim/conformance/*.clj` — Clojure verifier source.
- `scripts/bundle_verify.py` — Python verifier source.
- `scripts/verify3.mjs` — third-language verifier source.
- Internal helper libraries (`resolver-sim.hash.canonical`, etc.).
- Any explanation of the current algorithms beyond the normative specification.

## Required stable minimal result

Every input MUST produce exactly this shape (no extra required fields):

```json
{
  "status": "pass",
  "outcome_class": "verified",
  "claimable": true,
  "derived_claim_root": "...",
  "issue_codes": []
}
```

`status` is `pass | rejected | unsupported-version`.  `outcome_class` is
`verified | not-claimable`.  `derived_claim_root` is the derived parity-core
claim root for valid inputs, else null.

## Acceptance gates

The implementation MUST:

1. derive every committed vector root exactly (`canonical-roots.json`);
2. reproduce every committed Ed25519 decision (`crypto.json`);
3. accept every valid corpus case;
4. reject every invalid corpus case with the expected issue codes;
5. derive the same parity-core claim roots as the reference bundles;
6. reject unsupported canonicalisation and envelope/bundle versions with a
   typed `unsupported-*` result;
7. operate with NO PRF execution code (no domain validators, no receipts
   resolved by running anything);
8. verify historical bundles under their committed environments (no current
   registry or policy substitution);
9. produce deterministic output across repeated runs.

## Submission requirements

Submit:

- the verifier source, dependency manifest, and build/run instructions;
- a runnable gate script that, given the corpus and vectors, prints the
  per-case table and a pass/fail summary;
- the specification-ambiguity log (see `AMBIGUITY_LOG.md`) recording every
  point where behavior could not be determined directly from the spec;
- evidence the public corpus was not the only guide (see the private holdout
  corpus note below).

## Private holdout corpus

A verifier can overfit the public corpus.  Until submission, a small **private
holdout set** is maintained out of band (see the holdout cases listed in
`docs/conformance/ASSURANCE_PLAN.md`).  Passing the public corpus demonstrates
conformance work; passing the holdout corpus demonstrates that the
specification, not the cases, was understood.  The holdout set is excluded from
the public corpus root and from the release artifact.

## Language

Any language.  Rust, TypeScript, or Go are suggested.  Implementation
independence matters, not the language.

## Independence attestation

The implementer MUST attest:

- they did not read the Clojure, Python, or JavaScript verifier sources;
- they did not inspect the internal helper libraries;
- they recorded every ambiguity they resolved;
- dependencies are declared and do not embed PRF logic.
