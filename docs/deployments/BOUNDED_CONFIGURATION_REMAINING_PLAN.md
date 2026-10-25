# Remaining bounded configuration work

The next milestone is a reproducible local deployment whose canonical inputs,
authority transitions, verifier implementation and capital effects are all
bound to authenticated approval and independently checked observations.
Passing fixture tests alone does not meet this milestone.

This plan starts after the implemented follow-up in
[configuration closure](BOUNDED_CONFIGURATION_CLOSURE.md). It does not approve
policy inputs or authorize a public deployment. The target remains local
Anvil, chain 31337, with C0 at epoch 0 and C1 at epoch 1. Public-chain evidence,
public governance approval and production readiness are separate milestones.

## Starting position

### Historical verified execution state (2026-10-19)

An earlier candidate's retained protocol-deployments receipt records verified
stages through C1 application on Anvil chain 31337. Its independent report is
explicitly `verification-evidence-only-not-approval`, has six replayed PRF
assertions, and remains `partial` with no capital-flow evidence. That live run
was local-only and did not qualify W6 or W7; it is not candidate v8 evidence.

### Authoritative refresh (2026-10-22)

Candidate v8 supersedes the v7 references in older handoff notes. Its initial
input approval verifies against the exact frozen source snapshot at parent
`e6fe09666adf8092b2023ea1ecdd84fbf24fa98a`, tree
`066ee1dcd021ecf8e4d182f17a4ded02095cf3fb51e600ddbe96006f9d87ef09`, with 934
locked files and manifest root
`sha256:44a5c842a98b59653a4d2ea4c3a7933785d7e8f9fa7919e575a44fbc9589dc9f`.
The separate successor-review approval also verifies for manifest root
`sha256:1d4a8db274a8f9c502c534be1225a90bdb5a8e3f3a4430bca4a180d89446932b`.
Both are scoped approvals for the exact frozen candidate; neither authorizes
the EIP-191 authority transition or live deployment by itself. The current PRF
working copy has since advanced beyond that source snapshot and inherits no
approval; verification used the independently reconstructed frozen tree and
selected trusted contexts.

The retained v8 W3/W4 report integrity re-verifies to root
`042507fd7c6acca3d69625c9d3527878d23e2fe21a11bb48b6e91dada24c0dfe`. It is
still `partial`, `local-anvil-only`, and
`verification-evidence-only-not-approval`: four PRF assertions replayed, zero
capital-flow transactions, and no capital-flow evidence root. It records the
C0 deployment, genesis seal and empty-registry seal, but no C0→C1 execution.
The policy-v3 EIP-191 request remains unsigned; the required validator address
is `0xc78884593859e84524e41ce94cce38fbcb58c5f8`. The previously supplied
signature recovers to `0x44fbdfadcfc329615ac56c4375cb058518acb5d0`; the known
`0xC0E…` keystore is also a different identity. Neither authenticates as that
validator. The local
RPC is currently unavailable and no Anvil process or candidate-specific state
file was found in the inspected locations, so these retained readbacks are
historical rather than current chain observations.

Current protocol-deployments checks pass: `npm test` reports 41 files and 314
tests passed, and `npm run typecheck` passes. These code checks do not change
the live-execution or approval gates. W5 awaits the correctly authenticated
authority-transition artifact and a matching live instance; W6 and W7 remain
open because no composed capital-flow evidence exists.

### Gate recheck (2026-10-23)

The v8 initial-input approval was reverified against its exact frozen source
identity, not the advanced current checkout. The verifier rejected the current
checkout because a locked source file has changed; reconstructing the 934
locked files from Jujutsu parent
`e6fe09666adf8092b2023ea1ecdd84fbf24fa98a` reproduced tree
`066ee1dcd021ecf8e4d182f17a4ded02095cf3fb51e600ddbe96006f9d87ef09`, and the
approval verifier returned `valid: true`, `authenticated-local-approval` for
manifest root
`sha256:44a5c842a98b59653a4d2ea4c3a7933785d7e8f9fa7919e575a44fbc9589dc9f`.
The separate successor-review approval also returned `valid: true` for root
`sha256:1d4a8db274a8f9c502c534be1225a90bdb5a8e3f3a4430bca4a180d89446932b`.
Neither approval was changed.

The most recently supplied EIP-191 signature was checked against the exact
v8 authority-transition request and trusted policy context. Verification
rejected it as `invalid-transition-attestation`; the approved validator
address remains `0xc78884593859e84524e41ce94cce38fbcb58c5f8`. No signature was
written into the request and no transaction was authorized. The previously
retained C0/C0-seal report remains historical: no Anvil process was running
and JSON-RPC at `127.0.0.1:8545` was unavailable during this recheck.

The current PRF checkout's focused authority-state, transition-review,
genesis-seal-gate, genesis-seal-observer, registry-equivalence and
successor-configuration suites pass: 27 tests, 189 assertions, zero failures
or errors. This is implementation evidence only. W0 maintainer integration
and historical artifact equivalence remain open; W5 still needs the valid
policy-pinned signature and a live, matching instance; W6/W7 still need a
fresh C1 capital-flow execution and independently replayable evidence.

The PRF-EVM review branch has a narrow change,
`57f7bc77` (`bounded-configuration-authority-hardening`), created on
the exact fetched `main`/`origin/main` parent `9f702a10446f7e045bab6f281f20b53f80f8b32d`.
It contains only the 13 bounded authority-transition, genesis-continuity,
admission-binding and related test paths; the unrelated partial-fill work was
excluded. The focused Forge suites pass 44 tests: authority state 9, ECDSA
validator 4, genesis 10, statement admission 19 and governor integration 2.
The branch is published for maintainer review, but is not integrated or
accepted as a release. Its full compile succeeded, but an export from that
main-based change still uses bundle version 0.4.0 and contains only 30
artifacts, omitting `PrfEcdsaAuthorityTransitionValidator`; it is not a release
bundle. The five pre-existing Gate E fixture files were restored to their
original working-copy state and were not included in the review change. The
single-commit branch was published for maintainer review as
`protocol-robustness/prf-evm:bounded-configuration-authority-hardening`
(`57f7bc77`), based on the current remote main `9f702a10`. It has not been
merged or accepted as a release. This candidate does not alter the v8 source
lock or inherit its approval.

An exact v0.4.2 source rebuild was also attempted from PRF-EVM commit
`0dad4c2790a60ecca9a41c5d518f99522eab94ce`, the source commit named by the
retained v0.4.2 manifest. The checked-out submodules matched its tree and
`npm run compile` succeeded. A fresh export contained all 31 expected
artifacts and reproduced every manifest field except `fileSha256`, but its
bundle root was `50f1502659e8e5bb3263946de88253621ed236632a75b2467810cfaa131cd0f5`,
not the retained root `1c040be8f3364d303dc66cabd0bb676994c836eb4021e9216862dc991a9c3bae`.
Twenty-eight artifact file hashes differ. A sample artifact has different
compiler-generated immutable-reference identifiers even though its ABI and
creation/runtime bytecode hashes match. The exported files are isolated under
`/tmp`; the retained release fixtures were not modified. This corrects the
earlier assumption that the v0.4.2 source commit was unavailable: the source
commit exists, but exact artifact-bundle equivalence remains unproven. The
historical v0.4.1 root is still unreproduced.

A follow-up clean-input check used Foundry 1.7.1 with `forge build --force` for
the full 178-file tree, then built the two SP1 artifacts and exported. It
produced root
`fb2cff8270976b1926a7b9c5cee5200b68bdbc95f06d6334529a47d4b261efce`; 13 of 31
artifact file hashes differed from the retained v0.4.2 package. Those
differences were only the numeric keys in `immutableReferences`. Every ABI,
creation/runtime bytecode hash, remaining manifest field, and immutable
offset/length layout matched. This establishes executable-artifact equivalence
for that observed Foundry 1.7.1 build, but does not reproduce the canonical
package root.

