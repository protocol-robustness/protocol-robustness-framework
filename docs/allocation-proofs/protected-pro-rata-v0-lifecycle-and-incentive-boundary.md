# Protected pro-rata V0 lifecycle and incentive boundary

Status: source-backed design snapshot, not deployment qualification.

This note records a source-backed proposal for the bounded local demonstration
described by PRO-112. It maps existing behavior and records what is not yet
established; it does not add a new allocation, reward, custody, or settlement
protocol. Independent review and owner acceptance of the V0 boundary remain
pending.

## Source snapshot

| Owner | Source identity inspected | Qualification note |
|---|---|---|
| PRF | JJ commit `398bfeb1e8351653836f7a094063cc3d7bee7178` in `agent-c` | Current documentation and canonical model source in this workspace. |
| `prf-evm` | Git `c17b479811d0b1cdaefbd36227d4c2fac202a1d0` | Relevant Solidity sources were read at this commit. The checkout has a separate modified `README.md`; no relevant contract source modification was observed. |
| `prf-capital-lifecycle` | Git `c948cffee6cce640081242057c145b75f2f2dfcd` | Current local source identity; no dirty files reported. |
| `prf-coprocessor` | Git `18b477b6408f701cafb127072d327c3fc1c52819` | Checkout is clean. Guest entrypoint blob `945e876b2889afbd98a495cdca6e9a36d830db49` matches the source blob at `e2e4e9b25622c52eb028fc373a66e81b3982e933`; the local proof artifact itself does not record a build commit. |
| `protocol-deployments` | JJ commit `0e343e0cfbeaa6b24d605865c430aeff3eb6625e` | Inspected as the deployment-ledger source snapshot; not a deployment run or public release. |

These are inspection pins, not an assertion that the repositories are all at
the same release boundary. Re-pin them together before producing a qualified
cross-repository artifact.

## Deployment-plan alignment blockers

The tracked `protocol-deployments` plan at commit
`0e343e0cfbeaa6b24d605865c430aeff3eb6625e` is not aligned with this current
V0 proposal:

| Plan field | Tracked plan value | V0 requirement / consequence |
|---|---|---|
| Verifier profile | VKey `0x00d90e…87b9`, ELF `0x636330…acf8` | Must be updated to the current documented profile `0x003cb0…7bac5` / `0x7e9cf1…0e39`, or the owner must explicitly reselect and qualify the older identity. Current proof-profile documentation and the plan cannot both be treated as current without this decision. |
| Reserve logical contract | `shortfall/shortfall-reserve-v2` → artifact `OnchainShortfallReserveV2`; architecture binds `protectedCapital` to `0` | This does not implement the V0 fixture's 10-unit post-closure protected-capital release. The V3 reserve source has the scoped operation, immutable refund beneficiary, and closed-operation release checks. |
| Local authority | Local-only plan with `PrfLocalAuthorityAdapter` and configured transition steps | Suitable only as a local fixture. The adapter explicitly does not implement voting, quorum, timelock, or proposal execution. It provides no public governance approval. |
| Current worktree | The deployment repo has a large unrelated dirty working copy; the successor plan file itself is not listed dirty | Do not edit or commit that work as part of this PRF note. Validate plan changes in its owning integration process. |

The plan's `genesis-dependency-graph.v1.json` does separate initial empty
registry state from the later successor verifier registration/configuration
transition. That ordering is directionally consistent with the boundary here,
but declarative stages do not establish that those stages have executed or
their outputs are authentic. The plan is marked `localOnly`; it is not a
Base-Sepolia-ready profile.

The current source constructor/binding topology is:

```text
SP1 verifier → source-bound entitlement verifier adapter ─┐
governed verifier registry ────────────────────────────────┼→ statement admission
sealed chain-instance genesis + active configuration ─────┘
                                                               │
prepared-operation controller → entitlement assurance ───────┤
token + allocator ← reserve (one-time funding-authority bind)  │
                                                           ratification controller
                                                               │
                  one-time prepared-controller / assurance / reserve bindings
                                                               ↓
                           bound local executor → prepare, admit, fund, activate
                                                               ↓
                           committed representation → claims → protected refund
```

