# Researcher Network Pilot V0

Status: draft, unpublished and unqualified. The proposed first economic subject
is **Shortfall under stress**: insufficient capital distributed across several
claims under explicitly declared allocation policies. This document defines the
pilot preparation and its completion gate. It supplies no operator authority,
reward budget, accepted contribution or obligation.

The bounded technical proposal for Challenge 001 is recorded separately in
[`CHALLENGE_001_SHORTFALL_SCOPE_CANDIDATE.md`](CHALLENGE_001_SHORTFALL_SCOPE_CANDIDATE.md).
It remains a candidate and does not activate this pilot.

## Economic question and replay basis

Under what conditions do supported allocation policies produce materially
different per-claim allocations, unmet quantities or residual capital, and
which declared guarantees continue to hold?

Use the existing `resolver-sim.pro-rata.allocation/allocate` computation and
`resolver-sim.pro-rata.invariants/result-violations` checks. The allocator's
canonical request and result remain authoritative. The portable contract owns
their semantics. Keep the research comparison downstream of these artifacts.

Initial scope includes constrained liquidity, caps, integer rounding and
cap-excess redistribution. It excludes capital movement, application admission
and unspecified replacement algorithms. A difference between supported
policies is an economic observation; it is not automatically an invariant
violation or an accepted novel finding.

Two input-only calibration requests are provided under
`examples/researcher-challenges/shortfall-stress/`. They use the same available
capital and rows and differ only in cap-excess redistribution policy. They
contain no expected result. These known calibration examples teach replay and
must not themselves earn novelty credit.

From the repository root, run:

```sh
clojure -M -e '(require (quote clojure.edn)
                        (quote resolver-sim.pro-rata.allocation)
                        (quote resolver-sim.pro-rata.invariants))
  (doseq [file ["examples/researcher-challenges/shortfall-stress/unallocated.edn"
               "examples/researcher-challenges/shortfall-stress/redistribute.edn"]]
    (let [input (clojure.edn/read-string (slurp file))
          result (resolver-sim.pro-rata.allocation/allocate input)]
      (prn {:request (:canonical-request result)
            :result result
            :root-valid? (resolver-sim.pro-rata.allocation/allocation-hash-valid? result)
            :violations (resolver-sim.pro-rata.invariants/result-violations result)})))'
```

Record the complete canonical requests/results, not only allocation scalars.
Policy changes intentionally change request/result identity. Different policies
may produce the same scalar without establishing equivalent evidence.

The calibration replay gives allocations `[10, 33, 33]` (total 76) under
`:unallocated`, and `[10, 45, 45]` (total 100) under
`:redistribute-cap-excess`. Both result roots recompute and both pass the
existing invariant suite. Both report zero `:unallocated-residual`: the first
result records the remaining cap shortfall in row `:unmet`. Under the frozen
contract, `:unmet` is policy-specific cap shortfall and cannot generally be
interpreted as total unpaid demand or `requested - allocated`. The pilot must
keep those primitive meanings and explicitly define its downstream economic
measures. A residual-only comparison would miss this economic difference.

## Publication prerequisites

Freeze the actual source revision and subject basis, reviewed policy set and
input domain, independently pinned operator signing key, reviewer public key,
base reward/currency, total budget, reservation capacity, novelty rules and
closure rules before signing a publication. None of these values should be
invented from a qualification fixture. A working-copy label or the portable
manifest's `workspace-uncommitted` label does not freeze the pilot source.

Publish the public configuration, pilot profile, signed history, checkpoint and
evidence file manifest through an existing Git repository. Keep private signing
keys outside that repository. V1 and controlled histories use separate
publication schemas, bundle layouts, origin pins and retained reader-state files.