A further isolated rebuild used the exact v0.4.2 source commit
`0dad4c2790a60ecca9a41c5d518f99522eab94ce`, its three pinned submodule commits,
and the Foundry 1.5.1 binary recorded in that commit's README. The retained
manifest records EVM `osaka`, while the source `foundry.toml` leaves EVM
selection implicit; Foundry 1.5.1 defaults to `prague`. The default build
produced root `73bb636614927b4b8cb63f05afc4f667ec259843baf90e0984505499f479b673`.
Setting `FOUNDRY_EVM_VERSION=osaka` matched the retained manifest's declared
build settings but produced root
`207333e4ff8f4fc87fb55abcd23b2fc3b1225730df65b5be15bb4ecfd938956c`. All 31
ABI and manifest identity fields matched, but all 31 creation and runtime
bytecode hashes differed from the retained package. The build was performed
from an archive under `/tmp`; no source files, release fixtures or the
pre-existing PRF-EVM worktree fixture additions were changed. The published
source does not currently provide enough toolchain provenance to reproduce
the retained v0.4.2 bundle root. Do not substitute a different artifact root
or alter the pinned release package without a reviewed release decision.

| Work | Current evidence | Status |
| --- | --- | --- |
| W0 release baseline | Candidate v8 pins the 934-file source snapshot and v0.4.2 deployables used for the retained C0 run. Foundry 1.7.1 reproduces all ABI and bytecode hashes but 13 artifact-file hashes differ only in immutable-reference IDs (`fb2cff…` vs pinned `1c040b…`). The release README instead names Foundry 1.5.1; its exact source/submodule rebuild, even with EVM Osaka explicitly selected, differs in all 31 bytecode hashes (`207333…`). The source does not pin EVM version, and available provenance does not identify the exact artifact-producing toolchain. The narrow PRF-EVM hardening change (`57f7bc77`) is now published on a review branch and passes 44 focused tests, but exports an incomplete 30-artifact v0.4.0 package from main. Protocol-deployments typecheck passes and its current 41-file suite passes 314 tests. The historical v0.4.1 package root remains unreproduced. | Release package and source are pinned, but its canonical root is not independently reproducible; resolve toolchain provenance and maintainer integration before claiming W0 complete. |
| W1 authority enforcement | Transition enforcement and focused tests are retained. The fresh local demo readback records authority revision 1 with transitions enabled. | Implementation and local transition are verified; production authority remains out of scope. |
| W2 canonical input approval | The local-demo package retains separate initial-input and C0→C1 approval records and their trusted contexts. | Local-only approvals are present for this demo; they do not authorize public deployment. |
| W3 verifier identity | The 2026-10-25 live readback rechecked adapter metadata, effective verifier identity, and adapter/verifier runtime hashes against the retained deployment closure. | Verified at the observed local chain state; historical full deployment replay remains incomplete. |
| W4 genesis | The 2026-10-25 live readback confirms the sealed chain-instance root and sealed canonical successor registry. | Verified at the observed local chain state; historical full deployment replay remains incomplete. |
| W5 C0→C1 | The current local demo is at C1 `0xf8fa9013…c0349f71`, epoch 1; its authority revision is 1. The separate successor approval is retained. | Executed and read back on local Anvil only. |
| W6 capital flow | Corrected W6 setup evidence records successful setup/preparation transactions through block 147, reserve availability 70, and an on-chain/PRF v2 commitment match. The real proof attempt produced no proof; admission count is zero. | Open: recover or rederive and approve the exact proof inputs, then perform real proof admission, entitlement activation/claims, and independent reconcile. |
| W7 evidence package | A retained history bundle and replay utility reproduce all 147 block hashes and 370 signed transactions. Independent plan verification replays six PRF assertions and live deployment checks; report root `e9a489c6…948811c` is integrity-verified. | Open: W6 capital-flow transactions, trusted expectations, and historical state assertions are absent; the independent report remains partial. |

### Current local-demo refresh (2026-10-25)

The separate local-demo profile has a fresh chain readback at block 147. It
confirms chain ID 31337, the sealed instance root, C1 at epoch 1, authority
revision 1, verifier/runtime closure, and admission count zero. Its SHA-256 is
`131466dad91c69c85e9fdb7decaf411341efba762d34b8832d3d247fdf317821`.

W6 preparation was executed in blocks 142–147: the funding and ratification
bindings were configured, an entitlement operation was prepared against C1,
80 demo tokens were deposited, and 10 were reserved for an unrelated
obligation. The resulting available amount is 70. The supplemental
`etc/deployments/bounded-configuration-local-demo/w6-setup-evidence.v1.json`
has SHA-256
`eec2ea53a8c2c82317a37a70a21d433742c46347797427d7b087d97f5b8857dd`.
These transactions are setup evidence, not proof admission or completed
capital-flow evidence. The proof-attempt record preserves manifest and witness
digests, but the raw witness and its generation manifest are absent from the
demo bundle. The operation and reserve readback do not determine the supplied
reserve-binding root; recover it from its approved source or review and approve
a new local-demo derivation before regenerating the witness. Do not substitute
a guessed value. The CPU proof attempt stopped at the workstation's resource
limit without producing a proof; no statement was admitted and no claims or
reconciliation were executed. The full plan replay is also still unavailable
because the reloaded local node does not serve the earlier seal block. This
refresh supersedes the older statements above about the then-unavailable RPC
and unexecuted C0→C1 transition for the separate local-demo profile; it does
not change the frozen v8 source identity or its qualification limits.

A fresh RPC check of `economicState` and `currentCommitment` at block 147
returned `0x266c831f1d32e4871e1fdd2e26e529e98db2fb806c887898b0e9642048a5d0c3`.
PRF's `economic-state-commitment` recomputation returned the same value. The
earlier supplemental record had incorrectly stored
`0xb55531e705f51aed2f3c7d6d92fd71f11c3a74c7c748fa416036bce35175eb4f`; the
corrected artifact preserves that superseded value in its audit details.

This audit also found that PRF v2's Solidity observation projection omitted
`preparedOperationRoot`, which the Solidity `authoritativeObservation` includes.
The projection and cross-language fixture are corrected, and the focused
provenance/allocation-binding suites pass 6 tests and 26 assertions. For the
current C1 operation, PRF and the deployed contract agree on the predicted
block-148 observation root. This is candidate-input derivation evidence only;
it does not recover or approve the missing earlier proof input.

### Handoff recheck (2026-10-23)

The PRF-EVM hardening change is available on the published
`bounded-configuration-authority-hardening` review branch at `57f7bc77`; it
has not been merged or accepted as a release. The public repository currently
has no release for the retained v0.4.2 bundle, and the source commit named by
that bundle (`0dad4c2790a60ecca9a41c5d518f99522eab94ce`) is not present in its
public commit history. Consequently, the exact artifact-producing toolchain
and canonical bundle root still need maintainer resolution. No further source
change can establish that missing provenance locally.

The deployment transition request remains unsigned by the policy-pinned EIP-191
validator, and there is no matching live Anvil instance. Do not execute W5 or
start a replacement instance: either would require the external signature and
instance-bound evidence first. W6 and W7 depend on W5 and have no live
capital-flow evidence. The authenticated initial-input and successor-review
approvals remain valid only for their frozen v8 manifest and scope.

This follow-up is documentation-only: it corrects the branch publication
status and records the remaining external gates without changing approved
candidate inputs or source locks.

The prior deployment manifest root is
`0x9e41374c74e541b8d2a32536ad1f2f0768aa8116659096a9f810516ec74d171c`,
execution root
`0x20234a8f341f82e40c750f3dc55d8d9146718171fd4d50eab53518dd5af117c4`, and
independent report root
`5fdc9108e06fcd56aaf9a74eff5a071d93ffa9f7d5b4368026dcbd61bc06bc6b`.
These identifiers belong to the earlier candidate's local run. They are local
evidence only and must be rechecked against retained inputs before any later
transaction. Candidate v6 changes the source and deployable bundle identity;
it has not been deployed or approved. No existing transaction authorization
is inferred from its predecessor's receipts.

