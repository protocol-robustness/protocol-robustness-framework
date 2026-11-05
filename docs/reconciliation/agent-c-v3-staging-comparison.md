# Agent-c V3/EVM staging comparison

Status: working comparison report; not review, admission, approval, or deployment evidence.

Compared source commits with the current `agent-c-staging` tree at
`b772d530c81098508f87cb59abef25526818a2c9`:

| Source commit | Staging counterpart | Disposition | Evidence |
|---|---|---|---|
| `e79cda985b38a650b7e07b8642123a954823819a` | `78289072a21d2ec992789e3fb1fc42a01757668c` | Represented | Source and rewritten successor had identical changed-path snapshots. Subsequent staging commits preserve the conformance/reconstruction behavior; no unique source change identified. |
| `bf543f339bd7d0419f233175d1386f5251e9b779` | `36f6116d` | Represented; tests adapted | Source and rewritten successor had identical changed-path snapshots. Current staging uses canonical result `:rows`, while the source test refers to legacy `:allocations`. Both test the same mutation: alter an allocated amount, rehash the result, and require request matching to reject it. The current fixture shape is the correct one to retain. |
| `dd241eced594769447263270a223d30eb7ea7033` | staging tree | Represented | Assurance concept file in staging matches the source commit result. No follow-up needed. |

No V3/EVM source commit was imported. Checker implementation and its focused
unit test are tracked separately in the candidate. The operator-provided v9
candidate packages and checker JSON were excluded: they contain an address and
keystore UUID and explicitly identify the report as operator-supplied and not
independently rerun.

Commands run included `jj show` for each source commit, `jj diff` between each
source and rewritten successor on changed paths, and focused Clojure tests for
the checker. The checker tests passed: 6 tests, 16 assertions, 0 failures,
0 errors. No V3/EVM tests were rerun because no changes were made to those paths.
