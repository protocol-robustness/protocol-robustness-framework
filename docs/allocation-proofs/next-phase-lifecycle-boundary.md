# Allocation lifecycle contract (cancellation-window.v1)

This document records the compatibility contract for the allocation round
lifecycle as it is carried across execution layers: the PRF vocabulary, the
independent Rust kernel, and the Solidity coordinator. It mirrors the
`exact-replication-contract.md` styling but for the lifecycle slice.

The single source of truth for lifecycle *semantics* is the PRF vocabulary:

- `resolver-sim.assurance.canonical-force-authorisation` — the generic
  `cancellation-window.v1` primitive (`classify-lifecycle-window`), the
  `probabilistic-allocation-window` lifecycle profile, and the
  `cancellation-window-assertion` contract 8 evidence-replay path.
- `resolver-sim.allocation.round-state` — the allocation-specific projection
  (coprocessor round state → lifecycle target state, fail-closed).

Each execution layer provides a byte-for-byte mirror and must classify the same
round tokens identically.

## Lifecycle state mapping

| Round state | lifecycle target | window | reason |
|-------------|------------------|--------|--------|
| AllocationCommitted | `:allocation-committed` | open | — |
| RandomnessRequested | `:randomness-requested` | **closed (cutpoint)** | `:authoritative-randomness-requested` |
| RandomnessFulfilled | `:randomness-fulfilled` | closed | `:randomness-fulfilled` |
| ResultProposed | `:result-proposed` | closed | `:result-proposed` |
| ResultAccepted | `:result-accepted` | closed | `:result-accepted` |
| ClaimConsumptionStarted | `:claim-consumption-started` | closed | `:claim-consumption-started` |

The cutpoint is the authoritative randomness request — the first closed state,
earlier than consumption. Cancellation is possible only while the window is
`open`. Unknown or missing states fail closed to `:invalid` and are never
possible.

### Layer implementations

- **PRF** (`resolver-sim.assurance.canonical-force-authorisation`):
  `probabilistic-allocation-window` + `classify-lifecycle-window`.
- **PRF allocation round-state projection**
  (`resolver-sim.allocation.round-state`): token → lifecycle target,
  fail-closed to nil on unknown.
- **Rust** (`coprocessor/core/src/lifecycle.rs`): `classify_lifecycle_window`
  is a semantic-and-byte-for-byte mirror of the PRF classifier. Verified
  against the same cutpoint, post-cutpoint, open, unknown, and missing cases.

## Invariant (identical everywhere)

```
allocation-committed      → open   (cancellation possible)
randomness-requested      → closed (cancellation refused,
                                    reason :authoritative-randomness-requested)
randomness-fulfilled      → closed
result-proposed           → closed
result-accepted           → closed
claim-consumption-started → closed
<unknown>                 → invalid (fail closed)
```

This invariant must hold identically in the PRF mapper, the Rust kernel, and
future Solidity coordinator enforcement. A conforming cancellation decision can
never override a closed lifecycle window.

## Public `round-lifecycle` projection (kernel public values)

The allocation kernel now carries the lifecycle observation in its public
values under `round-lifecycle`, always present:

| field | value |
|-------|-------|
| `round-state` | observed token string, or null |
| `derived-state` | lifecycle target-state string, or null |
| `lifecycle-profile-id` | `prf.lifecycle-window/probabilistic-allocation` |
| `lifecycle-profile-version` | `1` |
| `cancellation-window-schema` | `cancellation-window.v1` |
| `cancellation-window` | `open` \| `closed` \| `invalid` |
| `cancellation-possible` | bool |
| `cancellation-blocking-reasons` | ordered reason strings |
| `lifecycle-assertion-status` | `passing` (open/closed) \| `failing` (invalid) |
| `evidence-status` | `evidence/derived-state` |
| `assurance` | `independent-replay` |

The projection is always derived from committed evidence (the observed
round-state) via the contract-8 replay path, so `evidence-status` and
`assurance` are constant on every path. Fail-closed reasons are distinguished:

- absent/null `round-state` → `["missing-target-state"]`
- unrecognised token → `["unknown-target-state"]`
- non-token value (e.g. an object) → `["malformed-round-state"]`

The `round-lifecycle` projection never changes the allocation `result/status`;
the allocation pass/reject verdict is governed by the 14 allocation assertions
only. The lifecycle is committed into the certificate digest (below).

## Certificate assertions digest v2

The lifecycle projection is committed into a bumped certificate-assertions
digest. Versioning decision (compatibility boundary):

- **Bump**: the certificate-assertions *projection version* (new domain tag
  `CERTIFICATE_ASSERTIONS_V2`), rather than the allocation-kernel version,
  which stays `allocation-kernel.v1`. The digest is never silently changed
  under the v1 tag.
- **Committed fields**: v1 fields plus `round-state`, `derived-state`,
  `lifecycle-profile-id`, `lifecycle-profile-version`,
  `cancellation-window-schema`, `cancellation-window`,
  `cancellation-possible`, `cancellation-blocking-reasons`,
  `lifecycle-assertion-status`, and `lifecycle-assurance`.
- The `allocation-context-hash` is unchanged: round-state is a per-observation
  attribute and never enters the allocation context hash, so same-seed
  continuation keeps a stable context while the digest tracks lifecycle
  progress.
- PRF: `CERTIFICATE_ASSERTIONS_V2` tag + `roots/certificate-assertions-digest-v2`.
- Rust: `tags::CERTIFICATE_ASSERTIONS_V2` +
  `roots::certificate_assertions_digest_v2`.

## Verification

- PRF: `round-state-test` (6 tests / 39 assertions), allocation suite,
  `namespace-load-test`, `clj-kondo` (0/0) for the
  `:target-evidence` evidence-replay path.
- Rust: `lifecycle` unit tests in `coprocessor/core/src/lifecycle.rs`
  (9 checks), `cargo fmt --check` clean, core `clippy -D warnings` clean.
- Lifecycle conformance vectors (23 total, 9 lifecycle): every state from the
  mapping table plus unknown, missing, and malformed tokens, verified by the
  `conformance.sh` gate as PRF result == native Rust result byte-for-byte.

## Next (out of scope for the mapper)

- Solidity coordinator enforcement (reject at and after `RandomnessRequested`),
  a same-seed continuation path that does not reopen cancellation.
- SP1 proof of the extended public projection and local on-chain verification
  of the v2 digest.