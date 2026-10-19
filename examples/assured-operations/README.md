# Assured Operations: treasury enforcement prototype

Experimental EVM fixture, not a production deployment or a qualified proof system.
Implements the narrow dispatcher profile of the
[architecture](../../docs/architecture/ASSURED_OPERATIONS_V1.md). The two core
schemas retain their specified canonical bodies; they remain unregistered in PRF's
production hash-intent registry. Solidity roots use the existing typed SHA-256
scheme, checked against `resolver-sim.hash.canonical`.

## Fixed enforcement path

One `AssuredExecutor` controls one immutable `Treasury` instance and one
`withdraw(address,uint256)` selector. The outer executor caller is the principal;
the treasury sees the executor as `msg.sender`. No forwarded caller, proxy,
delegatecall, multicall protocol, arbitrary helper target, fallback operation,
system contract, or engine hook is supported. Governance seals the target once.
Target address/code, chain ID and chain-instance reference, release/candidate/scope,
profile, principal, replay namespace, calldata and forwarded value are checked.

The core gate receives a concrete `IAssurancePolicy` reference. It knows nothing
about withdrawals, balances, solvency, or allocation. Its prototype codec uses
`:binding/target` and the closed single-code-binding, principal-bound selector
subset of the architecture. Optional definition, authority, validity and domain
fields are explicitly nil in that subset. Different application policy types can
be referenced without changing the registration grammar.

The treasury holds **native currency**. Native base units are the amount unit;
the chain-instance reference supplies asset domain. The target call forwards zero
native value: withdrawal moves funds already held by the treasury. Public deposits
increase the treasury revision. Withdrawal is the only consequential outgoing route,
increments revision, and checks actual source/destination deltas. Recipients that
forward funds during the callback are outside this narrow profile and cause rollback.
Forced incoming native transfers are detected through balance equality when the
projection is checked; an EVM revision counter does not observe `SELFDESTRUCT` alone.

## Trust and admission boundary

The sealer is the fixture's admission trust anchor. It supplies external genesis,
release, candidate and scope references and pins the observed target code and
executor relationship. **The prototype does not validate release bodies, manifests,
source/runtime equivalence, or genesis provenance on chain.** Numeric sentinel roots
in runtime tests are fixture identifiers, not claims of canonical deployment
qualification. The separate hash vectors contain illustrative canonical bodies.
Connecting genuine retained deployment artifacts and admission evidence is required
before qualifying AO-1/AO-7 for a deployed instance.

Governance is one immutable address, authenticated by actual `msg.sender`. This is
an explicit prototype control profile. It does not claim to implement PRF researcher
governance or a universal authority artifact. The control body canonically commits
that address, activation delay and forbidden removal. There is no unprotect, delete,
target replacement, replay reset, or alternate outgoing treasury route.

`TreasuryPolicy` is an application-specific bounded permission policy, with immutable
signer, maximum amount and minimum post-withdrawal balance. Its ECDSA attestation
binds policy, chain instance, candidate, target, route, principal, destination,
projection definition/result and inclusive deadline. The amount is bounded by the
signed policy's cap. There is no nonce in the predicate, so the permission can be
evaluated for several distinct envelopes at the same observed state. A committed
withdrawal changes that state and requires a new state-bound permission.

The attestor signs the raw 32-byte domain-separated canonical SHA-256 statement;
this prototype does not use a wallet personal-sign prefix or EIP-712. Verification
requires v=27/28, low-S and the selected immutable signer. This is a prototype ECDSA
profile, not a reinterpretation of PRF's existing Ed25519 signed-external-decision
schema. It is not a proof of solvency or an SP1 verifier. Policy changes replace
the concrete policy root and verifier instance; old permissions are not silently
accepted under the replacement policy, even when its limits remain identical.

## Exact registration activation semantics

The active tuple is `(registration root, revision, policy reference, policy address,
policy runtime hash, disabled)`. Each `execute` must provide both the expected root
and revision. It snapshots that tuple before admission and emits the tuple it
consumed. It never infers the effective revision later from a block number.

`propose(expectedParent, policy, disabled)` authenticates governance and the exact
current predecessor, derives N+1, snapshots policy root/address/code, and records
the earliest activation block. One pending proposal exists; replacing it explicitly
supersedes the earlier pending root. `activate(expectedSuccessor)` authenticates
governance, current predecessor, successor, block delay and policy code/root, then
atomically installs it. Genesis is revision 0 with no parent. A disabled successor
retains a concrete policy; enabling it requires another authorized revision.

