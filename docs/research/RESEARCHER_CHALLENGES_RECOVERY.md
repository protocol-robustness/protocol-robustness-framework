# Researcher Challenges: revocation and review-correction audit

Status: implemented as an **explicit opt-in control-policy composition**. V1
configuration/action contracts and ordinary V1 replay remain unchanged. The
original audit below explains why controls cannot be silently added to V0.
This remains a controlled offline pilot, not a key-recoverable public service.

## Existing contracts and concrete gaps

| Existing contract | Reuse | Limitation for this workflow |
| --- | --- | --- |
| Challenge configuration/action V1 | Frozen reviewer public key, scoped signed actions and causal history prefix | No recovery authority, key epoch, revocation or correction action |
| Scoped researcher reference | Canonical Ed25519 key control | Reference includes the key; changing keys changes the reference, not automatically the same researcher |
| Research claim acceptance V1 | Exact claim/verification/policy/authority bindings | Signature authentication is supplied by the challenge action, not the acceptance builder |
| Research claim lifecycle V1 | `accepted/rejected → superseded`, append-only history and bound reconstruction | Does not permit `superseded → accepted/rejected` or authenticate the issuer of a challenge correction |
| Researcher reward V1 | Immutable eligible proposal and approved obligation | No voiding, clawback, debt adjustment or payment authorization |
| Generic attestation revocation/lifecycle | Historical artifacts are not mutated | Revokes attestations, not challenge signing-key authority or reward obligations |
| Governed signer-key-set V1 | Rooted key material in existing governed workflows | Not a challenge registration/recovery authority; importing it would not establish one |

Replacing the public key and recomputing the configuration creates a different
configuration. Old signed actions bind the old scoped authority and history;
they do not become a valid migrated journal. Mutating enrollment public-key text
does not transfer a researcher reference or its experience. Adding an in-memory
revocation registry would make journal replay depend on unrooted present state.
None of these is an acceptable shortcut.

## Implemented composition contracts

Two canonical contracts are necessary:

- `researcher-challenge-control-policy.v1` binds the frozen V1 configuration,
  network, trusted activation history root/count and the exact fixed control
  rules. The policy is derived from a resolved V1 prefix; no caller-selectable
  relaxation, recovery authority or policy DSL exists.
- `researcher-challenge-control.v1` binds that policy, configuration/network,
  preceding history root, pinned reviewer reference, kind, nonce and payload.
  Admission verifies the actual Ed25519 signature and every binding. Control
  consequences reuse generic evidence records and claim lifecycle artifacts.

Neither is a replacement for claim acceptance, participant assurance or reward
V1. They use explicit canonical domain strings; shared hashing was not changed.
No mutable revocation registry or parallel evidence graph is introduced.

## Narrow successor boundaries

The implementation is an explicit composition of challenge action/replay
semantics, with a rooted opt-in to the new control rules. It
must preserve historical V1 reconstruction and existing accepted artifacts.
Do not append new meanings to `researcher-challenge-action.v1`, use a caller
`authorized?` flag, or let the latest mutable public-key file define authority.

### Participant key revocation

The pinned reviewer can prospectively disable an enrolled participant reference
for new actions. The authenticated event binds the exact configuration,
network, preceding history root, target enrollment/reference and reason/evidence
root. Its effective logical sequence is derived from
its admitted position, never a caller-supplied time. The event is signed and
admitted by the pinned reviewer.

Replay applies authority at each logical history prefix. A revocation must not
make pre-cutoff signatures invalid or erase work, quotes, decisions or obligations.
Post-cutoff new participant actions are refused. Merely choosing an earlier
wall-clock timestamp cannot evade the logical cutoff. Exact control-event replay
does not add another transition: replayed/stale or semantically repeated events
are rejected without changing history.

Revocation alone does not rotate or recover a key, transfer experience to a new
reference, or prove that two references belong to one human. Reviewer-key loss
is a separate bootstrap problem: V0 has no second trusted recovery authority.
Do not let an arbitrary replacement reviewer authorize itself. Until a recovery
authority/continuity policy is explicitly approved and rooted, freeze intake and
preserve the last trusted checkpoint rather than fabricate rotation.

### Review withdrawal/correction

The implemented operation is authenticated **withdrawal/supersession**, not a
replacement verdict. The payload binds exact submission, prior signed review,
reason and justification root. Replay resolves and binds the original acceptance
and lifecycle transition in its consequence, then derives the
existing lifecycle supersession from the resolved acceptance; never accept
independently supplied claim/policy/authority fields. Preserve the original
decision and original signature as historically valid evidence.

Generic lifecycle V1 can represent supersession without changing the claim,
verification, original acceptance or original transition. A later new verdict
requires an explicitly defined successor decision lineage: do not re-open
`:superseded` or silently replace the review map. The challenge must authenticate
the withdrawal; calling a generic builder is not sufficient authority.

Three separate views will be necessary:

- historical accepted work and issued obligations;
- current active acceptance/qualification;
- the immutable experience snapshot used by each already-issued quote.

The approved conservative rule is to exclude withdrawn accepted contributions
from **future** experience snapshots while keeping all past quotes byte-exact.
Historical work remains inspectable, but should not be mislabeled currently
accepted. Keep duplicate-contribution reservations consumed: withdrawing a
decision must not make the same witness eligible for a second obligation.

An existing reward obligation is never deleted, rewritten, reduced or called
void by review correction. Externally recorded payout assertions also remain
historical. If the operator wants a new present-tense hold/disposition policy,
define and authorize it separately; neither supersession nor this composition
implements payment execution, financial clawback or cancellation of a liability.

## Qualification requirements

