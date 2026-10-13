# Participant assurance for reproduction evidence

`research-participant-assurance.v1` records a pair of attributed interaction
reproductions. Its authoritative inputs are the frozen researcher axis and two
contexts containing the existing attribution, implementation identity, execution,
original result and reproduction evidence. The coordinate and attribution
schemas remain frozen.

Each context is validated before projection: attribution must be in scope;
implementation identity must match execution; reproduction evidence must equal
the existing comparison derivation; attribution must bind that exact evidence
and analytical subject. Source projection cannot substitute for reproduction.
Reproduced and counterexample contributions are supported; other evidence modes
remain outside this first pairwise integration.

## Dimensions

Established dimensions are deliberately separate:

| Dimension | Current basis |
| --- | --- |
| Participant references distinct | Resolve both nominal coordinates against the same frozen axis and compare their member identifiers |
| Implementation artifacts distinct | Compare the artifact roots bound by the execution implementation identities |
| People distinct | Unknown |
| Organizations distinct | Unknown |
| Operators distinct | Unknown |
| Machines distinct | Unknown |
| Identities authenticated | Unknown |
| Hardware attested | Unknown |

Implementation identity roots may differ only because version or dependency
metadata changed. That does not establish artifact-level implementation
distinctness. Different artifact roots establish only a difference in artifacts;
they do not prove different algorithms, authors or organizations.

An anonymous or pseudonymous researcher needs no real-world identity field to
contribute. Disclosure never changes reproduction validity or supplies a trust
score. Distinct references do not establish distinct people. The existing axis
membership rules remain authoritative for nominal indexing.

## Declarations and future validators

The artifact can retain declarations for the six real-world dimensions, with
values `:yes`, `:no`, `:unknown` or `:undisclosed`. Yes/no declarations require
canonical supporting evidence references. Every such assertion is explicitly
`:declared`; root references alone do not verify its content. Established values
remain `:unknown`. Absence of evidence never becomes independence or distrust.

`declared-dimension-view` displays declared values separately from established
values. Conflicting declarations are retained and reported as `:conflicting`;
none is selected by arrival order or vote. Repeating a declaration is idempotent.

A future authentication, organizational or hardware verifier must bind its
exact subject, mechanism and validation evidence before an established dimension
can change. V1 intentionally cannot accept a caller flag asserting verification.
Such integration will need an explicit successor or composition artifact;
it must preserve the meaning of the declaration-only V1 fields.

## Reproduction counts and provenance

The pair is symmetric: reversing input order produces the same assurance root.
Counts use unique canonical reproduction-execution roots. Replaying the same
execution counts once, even with another attribution. Deterministic same-input,
same-implementation executions share a semantic execution root, so this slice
cannot establish how many physical invocations occurred. Distinct attempt or
observed-run evidence will be needed to count those events reliably.

Matching reproduction count requires the same input and original-result root
and a `:reproduced` outcome. Contributions about different inputs/results yield
`:not-comparable`, with zero matching count; disagreement witnesses remain in
their independently rooted reproduction evidence.

The assurance artifact attaches through existing generic forensic evidence
nodes with restricted visibility. Its causal parents include the axis,
attributions, executions, inputs, results, reproduction evidence, implementation
identities/artifacts, and declaration evidence. Canonical reconstruction therefore
retains all constituents. No second evidence subsystem is introduced.

No aggregate boolean declares “independent researchers.” The two questions
“different implementation artifacts?” and “different participant references?”
can both answer yes while people and organizational independence remain unknown.
Authentication would not by itself establish any independence dimension.

No identity service, credential issuance, attestation, hardware proof, claim
acceptance, authority or incentive policy is implemented. The assurance artifact
adds evidence around reproductions without changing their semantic result roots.
