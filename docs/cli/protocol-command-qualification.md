# Command implementation qualification — 2026-10-18

This records command-interface checks for the existing workloads. It does not
promote allocator, coprocessor, proof or deployment release qualification.

## Immutable sources

| Source | Revision |
|---|---|
| PRF implementation and command tests | `7b54cee8651a7a658968950d33cc9aa0c8dffaf6` |
| PRF artifact-workspace / execution failure follow-up | `8f95770d8d5fb27d0a37e8fa3d486e4d78edd664` |
| PRF auxiliary output-root follow-up | `961fc633adf802717e8e992be6d30830f48007ed` |
| PRF explicit Clojure execution-directory follow-up | `4e5f04f074064881a9e09c7e88977a3c1f5c7351` |
| PRF current command source, including output lock isolation | `234d3d2f34940d8fcaea43c26b21b05c4b587ca4` |
| Unchanged PRF parent used to reproduce existing failures | `fe3fa6e75b7cdf91029d1aef01c6ad5012d47356` |
| Deployment command catalog and dispatcher | `25771923ef0883c1cae9be043375c0113d6554b5` |
| Deployment committed base | `f3b5c7bbf3dbc3950ae26f275a37a41233b21698` |

PRF implementation was committed through jj in `agent-c-1`. Deployment changes
were committed through jj in the isolated
`agent-c-1-protocol-deployments` workspace. The original deployment checkout's
five uncommitted files were preserved. No other agent's workspace was edited.
The temporary baseline workspace was removed after its check. A repository-wide
jj operation restore by another workspace invalidated the temporary feature
workspace registration, so clean qualification used a Git archive of the exact
PRF implementation revision, without `.git`, generated outputs or classpath
overrides.

## Dependency closure

PRF resolves its existing `deps.edn` with `-Srepro`. Tests use `-M:test`; the
source launcher uses `-M:with-sew`. Both use the existing local package graph:

```text
PRF commands -> existing resolver-sim protocol / assurance APIs
PRF -> prf/pro-rata-allocation -> prf/canonical
PRF -> prf/accounting-reductions
forensic adapters -> explicitly selected PRF Python scripts
deployment dispatcher -> existing TypeScript CLI executors
```

No dependency coordinate or source implementation was moved. The clean
classpath enumerated exactly one resource for each existing canonical namespace
(`codec`, `decode`, `hash`, `reference`, `sequence`), from the canonical package.
The node workspace was prepared with
`npm ci --ignore-scripts --prefer-offline`, using its existing lock file.
Python evaluation used pytest 9.1.1, PyNaCl 1.6.2 and jsonschema 4.26.0;
Node evaluation used Node 24.18.0 and the locked TypeScript 5.9.3.

The active Python dependency closure for those three roots (without optional
extras) was:

```text
attrs==26.1.0
cffi==2.0.0
iniconfig==2.3.0
jsonschema==4.26.0
jsonschema-specifications==2025.9.1
packaging==26.2
pluggy==1.6.0
pycparser==3.0
pygments==2.20.0
pynacl==1.6.2
pytest==9.1.1
referencing==0.37.0
rpds-py==2026.6.3
typing-extensions==4.16.0
```

| Dependency or fixture | SHA-256 |
|---|---|
| PRF `deps.edn` | `bd76b12fc9f96a84e02e4cebd46e97d06bc7903c336d691f53d0c061e42898d4` |
| Allocation package `deps.edn` | `97e1004f89fafb7366edce63aa56e3ac8a2c729b09a75b401a92a742b74fc757` |
| Canonical package `deps.edn` | `4b254aff241d8fc7d62defa9ef76a0a5fa3a9784cff06ac7e27e9ee5527a8f48` |
| Accounting reductions package `deps.edn` | `011638d92038a7b586405b8295441b34f1be9d5317a6e990e7553320d95fbc82` |
| Deployment `package-lock.json` | `d873b04e84a42a6c61c58487db3181919467558d60ea3a85ce1364625e991b8d` |
| Source-bound entitlement frozen vectors | `a8a9310fbc439a7d9d21d60078cfce78f714cdaafeebd8d27c2b0a80e2310848` |
| Forensic golden bundle | `2bfd32dd490148b68a04018a1de3c2262bc27134b6eaf792a1d991c9a775df64` |
| Forensic force-auth golden bundle | `0b8ff8ee9922e2cb52e3b082b4e703dd5975011ad0c1c626d22678afa867e54b` |

