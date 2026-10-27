# Bounty shortfall allocation v1

`resolver-sim.economics.bounty-shortfall` models policy-bound allocation of
authenticated reserve backing across already-approved bounty obligations. It
does not approve research findings, establish a payable by itself, move funds,
or operate a payout rail.

## Amounts kept distinct

For each approved obligation, the artifact keeps these quantities separate:

- **approved**: the original `bounty-payable.v1` amount and root;
- **cumulative allocation**: the portions assigned to backed child payables;
- **verified paid**: amounts accepted through the canonical
  `obligation-settlement.v1` verifier;
- **funded unpaid**: cumulative allocation minus verified payment;
- **unfunded liability**: approved amount minus cumulative allocation;
- **outstanding liability**: approved amount minus verified payment.

The conservation identity is:

```text
approved = verified paid + funded unpaid + unfunded liability
```

For the retained two-obligation fixture, Alice is approved for 6,000 and Bob
for 4,000. With 6,000 of authenticated model-fixture backing, largest-remainder
allocation funds 3,600 for Alice and 2,400 for Bob. Before any payout, the
ledger therefore reports 6,000 funded unpaid, 4,000 unfunded liability, and
10,000 total outstanding liability. A later 4,000 recovery can fund the
remaining 2,400 and 1,600; it does not make the 10,000 outstanding liability
zero. Only verified payments reduce that total.

Funding a claim does not discharge it. A recovery allocation may fund only the
still-unfunded portion; it preserves earlier funded claim records in
`allocation/funded-claims-history`. A failed or absent payout therefore leaves
the funded claim in `funded unpaid`. The `allocation/liabilities` map is the
total outstanding amount, while `allocation/unfunded-liabilities` is only the
portion eligible for a later allocation.

## Policy and replay

`build-policy` produces `bounty-shortfall-policy.v1`, binding an authority,
policy version and parent, token, authenticated funding snapshot, eligible
claims, ratio, largest-remainder rounding, deterministic claim-ID tie-break,
fee treatment, full-payment-only settlement, retained residual liability, and
recovery rule. A successor policy requires its predecessor allocation and a
new funding source. Reused event IDs or funding source roots, changed approved
obligations, stale snapshots, duplicate reservations, unauthorized policy, and
amount overcommitment fail closed.

`allocate` replays the shared PRF pro-rata allocator. It creates a backed
child payable and backing artifact for each positive allocation; it does not
create or authenticate the original approved obligation. `verify` recomputes
the entire allocation from its source inputs and also invokes the exact
weighted-proportionality verifier. `verify-funded-settlements` delegates each
full payout to the existing `obligation-settlement.v1` verifier and binds the
settlement to the specific child payable, backing, original obligation,
policy, event, asset, and source root. It does not implement a second receipt
or chain-observation verifier.

## Current evidence scope

The retained tests use deterministic local fixture signers and a synthetic
chain-observation fixture to exercise source, policy, replay, accounting, and
settlement bindings. The allocation artifact marks itself `model-only` and
`evm-qualification: not-verified`; those fixtures are not an external funding
authority, live reserve observation, EVM payout, or public-chain evidence.
Partial payment and residual-liability extinguishment are unsupported. A
settlement v1 record accepts a full payment only. Any extension requires a
reviewed versioned interface change to the canonical settlement layer.

The current recovery artifact retains all funding-round claim/backing records
and cumulative verified payment totals. It does not by itself prove that an
external reserve still holds the amounts represented by a prior funding
statement; that requires a fresh authenticated source statement for each
recovery.
