# Agent-c integration receipt — 2026-10-17

## Scope and source revisions

Integration was performed only in the `integration` workspace. The existing integration rebase and notebook repairs were preserved in JJ change `rstosnuoyvvlxvsrlsyluvmxvuxwrywz` (`2e660ad4dba8fceefabaedcee618de13b7dc7d96`), on top of the existing rebase to main (`9a43239571c8a447fa851e21b3735b081e63fe14`).

The two post-base `agent-c` changes integrated were:

- `typswkyomzqspvxzxrknzyprtyxuzmtx` (`4e2cebb6aff8240e05026ac0fb0eede2f0a52916`): parameterized canonical genesis-root derivation and added explicitly unapproved Base Sepolia input templates.
- `urzvusptuunvlrsuxykpskpsltoqyslq` (`e4924b7c32590314bfc8fb41be82dbafbec8ae11`): clarified the historical Stage A V2 baseline versus the additive successor-entitlement lifecycle.

Integration revisions:

- `nromqoylznxskyozuvswymmqowkwpkmn` (`4e6f099d7571a620115dfe2b53f23fa69abe042c`): genesis derivation and candidate templates.
- `nymuspyzvmouywxtnxkzszpvmstzknny` (`e9ac4405f7ce7a1a195723ea2d03a74deb731365`): lifecycle clarification; CHANGELOG conflict resolved by retaining each meaningful entry once.
- `mknqoqwzqzqsmmoumrmnsrxslprnlumn` (`f5f9fae5b11dcfc0de34382a5f949c454a2953c9`): updated focused expectations for the package-schema result and profile-identity behavior; changelog records the observable root-derivation behavior.

The former agent-c-1/c-3 work is already in the integration ancestry and was not replayed. No changes were taken from agent-c-2's dirty working copy or from agent-c-proXX workspaces. Original source workspaces and histories were left intact.

JJ retains an earlier same-change-ID revision of the root-merge checkpoint from before its empty parent was described. Its tree is identical to the selected merge (`jj diff` reports zero file changes); the integration line uses the revision with the described checkpoint. The earlier revision and all source histories were preserved rather than abandoned.

## Validation

- Focused genesis suite: 84 tests, 190 assertions; 0 failures, 0 errors.
- `bb fmt`: passed; no unrelated formatting changes.
- `bb lint`: passed; 0 errors and 81 existing warnings.
- `bb validate`: passed. Notebook support passed; all 53 registered notebooks loaded; SPEDS passed 66 tests / 265 assertions. Report: `target/validation/20261017T094443.816Z-c6bd5be3-a1ee-4cd7-809e-61e533c5a4fa/validation-report.edn`.
- `bb backstop`: passed. Yield unit suite: 179 tests; yield scenarios: 14/14 and 15/15; default backstop completed with `BACKSTOP DEFAULT PASSED`. Existing ownership check reported five mixed-authority files, with content-authority check passing.
- With `PRF_DEPLOY_CHAIN_ID=84532`, both Base Sepolia candidate package commands derived roots successfully. Results were explicitly `derived-only-not-approved`:
  - Authority package: `sha256:88ccddb67935097b5dce214ab7a618218bf762d69683785305ca357384f2ba2f`.
  - Initial configuration package: `sha256:ecf384cbb32b68256f5de949e7e3bacc1f0e485f5e590cc8adcc5890388aa353`.

## Qualification limits

This integration did not execute or qualify a staged deployment. The Base Sepolia package values remain owner-supplied candidates; the derived roots do not constitute approval, deployment evidence, or readiness. PRO-6 remains open pending its producer-to-consumer staged execution, canonical input approval, live genesis/configuration actions, and independent acceptance. No network transactions were submitted.

## Consolidation follow-up — 2026-10-17

### Preserved heads and integrated source

Recovery bookmarks preserve the original integration and source-workspace heads. Full commit identities:

| Workspace | Workspace head | Source bookmark/head |
| --- | --- | --- |
| `integration` before this merge | `a63380ce444ef3f6e87f45b9390939c40fb93c93` | `recovery/integration-head-2026-10-17` |
| `agent-c` | `c939935fce6b389755e8f8b5876945f0b5cd856b` | `5ba112d8b5c533d7069cc6628f38712026f35346` |
| `agent-c-1` | `564d0dd63790abf42e20a2ca0ae166018e892f79` | `a76cb6591052c4ddaac62821a69f2ce46ef0faaf` |
| `agent-c-2` | `dc373d504ef123b148e334dcdb4641d9c7a0b224` | `cc93a4c1e61b9339b79ee0f06729ae83bc8083ce` |
| `agent-c-2` divergent candidate | `8c2415f914072b4e4fbfa0469cc7faf278639078` | preserved as `recovery/agent-c-2-alt-2026-10-17` |
| `agent-c-3` | `9110e776ad04dd9cdb5c96f3d7e20d962f366112` | `7409621f1ddcb4415b5c4203f581953e5842d74e` |

