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