More precisely: `PrfStatementAdmission` is constructed from sealed genesis,
configuration executor, verifier registry, and an expected configuration
root/epoch. The successor verifier adapter is constructed from an SP1 verifier,
VKey, and ELF digest. The assurance contract consumes admission, that adapter,
and the prepared-operation controller. The ratification controller consumes
the prepared controller, assurance, and reserve. The allocator's proof-mode
source is the configuration executor; its funding authority is bound once to
the reserve. The reserve's ratification authority is then bound once to the
ratification controller, while the prepared controller and assurance each
bind that same controller once. The ratification controller finally binds
one code-bearing execution caller. This describes constructor and setter
requirements; it is not evidence that the selected plan has completed them.

The plan's `prf/configuration-executor` artifact resolves to Solidity
`SewConfigurationExecutor` under `src/integrations/sew/`. Its constructor
accepts PRF chain-instance genesis, `IPrfAuthority`, and the PRF verifier
registry; it has no SEW token, Governor, or Timelock constructor dependency.
The local PRF authority adapter implements the interface it consults. Thus
this artifact does not by itself establish a deployed SEW governance
dependency, but its source ownership/name and `@notice` text describe SEW
governance. The bounded plan should explicitly classify this shared executor's
ownership and capability; do not count it as an active SEW protocol deployment
or conceal the source-boundary ambiguity.

The separate candidate environment
`config/pro-rata-v2-entitlement/environments/local-revised.edn` also contains
literal sentinel values in bootstrap actions (including an all-zero genesis
seal argument and a repeated-`0x55` local authority policy root). These are
fixture inputs, not derived canonical roots or evidence that an authorized
transition has occurred. Before any plan run is considered valid, identify the
exact environment file selected by the executor and reject unresolved
sentinels; do not silently treat this candidate file as an admitted genesis.

## Proof identity and retained-evidence reconciliation

The current PRF semantics document pins the successor family as:

| Identity | Current documented pin |
|---|---|
| Program | `source-bound-entitlement-sp1-program.v1` |
| Public-values schema | `prf/source-bound-entitlement-public-values.v1` |
| Public-values length | 288 bytes (nine ABI words) |
| SP1 SDK / proof system | 6.3.1 / Groth16 |
| VKey | `0x003cb0ac41d9c5cd78fe461b5ba27dfb08cd74490c9526fa762e7e392ee7bac5` |
| ELF SHA-256 | `0x7e9cf1b3479a4b6fb8836b50b30b55d00736ac860eaba4230f057d4687bc0e39` |
| Guest source | `prf-coprocessor` revision `e2e4e9b25622c52eb028fc373a66e81b3982e933`, `source-bound-entitlement-sp1-program/src/main.rs` |

This is the intended V0 proof profile according to
`docs/architecture/PROTECTED_CAPITAL_PRO_RATA_V2_SEMANTICS.md`. Do not infer a
deployed verifier address or active registry selection from it.

An unarchived local proof fixture was also found and inspected. It matches the
current profile: fixture SHA-256 `09cfc99339815aa05a1a2f14e09f5655d93df76717a4b3eb3cb1d585b60513bc`,
on-chain proof SHA-256 `0x0a406ec40dd4622c378507db63bf33eec2cec27b2448609f9e5b397c10776e39`,
and public-values SHA-256
`0x5830cbd0414d24d1c92538dbd12ddec2ce60360cf357f0e46d3f9d839574aaac`.
It contains a 356-byte Groth16 proof and 288 public-values bytes for vector
`A-minimum-realistic`; the retained proof-generation log records SP1 SDK
verification and equality to the Rust core and golden vector. That vector has
70 available units and 50/30/20 requested allocations, yielding the 35/21/14
calculation. The artifact does not record a source commit, and its prepared
operation uses conformance-fixture bindings. It establishes a proof for the
frozen vector/profile, not admission for the actual 35/21/14 deployment
operation. The proof fixture and log are not yet part of a portable evidence
bundle.

