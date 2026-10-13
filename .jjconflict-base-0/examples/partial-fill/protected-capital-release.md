# Partial Fill protected-capital release: gap assessment

## Finding

The controlled fixture qualifies allocator closure, not final reserve
settlement. After Alice, Bob and Carol withdraw 35, 21 and 14 units, the
allocator liability is zero and the entitlement operation is `Closed`. The
reserve still holds 10 units. The fixture policy declares Carol the beneficial
owner and refund beneficiary, but that declaration is not enforced by the
deployed reserve code.

`OnchainShortfallReserveV2` has an immutable `protectedCapital` amount and
subtracts it from allocatable capacity. It has no protected-capital release
entry point, no terminal/finalized state, no Carol beneficiary, and no binding
between the policy record and its reserve commitment. Its deposit and
ratification methods remain callable after allocator closure. Its reserve
methods can serve multiple operation IDs, so `totalReserved == 0` alone does
not establish that the intended operation is the only operation relevant to
finalization.

Adding a release function only in a subclass would not close these gaps: the
inherited V2 methods would still permit deposits and new ratifications after
the subclass marks itself finalized. Modifying V2 in place would change its
existing semantics and commitments. Neither approach is suitable for the
frozen V2 fixture.

## Current qualification boundary

| Requirement | Status | Evidence or gap |
|---|---|---|
| Local sponsor minted and transferred 80 test units | Observed locally | ERC-20 `Transfer` and reserve `Deposited` logs in the controlled test |
| Policy assigns 10 units to Carol as restricted refundable sponsor capital | Declared by fixture | Versioned policy JSON and policy root; it is not a fact inferred from token transfer |
| 70 units fund claimant entitlements | Verified in local EVM path | Source-bound proof admission, ratification, activation and 35/21/14 withdrawals |
| Allocator liability reaches zero and operation closes | Verified in local EVM path | Retained `closed` snapshot |
| Carol owns a claim to the 10 in contract-enforced state | Missing | V2 does not store or bind owner/purpose/release policy |
| Early release, wrong beneficiary and double release reject | Missing | No release route exists to exercise these cases |
| Other scoped operations and pending reserve holds block finalization | Missing | V2 is not scoped to one operation and has no terminal gate |
| Exact 10-unit refund to Carol and final reserve state | Missing | No supported transfer path; must not be simulated |
| Public-chain settlement | Not established | This is a local Foundry execution only |

The existing negative test that confirms V2 has no terminal refund entry point
is a capability boundary check. It is not evidence that an implemented release
path rejects invalid release attempts.

## Bounded successor V3 design (locally qualified below)

The additive V3 implementation preserves V2's frozen behavior. The fixture
does not need a general multi-operation reserve. V3 binds one operation and one
protected-capital policy under these constraints:

1. Bind a versioned policy before funding. Its commitment must include chain ID,
   reserve and token identities, scoped operation ID, policy revision, sponsor
   and funding attribution, restricted amount, Carol's beneficiary address,
   permitted purpose, finalization authority and terminal conditions.
2. Enforce one scoped entitlement operation. Reject all other operation IDs
   and unsupported legacy ratification paths. Bind the policy root into the
   successor's reserve-state commitment so the source-bound assurance covers
   the same policy. This closes admission to additional work by construction;
   finalization must also permanently disable every admission and funding route.
3. Preserve the active invariant that protected capital is unavailable to
   allocation. Require the scoped allocator operation to be `Closed`, its
   outstanding liability to be zero, and all reserve holds to be terminal.
   Also require the exact protected amount to remain in reserve custody at
   finalization.
4. Preserve active claimant rights. Do not finalize while the scoped entitlement
   operation is other than `Closed` or its outstanding liability is nonzero.
   Keep existing withdrawal paths available until those conditions hold. Do not
   alter activated entitlements or redirect claimant payments as part of reserve
   finalization.
5. Use **controller-authorized finalization** for this fixture because the
   adopted policy names the bound ratification controller as release authority.
   The contract fixes the beneficiary and amount, so the controller has no
   discretion to redirect funds. A permissionless trigger would require an
   explicit policy revision and is not part of this proposal.
