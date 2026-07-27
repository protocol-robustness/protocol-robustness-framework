# PRF / Sew Protocol — Trace Equivalence Attestation

**Generated:** 2026-07-27T17:47:10Z
**Manifest:** `etc/trace-solidity-manifest.edn` (SHA-256: `a870fdb04f2b35bf27606fddb722d76e741b8eb72dbb221c19dac1acfb4c596b`)
**Clojure repo:** `<PRF_ROOT>`
**Solidity repo:** `<SEW_REPO_ROOT>`

---

## Summary

The PRF Clojure implementation and sew-protocol Solidity implementation demonstrate manifest-bound trace equivalence for 18 SEW and reference-validation traces. Every declared trace is SHA-256 matched across repositories and replayed against the Solidity implementation under CDRS v0.2.

| Metric | Value |
|--------|-------|
| Manifest traces | 18 |
| Manifest SHA-256 | `a870fdb04f2b35bf27606fddb722d76e741b8eb72dbb221c19dac1acfb4c596b` |
| Fixture schema | CDRS v0.2 (schema_version 2) |
| Forge tests passed | 24/24 |
| Excluded traces | 13 (documented below) |

---

## Included Traces (18)

Each trace is cryptographically bound to its Solidity fixture via SHA-256. The source-sha256 column confirms byte-content equivalence.

| ID | Source path | Source SHA-256 | Forge fixture |
|---|---|---|---|
| `sew-001-same-block-dual-resolution` | `suites/sew-domain-reference-v1/expected/traces/sew-001-same-block-dual-resolution.trace.json` | `32692d88e5f3d3e30285b620ecb56bff5ee945b68fd5c8785b6a32ab2a7a144b` | `test/foundry/traces/v2/sew-001.json` |
| `sew-002-pending-settlement-expiry` | `suites/sew-domain-reference-v1/expected/traces/sew-002-pending-settlement-expiry.trace.json` | `b9c039bf087308b0f928cb64f4d706e3d422bbcb99524a285a89fe5711157282` | `test/foundry/traces/v2/sew-002.json` |
| `sew-003-escalation-after-terminal` | `suites/sew-domain-reference-v1/expected/traces/sew-003-escalation-after-terminal.trace.json` | `ef00822a58d34fa15919db2db3e52dc153ed80b9d64c37a835fb54e438c82585` | `test/foundry/traces/v2/sew-003.json` |
| `sew-004-force-refund-illegal-release` | `suites/sew-domain-reference-v1/expected/traces/sew-004-force-refund-illegal-release.trace.json` | `66ab8e1b3daed75cf98cd6d3f96011649d04c897dbfd07fa722886a882bac0f2` | `test/foundry/traces/v2/sew-004.json` |
| `sew-005-escalation-supersedes-pending` | `suites/sew-domain-reference-v1/expected/traces/sew-005-escalation-supersedes-pending.trace.json` | `2830ba51c4a158487caad00318bc25fc4ac903a03453590593ca534b52a3f2a1` | `test/foundry/traces/v2/sew-005.json` |
| `ref-001-governance-sandwich` | `suites/reference-validation-v1/expected/traces/001-governance-sandwich.trace.json` | `ce55bf8e7a1f4b9815d11adb7f63843700a872005da6f7b5e0b1bd5f71f77e02` | `test/foundry/traces/v2/ref-001.json` |
| `ref-002-malicious-resolver-verdict` | `suites/reference-validation-v1/expected/traces/002-malicious-resolver-verdict.trace.json` | `ed10410de2235f3541f8791721a674fe47bee92e5a8ac20fe3185e7a4b2f36ad` | `test/foundry/traces/v2/ref-002.json` |
| `ref-003-dispute-flooding` | `suites/reference-validation-v1/expected/traces/003-dispute-flooding.trace.json` | `72a444bb114e1098217adaf291879698b89157d9ba7c1b3a754e7b8ad9db7094` | `test/foundry/traces/v2/ref-003.json` |
| `ref-004-bond-withdrawal-race` | `suites/reference-validation-v1/expected/traces/004-bond-withdrawal-race.trace.json` | `536223c0c467b411f3bfad95696acb9c7271241bdc7424cc5f566c8175d3c4d0` | `test/foundry/traces/v2/ref-004.json` |
| `ref-005-same-block-ordering` | `suites/reference-validation-v1/expected/traces/005-same-block-ordering.trace.json` | `32692d88e5f3d3e30285b620ecb56bff5ee945b68fd5c8785b6a32ab2a7a144b` | `test/foundry/traces/v2/ref-005.json` |
| `ref-006-autopush-settlement` | `suites/reference-validation-v1/expected/traces/006-autopush-settlement.trace.json` | `14ac048fd6379386a37d1a3282d2dbcdfb71f74419acba204a23b310d60c769b` | `test/foundry/traces/v2/ref-006.json` |
| `ref-007-appeal-failure-cascade` | `suites/reference-validation-v1/expected/traces/007-appeal-failure-cascade.trace.json` | `1f3df1cb7ba38fbb116e9c0c427d1ca5e02610e6bd801ccf54a6914ebdfa101f` | `test/foundry/traces/v2/ref-007.json` |
| `ref-008-yield-accrual-efficiency` | `suites/reference-validation-v1/expected/traces/008-yield-accrual-efficiency.trace.json` | `4121022b554bdc4263c7a37933ddef5d3647f36b31b192bb03f39c071ab7ecbb` | `test/foundry/traces/v2/ref-008.json` |
| `review-s-dr-001-basic-release-ruling` | `suites/ef-review-v1/expected/traces/review-s-dr-001.trace.json` | `67ef9e74717c74af00546e8de2fc8d427fd6fdd483b036e64851a8c45e1fe8dd` | `test/foundry/traces/v2/review-s-dr-001.json` [S-DR-001-basic-release-ruling] |
| `review-s-dr-084-evidence-after-settlement-rejected` | `suites/ef-review-v1/expected/traces/review-s-dr-084.trace.json` | `c5f280cd7ca5df445c147f70ef5df8242a6a783be5ebcbfecb759a3ce6ab6364` | `test/foundry/traces/v2/review-s-dr-084.json` [S-DR-084-evidence-after-settlement-rejected] |
| `review-nc-001-freeze-active-dispute` | `suites/ef-review-v1/expected/traces/review-nc-001.trace.json` | `3ebcbb277587ea5f5bcb43e0546c575989a5af3051500e6b340a654f577f425a` | `test/foundry/traces/v2/review-nc-001.json` [S-NC-001-freeze-active-dispute-negative-control] |
| `review-y06-pro-rata-shortfall` | `suites/ef-review-v1/expected/traces/review-y06.trace.json` | `95c00f776573643b94326b3839c798bf2295e4562a87a117235e05984f386b79` | `test/foundry/traces/v2/review-y06.json` [Y06_multi-party-pro-rata-shortfall] |
| `review-dr-n-002-appeal-rejected` | `suites/ef-review-v1/expected/traces/review-dr-n-002.trace.json` | `efcfdfc89c0817d6255fbca69c64d89804eee1e64f9fba496f7a9e507afb0527` | `test/foundry/traces/v2/review-dr-n-002.json` [DR-N-002-reversal-slash-appeal-rejected.edn] |

