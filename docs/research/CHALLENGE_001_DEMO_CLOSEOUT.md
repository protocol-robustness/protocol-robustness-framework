# Challenge 001 demo closeout evidence

This records the local V0 demonstration only. It does not claim owner
authorization, a live challenge, real participants, funding, payment, or physical
power-loss qualification.

## Artifact identities

| Artifact | Identity |
| --- | --- |
| Scope candidate | `sha256:94a974fd5c2d30c2574cd82b6159b147812fe21788b07ed21f4212a9ac794f73` |
| Approval payload | `sha256:2832a447b01cc04301d082eb544315cfbd2e5a41bb4364065458c591f82aa89d` |
| Source content manifest (245 entries) | `sha256:b416f5cb715cada609dd5739fde0edd16e4250acd2cc65159ad32772d9d62010` |
| Detached devtest signatures | [`challenge-001-approval-signatures.devtest.v1.edn`](../../examples/researcher-challenges/shortfall-stress/challenge-001-approval-signatures.devtest.v1.edn) |
| Source code revision | `0b1c907e00c6982ebe7b5b557906effec1dbc498` |
| Candidate/evidence integration commit | `750032b80a5deea56ae98601570372c21fa350bc` |

The detached bundle contains only role-labeled signatures and public-key
content. The operator and reviewer keys were generated under `/tmp/challenge-001-demo-keys`;
the mode `600` temporary private keys were removed after signing. The public
key fingerprints are distinct
(`sha256:f09053c280b9d55a7e5730e06eddb766228e0ec19f565478b07338a781dde903`
and `sha256:e48267baa91bf7b1d4a36e68e2abe36e8fbed39d0f21267a24a859101363cbcc`).
Both signatures also verified independently with OpenSSL `pkeyutl -verify -rawin`
against the exact approval payload root. The focused suite also confirms swapped
roles, a tampered signature, and a stale root fail verification. The payload remains
proposed/inactive, and the bundle explicitly does not authorize activation.
The source manifest root does not include this signature bundle. All 245
manifest entry digests were checked against the merged checkout, pinned
dependency sources, and local Maven repository; there were zero mismatches.

## Reproducible validation

The project tests were run with Clojure CLI 1.12.5.1654 and the pinned git
dependencies declared by `deps.edn`. This runtime's default gitlibs cache is
read-only, so the exact pinned dependency checkouts in the local cache and a
temporary test-only AOT cache were used for the full fresh-JVM run:

For a later full rerun after resolving the pinned dependencies, use this single
command from the repository root:

```sh
CLASSPATH=src:resources:/tmp/seq72-aot-classes \
JAVA_TOOL_OPTIONS='-XX:TieredStopAtLevel=1' \
clojure -Sdeps '{:aliases {:test {:override-deps {prf/canonical {:local/root "/home/user/Code/.cache-prf-challenge001-deps/canonical/clj"} prf/pro-rata-allocation {:local/root "/home/user/Code/.cache-prf-challenge001-deps/pro-rata/clj"}}}}}' \
-M:test -m resolver-sim.research.challenge-001-demo-test-runner
```

The normal portable invocation, when the pinned git dependencies are available
through the writable Clojure gitlibs cache, is
`clojure -M:test -m resolver-sim.research.challenge-001-demo-test-runner`.

This runs the four focused unit-test namespaces and the fresh-process / durable
write-boundary checks one at a time, with progress lines for each subprocess
check. The complete run passed: 64 tests, 514 assertions, zero failures or
errors.

* `resolver-sim.research.shortfall-pilot-test` and
  `resolver-sim.research.researcher-challenges-test`: all passing. The
  Challenge 001 tests cover in-bound locked admission,
  out-of-bound unchanged-history rejection, candidate/result tampering,
  fresh replay, and the detached devtest signature bundle's root, role,
  key-distinction, tamper, and stale-root checks.
* `resolver-sim.research.shortfall-publication-test` and
  `resolver-sim.economics.with-bounty.obligation-settlement-test`: all passing.
* The four unit-test namespaces together: 59 tests, 429 assertions, all passing.
* Fresh-process
  `challenge-001-v2-profile-reconstructs-in-a-fresh-process`: 1 test, 2
  assertions, all passing.
* Fresh-process
  `separate-participant-operator-and-observer-processes`: 1 test, 17
  assertions, all passing. It uses synthetic researchers and confirms the
  public observer can verify the checkpoint and reconstruct three accepted
  contributions with reward obligations, without receiving private keys.
* `controlled-publication-trust-state-process-kill-boundaries`: 1 test, 21
  assertions, all passing.
* `arithmetic-gated-controlled-shortfall-admission-process-kill-boundaries`:
  1 test, 24 assertions, all passing.
* `controlled-admission-literal-process-kill-at-durable-write-boundaries`:
  1 test, 21 assertions, all passing.

On code/artifact candidate `750032b80a5deea56ae98601570372c21fa350bc`, `bb validate` passed all four phases with exit code 0: lint (0 errors, 112 warnings), notebook-support, all 55 notebook namespaces, and SPEDS (66 tests, 265 assertions, zero failures/errors). The persisted run report is `target/validation/20261117T134039.503Z-ded002f0-aca7-4374-aa60-7da208d01f1c/validation-report.edn`. The `jjq check --rev @-` fast gate passed on this code/artifact candidate (`BACKSTOP FAST PASSED`, `jjq: check passed`). A later documentation-only closeout commit, `a37ff542565d1c49b04c5a49574091e9aae6de9a`, was checked with the same configured `bb backstop:fast` command, which also passed: command registry valid, content-authority audit 2,166 governed files with zero errors (five files remain explicitly classified as mixed), formatting passed, lint 0 errors and 112 warnings. The `jjq check` wrapper itself did not complete on the documentation-only commit because of its temporary workspace lifecycle; the underlying configured fast gate passed directly on that exact tree. The checks used `DEPS_CLJ_TOOLS_VERSION=1.12.5.1654`, `DEPS_CLJ_TOOLS_DIR=/usr/local/lib/clojure/libexec`, `GITLIBS=/home/user/Code/.cache-prf-challenge001-deps/.gitlibs`, and `XDG_CONFIG_HOME=/tmp/symphony-jj-config` to match the installed toolchain and writable dependency cache. Docker CLI is installed but its daemon socket is unavailable; no Docker-backed host or power-loss qualification is claimed. These repository gates do not constitute independent review or authorize staging/publication.

The two pinned git dependencies used for this run were
`prf/canonical` at `5e2b520feca812b049ba397b7b3681963c66a48c` and
`prf/pro-rata-allocation` at
`cf38de1ea4ef03654ee3de8bead56c32ef078b10`.

## Demo limits

The researchers and operator/reviewer keys are synthetic development fixtures.
Separate local processes and stores exercise the offline handoff; they do not
establish distinct real people or separate physical hosts. The process-kill
tests qualify the declared file-write boundaries, not physical power loss.
Settlement tests use local evidence and do not prove an external transfer or
chain finality. Do not use this bundle to activate or fund a live challenge.