There is also a distinct historical local deployment verification record at
`examples/partial-fill/evidence/pro-28-v1/groth16-reference-verification-20261018.json`:
it reports the same program/schema/288-byte profile, but VKey
`0x00d90efa52d17e54670a4248f001e4da443a938f12c55d0d414c125ba68087b9` and
ELF SHA-256
`0x636330de8e7af3485aba50bd3ca4b5e024cc4f977a2bf0f2c005f711e5ffacf8`.
Those identities differ from the current documented pin. Treat that record as
historical, local-only evidence for its own captured deployment; it is not a
proof artifact for the current V0 identity. The profile-level identity
mismatch is resolved by the matching current-profile vector proof above; the
historical PRO-28 deployment proof remains incompatible. A fresh proof must
still be generated from the actual prepared operation and live reserve
commitment, then admitted by the selected local verifier/registry. The actual
verifier runtime and configuration/registry selection still need fresh
deployment observations.

## Bounded economic profile

The reference scenario has 100 units of approved requests, 80 units deposited,
70 units allocated to Alice/Bob/Carol at 35/21/14, 10 units reserved for the
restricted residual refund, and 30 units of unfilled requests. These quantities
are separate accounting categories. The unfilled 30 is not a payment, reserve
balance, or proof of loss. The 10-unit refund is not a fourth beneficiary
entitlement.

The operational lifecycle is:

| Transition | Existing implementation boundary | Authority / evidence | Important limit |
|---|---|---|---|
| Source and allocation statement | PRF canonical source-bound entitlement inputs and pinned successor program | Canonical roots, witness/public values, verifier identity | A locally calculated allocation is not a payment. |
| Prepare operation | `SourceBoundProRataControllerV2.prepareEntitlementOperation` | Prepared operation binds chain, reserve, token, allocator, ratification controller, recipient and roots | A prepared operation does not transfer capital. |
| Assurance admission | `SourceBoundProRataEntitlementAssuranceV1.prepareSourceAssurance` | Configured verifier and bound ratification controller; proof/public values are checked against operation context | Admission is not ratification or settlement. |
| Ratify and fund | `SourceBoundProRataEntitlementRatificationControllerV1.ratifyAndFund` → `OnchainShortfallReserveV3.ratifyAndFundEntitlement` | One bound execution caller; expected live reserve commitment; atomic assurance consumption and reserve postconditions | Failure rolls back the combined ratification/funding transition. |
| Activate | `activateEntitlement` on controller/reserve and `ProductionProRataAllocatorV2` | Funded operation and selected entitlement representation | While Funded and before activation, cancellation/refund is available; after activation, funded capital becomes claim liability. |
| Claim and close | `ProductionProRataAllocatorV2.withdraw` and operation finalization | Per-entitlement consumed state, recipient/amount checks, transfer result and reserve liability checks | Current representation supports one-time full entitlement withdrawal, not partial redemption or expiry/reclaim. |
| Pre-activation unwind | `cancelAndRefundEntitlement` | Bound execution caller; Funded state only | It cannot cancel an activated entitlement. |

The controller and allocator keep separate state machines. The application
ratification controller has `None → Prepared → Ratifying → Funded → Activated
→ Finalized`, with a terminal `CancelledRefunded` branch from `Funded`.
`Ratifying` is a transaction-internal step; a failed assurance, reserve, or
token postcondition reverts the transaction, including assurance consumption.
The allocator enum is `None → Created → Funded → Activated → Claims → Closed`,
with `Funded → Cancelled` before activation. These names are contract states;
they must not be collapsed into one asserted lifecycle field in evidence.

