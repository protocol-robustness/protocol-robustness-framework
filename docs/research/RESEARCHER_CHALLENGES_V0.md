# Researcher Challenges V0

The proposed economic pilot and its remaining prerequisites are documented in
[Researcher Network Pilot V0](RESEARCHER_NETWORK_PILOT_V0.md). Its shortfall
calibration inputs execute the existing allocator; they are not accepted
contributions or published challenge definitions.

This is an offline signed-file testnet participation experiment. An operator
publishes a frozen catalog and history checkpoint; pseudonymous researchers
enroll, reserve tasks and submit existing PRF analytical evidence. The designated
reviewer signs acceptance and incentive decisions. Replay derives experience,
quoted reward terms, reward obligations and researcher summaries.

## Inspected contracts and extension seam

The implementation composes `research-analysis/verify-output`, `derive-claim`,
`research-claim-acceptance.v1`, `research-claim-lifecycle-transition.v1`, the
existing V1 reward eligibility/proposal/obligation pipeline, Ed25519 signing,
canonical hashing and typed generic forensic evidence nodes. Acceptance V1
validates authority assertions and treats decision evidence as opaque. The V0
signed reviewer action authenticates that assertion against a pinned key.

Five canonical contracts are necessary:

| Contract | Purpose |
| --- | --- |
| `researcher-challenge.v1` | ID, exact subject root/revision root, supported contribution type, explicit criteria, base amount, capacity and reward policy root |
| `researcher-reward-policy.v2` | Frozen experience tiers, cutoff, duplicate rule, currency and integer rounding |
| `researcher-challenges-configuration.v1` | Immutable network scope, reviewer key, policy and catalog; the operator must publish its trusted root |
| `researcher-challenge-action.v1` | One signed envelope for enrollment, task claim, submission, review and external payout recording |
| `researcher-challenge-task-claim.v1` | Derived quote binding its request, exact historical experience snapshot, tier, base and final amount |

The experience snapshot is embedded in the quote and committed with the existing
evidence-record domain. No editable score, identity provider, separate evidence
graph or new payout system is introduced. Hashing uses the existing recursive
canonical-safe projection for set-bearing PRF bodies; existing encoding and
V1 artifacts are unchanged. New contract domains are explicit strings and need
no shared `canonical.clj` change.

## Supported V0 challenge

The first contribution channel is `:adversarial-finding`: a verified analytical
counterexample. Criteria contain an exact proposition ID, measure ID and
`#{:counterexample-found}` classification. Other contribution channels are
rejected until their evidence validators are explicitly integrated. In
particular, source projection cannot acquire a reproduction label here.

Submission contains the six resolved authoritative inputs already accepted by
`verify-output`: research command, incentive model, deviation domain, research
assignment, outcome manifest and analytical output. Verification is recomputed;
caller-supplied verification or claim artifacts are not accepted. The analytical
claim is derived through the existing path and matched to the challenge subject
and criteria. The subject revision root identifies the operator's frozen subject
basis; any broader source-to-revision validation remains with existing subject
provenance tooling.

Generic analysis verification establishes the existing context/root/closure
checks. It does not independently execute a submitted EVM transition or prove
the finding mathematically. The designated reviewer must inspect that evidence
before signing acceptance. A signature authenticates the reviewer's decision;
it does not upgrade its epistemic meaning.

## Control of a pseudonymous reference

A reference derives from network scope and canonical Ed25519 public-key bytes.
Equivalent public-key text cannot create another reference. Enrollment signs
the complete scoped action, including current history root and nonce; the
admission path verifies key control. Subsequent submissions authenticate against
the enrolled key. Reviewer actions authenticate against the configured key.
The reviewer key cannot enroll as a researcher in this experiment.

Private keys remain in the researcher's local signing workflow. Names, passwords,
real-world identities and researcher scores do not enter these artifacts. Two
keys establish distinct references only; different people, organizations,
operators or machines remain unknown. Existing researcher-axis membership and
participant-assurance V1 meanings are unchanged.

## Quotation and experience

| Previously accepted unique contributions | Multiplier |
| --- | --- |
| 0–2 | 1/1 |
| 3–7 | 5/4 |
| 8+ | 3/2 |