6. In one atomic, non-reentrant call, set the terminal/replay-protection state
   before transferring exactly 10 units to the immutable Carol address; verify
   exact token balance deltas and emit an event containing the policy root,
   operation ID, beneficiary and amount.
7. Reject later deposits, reservations, ratification, cancellation/refunds and
   any second finalization after the terminal state. The controller must never
   choose or substitute the beneficiary.
8. Add local-EVM tests for early release, wrong beneficiary, double release,
   unresolved claimant liability, an unresolved/active hold, unrelated
   operation IDs, unauthorized finalizer and exact transfer. For every rejected
   path, assert token balances and operation/finalization state are unchanged.
9. Regenerate source-bound proofs and reserve commitments against this new
   reserve version and policy root. Re-run the successful claim lifecycle and
   retain a separate post-release snapshot. Keep the existing seven snapshots
   unchanged, labeling allocator closure separately from reserve settlement.

The current [journal generator](accounting-journal.py) derives a scoped event
projection from the seven retained observations and transfer evidence. The
snapshots remain primary. The generated journal records the 10 as unresolved
restricted custody; Carol's attribution is declared by fixture policy and is
neither independently proven nor contract-enforced. It does not claim complete
economic accounting. The V3 release is now qualified in a separate local run;
its terminal observation and exact refund event are retained under
`evidence/v3-live-context/`. The original seven V2 records remain unchanged,
and the V3 terminal result is not represented as an eighth state in that V2
sequence.

## Current qualification status (local V3 execution)

The bounded V3 path is now exercised end to end in the owning `prf-evm`
checkout. The V3 reserve binds a single entitlement operation and the versioned
restricted-capital policy, including sponsor, beneficiary, purpose and funding
attribution roots. The source-bound proof was admitted under the retained
configuration and verifier registry, ratified through the bound controller,
used to fund 70 units, and followed by 35/21/14 withdrawals. After allocator
closure and zero claimant liability, the controller's scoped finalization route
released exactly 10 units to the immutable Carol beneficiary. Finalization
blocks replay and later reserve activity.

The controller has an additive `finalizeProtectedCapital()` forwarding route.
It reads the operation ID from the V3 reserve itself, requires that controller
operation to be `Activated`, and then invokes the reserve. The reserve enforces
`Closed`, zero liability, no active holds and exact protected balance before
atomically transferring the fixed amount. The execution caller cannot provide
or substitute a beneficiary.

The original seven V2 observations and their journal are unchanged. The V3
post-release observation is retained separately, because it comes from a
separate local Foundry run. See the [V3 live-context evidence](evidence/v3-live-context/README.md)
for its terminal observation, transfer trace, proof artifacts, code snapshots,
manifest and test logs.

### Validation

Stage-specific source-bound qualification and adversarial checks:

```sh
PARTIAL_FILL_STAGE=2 forge test --match-path test/PartialFillV3Controlled.t.sol \
  --match-test 'test_(Qualification|Reject)' --offline -vv
```

Result: 10 passed. The V3 reserve guard and terminal-release tests:

```sh
forge test --match-path test/OnchainShortfallReserveV3.t.sol --offline -vv
```

Result: 8 passed. Adjacent V2 reserve, source-bound ratification-controller and
withdrawal regression suites also passed (32 tests total). Frozen V2 source and
commitments were not changed.

### Remaining limits

These are local Foundry results on chain ID 31337, not a Base Sepolia deployment
or public-chain execution. Carol's designated beneficiary is enforced by the
local V3 policy and code, but its legal/beneficial basis is the fixture policy
and local funding attribution, not independent external evidence. Stale V3
assurance is rejected without state mutation; recovery with a newly generated
V3 proof has not yet been qualified. The accounting journal remains scoped to
the original seven V2 states; the separate V3 terminal observation has not been
folded into a unified journal. Neither artifact establishes the completeness of
all external obligations or a universal financial ledger.