| Ordered calls in one transaction/block | Applicable registration |
|---|---|
| Execute expecting N, then activate N+1 | The execution consumes N; activation affects subsequent calls. |
| Activate N+1, then execute expecting N | Execution rejects with `RegistrationChanged`. |
| Activate N+1, then execute expecting N+1 | N+1 applies, including its disabled state or replacement policy. |
| Atomic enclosing batch activates N+1, then stale execution reverts uncaught | The enclosing revert restores N and the pending proposal. |
| A batch catches a stale execution's revert after successful activation | Activation can remain committed; the rejected execution has no consumption or target effects. |
| Authorized governance callback tries activation during target dispatch | The shared serialization guard rejects it; the execution remains bound to its original tuple. |

EVM call order is authoritative within a transaction; transaction order is
authoritative within a block. A block timestamp or number cannot replace the
explicit expected registration tuple. Activation and execution evidence are events
at their exact chain log/call locations. Authorization binds the desired revision
externally through the governance call; no authorization hash is inside the
registration preimage, so there is no authorization/registration hash cycle.

## Consumed state and replay

The treasury projection definition commits its target and ordered fields
`[:target/address :balance :revision]`. Its current result value is exactly
`{:target/address A :balance B :revision R}`. The result hash reuses
`registry.observation/result-root`'s domain
`prf.registry-chain-observation-result.v1`. This is a canonical projection result,
**not** a new universal observation root or an assertion that the current block's
hash is available inside the EVM.

Admission names and checks the exact projection-definition and result roots against
live target getters, and the attested permission binds both. `OperationExecuted`
records those consumed roots and the post-result root. The consumed observation's
identity is the pair **plus the exact execution event/call location**. Two calls
seeing equal projection values need not be the same observation. Later reconstruction
authenticates that context from chain data/replay; a block-end observation is not
substituted for this per-call state.

The replay namespace canonically binds executor address and chain instance. It is
one global operation-nonce namespace for this fixture: authorized principals must
coordinate nonce allocation, rather than assuming account-local nonces. Runtime
uses the nonce mapping only because the other namespace dimensions are immutable
and validated on every call. Governance revisions never reset it. A static policy
check precedes reservation; reserve before the external target call. Target failure
reverts the whole call, including nonce and treasury revision. Successful execution
consumes permanently. Policy/observation calls are STATICCALLs, and all gate mutation
paths share the same reentrancy guard.

## Canonical prototype profile definitions

These are isolated application/adapter fixtures; they introduce no universal
policy/configuration/authority/observation artifact and no production hash intent.
Every body is closed and uses the architecture's types, nil and canonical ordering.
SHA-256 roots below use `domain || canonical-bytes(body)`.

| Discriminator / domain | Exact body fields |
|---|---|
| `ao-dispatcher-profile.prototype.v1` / `ao.prototype.dispatcher-profile.v1` | `:artifact/schema`, `:executor/address`, `:executor/code-hash` (keccak reference), `:caller-mode :outer-msg-sender` |
| `ao-registration-control.prototype.v1` / `ao.prototype.registration-control.v1` | `:artifact/schema`, `:governance/address`, `:activation-delay-blocks`, `:removal :forbidden` |
| `ao-replay-namespace.prototype.v1` / `ao.prototype.replay-namespace.v1` | `:artifact/schema`, `:chain-instance/root`, `:executor/address` |
| `treasury-projection-definition.prototype.v1` / `ao.prototype.treasury-projection-definition.v1` | `:artifact/schema`, `:target/address`, `:fields` vector defined above |
| `treasury-withdrawal-policy.prototype.v1` / `ao.prototype.treasury-policy.v1` | `:artifact/schema`, `:signer/address`, `:maximum-amount`, `:minimum-post-balance` |
| `treasury-withdrawal-permission.prototype.v1` / `ao.prototype.treasury-withdrawal-permission.v1` | `:artifact/schema`, `:policy-ref`, `:chain-instance/root`, `:candidate-ref`, `:target/address`, `:operation`, `:principal`, `:destination`, `:state {:definition-root H :result-root H}`, `:not-after-block` |

Schema discriminator values in these bodies are strings. All integer fields are
nonnegative; operation and reference shapes match the architecture. Policy root
commits the cap/floor and signer, so the permission's policy reference transitively
binds them. The verifier address/runtime is an activation/admission fact, separate
from policy semantics. The prototype has no independent program/VKey/configuration
aggregate. Solidity stores digest bytes32 values; their canonical body encoding
uses the architecture's exact lowercase `sha256:`/`keccak256:` reference strings.