Amounts are integer minor units: `floor(base × numerator / denominator)`.
The quote is derived when the signed task claim is admitted, using the exact
preceding history. It records the count, unique canonical claim roots, accepted
review roots and policy. Later contributions never change that quote. An action
whose history prefix has changed must be rebuilt and signed again.

Experience counts unique subject/proposition/measure/witness tuples for that reference within the
configured network. Every counted claim has a valid signed submission,
recomputed verification, authoritative acceptance and an explicit eligible
incentive decision. Rejected, unreviewed or accepted-but-ineligible work counts
zero. Rewrapping the same witness in another assignment or claim cannot pay twice
under the same challenge revision. Repeated use of one witness across different challenges does not advance
experience, even where separate challenge policies allow separate obligations.
External historical contributions are not imported implicitly across identities
or network scopes.

Each researcher can reserve one task per challenge revision. Capacity bounds
all admitted reservations, including terminal ones. V0 has no expiry, release
or reassignment mechanism. A copied contribution cannot qualify twice under the
same challenge ID and subject revision, including when copied by another key.
Replay of an action, nonce, submission or reviewed submission is rejected.

## Review, incentive eligibility and obligation

The reviewer signs a request containing the submission root, acceptance decision,
separate incentive decision, decision/evaluation evidence references and explicit
acceptance time. Acceptance uses the existing V1 artifact and lifecycle; policy
identity is scoped to the exact challenge definition. Rejection cannot declare
eligible incentives. Acceptance with ineligible incentives remains valid review
history but produces neither experience credit nor an obligation.

Eligible accepted work creates a concrete V1 fixed-amount policy from the V2
schedule and frozen quote. It then passes through existing V1 eligibility,
proposal and authorized obligation builders. The derived policy binds challenge,
V2 policy and task claim. Eligibility binds the exact acceptance, verification,
signed reviewer decision and experience snapshot. The obligation remains
independently reconstructible through those constituent roots. No eligibility
or approval flags are accepted from researcher submissions.

## Offline participation interface

### Prepare the first operator-selected challenge

The `prepare` command removes manual subject/root and reward-policy copying. It
validates a resolved `incentive-model.v1` (including its required self-root),
derives the subject from that model, and constructs the existing configuration,
challenge and V2 policy. It adds no canonical schema and produces no findings,
enrollment, acceptance, experience or reward obligations.

An operator preparation file has exactly these fields:

```clojure
{:network-id :testnet/researcher-challenges
 :reviewer-public-key "<reviewer's public Ed25519 key content>"
 :incentive-model <complete existing rooted incentive-model.v1>
 :subject-revision "sha256:<reviewed subject revision>"
 :challenge-id :challenge/first-counterexample
 :proposition-id :claim/incentive-compatibility
 :metric-id :metric/net-payoff-delta
 :currency "TEST-POINTS"
 :base-reward 100
 :capacity 2}
```

Angle-bracket values above are instructions, not executable EDN. Replace them
with actual public key content and authoritative artifact bodies/roots. Reward
terms and capacity are operator choices, not defaults imposed by the command.
No private key, analytical output, acceptance or experience count is accepted.

```sh
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  prepare operator-preparation.edn > configuration.edn
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  state configuration.edn history.edn
```

Retain the preparation file and canonical model with the configuration. Review
the source-to-subject/revision lineage and publish the trusted configuration root
before inviting participants. Preparation validates artifact identity, not that
the declared model accurately describes a protocol or that its proposition is
true. It does not independently resolve the supplied subject revision.

Existing V1 challenges bind subject, revision and proposition/measure criteria,
**not an exact incentive-model root**. Different valid models of the same subject
can therefore prepare the same challenge. The retained model is an operator
basis; this command does not silently add a model-selection constraint to V1
submission admission. Requiring an exact model at admission needs an explicit
successor/composition contract, not a hidden preparation check.

The checked-in participation demo remains qualification-only. No substantive
challenge is activated by preparation. The yield partial-fill benchmark's
operational/invariant evidence is not automatically an incentive-compatibility
analytical counterexample; do not relabel it to fit this channel. Launch still
requires an operator-selected, reviewed model and real submitted evidence.