| Area | Available now | Remaining obligation |
| --- | --- | --- |
| Canonical authority | Generic state v1, local policy v1/v2/v3, atomic transition enforcement, Ed25519 and EIP-191 validator paths, shared commitment vectors; the bounded profile deploys the immutable EIP-191 validator and enables transition mode before sealing | Execute and independently verify the approved authority-state transition; qualify validator key/runtime against a current live instance |
| Initial inputs | Four generic candidate artifacts derive under local policy v3; the v8 source-bound review manifest and seven-root C0 artifact have authenticated approval | Keep approval bound to the frozen source identity; policy-v3 transition signature remains separate |
| Review manifest | Generic v3 content/root, scope, principal, action and release-source bundle bindings; PRF verifier plus deployment gates for initial inputs, genesis sealing and separate C0→C1 approval | Initial-input and successor-review approvals verify; supply the policy-pinned EIP-191 authority-transition attestation |
| Solidity hardening | Pinned base `4b6b6ed159762c88099c96d4dafd635ab99470ea` plus the source-locked combined overlay reproduces tree `98e2ced7c0c61f146f7da902ed027780cee0f8b9`; the 129-test focused run and updated 10-test authority suite pass (130 cases across runs); the production Solidity source compiled and all 31 artifact records match the retained v0.4.1 fixture | The historical bundle root is not reproduced because its release commit object is absent from this mirror; qualify the trusted validator implementation, bind it to approved inputs and retain deployment/runtime evidence |
| Registry equivalence | Shared fixtures pass in Clojure and Solidity | Retain fixture/release identities with the approved successor package |
| Proof and replay | Real-proof and consumer/assurance regression tests pass | Repeat the composed capital flow under the newly approved deployed C1 |
| Deployment qualification | Independent chain-instance preflight/result gate, pinned observer, signer-bound genesis and empty-registry seals, successor-registry equivalence, C0→C1 derivation, separate transition-approval verifier, and policy-v3 validator enablement are integrated into the resumable partial prefix | Execute approved successor registry/governance changes and later proof/capital stages; then run with real approvals and retain live receipts/readbacks |

Earlier local validation recorded integrated PRF-EVM revision
`02dcc7a869b492e8444f25fb9169e9c8061e102a` and release revision
`02b7ea507e84054fecb422c5a5b56b02b40d0dcb`, but those Git objects are absent
from the current mirror. The requested `prf-evm-config-bounded-current` ref
was also unavailable. The current reproducible baseline therefore uses clean
archive base `4b6b6ed159762c88099c96d4dafd635ab99470ea` plus the combined
content-addressed overlay retained in the source lock. The reconstructed tree
is `98e2ced7c0c61f146f7da902ed027780cee0f8b9`; the 129-test focused run passed before the test-only addition; the updated
authority suite passes 10/10, extending coverage to 130 cases across runs.
`npm run compile` passed before the test-only W1 addition on the same production Solidity sources; the updated test tree compiled during the Forge run. All 31 ABI,
creation-bytecode, runtime-bytecode and artifact-file records match the
retained v0.4.1 manifest. The package root
`0042d0fee369cd0687ef520ac5a8deb7018a88291655de52bfd24e22d02733c6` is not
claimed as reproduced because its provenance includes the unavailable
historical commit. The source lock pins the patch and submodules needed for
compiler remappings. This establishes reproducible local source and artifact
identities, not owner approval, publication or deployment. See the
[release baseline evidence](../../etc/deployments/baselines/README.md).

The protocol-deployments worktree is also integrated in a pinned local revision,
`92608dce563f6f44dc9ab302c301abb1cf12b996`, based on
`98659ce01e7e1d1bf1a3c6c84dc7ebb665ac5036` plus its preserved worktree edits.
Its bounded plan now pins the adapter's expected verifier metadata. When
authenticating selected stage-output dependencies, execution checks both
deployment identities, adapter readbacks at deployment and current state, and
the underlying verifier's historical and current runtime against its artifact.
A follow-up adds the same closure check to the fresh deploy path before the
deployment record is finalized, retained as a separate patch against the pinned
integration. The deployment plan executor now runs the dependency-safe prefix
through authority/verifier deployment, genesis deployment and independently
gated genesis sealing, derives the successor registry root from the observed
adapter and checks it against the registry contract's pure Solidity projection
at a pinned block, seals the approved empty registry after checking the PRF
root against Solidity, matching signer/governor identities and zero-entry
state, then deploys the configuration executor. It independently derives
C0→C1 from the approved C0, sealed instance artifact and successor registry
root. A resumed run can verify a distinct transition approval against the exact
instance, parent/new roots, epoch and proof mode; that evidence is retained in
the partial receipt. With that approval and a separate PRF-verified signed
authority-transition request, the executor can register/seal the successor
verifier, apply the authority-state transition and apply C1. It checks exact
call arguments before signing and verifies receipts and on-chain readbacks;
the plan receipt reports these stages only from retained successful evidence.
As of 2026-10-22, candidate v8's initial-input and successor-review approvals
verify, but its policy-pinned EIP-191 authority-transition request remains
unsigned. W5 is open; no C1 transition or proof-stage consumption is claimed.

The PRF review tooling now supports a detached Ed25519 approval envelope with
an out-of-band trust policy. It binds the exact manifest root, local scope,
principal and action, and rejects altered bindings. Its verifier is covered by key-generated tests. A source-bound manifest v3 is
required by genesis seal preflight; preflight rehashes every source-lock entry
against a separately pinned release-source bundle and parent. The bounded
protocol-deployments CLI now consumes the signed initial-input approval before
sending any deployment transaction and retains hashes of all four approval
inputs plus the verifier output in the deployment record. It extracts the four
approved constructor roots from the verifier result and replaces only the
known placeholder bindings in the bounded architecture before spec resolution;
non-placeholder values must already equal the approved roots. Plan resolution
also requires this authenticated root set. Immediately before signing
`sealGenesis`, the bounded signer consumes the independent live-observation
preflight, and it retains the request/context digests and verifier output. The
v8 initial-input approval and seal readback are retained for the frozen
candidate and historical Anvil instance; they do not transfer to later source
or instance changes. Current RPC readback is unavailable. The signer requires the
configuration's root argument to be the all-zero sentinel, substitutes only
the independently derived root, and verifies the mined receipt and same-block
readbacks. Both verifier outputs and their exact input digests are retained in
the deployment record. The retained v8 seal is historical; a fresh live seal is
not currently observed.

A deployment-specific four-artifact candidate now lives in
[bounded-configuration-local-candidate-v2](../../etc/deployments/bounded-configuration-local-candidate-v2/README.md).
It derives generic `authority-state.v1` and `local-authority-policy.v2`, preserves
an empty authority state at revision zero, derives a seven-root C0 with the
empty verifier registry, and retains a source-bound v3 review manifest. Its
validator key and local governor remain proposed candidate inputs. The package
is explicitly unapproved; no validator private key or implementation is
retained, and no transition can be authorized from it yet.

The separate generic review path now derives a four-artifact candidate set
using `authority-state.v1` and `local-authority-policy.v2`; it has a distinct
manifest schema and will not reinterpret the legacy demo package. The review
CLI can emit v1/v2/v3 manifests; v3 binds the exact source-tree SHA-256 and
verification rehashes that tree against separately supplied trusted context.
Invalid approval verification exits nonzero. Test vectors use
a marked test key only; no deployment package or trusted owner key has been
substituted for them.

