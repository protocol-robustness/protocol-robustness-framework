# PRF Dispute Review V0

## Ownership and boundary

Inspection found no reusable, general review workflow or `current-world`
artifact/schema in this checkout. The evidence DAG in
`resolver-sim.evidence.node` owns deterministic execution records; the evidence
chain owns hash-linked evidence records and optional signatures. Neither is a
general investigator's notebook. SEW owns case and instance lifecycle, ruling
authentication, finality and settlement closure as recorded in
`SEW_DISPUTE_LIFECYCLE_CONTRACT.md`. Generic adjudication outcome and closure
remain deferred by `PRF_GENERIC_ADJUDICATION_LAYERING.md`.

This V0 package is a local review record, not an independently trustworthy
conclusion by itself. It makes no claims of adjudicator authority, authenticated
outcome, consensus finality or settlement authority. A reviewer assessment is
not proof. In particular, a retrieved
`current-world` observation is only an observation unless its separate source
and authentication contract is verified. This checkout defines no such
`current-world` contract; snapshots are therefore recorded with their
observation time, source provenance and evidence references, without upgrading
their trust status.

## Package and identity

Each invocation creates a collision-safe directory named
`prf-dispute-review.<unique-id>` beneath
`$XDG_DATA_HOME/prf-dev/dispute-reviews/` (or
`~/.local/share/prf-dev/dispute-reviews/`). The directory ID is an operational
instance locator. It is deliberately excluded from content identity so equal
review content in two independent directories has the same canonical root.

V0 stores one `manifest.edn`, using PRF's canonical typed EDN-compatible values
and `resolver-sim.hash.canonical/domain-hash` with domain tag
`PRF_DISPUTE_REVIEW_V0`. The root is `sha256:` plus the resulting digest of the
manifest with `:review/instance-id` and `:review/canonical-root` omitted. This
uses existing canonical serialization and domain-separated hashing; it does
not create another codec or hash scheme. A reference never gets silently
substituted. Package authors may include exact copies for portability; the
initializer and verifier do not copy or replace evidence. Local file references
must resolve inside that review's directory; verification rejects absolute
paths, traversal, and symlinks that escape the package. Each file-backed source,
evidence and reproduction-result reference includes its SHA-256 identity. An
available file without a pinned identity is reported as absent/unpinned; a
changed file is failed. Non-local references remain unresolved unless a
suitable verifier is available.

Required manifest fields:

```edn
{:review/schema "prf-dispute-review.v0"
 :review/instance-id "prf-dispute-review.<unique-id>"
 :review/status :incomplete
 :review/target "claim, artifact, invariant, observation, or case reference"
 :review/scope "bounded question and included/excluded subjects"
 :review/subject {:subject/kind :dispute-case-or-other-subject-kind
                  :subject/id "exact case or subject identifier"
                  :subject/instance-id "exact subject instance identifier"
                  :subject/root "sha256:..."
                  :subject/reference {:path "subject.edn" :sha256 "..."}}
 :review/policy {:id "policy identifier"
                  :reference {:path "policy.edn" :sha256 "..."}
                  :status :pinned}
 :review/source-refs []
 :review/current-world [] ; observation time, provenance, trust class, exact refs
 :review/evidence []      ; refs include :trust-class
 :review/findings [{:id "finding-id"
                    :claim "exact claim or invariant"
                    :expected ... :observed ...
                    :evidence-refs [] :reproduction-id "reproduction-id"
                    :counterexample nil ; or minimal observation plus evidence refs
                    :assessment :supported ; :contradicted/:inconclusive/:insufficient-evidence
                    :trust-class :locally-consistent
                    :coverage-limitations ["..."]}]
 :review/uncertainties [] :review/contradictions [] :review/limitations []
 :review/reproduction [{:id "reproduction-id" :command "..."
                        :input-refs [{:path "source.edn" :sha256 "..."}]
                        :result-ref {:path "result.edn" :sha256 "..."}
                        :expected ... :observed ...}]
 :review/verification {}  ; :not-run, :absent, :failed, or :passed per check
 :review/reviewer {:name "..."}
 :review/attestation nil  ; optional authenticated attestation reference
 :review/predecessors [] :review/successors []
 :review/canonical-root "sha256:..."}
```

Findings are nonbinding reviewer assessments. Verification status is separate
from findings. Missing evidence is `:absent`, failed identity/reproduction is
`:failed`, and only executed successful checks are `:passed`. An incomplete
review can be retained, but cannot be labelled qualified or verified.

Every finding names its claim, expected and observed values, evidence references,
reproduction entry, counterexample when contradicted, trust class and coverage
limitations. References must be exact members of the package evidence list and
the reproduction ID must resolve to a pinned reproduction entry. The canonical
review root commits the subject, policy, snapshots, findings, methods and all
references as one revision. In V0, `:subject/root` is the SHA-256 identity of the
exact subject file and must equal `:subject/reference`'s pinned digest. Case ID
and instance ID remain separate fields, so two case instances cannot be
collapsed into one subject identity.

Trust classes are reviewer assessments: `:locally-consistent`,
`:externally-authenticated` or `:unestablished`. V0 checks local file identity
and declared reproduction output; it does not authenticate external receipts,
authority or current-world provenance. Verification therefore reports external
authentication as `:not-checked`, regardless of the declared class. A local hash
match does not upgrade evidence to externally authenticated.

## Directed investigation

For a bounded review, pin exact source identities; reconstruct the target from
those inputs; list expected invariants; examine evidence and counterevidence;
record any world snapshots with observation time and provenance; attempt at
least one bounded falsification; compare expected and observed results; record
missing inputs and alternative explanations; and provide reproducible commands.
No command in this workflow writes to a reviewed protocol or authoritative
state store.

## CLI

```sh
prf-dev dispute-review new --input request.edn
prf-dev dispute-review verify --review "$XDG_DATA_HOME/prf-dev/dispute-reviews/prf-dispute-review.<id>"
```

`new` creates a fresh directory and returns both its exact path and ID. The
request is EDN. `verify` checks schema and canonical identity, checks pinned
subject, policy, source and evidence references when available, checks that
structured findings name exact evidence entries and reproduction IDs, compares
a recorded reproduction result to its pinned result file, and reports
reproduction claims separately. A failed schema, identity, evidence, or
reproduction check returns a
nonzero CLI exit status; absent checks remain explicitly incomplete. It never
executes a command from the manifest. V0 does not
seal or sign packages. Keep local configuration, credentials, raw logs and
unredacted evidence outside source control.

V0 uses PRF's canonical domain-hash and SHA-256 reference conventions. Findings
remain nested under `:review/findings`, so they do not collide with run-level
generic findings artifacts. The existing artifact registry is scoped to run
outputs; it has no registration contract for persistent XDG review directories.
V0 therefore verifies the package manifest and references directly and does not
write a second registry. Binding review packages into a broader evidence-pack or
registry is an integration follow-up, not a new registry in this proposal.

## Pilot and qualification

The pilot under `examples/dispute-review-v0/` uses a frozen local observation
fixture and deterministic reconstruction. Its supported claim, deliberate
contradiction and absent-evidence example are review findings, not protocol
state. All data are modelled/local. They are not a public-chain observation or
externally authenticated evidence. The reconstruction command reads its pinned
package fixture and writes the package-local result file; it does not connect to
or mutate protocol state.

V0 does not provide evidence ingestion, automatic invariant discovery,
orchestration, reviewer approval, signatures, sealing, or generic adjudication.
An absent `current-world` source contract and lack of an execution sandbox for
arbitrary reproduction commands are explicit gaps; reproduction commands are
recorded but not run by verification.