Build the configuration with `configuration`, `build-challenge` and
`build-reward-policy` in `resolver-sim.research.researcher-challenges`, using
public key content and existing subject/evidence roots. Save that public artifact
as `configuration.edn`. Keep the same configuration throughout this history.

Request files have exactly these fields:

```clojure
{:actor "sha256:..."
 :kind :enroll
 :challenge-root nil
 :nonce "researcher-selected-unique-nonce"
 :payload {:public-key "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----\n"
           :disclosure :pseudonymous}}
```

Derive the actor with `(researcher-reference (:network/root configuration)
public-key-content)`. `:claim` payload is `{}` and binds a challenge root.
`:submit` payload is `{:task-claim/root quote-root :evidence/context context}`.
The request builder uses the current published prefix; it accepts no count or
reward fields. Researchers can inspect the public catalog/policy before signing.

```sh
clojure -M -m resolver-sim.research.researcher-challenges-cli catalog configuration.edn
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  request configuration.edn history.edn request.edn > unsigned-action.edn
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  sign unsigned-action.edn /local/private/key > signed-action.edn
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  apply configuration.edn history.edn signed-action.edn
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  summary configuration.edn history.edn sha256:RESEARCHER_REFERENCE
```

The operator exchanges signed files with participants using its chosen transport.
There is no public server. The CLI locks the history file, replays it completely,
validates the next signed action, then replaces the history atomically after
forcing the temporary file. Failure leaves prior history unchanged. Filesystem
power-loss durability, malicious rollback and cross-host concurrent writers
are not qualified. Retain signed actions and publish the resulting history root.
Independent replay can take that trusted root as its third argument to detect
prefix omission or substitution. Signatures alone cannot establish that an
unpublished history prefix is the latest authoritative state.

### Publish and check an exact history checkpoint

The operator can export replay-derived publication metadata:

```sh
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  checkpoint configuration.edn history.edn > checkpoint.edn
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  verify-checkpoint configuration.edn history.edn checkpoint.edn
```

This plain EDN map contains only configuration root, network root, history root
and action count. It is not another canonical artifact, signature, attestation
or claim of latestness. Publication requires a **separately trusted channel**:
participants must obtain the pinned configuration/checkpoint from the operator,
not trust a checkpoint supplied solely by the same untrusted history provider.
No online publication service or trust bootstrap is implemented.

Replay checks all signatures and consequences and compares all four fields.
Missing actions, a different configuration/network, substituted roots, incorrect
counts or extra/missing checkpoint fields are rejected. An old valid prefix and
its matching old checkpoint still validate internally; the application must
establish which published checkpoint is current through that trusted channel.

Participants and the operator can require this prefix check before constructing
or admitting an action, without changing its existing canonical identity:

```sh
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  request configuration.edn history.edn request.edn checkpoint.edn > unsigned-action.edn
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  apply configuration.edn history.edn signed-action.edn checkpoint.edn
```

Admission checks the checkpoint **inside the history file lock**, before applying
the signed action. A stale checkpoint causes rejection without changing history.
The optional argument preserves existing offline workflows; it is not an
optional weakening of action signature/history binding. Every successfully
admitted action advances the prefix, so export and publish a new checkpoint
after each admission. Do not use the pre-admission checkpoint to certify the new
history, and do not overwrite an archived checkpoint when publishing its successor.
An absent history file denotes the initial empty history only; comparison with
a checkpoint for a nonempty history fails.

`researcher-summary` derives accepted work, its evidence and verification roots,
acceptance/eligibility roots, frozen quoted tiers, obligations and payout records.
Reviewed work also exposes rejected and ineligible decisions. Experience is a
derived count; accepted work never implies increased claim authority.

## Evidence and external payout records

### Reviewer inspection and retention

Export a read-only dossier for one admitted submission:

```sh
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  review-dossier configuration.edn history.edn sha256:SUBMISSION_ROOT checkpoint.edn \
  > internal-review-dossier.edn
```

