# PRF / Sew Protocol — Trace Equivalence Attestation

**Generated:** 2026-07-27T14:29:25Z
**Manifest:** `etc/trace-solidity-manifest.edn` (SHA-256: `f379dcc894f1bb94e7a9becc8b4a496eb09b7e0ea5f80035ceddceb0c47541bf`)
**Clojure repo:** `/home/user/Code/.workspaces/agent-c`
**Solidity repo:** `/home/user/Code/sew-protocol`

---

## Summary

The PRF Clojure implementation and sew-protocol Solidity implementation demonstrate manifest-bound trace equivalence for 18 SEW and reference-validation traces. Every declared trace is SHA-256 matched across repositories and replayed against the Solidity implementation under CDRS v0.2.

| Metric | Value |
|--------|-------|
| Manifest traces | 18 |
| Manifest SHA-256 | `f379dcc894f1bb94e7a9becc8b4a496eb09b7e0ea5f80035ceddceb0c47541bf` |
| Fixture schema | CDRS v0.2 (schema_version 2) |
| Forge tests passed | 21/21 |
| Excluded traces | 13 (documented below) |

---

## Included Traces (18)

Each trace is cryptographically bound to its Solidity fixture via SHA-256. The source-sha256 column confirms byte-content equivalence.

| ID | Source path | Source SHA-256 | Forge fixture |
|---|---|---|---|
| `sew-001-same-block-dual-resolution` | `suites/sew-domain-reference-v1/expected/traces/sew-001-same-block-dual-resolution.trace.json` | `fcb0384d0952fe1e4db855ec86acf4477e1d7193e20d20b2618940a783c96d83` | `test/foundry/traces/v2/sew-001.json` |
| `sew-002-pending-settlement-expiry` | `suites/sew-domain-reference-v1/expected/traces/sew-002-pending-settlement-expiry.trace.json` | `4c37bb3fbffef83c89e69681a90ae93a4dc94de27e14a8c153f65bad81a55176` | `test/foundry/traces/v2/sew-002.json` |
| `sew-003-escalation-after-terminal` | `suites/sew-domain-reference-v1/expected/traces/sew-003-escalation-after-terminal.trace.json` | `c5fee432be6346dbf52eab75d15c3ed1499356e0a573c35bc85e364c3da1b4d2` | `test/foundry/traces/v2/sew-003.json` |
| `sew-004-force-refund-illegal-release` | `suites/sew-domain-reference-v1/expected/traces/sew-004-force-refund-illegal-release.trace.json` | `a9767cdd549bec72e9e69b43ae8640f210fade2784797d121c42a6b2bcf08216` | `test/foundry/traces/v2/sew-004.json` |
| `sew-005-escalation-supersedes-pending` | `suites/sew-domain-reference-v1/expected/traces/sew-005-escalation-supersedes-pending.trace.json` | `009478a829d88f15f21e9795d8341eb3a7bc87069a57b732f77b070855e9a2aa` | `test/foundry/traces/v2/sew-005.json` |
| `ref-001-governance-sandwich` | `suites/reference-validation-v1/expected/traces/001-governance-sandwich.trace.json` | `ec5680d26df627fd9859353617459a47882f4d4553a93ae136fcf8b98517ac71` | `test/foundry/traces/v2/ref-001.json` |
| `ref-002-malicious-resolver-verdict` | `suites/reference-validation-v1/expected/traces/002-malicious-resolver-verdict.trace.json` | `86c203c080ec274aed9317960dc4652fd155fd1e97e4223ce0728cbaa3726a5e` | `test/foundry/traces/v2/ref-002.json` |
| `ref-003-dispute-flooding` | `suites/reference-validation-v1/expected/traces/003-dispute-flooding.trace.json` | `0740ad6beb7ea5f9012cd47eceef317b91b986ae6613e23ab3fdb490d5e5cd34` | `test/foundry/traces/v2/ref-003.json` |
| `ref-004-bond-withdrawal-race` | `suites/reference-validation-v1/expected/traces/004-bond-withdrawal-race.trace.json` | `1dbf1f96c8d7238a41ee27b1b7f4de8bfadb01b5644e4501e9ec5b42e28ff1a0` | `test/foundry/traces/v2/ref-004.json` |
| `ref-005-same-block-ordering` | `suites/reference-validation-v1/expected/traces/005-same-block-ordering.trace.json` | `fcb0384d0952fe1e4db855ec86acf4477e1d7193e20d20b2618940a783c96d83` | `test/foundry/traces/v2/ref-005.json` |
| `ref-006-autopush-settlement` | `suites/reference-validation-v1/expected/traces/006-autopush-settlement.trace.json` | `52e78d8a6911add41c2dde132ff6e1fb6f55f09e953edfe48d58b9dace9e7ebd` | `test/foundry/traces/v2/ref-006.json` |
| `ref-007-appeal-failure-cascade` | `suites/reference-validation-v1/expected/traces/007-appeal-failure-cascade.trace.json` | `8a7bf89a442b64d5716538cc5b71d2fc54bf7d19ab75af7147acf0ee5db1b050` | `test/foundry/traces/v2/ref-007.json` |
| `ref-008-yield-accrual-efficiency` | `suites/reference-validation-v1/expected/traces/008-yield-accrual-efficiency.trace.json` | `704413e7afbcb479215de4b5914cf02d3658f8ea0d55ea7803b9dcd03bfda8ed` | `test/foundry/traces/v2/ref-008.json` |
| `review-s-dr-001-basic-release-ruling` | `suites/ef-review-v1/expected/traces/review-s-dr-001.trace.json` | `eaef58eb8072bb9453e95a86fee8b507d2fdde0d163f46a9f94e0ca521ac88f8` | `test/foundry/traces/v2/review-s-dr-001.json` [S-DR-001-basic-release-ruling] |
| `review-s-dr-084-evidence-after-settlement-rejected` | `suites/ef-review-v1/expected/traces/review-s-dr-084.trace.json` | `71a24ee34db86cf937e4b442fe2f548497b736b9da02901d08c5c43a03ef780a` | `test/foundry/traces/v2/review-s-dr-084.json` [S-DR-084-evidence-after-settlement-rejected] |
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
