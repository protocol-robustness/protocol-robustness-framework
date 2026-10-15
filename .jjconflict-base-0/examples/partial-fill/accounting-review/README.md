# Partial Fill V3: verification, acceptance and accounting ratification

This optional accounting consumer uses the existing V3 execution unchanged.
The actual requests are Alice 50, Bob 30 and Carol 20. Seventy units are
allocated as 35/21/14; a separate 10-unit fixture refund goes to Carol after
terminal closure. The units are synthetic local tokens, not USDC.

Candidate A preserves the derived 70/10 capital classification and 30 unmet
requests. Synthetic Candidate B proposes a 4/5 allocation from all deposited
units and classifies no restricted capital. Both conserve value, but B fails the
restriction and recipient-policy checks. B is not an observed payment branch. It
receives a genuine signed rejection in the retained local review history, and
cannot be accepted or ratified.

Every economic value (total, restricted and allocatable capital, per-recipient
allocations, funded and remaining liabilities, the restricted refund, settlement
outflows and terminal balances) is **derived** from the pinned observations and
the observed policy. Nothing in the retained fixture is hardcoded. A modified but
internally consistent source fixture (gross 90 / restricted 20 / available 70,
allocated 28/28/14, refund 20) is reconstructed by the same code in
`partial-fill-review-test.clj`.

Allocation and realized-settlement accounting have separate purpose-bound
policies and histories. Local reviewer and ratifier keys are distinct. Their
appointment is a controlled fixture declaration; signatures do not establish
external professional authority. Private keys are generated in memory and not
retained.

## Identity binding (v2 substrate)

`subject-decision.v2` closes a subject-identity substitution gap present in v1.
Two independent changes:

1. A decision's declared `:subject/root` must equal the **resolved** subject's
   recomputed canonical `:artifact/root`. A valid signature over a mismatched
   declared root is not admissible. This is checked independently of how the
   resolver indexes its `subjects` map.
2. The verification preimage (`subject-verification.v2`) is a bound record that
   commits the subject identity and schema, the policy root and schema, the
   domain/purpose/scope, the pinned validation root and the adapter report. A
   report can no longer be transplanted between subjects or policies.

The retained v1 files (`subject-decision.v1`, `subject-verification.v1`,
`subject-decision-history.v1`) are **not** silently reinterpreted: the immutable
v1 example bundle under `evidence/` is left frozen for provenance, and the
qualified generation is written under `evidence-v2/`. Because the decision
substrate changed, decision/history/verification identities were **re-derived**
and a fresh generation produced; policy schema (`subject-decision-policy.v1`) is
unchanged. The architecture document records the migration.

## Reproduce the retained result

`verify-local` recomputes the bundle from the pinned source evidence and checks
its internal consistency. It reports **LOCAL reproducibility only** and never
confers independent trust:

```sh
clojure -M examples/partial-fill/accounting-review/run.clj verify-local \
  examples/partial-fill/accounting-review/evidence-v2 \
  examples/partial-fill/evidence/v3-live-context
```

`verify-anchored` additionally requires an **independently supplied** trusted
pins file. It binds the bundle manifest, source manifest, validation method, role
authorities, policy roots and history checkpoints; missing, mismatched or
substituted identities fail closed. A pins file that lives inside the bundle
directory is refused, because generated pins must never automatically confer
independent trust:

```sh
clojure -M examples/partial-fill/accounting-review/run.clj verify-anchored \
  examples/partial-fill/accounting-review/evidence-v2 \
  examples/partial-fill/evidence/v3-live-context \
  examples/partial-fill/accounting-review/trust-pins-v2.edn
```

`generate` builds a new local fixture with ephemeral keys, writes its own
`trust-pins.edn` and `trust-pins.sha256`, and runs only the LOCAL self-check.

## Obtaining and authenticating the trust pins

The pins file `trust-pins-v2.edn` is a small EDN document whose `:pins/root`
commits every anchored identity. To rely on a bundle:

1. Obtain `trust-pins-v2.edn` (or at least its `:pins/root` and file digest) from
   a channel you trust **independently** of the bundle you are checking — for
   example an out-of-band copy, a signed release note, or a second checkout. The
   copy committed next to the bundle is convenient, not authoritative.
2. Confirm `sha256(trust-pins-v2.edn)` matches the value you obtained
   (`trust-pins-v2.edn.sha256` is provided only as a convenience; recompute it).
3. Run `verify-anchored`. The tool recomputes `:pins/root` from the pins file,
   refuses a pins file inside the bundle, and fails closed on any identity
   mismatch.

The program cannot establish the provenance of the pins you hand it; that step is
yours. It only proves the bundle is consistent with the pins and that the pins
are internally consistent.

`subject-decisions.clj`, `partial-fill-review.clj`, `run.clj` and
`architecture.md` are retained source snapshots. `canonical-preimages.json`
exposes the exact domain-prefixed canonical bytes; `sha256(hex-decode(bytes))`
equals the corresponding artifact root, and each signed decision's Ed25519
signature is over `payload-type \n artifact-root`.

## Authority and qualification boundary

The generic log adopts content for a named purpose. It has no dispatch or asset
API. Its signed records are incompatible with existing operation authorization
and proof-admission schemas. The browser review controls are simulated and
produce no signatures, authority records or transactions; the downloadable local
decisions do contain real Ed25519 signatures.

The retained `AccountingReviewBoundary.t.sol` integration test overlays into the
separate `prf-evm` checkout (see `docs/architecture/subject-decisions-and-accounting.md`
for the pinned revision and reproduction steps). It presents BOTH the allocation
and realized-settlement authenticated accounting records (`accounting-ratification.edn`,
`accounting-ratification-realized.edn`) directly to the real SP1 verifier through
the adapter, then to the full admission gate, tries each accounting root as an
assurance receipt, and attempts direct funding without the required execution
caller. All attempts must revert at those proof/admission/authorization boundaries
and preserve the checked reserve/allocator/beneficiary balances, claimant
liabilities and critical operation state. The captured run is retained as
`capital-boundary-test.log`.

To reproduce in an owning `prf-evm` checkout at the pinned revision, copy
`evm-input.json`, `accounting-ratification.edn` and
`accounting-ratification-realized.edn` into `test/fixtures/accounting-review/`
and the retained `AccountingReviewBoundary.t.sol` into `test/`, then run:

```sh
PARTIAL_FILL_STAGE=2 forge test --match-path test/AccountingReviewBoundary.t.sol \
  --match-test test_AccountingRatificationCannotAuthorizeCapital -vv
```

The local evidence does not establish external beneficial ownership, complete
outside obligations, public-chain state, a production audit or globally latest
authority state. Acceptance and ratification retain those limitations. No Base
Sepolia transaction is broadcast.