The current V1 signed publication binds the exact configuration and pilot roots,
checkpoint, evidence file hashes, publication sequence and previous publication
identity. It does not publish the controlled-pilot/control-policy composition. A
reader retains its last trusted publication and rejects a rollback
or an incompatible successor. The pinned key must come from an independent
channel. A valid signature on an old checkpoint does not establish latestness.
Conflicting signed successors must remain evidence of equivocation. A reader
cannot detect an unseen conflicting branch from one Git checkout alone; the
pilot uses exchanged signed checkpoints and the operator's designated Git
history as its publication trust model.

`verify-history-checkpoint` and its controlled counterpart still validate exact
replay only. The new offline `shortfall-publication` composition supplies signed
publication lineage and retained reader trust for the arithmetic-gated V1 and
opt-in controlled pilot paths. It does not change either checkpoint contract
or the controlled-history path.

### Signed publication and retained reader trust

One new canonical artifact, `researcher-shortfall-publication.v1`, binds the
operator public key, declared source-revision root, configuration and pilot
roots, replay-derived checkpoint, exact public-file SHA-256 references,
publication sequence and predecessor root. Its Ed25519 signature uses the
existing PRF signer. This narrow artifact is needed because existing checkpoints
and generic signing do not bind predecessor/current-reader publication state;
production governed configuration activation is not a research-pilot authority.

The V1 bundle has four fixed public filenames:

```text
configuration.edn
pilot.edn
history.edn
evidence.edn
```

Only these files are read. Hashing and EDN parsing use the same captured bytes.
All historical economic submissions are freshly replayed through the pilot
gate. The checkpoint is derived, never independently accepted from a caller.
Whitespace changes deliberately change byte-level file commitments; they do
not change canonical accounting or contribution identity. Do not put private
keys, credentials, private scenarios or unrelated material in these public
files. Publication tools do not recursively scan or upload a directory.

Controlled histories use a separate successor artifact,
`researcher-shortfall-controlled-publication.v1`, and a separate retained reader
state file. It binds the base pilot root, derived controlled-pilot root,
reviewer-control-policy root, checkpoint and exact file hashes. Its five fixed
files are `configuration.edn`, `pilot.edn`, `control-policy.edn`, `history.edn`
and `evidence.edn`. The controlled profile is derived from the policy's exact
activation prefix; it is not accepted from an independent caller field. Do not
mix V1 and controlled publications in one trust-state file or treat their
sequence numbers as one chain. Each mode needs its own independently pinned
sequence-zero publication root.

From the repository root:

```sh
clojure -M -m resolver-sim.research.shortfall-publication prepare \
  BUNDLE_DIR OPERATOR_PUBLIC_KEY SOURCE_REVISION_ROOT
clojure -M -m resolver-sim.research.shortfall-publication sign \
  UNSIGNED_PUBLICATION PRIVATE_KEY_OUTSIDE_REPOSITORY
clojure -M -m resolver-sim.research.shortfall-publication advance \
  BUNDLE_DIR PINNED_OPERATOR_PUBLIC_KEY READER_STATE SIGNED_PUBLICATION PINNED_ORIGIN_ROOT

# Prepare a successor after updating public files:
clojure -M -m resolver-sim.research.shortfall-publication prepare \
  BUNDLE_DIR OPERATOR_PUBLIC_KEY SOURCE_REVISION_ROOT PREVIOUS_SIGNED_PUBLICATION
clojure -M -m resolver-sim.research.shortfall-publication advance \
  BUNDLE_DIR PINNED_OPERATOR_PUBLIC_KEY READER_STATE SIGNED_SUCCESSOR
clojure -M -m resolver-sim.research.shortfall-publication equivocation \
  PINNED_OPERATOR_PUBLIC_KEY SIGNED_BRANCH_A SIGNED_BRANCH_B

# Opt-in controlled-history bundle; use separate origin and reader state:
clojure -M -m resolver-sim.research.shortfall-publication controlled-prepare \
  CONTROLLED_BUNDLE_DIR OPERATOR_PUBLIC_KEY SOURCE_REVISION_ROOT
clojure -M -m resolver-sim.research.shortfall-publication controlled-sign \
  UNSIGNED_CONTROLLED_PUBLICATION PRIVATE_KEY_OUTSIDE_REPOSITORY
clojure -M -m resolver-sim.research.shortfall-publication controlled-advance \
  CONTROLLED_BUNDLE_DIR PINNED_OPERATOR_PUBLIC_KEY CONTROLLED_READER_STATE \
  SIGNED_CONTROLLED_PUBLICATION PINNED_CONTROLLED_ORIGIN_ROOT
```

