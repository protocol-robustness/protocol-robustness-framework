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
content. The supplied development-only private keys remain outside the
repository under `/home/user/Code/challenge-001-keys` with mode `600`; the
previously requested `/secure/path/challenge` directory is absent in this
environment. The key fingerprints are distinct
(`sha256:f8f8f25db85a261697cd02b58300c933452ffb1e5659f5bdf3c3ab6749ddf907`
and
`sha256:7d31f496fdfa39a117e694fc1e71815b5d73ae5f7ea2474d6ab350504294a0e7`).
Both signatures were regenerated and verified against the exact approval root
with the PRF Ed25519 verifier. This confirms key possession only. The payload
remains proposed/inactive, and the bundle explicitly does not authorize
activation.
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

## Fresh local devnet bundle and HTTP smoke run (19 Nov 2026)

Using the available development reviewer public key, the current demo generator
produced a fresh empty-history bundle. Independent CLI replay succeeded. The
service started on `127.0.0.1:18788`; `/healthz` and `/v1/checkpoint` returned
the same configuration root, network root, empty-history root and action count.
The server was stopped after the checks. Bundle files, run status and checksums
are retained in
[`evidence/researcher-challenges/local-devnet-bundle-20261119`](../../evidence/researcher-challenges/local-devnet-bundle-20261119).
This establishes local devnet startup and checkpoint agreement only. The bundle
remains test-only, with funding, publication and activation authorization false.

## Local testnet-scope and ERC-20 execution demonstrations (18 Nov 2026)

The local bundle generator now accepts an optional `testnet` scope. A complete
offline signed-file run used temporary development keys for enrollment, claim,
submission, and a simulated reviewer acceptance. Fresh CLI replay succeeded and
the four-action history checkpoint verified. Public artifacts, SHA-256 file
manifest, replay report, and explicit unauthorized/simulated status are in
[`evidence/researcher-challenges/testnet-scope-rehearsal-20261118`](../../evidence/researcher-challenges/testnet-scope-rehearsal-20261118).
The resulting reward obligation root is
`sha256:3b7b1924387174b9e3a8a1e6cc15c5d39a64b169261dac353a90907a50a895b4`.
The loopback service started on the same artifacts; health and checkpoint
responses agreed on the configuration, network and four-action history roots.
It reported its runtime as `:local-devnet`. This exercises the offline protocol
under a `:testnet` namespace; it is not a public testnet service, chain
deployment, independent review, or authorization.

The complete local Anvil ERC-20 payout runner was also executed against the
existing devnet-scope obligation. Its retained report has `:valid? true`, a
matching 100-token transfer to the authorized pseudonymous beneficiary, and a
non-nil settlement sender. Public policy, consent, RPC context, transfer, report,
deployment and receipt artifacts plus checksums are in
[`evidence/researcher-challenges/local-anvil-payout-20261118`](../../evidence/researcher-challenges/local-anvil-payout-20261118).
That run used a synthetic backing commitment and a simulated accepted decision;
its Anvil instance has stopped. It establishes no real backing, public-chain
inclusion, testnet settlement, funding, publication, or activation.
