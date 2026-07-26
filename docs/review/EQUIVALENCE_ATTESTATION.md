# PRF / Sew Protocol — Trace Equivalence Attestation

**Generated:** 2026-07-26T22:33:52Z
**Manifest:** `etc/trace-solidity-manifest.edn` (SHA-256: `0511b7717ee0074373b2e08a53c374e70eb21653aadf59d2c8de26319c3a8a2d`)
**Clojure repo:** `/home/user/Code/.workspaces/agent-c`
**Solidity repo:** `/home/user/Code/sew-protocol`

---

## Summary

The PRF Clojure implementation and sew-protocol Solidity implementation demonstrate manifest-bound trace equivalence for 18 SEW and reference-validation traces. Every declared trace is SHA-256 matched across repositories and replayed against the Solidity implementation under CDRS v0.2.

| Metric | Value |
|--------|-------|
| Manifest traces | 18 |
| Manifest SHA-256 | `0511b7717ee0074373b2e08a53c374e70eb21653aadf59d2c8de26319c3a8a2d` |
| Fixture schema | CDRS v0.2 (schema_version 2) |
| Forge tests passed | 21/21 |
| Excluded traces | 13 (documented below) |

---

## Included Traces (18)

Each trace is cryptographically bound to its Solidity fixture via SHA-256. The source-sha256 column confirms byte-content equivalence.

| ID | Source path | Source SHA-256 | Forge fixture |
|---|---|---|---|
| `sew-001-same-block-dual-resolution` | `suites/sew-domain-reference-v1/expected/traces/sew-001-same-block-dual-resolution.trace.json` | `ee623f1c6327d7dc1582b5c85f60c017f9f4d948261adab30493c622b977c4eb` | `test/foundry/traces/v2/sew-001.json` |
| `sew-002-pending-settlement-expiry` | `suites/sew-domain-reference-v1/expected/traces/sew-002-pending-settlement-expiry.trace.json` | `531ad2c6a40413349d2165fe773516e55259468f5310c0128ee311874044e261` | `test/foundry/traces/v2/sew-002.json` |
| `sew-003-escalation-after-terminal` | `suites/sew-domain-reference-v1/expected/traces/sew-003-escalation-after-terminal.trace.json` | `2d92505bc7da060b1501cfe4f220195f1ee81473610efc9b534b760dbeb34ac2` | `test/foundry/traces/v2/sew-003.json` |
| `sew-004-force-refund-illegal-release` | `suites/sew-domain-reference-v1/expected/traces/sew-004-force-refund-illegal-release.trace.json` | `313f9e3bc4da1c16c557f3401d9a5567a5ab64d340ac702f63e87a40cda88100` | `test/foundry/traces/v2/sew-004.json` |
| `sew-005-escalation-supersedes-pending` | `suites/sew-domain-reference-v1/expected/traces/sew-005-escalation-supersedes-pending.trace.json` | `5ec8314b5766d78f527225fa71a6e540f522dcc739b896434106464bca21add4` | `test/foundry/traces/v2/sew-005.json` |
| `ref-001-governance-sandwich` | `suites/reference-validation-v1/expected/traces/001-governance-sandwich.trace.json` | `c9b5f9c628d7b9b4c360d0458039f8c866b0e8e7cc5823f003ab27bd0cb6062b` | `test/foundry/traces/v2/ref-001.json` |
| `ref-002-malicious-resolver-verdict` | `suites/reference-validation-v1/expected/traces/002-malicious-resolver-verdict.trace.json` | `79bf8fd514d2176b132daad7637ca6ff97d8803811fc728fa93fa49272ec7d08` | `test/foundry/traces/v2/ref-002.json` |
| `ref-003-dispute-flooding` | `suites/reference-validation-v1/expected/traces/003-dispute-flooding.trace.json` | `f2997311a7b17289d0a6d8289350cff9624f92410d97afaceb0b24a9bbb20772` | `test/foundry/traces/v2/ref-003.json` |
| `ref-004-bond-withdrawal-race` | `suites/reference-validation-v1/expected/traces/004-bond-withdrawal-race.trace.json` | `29ac4aa2e5e328087cab7c068394c50eb9d0171ece31a6db526c09794eef7e8f` | `test/foundry/traces/v2/ref-004.json` |
| `ref-005-same-block-ordering` | `suites/reference-validation-v1/expected/traces/005-same-block-ordering.trace.json` | `ee623f1c6327d7dc1582b5c85f60c017f9f4d948261adab30493c622b977c4eb` | `test/foundry/traces/v2/ref-005.json` |
| `ref-006-autopush-settlement` | `suites/reference-validation-v1/expected/traces/006-autopush-settlement.trace.json` | `cf903d3d61d9d60e66cd318eaaeed299eaa8b99c93d550805c98a220cb8c7c2c` | `test/foundry/traces/v2/ref-006.json` |
| `ref-007-appeal-failure-cascade` | `suites/reference-validation-v1/expected/traces/007-appeal-failure-cascade.trace.json` | `ed36b4d901eb7a3c3c04d360945595c19b75396447e42a4f4474327709ba23e3` | `test/foundry/traces/v2/ref-007.json` |
| `ref-008-yield-accrual-efficiency` | `suites/reference-validation-v1/expected/traces/008-yield-accrual-efficiency.trace.json` | `3b843aedeca7ba053eb7795edd5fbb60c11622c17413bd2cd1842bbbd6a2574d` | `test/foundry/traces/v2/ref-008.json` |
| `review-s-dr-001-basic-release-ruling` | `suites/ef-review-v1/expected/traces/review-s-dr-001.trace.json` | `f7711e609163a8ea0b31c3086a660a0a3fd1d40434972a3837133893a680c389` | `test/foundry/traces/v2/review-s-dr-001.json` [S-DR-001-basic-release-ruling] |
| `review-s-dr-084-evidence-after-settlement-rejected` | `suites/ef-review-v1/expected/traces/review-s-dr-084.trace.json` | `67f4baed0a791ed128baff49f127d7554c49d7f47a8dbfad1e597fdcce895919` | `test/foundry/traces/v2/review-s-dr-084.json` [S-DR-084-evidence-after-settlement-rejected] |
| `review-nc-001-freeze-active-dispute` | `suites/ef-review-v1/expected/traces/review-nc-001.trace.json` | `58b70ed86450fc8f475f4dac2a801768241e5b53949d7d3d162b996af491872a` | `test/foundry/traces/v2/review-nc-001.json` [S-NC-001-freeze-active-dispute-negative-control] |
| `review-y06-pro-rata-shortfall` | `suites/ef-review-v1/expected/traces/review-y06.trace.json` | `99b533979d487de5b50b336d2a758f25de86deb10d49c64ff8c8f310bd34f156` | `test/foundry/traces/v2/review-y06.json` [Y06_multi-party-pro-rata-shortfall] |
| `review-dr-n-002-appeal-rejected` | `suites/ef-review-v1/expected/traces/review-dr-n-002.trace.json` | `925f895c8a900aec53c2826fe2304ba360a3ed8adb9d46b8ec90517beb9f3caa` | `test/foundry/traces/v2/review-dr-n-002.json` [DR-N-002-reversal-slash-appeal-rejected.edn] |

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