The optional checkpoint enforces the exact published prefix before inspection.
The dossier is an ordinary derived report, not a new canonical artifact or an
acceptance/eligibility receipt. Full replay verifies signed history first. It
contains the challenge, enrolled public reference, complete submission context,
verification and claim, frozen quote, and any existing signed review and its
derived acceptance/lifecycle/eligibility/reward artifacts. Unknown submissions
or invalid history are rejected. Export does not change history or grant credit.

The report retains the exact configuration, signed history and checkpoint, so
an offline reader can replay the authoritative sources and reconstruct the
ordinary forensic DAG. Regenerated node roots must agree; runtime audit timestamps
need not. No second evidence graph or cached reviewer authority is introduced.

**Keep the dossier internal unless disclosure is separately authorized.** It
includes the complete selected history, potentially other participants' nonpublic
evidence. It is not a public summary or a confidentiality mechanism. It contains
no signing keys; file permissions, backup/disclosure policy and secure exchange
remain operator responsibilities.

Before signing any review, the designated reviewer should:

1. Obtain the pinned configuration and current checkpoint through the trusted
   operator channel; inspect the exact subject/revision and challenge criteria.
2. Inspect the submission's authoritative context, verification limits and
   counterexample witness. Generic verification checks bindings/closure, not an
   independent EVM replay or mathematical proof of the finding.
3. Retrieve and retain the underlying evidence bodies and their existing canonical
   commitments. The dossier explicitly lists unresolved analysis-evidence and
   counterexample-witness references; their presence is not proof of availability.
4. Record the acceptance justification separately from the incentive evaluation
   (including originality/duplicate and conflict-of-interest inspection). The
   reviewer is responsible for the opaque incentive evaluation; no experience
   score or runner agreement substitutes for that inspection.
5. Construct an explicit `:review` request, sign it locally, and admit it against
   the current prefix using the existing `request`, `sign` and `apply` commands.
6. Export a post-review dossier and successor checkpoint; retain the signed action
   and the justification/evaluation bodies. Verify the archive by replay.

The request actor is `(researcher-reference (:network/root configuration)
(:reviewer/public-key configuration))`, not the submitting researcher. Its
challenge root must match the submission. The exact review payload is:

```clojure
{:submission/root "sha256:..."
 :decision :accepted                ; or :rejected, explicitly chosen by reviewer
 :incentive/decision :ineligible     ; or :eligible, a separate explicit decision
 :incentive/evidence-root "sha256:..."
 :decision/evidence-root "sha256:..."
 :accepted-at "2026-10-10T12:00:00Z"} ; actual explicit review time, not a default
```

The example is a shape, not a recommended decision or evidence. The full request
also requires actor, `:kind :review`, challenge root, and a unique nonce. Rejection
cannot be incentive-eligible. Acceptance with ineligible incentives remains
accepted history but creates no obligation or experience credit. After a review
is admitted, V0 rejects another review of that submission. The explicit
[control-policy composition](RESEARCHER_CHALLENGES_RECOVERY.md) supports
authenticated withdrawal, not replacement verdicts or editing signed history.

Post-review dossiers also identify unresolved justification and incentive-evaluation
references from the exact signed action. These are opaque rooted references under
the existing V1 contracts. Export does **not** fetch their bodies, recompute an
unknown artifact schema's identity, certify their substance, or claim full external
evidence availability. An archive of only the dossier is sufficient to reconstruct
the signed decision and its consequences, but not to inspect every underlying
witness/justification. Retain those artifacts separately with their schema and
content commitments; never fabricate them from a root or treat a missing body as
successful evidence verification. Backup durability and adversarial archive storage
are unqualified.

`forensic-nodes` uses the existing typed evidence-node DAG. The causal action
chain and separate verification, claim, acceptance, lifecycle, eligibility,
proposal and obligation nodes remain inspectable. The full frozen configuration
and signed history reconstruct the bodies and validate every binding.
Evidence nodes retain the generic runtime audit timestamp outside their canonical
node identity. Replay must reproduce node roots and canonical content; regenerated
audit timestamps need not equal the original record envelope. Public
summaries may expose commitments; a history containing nonpublic evidence needs
operator-controlled disclosure. Confidential submission handling is unqualified.