Save the emitted publication files and exchange/publish the public bundle and
signed publication through the existing designated Git repository. No command
pushes Git, starts a service or creates an operator key. Operator and reviewer
are separate roles; their keys need not coincide. The source-revision root is
an operator declaration referencing the frozen source provenance, not automatic
verification of Git contents or a hermetic implementation closure.

Bootstrap requires both an independently pinned operator key and a pinned
sequence-zero publication root. Advance checks signature, self-root, exact file
bytes, economic replay, checkpoint, frozen scope, consecutive sequence,
predecessor and the previous history prefix. A higher signed sequence cannot
hide a shortened or forked action history. Configuration, pilot policy or source
revision changes require a new explicitly pinned origin; implicit key rotation
and configuration migration are unsupported. Exact replay is an idempotent
no-op. Known older publications or incompatible successors cannot replace the
retained state.

`READER_STATE` stores the full last trusted signed publication under an OS lock,
using the existing file-force plus atomic replacement primitive. Invalid input
leaves its bytes unchanged. It is local trust state, not a distributed database
or a new audit log. Keep it outside the remotely replaced Git checkout. Removing
or restoring an older copy loses its monotonic-trust protection; no secure
storage or malicious-local-host protection is claimed. A malformed retained
file cannot silently trigger bootstrap. A literal process-kill test now covers
controlled-reader advancement before file force, after force/before replacement,
and after replacement/before acknowledgement; restart and retry preserve the
old-or-new trust state consistently. This is process-termination qualification,
not directory-fsync/power-loss durability.

The equivocation report retains both authenticated conflicting signed metadata
branches without choosing a winner; it does not assert that both bundles are
available or replay successfully. A reader needs exchanged conflicting
publications to discover an unseen fork. No global latestness, permanent
availability, verified human independence, accepted finding or payout is
established by publication. Controlled-history publication remains deferred.

## Contribution and experience plan

V0 currently allows one reservation per researcher per challenge revision and
accepts only verified `:adversarial-finding` submissions. Its duplicate key spans
the finding's subject/proposition/measure/witness identity. Preserve these rules.

Represent the public pilot as an umbrella with three distinct economic task
definitions and a subsequent task available for quoting. Candidate task tracks
are cap/redistribution boundaries, rounding under shortfall, and declared
representation/tie-breaking effects. Each requires its own precise falsifiable
proposition, measure, frozen context and relevance/novelty criteria. The fourth
task proves the quotation change after three accepted eligible findings; it
does not need a fourth accepted contribution to demonstrate the transition.
Do not create extra tasks by renaming the same finding or changing only its
assignment. Final track definitions remain subject to engine/context integration.

Researcher A must obtain three unique, independently qualifying accepted
contributions. Its next quote must change from 1/1 to 5/4 of the base amount.
Retain an earlier quote to demonstrate that it remains unchanged. Researcher B
must produce an attributable fresh reproduction of one finding and at least
one distinct qualifying contribution for its own obligation. A reproduction
of A's finding retains B's execution/evidence identity; it cannot obtain a
second finding credit by changing attribution or rewrapping the witness.

Reproduction remains a distinct evidence mode. The existing V0 challenge
submission channel does not reward it. Supporting reproduction can be attached
through existing evidence machinery without claiming that another contribution
type is implemented. Two external pseudonymous keys establish two key references;
human, organizational and intellectual independence remain unknown.

## Independent validation before review

