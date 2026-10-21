# Pro-Rata Stage B bounded input and authority adapter

This adapter implements the PRF-side input and authority boundary for PRO-95
Stage B. It uses the existing `signed-external-decision.v1` Ed25519 envelope
and canonical hashing. It adds no evidence graph or qualification ontology.

Implementation:

- `src/resolver_sim/allocation/bounded_source_snapshot.clj`
- `test/resolver_sim/allocation/bounded_source_snapshot_test.clj`
- `scripts/forensic/pro28_receipts.py` remains the existing locally mined
  settlement reconstruction path; no second receipt verifier is introduced.

## Bounded source snapshot

`prf-bounded-allocation-source-snapshot.v1` commits to:

- source and bounded population identity;
- the source query root, observation start, requested cutoff, and source
  watermark;
- an ordered event sequence with a declared count and inclusive first/last
  sequence;
- unique event IDs, stable entity IDs, event type, event time, and payload;
- request open, amendment, and cancellation lifecycle consistency; and
- an Ed25519 signature under the verifier-selected
  `allocation-source-authority` key.

The snapshot verifier rejects event substitution, duplicate IDs or sequence
numbers, sequence gaps, count/range mismatch, events outside the bounded time
window, invalid request lifecycle transitions, malformed roots, untrusted or
wrong-role signers, and invalid signatures. A signed watermark at or beyond the
cutoff is the authority's assertion that the bounded population is complete
through that cutoff.

This assertion is not independently proven by the adapter. If an authority
signs an incomplete but internally consistent population, the adapter cannot
discover omitted upstream records. The trust policy and source system's query,
retention, amendment, and completeness guarantees must be independently
selected and reviewed. No production issuer or public source has supplied a
Stage B snapshot in this change.

## Separate authority bindings

Policy, beneficiary, and funding envelopes are signed under separate roles and
must all bind to the same source root. Beneficiary and funding envelopes must
also bind to the policy root.

- Policy authority signs a version, root, and policy rules.
- Beneficiary authority signs a unique authorization ID, pseudonymous owner
  reference, claim ID, and destination for each positive allocation claim.
  The mapping must exactly cover positive allocation claims.
- Funding authority signs the operation, reserve root, asset identity, funder,
  chain instance, deployment code hash, and amount.

Pseudonyms are supported directly. A wallet address does not establish legal
identity or ownership; the beneficiary authority's external authorization
process remains a trust assumption. A funding authorization does not establish
that funding executed. Actual execution is checked against the retained
transaction and receipt evidence.

`qualify-stage-b-inputs` returns `:bound` only when the four trust-selected
signatures, envelope shapes, source/policy root joins, and beneficiary coverage
all validate. This status describes the narrow input-binding result. It does
not mean the source facts are true, an operation was funded, or settlement was
complete.

## Local mined settlement reuse

The existing `examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-pro56-20261018/`
bundle retains the proof fixture, witness, deployment manifest, individual
transaction/receipt observations, runtime code observations, and a same-block
terminal state observation. `scripts/forensic/pro28_receipts.py` reconstructs
funding, activation, three payouts, receipt linkage, and terminal liabilities
from those retained artifacts. The receipts and block observations are local
Anvil/RPC evidence. They do not establish public-chain inclusion or finality.

This Stage B adapter does not rewrite that bundle, generate new chain
transactions, claim the local fixture as real customer/source data, or assert
that the retained run was independently reviewed. Stage C remains a separate
authorization and qualification gate.

## Focused checks

Run the adapter tests with:

```sh
clojure -M:test -e "(require 'resolver-sim.allocation.bounded-source-snapshot-test) (clojure.test/run-tests 'resolver-sim.allocation.bounded-source-snapshot-test)"
```

The retained local receipt package can be independently reconstructed using
the commands and externally pinned expected report digest in
`examples/partial-fill/evidence/pro-28-v1/README.md`. A digest copied only from
the evidence bundle is an integrity comparison, not an independent trust
anchor.

The focused tests cover authority-role/signature enforcement, changed event
payloads, omitted records, sequence gaps, out-of-window events, root
substitution, and beneficiary-to-allocation coverage. They exercise the
adapter contract with test keys; they do not qualify an external source or
production authority.