The earlier integration ancestry already contained the eligible committed changes from `agent-c`, `agent-c-1`, `agent-c-3`, and the original `agent-c-2` bookmark, so those were not replayed. The substantive divergent `agent-c-2` candidate `8c2415f914072b4e4fbfa0469cc7faf278639078` was integrated into the integration workspace in the described merge change `vozsukzkuvrlqwmmqkxwwswvzpnvxslr` (commit before recording this receipt: `1ecf78ac6867ee9eb9604c6ead896f0679b2e54a`). Its bounded-configuration implementation, tests, source-bound release evidence and pinned cross-repository patches are now in the consolidated tree. The 1,997,013-byte `protocol-deployments-current-head-overlay.patch` was copied from the preserved `agent-c-2` WIP because the candidate baseline pins its SHA-256 as `a4e4c7b37df5e263df0a282d6e236d5cf47b9c25fa3ae55cffcc1d317656f45a`.

### Resolution and retained WIP

The `scripts/deployment/genesis_roots.clj` conflict was resolved in integration by retaining integration's existing production-chain/package allowlists and adding the candidate's initial-configuration artifact handling, JSON key handling and canonical empty verifier-registry dependency. The resolved authority-state output uses the canonical artifact schema. `CHANGELOG.md` has no unresolved markers or integration diff. The c2 divergent changelog contains a JJ conflict; its unique meaningful entries were compared against the consolidated changelog, where they are already present, and were not duplicated. Historical conflict records remain in the preserved source histories.

Uncommitted source work was not discarded or folded into the candidate merge:

- `agent-c-2` WIP is retained at its original workspace. A recovery archive is `/home/user/agent-c-recovery/2026-10-17/agent-c-2-wip.tar.gz`, SHA-256 `93395f7d196277f3a26a216c373e425a63769a6900bd299f61ed3cf2b73fc255`. The original too-large patch remains in that workspace; its pinned copy is in the integration tree as noted above.
- `agent-c-1`'s untracked `q` status capture remains in its workspace and is also preserved at `/home/user/agent-c-recovery/2026-10-17/agent-c-1-q`, SHA-256 `c367bc63bf45664592ff71d6f9fb7374f4abecc0d572285d11a3aefc740eb40c`.

The `agent-c-2` WIP is not rebased because it contains a substantial alternate candidate tree with unresolved changelog ancestry; it remains explicitly pending comparison. `agent-c-1` remains pending workspace realignment because its source history has a changelog conflict and the untracked capture has not been resolved as user work. Neither workspace was overwritten. `agent-c` and `agent-c-3` had no uncommitted file changes and are eligible for a fresh development change on the consolidated baseline after this receipt is committed. No `agent-c-proXX` workspace or bookmark was changed.

### Consolidated validation

- `bb fmt`: passed after conflict resolution.
- Focused bounded-configuration/genesis suites: 124 tests, 452 assertions; 0 failures, 0 errors.
- `bb lint`: passed, 0 errors and 81 existing warnings.
- `bb validate`: passed; notebook support passed, 53/53 notebooks passed, and SPEDS passed 66 tests / 265 assertions. Report: `target/validation/20261017T104722.760Z-7a423eed-9a71-4791-8169-e4bce67a9c4f/validation-report.edn`.
- `bb backstop`: passed; yield unit tests passed (179 tests / 969 assertions), yield provider scenarios 14/14, SEW yield scenarios 15/15, formatting and lint passed, and the default gate printed `BACKSTOP DEFAULT PASSED`.
- The merge tree contains no unresolved conflicts. The current integration worktree has only the described merge-resolution and receipt changes until they are snapshotted into the integration history.

### Remaining qualification and integration status

The consolidated candidate does not qualify a live or staged deployment. Candidate semantic inputs still need owner approval; no live genesis sealing, successor configuration activation or public-network transaction was performed. PRO-6 remains open on those acceptance criteria. The c1/c2 workspaces remain preserved with pending work as described above. The final integration commit and any clean workspace development commits are identified by their immutable JJ IDs in the reconciliation completion report; the containing receipt commit cannot include its own commit ID.