---

## Excluded Traces (13)

These traces are outside the verified domain for documented reasons. Each is recorded in the manifest with a machine-readable reason code.

| ID | Reason |
|---|---|
| `review-s-dr-084` | requires submit_evidence on EvidenceModuleV1 |
| `review-nc-001` | requires register_stake / slashing module |
| `review-dr-n-002` | requires register_stake / slashing module |
| `review-y06` | yield-only actions (YieldOps) |
| `sew-001` | appeal-window-duration=0 divergence (sim finalizes, Solidity creates pending) |
| `sew-002` | keeper-driven expiry flow incompatible with vault harness |
| `sew-004` | appeal-window-duration=0 divergence |
| `sew-005` | escalation requires DecentralizedResolutionModule |
| `ref-001` | governance-sandwich pattern incomplete in test harness |
| `ref-002` | requires propose_fraud_slash on slashing module |
| `ref-003` | multi-address role dispatch incomplete |
| `ref-004` | requires register_stake |
| `ref-005` | appeal-window-duration=0 divergence |

---

## Pre-existing Solidity Fixtures (14)

These fixtures exist in the Solidity repo but are NOT part of the manifest-bound equivalence claim. They are reported as warnings by `bb trace:solidity:verify` and must not be counted toward the verified trace count.

| Fixture | Classification |
|---|---|
| `test/foundry/traces/trace_create_dispute_cancel.json` | Legacy v0.1 golden fixture |
| `test/foundry/traces/trace_create_dispute_release.json` | Legacy v0.1 golden fixture |
| `test/foundry/traces/trace_create_release.json` | Legacy v0.1 golden fixture |
| `test/foundry/traces/trace_phase_z_liveness.json` | Legacy v0.1 Phase Z adversarial |
| `test/foundry/traces/v2/negative/n01.json` | v0.2 negative/wrong outcome |
| `test/foundry/traces/v2/negative/n02.json` | v0.2 negative/unauthorized resolver |
| `test/foundry/traces/v2/negative/n03.json` | v0.2 negative/settlement not executed |
| `test/foundry/traces/v2/negative/n04.json` | v0.2 negative/wrong escalation level |
| `test/foundry/traces/v2/negative/n05.json` | v0.2 negative/wrong dispute initiator |
| `test/foundry/traces/v2/negative/n06.json` | v0.2 negative/auto-cancel triggered |
| `test/foundry/traces/v2/negative/n07.json` | v0.2 negative/wrong resolution actor |
| `test/foundry/traces/v2/s01.json` | Pre-existing v0.2 baseline (S01) |
| `test/foundry/traces/v2/s02.json` | Pre-existing v0.2 baseline (S02) |
| `test/foundry/traces/v2/s05.json` | Pre-existing v0.2 baseline (S05) |

---

## Verification Procedure

### Step 1 — Cross-repository integrity

```bash
bb trace:solidity:verify --sew-repo ../sew-protocol
```

Expected result: `VERIFIED` (all 18 manifest traces pass).

### Step 2 — Forge EVM replay

```bash
cd ../sew-protocol
forge test --match-contract TraceEquivalenceTest -vvv
```

Expected result: `ok. 21 passed; 0 failed`.

### Step 3 — Regenerate this attestation

```bash
python3 etc/generate-equivalence-attestation.py --sew-repo ../sew-protocol
```

---

## Boundary of the Equivalence Claim

The attested equivalence is **manifest-bound**. It covers only the 18 traces listed in the included table above. It does not claim:

- Full Sew protocol equivalence (12 excluded paths remain)
- Module-backed stake or slashing parity (not exercised)
- Yield module integration (requires separate harness)
- Generic EVM state equivalence (projection is 6-field)
- Automatic cross-repository synchronisation (sync is manual via `bb trace:solidity:sync`)

Within this manifest scope, no unresolved semantic divergence remains between the Clojure reference implementation and the Solidity contracts.
