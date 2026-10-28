# PRF Network Checkpoint Protocol v1

Status: **Proposed; requires policy-owner review before implementation is treated as normative**  
Issue: PRO-92  
Scope: generic, policy-certified checkpoints over PRF evidence DAG state  
Canonical artifacts: `network-checkpoint.v1`, `checkpoint-certificate.v1`, `head-observation.v1`

## 1. Purpose and limits

This protocol lets independent verifiers check that a particular content-addressed
DAG state was accepted under a named authority policy, extends a previously
accepted checkpoint, and was reported by specified observers as their current
head within a configured freshness window.

The underlying evidence DAG may contain concurrent branches. Checkpoints form a
linear sequence that selects and commits to an accepted state at chosen
boundaries. A checkpoint does not require agreement about every message arrival
or insertion order.

A valid certificate establishes **certification under its named policy and
trusted authority snapshot**. It does not by itself establish Byzantine-fault
tolerant finality, universal agreement, manifest completeness outside the
checkpoint policy's trust boundary, availability, or global latestness. A
head observation establishes what an authenticated source reported; even
matching fresh reports cannot prove that a newer checkpoint is not being withheld
from all queried sources.

This is a small certification and observation protocol, not a consensus engine.
It adds no peer discovery, leader election, gossip, staking, mandatory chain
anchoring, or automatic fork choice. It does not grant research, accounting,
Sew, settlement, or capital authority.

## 2. Existing PRF mechanisms and boundary

| Existing mechanism | Reuse in this protocol | Not supplied by it |
|---|---|---|
| Canonical hash scheme and domain-separated hashing | Exact content identities for checkpoints, certificates and observations | Agreement that a supplied event set is complete |
| Evidence execution nodes and DAG validation | Node integrity, declared parent references, cycle and reachability checks | Network ordering, head selection or currentness |
| Signed researcher decisions and signed external decision envelopes | Signing and signature-verification primitives | Generic network membership or checkpoint quorum policy |
| Frozen review rounds and three-member authority evaluation | Examples of pinned eligibility, distinct principals, signatures and equivocation handling | Generic checkpoint semantics; 2-of-3 is not BFT-safe by default |
| Research claim acceptance and ratification replay | Separate domain acceptance/ratification over exact subjects | Network checkpoint certification or operational authority |
| Authority and transition artifacts | Policy-defined membership/policy updates, verified through an explicit adapter | Automatic acceptance of arbitrary policy roots |

A checkpoint certificate cannot be substituted for a domain ratification, a
claim-acceptance record, a transition authorization, or an operational receipt.
A domain result may be included as an accepted event in a checkpoint; its own
domain verifier remains responsible for its meaning and authority.

## 3. Canonical artifact 1: `network-checkpoint.v1`

### 3.1 Projection

The canonical checkpoint body has exactly these fields:

```clojure
{:checkpoint/schema "network-checkpoint.v1"
 :network/genesis-root "sha256:<64 lowercase hex>"
 :checkpoint/epoch <non-negative integer>
 :checkpoint/resulting-epoch <non-negative integer>
 :checkpoint/sequence <non-negative integer>
 :checkpoint/previous-root nil-or-sha256
 :checkpoint/accepted-event-manifest-root "sha256:<64 lowercase hex>"
 :checkpoint/accepted-event-count <non-negative integer>
 :checkpoint/dag-state-root "sha256:<64 lowercase hex>"
 :checkpoint/frontier-roots ["sha256:..." ...]
 :checkpoint/governing-policy-root "sha256:<64 lowercase hex>"
 :checkpoint/governing-membership-root "sha256:<64 lowercase hex>"
 :checkpoint/resulting-policy-root "sha256:<64 lowercase hex>"
 :checkpoint/resulting-membership-root "sha256:<64 lowercase hex>"
 :checkpoint/proposer-id <canonical principal identifier>
 :checkpoint/claimed-time nil-or-ISO-8601-string}
```

The root is
`sha256:` plus the lowercase hex result of `SHA-256(UTF8("prf.network-checkpoint.v1") || canonical-bytes(body))` under the PRF canonical hash scheme. The v1 domain tags are exactly `prf.network-checkpoint.v1`, `prf.checkpoint-event-manifest.v1`, `prf.checkpoint-certificate.v1`, and `prf.head-observation.v1`. The root field is excluded from the body being hashed. No host-language sets, floating point values, implicit defaults, or unknown fields are allowed. Frontier roots are unique and sorted by their full UTF-8 root string. Integers are non-negative and exactly represented by the canonical integer type. A verifier rejects non-canonical encodings rather than normalizing them.

