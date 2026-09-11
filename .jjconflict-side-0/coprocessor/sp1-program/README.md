# SP1 guest

This crate is the real SP1 guest for the allocation kernel.

## Current state

The guest:

- reads the canonical allocation input document (raw bytes via
  `sp1_zkvm::io::read_vec`);
- runs the shared `allocation-kernel` core (`../core`);
- commits the public-value projection as the program's public values
  (`sp1_zkvm::io::commit_slice`).

The committed public values are the same canonical JSON projection the native
Rust kernel and the PRF JAR produce. There is exactly one source of kernel
logic: `../core`.

## Proof generation

Real proof generation happens in the host script (`../sp1-script`):
`cargo run --release --bin allocation-prove -- --prove`. EVM-compatible proof
generation: `cargo run --release --bin allocation-evm`.

## Deferred (documented, not implemented)

- SP1 proof-network integration;
- on-chain proof acceptance in a full coordinator lifecycle (the coordinator is
  still out of scope);
- production randomness and key management.
