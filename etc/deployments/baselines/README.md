# Bounded configuration release baseline

The v1 release baseline below is retained as historical evidence for its
original source parent. It no longer matches the current PRF source checkout.
The v3 current-source candidate remains preserved as historical review material.
The latest candidate uses
`bounded-configuration-release-sources.current.v3.json`, whose 930-entry lock
adds the Clojure dependency declarations and local accounting dependency source
to the deployment source closure. The lock itself remains outside its locked
path set. Its matching review package is in
`../bounded-configuration-local-candidate-v4/`. All candidates remain
unapproved; the v2/v3 packages and v1 baseline are preserved unchanged.

`bounded-configuration-release-baseline.v1.json` records its bounded-configuration
release-source bundle digest, prf-evm base and integrated revision, the relevant
protocol-deployments worktree overlay, pinned Solidity submodules, tool versions,
and the focused regression result. The source lock enumerates each included file; its bundle digest is SHA-256 over
sorted `path NUL file-digest LF` records. It also pins cross-repository source
patches and identities and is not a Git commit for the live PRF working copy.
The historical source lock contains 926 scoped release-source entries,
including PRF files, generic local candidate inputs and pinned cross-repository
patches. Its bundle digest is recorded in its baseline and candidate manifest.

The exact protocol-deployments patch is retained in
`protocol-deployments-worktree.patch`. It preserves the six existing edits and
adds the verifier runtime-closure implementation. Its base revision remains
`98659ce01e7e1d1bf1a3c6c84dc7ebb665ac5036`, and the resulting integrated
revision is `92608dce563f6f44dc9ab302c301abb1cf12b996`; do not apply the patch
over a newer revision without review. Typecheck and 60 targeted plan, stage
output and cross-plan tests passed. Full-suite baseline issues are recorded in
the work log and have not been reclassified as passing.

The deployment verifier-closure and genesis-seal-gate follow-up is retained separately as
`protocol-deployments-verifier-closure-followup.patch`, based on integrated
revision `92608dce563f6f44dc9ab302c301abb1cf12b996`; its current SHA-256 is
recorded in the source lock. The
patch retains verifier receipt/runtime closure, checks source-bound initial
approval before transactions, runs the independent live seal preflight before
signing, substitutes only its derived root for a zero sentinel, verifies the
receipt against same-block post-state, and records an executable deployment
prefix through successor-registry root derivation, the approved empty-registry
seal, configuration-executor deployment, C0→C1 derivation, separate
transition-approval verifier, then approval-gated successor verifier
registration and registry sealing. Registration recomputes the live
Solidity/PRF root, checks both governors, verifies every entry field and
retains successful receipts; sealing verifies the sealed readback. Policy-v3 authority validator code and cross-language attestation vectors are
available. The local entitlement profile deploys that validator from the
locally exported v0.4.1 artifact bundle, binds the approved policy root and
signer from authenticated PRF output, and enables transition mode before
genesis sealing with live pre/post checks. The v0.4.1 artifact bundle has now been reproduced from clean local PRF-EVM
release revision `02b7ea507e84054fecb422c5a5b56b02b40d0dcb`, parented by
`7804f42d8e6a4fde1e3a92f7087e4361b3266554`. After `forge clean`, the repository `npm run compile` script
completed with Solc 0.8.33 and the default profile and exported 31 artifacts. ABI, creation-
bytecode and runtime-bytecode hashes match the retained fixture for every
artifact; artifact-file digests and bundle root were refreshed. A second detached worktree at the same commit, with cleared Foundry outputs,
reproduced the exact same package root and all 31 artifact-file digests. The
package root is
`0042d0fee369cd0687ef520ac5a8deb7018a88291655de52bfd24e22d02733c6`. This
closes local W0 build reproducibility only; the commit is unsigned and
no owner approval, publication or deployment is claimed. The
transition-application executor now supports a PRF-verified authority request,
atomic authority transition, C1 configuration application, receipt readbacks
and stage-output evidence; later proof/capital stages remain open. No live
approved execution has been performed. TypeScript typecheck and 61 focused
resolver, approval and deployment-plan tests passed; 224 of 234 full-suite tests passed, with
10 Anvil-dependent failures because Anvil is unavailable. Twenty-two focused Clojure tests (141 assertions) pass; clj-kondo reports only
one existing unused-import warning in canonical.clj. No
owner-approved live deployment exists.

The prf-evm integration commit is `02dcc7a869b492e8444f25fb9169e9c8061e102a`,
parented by clean baseline `4b6b6ed159762c88099c96d4dafd635ab99470ea`.
It is an unsigned local commit. The portable source patch remains in
`../patches/prf-evm-configuration-hardening.patch` for independent recreation.
The 85 focused tests passed against this integrated commit with Solc 0.8.33.
Existing `lib` submodules were read from their pinned commits; the integration
changed no submodule content.

