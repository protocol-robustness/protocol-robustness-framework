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

## Bounded successor requirements

Implement a new reserve version rather than changing V2's frozen behavior. The
successor used for this fixture should:

1. Bind a versioned policy before funding. Its commitment must include chain ID,
   reserve and token identities, scoped operation ID, policy revision, sponsor
   and funding attribution, restricted amount, Carol's beneficiary address,
   permitted purpose, finalization authority and terminal conditions.
2. Enforce one scoped entitlement operation. Reject all other operation IDs
   and unsupported legacy ratification paths. Bind the policy root into the
   successor's reserve-state commitment so the source-bound assurance covers
   the same policy.
3. Preserve the active invariant that protected capital is unavailable to
   allocation. Require the scoped allocator operation to be `Closed`, its
   outstanding liability to be zero, and all reserve holds to be terminal.
   Also require the exact protected amount to remain in reserve custody at
   finalization.
4. Permit only the bound ratification controller to finalize. In one atomic,
   non-reentrant call, set the terminal state before transferring exactly 10
   units to the immutable Carol address, verify exact token balance deltas, and
   emit an event containing policy root, operation ID, beneficiary and amount.
5. Reject later deposits, reservations, ratification, cancellation/refunds and
   any second finalization after the terminal state. The controller must never
   be able to choose or substitute a beneficiary.
6. Add local-EVM tests for early release, wrong beneficiary, double release,
   unresolved claimant liability, an unresolved/active hold, unrelated
   operation IDs, unauthorized finalizer and exact transfer. For every rejected
   path, assert token balances and operation/finalization state are unchanged.
7. Regenerate source-bound proofs and reserve commitments against this new
   reserve version and policy root. Re-run the successful claim lifecycle and
   retain a separate post-release snapshot. Keep the existing seven snapshots
   unchanged, labeling allocator closure separately from reserve settlement.

Do not derive the canonical accounting journal yet. Once the successor passes
these checks, derive journal entries from the retained observed states,
including the final reserve release; snapshots remain primary evidence.

## Verification command and expected present result

Run the controlled tests in `prf-evm` with its retained fixture and stage set:

```sh
PARTIAL_FILL_STAGE=2 forge test --match-path test/PartialFillControlled.t.sol \
  --match-test 'test_(Qualification|Reject)' --offline -vv
```

The existing suite exercises local proof admission, funding, activation,
withdrawal, closure and rejection paths. It does not qualify protected-capital
release, because V2 has no such route. These results remain local-EVM evidence;
they do not establish Base Sepolia execution.
