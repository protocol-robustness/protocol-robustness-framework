# Extracted pro-rata allocation

The portable allocator is maintained in the separate
[`prf-pro-rata-allocation` repository](https://github.com/protocol-robustness/prf-pro-rata-allocation/tree/cf38de1ea4ef03654ee3de8bead56c32ef078b10).
PRF consumes it from the immutable Git revision declared in the root
[`deps.edn`](../../deps.edn). This directory is a migration pointer; it does not
contain a second Clojure or Rust implementation.

PRF's pin was reconciled to `cf38de1ea4ef03654ee3de8bead56c32ef078b10` on
2026-10-30, matching the published allocation repository `main`. This additive
revision includes shared candidate deployment-scoped claim vectors and the
candidate claim profile implementation. That profile remains unadopted and is
not used by PRF consumers. The package release metadata still reports partial
overall conformance; release, SP1, and production Solidity proof qualification
have not been promoted.

PRF retains host execution, progress, protocol composition, application policy,
evidence binding, EVM integration, lifecycle transitions, and proof assurance.