`network/genesis-root` identifies the network instance and trusted starting authority. It is not a freely supplied per-checkpoint label. The verifier pins it out of band or resolves it from an explicitly trusted genesis artifact.

For sequence zero, `checkpoint/previous-root` is nil and the checkpoint must match the verifier's trusted genesis bootstrap rule. Otherwise the previous root is mandatory, and sequence must be exactly the predecessor sequence plus one. `checkpoint/epoch` is the authority epoch governing this checkpoint and must equal the predecessor's `resulting-epoch` (or the trusted genesis epoch for the first checkpoint). `resulting-epoch` equals `epoch` when no authority epoch transition occurs; it equals exactly `epoch + 1` only when the accepted event manifest contains a valid transition authorized by the governing authority. It cannot decrease or skip.

`checkpoint/accepted-event-manifest-root` commits to the exact ordered list of event roots newly accepted since the predecessor. The reconstruction package must supply that complete list and every referenced event body. The reconstruction payload is the exact vector of event-root strings sorted by ascending UTF-8 bytes, with no duplicates. Its root is computed under domain tag `prf.checkpoint-event-manifest.v1` using the PRF canonical hash scheme; its count must equal the vector length. This manifest payload is reconstruction data committed by the checkpoint, not a fourth canonical protocol artifact. Event dependencies must be explicit in the event bodies. Replay uses a deterministic topological order with the full event root as the tie-breaker; cycles or ambiguous implicit ordering reject. A verifier resolves and validates each event, rejects missing, duplicate, unlisted, or unresolvable inputs, and deterministically replays the events from the predecessor state to recompute `dag-state-root` and `frontier-roots`.

The manifest is complete only relative to the acceptance rule and the prior trusted state: witnesses must independently check the manifest against the previously certified accepted-event set and the applicable policy. A bare Merkle root, reachability result, publisher assertion, or `accepted-event-count` does not prove completeness. If the policy cannot establish that no previously admitted event was silently omitted, the result must say so and cannot claim independently complete reconstruction.

`governing-policy-root` and `governing-membership-root` name the policy and frozen eligible-member snapshot that authorize this checkpoint. They must equal the predecessor's resulting roots, or the trusted genesis roots for the first checkpoint. The resulting roots describe authority state after applying this checkpoint's accepted events. They may equal the governing roots. If either changes, the manifest must include the exact authority-transition evidence, and a verifier must run the named authority adapter against the governing (old) policy and membership. The old authority must explicitly authorize the transition and its exact new roots. The new roots become governing only for the next checkpoint. A proposer cannot authorize its own new membership or policy by merely naming it in the candidate.

`proposer-id` identifies the publisher that assembled the candidate. A proposer has no signing or acceptance authority unless separately eligible under the governing policy. `claimed-time` is informational and never establishes order, freshness, expiry, precedence, or finality.

### 3.2 Checkpoint state machine

A verifier starts from a pinned genesis context containing network identity, initial authority roots, and a deterministic initial state (or an explicitly trusted sequence-zero checkpoint). For each next checkpoint:

1. Validate exact schema, canonical projection and root.
2. Require same network genesis, exact predecessor root, next sequence and valid epoch continuity.
3. Resolve and verify the complete event manifest against the prior accepted state.
4. Replay the accepted events and recompute DAG state, frontier and resulting authority roots.
5. Verify any policy/membership transition under the predecessor's authority.
6. Verify the checkpoint certificate under the predecessor's governing policy and membership.
7. Append the certified checkpoint to local retained history only after all checks pass.

A locally valid candidate is not certified until step 6 passes. A checkpoint with a missing predecessor, event, manifest entry, authority input, or transition proof is `stale-or-unknown`/incomplete, not repaired by trusting a supplied root.

If two valid certified checkpoints extend the same predecessor with different roots, preserve both checkpoints and all conflicting signatures as evidence and report `conflicting`. Do not choose by timestamp, sequence, arrival order, proposer, or arbitrary lexicographic rule. A later explicit policy-authorized resolution may extend the selected history while retaining the conflict evidence; v1 defines no automatic fork choice.

## 4. Canonical artifact 2: `checkpoint-certificate.v1`

### 4.1 Projection

