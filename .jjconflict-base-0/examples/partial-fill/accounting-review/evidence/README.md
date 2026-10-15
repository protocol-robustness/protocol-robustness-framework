# Partial Fill V3: verification, acceptance and accounting ratification

This optional accounting consumer uses the existing V3 execution unchanged.
The actual requests are Alice 50, Bob 30 and Carol 20. Seventy units are
allocated as 35/21/14; a separate 10-unit fixture refund goes to Carol after
terminal closure. The units are synthetic local tokens, not USDC.

Candidate A preserves the 70/10 capital classification and 30 unmet requests.
Synthetic Candidate B proposes 40/24/16 from all 80 deposited units. Both
conserve 80, but B fails the restriction and recipient-policy checks. B is not
an observed payment branch. It receives a genuine signed rejection in the
retained local review history, and cannot be accepted or ratified.

Allocation and realized-settlement accounting have separate purpose-bound
policies and histories. Local reviewer and ratifier keys are distinct. Their
appointment is a controlled fixture declaration; signatures do not establish
external professional authority. Private keys are generated in memory and not
retained. An independent reader needs only the published public keys, source
evidence, exact subjects and authenticated history.

## Reproduce the retained result

From PRF, with normal repository Clojure dependencies available:

```sh
clojure -M examples/partial-fill/accounting-review/run.clj verify \
  examples/partial-fill/accounting-review/evidence \
  examples/partial-fill/evidence/v3-live-context \
  examples/partial-fill/accounting-review/trust-pins.edn
```

Keep `trust-pins.edn` from an independently trusted checkout. It pins both
purpose-specific policy roots and final history checkpoints. A bundle's own
claimed keys or checkpoints are not an independent trust source. Verification
recomputes canonical roots, checks Ed25519 signatures, resolves the original
manifest-pinned V3 evidence, reconstructs both histories and compares all
derived reports and exported canonical preimages. Altered files, signatures,
policy substitutions, missing acceptance and truncated logs fail closed.

To exercise new local fixture authorities, generate a **new** directory:

```sh
clojure -M examples/partial-fill/accounting-review/run.clj generate /tmp/new-accounting-review
```

New ephemeral keys produce new policy/decision roots. This does not replace
the trusted retained history. Review its generated `trust-pins.edn` as a new
fixture declaration before choosing to trust it.

`subject-decisions.clj`, `partial-fill-review.clj`, `run.clj` and
`architecture.md` are retained source snapshots. Overlay them into the matching
PRF paths in a trusted base checkout for an independently executable replay.
`canonical-preimages.json` exposes the exact domain-prefixed canonical bytes.
The website verifier independently SHA-256 checks those bytes and verifies
Ed25519 signatures; the Clojure replay also establishes their binding to EDN
bodies and recomputes accounting eligibility.

## Authority and qualification boundary

The generic log adopts content for a named purpose. It has no dispatch or asset
API. Its signed records are incompatible with existing operation authorization
and proof-admission schemas. Existing V2 `ratifyAndFund` semantics remain
unchanged. The browser review controls are simulated and produce no signatures,
authority records or transactions; the downloadable local decisions do contain
real Ed25519 signatures.

The retained `AccountingReviewBoundary.t.sol` integration test overlays into
`prf-evm/test/`. Copy `accounting-ratification.edn` and `evm-input.json` to
`prf-evm/test/fixtures/accounting-review/`, then run in the owning EVM checkout:

```sh
PARTIAL_FILL_STAGE=2 forge test --match-path test/AccountingReviewBoundary.t.sol \
  --match-test test_AccountingRatificationCannotAuthorizeCapital --offline -vv
```

It presents the exact authenticated accounting record to real SP1 admission,
tries its accounting root as an assurance receipt, and attempts direct funding
without the required execution caller. All three must revert and preserve the
checked reserve/allocator/beneficiary balances, claimant liabilities and
critical operation state. Its retained log is `capital-boundary-test.log`.
This is a separate local negative test, not an execution bridge or a public
transaction. Existing V3 positive qualification and stale-recovery tests remain
under the original retained V3 manifest.

The local evidence does not establish external beneficial ownership, complete
outside obligations, public-chain state, a production audit or globally latest
authority state. Acceptance and ratification retain those limitations. No Base
Sepolia transaction is broadcast. See
`docs/architecture/subject-decisions-and-accounting.md` for ownership boundaries,
immutable-policy restrictions, successor rules and checkpoint assumptions.