## Completed checks

| Check | Actual result |
|---|---|
| Clean-source Clojure qualification at `7b54cee8` | 55 tests, 264 assertions, zero failures/errors; two baseline test vars explicitly excluded as described below |
| Final Clojure follow-up at `4e5f04f0` | 15 command / execution-DAG tests, 74 assertions, zero failures/errors |
| Command registry / handler / wrapper parity | Valid |
| Canonical namespace resource count | Exactly one for each of the five package namespaces |
| Launcher from an unrelated directory | Exit 0; relative request path resolves correctly; JSON financial classification and replay assumption checked |
| Forensic Python regressions | 69 passed across golden bundle, golden force-auth, semantic dispatcher and reproduction protocol-state tests |
| Reproduction / output follow-up tests at `234d3d2f` | 10 passed from a clean archive, including four new execution/isolation cases |
| Deployment typecheck | Passed |
| Deployment CLI and successor-plan regressions | 39 passed, including full structural plan evaluation from an unrelated directory and preservation of its fresh-deployment blocker |
| Full forensic run through the new launcher | 14 scenarios passed; post-run bundle verification failed and exit 1 was propagated, as detailed below |
| Full forensic reproduction through the new launcher | 14 scenarios passed again; existing reproduction policy reported a core `bundle/hash` divergence and exit 1 was propagated |

The clean Clojure selection covers
`resolver-sim.commands.protocol-commands-test`,
`resolver-sim.allocation.source-bound-entitlement-test`,
`resolver-sim.allocation.proof-admission-test`,
`resolver-sim.allocation.cli-test`,
`resolver-sim.protocols.sew.financial.finality-test`,
`resolver-sim.protocols.sew.financial.finality-hardening-test`,
`resolver-sim.protocols.sew.dispute-ruling-finality-test`, and
`resolver-sim.evidence.deployment-bound-test`, with the two named exclusions.
All new command tests ran. Checks include context substitution, exact persisted
input/proof byte binding, signature rejection, retained-artifact mutation,
unavailable protocol semantics, large JSON integers and unchanged root strings.

The Python command was:

```sh
python3 -m pytest -q \
  test/forensic_python/test_golden_bundle.py \
  test/forensic_python/test_golden_force_auth.py \
  test/forensic_python/test_validate_dispatcher.py \
  test/forensic_python/test_reproduce_protocol_state.py
```

The deployment checks were:

```sh
npm run typecheck
npm test -- test/protocol-cli.test.ts test/successor-plan.test.ts
```

## Existing failures and release gates

The broader working-tree Clojure regression run had 56 tests / 265 assertions,
with four failures and one error. Both responsible test vars were independently
executed against the unchanged parent, reproducing the same four failures and
one error:

- `financial.finality-test/test-open-gates-exhaustive` uses stale state and
  pending-settlement field shapes and a stale yield-owner mapping. Its
  no-escrow expectation also differs from the current private gate helper.
  Correct its fixtures/expectations against the authoritative state contract
  before treating that suite as green; no economic rule was changed here.
- `proof-admission-test/issuer-derives-and-signs-only-verified-bundle-identity`
  reads an absent, untracked artifact at
  `results/allocation/a-vs-b-plus-c/realized-statement/sp1-proof-artifact.json`.
  Restore its appropriately qualified fixture or make the test self-contained.
  No replacement was substituted for that missing integration fixture.

These tests remain in the source and remain release gates. The clean passing
selection does not mean the complete regression suite passed.

An additional full `forensic run` was executed from an unrelated directory using
the clean source archive, the `yield-provider-scenarios` suite, a diagnostic
registry snapshot explicitly marked qualification-only, and output outside the
checkout. Preflight passed with a source-archive provenance warning. All 14
scenarios passed and the scenario runner exited 0. The existing Python runner's
mandatory post-run verification then failed for three reasons: an unresolved
DAG parent/root reference, no copied claim JSON files and no copied attestation
JSON files. Actual claim/attestation files are stored under nested `sha256/`
directories, while the legacy copy loop examines only direct children. The
workspace also reports failing forensic acceptance claims; copying files alone
would not establish release assurance. The new command propagated exit 1.

