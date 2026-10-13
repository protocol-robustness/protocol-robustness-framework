# Next phase: real SP1 proving + local Solidity verification

## Scope

The next phase adds real SP1 proof generation and local on-chain proof
verification. It must still exclude host-integration work and the complete
allocation coordinator lifecycle.

In scope:

- Install SP1 (`sp1up` + toolchain).
- Compile the allocation kernel as a real SP1 guest using SP1 guest entry
  macros (`sp1_main!`).
- Generate proofs locally (and, for non-trivial programs, SP1's prover
  network).
- Generate the EVM verifier for the allocation kernel program.
- Wire `SP1AllocationProofVerifier` to the generated verifier behind
  `IAllocationProofVerifier`.
- Verify the SP1 proof through the generated Solidity verifier locally
  (anvil/forge).

Out of scope until the proof boundary is independently green:

- Host-system integration (adapter, `InputsComplete` workflow);
- the complete `AllocationCoordinator` lifecycle (freeze, randomness request,
  result activation, double-consumption guard, custody);
- cancellation and post-randomness recovery;
- Kafka, MongoDB, IPFS, or blob publication;
- production randomness and production key management.

## Gate

The phase is complete only when both of the following hold:

```
PRF public result
==
native Rust public result
==
SP1 guest public values
```

and

```
SP1 proof verifies through the generated Solidity verifier
```

## Migration path

The SP1 guest/host skeletons in `coprocessor/sp1-program` and
`coprocessor/sp1-host` are plain-Rust today and depend on the shared
`allocation-kernel` core. The next phase:

1. Adds the SP1 guest dependency to `allocation-sp1-program`, wraps the shared
   kernel entry point with `sp1_main!`, and commits the same public values.
2. Replaces the mock prover in `allocation-sp1-host` with the real SP1
   `ProverClient`.
3. Adds `scripts/prove.sh` that produces a Groth16/PLONK proof and the generated
   verifier contract.
4. Adds `contracts/test/SP1AllocationProofVerifier.t.sol` gating proof
   verification, replacing `MockAllocationProofVerifier` in the verification
   path.
5. Extends `scripts/conformance/conformance.sh` with the third equality (SP1 guest public
   values) and the on-chain verification check.

The certificate's `:proof` fields and any `:zk-proof` assurance
classifications must remain `:not-yet-evaluated` until the on-chain proof gate
is green.