This lock records implementation provenance only. Its approval, observation,
genesis, transition and capital-flow fields explicitly remain incomplete.

The authority-transition follow-up patch is based on integrated prf-evm revision
`02dcc7a869b492e8444f25fb9169e9c8061e102a`; its SHA-256 is recorded in the
release lock. It adds the opt-in atomic authority transition path, Governor
entry points, the immutable policy-v3 EIP-191 ECDSA validator, and the v0.4.1
deployable allow-list needed to export the bounded successor artifacts. Four
focused Solidity suites pass (26 tests), including direct rejection of an
unauthorized transition caller with unchanged authority root, revision and
decision state. The shared PRF/Solidity fixture pins
policy root, transition commitment, signer address and signature. PRF also
recovers the EVM signer and verifies it against the separately trusted policy
key record. The source patch applies cleanly to its pinned base. The first
artifact-build attempt after patch import ended without a completion result and
did not establish reproducibility. The later two clean builds recorded above
resolved that build question: both produced the same v0.4.1 package root and
all 31 artifact identities. This closes local build reproducibility only;
owner-approved policy inputs, deployment and live transition are not claimed.

The source-bound approval follow-up introduces local review manifest v3 and
seal preflight v2. Its CLI bridge is included in the pinned protocol-deployments
follow-up patch. The preflight recomputes every file digest in the release
lock and checks the parent/tree identities against the operator-supplied trust
context before accepting an approval or planned seal call. Focused source-bound
approval, genesis-gate and source-lock tests pass (7 tests, 50 assertions).
The bounded protocol-deployments CLI consumes initial-input approval before
transactions and live observation preflight before signing the seal. It retains
the preflight and post-seal receipt/readback verifier outputs in the deployment
record. No owner key or approval is present, and these checks have not run on a
fresh deployment.

The local generic candidate package set and v3 review manifest are retained in
`../bounded-configuration-local-candidate-v2/`. The source lock includes the
four semantic package artifacts, combined package set and repeatable derivation;
the manifest digest is recorded separately because the manifest itself commits
to the source-tree root. All package statuses remain candidate-only. The local
authority policy is now explicitly v3 and proposes a validator address without
retaining its private key; its authority-state and policy roots were rederived.

The protocol-deployments follow-up requires source-bound PRF initial-input
approval before deployment transactions. At the `sealGenesis` bootstrap
boundary it runs the live PRF preflight, checks the exact sender, target and
runtime-hash arguments, rejects nonzero configured roots, installs only the
derived root, then independently verifies the receipt and block-pinned
post-seal readbacks. The source identity lock records its patch digest and
focused test evidence. This remains unapproved source/test evidence; it does
not supply owner approval or claim a live deployment.


The W6 capital-replay follow-up is retained separately as
`prf-evm-w6-capital-replay-followup.patch` against integrated revision
`02dcc7a869b492e8444f25fb9169e9c8061e102a`. It adds post-revert balance,
liability, claim-count and consumed-receipt assertions for duplicate
ratification and withdrawal, plus prevents one V1 admission receipt from being
prepared for two distinct capital operations. All three focused suites pass (29 tests total). This
strengthens fixture-level replay evidence but does not qualify a fresh approved
C1 deployment. Its digest and source entry are pinned in the release lock.


The latest protocol-deployments validation after the verifier-substitution regressions passed TypeScript typecheck and 240 tests across 34 non-Anvil Vitest files. The focused runtime-closure suite passed 4/4, including matching-metadata adapter runtime substitution, effective-verifier address substitution, and underlying verifier runtime substitution. The four RPC/Anvil-dependent suites remain outside this local validation result.

The W7 verifier follow-up adds optional capital-flow transaction and historical
state replay to `verify-plan-execution`. It requires the complete six-action
success path, including operation preparation, and nine negative cases. It
binds the evidence to the sealed genesis and applied C1
root/epoch, and checks each action against the fixed topology, deployment
manifest and pinned ABI. It checks transaction targets, selectors, receipt
statuses and canonical block hashes, plus required state-domain
calls on either side of each transaction and requires one transaction per block
to make rollback observations meaningful. The replay binds operation and receipt
identity through admission, assurance, ratification, activation and withdrawal;
duplicate admission must repeat the same call while leaving capital-bound state
unchanged. The independent report commits the evidence digest and transaction/state
replay counts. TypeScript typecheck and focused replay, report and error-code-coverage
tests pass. The retained patch
applies cleanly to the pinned integrated checkout. State-call arguments and
expected values still require qualification against the approved topology; no
real W6 bundle is present.

