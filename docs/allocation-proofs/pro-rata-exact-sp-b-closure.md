# SP-B Closure — exact pro-rata verification slice

Implementation slice SP-B (allocator-independent exact verification of weighted
pro-rata allocations) is closed. The table below is the authoritative closure
status.

## Closure status

| Item                                            | Status                                   |
| ----------------------------------------------- | ---------------------------------------- |
| Exact verifier implementation                   | CLOSED                                   |
| Request-authoritative reconstruction            | CLOSED                                   |
| Claimed-result comparison-only boundary         | CLOSED                                   |
| Redistribution-chain reconstruction             | CLOSED                                   |
| Payoffs `:weight-proportionality` wiring        | CLOSED                                   |
| No `:not-evaluated` result on supported domain  | CLOSED                                   |
| Allocator dependency boundary                   | CLOSED (aliases **and** FQ refs covered) |
| Coverage `:complete` support-domain proof       | CLOSED                                   |
| Minimal frozen corpus identity                  | CLOSED (locked)                          |
| Extended independent adversarial corpus         | OPEN P1                                  |
| Spec version naming                             | CLOSED (`pro-rata-exact-verification.v1`) |
| Cryptographic normative spec root               | OPEN P1                                  |
| Programme                                       | READY FOR SP-C                           |
| Realized-statement integration                  | DEFERRED (correctly)                     |
| Rust/SP1 exact-verifier conformance             | NOT YET CLAIMED                          |

## Contract: verifier independence

`exact-verifier.clj` verifies a pro-rata **allocation result** from a canonical
request plus the claimed result only. It reconstructs the mathematically
expected allocation from the request via its own decomposition; the claimed
result is only ever **compared**, never used to decide what should have
happened. A passing verdict does not reduce to replaying the producer.

Dependencies are governed by an explicit allowlist:

| Layer                        | Standing  | Namespace / examples                |
| ---------------------------- | --------- | ----------------------------------- |
| canonical arithmetic         | allowed   | `resolver-sim.hash.canonical`       |
| canonical identity           | allowed   | `domain-hash`                       |
| primitive rounding math      | allowed   | integer floor / remainder           |
| primitive ordering           | allowed   | canonical tie-break                 |
| allocator                    | forbidden | `resolver-sim.pro-rata.allocation`  |
| partial-fill producer        | forbidden | `resolver-sim.yield.partial-fill`   |
| payoffs allocation           | forbidden | `resolver-sim.economics.payoffs`    |
| result-derived expected      | forbidden | n/a                                 |

The independence test asserts **both** that the verifier namespace has no
aliases to the forbidden namespaces **and** that its source contains no
fully-qualified `namespace/` reference to them. A bare "no alias" check would
not rule out fully-qualified calls, so the source scan closes that gap.

## Coverage `:complete` is only claimed over the supported policy domain

The verifier exposes its implemented domain as `supported-policies`:

```clojure
{:rounding #{:floor :floor-with-largest-remainder}
 :cap-treatment #{:unallocated :redistribute}
 :ordering #{:input-order :canonical-id}}
```

The evaluator's canonical path (`canonical-pro-rata-request`) admits only a
subset of that domain — rounding `#{:floor :floor-with-largest-remainder}`,
cap-treatment `#{:unallocated :redistribute}`, tie-break `#{:input-order}`. A
test proves every evaluator-admitted policy combination is implemented by the
verifier, which is what makes `:coverage-status :complete` truthful rather than
an over-claim.

For any request outside the verifier's supported domain,
`verify-weighted-proportionality` returns `{:status :unsupported ...}` with the
offending dimensions — it never silently classifies a narrower algorithm as
complete coverage of the wider surface. `reconstruct` likewise rejects
unsupported policies instead of defaulting.

## Verified behavior

- Verifier namespace has no aliases to, and no fully-qualified source reference
  to, the allocator stack (independent).
- Reconstruction is unaffected by a missing/empty claimed result (comparison-only).
- All 7 frozen hand-derived cases reconstruct to their hand-derived targets,
  including the `:chain` redistribution passes.
- The SHA-256 corpus identity is locked and must not drift without a reviewed
  math change: `94200977d363128af4cb7d5cc86c54c8936cbbba71914a79a5ee5e6bb3505324`
  over `(frozen-spec-version "pro-rata-exact-verification.v1", frozen-corpus)`.
- A consistent allocation verifies `:passed`; tamper of `:allocated` or totals
  causes `:failed`; disagreement with the allocator on a large corpus is
  rejected (agreement is accepted, disagreement is rejected — never vice versa).

## Wiring

`evaluate-pro-rata-allocation` now evaluates `:weight-proportionality` (checks
index 4 alongside `:deterministic-replay`) via `verify-weighted-proportionality`
over the allocation result, replacing the former not-evaluated stub.
`payoffs_test.clj` asserts checks[4] is `:weight-proportionality` with status
`:passed`, coverage `:complete`, and zero `not-evaluated-check-count`.

## Error scope

- `:weight-proportionality` may report `:failed` (detected deviation or
  malformed claim), `:unsupported` (policy outside `supported-policies`), or
  `:passed`. It never reports a silently-narrowed pass.
- `:coverage-status :complete` is only produced when every check was evaluated;
  `:unsupported` is treated as evaluated (not `:not-evaluated`), so a future
  allocator policy the verifier does not implement will surface as
  `:unsupported` and cannot masquerade as a passed check over full coverage.

## Open P1 (not blocking SP-C, but required before claiming Rust/SP1 conformance)

1. **Extended independent adversarial/edge corpus.** The frozen corpus is a
   minimal conformance set. Independent hand-derived vectors are still needed
   for: all-zero weights over an unallocated request, equal-remainder
   canonical tie with a unit residual, `cap = 0`, all rows capped with residual,
   multiple (3+) redistribution rounds, one-unit residual, huge-integer
   (BigInt) arithmetic, and adversarial witnesses that corrupt the intermediate
   state — a wrong active set, wrong remaining amount, wrong award winner,
   `cap + 1`, duplicate award, or a skipped round. The large differential corpus
   against the allocator is useful but cannot replace these: an allocator bug
   mirrored by the verifier would pass a differential corpus, whereas
   hand-derived vectors provide an oracle independent of either implementation.
2. **Cryptographic normative spec root.** The current locked identity is
   `SHA-256(pr-str [spec-version-string, corpus])`. That commits the version
   string and the corpus but does not independently commit the *definition* of
   the normative spec semantics. For clean-room/SP1 assurance, prefer the
   existing `resolver-sim.hash.canonical/domain-hash` machinery and a two-root
   model — `fairness-spec-root S` and `conformance-corpus-root C` — so that
   Clojure verifier, Rust verifier, and SP1 program each commit/identify the
   same `S` and `C`. Using the existing hc/domain-hash / corpus-root convention
   is preferred over the bespoke plain SHA-256 tuple.

## Verification note

- `test/resolver_sim/pro_rata/exact_verifier_test.clj` — 9 tests / 74 assertions:
  independence (no alias + no FQ source reference), reconstruction-without-
  claimed-result, frozen corpus reconstruction, locked corpus identity,
  supported-domain-covers-evaluator-path, unsupported-never-narrower,
  accept/tamper, agreement-with-allocator, redistribution-chain reconstruction.
- `test/resolver_sim/economics/payoffs_test.clj` — updated coverage assertions.
- Full battery across the seven touched suites:
  211 tests / 732 assertions / 0 failures / 0 errors.