| Call / transition | Who may call | Preconditions and effect | Replay / failure behavior |
|---|---|---|---|
| `prepareEntitlementOperation` | The operation's bound initiator | Unique operation ID, chain ID equals `block.chainid`; stores prepared root and deployment/configuration/proof-profile bindings; no capital reservation. | Duplicate IDs reject; initiator may abandon while Prepared. |
| `prepareSourceAssurance` | Any caller after one-time controller binding | Admission and source adapter validate proof/public values against the prepared operation; stores one assurance and receipt mapping. | Duplicate operation assurance or receipt rejects; invalid proof/binding rejects before usable assurance state. |
| `registerPrepared` | Any caller | Registers only an already-Prepared successor-profile operation into the ratification controller; no custody effect. | Requires controller state None; second registration rejects. |
| `ratifyAndFund` | One-time bound execution-caller contract | Prepared state, current chain and exact reserve/token/allocator/controller/recipient bindings, expected live reserve commitment, matching admitted assurance; transfers exactly available amount to allocator. | Duplicate calls reject after state transition. Any failure reverts the combined assurance consumption, controller transition, reserve accounting and token transfer. |
| `replaceStaleAssurance` | Bound execution caller only | Operation remains Prepared; old assurance is genuinely stale against live reserve commitment; replacement binds current commitment and expected generation. | Repeated stale replacements advance generation; wrong generation, current/non-stale old proof, invalid proof, duplicate receipt, or replacement mismatch rejects without replacing. Unavailable after funding/abandonment/consumption. |
| `activateEntitlement` | Bound execution caller only | Funded operation; deployed representation's logical root, count and total match the admitted tuple. Creates outstanding liability equal to entitlement total. | Duplicate/incorrect activation rejects; cancellation cannot succeed after activation. |
| `withdraw` | Any caller may relay a valid claim | Activated/Claims state; valid table opening, unique index and claim identity; live proof mode plus caller minimum; exact transfer to committed beneficiary. | Index/identity replay, invalid required proof, over-claim, or non-exact token delta reverts consumption and accounting. Any order is allowed. |
| `cancelAndRefundEntitlement` | Bound execution caller only | Funded but not activated; reserve remains the recorded funding source; exact allocator-to-reserve refund. | Terminal Cancelled state; replay rejects. |
| `finalizeProtectedCapital` | Bound execution caller only | Scoped operation Closed; zero allocator liability, no reserve holds, reserve balance exactly equals protected amount. | One-time terminal transfer to immutable refund beneficiary; duplicate call rejects. |

The `PrfStatementAdmission` layer separately binds a sealed genesis,
configuration root/epoch, chain instance, and sealed verifier-registry root
before proof verification. Registry configuration is governed by its configured
governor/authority. The local authority adapter is an authority endpoint, not
an implementation of voting or timelock. Registry registration/activation and
statement admission are not interchangeable with application ratification.

```text
canonical source + policy
  → allocation / successor witness
  → prepared operation
  → proof admission
  → authorized atomic ratify-and-fund
  → activation
  → Alice / Bob / Carol claims
  → allocator closed, liability zero
  → release the separate protected 10 to refundBeneficiary

accepted research contribution
  → reward policy eligibility
  → proposal
  → authorized researcher-reward obligation [unpaid]

approved bounty obligations
  → separate model-only bounty-shortfall allocation
  → payment only if an independently verified settlement exists
```

The last two lines are parallel forensic branches; neither feeds the protected
allocator or reserve in the proposed V0 profile.

In the 80-unit deposit fixture, the reserve excludes 10 units as immutable
`protectedCapital`; 70 units are available for the entitlement funding call.
That 10 is released to the constructor-bound `refundBeneficiary` only by
`finalizeProtectedCapital`, after the scoped allocator operation is `Closed`,
liability is zero, and no reserve amount is reserved. It is therefore a
post-closure restricted release, not an Alice/Bob/Carol claim or part of the
70-unit funded transfer. The separate 30-unit unfilled demand does not enter
reserve custody.

The `prf-capital-lifecycle` package provides a Clojure/Rust transition kernel
and vectors, but its current interface document says it has no runtime
consumer. Its abstract staged `Reserved` / `Funded` transitions are not a
byte-for-byte model of this EVM profile's atomic ratify-and-fund call. Use it as
a semantic comparison and gap inventory, not as evidence that the Solidity
adapter consumes or conforms to every package transition.

## Authority and custody map