An independent PRF `genesis-seal-gate` now builds the chain-instance artifact
from generic authority-state.v1/local-policy.v2 inputs and separately collected
deployment facts. It requires a source-bound v3 manifest, rehashes every file
in the trusted release lock, and checks the pinned source parent/tree root. Its
preflight then requires authenticated initial-input approval, canonical code hashes,
an unsealed genesis, matching constructor/readback values, two ordered block-
pinned observations with no intervening state change, and exact decoded seal
target/arguments. It compares both live runtime hashes with independently
configured artifact identities. A separate result verifier also binds the mined transaction,
receipt, same-block post-seal readbacks, stored root and cleared sealer to that
preflight evidence. The preflight emits reproducible evidence without
submitting a transaction. Wiring both checks into the live
protocol-deployments signer and collecting a real receipt/readback remain
outstanding.
The independent command is
`clojure -M:test -m scripts.deployment.genesis-seal-gate <preflight-request.edn> <trusted-context.edn> <rpc-url> <genesis-address> <authority-adapter-address>`.
For manifest v3, the separate trusted context includes `:source-revision`,
`:source-tree-sha256`, `:source-root`, and `:source-lock-path` alongside the
principal, trust policy, action and runtime artifact identities. The command
collects two latest-block observations directly from JSON-RPC. This context is
operator-controlled and not part of the request bundle. For offline reproduction, omit the three RPC arguments and retain both
observations in the request file.
After sealing, a fresh verifier invocation rechecks the retained observation
blocks and reads the transaction, receipt and post-state directly:
`clojure -M:test -m scripts.deployment.genesis-seal-gate verify-seal-result <preflight-request.edn> <trusted-context.edn> <rpc-url> <transaction-hash> <genesis-address> <authority-adapter-address>`.

## Work packages

### W0 — Establish the integrated baseline
The historical PRF-EVM integration/release Git objects are unavailable in the current mirror. The pinned base plus `etc/deployments/baselines/prf-evm-bounded-combined-overlay.patch.gz` now provides the reproducible source baseline: its resulting tree is `98e2ced7c0c61f146f7da902ed027780cee0f8b9`, the prior 129-test focused Solidity run passed; after the W1 test-only addition, the authority suite passes 10/10, extending the covered union to 130 cases, and `npm run compile` exports 31 artifact records matching the retained v0.4.1 manifest. The original package root is not claimed because archive provenance differs.

On 2026-10-18, an independent scratch reconstruction reapplied the pinned
overlay to the pinned base and reproduced tree
`98e2ced7c0c61f146f7da902ed027780cee0f8b9`. With the pinned submodule commits
checked out, the seven focused Forge suites passed again (85 tests), followed
by the authority-transition and ECDSA-validator suites (14 tests). This
revalidates the source overlay and those contract behaviors in the current
environment; it does not reproduce the historical artifact-bundle root or
provide owner approval, live deployment evidence, or W4–W7 qualification.

On 2026-10-19, the overlay was also applied in a disposable source copy of the
clean PRF-EVM checkout at parent `d8aee74e9652f44f68e345aff30090325df2ae54`
(working-copy parent of the empty `4b8441ae` change). It applied without
conflicts. The seven focused suites passed again (85 tests), and the two
authority-transition/ECDSA suites passed (14 tests), using Solc 0.8.33. This
shows compatibility with the current shared checkout's source; the checkout
itself was not modified and the release baseline is still an overlay pending
maintainer integration and source/artifact pinning.

The exact PRF-EVM base tree is `181cb24a13dd634bca95dad8981b49e61e983393`;
applying the pinned overlay (`de072d677eff58f01a3fdef22d17c2f73ae549c848ee12b2b0f22cc77e1cc6f9`)
produces Git tree `b377fbc44495ec05a27fe584078ebbd5fe8c9233`. This tree identity
was independently materialized in a temporary shared-object clone. Its 22
changed paths and test results identify a reproducible compatibility build,
not an accepted release revision.

That exact tree also passes `npm run compile` and `npm run deployables:export`
under Foundry 1.7.1 / Solc 0.8.33. Export validates 31 artifacts and reports
bundle root `10cc6f300a2f8d9b25a18d353b34eaa8b2cc7e58f2b1ccb53a7034612b11911f`.
An independent second temporary clone reproduced the same tree and bundle
root from fresh Foundry outputs.
The bundle records source commit `d8aee74e9652f44f68e345aff30090325df2ae54`
as dirty because the overlay is applied in the temporary clone. This is a
reproducible compatibility build, not a clean release identity or an artifact
equivalence claim against the unavailable historical bundle.

On 2026-10-19, the PRF-side registry-equivalence, source-lock, genesis-seal
preflight, successor-configuration, and transition-approval review suites
passed 16 tests and 93 assertions. These confirm the derivation and gate
checks against retained fixtures; they do not satisfy live deployment
acceptance.


Owner: prf-evm and PRF maintainers. Priority: P0. Dependencies: none.

- Locate the requested baseline or explicitly select a replacement revision.
  Compare its contracts and deployment scripts with the tested patch baseline.
- Integrate the [Solidity patch](../../etc/deployments/patches/README.md) in a
  writable prf-evm checkout, resolve any differences and rerun the seven focused
  suites and shared PRF fixture tests.
- Record exact PRF, prf-evm and protocol-deployments revisions, compiler/toolchain
  identities and artifact digests. Include uncommitted source changes in a
  content-addressed source bundle, or freeze clean revisions before approval.

Acceptance: integrated source and build artifacts are reproducible and pinned;
the original read-only checkout or a temporary test tree is not mistaken for
the accepted release. Baseline equivalence is resolved explicitly.

The protocol-deployments source overlay was reconstructed from a clean archive
of pinned base `98659ce01e7e1d1bf1a3c6c84dc7ebb665ac5036` in a temporary review
tree. Both retained patches applied in order and passed `git apply --check`;
typecheck and 46 focused tests passed across approval, genesis sealing,
successor transition, verifier identity, capital-flow replay and evidence
reporting. This confirms patch-stack reproducibility at the pinned base. The
separate current protocol-deployments checkout was not modified, and this
validation does not provide owner approval or live execution evidence.

The overlay was then rebased onto the current committed
protocol-deployments revision `f3b5c7bbf3dbc3950ae26f275a37a41233b21698` in
disposable clones. Git three-way application preserved the checkout's local
chain-binding changes; after deduplicating an overlapping test fixture, the
merged tree matched the clean rebased overlay at
`666cad1e81742948d3b30a0085466dca0c51d534`. Typecheck passed and 46 focused
tests passed. The complete 39-file test run passed 272 tests; 10 Anvil-dependent
tests could not start because this environment denied Anvil socket startup. The
rebased patch is pinned separately in the baseline record. It is an unsigned
local source overlay, not a deployed or owner-approved release.

### W1 — Close authority semantics and the execution path

Owner: PRF for semantics; prf-evm for enforcement. Priority: P0.
Dependencies: W0 for release integration; semantic review can start immediately.

- Review the additive `authority-state.v1` and `local-authority-policy.v1`
  contracts. Confirm that decision authorization, revocation and proof-mode
  assignment belong in the committed state. Validate decision preimages using
  the canonical configuration-transition contract, not just root syntax.
- Specify how a permitted transition becomes executable. The design must bind
  old/new authority roots, mutation payload, policy, predecessor/revision,
  target adapter and deployment instance, and prevent replay.
- Select and document the canonical-validation mechanism before implementation:
  on-chain recomputation, verified canonical witness, or an explicitly trusted
  validator under the approved local policy. A validator attestation must be
  described as a trust dependency, not on-chain canonical recomputation.
- Implement one atomic path for covered mutations and state-root updates.
  Disable or restrict every legacy administrative route that could bypass it.
  A wrapper is insufficient while the governor retains a direct bypass.
- Make policy/governor replacement semantics executable. Either implement the
  declared capability safely or prohibit unsupported changes in the applicable
  local policy and transition validator.
- Preserve the immutable initial authority root in genesis. Initial state has
  no decisions; later state descends from it after instance sealing.

Acceptance: approved transition succeeds atomically; unauthorized callers,
arbitrary new roots, wrong preimages, stale predecessors, skipped revisions,
revoked decisions, proof-mode replacement, replay and direct bypasses fail.
A failed transition leaves both the committed root and effective decision
state unchanged. Legitimate later updates preserve genesis identity.

