# PRF Capital Lifecycle (in-tree package boundary)

This is the PRF integration copy of the pure transition-kernel slice now also
checked into the standalone `prf-capital-lifecycle` repository.
It is not yet a released or pinned dependency, and no PRF, Shortfall, SP1, or
Solidity runtime consumes it as a package.

- `clj/`: Clojure transition implementation.
- `rust/`: Rust transition implementation.
- `test-vectors/`: shared accepted/rejected transition cases consumed by both.

The kernel accepts explicit admission and exact custody outcomes. It does not
perform canonical hashing, derive allocation or source artifacts, verify proofs,
transfer tokens, or change the existing NOW-tranche behavior. Existing frozen
identities and proof public values remain owned by their current implementations.

The current 26 vectors are a first implementation slice, not a complete
substitute for the normative Protected Capital V2 rules. Continue deriving
fixtures from the existing EVM tests before wiring any adapter or extracting an
external repository.

The terminal transition vectors are grounded in:

- `prf-evm/test/SourceBoundProRataEntitlementRatificationControllerV1.t.sol`:
  `test_CancelAndRefundReturnsExactCapitalAndIsTerminal` and
  `test_ActivationWinsAgainstCancelAndRefund`.
- `prf-evm/test/ProductionProRataAllocatorV2Withdrawal.t.sol`:
  `test_RequestedNoProofAndInvalidOptionalProofExecuteAndCloseExactly`.

They assert lifecycle outcomes only; Solidity error selectors and custody
effects remain adapter conformance responsibilities.

The `AllocationBasisPort` and its illustrative adapters are specified in
[`allocation-basis-port-examples-v1.edn`](../../docs/architecture/allocation-basis-port-examples-v1.edn). The EDN examples remain design fixtures with placeholder references. Shared transition vectors now exercise the lifecycle port binding: reservation captures an opaque asset reference, and activation checks opaque basis and representation references, operation scope, asset scope, funded total, and member count. The kernel compares references as values; canonical reference parsing and hashing remain adapter responsibilities.