| Principal / component | Source-enforced power in this profile | Not established by source alone |
|---|---|---|
| Prepared-operation governance | `SourceBoundProRataControllerV2` sets governance to its deployer; it binds one ratification controller. | A deployer address is not evidence of a public multisig or an approved operator policy. |
| Operation initiator | May prepare only its own operation and may abandon that still-prepared operation. | Initiator identity does not admit the proof or move funds. |
| Assurance governance | Explicit constructor authority; can bind the single ratification controller once. | The authority address's public approval must be separately evidenced. |
| Ratification execution caller | Bound once by prepared-controller governance; must be a nonzero contract. The ratification controller accepts funding/activation/refund calls only from this address. | A local example caller is not a Safe or public governance approval. Its interface and role need to be pinned for each execution. |
| Reserve sponsor | Constructor-bound `sponsor` may deposit exact-transfer ERC-20 amounts. | Token identity, sponsor funding, and deposit receipts require chain-specific observation. |
| Reserve application authority | Constructor-bound; binds ratification authority once and sets the restricted-capital policy root. | No authority may be inferred from the role name; the actual key/multisig and approval are external inputs. |
| Reserve ratification authority | The bound entitlement ratification controller performs operation-scoped ratify/fund, activation, pre-activation refund, and post-closure protected-capital release. | Does not authorize arbitrary external calls or change the immutable sponsor/refund beneficiary. |
| Entitlement recipient | A valid opening names the beneficiary; the transfer is made directly to that address. Any caller may submit a valid withdrawal. | A calculated row alone does not establish recipient receipt; exact balance deltas and transaction context must be observed. |
| Research-reward approval authority | PRF `build-obligation` requires an authorized authority ID/root for the exact eligible proposal. | This source-level authorization field is not an on-chain signer check and does not authorize payment. |

Reserve custody begins at the sponsor deposit. Ratify/fund transfers exactly the
available 70 units to the allocator while the 10 protected units remain in the
reserve. Activation creates the beneficiary liabilities. A successful claim
reduces allocator custody and liability by the same amount. On exact full
closure, `finalizeProtectedCapital` transfers the 10 protected units to the
fixed refund beneficiary. The remaining 30 represents unfilled demand and
never enters custody in this fixture.

Relevant existing EVM test suites are
`test/SourceBoundProRataEntitlementRatificationControllerV1.t.sol`,
`test/ProductionProRataAllocatorV2Withdrawal.t.sol`, and
`test/OnchainShortfallReserveV3.t.sol`. The capital-lifecycle document records
its Foundry test names and commands, but its Clojure/Rust kernels are not
connected to these runtimes. This note maps test/source evidence; it does not
claim those tests were rerun during this documentation change.

Representative source-linked cases include
`test_StaleLiveReserveRollsBackAssuranceAndRatification`,
`test_ExecutionChainMismatchRejectsBeforeAssuranceConsumption`,
`test_ActivationWinsAgainstCancelAndRefund`,
`test_RequiredRejectsMissingAndInvalidButAcceptsValidProof`,
`test_TableOpeningSubstitutionAndDuplicateClaimReplayReject`,
`test_FeeOnTransferRollsBackConsumptionAndAccounting`,
`test_ReleaseRequiresClosedOperationAndZeroLiability`, and
`test_ExactRefundToImmutableBeneficiaryAndReplayBlocked`. They cover selected
local EVM invariants, not the complete cross-repository profile or public
authority policy.

The method and contract mapping above is derived from source, not from names
alone. The focused local lifecycle evidence in `prf-evm/docs/gate-e.md` reports
the 35/21/14 withdrawals and zero final liability for its retained local run.
That evidence is local and source-relative; it does not authenticate public
chain state or establish Base Sepolia readiness.

## Incentives: explicit adjacent flow

V0 includes the incentive state in the forensic account, but does not silently
merge incentive obligations into the beneficiary allocation or its proof.

### Researcher reward obligation

The existing PRF V1 sequence is:

```text
accepted contribution identity
  → policy-bound eligibility
  → reward proposal
  → authorized researcher-reward-obligation.v1
```

