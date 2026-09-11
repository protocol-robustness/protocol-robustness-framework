# PRF / Sew Protocol — Trace Equivalence Attestation

**Generated:** 2026-08-04T14:07:34Z
**Manifest:** `etc/trace-solidity-manifest.edn` (SHA-256: `408b9a2b35fb570bacc00bcefa248d78e2ee7bfca43484a014f8570baab5b863`)
**Clojure repo:** `/home/user/Code/.workspaces/agent-c`
**Solidity repo:** `/home/user/Code/sew-protocol`
**Solidity commit:** `f248eceb9d80d9228367cd8a39f618c99e09b9a9`

---

## Summary

The Clojure reference implementation and the sew-protocol Solidity implementation are bound by a SHA-256 trace manifest. Within that manifest, each trace is classified by how far its equivalence is demonstrated. The vocabulary is load-bearing: **fixture sync integrity** (byte identity) is NOT contract equivalence.

- **Contract-replayed** — the fixture was replayed against live contracts by a Forge test in `TraceEquivalenceTest.sol`, with a per-trace replay receipt emitted under `out/receipts/` as execution evidence. Each receipt records the fixture content hash, the negotiated replay-spec, and observable invariant-profile application. Only these traces are `equivalence verified`.
- **Byte-synchronised only** — the fixture is byte-identical (SHA-256) to the Clojure source, but no replay receipt exists yet because the trace uses actions the basic vault harness cannot reproduce.

| Metric | Value |
|--------|-------|
| Manifest traces | 18 |
| Manifest SHA-256 | `408b9a2b35fb570bacc00bcefa248d78e2ee7bfca43484a014f8570baab5b863` |
| Fixture schema | CDRS v0.2 (schema_version 2) |
| Forge tests passed | 38/38 |
| Contract-replayed | 10 |
| Byte-synchronised only | 8 |

---

## Contract-Replayed Traces (10)

These fixtures were replayed against the EVM and have a replay receipt under `out/receipts/` as execution evidence. The receipts, not the test-function inventory, define this set. Execution-level assertions (per-step projection, invariant profile with observable application, terminal projection hash) run for each.

| ID | Source path | Source SHA-256 | Forge fixture |
|---|---|---|---|
| `sew-001-same-block-dual-resolution` | `suites/sew-domain-reference-v1/expected/traces/sew-001-same-block-dual-resolution.trace.json` | `8960f2f20240c2c75648a7d18d9d688e24d7d9ce324a2f7b1e2c11d62114da66` | `test/foundry/traces/v2/sew-001.json` |
| `sew-002-pending-settlement-expiry` | `suites/sew-domain-reference-v1/expected/traces/sew-002-pending-settlement-expiry.trace.json` | `f16795d789afbe8499a5f6f9e1e56f70f43cfffdf8bfb8717c71fddf31ab24ec` | `test/foundry/traces/v2/sew-002.json` |
| `sew-003-escalation-after-terminal` | `suites/sew-domain-reference-v1/expected/traces/sew-003-escalation-after-terminal.trace.json` | `ef00822a58d34fa15919db2db3e52dc153ed80b9d64c37a835fb54e438c82585` | `test/foundry/traces/v2/sew-003.json` |
| `sew-004-force-refund-illegal-release` | `suites/sew-domain-reference-v1/expected/traces/sew-004-force-refund-illegal-release.trace.json` | `19f252ea31560b1b3a763e270f8fc07a82447363b170d85daacb581ea0be71a1` | `test/foundry/traces/v2/sew-004.json` |
| `ref-001-governance-sandwich` | `suites/reference-validation-v1/expected/traces/001-governance-sandwich.trace.json` | `632a3122bf4edb2ad75f72320617061a0fd32679d9a02b4559438ab5a2a74cb1` | `test/foundry/traces/v2/ref-001.json` |
| `ref-004-bond-withdrawal-race` | `suites/reference-validation-v1/expected/traces/004-bond-withdrawal-race.trace.json` | `536223c0c467b411f3bfad95696acb9c7271241bdc7424cc5f566c8175d3c4d0` | `test/foundry/traces/v2/ref-004.json` |
| `ref-005-same-block-ordering` | `suites/reference-validation-v1/expected/traces/005-same-block-ordering.trace.json` | `8960f2f20240c2c75648a7d18d9d688e24d7d9ce324a2f7b1e2c11d62114da66` | `test/foundry/traces/v2/ref-005.json` |
| `ref-006-autopush-settlement` | `suites/reference-validation-v1/expected/traces/006-autopush-settlement.trace.json` | `e526f98849e90565e6a0147d845edd1ca4182ae076367c68e9e6d42c347b4e88` | `test/foundry/traces/v2/ref-006.json` |
| `ref-007-appeal-failure-cascade` | `suites/reference-validation-v1/expected/traces/007-appeal-failure-cascade.trace.json` | `11a3cecca00a92066cc3ec2d60aa0bcb41bebc89664cd6e08cd50b8c5fd01687` | `test/foundry/traces/v2/ref-007.json` |
| `review-s-dr-001-basic-release-ruling` | `suites/ef-review-v1/expected/traces/review-s-dr-001.trace.json` | `b2a619cbb7310c80ad136ed071492734167b7098e1a7b5965a927fbfdfdef307` | `test/foundry/traces/v2/review-s-dr-001.json` [S-DR-001-basic-release-ruling] |