## Financial reconstruction and evidence

The successful withdrawal test independently decodes the target and executor logs
and joins actual balances to intended calldata. With retained calldata, canonical
bodies, evidence bytes, activation logs and authoritative transaction/receipt plus
per-call replay, a verifier can reconstruct:

* Intended native asset instance, amount, source treasury, destination, caller and nonce.
* The consumed registration revision, concrete policy, verifier deployment/runtime,
  ECDSA statement and evidence commitment, and why authority/admission succeeded.
* Exact pre-revision/balance observation and post-result root.
* Actual target call and source/destination balances before/after, revision transition
  and successful nonce consumption.

Compose these facts using the existing forensic evidence graph and quantity/delta
transition kernel described in the architecture. The events are the prototype's
per-call evidence adapter, not a new canonical receipt artifact. Attach block hash,
transaction identity, log indices and call-frame identity from the chain. Preserve
body availability. Recompute native quantity deltas under the application's asset
and subject identities; separately account for sender gas fees. Events become
authoritative only after runtime identity, chain inclusion, finality and semantics
are authenticated. This suite tests the joins in Foundry's EVM; it does not provide
a deployed/finalized chain dossier or claim full AO-11 production qualification.

## Run and extend

Requires installed Babashka, Python 3, Foundry and solc 0.8.33. No third-party Solidity
library, fetched package, or runtime dependency was added. From the repository root:

```sh
python3 examples/assured-operations/scripts/generate_vectors.py
forge test --root examples/assured-operations --use "$(command -v solc)" --offline -vv
forge fmt --root examples/assured-operations --check
```

The vector generator reloads the existing PRF encoder and registry-observation
namespace, verifies proposed domain prefix-freeness, and writes illustrative
canonical EDN bodies plus golden envelope/registration/result/policy hashes and the
maximum-U256 encoding. Regeneration must not redefine the architecture or bless a
semantic change. Tests require those independent values and include 256 fuzz runs
for amount/nonce, activation ordering, rollback, stale state, signature/configuration
substitution, runtime/code/chain/caller substitutions, direct route bypass and
reentrancy. Foundry build/cache outputs are ignored locally.

## Pro-rata conformance result

`pro-rata-conformance/` exercises the unchanged generic executor against the
existing `SourceBoundProRataAssuranceV2`, ratification controller, V2 reserve and
`ProductionProRataAllocatorV2`. It adds only an application policy, operation
registration, reserve/source projection and thin dispatch adapter. The executor,
envelope/registration codec, nonce, activation and execution semantics are unchanged.

| Property | Treasury withdrawal | V2 pro-rata ratification | Governance upgrade |
|---|---|---|---|
| Exact target and operation | ✓ | ✓ (adapter route) | ✓ (`applyUpgrade`) |
| Caller authority | ✓ | ✓ (prepared initiator) | ✓ (timelock principal + council approval) |
| Expected registration root/revision | ✓ | ✓ | ✓ |
| Consumed state projection | Native balance/revision | Reserve commitment, availability revision, V2 assurance and funding state | Current implementation, runtime hash, release, configuration and source roots |
| One-shot envelope | ✓ | ✓ | ✓ |
| Reusable assurance possible | ✓ by policy | Generic gate permits it; V2 receipt is itself one-shot | Policy-specific; approval binds one envelope and prior state |
| Financial delta reconstruction | ✓ | ✓ reserve/allocator token deltas and existing reserve observation | n/a |
| Generic core modification | Baseline | **None** | **None** |
| Direct route necessarily passes the gate | Protected target only | ✓ after one-time execution-caller binding | ✓ in fixture; no alternate setter |

| Consumer | Composition | Mandatory enforcement | Production assurance/provenance |
|---|---|---|---|
| Treasury | ✓ | ✓ | Experimental |
| Governance | ✓ | ✓ | Experimental |
| Pro-rata | ✓ | ✓ | Pending |

The route-closure acceptance test first calls the existing V2 `ratifyAndFund`
method directly and requires rejection with no assurance consumption, state change
or asset movement. It then submits the same operation through Assured Operations
and requires success. Each ratification-controller instance permanently binds,
once, to a code-bearing execution route authorized by the existing prepared
controller governance. The dispatcher adapter requires the executor as caller, so
the accepted route is the same one checked by the generic gate. V2 proof, reserve,
allocator and transition semantics are unchanged; the generic executor, envelope,
registration, codec and nonce semantics are unchanged.

