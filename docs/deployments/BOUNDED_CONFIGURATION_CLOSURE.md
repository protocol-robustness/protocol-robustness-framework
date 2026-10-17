# Bounded configuration closure

The actionable follow-up is now in
[Remaining bounded configuration work](BOUNDED_CONFIGURATION_REMAINING_PLAN.md).
The assessment below records the original gaps and subsequent implementation;
use the follow-up plan for current dependencies and acceptance gates.

This plan records the remaining configuration work for the local controlled
entitlement demonstration. It is a proposal, not approval of canonical inputs
or a claim of local qualification.

## Review baseline

Reviewed PRF parent revision `bf7a459c` and the available prf-evm checkout at
`4b6b6ed159762c88099c96d4dafd635ab99470ea`. The requested
`prf-evm-config-bounded-current` ref was not available in either checkout;
equivalence with that baseline remains unverified.

PRF already supplies candidate protocol, authority and initial configuration
packages and a root producer in `scripts/deployment/genesis_roots.clj`.
The producer recomputes package component roots and checks declared aggregate
roots. All packages remain `:candidate-owner-approval-required`; results remain
`derived-only-not-approved`. These are useful derivations, not semantic closure.

The existing authority schema is
`eae-controlled-entitlement-demo.authority-state.v1`, not generic
`authority-state.v1`. Preserve its identity when adding a generic successor;
renaming it or changing its projection would change already pinned roots.

## Work and acceptance evidence

| Order | Owner | Required result | Acceptance evidence |
| --- | --- | --- | --- |
| 1 | PRF | Specify generic `authority-state.v1` and a separate local authority policy | Closed schema, semantic validation, domain/projection specification, positive and negative vectors |
| 2 | Input owner / approval authority | Review protocol genesis, authority policy/state and all seven configuration components | Scoped approval manifest binding exact artifacts, roots, derivation version and source revision |
| 3 | PRF | Derive C0, C1 and the transition from approved material | Repeatable derivations; C0 has the empty registry; only the registry component changes in C1 |
| 4 | prf-evm | Reject executor instance mismatch and initial authority drift | Constructor/admission mismatch tests; sealing fails after pre-seal authority-root drift |
| 5 | PRF + prf-evm | Pin registry encoding equivalence | Same fixtures executed in Clojure and Solidity, including empty, single, reordered multi-entry and inactive cases |
| 6 | protocol-deployments | Bind observations to derivation and approval before sealing | Independently recomputed instance root equals planned root; observed addresses, chain and runtime identities match; no arbitrary-root override |
| 7 | Deployment admission authority | Authenticate effective verifier implementation | Approved artifact/runtime match for adapter and underlying verifier, with upgrade controls accounted for |
| 8 | protocol-deployments + prf-evm | Execute C0/epoch 0 to C1/epoch 1, then the composed capital flow | Real proof succeeds; wrong instance, proof, verifier, transition and replay attempts fail at the relevant boundary |

An approval manifest should bind exact artifact content as well as derived
roots, identify the approving principal and approval mechanism, and restrict
the approval to the intended protocol, chain, policy scope and local profile.
Validation must reject substitution, missing components and scope mismatch.
A status string alone does not authenticate approval. No approval record has
been created by this plan.

## Authority semantics to settle before freezing v1

Define what the state commits to: principals and capabilities, policy identity,
execution/protocol scope, revision and predecessor relationship, and the
decision/proof-mode state if those are intended to be covered. Specify the
permitted transitions and who validates them. Keep the particular SEW
governance model outside the generic canonical projection.

The current adapter can authorise and revoke decisions and assign proof modes
separately from `updateAuthorityStateRoot`. If those facts belong in the
canonical authority state, validating only root updates leaves the state and
contract behavior disconnected. The implementation must bind all covered
mutations to validated transitions, or explicitly define a narrower policy
commitment and qualify its limitations. Canonical derivation alone cannot
enforce this relationship on chain.

Keep genesis's immutable initial authority root separate from current authority
state. Recheck initial-root continuity at sealing. Later legitimate transitions
must preserve genesis history.