`docs/architecture/RESEARCHER_REWARDS.md` explicitly says the obligation is
not payment authority or proof of payment. V1 does not provide funding,
treasury operations, payment, discharge, disputes, or appeals. The in-tree
`packages/prf-incentives` is documented as a source candidate, not an
independently released package. Therefore the V0 demo may link a genuine
authorized reward obligation as a separate forensic subject, but it must label
it **authorized, unpaid** unless a distinct settlement evidence path is
verified. It must not show the obligation as a received payout.

The obligation constructor in
`src/resolver_sim/benchmark/researcher_reward.clj` verifies the exact proposal
and eligibility roots and requires `:authority/authorized?`, an authority ID,
and an authority root. That is a validated semantic input to an immutable
obligation; it is not a signature check or an independently authenticated
authority observation.

### Bounty shortfall allocation

`docs/architecture/bounty-shortfall-allocation-v1.md` describes a separate
model that allocates authenticated model-fixture backing across already
approved bounty obligations. Its conservation equation is:

```text
approved = verified paid + funded unpaid + unfunded liability
```

The current artifact marks EVM qualification `not-verified`; its fixture is not
live reserve evidence. It reuses the PRF pro-rata allocator but does not
approve findings, create the original payable, move funds, or provide an EVM
payout. Full-payment-only settlement is supported by the described settlement
path; partial payment is not.

### V0 inclusion rule

For the 100/80/70 controlled shortfall, researcher rewards and bounty payables
remain outside the 70-unit successor-entitlement claim set and outside the
10-unit restricted refund. A reward can be shown as an adjacent, separately
rooted obligation in the forensic view. No reward payment is executed by this
demo. If a future policy deliberately co-allocates approved bounty claims with
beneficiaries, that decision must happen before source snapshot, allocation,
proof and prepared-operation commitments; the eligible claim set, asset,
reserve, authority, tie-break/rounding, recipient binding and liability rules
must then be explicitly approved and committed. Adding a reward after proof
generation would change the proved operation and is not permitted.

The Sew bounty escrow route is not a shortcut for this integration.
`docs/architecture/sew-bounty-escrow-route-v1.md` labels its adapter model-only;
the inspected `withdrawEscrow` flow aggregates the recipient/workflow claimable
balance and does not attribute the transfer to a bounty category. Existing
`obligation-settlement.v1` covers a direct native-transfer example, not that
bulk Sew withdrawal. A reviewed settlement interface and authenticated
category-level evidence are still required before a Sew withdrawal can count
as payment of a particular bounty obligation.

## Forensic claims and gaps

The evidence view should present separate identities for:

1. Deployment and runtime dependencies.
2. Source snapshot, allocation statement and proof/admission.
3. Prepared operation, authority path, reserve commitment and ratification.
4. Funding, activation, each recipient claim, residual refund and final
   liability state.
5. Any researcher reward eligibility/proposal/authorized obligation, linked
   by its own canonical roots and kept separate from settlement.

Every claim must say whether it is calculated, proven, authorized, observed,
economically reconciled, or still unverified. A reward-obligation root proves
neither a transfer nor a recipient balance change. A test log or a route model
must not be promoted to authenticated payment evidence.

Known V0 limits include local-only execution evidence; no public Base Sepolia
qualification; full-only single claims; no expiry/reclaim of unclaimed
entitlements; and no qualified Sew bounty-to-settlement attribution. The demo
also needs a source-pinned deployment dependency closure and independently
reconstructible transaction/state evidence before it can claim public
qualification.

## Proposed V0 decision record

- **Include incentives in scope:** yes, as a distinct obligation and forensic
  lineage, including the explicit authorized-unpaid state.
- **Count incentives in the Alice/Bob/Carol allocation:** no, for V0.
- **Execute incentive payments in V0:** no.
- **Treat a bounty shortfall model or Sew withdrawal as verified settlement:**
  no.
- **Change existing canonical identities or contract semantics:** no.

These scope choices are recommendations for owner review, not governance or
public deployment approvals.

An independent review should validate the pinned source inventory and
transition map before this snapshot is used as an implementation specification
or public-facing qualification claim.