The test verifier still accepts the verifier call without checking an SP1 proof;
real V2/SP1 admission remains a separate experiment. The deployment sealer also
remains trusted fixture setup; canonical deployment provenance is a separate
experiment. Deployment workflows must bind the ratification controller to the
intended dispatcher route before enabling ratification.

That is a deployment invariant: a V2 ratification controller is operationally
admitted only when its permanent caller binding equals the intended deployed
adapter, the adapter has runtime code, and the adapter reports the intended
Assured Operations executor. The deployment fixture asserts these conditions
after binding by independently reading the controller and adapter state. An
unbound or mismatched controller remains fail-closed.

Route binding proves connectivity, not production provenance. Production
admission must also establish that each security-critical component in the
protected route resolves to the intended canonically admitted deployment/release
identity. For pro-rata this includes the controller, adapter and executor, plus
verifier/admission contracts where they participate in the trust path. Address
and code-presence checks alone do not establish provenance.

### Frozen conformance baseline

Treat the envelope and registration schemas, registration revision semantics,
nonce consumption, state/projection binding, dispatcher path, permanent
pro-rata route binding, forensic evidence composition, and deployment-route
invariant as frozen. Change one only if a boundary-substitution experiment
demonstrates an actual contradiction, with a concrete reproducer.

The next experiments are independent substitutions at existing boundaries:
mock assurance admission to real V2/SP1 verification, then the trusted
deployment sealer to canonical deployment provenance. Keep the operation model
and frozen components constant in each experiment; do not bundle the substitutions.

The exact projection records the pre-call reserve commitment and revision and
post-call reserve/allocator balances, V2 assurance state, and ratification state.
The existing V2 allocator operation and reserve observation preserve allocation,
realized-statement and funding facts. These join to authoritative transaction and
log data for financial reconstruction; no withdrawal or operation receipt artifact
was added.

The only Assured Operations implementation priority is this pro-rata route
closure. Real V2/SP1 proof admission and canonical deployment provenance remain
separate experiments. Reconsider execution-engine support only after all three
have passed, then identify what repeated security-critical machinery or bypass
surface remains across treasury, governance and pro-rata. A small standard guard
would not by itself justify engine enforcement.

Current milestone status: architecture ✓; treasury composition ✓; treasury
enforcement ✓; governance composition/enforcement ✓; pro-rata composition ✓;
pro-rata route closure ✓; deployment-route invariant ✓; real SP1 admission is
next, with an AO-specific Gate E runner prepared; canonical deployment
provenance is a prerequisite gap because the inspected runtime does not expose
the necessary admission surface;
execution-engine/L2 decision deferred.

Run the fresh real V2/SP1 AO substitution from the repository root:

```sh
bash examples/assured-operations/pro-rata-conformance/run-real-v2-sp1.sh
```

The runner uses the local Anvil development key, deploys the Gate E Option A
V2 topology, materializes and proves the live prepared operation, then runs
`ProRataRealProofConformanceTest` against that deployment before the Gate E
consumer test. It keeps the V2 deployment manifest, prover input, proof fixture,
AO route-manifest overlay, source-state record and source inventories, plus the
complete deploy/prove/test log under
`examples/assured-operations/pro-rata-conformance/artifacts/real-v2-sp1/` and
refuses to overwrite existing artifacts. Set ARTIFACT_DIR to retain a failed attempt and write a subsequent run to a separate directory. The source record includes JJ/Git
revision context, dirty-state metadata, tool versions and hashes of the relevant
source inventories and prover binary. Failed runs retain their log and any
artifacts already produced. After success, the runner compares pre/post source
inventories, checks proof public values against the deployed and AO-dispatched
operation, and records the exact AO envelope/calldata, registration revision and
pre-call projection witness in the route manifest. It writes
`real-v2-sp1-evidence-manifest.json` last. That final index
hashes the completed run log and the other retained evidence; the AO route
manifest itself remains a runtime output and does not include the log hash. The
Gate E consumer test result is recorded as auxiliary; all six AO proof-path
cases and the post-run consistency checks gate the AO result. The prover binary
can be built with:

```sh
cargo build --release --manifest-path coprocessor/Cargo.toml \
  -p allocation-sp1-script --bin source-bound-v2-prove
```

The AO test verifies through the existing V2 admission path, rejects tampered
proof/public values and configuration substitutions, rejects stale projections
and direct ratification, and checks the resulting V2 funding and observation.
The runtime experiment remains pending until this runner completes successfully.

