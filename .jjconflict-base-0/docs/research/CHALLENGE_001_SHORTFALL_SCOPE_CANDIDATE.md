# Challenge 001 — Shortfall under stress (scope candidate v1)

Status: **technical candidate only**. This is not a frozen challenge, an
activated researcher configuration, a funding commitment, or authority to accept
submissions or create reward obligations. The matching EDN candidate is
`examples/researcher-challenges/shortfall-stress/challenge-001-scope-candidate.v1.edn`.
Its current candidate root is
`sha256:51a39238bde9539b7687374575955f40e985e7fc9e4cc216dca254e8cebda310`.

## Proposed bounded domain

Each case is a pair of canonical allocator requests over the same rows and
available amount, differing only in the redistribution policy:

- 2–12 unique canonical allocation rows (claim slots, not claim artifacts);
- each requested amount is an integer from 1 through 1,000,000 units;
- total requested is at most 10,000,000 units;
- available is an integer from 0 through total requested, inclusive;
- to isolate redistribution, weight equals requested;
- a cap is optional; if present, it is an integer from 0 through that row's
  request. Zero-cap rows are permitted.

The falsification criterion is evaluated on a shortfall case (`available <
total requested`). Fully funded inputs remain useful controls, but cannot be a
shortfall finding. This limits the study to the existing integer allocator; it
does not assert token, account, claim-acceptance, or payment semantics.

The comparison fixes `:rounding-policy :largest-remainder` and
`:tie-break-policy :canonical-row-id`. Its only policy contrast is
`:redistribution-policy :unallocated` versus
`:redistribute-cap-excess`, both implemented by mechanism version 1. The
observed policy roots under the current implementation are recorded in the EDN
candidate. No other policy is in scope. In particular, `:floor` is implemented
but excluded from this challenge so the independent effect of rounding is not
confounded with cap-excess redistribution.

## Verification and evidence

The operator must freshly normalize both input requests and execute the
authoritative allocator for each. The requests must be equal after removing
only the redistribution-policy field. Both complete results must have valid
request/allocation roots and pass the existing `result-violations` suite,
including cap-respecting, quota-bounded, largest-remainder assignment,
residual consistency, and aggregate conservation checks. The verifier retains
the canonical input pair and both full result bodies; it never accepts a
researcher's claimed scalar as computation input.

For a result pair, define each row's absolute allocation difference and
`D = max(row differences)`. `D > 0` is a finite counterexample to allocation
policy invariance for that exact case and policy pair. It does not establish a
general economic theorem or indicate that either allocation is invalid. A
compact witness projection consists of all canonically ordered rows tied at
`D`, with row identity, request, effective cap, and allocation under both
policies. This is a derived view of the retained full results, not a proof that
the input itself is irreducible.

`D = 0` with both valid results is a supported boundary observation for one
case only. It is not a proof of policy invariance and does not meet current V0
acceptance criteria, which admit only `:counterexample-found`. The allocator's
`:unmet` field is policy-specific cap shortfall; it must not be represented as
total unpaid demand or `requested - allocated`.

The existing exact published calibration case root is excluded in the rooted
pilot profile. Renaming IDs or changing row order alone is not novelty. Because
row identities participate in canonical tie-breaking, a change that changes a
tie outcome may be an interesting boundary case, but it still needs an
economic explanation and reviewer novelty decision. Exact accepted
contributions are rejected by existing V0 deduplication; near-duplicate
scenario detection is not automatic and remains a reviewer obligation. A
positive `D` is falsification of the finite invariance proposition, not an
invariant violation.

The independent verification baseline is a fresh operator execution from the
requests. A second pseudonymous researcher may independently rerun those inputs
and return rooted execution/reproduction evidence. That is same-implementation
reproduction unless a separately identified implementation is used. It does
not prove distinct people or organizations, and current V0 does not make
reproduction a rewardable contribution type. It may corroborate a finding but
must not be counted as another finding or vote.

## Evaluator compatibility and limits

Boundary probes were run through the current Clojure evaluator with both
redistribution policies. The following cases all produced valid result roots
and zero existing invariant violations:

