# PRF Capital Lifecycle (in-tree package boundary)

This is the first pure transition-kernel slice for the proposed future
`prf-capital-lifecycle` repository. It is not yet a released package, and no
PRF, Shortfall, SP1, or Solidity runtime consumes it.

- `clj/`: Clojure transition implementation.
- `rust/`: Rust transition implementation.
- `test-vectors/`: shared accepted/rejected transition cases consumed by both.

The kernel accepts explicit admission and exact custody outcomes. It does not
perform canonical hashing, derive allocation or source artifacts, verify proofs,
transfer tokens, or change the existing NOW-tranche behavior. Existing frozen
identities and proof public values remain owned by their current implementations.

The current 18 vectors are a first implementation slice, not a complete
substitute for the normative Protected Capital V2 rules. Continue deriving
fixtures from the existing EVM tests before wiring any adapter or extracting an
external repository.
