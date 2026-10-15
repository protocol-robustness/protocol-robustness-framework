# Subject acceptance and ratification, with accounting as a consumer

## Ownership and reuse

`resolver-sim.assurance.subject-decisions` is an optional content-decision log.
It owns exact subject/policy/domain/purpose binding, distinct reviewer and
ratifier roles, signed decisions, predecessor sequencing and reconstruction.
It cannot transfer assets, create obligations or dispatch protected operations.
Consumers pin the policy and its public keys independently of the history.
The policy also binds a versioned validation-method root. The installed adapter
must declare that same root; statements cannot select another verifier. The
method record specifies checks and the independent verifier's spec/corpus.
This is an explicit trusted-code boundary, not a proof of arbitrary code: the
retained source snapshots permit inspection and replay of the implementation.

The accounting adapter owns the statement schema, retained-evidence validation,
allocation/reconciliation checks, eligibility, accounting purposes and scope.
PRF does not designate a real-world accountant or governance authority.

Inspected existing implementations:

| Existing mechanism | Reused boundary / reason for keeping semantics intact |
| --- | --- |
| `hash.canonical` / `hash.reference` | Existing canonical V1 encoding and SHA-256 references; new string domains only |
| `evidence.finalization-signature` generic profile API | Existing detached Ed25519 encoding and cryptographic verification, under an additive subject-decision profile |
| `benchmark.signing` | Existing Ed25519 PEM parsing |
| `network.researcher-challenges` | Signed-history pattern, but challenge/reward/enrollment decisions remain research-specific and unchanged |
| `benchmark.research-claim-acceptance` | Canonical claim assertion, explicitly lacks independent authentication of its opaque decision evidence; cannot be reused as signed accounting acceptance |
| `governed-authority-transition` | V1 explicitly fixes configuration adoption; not a generic subject-decision kernel |
| `signed-external-decision` | Existing generic authorization primitive, but no acceptance/adoption lifecycle or predecessor reconstruction; left unchanged |
| Accounting reductions | Additive arithmetic does not establish restricted-capital rights, payment coverage or acceptance |
| Independent exact pro-rata verifier | Reconstruct per-recipient amounts from source requests, caps and available capital, without calling the allocator |

No primitive extraction or research/canonical identity migration is required.

## Three states and separate authorities

1. **Verified**: domain checks are recomputed from resolved evidence. No human
   acceptance or authoritative statement follows automatically.
2. **Accepted/rejected**: a policy-named reviewer signs the exact statement and
   verification root. Acceptance requires passing verification and eligibility;
   rejection can record a failed candidate. Rejection does not constitute proof
   of a different economic outcome.
3. **Ratified**: a different policy-named role signs adoption of the exact
   accepted statement, acceptance root and current predecessor for one purpose.

Each `subject-decision.v1` record commits the policy version/root, domain,
purpose, subject, recomputed verification, signer identity, sequence, predecessor
head and (for ratification) acceptance and superseded statement roots. Separate
domain-specific wrappers are unnecessary. The accounting meaning resides in
`partial-fill-accounting-statement.v1` and its policy adapter.

Both roles have different Ed25519 public keys in the local policy. These are
ephemeral demonstration authorities, not externally appointed reviewers.
Private keys never enter the retained bundle. The signature is real; the
appointment and its scope are controlled fixture declarations.

## History, corrections and limits

History is an immutable vector replayed from the pinned policy. Every action
binds the previous history root and exact next sequence. Replays, duplicates,
wrong signatures, unknown operations and changed evidence fail closed. Review
decisions are final for a given subject under V1. A correction must be a new
subject with a predecessor pointing at the currently adopted statement, followed
by new acceptance and ratification. Competing stale successors cannot both
extend the same adopted history. Historical events remain available.

V1 has no revocation, expiry or live key rotation operation. Its immutable
policy explicitly permits successor statements only. Consumers needing
revocation must introduce and qualify a versioned policy before relying on it;
they must not interpret unsupported revocation records as valid. `reconstruct-at`
compares replay with an independently retained head/count to detect rollback.
A complete local history cannot establish global latestness or detect an
unseen competing log. Persistence, consensus and a public review service are
outside this increment.

## Partial Fill evidence boundary

Use the actual separate V3 run: requests 50/30/20, allocation 35/21/14, deposit
80, available 70, restricted 10 and unmet requests 30. Carol is both a claimant
for 14 and the fixture-designated recipient of a separate 10-unit refund.
Her external beneficial ownership and external obligation completeness remain
unverified. The units are synthetic local test tokens, not observed USDC.

Pre-settlement statements describe allocations and classifications, with no
claim of payment. Post-settlement statements separately describe observed local
withdrawals and terminal refund. Distinct purpose-bound policies/histories keep
those accounting cutoffs separate. The synthetic counterfactual allocates
40/24/16 from all 80 deposited units and classifies no restricted capital: it
conserves value but fails the actual 70-unit policy and per-recipient checks.
It is never represented as an observed transfer branch.

The loader pins the retained manifest, verifies every listed byte/hash, checks
the eight log observations and five transfers against their projections, and
recomputes bounded custody/liability/recipient checks. It does not rerun SP1 or
authenticate public-chain state. The retained proof qualification remains the
evidence for those local cryptographic/contract checks.

## Relation to protected execution

Accounting adoption is content authority for a specified reporting purpose.
The qualified V2 `ratifyAndFund` remains operational ratification against live
reserve/source commitments. Its authority, proof admission and route do not
consume this accounting log. A signed accounting event fails existing execution
authorization schemas; there is no automatic bridge. Actual later execution
produces fresh observations for a new accounting statement.

Website controls simulate review state and produce no signatures, records or
transactions. Downloadable local artifacts separately demonstrate genuine
signed acceptance and ratification under the fixture policy. Neither path is a
production accounting audit. No Base Sepolia transaction is broadcast.