The W3/W7 verifier-closure replay follow-up independently reconstructs adapter
and verifier deployment facts from manifest transactions, checks historical and
current runtime against pinned artifacts, reads the effective verifier and
metadata at deployment and now, and compares the observations with retained
closure evidence. `verify-plan-execution` includes the rechecked closure in its
live-verification root instead of treating the deployment assertion as an
unverifiable producer claim. The closure, live-verification and report suites
pass (27 tests), and TypeScript typecheck passes. The refreshed patch applies
to integrated revision `92608dce563f6f44dc9ab302c301abb1cf12b996`; this is
implementation evidence, not a live approved deployment.

The source-bound approval verifiers reject trusted-key policies with missing or duplicate key identifiers, preventing list order from selecting the effective signer. The Ed25519 approval verifier and ECDSA authority-transition verifier have regression coverage for duplicate identifiers.

The cross-repository overlay was freshly reconstructed in `/tmp` from a clean
archive of protocol-deployments base `98659ce01e7e1d1bf1a3c6c84dc7ebb665ac5036`.
Both pinned patches applied in order and each passed `git apply --check`.
TypeScript typecheck passed, followed by 46 tests across the initial approval,
genesis seal, successor approval/configuration, registry derivation, verifier
runtime closure, capital-flow replay, report and evidence-replay suites. This
rechecks the overlay against its pinned base; it does not modify the separate
current protocol-deployments checkout or establish live deployment evidence.

A current-head overlay is also pinned at `protocol-deployments-current-head-overlay.patch`, based on committed revision `f3b5c7bbf3dbc3950ae26f275a37a41233b21698`. A disposable rebase/merge against the existing local chain-binding edits produced the same tree as the clean overlay (`666cad1e81742948d3b30a0085466dca0c51d534`). Typecheck passed; 46 focused tests passed; the full run passed 272/282 tests, with the 10 failures limited to Anvil startup being denied by this environment. This does not modify the current external checkout or qualify a live deployment.

The additive `protocol-deployments-capital-flow-qualification-followup.patch`
is based on the current-head overlay result tree `666cad1e81742948d3b30a0085466dca0c51d534`. It adds ABI-decoded receipt event/transfer cross-links and requires the independent verifier to load `protocol-capital-flow-expectations/v1` from a relative path inside the separately selected trusted-context root. The package binds expected state reads to manifest, sealed instance, applied C1/epoch and operation identity; its digest is folded into the verification root. The focused replay, trusted-expectation and report suites pass 41 tests and TypeScript typecheck passes. The qualification authority's approval of the expected values remains trusted, and no approved live execution is claimed.


## Reconstructed PRF-EVM hardening stack

The historical integration commit `02dcc7a869b492e8444f25fb9169e9c8061e102a` and release commit `02b7ea507e84054fecb422c5a5b56b02b40d0dcb` are not available as Git objects in the current mirror. A clean archive of pinned base `4b6b6ed159762c88099c96d4dafd635ab99470ea` plus the deterministically gzip-compressed overlay file pinned in `bounded-configuration-release-sources.v1.json` reconstructs the hardening, authority-transition and capital-replay stack. The compressed patch SHA-256, resulting source-tree identity and toolchain are pinned in the source/baseline JSON. Run `gzip -dc etc/deployments/baselines/prf-evm-bounded-combined-overlay.patch.gz | git -C <clean-prf-evm-base> apply` to apply it; this reproduces tree `98e2ced7c0c61f146f7da902ed027780cee0f8b9`.

The reconstructed stack passed the 129-test focused Solidity run with no failures; after the test-only W1 addition the updated authority suite passes all 10 tests, extending coverage to 130 across the two runs and `npm run compile` under Foundry 1.7.1 / Solc 0.8.33 before the final test-only W1 addition. The updated test tree compiles in Forge. Export produced 31 v0.4.1 artifacts whose ABI, creation-bytecode, runtime-bytecode and file-digest records match the retained expected manifest exactly. The exported bundle root itself is not claimed as reproduced: its provenance includes the unavailable historical commit identity. These results close source/build reproducibility for the content-addressed reconstruction, not owner approval or live deployment.

On 2026-10-19, the combined overlay also applied cleanly to a disposable copy of the current clean PRF-EVM checkout at parent `d8aee74e9652f44f68e345aff30090325df2ae54`. The seven focused suites passed (85 tests), and the authority-transition plus ECDSA-validator suites passed (14 tests), compiling with Solc 0.8.33. The shared checkout was left unchanged. This verifies overlay compatibility with that source revision; it does not constitute maintainer integration, a newly pinned output tree, or a release artifact identity.