```clojure
{:certificate/schema "checkpoint-certificate.v1"
 :network/genesis-root "sha256:..."
 :checkpoint/root "sha256:..."
 :checkpoint/sequence <non-negative integer>
 :checkpoint/epoch <non-negative integer>
 :policy/root "sha256:..."
 :membership/root "sha256:..."
 :certificate/endorsements
 [{:principal/id <canonical principal identifier>
   :key/id <key identifier in the membership snapshot>
   :signature <PRF signed-envelope.v1>}
  ...]
 :certificate/root "sha256:<derived root>"}
```

The certificate root is a domain-separated PRF canonical hash of the exact body
with `certificate/root` omitted. Endorsements are sorted by principal ID and
then key ID; duplicates by either principal or key reject. Each signature
covers the exact checkpoint root using the `prf.network-checkpoint.v1` payload
type and the member's enrolled key. Certificate metadata never changes what was
signed. There is no authoritative `:valid?`, `:unanimous`, `:quorum`, or `:final`
field: those are derived verifier results, not signer-controlled labels.

The bound network, checkpoint, sequence, epoch, governing policy and governing
membership roots must exactly equal the checkpoint. The verifier resolves the
policy and membership snapshots from the predecessor's trusted history, not
from the certificate itself. It checks principal/key enrollment, key validity
at the checkpoint epoch, revocation/effective interval, signature validity,
principal uniqueness and all policy-specific role/eligibility constraints.
Unknown, revoked, duplicate, cross-network, cross-epoch, replayed or
ineligible endorsements are rejected. Historical signatures are evaluated
against the historical snapshot and applicable key-validity rules; a current
revocation does not silently rewrite historical membership.

The policy specifies the required threshold and any role or independence
constraints. The verifier recomputes eligible distinct signer count and the
policy outcome from the verified endorsements. Threshold satisfaction yields
`certified` only under the named policy. A policy's prose or certificate label
cannot supply a missing threshold.

### 4.2 Quorum safety claims

A threshold certificate is not inherently safe against conflicting certificates.
For membership size `n`, threshold `q`, and an asserted maximum of `f` faulty or
equivocating members, an intersection-only safety claim requires
`2q - n > f`, assuming all non-faulty members durably refuse to endorse two
different successors of the same checkpoint scope. The policy must state its
membership, threshold, fault bound and assumptions. The verifier checks the
inequality before reporting that safety profile. A profile that does not meet
it may still report `certified`, but must report `conflict-safety-unproven` and
must not claim Byzantine finality. For example, 2-of-3 has one-member
intersection; with one equivocating signer it can produce conflicting
certificates and does not satisfy this bound.

Witness anti-equivocation is a separate durable signing rule, not an inference
from signatures. A witness MUST persist a lock before returning a signature,
keyed by network genesis, epoch, sequence, predecessor root, policy root and
membership root. It signs at most one checkpoint root for that scope. A
repeated request for the same root may return the retained signature. A
request for a different root returns a typed equivocation-lock error. Lock
state and signatures MUST survive crash/restart; if durable persistence fails,
the witness MUST fail closed and emit no signature. If conflicting signed roots
are discovered, both are retained and attributed; neither is silently discarded.
The lock reduces accidental or compliant-witness equivocation but does not
make a Byzantine witness truthful or turn a weak threshold into BFT safety.

## 5. Canonical artifact 3: `head-observation.v1`

```clojure
{:observation/schema "head-observation.v1"
 :network/genesis-root "sha256:..."
 :source/id <configured source identity>
 :source/key-id <key id bound to that source>
 :observation/scope "certified-network-head"
 :checkpoint/root "sha256:..."
 :checkpoint/sequence <non-negative integer>
 :observation/client-nonce nil-or-base64url
 :observation/issued-at <ISO-8601 string>
 :observation/signature <PRF signed-envelope.v1>
 :observation/root "sha256:<derived root>"}
```

The source signs the domain-separated hash of the exact observation body,
including nonce and issue time. A nonce-bearing response must echo a fresh
client-generated nonce bound to that request. When a nonce is unavailable, a
verifier may use a configured maximum observation age and a trusted local clock;
source time alone is not a trusted clock. The source identity and key must be
configured independently of the response and bound to the expected network.

A valid observation authenticates only the statement “this configured source
reported this checkpoint as its head for this request/time.” It does not certify
the checkpoint; checkpoint and certificate verification remain independent. It
does not prove that the source is honest, independent from another source,
complete, or globally current. Distinct source IDs count as independent only
when the verifier's external source configuration establishes independent
administrative/key trust; different names or URLs alone are insufficient.

