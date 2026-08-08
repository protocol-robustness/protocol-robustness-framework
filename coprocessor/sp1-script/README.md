# SP1 host script

This crate is the SP1 host script for the allocation kernel. It builds the
guest program, executes and proves it, verifies the proof, and checks the
three-way equality:

```
PRF public result == native Rust public result == SP1 guest public values
```

## Commands

```bash
# Execute the guest (no proof)
cargo run --release --bin allocation-prove -- --execute --input <input.json>

# Generate and verify a core proof
cargo run --release --bin allocation-prove -- --prove --input <input.json>

# Print the program verification key
cargo run --release --bin allocation-vkey

# Generate an EVM-compatible (groth16/plonk) proof + Solidity fixture
cargo run --release --bin allocation-evm -- --input <input.json> --system groth16
```

The guest program lives in `../sp1-program` and depends on the shared kernel in
`../core`. There is one source of kernel logic: `../core`.

## Proof mode

`cargo run --bin allocation-prove -- --prove` uses the prover configured by the
environment. Set `SP1_PROVER=cpu` for local proof generation, or omit it to use
the default prover. Proofs are verified locally with the SP1 SDK before any
comparison is asserted.

## Environment

The gnark Go build (for groth16/plonk) requires a filesystem with a generous
`NAME_MAX`. On systems where `$HOME` is on ecryptfs (NAME_MAX 143), point the Go
module cache elsewhere, e.g.:

```bash
GOPATH=/tmp/opencode/gopath GOMODCACHE=/tmp/opencode/gopath/pkg/mod
```