The PRF off-chain semantics include `local-authority-policy.v2`, which
commits an Ed25519 validator key, and `local-authority-policy.v3`, which
explicitly versions an EVM-recoverable secp256k1 validator address. Existing
v1/v2 identities retain their original projections. Both policies use
`authority-state-transition.v1`,
which binds previous/successor state and policy roots, revisions, adapter,
instance, derived mutations and canonical configuration-transition preimages.
The EVM transition path is now implemented in the pinned follow-up patch
`prf-evm-authority-transition-followup.patch`. Its regression suite now covers a complete authorized revocation, confirms revoked decisions and their proof mode are no longer effective, and rejects direct legacy proof-mode/revoke calls after transition opt-in; all 10 authority-transition tests pass. The adapter opts into transition
mode before genesis sealing, rejects opt-in after any legacy mutation, and then
atomically validates and applies a sorted mutation list before advancing the
authority-state root and revision. Direct legacy administrative routes are
blocked after opt-in. The Governor exposes governance-only entry points, and
an integration test executes both enablement and transition through passed
Governor proposals. Invalid attestation, wrong predecessor, skipped revision,
unordered mutations, direct legacy bypasses and a non-governor transition call
revert without changing the committed root, revision or decision state. The
authority-transition follow-up patch now pins this unauthorized-caller
regression as well.

The PRF side derives the EVM ABI mutation root and execution commitment and
independently checks the transition envelope. For policy v3, it also recovers
and checks an EIP-191 secp256k1 signer against the policy and separately trusted
key record. `PrfEcdsaAuthorityTransitionValidator` now provides an immutable
on-chain policy-root/signer binding and signature check. It authenticates the
attestation only; it does not recompute semantic state or decision preimages,
so the PRF verifier remains a required trust dependency. A shared test-only
signature vector passes in Clojure and Solidity. Runtime identity, approved
validator-key custody, artifact publication, and deployment-time binding of
this validator into the adapter remain open. The candidate package has been
versioned to policy v3 but remains unapproved and has no corresponding private
key.
Policy/governor replacement remains prohibited in transition v1.

### W2 — Produce the local approval package and authentication gate

Owner: PRF and protocol-deployments implement; designated input owner approves.
Priority: P0. Dependencies: W1 semantics; W0 for frozen source identities.

- Construct deployment-specific generic authority state and local policy
  artifacts. Do not reuse test fixtures as approved inputs. Preserve the old
  demo schema and roots as historical candidates; explicitly derive new roots
  where adoption changes identity.
- Review protocol-genesis component material for consistency with the adopted
  semantics. Recompute affected component and aggregate roots.
- Prepare the exact C0 package, including approval of all seven constituent
  roots and their semantic material. The verifier registry is empty at C0.
- Extend or version the current review-manifest/package-set tooling for the
  adopted generic authority artifacts. Its existing demo-only derivation path
  must not silently reinterpret the older authority package. Manifest v3 now
  binds source parent and tree digest; the seal preflight checks those against
  current file bytes and an out-of-band expected identity. Manifest v3 now
  binds the source-tree SHA-256 as well as the source parent; the seal preflight
  rehashes each listed file against an independently pinned release lock.
- Add an approval evidence envelope that binds the manifest commitment,
  approving principal, local scope and approved action. Verify it through an
  authenticated mechanism against an independently configured trusted
  authority. A principal named inside the submitted manifest cannot establish
  its own trust.
- Make the deployment executor reject missing, invalid, substituted or
  out-of-scope approval evidence. The bounded protocol-deployments CLI now
  consumes the v3 PRF approval verifier before sending any deployment
  transaction and records the verifier result with exact input digests. The
  `sign-initial-inputs` command validates manifest content, source-lock identity,
  the fixed action, and active trusted-key-to-principal binding before writing
  a detached approval envelope; the private key remains an external input, and
  an existing output file is never overwritten. Approval verification rejects
  trusted-key policies with missing or duplicate key identifiers, preventing
  signer selection from depending on entry order; conflicting-key regressions
  cover both orders. Live observation and
  seal-preflight evidence still need to be consumed at the seal stage.

Acceptance: owner can review complete artifacts before approving. Approval of
one exact manifest cannot authorize altered policies, another source bundle,
another principal, a public chain or an unrelated deployment action. Candidate
status, content validity and authenticated approval remain distinct facts.

### W3 — Authenticate verifier implementation provenance

Owner: prf-evm supplies artifacts; protocol-deployments observes; designated
admission authority evaluates. Priority: P1. Dependencies: W0; informs W4/W5.

- Pin the program VKey, ELF hash, statement/public-values schemas and adapter
  build artifacts to one selected proof profile. Reconcile differences among
  existing demo profiles and fixture identities before using them together.
- Derive expected deployed runtime identities with immutable constructor values
  and library links accounted for. Follow any underlying verifier, proxy,
  gateway or implementation-selection path actually used during verification.
- Implement independent code/readback checks against those artifacts. Define
  how an upgrade is prohibited or requalified, and when identities are rechecked
  before a proof can create usable authorization.
- Bind statement admission's configuration executor to the same sealed chain
  instance at construction and recheck the binding immediately before proof
  verification; mismatched-instance deployments fail closed.
- Implemented in the pinned protocol-deployments integration for the local
  entitlement verifier: adapter getters and effective `sp1Verifier()` are read
  at deployment and current state, and the verifier runtime is checked against
  its selected artifact. A follow-up now applies that check to fresh
  adapter/verifier deployments before finalizing the deployment record,
  rebuilding their facts from successful receipts and block identities.
  Focused closure and runtime-substitution tests and typecheck pass. The
  standalone plan verifier now replays
  retained closure evidence against the pinned plan, manifest deployment
  receipts, historical/current adapter and verifier runtimes, effective
  `sp1Verifier()` readbacks and immutable metadata. It includes the replayed
  closure assertion in the independent live-verification root. Substituted
  retained closure evidence fails closed. The executor verifies this closure
  before deriving the successor registry root; the approved C0→C1 transition
  binds that registry before admission contracts are deployed. The selected
  adapter's verifier pointer and proof metadata are immutable. This makes the
  verifier check a prerequisite to the on-chain admission configuration.
  Remaining W3/W6 qualification is to bind the profile to the approved package
  and exercise real proof admission under the resulting C1 deployment.
- Keep registry v1 encoding unchanged. If on-chain runtime enforcement requires
  new commitments, introduce an explicit versioned contract/schema decision.

Acceptance: a substituted adapter that reports matching metadata is rejected;
so are a wrong underlying verifier and an unapproved implementation change.
The evidence identifies the effective verifier, not just its facade address.

### W4 — Enforce canonical derivation and observation before sealing

Owner: protocol-deployments; PRF remains the canonical producer. Priority: P0.
Dependencies: W0–W3 for the qualified run; implementation can use unapproved
fixtures earlier without claiming qualification.

- Deploy the authority adapter and unsealed genesis from approved initial
  inputs. Observe chain ID, final addresses, constructor bindings, initial
  authority state and runtime identities.
- Build the complete canonical instance artifact from those observations.
  Recompute it in an independent verification invocation against the approved
  inputs and separately collected chain readbacks. Comparing two copies of
  the producer's manifest is insufficient.
- Gate sealing on equality of approved inputs, observed facts, independently
  computed root and planned transaction arguments. The bounded signer now
  rejects any configured genesis root except the all-zero sentinel, then uses
  the root returned by the live independent preflight. An arbitrary-root
  override cannot reach the signer.
- Reobserve immediately before sealing and read back the sealed result. Pin
  observation block identity, transaction receipt, canonical artifact and
  derivation/tool version so another verifier can reproduce the comparison.
- Treat interruption, nonce/address drift or changed state as a reason to
  recompute and revalidate. Never reuse a stale seal authorization blindly.

