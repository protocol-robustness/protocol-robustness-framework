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
| Extended independent adversarial corpus         | PARTIAL (Clojure/Rust covered; SP1 profile open) |
| Spec version naming                             | CLOSED (`pro-rata-exact-verification.v1`) |
| Cryptographic normative spec and corpus roots   | CLOSED (Clojure/Rust/SP1 execution parity) |
| Programme                                       | READY FOR SP-C                           |
| Realized-statement integration                  | DEFERRED (correctly)                     |
| Rust/SP1 exact-verifier conformance             | PARTIAL (positive execution passed; proof open) |

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

- Verifier namespace depends only on generic verification and has no
  fully-qualified source reference to the allocator stack (independent).
- Reconstruction is unaffected by a missing/empty claimed result (comparison-only).
- All 7 frozen hand-derived cases reconstruct to their hand-derived targets,
  including the `:chain` redistribution passes.
- The SHA-256 corpus identity is locked and must not drift without a reviewed
  math change: `94200977d363128af4cb7d5cc86c54c8936cbbba71914a79a5ee5e6bb3505324`
  over `(frozen-spec-version "pro-rata-exact-verification.v1", frozen-corpus)`.
- Supplemental hand-derived Clojure and Rust cases cover all-zero weights, a canonical
  equal-remainder unit award, zero cap, all rows capped with residual,
  three redistribution rounds, and arbitrary-precision amounts. These cases
  do not change the locked minimal corpus identity.
- Clojure and Rust independently construct and hash the fairness-spec and
  supplemental-corpus preimages. Their roots match:
  - fairness spec: `e0650cf27f9ba13f2c1799ad0ee09ba79214edd1b80a625b5e63f75a7bc5c7f4`
  - supplemental corpus: `a6d723b27f154d6981cc41a7837ef7a9278ed3265eb98e4a480871845c5b97c1`
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

1. **SP1 exact-verifier profile.** An isolated guest and execution harness now
   exist, separate from the allocation-kernel guest. The guest compiles for the
   SP1 target, and the local positive fixture completed execution. Its 96-byte
   output matched the expected fairness-spec, supplemental-corpus, and
   verification-input roots. Before claiming Rust/SP1 conformance, run
   malformed, unsupported, and tampered-claim fixtures through the guest, bind
   a stable program/VKey identity, and qualify real proof generation and
   verification. Do not mutate the existing allocation-kernel guest's public
   values or VKey identity.
2. **Witness attacks are out of the current verifier contract.** The current
   verifier accepts a request and claimed allocation result; it does not accept
   intermediate redistribution witnesses. Wrong active sets, remaining
   amounts, duplicate witness awards, and skipped witness rounds therefore are
   not verifier inputs. They become conformance cases only if a future proof
   profile introduces a witness-bearing interface.

## Verification note

- `test/resolver_sim/pro_rata/exact_verifier_test.clj` — 13 tests / 127 assertions:
  independence (generic verifier alias only + no FQ allocator reference), reconstruction-without-
  claimed-result, frozen corpus reconstruction, locked corpus identity,
  supported-domain-covers-evaluator-path, unsupported-never-narrower,
  accept/tamper, agreement-with-allocator, redistribution-chain reconstruction,
  supplemental independent edge cases, and fixed spec/corpus roots.
- [`prf-pro-rata-allocation` Rust crate](https://github.com/protocol-robustness/prf-pro-rata-allocation/tree/48e0b362748cd39d09994a8aa1496c668199aa74/rust) — 52 tests pass, including independent
  exact reconstruction and independently constructed canonical spec/corpus
  root parity.
- `prf-coprocessor@e2e4e9b25622c52eb028fc373a66e81b3982e933/exact-verifier-sp1-program` — guest ELF compilation and positive
  fixture execution succeeded. Output roots matched:
  fairness spec `e0650cf27f9ba13f2c1799ad0ee09ba79214edd1b80a625b5e63f75a7bc5c7f4`,
  supplemental corpus `a6d723b27f154d6981cc41a7837ef7a9278ed3265eb98e4a480871845c5b97c1`,
  and verification input `ee2401b91c694d66990b96114555485b5d8efc9944351c8a9339b64806b4339f`.
  Malformed/tampered fixture coverage and proof qualification remain open.
- `test/resolver_sim/economics/payoffs_test.clj` — updated coverage assertions.
- Full battery across the seven touched suites:
  211 tests / 732 assertions / 0 failures / 0 errors.