For each submission, retain canonical scenario requests, submitted complete
allocation results, implementation/dependency identities, replay instructions,
economic explanation and any counterexample witness. The verifier freshly runs
the supported allocator from the submitted requests, applies the applicable
primitive checks and compares the full result/root with the submitted result.
Reject wrong inputs, policy substitutions, forged outcomes and stale revisions.
Retain arithmetic or invariant failures for inspection; they cannot be silently
converted into successful validated artifacts.

Only after computational checks succeed may the reviewer assess relevance and
novelty, sign authoritative acceptance, and separately establish incentive
eligibility. Preserve those three decisions and their evidence bodies.

The current generic `research-analysis/verify-output` validates analytical
context, closure and roots. It does not rerun allocation arithmetic. Existing
V0 submission expects its six authoritative analytical context artifacts;
an allocation result cannot be passed off as that context. The narrow
`resolver-sim.research.shortfall-pilot` adapter now binds fresh arithmetic to
this existing path. It does not modify generic verification or claim authority.

### Implemented arithmetic-gated file workflow

`research-shortfall-policy-comparison.v1` retains two normalized requests,
their complete freshly computed allocation results, case root and maximum
absolute per-row allocation difference. Requests must match except for rounding
and redistribution policy. A positive difference is a finite counterexample to
allocation-policy invariance for that declared pair; zero is supported within
that single case. This metric does not measure unpaid demand, prove general IC,
or establish novelty. Primitive result invariants must pass for both results.

`researcher-shortfall-pilot.v1` composes the existing configuration with an
explicit method root, fixed allocation-policy roots and the exact canonical
case root of the published calibration pair as a non-qualifying input. The
submission gate rejects that case even if it has a valid counterexample and a
reviewer later approves it; changing only the allocation ID does not constitute
an actual research finding, so novelty review remains necessary. This exclusion
is intentionally narrow and does not claim to classify arbitrary near-duplicate
scenarios. The profile is necessary to make
the additional arithmetic gate a published requirement, rather than an optional
unrooted verifier setting. Neither artifact revises any existing V1 identity.
The method root identifies declared comparison semantics, not a hermetic source
or binary closure. Publish/pin the actual implementation revision separately.

Use the following offline entry point (output is EDN):

```sh
clojure -M -m resolver-sim.research.shortfall-pilot-cli compare \
  examples/researcher-challenges/shortfall-stress/unallocated.edn \
  examples/researcher-challenges/shortfall-stress/redistribute.edn

# Subsequent commands consume saved canonical artifacts:
clojure -M -m resolver-sim.research.shortfall-pilot-cli context METADATA COMPARISON
clojure -M -m resolver-sim.research.shortfall-pilot-cli profile CONFIG COMPARISON
clojure -M -m resolver-sim.research.shortfall-pilot-cli controlled-profile \
  CONFIG PILOT_PROFILE CONTROL_POLICY ACTIVATION_HISTORY
clojure -M -m resolver-sim.research.shortfall-pilot-cli apply \
  CONFIG PROFILE HISTORY SIGNED_ACTION EVIDENCE_MAP CHECKPOINT
clojure -M -m resolver-sim.research.shortfall-pilot-cli replay \
  CONFIG PROFILE HISTORY EVIDENCE_MAP
clojure -M -m resolver-sim.research.shortfall-pilot-cli controlled-apply \
  CONFIG CONTROLLED_PROFILE CONTROL_POLICY HISTORY SIGNED_ACTION EVIDENCE_MAP CHECKPOINT
clojure -M -m resolver-sim.research.shortfall-pilot-cli controlled-replay \
  CONFIG CONTROLLED_PROFILE CONTROL_POLICY HISTORY EVIDENCE_MAP
```

`METADATA` contains only `:subject-root`, `:environment-root`, `:runner-root`
and `:assignment-fields` (issued assignment ID, policy hash, review-round hash,
target and plan root). No expected output is accepted. The context constructor
derives command, model, domain, assignment input binding, outcome, closure and
analytical output. Model interpretation is terminal allocation in capital units;
the domain is explicitly a single observed policy-pair case, not an exhaustive
incentive proof. Challenges must declare proposition
`:claim/allocation-policy-invariance` and metric
`:metric/maximum-row-allocation-difference`.