Acceptance: changed input, address, chain, authority state, runtime code or
planned non-root argument stops sealing; the genesis root is installed from
independent recomputation. Successful result verification matches the sealed
root and initial bindings. Restart/retry behavior cannot bypass checks. The
signer gates and executable prefix through the seal stage are implemented.
This acceptance remains open until the prefix runs with approved inputs and a
real local seal is independently observed.
The current prf-evm working tree now rechecks the initial authority-state root
immediately before genesis sealing and rejects drift after construction. It
also binds statement admission to the same chain-instance root at construction
and again before every proof verification. `PrfGenesisTest` passes (10 tests),
including the new drift regression; `PrfStatementAdmissionTest` passes (19
tests), including both instance-binding regressions. This implementation is a
prospective source change: it is not in the frozen source lock or the already
deployed admission runtime, so a deployment using it requires a rebuilt,
re-identified candidate and renewed approval. These tests do not replace the
independently derived live seal preflight and receipt/readback required for W4.

### W5 — Derive and execute the approved C0 → C1 transition

Owner: PRF derives; protocol-deployments executes; authority approves.
Priority: P1. Dependencies: W1–W4.

- Bind the executor and epoch-0 empty registry to the sealed instance.
- Observe the deployed successor verifier, authenticate it through W3 and
  derive its registry artifact using the shared encoding fixtures.
- Derive C1 from C0 by changing only `verifier-registry/root`. Derive the
  transition from the exact instance target, parent/new configuration roots,
  parent/new registry roots, epoch 1 and applicable proof-mode authorization.
- Obtain the scoped transition approval after final deployed identities are
  known. Initial-input approval alone does not approve an address-dependent
  registry or a later decision. Alternatively use a separately approved,
  machine-checkable policy that explicitly authorizes those exact derivations.
- Execute through W1's validated authority path and the configuration executor.
  Independently read back C1, epoch 1, registry sealing, decision consumption
  and the authority successor state. Only after those checks pass, bind the
  admission constructor snapshot to the observed C1 root and epoch 1, then
  deploy admission and its dependent protected-operation and entitlement
  contracts. The CLI now defers those deployments until after C1 application
  and fails closed if the retained application evidence is absent. This
  ordering is implemented and covered by plan tests; execution still requires
  approved inputs and live readbacks.

Acceptance: C0 and C1 are distinct and reproducible; only the permitted
component changes. Wrong parent, epoch, instance, registry, proof mode,
unauthorized caller or reused decision fails. Genesis identity remains fixed.

PRF now has `scripts.deployment.successor-configuration`, a derivation-only
tool that checks a closed C0 artifact, a local chain-instance genesis bound to
the same protocol root and C0 root, and the canonical empty registry. It
recomputes the instance root, optionally checks an independently retained
  expected root, changes only `verifier-registry/root`, and derives the v1
configuration-transition root for that exact instance at epoch 1. The output
retains its chain-instance artifact so a fresh invocation can recompute and
compare the complete derivation record. Fixture-level derivations are tested;
the resumable deployment invokes this tool against the authenticated C0, sealed
instance and observed successor registry. Candidate v8's C0 and successor
verifier package have separate authenticated review approvals. The policy-v3
EIP-191 authority-transition signature, on-chain execution, authority
successor update and current live readback remain absent. The
configuration-transition schema commits the new registry root; the old
registry root is committed through C0 itself.

The separate `scripts.deployment.successor-transition-review` path now builds
a candidate manifest over the recomputable derivation and exact successor
registry entries, with local chain ID, chain-instance root, C0/C1 roots,
epoch 1, successor proof mode, source revision and an explicit approver
principal. Its detached Ed25519
approval uses a distinct domain, trust role and fixed
`approve-successor-transition` action; the verifier requires the trusted
principal, source revision and scope from an external context. Thus an
initial-input approval cannot be replayed as transition approval. Commands:
`clojure -M:test -m scripts.deployment.successor-transition-review manifest
<derivation.edn> <registry.edn> <source-revision> <approval-principal>` and
`... verify <manifest.edn> <registry.edn> <trusted-context.edn> <approval.edn>`.
`manifest-json` and `verify-json` support deployment-produced evidence. The
deployment executor derives C1 from the retained approved C0 and sealed
chain-instance artifact, then consumes the separate approval with the exact
live scope when all four transition-review files are supplied on a resumed
deploy command. The approval verifier and Clojure JSON output are tested. The dedicated
`sign` command now validates the transition derivation, trusted source revision,
fixed transition action, and active transition-key/principal binding before
writing a detached envelope; it never overwrites an existing approval file.
The owner-held key remains an external input. The executor now treats that
approval as the gate for the successor-registry
mutations: it rechecks both governors against the signer, recomputes the
one-entry Solidity registry root against PRF and retained derivation evidence,
registers only the derived adapter metadata, verifies the full on-chain entry,
and seals only that registered root. It persists transaction receipts and
readback assertions and refuses to reconcile pre-existing registry mutation
without a retained receipt. The plan validator without transition inputs still
reports `authorize-successor-configuration` as the first unsupported
prerequisite. The executable resume path with independently verified successor
approval and authority-transition inputs now contains the authority mutation
and C1 configuration application stages with independent readbacks. Admission
deployment, proof qualification and the capital flow remain open. Initial-input
approval alone does not authorize those actions.

### W6 — Qualify the composed local capital flow

Owner: protocol-deployments and prf-evm; PRF checks canonical evidence.
Priority: P1. Dependencies: W5.

- Produce operation and witness inputs from the actual C1 deployment manifest.
  Derive the reserve input from a validated reserve-v2 observation and
  operation-bound Shortfall context, or use a separately reviewed local-demo
  derivation whose supplied root is retained and approved. Retain the exact
  input manifest, raw witness, generator identity and digests. Generate a real
  proof bound to that prepared operation and configuration.
- Exercise admission, assurance preparation, ratification, capital reservation
  and funding, entitlement activation and withdrawal through the actual
  applicable topology. Reconcile final balances, liabilities, reserve state
  and consumed operation/claim identities.
- Execute negative cases: wrong proof, metadata-matching verifier substitution,
  wrong instance, stale C0 operation, wrong operation, unauthorized configuration
  route, repeated admission, repeated ratification and repeated withdrawal.
- Check rollback at failure boundaries and show that repeated admission of the
  same receipt cannot create another capital effect. Retain generic receipt
  identity semantics and enforce consumption at the capital boundary.

Acceptance: an independent verifier can reconstruct the witness and reserve
binding root from retained, approved inputs; the positive run completes under
approved C1. Wrong proof,
verifier, instance, operation and unauthorized-route cases fail at their
intended boundary with no unauthorized state or capital effect. A valid stale
C0 proof may be admitted under the active C1 verifier, but assurance preparation
must reject its C0 operation root and leave capital-bound state unchanged.
Repeated admission may return the same durable receipt under generic receipt
semantics, but must not change any capital-bound state; repeated ratification
and withdrawal revert.
Evidence comes from this fresh deployment, not an unrelated fixture run.

Current fixture-level evidence now asserts that duplicate entitlement
withdrawal and repeated ratification revert without changing token balances,
liability, claim counts, reservations or the consumed assurance receipt. The
receipt-reuse and ratification/withdrawal regression patch is separately pinned
in the release bundle and all three focused Foundry suites pass. Admission replay identity and
operation-scoped consumption semantics also have direct tests. This is
implementation evidence only: the tested flow has not been rerun from an
approved C1 deployment, and it does not close W6.

### W7 — Assemble independently verifiable qualification evidence

Owner: protocol-deployments packages; designated qualification authority reviews.
Priority: P1. Dependencies: W0–W6.