Retained diagnostic output is under
`/tmp/prf-forensic-command-qualification-wb3r94hm`. Its `run.stdout` SHA-256 is
`aaa31452200b9c5abda018df56f54ba81113586d54c4b722d3e9310d291b03a6`;
the produced `run-bundle-root.json` file SHA-256 is
`8d676f75a5ad89fc756ddbcd1eff76457229d8cc431194437a5395164363c829`.
These identify failed diagnostic artifacts, not RC bundles. The initial trial's
source-tree hash was
`09ad7932cc673db3d15c1473375036f3e97b3b4e8c4fae6c009a320c689caaef`,
covering only `src` and `protocols_src`. It does not cover the complete extracted
package dependency closure; the immutable source revision and dependency
manifest digests above must also be retained. Forensic packaging, DAG reference
compatibility, acceptance claims and source/dependency provenance remain release
gates. Verification of the committed signed golden bundles passed separately.

The new `forensic reproduce` interface was then exercised on that retained
diagnostic run. All 14 scenarios passed again, and the original source-tree hash
matched. Five core fields matched; `bundle/hash` differed, so the existing
comparison policy returned `diverged` and exit 1. All seven diagnostic fields
matched. Neither the comparison policy nor canonical identities were changed to
turn this into a passing reproduction. This first trial also exposed legacy
checkout-local artifact output. The follow-up revisions give each reproduction
its own `workspace/` beneath its new output directory, overriding any inherited
artifact root, and redirect auxiliary `results` / `runs` output for both run and
reproduce. A further full trial showed two Clojure writers ignored those legacy
root variables. The final follow-up passes their existing explicit execution
directory argument for forensic runs, keeping pre-run commitments and execution
DAGs under `workspace/execution/`; their legacy default arities are preserved.
The remaining checkout-local lock was moved to the explicit output namespace,
outside the per-run directory that may be hardened after success.
Reproduction also rejects a failing child process even if that process
writes a matching bundle. Focused tests exercise both successful and failing
executions and preserve an existing checkout artifact sentinel. Bundle identity
compatibility remains a reproduction qualification gate.

A fresh full run at final command source
`234d3d2f34940d8fcaea43c26b21b05c4b587ca4` passed all 14 scenarios and then
correctly returned exit 1 for the same three forensic packaging/DAG checks.
Its pre-run commitment and execution DAG were retained below
`workspace/execution/`, and a before/after check found no generated files in
checkout-local `results/`. Its diagnostic output is retained under
`/tmp/prf-forensic-qualified-cxa4fet5`. The outer `run.stdout` SHA-256 is
`bfc25fcb03978a77320aeb00cdd3a661cbe0e27d9d8e55e4f1bf0b444c618868`,
and the failed bundle file SHA-256 is
`a58506912a7c63ebf9cc46fa60ac4b5c8f2959cf8d456499652a81ebe24bedf6`.
Its existing `src` / `protocols_src` source-tree hash is
`188fa6ca515cfe71804edbd802240225a2dcc94c093adc500bdfc17c0fc6b828`.

The final same-source reproduction also passed all 14 scenarios, retained both
execution artifacts beneath its own workspace and left checkout-local results
unchanged. Its source pre-check matched. The existing comparison policy still
reported five core matches and one `bundle/hash` divergence, returning exit 1.
The retained final `reproduce-report.json` SHA-256 is
`a7c7ff2608a7755390ede051643a2086a2feec28e59840363b16f47bb0948c55`.
Thus output isolation and truthful failure propagation are checked; successful
end-to-end forensic acceptance and reproduction are not established.

Deployment integration must reconcile the retained bounded-configuration
overlay with the deployment owner's current changes before exposing its extra
approval/report/execution capabilities. The current committed successor plan
still has unimplemented execution prerequisites. Local-chain transaction
execution was not requalified by the new dispatcher tests.

Live chain finality observation, authentication of caller-supplied worlds,
broader admission profiles, independent SP1 verification and Solidity proof
qualification retain their existing gates. No RC bundle, tag, remote,
publication or cross-repository pin change was produced.

Optional improvements include additional protocol classifiers and direct JSON
request codecs. They are outside the current EDN snapshot and retained-evidence
command workload.
