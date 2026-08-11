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

# Prove the realized-allocation statement, locally verify it, compare its exact
# public JSON bytes to native Rust, and persist an unsigned proof artifact.
cargo run --release --bin realized-statement-prove -- --prove \
  --input ../../scenarios/allocation/a-vs-b-plus-c/realized-statement-input.json \
  --artifact ../../results/allocation/a-vs-b-plus-c/realized-statement/sp1-proof-artifact.json

# Generate and verify a core proof
cargo run --release --bin allocation-prove -- --prove --input <input.json>

# Print the program verification key
cargo run --release --bin allocation-vkey

# Generate an EVM-compatible (groth16/plonk) proof + Solidity fixture
cargo run --release --bin allocation-evm -- --input <input.json> --system groth16
```

The guest program lives in `../sp1-program` and depends on the shared kernel in
`../core`. There is one source of kernel logic: `../core`.

## Reproducible realized-proof target

Run the repository target rather than relying on a machine-global writable
Cargo registry/cache:

```bash
make allocation-realized-proof
# or choose an artifact destination:
./scripts/prove-realized-statement.sh results/allocation/custom/sp1-proof-artifact.json
```

It uses ignored project-local `.sp1-proof-cache/` Cargo, target, and Go module
cache directories. Those paths affect build caching only; the persisted proof
artifact records the proof profile, Rust-visible guest ELF digest, VK, exact
public bytes, and proof bytes/digests. A fresh cache requires the normal Cargo
package acquisition available in the supported CI/proving environment.

## Proof mode

`cargo run --bin allocation-prove -- --prove` uses the prover configured by the
environment. Set `SP1_PROVER=cpu` for local proof generation, or omit it to use
the default prover. Proofs are verified locally with the SP1 SDK before any
comparison is asserted.

## Environment

The core CPU prover may build SP1's native executor binary. Its Cargo-registry
source/cache must be writable for that build (a read-only dependency cache will
fail while creating `sp1-native-bins/.../.cargo-build-lock`). This is a build
cache requirement and does not change the guest ELF, verification key, or proof
identity.

The gnark Go build (for groth16/plonk) requires a filesystem with a generous
`NAME_MAX`. On systems where `$HOME` is on ecryptfs (NAME_MAX 143), point the Go
module cache elsewhere, e.g.:

```bash
GOPATH=/tmp/opencode/gopath GOMODCACHE=/tmp/opencode/gopath/pkg/mod
```