Package source/build identities, approved semantic artifacts, approval evidence,
derivation records, shared registry fixtures, runtime observations, sealed
genesis readbacks, C0/C1 artifacts, authority/configuration transition evidence,
real proof, capital-flow receipts and negative-test results. Provide a verifier
command that checks the bundle from its retained inputs without trusting the
producer's success flags. The plan-execution verifier now checks retained
derivation, approval, registry, authority-transition and C1-application
evidence against the corresponding stage outputs. It also binds execution
chain and selected-unit scope to the manifest and plan, and rejects a complete
receipt with unfinished stages or stages incorrectly marked not applicable.
The standalone command replays PRF approval/root derivations from retained
inputs under a supplied PRF checkout and independently supplied trust-context
root; genesis seal-result evidence is checked against the RPC and historical
reads. The execution receipt binds a retained final resolved-spec artifact,
including post-C1 constructor bindings, and standalone verification checks that
digest against the manifest before recomputing live runtime and assertion
evidence from the RPC and pinned fixtures. The verifier can retain a deterministic
`protocol-deployment-independent-verification/v4` report binding the receipt,
its complete/partial status, manifest, resolved-spec digest, replay count and
live-verification root. Partial prefixes stay identified as partial. The report
now enumerates the independently supplied trust context, RPC observation,
source/artifact selection, approval-key custody and proof/toolchain assumptions.
`verify:plan-report` independently checks the report schema, fixed trust list
and digest; `verify:plan-execution` remains the command that replays deployment
evidence. The verifier now accepts an optional
`protocol-capital-flow-execution/v6` bundle and replays the complete ordered
action set, including prepared-operation creation before admission, against RPC
transaction identities, receipt statuses and canonical
block hashes. It checks required state-domain reads immediately before and
after each associated transaction, requiring isolated single-transaction
blocks for rollback observations. The evidence digest and transaction/state
assertion counts are report-bound, and the bundle root is checked against the
independently verified sealed genesis. Action targets and selectors are checked
against a fixed action map, the verified deployment manifest and pinned
resolved ABI. State getters are checked against fixed domain/function mappings
and pinned view ABIs. The flow's configuration root and epoch must match the
verified C1 application stage and remain unchanged through every action. A fixed
transaction chronology places wrong-instance preparation before valid operation
creation, proof failures before admission, receipt replay after admission,
wrong-operation after assurance, and repeated ratification/withdrawal after
their successful capital actions. The replay inspects intervening blocks and
rejects unreported transactions that could alter state between listed actions.
Regression tests reject reordered actions, interleaved transactions, duplicate
deployed contract and final assertion identities, and malformed multi-binding
negative cases. The plan-execution manifest gate enforces both identity checks
before accepting a receipt for registry-candidate review. State
call arguments and expected values still need qualification review against the
approved operation. It now checks the nine-word entitlement public-values
schema, prepared-operation root, reserve commitment and statement root against
the bundle, and binds operation preparation to chain ID, C1 root/epoch and
verifier identity. Prepared reserve, token and allocator addresses must match
the deployment manifest. Getter argument counts are fixed, and balance and
consumed-claim reads are bound to the beneficiary and claim identity decoded
from withdrawal calldata. The unauthorized configuration case must be a valid
next-epoch direct-target request, and its decision authorization and proof-mode
records must both be absent before and after the reverting call. Negative
transaction inputs are constrained to the
intended single-variable substitutions: wrong proof, verifier substitution,
stale-C0 operation root, and wrong-instance chain ID. The stale-C0 case models
the actual contract boundary: statement admission under C1 may accept a valid
proof whose public values commit to a C0-prepared operation; assurance
preparation must then reject that root against the active operation. The v6
bundle retains the complete stale operation tuple and independently derived C0
root so replay can recompute the stale root. Repeated admission must replay the
same calldata and preserve capital-bound state. These checks validate
evidence shape and transaction bindings; they do not establish proof validity
or approval of historical state values. Those remain W6 checks, so W7 remains
open. Replay tests now load the source-bound v0.4.2 local-candidate artifact
ABIs to exercise the deployed action and getter selectors. Every observed action call must also
decode and re-encode byte-for-byte against its pinned function ABI.
Evidence retention does not itself grant admission; the complete verifier
still needs a real execution bundle and capital-flow evidence.

The adjacent PRO-28 forensic receipt work supplied a useful evidence pattern,
implemented in the retained W7 qualification follow-up: decode the withdrawal call, bind its
operation, entitlement index, beneficiary and amount to the allocator's
withdrawal event, and require the matching token-transfer log in that receipt.
Both operation events are decoded using the corresponding pinned artifact ABI.
Ratification replay likewise binds the operation and admission receipt to the
funding event, derives the amount from admitted entitlement public values, and
requires the reserve-to-allocator transfer for that amount. Regression tests
reject missing, extra, wrong-recipient and wrong-amount transfers and events for
another operation. This closes the call-to-log evidence gap for the two capital
movements; it does not qualify producer-supplied historical state values or
replace historical state replay. The retained qualification follow-up's capital-flow replay, trusted-expectation loading and report suites
pass 41 tests, and TypeScript typecheck passes. These are fixture results;
W7 still needs approved operation expectations and a real approved flow with
retained receipts and readbacks.

The verifier now requires a separate `protocol-capital-flow-expectations/v3`
package from the producer's execution evidence. The CLI loads it only from a
relative path inside the independently selected trusted-context root. The
package binds the exact manifest root, sealed instance, applied C1/epoch,
operation identity, getter calls and expected before/after values. It also
binds the local operation-scoped caller and entitlement representation to
their approved source/artifact digests and constructor-specific runtime code
identities. Replay routes ratification and activation through that caller and,
at each action, checks its immutable operator/operation/controller getters,
the controller's caller binding, and the representation runtime before
accepting activation. The focused verifier/report suites pass 47 tests and
TypeScript typecheck passes. Replay rejects evidence whose state assertions
differ and includes the expectation digest in the independent verification
root. This makes expectation review an explicit qualification input; approval
of those values remains a trust assumption. An earlier candidate's 2026-10-19
C0→C1 execution was independently reported but contained no W6 capital-flow
actions. Candidate v8's retained report stops before C1. No approved expectation
package or complete capital-flow bundle exists.

The W6 replay requires an explicit setup stage before its capital-flow action
sequence. The deployed
`SourceBoundProRataEntitlementRatificationControllerV1` rejects ratification
and activation calls unless `bindExecutionCaller` has set a contract address;
the observed controller is currently unbound. The earlier action map named
`ratifyAndFund` and `activateEntitlement` directly on the controller. The
protocol-deployments verifier now replays the routed `fund` and `activate`
calls through the source-pinned local operator and verifies setup receipts for
operator deployment, controller binding, reserve approval/deposit, and
representation deployment. It checks canonical setup calldata and approved
principals, creation-input hashes, receipt addresses and runtime code hashes,
operator constructor getters, historical caller-binding state, reserve/deployer
balances and allowance, and the seed transfer event. All setup transactions
participate in the isolated-block chronology. The evidence and expectation
schemas are now v6 and v3 respectively. The focused verifier/report suites pass
47 tests and TypeScript typecheck passes. This is fixture coverage, not live
qualification: the expected operation, seed amount, proof inputs and historical
state assertions still require separate review and approval, and no W6 setup or
capital-flow transactions have been executed or retained.

An existing local-only implementation is available in the PRF-EVM checkout at
`script/RunGateEEntitlementLocal.s.sol`. Its `GateEEntitlementLocalOperator`
has immutable operator, operation ID and controller bindings and exposes only
fund, activate and refund calls; its runner checks chain 31337, governance-key
identity, proof/witness provenance, prepared-operation identity and current
reserve commitment before binding the caller and admitting/funding. The source
file SHA-256 is
`49b2278666afca559d8690a47b39456fa38a5282b581a825ceab7981c2007a5c`. Reuse
and pin this helper for the local flow rather than creating a second operator
implementation. Its retained Forge artifact
`out/RunGateEEntitlementLocal.s.sol/GateEEntitlementLocalOperator.json` has
SHA-256
`2bd343ba5a78e886cce8945202bb10bdadcd60bcf4f38750ffd76e8d4d0088fc`; it was
compiled with Forge 1.7.1 and Solc 0.8.33. Its deployed bytecode contains
immutable references for the operator, operation ID, and controller, so the
runtime identity must be recomputed for the exact constructor arguments and
checked against both observed code and those getters. Its existing deployment script initializes a separate
topology with reserve capital; that setup cannot be copied as current-chain
evidence because the approved C1 deployment's reserve is empty. The current
flow still needs a separately approved and evidenced reserve seed operation,
representation deployment, and operator binding against the retained C1
contracts.