`EVIDENCE_MAP` maps comparison roots to complete comparison bodies. Every
historical submission needs its body retained. The gate freshly reruns the
allocator and rebuilds the entire submitted context, rejecting altered values,
witnesses, missing bodies, policy substitutions and mismatched provenance.
It runs after signature/history validation and before atomic history replacement
under the existing admission lock. Reviewer approval and incentive eligibility
still require their separate signed action. Use this gated entry point for pilot
submission **and review**; ordinary V1 admission does not establish economic
replay assurance. The controlled profile is a successor composition root over
the unchanged V1 arithmetic-pilot profile and existing control-policy root.
Controlled apply validates the resulting full history under the same OS lock,
before atomic replacement. A withdrawal/revocation cannot bypass arithmetic
replay for historical submissions. Controlled publication is a separate signed
successor artifact that binds the base profile, derived controlled profile,
control policy, exact public files and controlled checkpoint.

Replay also returns ordinary typed PRF evidence nodes for method validation.
Their stable node hashes are independent of the generic audit timestamp/record
envelope. Comparison results do not change when runner attribution changes.
These are same-implementation replays, not independent-implementation evidence.
Calibration remains known, unrewardable teaching material.

## Process failure and exchange qualification

The existing controlled-history writer now has opt-in test-only barriers at
three boundaries: before file force, after file force/before atomic replacement,
and after atomic replacement/before acknowledgement. The process test
force-kills the JVM at each boundary, starts a fresh JVM on the same files,
replays history, then retries the identical signed control. Before replacement,
the old history replays and retry applies once. After replacement, the new
history replays and stale redelivery is rejected without a second transition.
The barrier directory and markers do not enter canonical actions or results.
This qualifies process termination at these file-write boundaries only; it does
not establish power-loss or directory-fsync durability.

The arithmetic-gated controlled-apply composition now uses the same writer and
performs its arithmetic gate before persistence. Focused integration coverage
checks a signed submission, reviewer withdrawal, replay and rejected admission.
A separate literal process-kill test exercises this exact combined workflow at
all three persistence boundaries. It proves the arithmetic gate completed
before each boundary, old history is retained before atomic replacement, and
committed history survives replacement; fresh-process replay and retry then
show one accepted transition at most. This qualifies JVM process termination at
those boundaries only, not power-loss or directory-fsync durability.

Exchange files and hashes between the operator and two external researchers.
An independent observer receives public artifacts and required evidence bodies,
verifies the signed publication and reconstructs the same history, quotations,
acceptances and obligations. Test a missing evidence body, changed file,
duplicate submission, forged attribution and stale publication. Retain opaque
reference bodies wherever reviewer decisions depend on them. Use an explicit
restricted channel for evidence unsuitable for public Git disclosure.

## Completion gate

Call the milestone **Researcher Network Pilot V0 Qualified** only after:

1. A real challenge and its terms/source are frozen and signed.
2. Two external pseudonymous researchers exchange actual evidence independently.
3. Economic replay and existing analytical verification both pass.
4. Reviewer acceptance and incentive eligibility are separately recorded.
5. Three unique eligible accepted contributions advance one researcher, and its
   subsequent quote is 5/4 while historical quotes remain unchanged.
6. Another researcher provides an attributable fresh reproduction and distinct
   qualifying work. Duplicate/replayed contributions earn no additional credit.
7. An independent observer reconstructs the reward obligations from the published
   history and retained evidence, with the targeted crash tests passing.

Record reviewer time, researcher replay/setup effort, failed submission attempts
and evidence-exchange friction as operational pilot measurements. They do not
determine canonical reward amounts or experience. Payment execution, verified
human independence, permissionless public intake and broader operations remain
separate qualification questions.