---

## Byte-Synchronised Traces (8)

These fixtures are SHA-256 matched to their Clojure sources and byte-verified by `bb trace:solidity:verify`, but are NOT yet wired into Forge.  Byte-sync proves fixture integrity, not contract equivalence. Each is listed with the harness boundary that currently prevents replay.

| ID | Source path | Source SHA-256 | Forge fixture | Reason not replayed |
|---|---|---|---|---|
| `sew-005-escalation-supersedes-pending` | `suites/sew-domain-reference-v1/expected/traces/sew-005-escalation-supersedes-pending.trace.json` | `8e049967000ea9ffbaedf0ae2ca98dba9160518087d7069fbb0104da47c1931c` | `test/foundry/traces/v2/sew-005.json` | contains an accepted escalate_dispute step; DefaultResolutionModule.canEscalate returns false |
| `ref-002-malicious-resolver-verdict` | `suites/reference-validation-v1/expected/traces/002-malicious-resolver-verdict.trace.json` | `a02b665fad371359c6cac2d7c9c1c1c592686fc81927e246acf3ca4c16e67975` | `test/foundry/traces/v2/ref-002.json` | requires propose_fraud_slash / slashing-module actions |
| `ref-003-dispute-flooding` | `suites/reference-validation-v1/expected/traces/003-dispute-flooding.trace.json` | `c4ab14c31ee4a6ae8109e16a2ee725f77ecd0d31a288897194946ac1b154ebc7` | `test/foundry/traces/v2/ref-003.json` | escrow amounts (500 wei) below contract MIN_ESCROW_AMOUNT (1000); sim does not enforce the minimum |
| `ref-008-yield-accrual-efficiency` | `suites/reference-validation-v1/expected/traces/008-yield-accrual-efficiency.trace.json` | `4121022b554bdc4263c7a37933ddef5d3647f36b31b192bb03f39c071ab7ecbb` | `test/foundry/traces/v2/ref-008.json` | uses yield-only action trigger-accrue (YieldOps) |
| `review-s-dr-084-evidence-after-settlement-rejected` | `suites/ef-review-v1/expected/traces/review-s-dr-084.trace.json` | `aec7bd34f4877a9f9f43663981b47d7dfdcc5d7d581c82d9cf81abe303e134c1` | `test/foundry/traces/v2/review-s-dr-084.json` [S-DR-084-evidence-after-settlement-rejected] | requires submit_evidence on EvidenceModuleV1 |
| `review-nc-001-freeze-active-dispute` | `suites/ef-review-v1/expected/traces/review-nc-001.trace.json` | `5343c589f830462004bc847f2cf42bfb3f75c37eb1a9ea66c8090975b6fb8197` | `test/foundry/traces/v2/review-nc-001.json` [S-NC-001-freeze-active-dispute-negative-control] | requires propose/execute_fraud_slash (slashing module) |
| `review-y06-pro-rata-shortfall` | `suites/ef-review-v1/expected/traces/review-y06.trace.json` | `b0066145f7f3203e4f5df3823b2459581f8bb77de9b7e55e597064939e47587b` | `test/foundry/traces/v2/review-y06.json` [Y06_multi-party-pro-rata-shortfall] | yield-only actions (YieldOps) |
| `review-dr-n-002-appeal-rejected` | `suites/ef-review-v1/expected/traces/review-dr-n-002.trace.json` | `352327a5e8deb902eda04146173262d2a8623521efab9fc77041ff2b9263da6b` | `test/foundry/traces/v2/review-dr-n-002.json` [DR-N-002-reversal-slash-appeal-rejected.edn] | requires submit_evidence / appeal_slash / challenge_resolution / resolve_appeal actions |

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

Expected result: `VERIFIED` (all 18 manifest traces pass byte verification).

### Step 2 — Forge EVM replay

```bash
cd ../sew-protocol
forge test --match-contract TraceEquivalenceTest -vvv
```

Result at generation time: 38/38 passed (of which 10 are contract-replayed manifest traces).

### Step 3 — Regenerate this attestation

```bash
python3 etc/generate-equivalence-attestation.py --sew-repo ../sew-protocol
```

---

## Boundary of the Equivalence Claim

The attested equivalence is **manifest-bound and layered**. It claims:

- Byte-identical fixtures across repos for all 18 manifest traces.
- Contract-level equivalence for the 10 contract-replayed traces listed above, including the invariant profile (conservation-of-funds, dispute-level-bounded, terminal-payout-exclusivity, held-reconstruction, state-transition-valid, escalation-monotonic, terminal-state-immutable) and the terminal projection hash (SHA-256 of `state|afa|psExists|disputeLevel`).

It does NOT claim:

- Contract-level equivalence for the 8 byte-synchronised-only traces.
- Full Sew protocol equivalence beyond the wired traces.
- Module-backed stake or slashing parity (not exercised).
- Yield module integration (requires separate harness).
- Non-zero dispute-level / successful-escalation traces (DefaultResolutionModule cannot escalate).
- Generic EVM state equivalence (the projection is limited to the `diff.clj` comparable-keys).
- Automatic cross-repository synchronisation (sync is manual via `bb trace:solidity:sync`).

Within the contract-replayed scope, no unresolved semantic divergence remains between the Clojure reference implementation and the Solidity contracts.
