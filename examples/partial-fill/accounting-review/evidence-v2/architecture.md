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

Each `subject-decision.v2` record commits the policy version/root, domain,
purpose, subject, recomputed verification, signer identity, sequence, predecessor
head and (for ratification) acceptance and superseded statement roots. Separate
domain-specific wrappers are unnecessary. The accounting meaning resides in
`partial-fill-accounting-statement.v1` and its policy adapter.

Both roles have different Ed25519 public keys in the local policy. These are
ephemeral demonstration authorities, not externally appointed reviewers.
Private keys never enter the retained bundle. The signature is real; the
appointment and its scope are controlled fixture declarations.

## Identity and verification binding (v2 substrate)

V1 committed a verification root over the raw adapter report only, and never
required a decision's declared `:subject/root` to equal the resolved subject's
recomputed canonical identity. A reviewer demonstrated the consequence: a
validly signed decision could name decoy statement Z while the `subjects` map
resolved that root to a different, self-consistent statement Y, so
reconstruction verified Y but recorded Z. Because a checkpoint chain binds the
history and its verification roots but not subject content, a substituted subject
whose verifier report matched could be adopted.

`subject-decision.v2` / `subject-verification.v2` close both paths:

1. `advance` requires `(= (:subject/root event) (:artifact/root subject))` where
   the right-hand side is recomputed from the resolved subject. A forged or
   substituted declared root fails closed even with a valid signature, and
   regardless of how the resolver keys its `subjects` map.
2. The verification preimage is a bound record committing the subject identity
   and schema, the policy root and schema, the domain/purpose/scope, the pinned
   validation root and the adapter report. A report cannot be transplanted
   between subjects or between policies, and two distinct subjects with identical
   adapter reports still receive distinct verification roots.

Adversarial tests reproduce the decoy-Z/statement-Y exploit against the fixed
kernel and assert rejection (`:subject-identity-mismatch`), plus forged declared
roots, subject-object root forgery, report mutation and index-substitution.

### Versioning and migration

V1 uses different decision, history and verification domain tags, so v1 records
are never silently reinterpreted as v2. The retained v1 example bundle under
`examples/partial-fill/accounting-review/evidence/` is left frozen for
provenance; the qualified generation is `evidence-v2/`. Changing the substrate
re-derived every decision/history/verification identity and required a fresh
generation. The policy schema (`subject-decision-policy.v1`) is unchanged: it
binds domain, purpose, scope, subject schema, validation root and role keys, none
of which changed. No in-place edit of any frozen history was performed.

## Trust anchoring: local reproducibility versus qualification

Reproducing a bundle and trusting it are different operations.
`run.clj verify-local` recomputes the bundle from pinned source evidence and
checks internal consistency only; it reports **LOCAL** and never confers
independent trust. `run.clj verify-anchored` additionally requires an externally
supplied `trust-pins.v2` document and binds:

- the bundle manifest digest,
- the source-evidence manifest digest,
- the validation-method root,
- the role authorities (reviewer and ratifier public keys per purpose),
- the policy roots, and
- the final history checkpoints.

Missing, mismatched or substituted identities fail closed, and `:pins/root`
commits the whole pins document. A pins file located inside the bundle directory
is refused, so generated pins within a bundle can never automatically confer
independent trust. The program cannot prove the provenance of the pins a caller
supplies; a researcher must obtain `trust-pins-v2.edn` (or its `:pins/root` and
digest) out of band and confirm the digest before relying on the result.

## History, corrections and limits

History is an immutable vector replayed from the pinned policy. Every action
binds the previous history root and exact next sequence. Replays, duplicates,
wrong signatures, unknown operations and changed evidence fail closed. Review
decisions are final for a given subject under V1/V2. A correction must be a new
subject with a predecessor pointing at the currently adopted statement, followed
by new acceptance and ratification. Competing stale successors cannot both
extend the same adopted history. Historical events remain available.

V1/V2 have no revocation, expiry or live key rotation operation. The immutable
policy explicitly permits successor statements only. Consumers needing
revocation must introduce and qualify a versioned policy before relying on it;
they must not interpret unsupported revocation records as valid. `reconstruct-at`
compares replay with an independently retained head/count to detect rollback.
A complete local history cannot establish global latestness or detect an unseen
competing log. Persistence, consensus and a public review service are outside
this increment.

## Partial Fill evidence boundary and derivation

Use the actual separate V3 run: requests 50/30/20, allocation 35/21/14, deposit
80, available 70, restricted 10 and unmet requests 30. Carol is both a claimant
for 14 and the fixture-designated recipient of a separate 10-unit refund. Her
external beneficial ownership and external obligation completeness remain
unverified. The units are synthetic local test tokens, not observed USDC.

The adapter **derives** every economic value from the pinned observations and the
observed policy: total capital from the reserve-state gross balance, restricted
capital from the policy restricted amount, allocatable capital from the available
amount, per-recipient allocations from the independent exact verifier, funded
capital from the reserve→allocator transfer, withdrawals from allocator→beneficiary
transfers, the restricted refund, and remaining/terminal balances from the
terminal observation and the last accounting state. Window assertions compare
against those derived thresholds. A modified but internally consistent fixture
(gross 90 / restricted 20 / available 70, allocated 28/28/14, refund 20) is
reconstructed by the same adapter in the test suite, demonstrating that no value
is hardcoded.

Pre-settlement statements describe allocations and classifications, with no
claim of payment. Post-settlement statements separately describe observed local
withdrawals and terminal refund. Distinct purpose-bound policies/histories keep
those accounting cutoffs separate. The synthetic counterfactual proposes a 4/5
allocation from all deposited units and classifies no restricted capital: it
conserves value but fails the actual policy and per-recipient checks. It is never
represented as an observed transfer branch.

The loader pins the retained manifest, verifies every listed byte/hash, checks
the eight log observations and five transfers against their projections, and
recomputes bounded custody/liability/recipient checks. It does not rerun SP1 or
authenticate public-chain state.

### Cross-history relationship (allocation vs realized settlement)

The two purposes have separate policies and histories and no signed predecessor
link. They are bound to the same operation by their shared `:operation/id` and
`:evidence/root` and by the fact that each statement independently re-resolves
the same pinned evidence. This was evaluated as an ambiguity: because the
adapter derives the realized settlement from the same frozen observations and
checks it against the same allocated rows, a realized statement cannot describe a
different operation or allocation without failing its own resolution. The
residual ambiguity is only that a consumer reading the two signed records alone
must compare their `:operation/id`/`:evidence/root` (or re-resolve) to connect
them. No signed cross-history reference was added: it would force a change to
immutable records for no demonstrated additional security, so it is documented
instead. Consumers SHOULD require both adopted statements to share
`:operation/id` and `:evidence/root`.

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
