# Local EVM reservation / protected-capital demo

Question: can an obligation reserve capital without moving custody, preserve the
protected floor, release exactly once, and reject an operation prepared against
stale availability even when the balances return to their original values?

Run from the PRF checkout, with the companion Solidity source checkout and
installed Foundry tools (`forge`, `cast`, `anvil`), Git, Python 3, and the
companion's pinned Solidity compiler already installed:

```bash
python3 scripts/demos/protected_capital_reservation.py \
  --evm-repo /path/to/prf-evm \
  --report /tmp/reservation-demo.json
```

The report is optional; an existing report path is rejected. Compilation copies
the required current companion Solidity sources, including uncommitted changes,
into a temporary Foundry root. The shim contains only the imported `IERC20`
interface and a small mintable test token. It does not need the companion's
OpenZeppelin, forge-std, or SP1 submodules. No dependency download is attempted.
Build artifacts and cache stay in the temporary directory. The script starts
its own loopback Anvil instance, uses its unlocked test account, and shuts the
node down when finished. It never connects to a pre-existing chain. The
companion checkout is read only during this demo.

## Presentation sequence

| Action | Gross custody | Protected | Reserved | Available | Revision |
|---|---:|---:|---:|---:|---:|
| **Baseline after deposit 100** | **100** | **10** | **0** | **90** | **0** |
| Attempt to reserve 91: rejected | 100 | 10 | 0 | 90 | 0 |
| Reserve 20 | 100 | 10 | 20 | 70 | 1 |
| Attempt stale operation: rejected | 100 | 10 | 20 | 70 | 1 |
| Release 20 | 100 | 10 | 0 | 90 | 2 |
| Reuse ID or release twice: rejected | 100 | 10 | 0 | 90 | 2 |
| Attempt original stale operation again: rejected | 100 | 10 | 0 | 90 | 2 |

The baseline snapshot and revision-0 commitment are saved explicitly in the
report. The final rejection demonstrates why revision belongs in the reserve commitment:
equal balances do not mean the original commitment has become current again.
Each rejected action checks the exact custom-error selector with `eth_call`,
then sends a transaction and requires a mined reverted receipt with no retained
logs. The complete observed reserve/custody state must match before and after.
Snapshots cover reservation state, allocator operation, allowance, snapshot
counter, and ratification flag as well as balances and revision.

The V2 creation call supplies an immutable deadline one day after the baseline.
The reservation getter records state, amount, and deadline. This presentation
releases the hold before its deadline; permissionless expiry and controller
scope substitutions are covered by the companion's focused tests.

## Evidence and claim boundary

This executes the production `OnchainShortfallReserveV2` and
`ProductionProRataAllocatorV2` contracts with the local `ReservationDemoToken`
test ERC-20. Prepared-operation and allocation roots are explicitly synthetic
fixtures. The stale requests stop at the live commitment comparison. There is
no proof admission, successful allocator funding, entitlement activation,
withdrawal, closure, or Assured Operations dispatcher qualification in this demo.

The optional JSON report is local execution evidence: it records the companion
HEAD and dirty status, hashes for every copied companion source and compiled
creation bytecode, deployed addresses, state snapshots, and transaction
hashes/statuses. It is an ordinary demo report, independent of the proposed
Canonical Artifact Registry. Temporary chain history disappears when the
script exits; this is a locally repeatable demonstration rather than
retained-chain forensic reproduction.

The registry remains proposed and paused. Generic `AllocationBindingV1` remains
specified/unregistered. Prepared-operation domain reuse and reserve-observation
compatibility must be reviewed before extending this demo to a cross-language
proof path. The earlier registry inventory describes its inspection snapshot;
the current checkout has successor decoding and activation, but no allocator
cancel-and-refund path. That remains a blocker for a cancellation demo.

Supported reserve assets must be exact-transfer, non-rebasing ERC-20s with
conventional `balanceOf` semantics. Exact-delta checks enforce transfer
postconditions; they do not certify an arbitrary token's future behavior.
During bootstrap, deposits below the protected floor are allowed, but
reservation and both funding routes reject. Once operational,
`gross >= protected + reserved` and `available = gross - protected - reserved`.

## Recorded local run

On 2026-10-06, the dependency independent build and full sequence passed against
the companion source checkout, without using its OpenZeppelin or forge-std
submodules. All five negative cases produced mined reverted transactions with
the expected custom errors and unchanged observed state. The final state was
gross 100, protected 10, reserved 0, available 90, revision 2, with zero
allocator balance and allowance. The retained local report was
`/tmp/prf-reservation-demo-no-deps-20261006.json`; reports are not checked in.