| Rows | Total requested | Available | `D` |
|---:|---:|---:|---:|
| 2 | 2 | 0 | 0 |
| 10 | 10,000,000 | 10,000,000 | 0 |
| 12 | 10,000,000 | 5,000,000 | 37,788 |

The 12-row probe included a capped row. These are compatibility probes, not
challenge findings or expected results supplied to researchers.

The allocator and existing V0 replay path do not enforce this candidate's
row-count, per-row, total-request, or availability bounds. A separate
`validate-challenge-001-scope-candidate!` preflight now checks the candidate's
rooted domain and exact method/policy declarations, then freshly validates a
comparison against those bounds. It is deliberately not called by submission
admission and creates no challenge, acceptance, or reward artifact. Before a
real challenge is frozen, an approved challenge-specific submission gate must
enforce these bounds without changing allocator semantics or identities. The
current adapter retains full results rather than a separately stored compact
witness; the witness projection is deterministically derivable from them.

## Source identity and manifest

Read-only revision extraction succeeded only with `jj --ignore-working-copy`:
the visible working-copy revision is
`7e9e371fca7ceedca94e1b61a1c5e7d687d60459` (no description), with parent
`7f861667655d6315ed58518cd040f4f2db261110` (`Checkpoint after protected-capital
reservation audit`). The working copy was not captured in that revision, so
neither revision identifies the current files. Ordinary `jj` snapshotting fails
because the shared `/home/user/Code/sew-simulation/.git/objects` is read-only;
that store was not modified. The content manifest is therefore the candidate
source identity.

[`challenge-001-source-content-manifest.v1.tsv`](challenge-001-source-content-manifest.v1.tsv)
contains 219 entries: the 56 local Clojure namespaces loaded by the
shortfall pilot, its CLI, and publication entry points; all 50 project resource
files; `deps.edn`; both calibration input files; and all 110 JARs on the
project-only `clojure -Srepro -Spath` classpath. Including the complete project
classpath is conservative and broader than the minimal execution dependency
closure; it prevents silently omitting a classpath artifact. No test keys,
private keys, user Clojure configuration, build caches, or result corpora are
included.

The manifest was refreshed for the current checkout after the previously
recorded allocator policy roots and nine listed source/project files no longer
matched their recorded bytes. It also now includes the current researcher
network namespace actually loaded by the pilot entry points, replacing its
unused compatibility shim in the source list. The two policy roots now match
the current allocator implementation; no allocator semantics were changed in
this candidate refresh. The manifest content-set root is:

```text
sha256:790402f2ecd202dcdf2d7b509f34c31d3a09b2ee06d7df4ee2e0b1201ab3bffb
```

It was independently checked using the system `sha256sum` command: all 219
source/resource/JAR entries matched, and hashing the manifest file reproduced
the root above. This remains a content-set identity, not a hermetic source/build
closure or captured jj revision; use this refreshed root for the current local
candidate only. The manifest was refreshed after adding the explicitly
non-admitting candidate-scope validator and focused tests. For another machine,
set `m2_repository` to its Maven cache root
and repeat the per-line check; the TSV records repository-relative Maven paths,
not this machine's absolute cache path. Runtime used Clojure CLI 1.12.5.1654,
Clojure 1.12.0, and OpenJDK 21.0.12.1. The manifest does not claim a hermetic
JDK/build environment or prove organizational independence.

## Proposed funding and approval state

The supplied proposal is 100 USDC base per accepted contribution, a 750 USDC
total cap, capacity six, two pseudonymous pilot researchers, maximum four
accepted contributions per researcher, and a 5/4 quote after three accepted
eligible contributions. These values remain **proposed, not authorized**. With
six total slots, the 8-contribution 3/2 tier is unreachable in this pilot; it
will not be represented as tested. Before creating the actual challenge
configuration, approve the funding, verify the exact Base USDC contract and
precision, set the base amount in canonical minor units, and provide/pin
operator and reviewer public keys. Private keys must remain outside the
repository.

Acceptance/novelty policy is also still proposed. The current implementation
can represent only `:adversarial-finding` with `:counterexample-found` criteria;
it cannot accept a zero-difference boundary observation or a reproduction as a
qualifying contribution. This candidate intentionally does not freeze a
complete `researcher-challenge.v1`, activate publication, admit submissions,
or produce reward obligations.