## 6. Verification outcomes

These outcomes are separate claims and must not be collapsed into one green
`verified` flag:

| Outcome | Meaning | Does not mean |
|---|---|---|
| `invalid` | Supplied canonical data is malformed or contradicts a pinned root, predecessor, policy, signature, or deterministic replay result | A claim that required external history or trust data was merely unavailable |
| `locally-valid` | Canonical roots, available event manifest, deterministic replay and predecessor continuity validate against the supplied trusted genesis/history | A valid policy certificate, network-wide completeness, or currentness |
| `certified` | `locally-valid` plus valid endorsements meeting the governing policy and frozen membership snapshot | BFT-finality, absence of conflicting certificates, or global latestness |
| `current-observed` | At least two independently configured sources returned fresh, authenticated observations of the same certified checkpoint after reconstruction from the verifier's pinned anchor | That no newer checkpoint exists or that all network participants agree |
| `stale-or-unknown` | Required history, proof, fresh source responses or trust input is missing, old, unavailable or inconsistent | Acceptance by default or safe advancement |
| `conflicting` | Two valid incompatible certified successors, or incompatible authenticated source reports that cannot be reconciled by verified ancestry | Permission to choose whichever head is newer or arrived last |

A lower-level failure prevents a higher-level status. Conflicting valid
successors remain `conflicting` even if one has a larger sequence or later
claimed time. If one source reports an ancestor and another reports a valid
descendant, the ancestor report is stale. With the v1 minimum of two independent
sources, one lagging response makes the combined result `stale-or-unknown`;
retain both observations. A policy may evaluate a larger source set and report
`current-observed` only if its configured threshold remains met after stale
responses are excluded.

### 6.1 Optional precondition for high-assurance consumers

A consumer that requires a recently observed head MAY make `current-observed`
within a locally configured freshness window a precondition for its own
high-assurance operation. The consumer supplies the verifier with its pinned
genesis, required source identities and independence groups, a fresh request
nonce (when using challenge freshness), maximum observation age, and allowed
future clock skew. The verifier must reconstruct and certify the reported
checkpoint from the pinned anchor before checking this precondition.

If the precondition is enabled, the consumer fails closed when reconstruction
or certification is incomplete, the configured independent-source threshold is
not met, observations are stale or nonce-mismatched, a source is unavailable,
or the sources report incompatible certified heads. The operation must not
advance on `stale-or-unknown`, `conflicting`, or `invalid`. A successful
`current-observed` result is scoped to the configured sources and freshness
window; it is not proof that a newer checkpoint is not being withheld. This
protocol defines no mandatory operation policy or production integration.

## 7. Trust, membership and transition rules

1. **Genesis:** a verifier pins the network/genesis root and its initial
   authority roots from an explicit trusted source. A packet cannot establish
   its own trusted genesis.
2. **Governance:** the checkpoint policy names the authority source/adapter,
   eligible principals, enrolled keys and intervals, threshold, role constraints,
   accepted event types, manifest completeness rule, epoch rules and any
   conflict-resolution procedure.
3. **Historical snapshots:** every checkpoint identifies its governing policy
   and membership roots. They resolve to immutable snapshots. Eligibility is
   checked at the checkpoint epoch using those snapshots and the authorized
   transition history.
4. **Transition authorization:** the predecessor authority authorizes exact
   resulting policy/membership roots and epoch changes. The new snapshot is
   effective only after that checkpoint certifies. Its members may endorse
   subsequent checkpoints, not retroactively authorize their own introduction.
5. **Domain separation and replay:** a signature binds network genesis,
   checkpoint root, sequence and epoch through the checkpoint body; signatures
   cannot be reused for another checkpoint, network, protocol purpose or epoch.
6. **Retention:** retain checkpoint bodies, certificates, accepted-event
   manifests and resolved event/authority inputs needed for fresh replay, plus
   all conflicting signed evidence. Hashes without retrievable content are
   references, not completed verification.
7. **No trust from labels:** source-provided `verified`, `current`, `accepted`,
   `quorum` or `final` fields are ignored unless reproduced from canonical
   inputs by this verifier.

## 8. Independent-verifier input matrix