Only the reviewer can append payout records for a known obligation. Status is
`:recorded-pending`, `:recorded-paid` or `:recorded-failed`, with an external
reference. All records remain visible; the obligation is unchanged. These are
authenticated external assertions, not observations of on-chain settlement.

## Qualification boundary

Focused validation covers real Ed25519 signatures for two references, distinct
submissions to one challenge, tier advancement, frozen-rate preservation,
duplicate/copied/replayed evidence, forged attribution, unauthorized review,
capacity, ineligible/rejected decisions, deterministic reconstruction, ordinary
DAG validation, file replay and externally recorded payout status.

A portable qualification bundle (configuration, signed history, generated
summaries and ordinary evidence nodes; no private keys) can be generated with:

```sh
clojure -M:test -m resolver-sim.research.researcher-challenges-test \
  /tmp/prf-researcher-challenges-v0-demo.edn
```

Its analytical inputs are reference test fixtures, explicitly classified as
qualification-only. Different keys and witness commitments demonstrate the
participation mechanics; this fixture establishes no human independence or
substantive research finding.

### Literal process participation qualification

The process test invokes the production CLI in fresh JVMs with isolated working
directories for two participants, the operator and an independent observer.
Each participant generates an ephemeral Ed25519 key, constructs a request and
signs it through the actual CLI. Both initially use the same published prefix:
after one enrollment advances history, the other's stale request is rejected
and must be rebuilt and re-signed. Forged attribution, duplicate admission and
stale checkpoint use fail without changing the authoritative history.

The observer separately replays the portable **qualification-only** accepted
history and reconstructs its exact researcher summary and reward obligations
using public artifacts, without private signing keys. This replay does not
represent new substantive findings or fresh reviewer decisions. State,
summary, dossier and forensic reconstruction commands have a 180-second child
bound; other CLI commands retain a 60-second bound. Timed-out children are
forcibly stopped and reaped. File-backed output streams and cleanup cover every
ephemeral process/key/file. Host paths and process identity remain operational.

The same suite also exercises the opt-in control composition in fresh JVMs:
reviewer signing, withdrawal admission, observer reconstruction of reduced
active experience with historical obligations intact, revocation admission,
and refusal of subsequent participant requests. Duplicate controls and stale
checkpoints leave persisted history unchanged. The observer validates the
existing generic evidence DAG without receiving private keys. Restart here
means a fresh process reading persisted state after a clean prior command exit;
forced-crash and power-loss cutpoints remain unqualified for control admission.

```sh
clojure -M:test -e "(require 'resolver-sim.research.researcher-challenges-process-test) \
  (let [r (clojure.test/run-tests 'resolver-sim.research.researcher-challenges-process-test)] \
    (System/exit (+ (:fail r) (:error r))))"
```

This qualifies process-separated file exchange and independent replay on one
machine. It does **not** qualify cross-host transfer, filesystem access isolation,
public intake, crash/power-loss durability, reviewer operations or authenticated
checkpoint distribution. Private keys are test-generated and stay in participant
directories; production keys must not be included in exchanged files. A real
multi-host pilot should exchange these same public files, not invent a second
submission format.

Public service deployment, rate limiting, denial-of-service resistance, public reviewer
operations, reviewer-key recovery/rotation, cross-host controls, Sybil resistance, organizational independence,
public publication/checkpoint availability, real payments and network delivery
are unqualified. This experiment does not alter primitive correctness,
claim derivation, researcher positions, pro-rata arithmetic or existing V1
reward/participant-assurance contracts.

The [revocation/correction composition](RESEARCHER_CHALLENGES_RECOVERY.md) implements
the approved conservative rules through two new control contracts and explicit
opt-in replay. Ordinary V0 rejects control action kinds and repeat reviews.
Generic lifecycle supersession alone does not authenticate a challenge withdrawal
or cancel an issued obligation; controls supply signature and history binding.

Run focused tests:

```sh
clojure -M:test -e "(require 'resolver-sim.research.researcher-challenges-test) \
  (clojure.test/run-tests 'resolver-sim.research.researcher-challenges-test)"
```