The successor must demonstrate:

1. Old V1 configuration/history still replay to identical roots and consequences.
2. Revocation is signed by the exact authorized key and bound to its exact
   target/prefix; wrong target, foreign network, forged issuer and stale replay fail.
3. Historical signatures remain reconstructible; post-cutoff new participant
   actions fail even when backdated. New key/reference is not automatic continuity.
4. Withdrawal uses exact prior acceptance lineage, records generic supersession,
   preserves the old decision, and cannot be forged, duplicated or cross-substituted.
5. Any approved prospective experience adjustment leaves historical snapshots,
   tier quotations, obligations and payout records unchanged.
6. Withdrawn contributions cannot acquire duplicate rewards; reconstructed
   current state and historical state remain distinct.
7. Ordinary forensic DAG/checkpoint/dossier paths expose control causality without
   introducing a parallel control/evidence registry.

Focused tests cover local controlled replay/admission, revocation, withdrawal,
prospective experience, immutable history/obligations, signed substitution
negatives, checkpoints, dossiers and the existing generic forensic DAG. Saved
pre-control V0 artifact reconstruction is also checked. The literal subprocess
suite additionally signs and admits withdrawal/revocation in separate fresh
JVMs, reconstructs controlled state from persisted files, rejects replay and
stale checkpoints without changing history bytes, and validates the generic
forensic DAG. Withdrawal preserves historical obligations while removing only
active experience; revocation remains effective when a fresh process reads the
history. Full state, summary, dossier and forensic reconstruction commands have
a 180-second subprocess bound; other CLI commands retain a 60-second bound.
Timed-out children are forcibly stopped and reaped. This is clean
process-exit/reconstruction qualification, not a forced
crash or power-loss test. Cross-host controls,
process-kill during control admission, power-loss durability and public operation
are not qualified by these tests. Reviewer recovery and replacement verdicts
are not implemented.

## Approved scope and deferred operations

The implemented scope is:

- participant revocation and review withdrawal only, by the pinned reviewer;
- reviewer rotation/key loss and identity/experience transfer deferred;
- withdrawn contributions excluded from future experience only;
- prior quotes, issued obligations, duplicate reservations and payout records
  preserved without economic reinterpretation;
- replacement acceptance decisions deferred until their lineage is specified.

These are explicit admission/authority semantics, not packaging changes. No
public service or external payment consequences are inferred from this local
qualification.

## Offline control workflow

Freeze the trusted activation prefix before any controls:

```sh
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  control-policy configuration.edn history.edn > control-policy.edn
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  control-checkpoint configuration.edn control-policy.edn history.edn > control-checkpoint.edn
```

Publish the policy root and checkpoint through the same separately trusted
operator channel as the configuration. The checkpoint includes the exact control
policy root; it does not certify latestness or authenticate publication.
Retain the original activation prefix. Do not regenerate a different policy at
a later prefix, mix ordinary V1 admission with controlled admission, or drop
controls to make a revoked key appear current.

Controls use the usual request-file shape: `:actor` is the pinned reviewer
reference, `:challenge-root` is nil, `:nonce` is unique, and `:kind` selects the
operation. Their exact payloads are:

```clojure
;; :kind :revoke-participant
{:researcher/root "sha256:..."
 :enrollment/root "sha256:..."
 :reason :key-compromised
 :evidence/root "sha256:..."}
;; :kind :withdraw-review
{:submission/root "sha256:..."
 :review/action-root "sha256:..."
 :reason :review-error
 :evidence/root "sha256:..."}
```

Revocation reasons are `:key-compromised`, `:participant-request` or
`:operator-suspension`. Withdrawal reasons are `:review-error`,
`:evidence-invalidated` or `:incentive-review-error`. References are exact
artifact roots, not participant labels. Justification roots remain opaque;
retain their actual bodies separately. No backdated effective cutoff is accepted.

```sh
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  control-request configuration.edn control-policy.edn history.edn request.edn control-checkpoint.edn \
  > unsigned-control.edn
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  sign unsigned-control.edn /local/reviewer/key > signed-control.edn
clojure -M -m resolver-sim.research.researcher-challenges-cli \
  control-apply configuration.edn control-policy.edn history.edn signed-control.edn control-checkpoint.edn
```

Refresh/publish the checkpoint after successful admission. `control-request` and
`control-apply` also handle ordinary participant/reviewer V1 actions under the
composed rules, with unchanged action identities. Both verify replay; file
admission checks the optional checkpoint inside the existing OS lock and uses
the same fsync/atomic-replacement path. Failed controls leave history byte-exact.

Other read-only commands use `CONFIG POLICY HISTORY` followed by their target:

- `control-state`: reconstructed current state plus historical artifacts;
- `control-verify-checkpoint ... CHECKPOINT`: full replay against trusted prefix;
- `control-summary ... RESEARCHER_ROOT`: active experience/work, revocation,
  withdrawals and separate historical accepted work/obligations/payout records;
- `control-review-dossier ... SUBMISSION_ROOT [CHECKPOINT]`: original signed
  decision plus withdrawal and current `:superseded` status;
- `control-forensic`: ordinary typed generic evidence nodes, including control
  causality and exact lifecycle supersession.

An enrolled revoked researcher retains historical attribution/credit but cannot
submit new work. The reviewer may still review valid pre-cutoff submissions;
revocation is not automatic withdrawal. Accepted-but-ineligible and rejected
reviews never gain experience through withdrawal. Dossiers retain configuration,
policy and signed history for reconstruction and explicitly mark external
justifications unresolved. No command changes an issued obligation or payout
assertion. The pinned reviewer cannot revoke/rotate itself through this protocol.