| Claim | Minimum independently supplied inputs | If any input is absent |
|---|---|---|
| Canonical checkpoint identity | Exact checkpoint body, supported schema/hash version | `stale-or-unknown` / invalid |
| `locally-valid` | Trusted genesis, predecessor chain, checkpoint body, full accepted-event manifest and bodies, DAG nodes/edges, authority transition inputs and deterministic adapters | Do not claim complete local reconstruction |
| `certified` | All `locally-valid` inputs plus certificate, historical policy/membership snapshots, signer-key bindings/revocation intervals, signature verifier | Do not claim certification |
| `current-observed` | Certified chain from pinned anchor, fresh signed head observations, independent source/key configuration, local freshness policy, matching source nonce or trusted-clock policy | `stale-or-unknown` |
| `conflicting` | Both incompatible checkpoint/certificate packages or authenticated contradictory observations, including signatures and lineage | Record unresolved uncertainty; do not choose a winner |

An incomplete accepted-event manifest is not made complete by agreement among
head sources. If witnesses cannot independently reconstruct completeness under
the policy, certification must say so; consumers requiring complete state must
fail closed.

## 9. Adversarial cases and expected result

- Candidate changes any field after signing: canonical root or signature fails.
- Candidate skips a sequence, changes parent, crosses network genesis or supplies
  the wrong governing authority roots: reject transition.
- Candidate omits a previously accepted manifest item, names a missing item,
  duplicates an item, or supplies a false count/state/frontier: reject replay or
  mark reconstruction incomplete; never trust the supplied state root.
- Policy or membership root changes without an old-authority transition: reject.
- New member signs the checkpoint that introduces that member: reject unless
  independently eligible in the predecessor snapshot.
- Same principal signs under two keys to fake quorum: count one principal and
  reject duplicate-principal certificate.
- Revoked, unknown, wrong-purpose, cross-epoch or wrong-network key signs:
  reject according to pinned historical validity rules.
- Witness crashes before durable lock: it has emitted no signature. Storage
  failure after lock but before response may permit retry only for the same
  checkpoint root. Restart on a different root for that scope rejects.
- Two eligible certificates extend the same parent: retain both, report
  `conflicting`; do not select by time or sequence.
- 2-of-3 policy claims one-fault Byzantine finality: reject that safety label
  because `2q - n = 1`, not greater than `f = 1`; it may still be described as
  threshold-certified if the policy permits.
- One head source is unavailable or serves an old ancestor: currentness is
  `stale-or-unknown` unless the configured policy's source threshold is still
  met by fresh independent sources; never treat absence as agreement.
- Two sources agree on an old head while withholding a newer head: at most
  `current-observed` within the stated source set and time window; global
  latestness remains unproved.

## 10. Versioning and rollout

This proposal adds three new versioned artifact schemas and four new domain
separation tags (the checkpoint, event-manifest commitment, certificate, and
head observation). It does not modify existing canonical snapshot identities,
researcher decision schemas, claim-acceptance identities, benchmark certificate
schemas, or authority artifacts. Any future change to a committed field,
projection, eligibility meaning, replay rule or status interpretation requires a
new schema version and cross-language vectors.

PRO-93 may develop a candidate offline implementation while this proposal is
under review. That implementation must not be described as normative or
protocol-conformant until the protocol and policy-owner review decision is
recorded. PRO-94 may add read-only head observation and a two-source
demonstration only after the PRO-93 API is available from a pinnable PRF
revision. Neither task authorizes a production network deployment. Policy
approval and deployment authorization remain separate.

## 11. Ownership and review record

- Owning repository: `protocol-robustness-framework` (PRF), workspace
  `/home/user/Code/.workspaces/agent-c-2`.
- Specification change: Jujutsu change `wlwnknpyvuum`, commit
  `090111953c07` (“Specify network checkpoint protocol v1”).
- Review decision: implementation may proceed as a non-normative candidate;
  protocol/policy-owner review is still required before v1 is treated as
  normative or used to authorize deployment.
- PRO-93 owns offline construction, certificate verification, witness-lock
  enforcement and authenticated observation checks in PRF. PRO-94's HTTP
  transport and two-source learning demonstration live in the separate
  `prf-network-dag-learning` repository and call the PRF verifier rather than
  duplicating it. The learning repository's current public dependency pin
  predates this candidate API; transport-only tests remain runnable with that
  pin, while a local PRF path override exercises signed history reconstruction
  and conflict handling. Pin a reviewed public PRF revision before treating the
  end-to-end demo as a public integration.

The local durable witness lock is an operational safeguard, not a protocol
artifact or a defense against an administrator who can roll back or replace the
store. Production use requires a rollback-resistant signing boundary or an
explicit trust assumption covering the lock store.