The current deployment uses `OnchainShortfallReserveV2`, while the existing
PRF source-bound reserve path only reconstructed the V1 snapshot hash. PRF now
has additive `prf/onchain-shortfall-reserve-observation.v2` and
`prf/onchain-shortfall-reserve-binding.v2` implementations. They recompute the
Solidity V2 economic-state and authoritative-observation commitments, then bind
the source facts to the sealed chain instance, C1/epoch and prepared operation.
The focused tests pin an ABI/Keccak vector independently reproduced with
viem and reject altered commitments, availability and context. V1 identities
remain unchanged. This closes a semantic-input gap for W6, but does not by
itself authenticate RPC observations, produce a proof or qualify a capital
transaction; live deployment binding and complete W6/W7 evidence remain open.

Current regression evidence: `bb test:unit` passes 897 tests and 3,609
assertions, including the candidate-v5 source and manifest binding test.
Candidate-v6/v7 source-lock and manifest-binding tests pass separately
(2 tests, 20 assertions); the W6 v0.4.2 ABI replay suite passes 34 tests.
The prf-evm working tree builds successfully;
`PrfGenesisTest` passes 10 tests, `PrfStatementAdmissionTest` 19, and
`PrfConfigurationAuthorizationConformanceTest` 30. The complete unscoped
`forge test` invocation exits nonzero: 284 tests pass and 41 phase-dependent
tests fail because Gate E proof-manifest environment inputs are absent and the
accounting/partial-fill suites require `PARTIAL_FILL_STAGE=2`. With that stage
selected, the documented composed-flow subset passes 14 tests; bootstrap and
genesis-export tests belong to other stages. In protocol-deployments, the
focused replay/expectation/report suites pass 47 tests and `npm run typecheck`
passes. These results validate code and replay logic only; they do not
substitute for the approved live deployment bundle required by W7.

Acceptance: a separate invocation can verify all bindings and reproduce the
qualification decision inputs. The report states remaining trust assumptions
and declares only the approved local scope.

## Execution order and approval points

The policy-v3 EIP-191 validator is now wired into the bounded local deployment
profile. Authenticated approval output supplies the policy root and signer;
the plan deploys the validator; the CLI checks its immutable getters, adapter
governor and genesis bindings, unchanged initial authority root and unsealed
state before enabling transitions, then checks the resulting adapter bindings.
Resolver, approval-gate and plan tests cover this path. The prior local
approval and execution apply to candidate v4; they do not authorize candidate
v5's changed source identity or the new runtime code. W1 remains open for v5
until the newly approved transition path is executed and its failure cases
and resulting authority state are independently observed.

### Current candidate audit (2026-10-19)

The preserved v2–v6 candidates remain unchanged as historical review
material. Candidate v7 uses
`bounded-configuration-release-sources.current.v6.json`, with 933 sorted
source entries, parent `896ab2ca29d178d9b3993750d4fb774deaf984c4`, and tree
`6ffee509341fb888235d4bb3f0ef2cee466f29fb8ac4433fb3c892f077f2ae89`. It adds
the protocol-deployments W6 replay-fixture patch to the previously pinned
authority-continuity and PRF-EVM Foundry-path normalization patches. The four
semantic artifacts and seven C0 roots remain unchanged; the source-bound
initial-input manifest root is
`sha256:4a58d73213ff165e9e34fbfcd2694c029e681dbccdc0420bc0c0b3ea0c519f27`.
The 30-artifact v0.4.2 local bundle has root
`5b9da0dfc6b1a2864d0ae174faef3ddf0095dab21b4a158de25aef677235b7ba`; three
exports have matched byte-for-byte, including one after a clean Foundry build,
and the protocol-deployments bundle consumer validates
the manifest and the three modified core artifacts. Capital-flow replay now
uses the v0.4.2 ABIs and passes 34 tests. The v7 source-lock and
manifest-binding test passes. Candidate v7 remains an unapproved historical
candidate. This 2026-10-19 audit was superseded by v8 on 2026-10-22; current
approval and W3/W4 report evidence appear at the start of this plan.

The following order table records the superseded 2026-10-19 sequence; the
2026-10-22 table below it is the current execution order.

| Order | Work | Concrete output | Gate / dependency |
| --- | --- | --- | --- |
| 1 | Preserve the historical v0.4.1 baseline, and pin the candidate-v7 source lock plus v0.4.2 local artifact bundle. A clean Foundry rebuild and three exports reproduce the bundle byte-for-byte; retain all artifact records and tool versions. | 933-entry source lock, source-bound v7 manifest, exact v0.4.2 bundle root and deployables validation evidence. | Do not overwrite v0.4.1. The historical v0.4.1 root remains unreproduced; local candidate provenance is not a public release. |
| 2 | Finish W2 semantic review and local approval material against candidate v7. Review policy-v3 and its validator trust assumption, finalize the four candidate artifacts and seven-root C0, then obtain authenticated approval over candidate v7's exact source-bound manifest and local scope. | Owner-reviewed package plus detached, independently verifiable initial-input approval. | Prior candidate approvals do not cover the new manifest; absent renewed approval, stop before deployment transactions. |
| 3 | Complete W3/W4 execution gates against the approved package. Authenticate deployed verifier and validator runtime identities, perform fresh chain observations, independently derive genesis, and seal only when all bindings match. Retain mined receipt and same-block readbacks. | Verified local genesis seal and deployment evidence bundle. | Requires steps 1–2; changed source or deployment facts require a new derivation and approval as applicable. |
| 4 | Complete W5. Deploy and bind the executor and empty epoch-0 registry to the sealed instance; deploy/authenticate the successor verifier; derive C1; obtain separate transition approval; execute the approved authority/configuration update and independently check readbacks. | Distinct, reproducible C0/C1 identities and retained approval, transaction and state evidence. | Initial-input approval does not authorize this transition. Stop if parent, epoch, instance, proof mode or registry differs from the approved manifest. |
| 5 | Run W6, then package W7. Exercise the real proof and protected capital path, run negative and replay cases at the capital-consumer boundary, and create a verifier command that rechecks the complete retained bundle. | Local qualification bundle and independent verification result, scoped to the approved local deployment. | Requires successful W5. Public-chain evidence, public approval and production readiness remain out of scope. |

### Superseding execution state (2026-10-22)

| Order | Work | Next concrete action | Gate |
| --- | --- | --- | --- |
| 1 | W0 | Keep the v8 source lock and v0.4.2 bundle fixed; finish maintainer integration and explicitly record the unresolved historical v0.4.1 equivalence. | The 314-test protocol-deployments suite and typecheck pass; this is not release-maintainer acceptance. |
| 2 | W2 | Preserve the verified v8 initial-input and successor-review approvals unchanged. | Recompute and reapprove if source, package roots, validator identity, or scope changes. |
| 3 | W3/W4 | Before resuming, obtain a fresh RPC readback and compare chain ID, block ancestry, addresses, code hashes, seals and configuration-executor state with the retained instance. | RPC is currently down. If the approved instance cannot be recovered, stop and rederive/reapprove any changed instance-bound artifact. |
| 4 | W5 | Obtain and verify the EIP-191 authority-transition attestation from policy-pinned address `0xc78884593859e84524e41ce94cce38fbcb58c5f8`; execute only after the approved instance is live and matching. | The request remains unsigned. The known `0xC0E…` key and previously supplied signature recover to other addresses. |
| 5 | W6/W7 | After W5 is verified, prepare separately approved operation expectations, run positive/negative proof and protected-capital paths, then package and independently replay the full evidence bundle. | No W6 capital-flow evidence exists; W7 remains incomplete. |

Build and test unapproved fixtures while approval work proceeds, but label those
results as implementation evidence only. Never convert candidate artifacts or
test keys into approval evidence, and do not bypass a missing approval by
sealing or executing a transition with operator-supplied roots.

No public deployment or public authority approval is implied. Work outside the
current writable roots needs a writable checkout; the preserved Solidity patch
is the integration input, not evidence that integration has already occurred.
