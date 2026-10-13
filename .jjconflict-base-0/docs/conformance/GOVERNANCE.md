# Conformance Framework — Maintenance Governance

The conformance core is feature-complete at `:conformance/core-version 1`.
Changes are no longer generalisation work; they are protocol changes and MUST
go through this lightweight review before landing.

## Change proposal

Any change to the conformance framework MUST be described by a change proposal
(`etc/conformance/change-proposal.edn` is the template).  A proposal MUST state:

- affected normative sections of `SPECIFICATION.md`;
- affected schemas and roots (which envelope/root values change);
- the compatibility classification from `VERSIONING.md`
  (compatible | new minor version | new envelope version | new profile version
  | breaking core version);
- new or changed issue codes;
- corpus additions (public and/or holdout) that cover the change;
- vector changes;
- historical-verification impact;
- profile impact;
- whether a new release root is required (always for non-compatible changes).

## Review requirements

Every proposal MUST land with, at minimum:

1. implementation tests (Clojure suite);
2. Python parity re-run;
3. independent-verifier parity re-run after G9c (`corpus_gate`, `holdout_gate`);
4. corpus updates covering the change;
5. versioning classification recorded in the proposal;
6. release-root update where applicable, and golden re-pinning when roots change.

A reviewer MUST confirm that the change does not silently alter normative
behaviour under an existing release root.  Resolutions to spec ambiguities MUST
use one of the mechanisms in `AMBIGUITY_LOG.md`
(normative clarification, additional vector, additional corpus case,
implementation-defined rule, or new version) and MUST NOT edit the normative
text without a proposal.

## Frozen features

The following MUST remain frozen behind their committed promotion triggers in
`etc/conformance/maturity.edn`; a proposal promoting one is a protocol review,
not a routine change:

- receipt DAG (promote only when committed ancestry, not merely prerequisites,
  changes claim validity);
- named coverage dimensions (promote only when two profiles need multiple
  distinct coverage universes);
- symmetric comparison (promote only when a real profile has two equally
  authoritative implementations and passes operand-reversal invariants);
- schema migration (promote only when the first real v2 artifact exists).

## Freeze policy

The core is frozen at `:conformance/core-version 1`.  The core MUST NOT be
reopened for:

- more convenient profile authoring;
- a speculative fourth profile;
- prettier receipt topology;
- general plugin support;
- optional abstraction cleanup;
- hypothetical migration machinery.

A core change is justified ONLY by one of:

1. an independent verifier exposes a specification ambiguity or divergent
   normative interpretation;
2. the real adoption bundle cannot express a necessary assurance fact
   honestly;
3. a security defect permits an incorrect or stronger claim;
4. an actual v2 envelope or profile requires migration;
5. a deferred feature crosses its committed promotion trigger.

Everything else belongs in adapters, tooling, documentation, or reviewer UX
and MUST NOT touch the normative specification, roots, or verdicts.

## Adjudication

One **protocol adjudicator** is nominated by the maintainer before recruitment.
The adjudicator resolves all verifier/spec/corpus disagreements from a
clean-room submission, classifies each holdout disagreement as an
implementation defect, a specification ambiguity, or a corpus defect, and
approves every ambiguity resolution before it changes normative text.  A
resolution that alters existing roots or verdicts requires a new version and a
new release root (VERSIONING.md); it MUST NOT be applied silently under the
existing release root.

## Release discipline

- `release.v1.edn` is immutable for a given core version.
- The external-assurance descriptor (`assurance.v1.edn`) references the release
  it evaluated; it is updated by assurance work, not by protocol changes.
- The holdout corpus stays out of the public corpus root and the release
  artifact until the clean-room submission it guards has been judged.
