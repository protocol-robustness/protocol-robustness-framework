# On-chain Shortfall Reserve Provenance V2

This additive schema validates economic-state observations from
`OnchainShortfallReserveV2`. It does not alter the historical V1 snapshot or
reserve-observation identities.

## Observation inputs

`prf/onchain-shortfall-reserve-observation.v2` binds the protocol genesis,
sealed chain-instance genesis, execution chain ID, active configuration root
and epoch, operation ID and prepared-operation root, reserve/token/allocator
addresses, reserve economic fields, availability revision, snapshot ID and
creation block. Addresses are semantic deployment observations; they are not
accepted as proof that runtime code is authentic. The deployment verifier must
independently bind them to its manifest and observed runtime identities.

The reserve state commitment is exactly:

```text
keccak256(abi.encode(
  keccak256("prf.economic-reserve-state.v2"),
  preparedOperationRoot,
  bytes32(uint256(uint160(reserve))),
  bytes32(uint256(uint160(token))),
  grossBalance,
  reservedBefore,
  protectedCapital,
  availableAmount,
  availabilityRevision
))
```

The operation's chain ID and configuration epoch use the prepared-operation
schema's `uint128` widths. Reserve balances and amounts are `uint128`,
availability revision and snapshot ID are `uint64`, and creation block is
`uint256`; the builder enforces these widths before ABI encoding.

The authoritative observation root is exactly:

```text
keccak256(abi.encode(
  keccak256("prf.authoritative-reserve-observation.v2"),
  reserveStateCommitment,
  snapshotId,
  createdAtBlock
))
```

The Clojure builder independently recomputes both Solidity commitments,
requires `availableAmount = grossBalance - reservedBefore - protectedCapital`,
and requires positive availability. It then derives the PRF SHA-256 observation
root over the closed semantic artifact including the instance, C1 and operation
bindings. The Solidity observation root is a field in that PRF artifact; it is
not substituted for the PRF artifact root.

`prf/onchain-shortfall-reserve-binding.v2` validates that observation and a
`prf/shortfall-context.v2` whose source, availability root, amount and asset
match, then binds the Shortfall context to the operation, chain instance and C1
configuration identity.

## Conformance and limits

The pinned vector in `test/resolver_sim/shortfall/provenance_v2_test.clj` was
independently recomputed with viem's ABI encoder and Keccak implementation for
the exact fixed-width Solidity fields. This proves encoding agreement for that
vector; it is not a proof that an RPC response was authoritative. Qualification
must retain the block hash, RPC observation, contract addresses, runtime hashes
and transaction/readback evidence, and the W7 verifier must replay those
bindings. The schema does not assert proof validity, token authenticity,
governance approval or capital-operation authorization.