## Authenticated bounty funding and direct payout

`src/BountySettlement.sol` is a separate controlled native-asset example for
the bounty lifecycle. The configured funder deposits actual native currency;
the contract records a unique source root, sequence and available balance.
Only the award authority can create a payable, and only against that recorded
reserve. The beneficiary address can be pseudonymous; no civil identity is
required. A claim is one-time and pays the full amount directly to that
beneficiary. The contract checks exact reserve and beneficiary balance deltas,
and a rejected transfer rolls back the settlement state.

`test/BountySettlement.t.sol` retains executable checks for funded backing,
unauthorized and overdrawn award rejection, exact payout deltas, replay
rejection, and failed-transfer rollback. Run it from the repository root:

```sh
forge test --root examples/assured-operations --use "$(command -v solc)" --offline --match-contract BountySettlementTest -vv
python3 examples/assured-operations/scripts/verify_bounty_rpc.py
```

The second command deploys the contract to a fresh local Anvil instance, funds
and approves a pseudonymous beneficiary, executes a claim, compares the payout
event's payable roots with contract readbacks, and invokes the Clojure JSON-RPC
receipt verifier. The verifier binds chain ID, derived local chain-instance root,
genesis hash, contract runtime digest and observer source, then walks parent links
to genesis. The script writes the signed payout and observation into a canonical
settlement and replays it in a separate Clojure process while checking the live
receipt. It demonstrates local inclusion and execution consistency; Anvil's head
report is not independent consensus finality or evidence about a public deployment.

This example authenticates on-chain funding through actual deposit execution
and a configured funding authority. It does not prove the authority's external
source of funds or qualify a production bounty service. The Clojure verifier can
optionally query JSON-RPC for receipt, event and endpoint-reported finality checks;
the Sew adapter does not configure that live RPC check by default. The Clojure
path uses separately signed funding and payout attestations and local custody
accounting; those artifacts are not on-chain transfer proofs.

## Governance upgrade conformance result

`GovernanceConformance.t.sol` runs a third, non-capital consumer through the same
executor and codec. A timelock principal submits the exact upgrade calldata. A
separate governance signer approves the proposed implementation address and runtime
hash, source artifact root, release root, configuration root, required security
policy, chain instance, envelope, and exact pre-upgrade projection. The target
accepts upgrades only from the executor; its projection commits the active
implementation and linked release/configuration identities.

The test joins the signed approval root and consumed registration revision in the
executor event with the `RouterUpgraded` event and resulting state. This reconstructs
the approved candidate and installed transition in the fixture without a new receipt
artifact. The canonical target deployment sealer remains the prototype trust seam;
candidate identities come from the application policy and governance signature,
not production registry admission. The security-policy getter is an asserted
candidate property, not an independent cryptographic audit. Production use must
replace these fixtures with admitted release, artifact, authority and assurance
records.

## Frozen execution-domain decision (2026-10-04)

Keep dispatcher-mediated Solidity enforcement as the Assured Operations baseline.
Do not begin execution-engine or L2 work from the present evidence. Revisit only
when a conformance case shows a recurring requirement that application-level
enforcement cannot adequately meet. The pro-rata direct-route
bypass is caused by the current consumer's public controller entry point and can be
closed by an application-level executor restriction. Treasury and governance run
through executor-only target routes. No generic machinery has yet been shown to be
duplicated across independent production consumers.

If future evidence justifies system-level support, compare
mandatory target resolution, active registration-revision lookup, call-frame
admission, atomic nonce consumption and consumed-projection evidence. Engine-level
hooks could reject direct calls to a registered route, while route inventories,
semantic aliases, policy propositions and state projections would still require
application definitions. First close the V2 direct route; then replace its proof
verifier and the deployment sealer as separate conformance changes.

Remaining Solidity coverage gaps: a voluntary verifier with an independently callable
target still permits bypass; new alias mutators or unsafe upgrade routes can defeat
selector coverage. This immutable treasury has neither. The prototype does not
establish proxy closure, arbitrary semantic-equivalence detection, distributed
settlement, cross-domain finality, or production governance-compromise resistance.

Run the pro-rata composition fixture with a neighboring `prf-evm` checkout at
`../../../../../prf-evm` relative to its Foundry root:

```sh
forge test --root examples/assured-operations/pro-rata-conformance --use "$(command -v solc)" --offline -vv
forge fmt --root examples/assured-operations/pro-rata-conformance --check
```
