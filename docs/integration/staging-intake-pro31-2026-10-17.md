# PRO-31 staging intake — blocked on PRO-28

**Status:** `pending-dependency` — reviewed source delta recorded; not admitted to integration.

This record pins the reviewed PRO-31 source and its dependency for the integration staging process. It is an intake/review record only. It is not a deployment manifest, an admitted release, or proof that the change has been integrated.

## Source identity

| Field | Value |
| --- | --- |
| Linear issue | PRO-31 — Authenticate forensic block and runtime context |
| Source workspace | `/home/user/Code/.workspaces/agent-c-pro31` |
| Source bookmark | `marcgriffiths/pro-31-live-chain-context` |
| Source commit | `900df46252072c0e1cb864059f362755467d747f` |
| Source change ID | `vutmnkzlsxmmkwssuvlmluvyvqqmzxou` |
| Required parent / PRO-28 source | `6118125d2d02d980952dd1799d4513fa176a1a43` |
| Required parent change ID | `vptwlnxxpkqxrxvyprrtkpokttkmzxrm` |
| Commit subject | `Authenticate forensic block and runtime context` |
| Patch SHA-256 (`jj diff --git` for source commit) | `6753ff63421c2744b54a60a451e1fa91a87550212a11426763fe628a5dbbd3f9` |

The PRO-31 commit is a direct child of the pinned PRO-28 commit. The source delta is not an ancestor of the current `integration` bookmark and is not represented in this integration tree.

## Pinned source payload

These hashes are SHA-256 of each path's complete file contents at source commit `900df46252072c0e1cb864059f362755467d747f`.

| Path | Source content SHA-256 |
| --- | --- |
| `CHANGELOG.md` | `9e8c16354b55f3fede37e5f6a0bb0cf3227b1618a32ee4967d594540cb151b95` |
| `examples/partial-fill/evidence/pro-28-v1/README.md` | `0013ecb3c887439fc871555ca79b699f0ef0678ffb0f13245b71ab77f206f013` |
| `examples/partial-fill/evidence/pro-28-v1/raw/fresh-replay-20261013/live-chain-check.json` | `00032150632ebf5b5d92903eb6b027cb6d5d67f16432dcfcbbfa18bd204cfd3f` |
| `scripts/forensic/pro28_receipts.py` | `5d6b6e209b8b3888c39f0f4e9becd133314f6caebc0e054466b29bf3f66a5ddc` |
| `test/forensic_python/test_pro28_receipts.py` | `4c3db1ea97641eccc942e68ca164b77ec685ea35e9d783e25c78f7fc0354d17e` |

## Review and dependency disposition

- The exact PRO-31 revision received an independent review with no actionable findings. That review did not establish a reproducible test sign-off; pytest was unavailable in the reviewed environment.
- PRO-31 depends on PRO-28's receipt/economic-evidence baseline. The retained PRO-28 evidence is not accepted: reported transfer metadata is inconsistent with the expected reserve deposit interface (`sealRegistry(bytes32)` versus `deposit(uint256)`), and the latest retained replay was reported as 11 passing / 10 failing.
- Therefore PRO-31 is **blocked from integration** until PRO-28 is resolved and the PRO-31 delta is revalidated against the accepted PRO-28 result. Do not detach or reinterpret this delta as an independent fix: its evidence and tests modify PRO-28 paths.
- No PRO-31 source was copied, replayed, rebased, or merged by creating this intake. No Linear status was changed.

## Admission conditions

Before integrating, confirm all of the following:

1. PRO-28 has an accepted source revision and retained replay evidence.
2. The PRO-31 parent dependency is updated or explicitly shown compatible without rewriting its historical source identity.
3. The five payload hashes above still match the pinned PRO-31 commit, or any changed revision receives a new intake record.
4. The focused forensic tests run successfully in an identified clean checkout; record exact command, commit and results.
5. Integration review confirms the block/runtime observations are tied to the intended chain, block and deployment identities and do not overstate unsigned RPC observations.

Until these conditions are met, preserve the source workspace and history. This intake record does not authorize cleanup, Linear updates, or integration.