## Narrow Solidity changes

Admission should compare the executor's `chainInstanceGenesisRoot()` with its
own genesis root at construction and admission. Its current constructor stores
expected configuration values without checking the live executor; decide and
test constructor validation explicitly. The admission-time comparison should
remain part of the live configuration checks.

Registry v1 encoding uses eight 32-byte entry fields, including the padded
address and public-values schema hash. The raw domain
`PRF_VERIFIER_REGISTRY_V1` is 24 UTF-8 bytes. Fix conflicting Solidity comments
and pin shared fixtures without changing the encoding. Existing PRF tests
already cover one golden root, schema sensitivity and reordering; they do not
replace execution of the same fixture corpus in both languages.

Matching adapter metadata does not authenticate implementation. Record and
verify runtime identity and the effective underlying verifier, including any
proxy or gateway upgrade path. An on-chain code-hash commitment would need an
explicit versioning decision; do not silently extend registry v1.

Leave generic receipts as durable statement identities unless a separate
requirement calls for attempt uniqueness. Existing generic consumer tests
permit a receipt once per distinct matching operation and reject duplicate
application of one operation. The capital path must additionally demonstrate
that the proof statement and prepared-operation context prevent transferring
authorization to a different capital operation, and that repeated admission
cannot create an additional capital effect.

## Qualification boundary

Keep identity, authority, verification and evidence separate. Evidence anchors
record commitments; snapshots preserve a configuration observed at creation.
Neither approves semantic inputs. No refactor of those two contracts is
required by this plan.

Local qualification requires approved inputs, reproducible root derivation,
independent agreement with on-chain observations, distinct validated C0/C1
identities, rejected unauthorized transitions and substitutions, and a
successful composed capital flow under C1. Public deployment evidence, public
authority approval and production readiness remain separate stages.

The existing candidate genesis guide mentions Base Sepolia despite restricting
the authority/configuration candidates to chain 31337. Resolve that scope
conflict before using it as an executable deployment procedure; local approval
must not implicitly authorize a public-chain deployment.

## Implemented follow-up

The additive generic schema, local policy, strict validators and transition
checks now exist in `src/resolver_sim/authority_state.clj`, with pinned vectors
and a [schema specification](../architecture/canonical-artifacts/AUTHORITY_STATE_V1.md).
The existing demo package roots and candidate status are preserved.

The deployment root producer now derives the three candidate packages as a
scope-checked set and emits the exact seven-root C0 artifact and package
content digests. `scripts/deployment/configuration_review.clj` creates and
checks a review manifest binding those inputs, source revision, derivation
version, intended approval principal and local scope. Content checks do not
authenticate approval. No approval was issued.

The shared registry fixture corpus is pinned at
`etc/conformance/fixtures/verifier-registry-equivalence.v1.json`. Clojure
recomputes the document and roots; the Solidity test reads the same corpus.
Both implementations passed empty, single, multi-entry, reordered and
inactive cases.

Solidity instance checks, pre-seal authority continuity, encoding documentation
and regression tests were implemented and tested in a writable copy. The
[portable patch](../../etc/deployments/patches/README.md) contains the exact
changes; the external prf-evm checkout remains unchanged because it is outside
this session's writable roots.

Still open: authenticated input approval, adoption of the generic authority
state through an enforced mutation path, verifier runtime provenance,
independent deployment observation and seal gating, and a fresh composed
local deployment under approved C0/C1 inputs. Existing fixture proof tests do
not establish that deployment qualification.

Validation of this follow-up: 287 focused PRF/canonical tests passed with
172,199 assertions; 85 focused Solidity tests passed across seven suites in
the writable copy, including real Groth16 proof tests. Targeted Clojure
formatting passed. New source/test lint passed without warnings; linting the
existing canonical file additionally reports its pre-existing unused
`ByteArrayOutputStream` import. The persisted Solidity patch passes
`git apply --check` against the recorded prf-evm revision, and both languages'
registry fixture files are byte-for-byte identical.
